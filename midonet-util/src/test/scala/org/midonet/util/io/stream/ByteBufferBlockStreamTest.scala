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

package org.midonet.util.io.stream

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.{StandardOpenOption, Files => JFiles}

import scala.concurrent.duration.Duration
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Seconds, Span}

import org.midonet.util.MidonetEventually
import org.midonet.util.collection.RingBufferWithFactory

@RunWith(classOf[JUnitRunner])
class ByteBufferBlockStreamTest extends FeatureSpec
                                        with Matchers
                                        with BeforeAndAfter
                                        with GivenWhenThen
                                        with MidonetEventually {

    val blockSize: Int = 10
    val poolSize: Int = 10
    var currentTime: Long = 0L

    val timeout: Long = Duration("20s") toNanos

    implicit val patience: PatienceConfig = new PatienceConfig(Span(5, Seconds),
                                                               Span(1, Seconds))

    case class TestTimedBlockHeader(override val blockLength: Int,
                                    override val lastEntryTime: Long)
        extends TimedBlockHeader {

        override def isValid: Boolean = lastEntryTime != -1

    }

    object TestTimedBlockBuilder extends BlockHeaderBuilder[TimedBlockHeader] {

        val LengthOffset = 0
        val LastTimeOffset = 4

        override val headerSize: Int = 4 + 8 // length + timestamps

        override def init(buffer: ByteBuffer): Unit = {
            val header = TestTimedBlockBuilder(buffer)
            if (!header.isValid) {
                reset(buffer)
            } else {
                buffer.position(header.blockLength + headerSize)
            }
        }

        override def reset(buffer: ByteBuffer): Unit = {
            buffer.putInt(LengthOffset, 0)
            buffer.putLong(LastTimeOffset, currentTime)
            buffer.position(headerSize)
        }

        override def update(buffer: ByteBuffer, params: AnyVal*): Unit = {
            buffer.putInt(LengthOffset, buffer.position - headerSize)
            buffer.putLong(LastTimeOffset, currentTime)
        }

        override def apply(buffer: ByteBuffer): TimedBlockHeader = {
            TestTimedBlockHeader(buffer.getInt(LengthOffset),
                                 buffer.getLong(LastTimeOffset))
        }
    }

    private class TestRingBufferWithFactory(
            poolSize: Int, empty: ByteBuffer, factory: (Int) => ByteBuffer)
        extends RingBufferWithFactory[ByteBuffer](poolSize, empty, factory) {

        override val ring = new Array[ByteBuffer](capacity)

    }

    private def heapBlockFactory(blockBuilder: BlockHeaderBuilder[TimedBlockHeader])
    : BlockFactory[TimedBlockHeader] = {
        val testBlockSize = blockSize + blockBuilder.headerSize
        new HeapBlockFactory[TimedBlockHeader](testBlockSize, blockBuilder)
    }

    private def fileBlockFactory(blockBuilder: BlockHeaderBuilder[TimedBlockHeader])
    : BlockFactory[TimedBlockHeader] = {
        val testBlockSize = blockSize + blockBuilder.headerSize
        val tmpFile = JFiles.createTempFile(null, null)
        val channel = FileChannel.open(tmpFile,
                                       StandardOpenOption.CREATE,
                                       StandardOpenOption.READ,
                                       StandardOpenOption.WRITE)
        new MemoryMappedBlockFactory[TimedBlockHeader](
            channel, testBlockSize, blockBuilder)
    }

    private def getStreams(blockBuilder: BlockHeaderBuilder[TimedBlockHeader],
                           blockFactory: BlockFactory[TimedBlockHeader])
    : (ByteBufferBlockWriter[TimedBlockHeader] with TimedBlockInvalidator[TimedBlockHeader],
        ByteBufferBlockReader[TimedBlockHeader],
        TestRingBufferWithFactory) = {
        val testBlockSize = blockSize + blockBuilder.headerSize

        val buffers = new TestRingBufferWithFactory(
            poolSize, null, blockFactory.allocate)

        val writer =
            new ByteBufferBlockWriter(blockBuilder, buffers, timeout)
                with TimedBlockInvalidator[TimedBlockHeader] {
                override val expirationTime: Long = timeout
                override def currentClock = currentTime
            }

        val reader = new ByteBufferBlockReader(blockBuilder, buffers)
        (writer, reader, buffers)
    }

    feature("ByteBufferBlockStream handles writing and reading") {
        scenario("Writing/reading a message smaller than the block size") {
            Given("A byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (outStream, _, buffers) = getStreams(blockBuilder,
                                                     heapBlockFactory(blockBuilder))

            When("Writing to the stream")
            val outBlock = ByteBuffer.allocate(5)
            Random.nextBytes(outBlock.array())
            outStream.write(outBlock.array())

            Then("The byte buffer only contains the header")
            val position = outStream.buffers.head.get.position
            position shouldBe blockBuilder.headerSize + 5
            blockBuilder(outStream.buffers.head.get).blockLength shouldBe 5

            And("Reading from the same raw data")
            outStream.close() //so we flush the data
            val inStream = new ByteBufferBlockReader(blockBuilder, buffers)

            Then("Message is reconstructed")
            val inBlock = ByteBuffer.allocate(5)
            inStream.read(inBlock.array())
            inBlock.array() shouldBe outBlock.array()
        }

        scenario("Writing/reading a message exactly the same block size") {
            Given("A byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (outStream, _, buffers) = getStreams(blockBuilder,
                                                     heapBlockFactory(blockBuilder))

            When("Writing to the stream up to the boundary")
            val outBlock1 = ByteBuffer.allocate(10)
            Random.nextBytes(outBlock1.array())
            outStream.write(outBlock1.array())

            Then("Only one block should have been allocated")
            var position = outStream.buffers.head.get.position
            position shouldBe blockBuilder.headerSize + 10
            outStream.buffers.length shouldBe 1
            blockBuilder(outStream.buffers.head.get).blockLength shouldBe 10

            When("Writing another buffer")
            val outBlock2 = ByteBuffer.allocate(10)
            Random.nextBytes(outBlock2.array())
            outStream.write(outBlock2.array())

            Then("Another block is allocated")
            position = outStream.buffers.head.get.position
            position shouldBe blockBuilder.headerSize + 10
            outStream.buffers.length shouldBe 2
            blockBuilder(outStream.buffers.head.get).blockLength shouldBe 10

            And("Reading from the same raw data")
            outStream.close() //so we flush the data
            val inStream = new ByteBufferBlockReader(
                blockBuilder, buffers)

            Then("Message is reconstructed")
            var inBlock = ByteBuffer.allocate(10)
            inStream.read(inBlock.array())
            inBlock.array() shouldBe outBlock1.array()
            inStream.read(inBlock.array())
            inBlock.array() shouldBe outBlock2.array()
        }

        scenario("Writing/reading a message bigger than the block size") {
            Given("A byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (outStream, _, buffers) = getStreams(blockBuilder,
                                                     heapBlockFactory(blockBuilder))

            When("Writing to the stream a chunk smaller than block size")
            val outBlock1 = ByteBuffer.allocate(11)
            Random.nextBytes(outBlock1.array())

            Then("An exception should be thrown")
            intercept[IOException] {
                outStream.write(outBlock1.array())
            }
        }

        scenario("Writting/reading a chunk of data bigger than remianing bytes") {
            Given("A byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (outStream, _, buffers) = getStreams(blockBuilder,
                                                     heapBlockFactory(blockBuilder))

            When("Writing to the stream up to half the size")
            val outBlock1 = ByteBuffer.allocate(5)
            Random.nextBytes(outBlock1.array())
            outStream.write(outBlock1.array())

            Then("Only one block should have been allocated")
            var position = outStream.buffers.head.get.position
            position shouldBe blockBuilder.headerSize + 5
            outStream.buffers.length shouldBe 1
            blockBuilder(outStream.buffers.head.get).blockLength shouldBe 5

            When("Writing another buffer that doesn't fit on the current block")
            val outBlock2 = ByteBuffer.allocate(10)
            Random.nextBytes(outBlock2.array())
            outStream.write(outBlock2.array())

            Then("Another block is allocated")
            position = outStream.buffers.head.get.position
            position shouldBe blockBuilder.headerSize + 10
            outStream.buffers.length shouldBe 2
            blockBuilder(outStream.buffers.head.get).blockLength shouldBe 10

            And("Reading from the same raw data")
            outStream.close() //so we flush the data
            val inStream = new ByteBufferBlockReader(
                blockBuilder, buffers)

            Then("Message is reconstructed")
            val inBlock1 = ByteBuffer.allocate(5)
            inStream.read(inBlock1.array())
            inBlock1.array() shouldBe outBlock1.array()
            val inBlock2 = ByteBuffer.allocate(10)
            inStream.read(inBlock2.array())
            inBlock2.array() shouldBe outBlock2.array()


        }

        scenario("Reading from an empty stream") {
            Given("A byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (_, inStream, _) = getStreams(blockBuilder,
                                              heapBlockFactory(blockBuilder))

            Then("Reading from an empty stream returns -1")
            val bb = ByteBuffer.allocate(blockSize)
            inStream.read(bb.array, 0, blockSize) shouldBe -1
            inStream.read(bb.array) shouldBe -1

        }

        scenario("Writting to a full ring buffer") {
            Given("A byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (outStream, _, buffers) = getStreams(blockBuilder,
                                                    heapBlockFactory(blockBuilder))

            When("Filling up the capacity of the ring buffer")
            val outBlock = ByteBuffer.allocate(blockSize)
            Random.nextBytes(outBlock.array())
            (1 to poolSize) foreach { _ =>
                outStream.write(outBlock.array())
            }

            Then("The ring buffer is full")
            buffers.isFull shouldBe true
            val tailHeader = blockBuilder(buffers.peek.get)

            When("When writting a new block")
            outStream.write(outBlock.array())

            Then("The oldest block in the queue is overwritten")
            buffers.isFull shouldBe true
            val newTailHeader = blockBuilder(buffers.peek.get)
            tailHeader should be !== newTailHeader
        }

        scenario("Writting to a full ring buffer backed by mem mapped files") {
            Given("A byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (outStream, _, buffers) = getStreams(blockBuilder,
                                                     fileBlockFactory(blockBuilder))

            When("Filling up the capacity of the ring buffer")
            val outBlock = ByteBuffer.allocate(blockSize)
            Random.nextBytes(outBlock.array())
            (1 to poolSize) foreach { _ =>
                outStream.write(outBlock.array())
            }

            Then("The ring buffer is full")
            buffers.isFull shouldBe true
            val tailHeader = blockBuilder(buffers.peek.get)

            When("When writting a new block")
            outStream.write(outBlock.array())

            Then("The oldest block in the queue is overwritten")
            buffers.isFull shouldBe true
            val newTailHeader = blockBuilder(buffers.peek.get)
            tailHeader should be !== newTailHeader
        }

        scenario("Clearing a stream releases references on the underlying buffers") {
            Given("A byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (outStream, _, buffers) = getStreams(blockBuilder,
                                                     heapBlockFactory(blockBuilder))

            When("Filling up the capacity of the ring buffer")
            val outBlock = ByteBuffer.allocate(blockSize)
            Random.nextBytes(outBlock.array())
            for (i <- 0 until poolSize) {
                outStream.write(outBlock.array())
            }

            Then("The ring buffer is full")
            buffers.isFull shouldBe true
            buffers.isEmpty shouldBe false
            val tailHeader = blockBuilder(buffers.peek.get)

            When("Clearing the writer")
            outStream.clear()
            buffers.isEmpty shouldBe true
            buffers.isFull shouldBe false
            for (i <- 0 until poolSize) {
                buffers.ring(i) shouldBe null
            }
        }

    }

    feature("ByteBufferExpirationStream handles block expiration") {
        scenario("The running block is not expired") {
            Given("An expiration byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (outStream, _, _) = getStreams(blockBuilder,
                                               heapBlockFactory(blockBuilder))

            When("Starting the clock")
            currentTime = 0

            And("Writing a single message to have a single block")
            val outBlock = ByteBuffer.allocate(blockSize)
            Random.nextBytes(outBlock.array())
            val beforeWrite = currentTime
            outStream.write(outBlock.array)
            currentTime += 1

            Then("No blocks should be invalidated")
            outStream.invalidateBlocks() shouldBe 0

            When("Advancing the clock past the timeout time")
            currentTime += (timeout * 2)

            Then("No blocks should be invalidated because we have only one")
            outStream.invalidateBlocks() shouldBe 0

        }

        scenario("Blocks are removed after expiration time.") {
            Given("An expiration byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (outStream, _, _) = getStreams(blockBuilder,
                                               heapBlockFactory(blockBuilder))

            When("Starting the clock")
            currentTime = 0

            And("Writing a single block")
            val outBlock = ByteBuffer.allocate(blockSize)
            Random.nextBytes(outBlock.array())
            outStream.write(outBlock.array)

            And("Writing a second block after some time")
            currentTime += 1
            val beforeWrite = currentTime
            outStream.write(outBlock.array)
            currentTime += 1

            Then("The expiration markers should be correctly set")
            outStream.buffers.length shouldBe 2
            val blocks = outStream.buffers.iterator.toIndexedSeq
            val header = blockBuilder(blocks(1))
            val tail = blockBuilder(blocks(0))
            header.lastEntryTime shouldBe beforeWrite
            header.lastEntryTime should be < currentTime
            tail.lastEntryTime should be < header.lastEntryTime

            When("Waiting a timeout amount of time")
            currentTime += timeout

            Then("The head block should be released and cleared")
            eventually {
                outStream.invalidateBlocks() shouldBe 1
                outStream.buffers.length shouldBe 1
                outStream.buffers.isEmpty shouldBe false
            }
        }

        scenario("Expiring a single block on a multiblock stream.") {
            Given("An expiration byte buffer block stream")
            val blockBuilder = TestTimedBlockBuilder
            val (outStream, _, _) = getStreams(blockBuilder,
                                               heapBlockFactory(blockBuilder))

            When("Starting the clock")
            currentTime = 0

            And("Writing a single message bigger than a single block")
            val outBlock1 = ByteBuffer.allocate(blockSize)
            val outBlock2 = ByteBuffer.allocate(blockSize/2)
            Random.nextBytes(outBlock1.array())
            Random.nextBytes(outBlock2.array())
            outStream.write(outBlock1.array)
            outStream.write(outBlock2.array)
            currentTime += 1

            var blocks = outStream.buffers.iterator.toIndexedSeq
            outStream.buffers.length shouldBe 2
            val header1 = TestTimedBlockBuilder(blocks(0))
            var header2 = TestTimedBlockBuilder(blocks(1))
            header1.lastEntryTime shouldBe header2.lastEntryTime
            header2.lastEntryTime shouldBe currentTime - 1

            When("Waiting half of the time and refreshing the 2nd block and flushing")
            currentTime += timeout
            val outBlock3 = ByteBuffer.allocate(1)
            Random.nextBytes(outBlock3.array())
            outStream.write(outBlock3.array())

            Then("The first block timeout remains the same")
            header1 shouldBe TestTimedBlockBuilder(blocks(0))
            And("The new block timeouts and length should have been refreshed")
            val oldLength = header2.blockLength
            val oldLastEntryTime = header2.lastEntryTime
            header2 = TestTimedBlockBuilder(blocks(1))
            header2.lastEntryTime should be > oldLastEntryTime
            header2.lastEntryTime shouldBe currentTime
            header2.blockLength should be > oldLength
            header1.lastEntryTime should be < header2.lastEntryTime

            When("Waiting for the first block to expire")
            currentTime += timeout / 2

            Then("The first block should be released")
            outStream.invalidateBlocks() shouldBe 1
            blocks = outStream.buffers.iterator.toIndexedSeq
            val newHead = TestTimedBlockBuilder(blocks(0))
            Then("The new head should be the previous 2nd block")
            newHead shouldBe header2
            outStream.buffers.length shouldBe 1

            When("Writting a new block")
            outStream.write(outBlock1.array())
            And("Fast forwarding time")
            currentTime += timeout + 1

            Then("We should invalidate 1 block (but the header)")
            outStream.invalidateBlocks() shouldBe 1
            And("We should invalidate the header if no excluded blocks")
            outStream.invalidateBlocks(excludeBlocks = 0) shouldBe 1

        }
    }
}


