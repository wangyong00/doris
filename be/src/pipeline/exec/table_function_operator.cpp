// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

#include "table_function_operator.h"

#include <memory>

#include "pipeline/exec/operator.h"
#include "vec/core/block.h"
#include "vec/exprs/table_function/table_function_factory.h"

namespace doris {
#include "common/compile_check_begin.h"
class RuntimeState;
} // namespace doris

namespace doris::pipeline {

TableFunctionLocalState::TableFunctionLocalState(RuntimeState* state, OperatorXBase* parent)
        : PipelineXLocalState<>(state, parent), _child_block(vectorized::Block::create_unique()) {}

Status TableFunctionLocalState::init(RuntimeState* state, LocalStateInfo& info) {
    RETURN_IF_ERROR(PipelineXLocalState<>::init(state, info));
    SCOPED_TIMER(exec_time_counter());
    SCOPED_TIMER(_init_timer);
    _init_function_timer = ADD_TIMER(custom_profile(), "InitTableFunctionTime");
    _process_rows_timer = ADD_TIMER(custom_profile(), "ProcessRowsTime");
    _filter_timer = ADD_TIMER(custom_profile(), "FilterTime");
    return Status::OK();
}

Status TableFunctionLocalState::_clone_table_function(RuntimeState* state) {
    auto& p = _parent->cast<TableFunctionOperatorX>();
    _vfn_ctxs.resize(p._vfn_ctxs.size());
    for (size_t i = 0; i < _vfn_ctxs.size(); i++) {
        RETURN_IF_ERROR(p._vfn_ctxs[i]->clone(state, _vfn_ctxs[i]));

        vectorized::TableFunction* fn = nullptr;
        RETURN_IF_ERROR(vectorized::TableFunctionFactory::get_fn(
                _vfn_ctxs[i]->root()->fn(), state->obj_pool(), &fn, state->be_exec_version()));
        fn->set_expr_context(_vfn_ctxs[i]);
        _fns.push_back(fn);
    }
    return Status::OK();
}

Status TableFunctionLocalState::open(RuntimeState* state) {
    SCOPED_TIMER(PipelineXLocalState<>::exec_time_counter());
    SCOPED_TIMER(PipelineXLocalState<>::_open_timer);
    RETURN_IF_ERROR(PipelineXLocalState<>::open(state));
    RETURN_IF_ERROR(_clone_table_function(state));
    for (auto* fn : _fns) {
        RETURN_IF_ERROR(fn->open());
    }
    _cur_child_offset = -1;
    return Status::OK();
}

void TableFunctionLocalState::_copy_output_slots(
        std::vector<vectorized::MutableColumnPtr>& columns) {
    if (!_current_row_insert_times) {
        return;
    }
    auto& p = _parent->cast<TableFunctionOperatorX>();
    for (auto index : p._output_slot_indexs) {
        auto src_column = _child_block->get_by_position(index).column;
        columns[index]->insert_many_from(*src_column, _cur_child_offset, _current_row_insert_times);
    }
    _current_row_insert_times = 0;
}

// Returns the index of fn of the last eos counted from back to front
// eg: there are 3 functions in `_fns`
//      eos:    false, true, true
//      return: 1
//
//      eos:    false, false, true
//      return: 2
//
//      eos:    false, false, false
//      return: -1
//
//      eos:    true, true, true
//      return: 0
//
// return:
//  0: all fns are eos
// -1: all fns are not eos
// >0: some of fns are eos
int TableFunctionLocalState::_find_last_fn_eos_idx() const {
    for (int i = _parent->cast<TableFunctionOperatorX>()._fn_num - 1; i >= 0; --i) {
        if (!_fns[i]->eos()) {
            if (i == _parent->cast<TableFunctionOperatorX>()._fn_num - 1) {
                return -1;
            } else {
                return i + 1;
            }
        }
    }
    // all eos
    return 0;
}

// Roll to reset the table function.
// Eg:
//  There are 3 functions f1, f2 and f3 in `_fns`.
//  If `last_eos_idx` is 1, which means f2 and f3 are eos.
//  So we need to forward f1, and reset f2 and f3.
bool TableFunctionLocalState::_roll_table_functions(int last_eos_idx) {
    int i = last_eos_idx - 1;
    for (; i >= 0; --i) {
        _fns[i]->forward();
        if (!_fns[i]->eos()) {
            break;
        }
    }
    if (i == -1) {
        // after forward, all functions are eos.
        // we should process next child row to get more table function results.
        return false;
    }

    for (int j = i + 1; j < _parent->cast<TableFunctionOperatorX>()._fn_num; ++j) {
        _fns[j]->reset();
    }

    return true;
}

bool TableFunctionLocalState::_is_inner_and_empty() {
    for (int i = 0; i < _parent->cast<TableFunctionOperatorX>()._fn_num; i++) {
        // if any table function is not outer and has empty result, go to next child row
        // if it's outer function, will be insert into one row NULL
        if (!_fns[i]->is_outer() && _fns[i]->current_empty()) {
            return true;
        }
    }
    return false;
}

Status TableFunctionLocalState::get_expanded_block(RuntimeState* state,
                                                   vectorized::Block* output_block, bool* eos) {
    auto& p = _parent->cast<TableFunctionOperatorX>();
    vectorized::MutableBlock m_block = vectorized::VectorizedUtils::build_mutable_mem_reuse_block(
            output_block, p._output_slots);
    vectorized::MutableColumns& columns = m_block.mutable_columns();

    for (int i = 0; i < p._fn_num; i++) {
        if (columns[i + p._child_slots.size()]->is_nullable()) {
            _fns[i]->set_nullable();
        }
    }
    SCOPED_TIMER(_process_rows_timer);
    while (columns[p._child_slots.size()]->size() < state->batch_size()) {
        RETURN_IF_CANCELLED(state);

        if (_child_block->rows() == 0) {
            break;
        }

        bool skip_child_row = false;
        while (columns[p._child_slots.size()]->size() < state->batch_size()) {
            int idx = _find_last_fn_eos_idx();
            if (idx == 0 || skip_child_row) {
                _copy_output_slots(columns);
                // all table functions' results are exhausted, process next child row.
                process_next_child_row();
                if (_cur_child_offset == -1) {
                    break;
                }
            } else if (idx < p._fn_num && idx != -1) {
                // some of table functions' results are exhausted.
                if (!_roll_table_functions(idx)) {
                    // continue to process next child row.
                    continue;
                }
            }

            // if any table function is not outer and has empty result, go to next child row
            if (skip_child_row = _is_inner_and_empty(); skip_child_row) {
                continue;
            }

            DCHECK_LE(1, p._fn_num);
            auto repeat_times = _fns[p._fn_num - 1]->get_value(
                    columns[p._child_slots.size() + p._fn_num - 1],
                    //// It has already been checked that
                    // columns[p._child_slots.size()]->size() < state->batch_size(),
                    // so columns[p._child_slots.size()]->size() will not exceed the range of int.
                    state->batch_size() - (int)columns[p._child_slots.size()]->size());
            _current_row_insert_times += repeat_times;
            for (int i = 0; i < p._fn_num - 1; i++) {
                _fns[i]->get_same_many_values(columns[i + p._child_slots.size()], repeat_times);
            }
        }
    }

    _copy_output_slots(columns);

    size_t row_size = columns[p._child_slots.size()]->size();
    for (auto index : p._useless_slot_indexs) {
        columns[index]->insert_many_defaults(row_size - columns[index]->size());
    }

    {
        SCOPED_TIMER(_filter_timer); // 3. eval conjuncts
        RETURN_IF_ERROR(vectorized::VExprContext::filter_block(_conjuncts, output_block,
                                                               output_block->columns()));
    }

    *eos = _child_eos && _cur_child_offset == -1;
    return Status::OK();
}

void TableFunctionLocalState::process_next_child_row() {
    _cur_child_offset++;

    if (_cur_child_offset >= _child_block->rows()) {
        // release block use count.
        for (vectorized::TableFunction* fn : _fns) {
            fn->process_close();
        }

        _child_block->clear_column_data(_parent->cast<TableFunctionOperatorX>()
                                                ._child->row_desc()
                                                .num_materialized_slots());
        _cur_child_offset = -1;
        return;
    }

    for (vectorized::TableFunction* fn : _fns) {
        fn->process_row(_cur_child_offset);
    }
}

TableFunctionOperatorX::TableFunctionOperatorX(ObjectPool* pool, const TPlanNode& tnode,
                                               int operator_id, const DescriptorTbl& descs)
        : Base(pool, tnode, operator_id, descs) {}

Status TableFunctionOperatorX::_prepare_output_slot_ids(const TPlanNode& tnode) {
    // Prepare output slot ids
    SlotId max_id = -1;
    for (auto slot_id : tnode.table_function_node.outputSlotIds) {
        if (slot_id > max_id) {
            max_id = slot_id;
        }
    }
    _output_slot_ids = std::vector<bool>(max_id + 1, false);
    for (auto slot_id : tnode.table_function_node.outputSlotIds) {
        _output_slot_ids[slot_id] = true;
    }

    return Status::OK();
}

Status TableFunctionOperatorX::init(const TPlanNode& tnode, RuntimeState* state) {
    RETURN_IF_ERROR(Base::init(tnode, state));

    for (const TExpr& texpr : tnode.table_function_node.fnCallExprList) {
        vectorized::VExprContextSPtr ctx;
        RETURN_IF_ERROR(vectorized::VExpr::create_expr_tree(texpr, ctx));
        _vfn_ctxs.push_back(ctx);

        auto root = ctx->root();
        vectorized::TableFunction* fn = nullptr;
        RETURN_IF_ERROR(vectorized::TableFunctionFactory::get_fn(root->fn(), _pool, &fn,
                                                                 state->be_exec_version()));
        fn->set_expr_context(ctx);
        _fns.push_back(fn);
    }
    _fn_num = cast_set<int>(_fns.size());

    // Prepare output slot ids
    RETURN_IF_ERROR(_prepare_output_slot_ids(tnode));
    return Status::OK();
}

Status TableFunctionOperatorX::prepare(doris::RuntimeState* state) {
    RETURN_IF_ERROR(Base::prepare(state));
    for (auto* fn : _fns) {
        RETURN_IF_ERROR(fn->prepare());
    }
    RETURN_IF_ERROR(vectorized::VExpr::prepare(_vfn_ctxs, state, row_descriptor()));

    // get current all output slots
    for (const auto& tuple_desc : row_descriptor().tuple_descriptors()) {
        for (const auto& slot_desc : tuple_desc->slots()) {
            _output_slots.push_back(slot_desc);
        }
    }

    // get all input slots
    for (const auto& child_tuple_desc : _child->row_desc().tuple_descriptors()) {
        for (const auto& child_slot_desc : child_tuple_desc->slots()) {
            _child_slots.push_back(child_slot_desc);
        }
    }

    for (int i = 0; i < _child_slots.size(); i++) {
        if (_slot_need_copy(i)) {
            _output_slot_indexs.push_back(i);
        } else {
            _useless_slot_indexs.push_back(i);
        }
    }

    return vectorized::VExpr::open(_vfn_ctxs, state);
}

#include "common/compile_check_end.h"
} // namespace doris::pipeline
