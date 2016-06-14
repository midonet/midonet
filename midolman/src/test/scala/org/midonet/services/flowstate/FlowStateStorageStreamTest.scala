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
import java.nio.ByteBuffer
import java.util.UUID

import scala.collection.mutable

import com.google.common.io.Files
import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Millis, Seconds, Span}

import org.midonet.midolman.config.{FlowStateConfig, MidolmanConfig}
import org.midonet.packets.ConnTrackState.ConnTrackKeyStore
import org.midonet.packets.NatState.NatKeyStore
import org.midonet.packets.{FlowStateStorePackets, SbeEncoder}
import org.midonet.services.flowstate.stream._
import org.midonet.util.collection.RingBuffer
import org.midonet.util.io.stream.{BlockFactory, BlockHeader, HeapBlockFactory, TimedBlockHeader}

@RunWith(classOf[JUnitRunner])
class FlowStateStorageStreamTest extends FlowStateBaseTest {

    private var config: FlowStateConfig = _
    private var tmpFile: File = _
    private var buffers: RingBuffer[ByteBuffer] = _
    private var pool: RingBuffer[ByteBuffer] = _
    private var portId: UUID = _
    implicit val patience: PatienceConfig = new PatienceConfig(Span(15, Seconds),
                                                               Span(100, Millis))
    private var blockFactory: BlockFactory[BlockHeader] = _

    before {
        val tmpDir = Files.createTempDir()
        System.setProperty("midolman.log.dir", System.getProperty("java.io.tmpdir"))
        // Setting compression_ratio to 1 because it's random data
        val flowStateConfig = ConfigFactory.parseString(
            s"""
               |agent.minions.flow_state.log_directory: ${tmpDir.getName}
               |agent.minions.flow_state.block_size : 1
               |agent.minions.flow_state.blocks_per_port : 10
               |agent.minions.flow_state.expiration_time : 5s
               |""".stripMargin)
        config = MidolmanConfig.forTests(flowStateConfig).flowState
        buffers = new RingBuffer[ByteBuffer](config.blocksPerPort, null)
        portId = UUID.randomUUID()
        pool = new RingBuffer[ByteBuffer](config.blocksPerPort, null)
        blockFactory = new HeapBlockFactory[TimedBlockHeader](
            config.blockSize, FlowStateBlock)
            .asInstanceOf[BlockFactory[BlockHeader]]
    }

    feature("FlowStateStream handles writes") {
        scenario("Output buffer has a minimum size of 1KB.") {
            val newConfig = ConfigFactory.parseString(
                s"""
                   |agent.minions.flow_state.block_size : 1
                 """.stripMargin)
            MidolmanConfig.forTests(newConfig).flowState.blockSize shouldBe 1024
        }

        scenario("Writing a single message on an empty stream is compressed") {
            Given("A flow state storage stream")
            val outStream = FlowStateWriter(config, buffers, blockFactory.allocate)

            And("A message to store")
            val writeEncoder = validFlowStateMessage(numNats = 2,
                                                     numEgressPorts = 3)._3

            When("Writing to the stream")
            outStream.write(writeEncoder)

            Then("There's no buffer allocated yet.")
            outStream.buffers.length shouldBe 0

            When("Closing and flushing the stream")
            outStream.close()

            Then("The message should be compressed")
            val position = outStream.buffers.head.get.limit()
            position should be > FlowStateBlock.headerSize
            position should be < FlowStateBlock.headerSize + writeEncoder.encodedLength()
        }


        scenario("Writing to a stream adds a buffer when current is full") {
            Given("A flow state storage stream")
            val outStream = FlowStateWriter(config, buffers, blockFactory.allocate)

            When("Writing to the stream indefinitely")
            while (outStream.buffers.length < 2) {
                val encoder =
                    validFlowStateMessage(numNats = 2, numEgressPorts = 3)._3
                outStream.write(encoder)
            }

            When("Closing the stream and flushing the data to the buffers")
            outStream.close()

            And("Having read the headers of each block")
            val iter = outStream.buffers.iterator
            val buf1 = iter.next()
            val buf2 = iter.next()

            val header1 = FlowStateBlock(buf1)
            val header2 = FlowStateBlock(buf2)

            Then("The first and last entries of each block should be increasing")
            header1.lastEntryTime should be < header2.lastEntryTime

            And("The data on the 2nd buffer should not fit on the 1st buffer")
            val compressedData2 = header2.blockLength - FlowStateBlock.headerSize
            compressedData2 should be > buf1.capacity - buf1.limit
        }
    }

