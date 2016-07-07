/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.services.flowstate

import java.io.File

import com.google.common.io.Files
import com.typesafe.config.ConfigFactory

import org.cassandraunit.utils.EmbeddedCassandraServerHelper

import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.services.flowstate.stream.{Context, FlowStateManager}

trait FlowStateBaseCuratorTest extends FlowStateBaseTest
                                       with CuratorTestFramework {

    protected var config: String = _
    protected var midolmanConfig: MidolmanConfig = _
    protected var streamContext: Context = _
    protected var tmpDir: File = _

    override def beforeEach(): Unit = {
        super[CuratorTestFramework].beforeEach()
        baseBefore()
    }

    private def baseBefore(): Unit = {
        tmpDir = Files.createTempDir()
        // We assume midolman.log.dir contains an ending / but tmpdir does not
        // add it on some platforms.
        System.setProperty("minions.db.dir",
                           s"${System.getProperty("java.io.tmpdir")}/")

        EmbeddedCassandraServerHelper.startEmbeddedCassandra(60000L)

        config =
            s"""
               |zookeeper.zookeeper_hosts = "${zk.getConnectString}"
               |agent.minions.flow_state.enabled : true
               |agent.minions.flow_state.legacy_push_state : true
               |agent.minions.flow_state.legacy_read_state : true
               |agent.minions.flow_state.local_push_state : true
               |agent.minions.flow_state.local_read_state : true
               |agent.minions.flow_state.port : 1234
               |agent.minions.flow_state.connection_timeout : 5s
               |agent.minions.flow_state.block_size : 1
               |agent.minions.flow_state.blocks_per_port : 10
               |agent.minions.flow_state.expiration_time : 20s
               |agent.minions.flow_state.log_directory: ${tmpDir.getName}
               |cassandra.servers : "127.0.0.1:9142"
               |cassandra.cluster : "midonet"
               |cassandra.replication_factor : 1
               |"""

        val flowStateConfig = ConfigFactory.parseString(config.stripMargin)

        midolmanConfig = MidolmanConfig.forTests(flowStateConfig)
        val manager = new FlowStateManager(midolmanConfig.flowState)
        streamContext = Context(midolmanConfig.flowState, manager)
    }
}
