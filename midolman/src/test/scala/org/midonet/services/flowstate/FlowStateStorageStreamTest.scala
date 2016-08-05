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

import java.io.{File, IOException}
import java.nio.ByteBuffer
import java.nio.file.{Path, Paths, Files => JFiles}
import java.util.UUID

import scala.collection.mutable

import com.google.common.io.Files
import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Millis, Seconds, Span}

import org.midonet.midolman.config.{FlowStateConfig, MidolmanConfig}
import org.midonet.packets.SbeEncoder
import org.midonet.services.flowstate.stream.snappy.SnappyBlockWriter
import org.midonet.services.flowstate.stream.{ByteBufferBlockReader, ByteBufferBlockWriter, _}
import org.midonet.util.collection.RingBufferWithFactory
import org.midonet.util.io.stream._

@RunWith(classOf[JUnitRunner])
class FlowStateStorageStreamTest extends FlowStateBaseTest {

    private var config: FlowStateConfig = _
    private var context: stream.Context = _
    private var tmpFile: File = _
    private var portId: UUID = _
    private var tmpDir: File = _
    implicit val patience: PatienceConfig = new PatienceConfig(
        Span(15, Seconds),
        Span(100, Millis))

    implicit def toWriterImpl(writer: FlowStateWriter): FlowStateWriterImpl = {
        writer.asInstanceOf[FlowStateWriterImpl]
    }

    implicit def toReaderImpl(reader: FlowStateReader): FlowStateReaderImpl = {
        reader.asInstanceOf[FlowStateReaderImpl]
    }

    private class TestByteBufferBlockWriter(
            builder: BlockHeaderBuilder[TimedBlockHeader],
            buffers: RingBufferWithFactory[ByteBuffer],
            expirationTime: Long,
            howManyBeforeFailing: Int)
        extends ByteBufferBlockWriter(builder, buffers, expirationTime) {

        private var written = 0

        override def write(buff: Array[Byte], offset: Int, length: Int): Unit = {
            if (written < howManyBeforeFailing) {
                written += 1
                super.write(buff, offset, length)
            } else {
                super.write(buff, offset, length/2)
            }
        }
    }

    private def assertEqualMessages(readEncoder: SbeEncoder,
                                    writeEncoder: SbeEncoder) = {
        val writeBuff = ByteBuffer
            .wrap(writeEncoder.flowStateBuffer.array(),
                  0, writeEncoder.encodedLength())
        val readBuff = ByteBuffer.wrap(readEncoder.flowStateBuffer.array(),
                                       0, readEncoder.encodedLength())
        readBuff.clear()
        writeBuff shouldBe readBuff
    }

    before {
        tmpDir = Files.createTempDir()
        // We assume midolman.log.dir contains an ending / but tmpdir does not
        // add it on some platforms.
        System.setProperty("minions.db.dir",
                           s"${System.getProperty("java.io.tmpdir")}/")
        // Setting compression_ratio to 1 because it's random data
        val flowStateConfig = ConfigFactory.parseString(
            s"""
               |agent.minions.flow_state.log_directory: ${tmpDir.getName}
               |agent.minions.flow_state.block_size : 1
               |agent.minions.flow_state.blocks_per_port : 10
               |agent.minions.flow_state.expiration_time : 20s
               |""".stripMargin)
        config = MidolmanConfig.forTests(flowStateConfig).flowState
        portId = UUID.randomUUID()
        context = stream.Context(config, new FlowStateManager(config))
    }