    feature("FlowStateStream handles reads") {
        scenario("Reading from a single-block empty stream") {
            Given("A flow state storage stream")
            val inStream = FlowStateReader(config, buffers)

            Then("The stream returns None")
            inStream.read() shouldBe None
        }

        scenario("Reading from a single-block multi-message stream") {
            Given("A flow state storage stream")
            val outStream = FlowStateWriter(config, buffers, blockFactory.allocate)
            val inStream = FlowStateReader(config, buffers)

            And("Some messages to store")
            val writeMsgs = for (i <- 1 until 5)
                yield validFlowStateMessage(numNats = 2,
                                            numEgressPorts = 3)._3

            When("Writting to the stream")
            for (msg <- writeMsgs)
                outStream.write(msg)
            outStream.flush()
            outStream.buffers.length shouldBe 1
            outStream.close()

            def readFully() {
                val readMsgs = mutable.MutableList.empty[SbeEncoder]
                var msg: Option[SbeEncoder] = None
                while ( {
                    msg = inStream.read(); msg
                }.isDefined) {
                    readMsgs += msg.get
                }

                Then("Read and writen buffers are the same")
                for ((writeMsg, readMsg) <- writeMsgs zip readMsgs) {
                    val writeBuff = ByteBuffer
                        .wrap(writeMsg.flowStateBuffer.array(),
                              0, writeMsg.encodedLength())
                    val readBuff = ByteBuffer
                        .wrap(readMsg.flowStateBuffer.array(),
                              0, readMsg.encodedLength())
                    readBuff.clear()
                    writeBuff shouldBe readBuff
                }
            }

            Then("We can read the whole stream")
            readFully()

            When("Doing a reset on the input stream")
            inStream.reset()

            Then("We can read the whole stream again")
            readFully()
        }

        scenario("Reading from a multi-block multi-message stream") {
            Given("A flow state storage stream")
            val outStream = FlowStateWriter(config, buffers, blockFactory.allocate)
            val inStream = FlowStateReader(config, buffers)

            When("Writing to the stream indefinitely")
            val writeMsgs = mutable.MutableList.empty[SbeEncoder]
            val numBlocks = 3
            while (outStream.buffers.length < numBlocks) {
                val encoder =
                    validFlowStateMessage(numNats = 2, numEgressPorts = 3)._3
                writeMsgs += encoder
                outStream.write(encoder)
            }

            When("Closing and reading from the stream")
            outStream.close()
            inStream.reset()

            val readMsgs = mutable.MutableList.empty[SbeEncoder]
            var msg: Option[SbeEncoder] = None
            while ( { msg = inStream.read(); msg }.isDefined) {
                readMsgs += msg.get
            }

            Then("Read and written buffers are the same")
            readMsgs.length shouldBe writeMsgs.length
            for ((writeMsg, readMsg) <- writeMsgs zip readMsgs) {
                val writeBuff = ByteBuffer.wrap(writeMsg.flowStateBuffer.array(),
                                                0, writeMsg.encodedLength())
                val readBuff = ByteBuffer.wrap(readMsg.flowStateBuffer.array(),
                                               0, readMsg.encodedLength())
                readBuff.clear()
                val readString =
                    FlowStateStorePackets.toString(readMsg.flowStateMessage,
                                                   ConnTrackKeyStore,
                                                   NatKeyStore)
                val writeString =
                    FlowStateStorePackets.toString(writeMsg.flowStateMessage,
                                                   ConnTrackKeyStore,
                                                   NatKeyStore)
                log.debug(s"Msg written: $writeString")
                log.debug(s"Msg read: $readString")
                writeBuff shouldBe readBuff
            }
        }
    }

    feature("FlowState expiration") {
        scenario("Can read input stream after some expirations.") {
            Given("A flow state storage stream")
            val outStream = FlowStateWriter(config, buffers, blockFactory.allocate)
            val inStream = FlowStateReader(config, buffers)
            val timeout = config.expirationTime.toMillis

            And("A stream of messages until we have two blocks")
            val writeMsg = validFlowStateMessage(numNats = 2, numEgressPorts = 3)
            val writeEncoder = writeMsg._3

            while (buffers.length < 2) {
                outStream.write(writeEncoder)
            }
            outStream.buffers.length shouldBe 2
            var blocks = buffers.iterator.toIndexedSeq
            val header1 = FlowStateBlock(blocks(0))
            var header2 = FlowStateBlock(blocks(1))

            When("Waiting half of the time and refreshing the 2nd block")
            Thread.sleep(timeout/2)
            outStream.write(writeEncoder)
            outStream.flush()
            blocks = buffers.iterator.toIndexedSeq // update view
            header2 = FlowStateBlock(blocks(1))
            header1.lastEntryTime should be < header2.lastEntryTime

            When("Waiting for the first block to expire")
            Thread.sleep(timeout/2)

            Then("The first block should be released")
            eventually {
                val expiredBuffer = buffers.peek
                outStream.invalidateBlocks() shouldBe 1
                val newHeader1 = FlowStateBlock(buffers.head.get)
                Then("The new head should be the previous 2nd block")
                newHeader1 shouldBe header2
                And("The 1st buffer should be released")
                outStream.buffers.length shouldBe 1
                val expiredHeader = FlowStateBlock(expiredBuffer.get)
                expiredHeader.lastEntryTime shouldBe -1
                expiredHeader.blockLength shouldBe 0
            }

            And("We can read from the stream")
            // point the stream to the new head after expirations
            inStream.reset()
            val readMsg = inStream.read()
            readMsg should not be None
            val readEncoder = readMsg.get
            val writeBuff = ByteBuffer.wrap(writeEncoder.flowStateBuffer.array(),
                                            0, writeEncoder.encodedLength())
            val readBuff = ByteBuffer.wrap(readEncoder.flowStateBuffer.array(),
                                           0, readEncoder.encodedLength())
            readBuff.clear()
            val readString =
                FlowStateStorePackets.toString(readEncoder.flowStateMessage,
                                               ConnTrackKeyStore,
                                               NatKeyStore)
            val writeString =
                FlowStateStorePackets.toString(writeEncoder.flowStateMessage,
                                               ConnTrackKeyStore,
                                               NatKeyStore)
            log.debug(s"Msg written: $writeString")
            log.debug(s"Msg read: $readString")
            writeBuff shouldBe readBuff

        }
    }
}


