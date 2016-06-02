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
import java.util.UUID

import com.google.common.io.Files
import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.mockito.{Matchers => mockito}
import org.scalatest.junit.JUnitRunner

import org.midonet.midolman.config.{FlowStateConfig, MidolmanConfig}
import org.midonet.packets.SbeEncoder

@RunWith(classOf[JUnitRunner])
class FlowStateStorageStreamTest extends FlowStateBaseTest {

    private var config: FlowStateConfig = _
    private var tmpFile: File = _
    before {
        val tmpDir = Files.createTempDir()
        System.setProperty("midolman.log.dir", System.getProperty("java.io.tmpdir"))
        // Setting compression_ratio to 1 because it's random data
        val flowStateConfig = ConfigFactory.parseString(
            s"""
               |agent.minions.flow_state.enabled : true
               |agent.minions.flow_state.port : 1234
               |agent.minions.flow_state.log_directory: ${tmpDir.getName}
               |agent.minions.flow_state.block_size : 10
               |agent.minions.flow_state.compression_ratio : 1
               |agent.minions.flow_state.total_storage_size : 10
               |agent.minions.flow_state.expiration_time : 20s
               |""".stripMargin)
        config = MidolmanConfig.forTests(flowStateConfig).flowState
    }

    feature("FlowStateOutputStream handles writes") {
        scenario("On an empty stream") {
            val portId = UUID.randomUUID()

            val manager = new FlowStateStorageProvider(config)
            val streams = manager.get(portId)
            val msg = validFlowStateMessage(numNats = 2, numEgressPorts = 3)
            msg match {
                case (_, _, encoder) =>
                    streams.out.write(encoder)
                    streams.out.buffers.head.position() shouldBe encoder.encodedLength()
            }
            streams.out.close()
        }

        scenario("Writting on a full block") {
            val portId = UUID.randomUUID()

            val manager = new FlowStateStorageProvider(config)
            val streams = manager.get(portId)
            var encoder: SbeEncoder = null
            val iter = streams.out.buffers.iterator
            var currentBuffer = iter.next
            eventually {
                validFlowStateMessage(numNats = 2, numEgressPorts = 3) match {
                    case (_, _, enc) => encoder = enc
                }
                val previousPosition = currentBuffer.position()
                streams.out.write(encoder)

                currentBuffer.position() shouldBe previousPosition + encoder.encodedLength()
                streams.getByteBuffers.length shouldBe 1
            }
            currentBuffer = iter.next
            currentBuffer.position() shouldBe encoder.encodedLength()
        }
    }
}


