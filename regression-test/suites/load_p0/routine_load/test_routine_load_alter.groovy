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

import org.apache.kafka.clients.admin.AdminClient
import org.apache.kafka.clients.producer.KafkaProducer
import org.apache.kafka.clients.producer.ProducerRecord
import org.apache.kafka.clients.producer.ProducerConfig

suite("test_routine_load_alter","p0") {
    def kafkaCsvTpoics = [
                  "test_routine_load_alter",
                ]
    String enabled = context.config.otherConfigs.get("enableKafkaTest")
    String kafka_port = context.config.otherConfigs.get("kafka_port")
    String externalEnvIp = context.config.otherConfigs.get("externalEnvIp")
    def kafka_broker = "${externalEnvIp}:${kafka_port}"
    if (enabled != null && enabled.equalsIgnoreCase("true")) {
        // define kafka 
        def props = new Properties()
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "${kafka_broker}".toString())
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
        // add timeout config
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, "10000")  
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, "10000")

        // check conenction
        def verifyKafkaConnection = { prod ->
            try {
                logger.info("=====try to connect Kafka========")
                def partitions = prod.partitionsFor("__connection_verification_topic")
                return partitions != null
            } catch (Exception e) {
                throw new Exception("Kafka connect fail: ${e.message}".toString())
            }
        }
        // Create kafka producer
        def producer = new KafkaProducer<>(props)
        try {
            logger.info("Kafka connecting: ${kafka_broker}")
            if (!verifyKafkaConnection(producer)) {
                throw new Exception("can't get any kafka info")
            }
        } catch (Exception e) {
            logger.error("FATAL: " + e.getMessage())
            producer.close()
            throw e  
        }
        logger.info("Kafka connect success")

        for (String kafkaCsvTopic in kafkaCsvTpoics) {
            def txt = new File("""${context.file.parent}/data/${kafkaCsvTopic}.csv""").text
            def lines = txt.readLines()
            lines.each { line ->
                logger.info("=====${line}========")
                def record = new ProducerRecord<>(kafkaCsvTopic, null, line)
                producer.send(record)
            }
        }
    }

    if (enabled != null && enabled.equalsIgnoreCase("true")) {
        def tableName = "test_routine_load_alter"
        def jobName = "test_alter"
        sql """ DROP TABLE IF EXISTS ${tableName} """
        sql """
            CREATE TABLE IF NOT EXISTS ${tableName} (
                `k1` int(20) NULL,
                `k2` string NULL,
                `v1` date  NULL,
                `v2` string  NULL,
                `v3` datetime  NULL,
                `v4` string  NULL
            ) ENGINE=OLAP
            DUPLICATE KEY(`k1`)
            COMMENT 'OLAP'
            DISTRIBUTED BY HASH(`k1`) BUCKETS 3
            PROPERTIES ("replication_allocation" = "tag.location.default: 1");
        """

        try {
            sql """
                CREATE ROUTINE LOAD ${jobName} ON ${tableName}
                COLUMNS TERMINATED BY ","
                PROPERTIES
                (
                    "max_batch_interval" = "5",
                    "max_batch_rows" = "300000",
                    "max_batch_size" = "209715200"
                )
                FROM KAFKA
                (
                    "kafka_broker_list" = "${externalEnvIp}:${kafka_port}",
                    "kafka_topic" = "${kafkaCsvTpoics[0]}",
                    "kafka_partitions" = "0",
                    "kafka_offsets" = "2"
                );
            """
            sql "sync"

            def count = 0
            while (true) {
                def res = sql "select count(*) from ${tableName}"
                def state = sql "show routine load for ${jobName}"
                log.info("routine load state: ${state[0][8].toString()}".toString())
                log.info("routine load statistic: ${state[0][14].toString()}".toString())
                log.info("reason of state changed: ${state[0][17].toString()}".toString())
                if (res[0][0] > 0) {
                    break
                }
                if (count >= 120) {
                    log.error("routine load can not visible for long time")
                    assertEquals(20, res[0][0])
                    break
                }
                sleep(5000)
                count++
            }
            qt_sql_before "select * from ${tableName} order by k1"

            // test alter offset
            sql "pause routine load for ${jobName}"
            sql "ALTER ROUTINE LOAD FOR ${jobName} FROM KAFKA(\"kafka_partitions\" = \"0\", \"kafka_offsets\" = \"1\");"
            sql "resume routine load for ${jobName}"

            def props = new Properties()
            props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, "${kafka_broker}".toString())
            props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
            props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, "org.apache.kafka.common.serialization.StringSerializer")
            // Create kafka producer
            def producer = new KafkaProducer<>(props)

            for (String kafkaCsvTopic in kafkaCsvTpoics) {
                def txt = new File("""${context.file.parent}/data/${kafkaCsvTopic}.csv""").text
                def lines = txt.readLines()
                lines.each { line ->
                    logger.info("=====${line}========")
                    def record = new ProducerRecord<>(kafkaCsvTopic, null, line)
                    producer.send(record)
                }
            }

            count = 0
            while (true) {
                def res = sql "select count(*) from ${tableName}"
                log.info("count: ${res[0][0]}".toString())
                def state = sql "show routine load for ${jobName}"
                log.info("routine load state: ${state[0][8].toString()}".toString())
                log.info("routine load statistic: ${state[0][14].toString()}".toString())
                log.info("reason of state changed: ${state[0][17].toString()}".toString())
                if (res[0][0] >= 6) {
                    break
                }
                if (count >= 120) {
                    log.error("routine load can not visible for long time")
                    assertEquals(20, res[0][0])
                    break
                }
                sleep(5000)
                count++
            }
            qt_sql_alter_after "select * from ${tableName} order by k1" 
        } finally {
            sql "stop routine load for ${jobName}"
        }
    }
}