    feature("FlowState builder handles headers") {
        scenario("Reading a valid header") {
            val rawHeader = ByteBuffer.allocate(FlowStateBlock.headerSize)
            FlowStateBlock.init(rawHeader)

            val header = FlowStateBlock(rawHeader)
            header.isValid shouldBe false
            header.lastEntryTime shouldBe -1
            header.blockLength shouldBe 0
            header.magic shouldBe FlowStateBlock.MagicNumber
            header.toString.endsWith(
                "[magic: XFlowState, last: -1, length: 0]") shouldBe true
        }

        scenario("Reseting an invalid header") {
            val block = ByteBuffer.allocate(FlowStateBlock.headerSize + 4)
            FlowStateBlock.init(block)
            block.putInt(Int.MaxValue)
            FlowStateBlock.update(block)

            var header = FlowStateBlock(block)
            header.isValid shouldBe true
            header.lastEntryTime should be > 0L
            header.blockLength shouldBe 4
            block.position shouldBe FlowStateBlock.headerSize + header.blockLength

            val newBlock = ByteBuffer.wrap(block.array)
            FlowStateBlock.reset(newBlock)

            header = FlowStateBlock(newBlock)
            header.isValid shouldBe false
            header.lastEntryTime shouldBe -1
            header.blockLength shouldBe 0
            newBlock.position shouldBe FlowStateBlock.headerSize
            header.toString.endsWith(
                "[magic: XFlowState, last: -1, length: 0]") shouldBe true
        }

        scenario("Not reseting a valid header") {
            val block = ByteBuffer.allocate(FlowStateBlock.headerSize + 8)
            FlowStateBlock.init(block)
            block.putInt(Int.MaxValue)
            FlowStateBlock.update(block)

            var header = FlowStateBlock(block)
            header.isValid shouldBe true
            header.lastEntryTime should be > 0L
            header.blockLength shouldBe 4
            block.position shouldBe FlowStateBlock.headerSize + header.blockLength

            val newBlock = ByteBuffer.wrap(block.array)
            FlowStateBlock.init(newBlock)

            header = FlowStateBlock(newBlock)
            header.isValid shouldBe true
            header.lastEntryTime should be > 0L
            header.blockLength shouldBe 4
            block.position shouldBe FlowStateBlock.headerSize + header.blockLength
        }
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
            val outStream = FlowStateWriter(context, portId)
            val buffers = outStream.out.out.buffers

            And("A message to store")
            val writeEncoder = validFlowStateInternalMessage(numNats = 2,
                                                     numEgressPorts = 3)._3

            When("Writing to the stream")
            outStream.write(writeEncoder)

            Then("There's no buffer allocated yet.")
            buffers.length shouldBe 0

            When("Closing and flushing the stream")
            outStream.close()

            Then("The message should be compressed")
            val position = buffers.head.get.limit()
            position should be > FlowStateBlock.headerSize
            position should
            be < FlowStateBlock.headerSize + writeEncoder.encodedLength()
        }

        scenario("Writing to a stream adds a buffer when current is full") {
            Given("A flow state storage stream")
            val outStream = FlowStateWriter(context, portId)
            val buffers = outStream.out.out.buffers

            When("Writing to the stream indefinitely")
            while (buffers.length < 2) {
                val encoder =
                    validFlowStateInternalMessage(numNats = 2, numEgressPorts = 3)._3
                outStream.write(encoder)
            }

            When("Closing the stream and flushing the data to the buffers")
            outStream.close()

            And("Having read the headers of each block")
            val iter = buffers.iterator
            val buf1 = iter.next()
            val buf2 = iter.next()

            val header1 = FlowStateBlock(buf1)
            val header2 = FlowStateBlock(buf2)

            Then(
                "The first and last entries of each block should be increasing")
            header1.lastEntryTime should be < header2.lastEntryTime

            And("The data on the 2nd buffer should not fit on the 1st buffer")
            val compressedData2 = header2.blockLength -
                                  FlowStateBlock.headerSize
            compressedData2 should be > buf1.capacity - buf1.limit
        }

