/*
 * Copyright 2015 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.cluster.state

import com.google.inject.PrivateModule
import org.I0Itec.zkclient.ZkClient
import kafka.utils.ZkUtils

import org.midonet.cluster.storage.KafkaConfig

/**
 * This Guice module is dedicated to declare dependencies that are exposed to
 * MidoNet components which use merged maps.
 */
class MergedMapModule(val conf: KafkaConfig) extends PrivateModule {
    override def configure(): Unit = {
        if (conf.useMergedMaps) {
            /* The zookeeper client used by Kafka. */
            val zkClient = ZkUtils.createZkClient(conf.zkHosts,
                                                  conf.zkConnectionTimeout,
                                                  conf.zkSessionTimeout)
            bind(classOf[ZkClient]).toInstance(zkClient)
            expose(classOf[ZkClient])

            bind(classOf[KafkaConfig]).toInstance(conf)
            expose(classOf[KafkaConfig])

            bind(classOf[MergedMapState]).to(classOf[MergedMapStateStorage])
            expose(classOf[MergedMapState])
        }
    }
}
