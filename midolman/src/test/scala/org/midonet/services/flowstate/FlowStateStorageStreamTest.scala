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
        scenario("On an empty file") {
            val portId = UUID.randomUUID()

            val output = new FlowStateOutputStream(config, portId)
            val msg = validFlowStateMessage(numNats = 2, numEgressPorts = 3)
            msg match {
                case (_, _, encoder) =>
                    output.write(encoder)
                    output.blockPointer shouldBe 0
                    output.txLogBuffer.position() shouldBe encoder.encodedLength()
                    output.txLogCompressedFile.getChannel.position() shouldBe 0
            }
            output.close()
        }

        scenario("Writting on a full block") {
            val portId = UUID.randomUUID()

            val output = new FlowStateOutputStream(config, portId)
            val uncompressedBlockSize = config.compressionRatio *
                                        config.blockSize * 1024 * 1024
            var (_, _, encoder) = validFlowStateMessage(numNats = 2, numEgressPorts = 3)

            def aboutToGetFilled(enc: SbeEncoder): Boolean = {
                output.txLogBuffer.position() + enc.encodedLength() > uncompressedBlockSize
            }

            while (!aboutToGetFilled(encoder)) {
                val previousPosition = output.txLogBuffer.position()
                log.debug(s"previousPosition $previousPosition")
                output.write(encoder)
                output.blockPointer shouldBe 0
                output.txLogBuffer.position() shouldBe previousPosition + encoder.encodedLength()
                output.txLogCompressedFile.getChannel.position() shouldBe 0
                validFlowStateMessage(numNats = 2, numEgressPorts = 3) match {
                    case (_, _, enc) => encoder = enc
                }
            }

            var previousPosition = output.txLogBuffer.position()
            log.debug(s"previousPosition $previousPosition")

            output.write(encoder)
            previousPosition = output.txLogBuffer.position()
            log.debug(s"previousPosition $previousPosition")
            output.blockPointer shouldBe 1
            output.txLogBuffer.position() shouldBe encoder.encodedLength()
            output.txLogCompressedFile.length() > 0 shouldBe true


        }
    }
}