        scenario("Trying to write a message bigger than the block size") {
            Given("A flow state storage stream")
            val blockWriter = ByteBufferBlockWriter(context, portId)
            val snappyWriter = new SnappyBlockWriter(blockWriter, config.blockSize)

            val block = ByteBuffer.allocate(config.blockSize + 1)
            intercept[IndexOutOfBoundsException] {
                snappyWriter.write(block.array(), 0, block.array().length)
            }
        }
    }

    feature("FlowStateStream handles reads") {
        scenario("Reading from a single-block multi-message stream") {
            Given("A flow state storage stream")
            val outStream = FlowStateWriter(context, portId)
            val buffers = outStream.out.out.buffers

            And("Some messages to store")
            val writeMsgs = for (i <- 1 until 2)
                yield validFlowStateInternalMessage(numNats = 2,
                                            numEgressPorts = 3)._3

            When("Writting to the stream")
            for (msg <- writeMsgs)
                outStream.write(msg)
            outStream.flush()
            buffers.length shouldBe 1
            outStream.close()

            val inStream = FlowStateReader(context, portId)

            def readFully() {
                val readMsgs = mutable.MutableList.empty[SbeEncoder]
                var msg: Option[SbeEncoder] = None
                while ( {
                    msg = inStream.read()
                    msg
                }.isDefined) {
                    readMsgs += msg.get
                }

                Then("Read and writen buffers are the same")
                for ((writeMsg, readMsg) <- writeMsgs zip readMsgs) {
                    assertEqualMessages(readMsg, writeMsg)
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
            val outStream = FlowStateWriter(context, portId)
            val buffers = outStream.out.out.buffers

            When("Writing to the stream indefinitely")
            val writeMsgs = mutable.MutableList.empty[SbeEncoder]
            val numBlocks = 3
            while (buffers.length < numBlocks) {
                val encoder =
                    validFlowStateInternalMessage(numNats = 2, numEgressPorts = 3)._3
                writeMsgs += encoder
                outStream.write(encoder)
            }

            When("Closing and reading from the stream")
            outStream.close()

            val inStream = FlowStateReader(context, portId)
            val readMsgs = mutable.MutableList.empty[SbeEncoder]
            var msg: Option[SbeEncoder] = None
            while ( {
                msg = inStream.read(); msg
            }.isDefined) {
                readMsgs += msg.get
            }

            Then("Read and written buffers are the same")
            readMsgs.length shouldBe writeMsgs.length
            for ((writeMsg, readMsg) <- writeMsgs zip readMsgs) {
                assertEqualMessages(readMsg, writeMsg)
            }
        }
    }

    feature("FlowState expiration") {
        scenario("Can read input stream after some expirations.") {
            Given("A flow state storage stream")
            val outStream = FlowStateWriter(context, portId)
            val buffers = outStream.out.out.buffers
            val timeout = config.expirationTime.toMillis

            And("A stream of messages until we have two blocks")
            val writeMsg = validFlowStateInternalMessage(numNats = 2,
                                                 numEgressPorts = 3)
            val writeEncoder = writeMsg._3

            while (buffers.length < 2) {
                outStream.write(writeEncoder)
            }
            buffers.length shouldBe 2
            var blocks = buffers.iterator.toIndexedSeq
            val header1 = FlowStateBlock(blocks(0))
            var header2 = FlowStateBlock(blocks(1))

            When("Waiting half of the time and refreshing the 2nd block")
            Thread.sleep(timeout / 2)
            outStream.write(writeEncoder)
            outStream.flush()
            blocks = buffers.iterator.toIndexedSeq // update view
            header2 = FlowStateBlock(blocks(1))
            header1.lastEntryTime should be < header2.lastEntryTime

            When("Waiting for the first block to expire")
            Thread.sleep(timeout / 2)

            Then("The first block should be released")
            val expiredBuffer = buffers.peek
            outStream.out.out.invalidateBlocks() shouldBe 1
            val newHeader1 = FlowStateBlock(buffers.head.get)
            Then("The new head should be the previous 2nd block")
            newHeader1.lastEntryTime shouldBe header2.lastEntryTime
            newHeader1.blockLength shouldBe header2.blockLength

            And("The 1st buffer should be released")
            buffers.length shouldBe 1
            val expiredHeader = FlowStateBlock(expiredBuffer.get)
            expiredHeader.lastEntryTime shouldBe -1
            expiredHeader.blockLength shouldBe 0

            And("We can read from the stream")
            // point the stream to the new head after expirations
            val inStream = FlowStateReader(context, portId)
            val readMsg = inStream.read()
            readMsg should not be None
            val readEncoder = readMsg.get
            assertEqualMessages(readEncoder, writeEncoder)
            // TODO: add check on number of messages and equality on all of them
        }
    }

    feature("Flow state files are cleaned when unused") {
        scenario("No files present") {
            Given("An empty flow state manager")
            context.ioManager.buffers should have size 0

            Then("Removing the unused files should not remove anything")
            context.ioManager.removeInvalid() shouldBe 0
        }

        scenario("Openend files are not removed by the cleaner") {
            Given("An opened file")
            val portId = UUID.randomUUID()
            context.ioManager.open(portId)
            context.ioManager.buffers should have size 1

            Then("Removing the unused files should not remove anything")
            context.ioManager.removeInvalid() shouldBe 0
        }

        scenario("Closed files are not removed by the cleaner") {
            Given("An opened file")
            val portId = UUID.randomUUID()
            context.ioManager.open(portId)
            context.ioManager.buffers should have size 1

            When("Closing it")
            context.ioManager.close(portId)

            Then("Removing the unused files should not remove anything")
            context.ioManager.removeInvalid() shouldBe 0
        }

        scenario("Not opened and not closed files are removed by the cleaner") {
            Given("An opened file")
            val portId1 = UUID.randomUUID()
            context.ioManager.open(portId1)
            And("A closed one")
            val portId2 = UUID.randomUUID()
            context.ioManager.open(portId2)
            And("A random file in the flow state directory")
            val fileName = s"${context.ioManager.storageDirectory}/${UUID.randomUUID}"
            new File(fileName).createNewFile()

            context.ioManager.buffers should have size 2

            Then("Removing the unused files should remove a single file")
            context.ioManager.removeInvalid() shouldBe 1
            JFiles.list(Paths.get(context.ioManager.storageDirectory)).toArray.foreach {
                case p: Path =>
                   context.ioManager.buffers.keySet should contain (UUID.fromString(p.getFileName.toString))
            }
        }

        scenario("File descriptor is closed when writer is closed") {
            Given("An openend file")
            val portId = UUID.randomUUID
            context.ioManager.open(portId)
            val (buffers, fileHandler) = context.ioManager.buffers.get(portId).get
            fileHandler.isOpen shouldBe true
            context.ioManager.clear(portId)
            fileHandler.isOpen shouldBe false
        }
    }

    feature("Writting/reading from heap memory byte buffers") {
        scenario("Opening an empty ring buffer") {
            Given("An in-memory flow state writter")
            val blockFactory = new HeapBlockFactory[TimedBlockHeader](
                config.blockSize, FlowStateBlock)
                .asInstanceOf[BlockFactory[BlockHeader]]
            val buffers = new RingBufferWithFactory[ByteBuffer](
                config.blocksPerPort, null, blockFactory.allocate)
            val outStream = FlowStateWriter(context, buffers)

            And("A stream of messages until we have two blocks")
            val writeMsgs = mutable.MutableList.empty[SbeEncoder]
            while (buffers.length < 2) {
                val writeMsg = validFlowStateInternalMessage(numNats = 2,
                                                     numEgressPorts = 3)
                val writeEncoder = writeMsg._3
                outStream.write(writeEncoder)
                writeMsgs += writeEncoder
            }
            outStream.close()

            Then("We can read from it")
            val inStream = FlowStateReader(context, buffers)
            val readMsgs = mutable.MutableList.empty[SbeEncoder]
            var msg: Option[SbeEncoder] = None
            while ( { msg = inStream.read(); msg }.isDefined) {
                readMsgs += msg.get
            }

            Then("Read and written buffers are the same")
            readMsgs.length shouldBe writeMsgs.length
            for ((writeMsg, readMsg) <- writeMsgs zip readMsgs) {
                assertEqualMessages(readMsg, writeMsg)
            }
        }

        scenario("Reading from a truncated buffer due to failure on write") {
            Given("Opening a flow state reader on previously written")
            val blockFactory = new HeapBlockFactory[TimedBlockHeader](
                config.blockSize, FlowStateBlock)
                .asInstanceOf[BlockFactory[BlockHeader]]
            val buffers = new RingBufferWithFactory[ByteBuffer](
                config.blocksPerPort, null, blockFactory.allocate)
            val blockWriter = new TestByteBufferBlockWriter(
                FlowStateBlock, buffers, config.expirationTime toNanos, 2)
            val snappyWriter = new SnappyBlockWriter(blockWriter, config.blockSize)
            val outStream = new FlowStateWriterImpl(config, snappyWriter)

            When("Writting 4 messages")
            val writeEncoder = validFlowStateInternalMessage(numNats = 2,
                                                     numEgressPorts = 3)._3
            outStream.write(writeEncoder)
            outStream.flush()
            outStream.write(writeEncoder)
            outStream.flush()
            outStream.write(writeEncoder)
            outStream.flush()
            outStream.write(writeEncoder)
            outStream.close()

            Then("It stop after the first truncated message is found")
            val inStream = FlowStateReader(context, buffers)
            var readEncoder = inStream.read().get
            assertEqualMessages(readEncoder, writeEncoder)
            readEncoder = inStream.read().get
            assertEqualMessages(readEncoder, writeEncoder)
            inStream.read() shouldBe None
        }
    }

    feature("Writting/reading from disk with mem mapped files") {
        scenario("Create/Delete file") {
            When("Opening a flow state writer on unexiting port")
            val outStream = FlowStateWriter(context, portId)
            val buffers = outStream.out.out.buffers

            Then("An empty file is created")
            val filePath = Paths.get(
                s"${System.getProperty("minions.db.dir")}" +
                s"${tmpDir.getName}/$portId")
            JFiles.exists(filePath) shouldBe true
            JFiles.size(filePath) shouldBe 0

            When("Writting a single entry")
            val writeMsg = validFlowStateInternalMessage(numNats = 2,
                                                 numEgressPorts = 3)
            val writeEncoder = writeMsg._3
            outStream.write(writeEncoder)

            Then("No flushing done to disk")
            JFiles.size(filePath) shouldBe 0

            When("Flushing the compressed stream")
            outStream.flush()

            Then("File size updated accordingly")
            val size1 = JFiles.size(filePath)
            size1 shouldBe config.blockSize

            When("Deleting the file")
            context.ioManager.close(portId)

            Then("Memory is not released")
            outStream.write(writeEncoder)
            outStream.flush()

            When("Releasing the buffers")
            outStream.clear()

            Then("Buffers should be empty")
            buffers.length shouldBe 0

            And("Trying to delete an already deleted file doesn't fail")
            context.ioManager.remove(portId)
        }

        scenario("Opening a non existing file (new binding)") {
            When("Opening a flow state reader on unexisting port")
            intercept[IOException] {
                FlowStateReader(context, portId)
                // The caller should recover from this failure
            }
        }

        scenario("Clearing the buffers in input stream") {
            Given("A pre-populated file for a given portId")
            val outStream = FlowStateWriter(context, portId)
            val writeMsg1 = validFlowStateInternalMessage(numNats = 2,
                                                  numEgressPorts = 3)
            val writeEncoder1 = writeMsg1._3
            outStream.write(writeEncoder1)
            outStream.flush() // flush cached data.
            outStream.close() // no more writting on this writer.


            And("Opening for reading")
            val inStream = FlowStateReader(context, portId)
            val readEncoder1 = inStream.read().get
            inStream.read() shouldBe None
            Then("The message read is the same as written before")
            assertEqualMessages(readEncoder1, writeEncoder1)
            inStream.in.input.buffers.length shouldBe 1

            When("Clearing the buffers of the input stream")
            inStream.clear()
            inStream.in.input.buffers.length shouldBe 0
        }

        scenario("Reboot (opening an already existing file for reading/writting)") {
            Given("A pre-populated file for a given portId")
            val outStream = FlowStateWriter(context, portId)
            val writeMsg1 = validFlowStateInternalMessage(numNats = 2,
                                                  numEgressPorts = 3)
            val writeEncoder1 = writeMsg1._3
            outStream.write(writeEncoder1)
            outStream.flush() // flush cached data
            outStream.close() // no more writting on this writer

            When("Opening for reading")
            val inStream = FlowStateReader(context, portId)
            var readEncoder1 = inStream.read().get
            inStream.read() shouldBe None
            Then("The message read is the same as written before")
            assertEqualMessages(readEncoder1, writeEncoder1)

            When("Writing on a new output stream")
            val newOutStream = FlowStateWriter(context, portId)
            val writeMsg2 = validFlowStateInternalMessage(numNats = 2,
                                                  numEgressPorts = 3)
            val writeEncoder2 = writeMsg2._3
            newOutStream.out.out.buffers.length shouldBe 1
            newOutStream.write(writeEncoder2)
            newOutStream.flush()
            context.ioManager.clear(portId) // removes any in memory reference

            Then("Opening for reading contains two messages")
            val newInStream = FlowStateReader(context, portId)
            readEncoder1 = newInStream.read().get
            assertEqualMessages(readEncoder1, writeEncoder1)
            val readEncoder2 = newInStream.read().get
            assertEqualMessages(readEncoder2, writeEncoder2)
            newInStream.read() shouldBe None
        }

        scenario("Port migration (request for existing raw flow state)") {
            Given("A pre-populated file for a given portId")
            val outStream = FlowStateWriter(context, portId)
            val writeMsg1 = validFlowStateInternalMessage(numNats = 2,
                                                  numEgressPorts = 3)
            val writeEncoder1 = writeMsg1._3
            outStream.write(writeEncoder1)
            outStream.flush() // flush cached data
            outStream.close() // remove references to underlying buffer
            var buffersSeq = outStream.out.out.buffers.iterator.toIndexedSeq
            val header1 = FlowStateBlock(buffersSeq(0))

            When("Opening raw data for reading")
            val rawInStream = ByteBufferBlockReader(context, portId)
            val rawBlock = ByteBuffer.allocate(config.blockSize)
            buffersSeq = rawInStream.buffers.iterator.toIndexedSeq
            rawInStream.read(rawBlock.array, 0, FlowStateBlock.headerSize)
            val header1_1 = FlowStateBlock(buffersSeq(0))
            rawInStream.read(rawBlock.array, FlowStateBlock.headerSize, header1_1.blockLength)

            header1.blockLength shouldBe header1_1.blockLength
            header1.lastEntryTime shouldBe header1_1.lastEntryTime

            And("Transfer it to another file (using the raw interface)")
            val newPortId = UUID.randomUUID() // so we don't overwrite existing one
            val rawOutStream = ByteBufferBlockWriter(context, newPortId)
            rawOutStream.write(rawBlock.array(), 0, header1_1.blockLength)

            Then("Read from the new file succeeds")
            val inStream = FlowStateReader(context, newPortId)
            val readEncoder = inStream.read().get
            assertEqualMessages(readEncoder, writeEncoder1)
        }
    }
}
