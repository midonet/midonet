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

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.util.Random

import com.typesafe.scalalogging.Logger

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Seconds, Span}
import org.slf4j.LoggerFactory

import org.midonet.util.MidonetEventually
import org.midonet.util.collection.{ObjectPool, OnDemandArrayObjectPool}

@RunWith(classOf[JUnitRunner])
class ByteBufferBlockStreamTest extends FeatureSpec
                                        with Matchers
                                        with BeforeAndAfter
                                        with GivenWhenThen
                                        with MidonetEventually {

    val log = Logger(
        LoggerFactory.getLogger("org.midonet.util.io.stream.bytebuffer-blockstream"))
    val blockSize: Int = 10
    val poolSize: Int = 10
    var currentTime: Long = 0L

    val timeout: Duration = Duration("20s")

    implicit val patience: PatienceConfig = new PatienceConfig(Span(5, Seconds),
                                                               Span(1, Seconds))

    case class TestBlockHeader(override val blockLength: Int) extends BlockHeader

    object TestBlockHeader extends BlockHeaderBuilder[BlockHeader] {

        val LengthOffset = 0

        override val headerSize: Int = 4 //only the length

        override def update(buffer: ByteBuffer, params: AnyVal*): Unit = {
            buffer.putInt(LengthOffset, buffer.position - headerSize)
        }

        override def init(buffer: ByteBuffer): Unit = {
            buffer.putInt(LengthOffset, 0)
            buffer.position(headerSize)
        }

        override def apply(buffer: ByteBuffer): BlockHeader = {
            TestBlockHeader(buffer.getInt(LengthOffset))
        }
    }

    case class TestExpirationHeader(override val blockLength: Int,
                                    override val lastEntryTime: Long)
        extends ExpirationBlockHeader

    object TestExpirationBuilder extends BlockHeaderBuilder[ExpirationBlockHeader] {

        val LengthOffset = 0
        val FirstTimeOffset = 4
        val LastTimeOffset = 12

        override val headerSize: Int = 4 + 8 + 8 // length + timestamps

        override def init(buffer: ByteBuffer): Unit = {
            buffer.putInt(LengthOffset, 0)
            buffer.putLong(FirstTimeOffset, currentTime)
            buffer.putLong(LastTimeOffset, Long.MaxValue)
            buffer.position(headerSize)
        }

        override def update(buffer: ByteBuffer, params: AnyVal*): Unit = {
            buffer.putInt(LengthOffset, buffer.position)
            buffer.putLong(LastTimeOffset, currentTime)
        }

        override def apply(buffer: ByteBuffer): ExpirationBlockHeader = {
            TestExpirationHeader(buffer.getInt(LengthOffset),
                                 buffer.getLong(LastTimeOffset))
        }
    }

    class TestExpiringOutputStream
        (override val blockBuilder: BlockHeaderBuilder[ExpirationBlockHeader],
         override val buffers: mutable.Queue[ByteBuffer],
         override val bufferPool: ObjectPool[ByteBuffer],
         override val allowSplit: Boolean)
    extends ByteBufferBlockOutputStream[ExpirationBlockHeader](
        blockBuilder, buffers, bufferPool, allowSplit) with ExpiringBlockStream {

        override val expirationTime: Long = timeout toMillis

        override def currentClock = currentTime
    }

    object HeapBlockFactory {
        def allocate(pool: ObjectPool[ByteBuffer], size: Int): ByteBuffer = {
            ByteBuffer.allocate(size)
        }
    }

    feature("ByteBufferBlockStream handles writing and reading") {
        scenario("Writing/reading a message smaller than the block size") {
            Given("A byte buffer block stream")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new ByteBufferBlockOutputStream(
                blockBuilder, buffers, pool)

            When("Writing to the stream")
            val outBlock = ByteBuffer.allocate(5)
            Random.nextBytes(outBlock.array())
            outStream.write(outBlock.array())

            Then("The byte buffer only contains the header")
            val position = outStream.buffers.head.position
            position shouldBe blockBuilder.headerSize + 5
            TestBlockHeader(outStream.buffers.head).blockLength shouldBe 5

            And("Reading from the same raw data")
            outStream.close() //so we flush the data
            val inStream = new ByteBufferBlockInputStream(
                blockBuilder, buffers)

            Then("Message is reconstructed")
            val inBlock = ByteBuffer.allocate(5)
            inStream.read(inBlock.array())
            inBlock.array() shouldBe outBlock.array()
        }
        
        scenario("Writing/reading a message exactly the same block size") {
            Given("A byte buffer block stream")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new ByteBufferBlockOutputStream(
                blockBuilder, buffers, pool)

            When("Writing to the stream up to the boundary")
            val outBlock1 = ByteBuffer.allocate(10)
            Random.nextBytes(outBlock1.array())
            outStream.write(outBlock1.array())

            Then("Only one block should have been allocated")
            var position = outStream.buffers.last.position
            position shouldBe blockBuilder.headerSize + 10
            outStream.buffers.length shouldBe 1
            TestBlockHeader(outStream.buffers.head).blockLength shouldBe 10

            When("Writing another buffer")
            val outBlock2 = ByteBuffer.allocate(10)
            Random.nextBytes(outBlock2.array())
            outStream.write(outBlock2.array())

            Then("Another block is allocated")
            position = outStream.buffers.last.position
            position shouldBe blockBuilder.headerSize + 10
            outStream.buffers.length shouldBe 2
            TestBlockHeader(outStream.buffers.last).blockLength shouldBe 10

            And("Reading from the same raw data")
            outStream.close() //so we flush the data
            val inStream = new ByteBufferBlockInputStream(
                blockBuilder, buffers)

            Then("Message is reconstructed")
            var inBlock = ByteBuffer.allocate(10)
            inStream.read(inBlock.array())
            inBlock.array() shouldBe outBlock1.array()
            inStream.read(inBlock.array())
            inBlock.array() shouldBe outBlock2.array()
        }


        scenario("Writing/reading a message bigger than the block size " +
                 "(with block split)") {
            Given("A byte buffer block stream")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new ByteBufferBlockOutputStream(
                blockBuilder, buffers, pool, allowSplit = true)

            When("Writing to the stream up to the boundary")
            val outBlock1 = ByteBuffer.allocate(11)
            Random.nextBytes(outBlock1.array())
            outStream.write(outBlock1.array())

            Then("Two blocks should have been allocated")
            def block(pos: Int) = outStream.buffers.get(pos).get
            outStream.buffers.length shouldBe 2
            block(0).position shouldBe blockBuilder.headerSize + 10
            block(1).position shouldBe blockBuilder.headerSize + 1
            TestBlockHeader(block(0)).blockLength shouldBe 10
            TestBlockHeader(block(1)).blockLength shouldBe 1

            When("Writing another buffer")
            val outBlock2 = ByteBuffer.allocate(10)
            Random.nextBytes(outBlock2.array())
            outStream.write(outBlock2.array())

            Then("Another block is allocated")
            outStream.buffers.length shouldBe 3
            outStream.buffers.get(0).get.position shouldBe blockBuilder.headerSize + 10
            outStream.buffers.get(1).get.position shouldBe blockBuilder.headerSize + 10
            outStream.buffers.get(2).get.position shouldBe blockBuilder.headerSize + 1
            TestBlockHeader(block(0)).blockLength shouldBe 10
            TestBlockHeader(block(1)).blockLength shouldBe 10
            TestBlockHeader(block(2)).blockLength shouldBe 1

            And("Reading from the same raw data")
            outStream.close() //so we flush the data
            val inStream = new ByteBufferBlockInputStream(blockBuilder,
                                                          buffers)

            Then("Message is reconstructed")
            var inBlock1 = ByteBuffer.allocate(11)
            inStream.read(inBlock1.array())
            inBlock1.array() shouldBe outBlock1.array()
            var inBlock2 = ByteBuffer.allocate(10)
            inStream.read(inBlock2.array())
            inBlock2.array() shouldBe outBlock2.array()
        }

        scenario("Writing/reading a message bigger than the block size " +
                 "(without block split)") {
            Given("A byte buffer block stream")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new ByteBufferBlockOutputStream(
                blockBuilder, buffers, pool, allowSplit = false)

            When("Writing to the stream a chunk bigger than the block size")
            val outBlock1 = ByteBuffer.allocate(11)
            Random.nextBytes(outBlock1.array())

            Then("An exception should be thrown")
            intercept[IOException] {
                outStream.write(outBlock1.array())
            }
        }

        scenario("Writting byte by byte is supported and equivalent to writting a chunk") {
            Given("Two byte buffer block streams")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers1 = mutable.Queue.empty[ByteBuffer]
            val pool1 = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream1 = new ByteBufferBlockOutputStream(
                blockBuilder, buffers1, pool1, allowSplit = false)

            val buffers2 = mutable.Queue.empty[ByteBuffer]
            val pool2 = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream2 = new ByteBufferBlockOutputStream(
                blockBuilder, buffers2, pool2, allowSplit = false)

            When("Writing two chunks to first stream")
            val outBlock = ByteBuffer.allocate(20)
            Random.nextBytes(outBlock.array())
            outStream1.write(outBlock.array(), 0 , 10)
            outStream1.write(outBlock.array(), 10 , 10)

            And("Writting the same chunk byte by byte to the second stream")
            outBlock.clear()
            for (b <- outBlock.array()) {
                outStream2.write(b)
            }

            Then("Both arrays are the same")
            def getBlock(stream: ByteBufferBlockOutputStream[BlockHeader], i: Int)
            : Array[Byte] =
                stream.buffers.get(i).get.array()
            getBlock(outStream1, 0) shouldBe getBlock(outStream2, 0)
            getBlock(outStream1, 1) shouldBe getBlock(outStream2, 1)
        }

        scenario("Writting/reading a chunk of data bigger than remianing bytes " +
                 "(without block split)") {
            Given("A byte buffer block stream")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new ByteBufferBlockOutputStream[BlockHeader](
                blockBuilder, buffers, pool, allowSplit = false)

            When("Writing to the stream up to half the size")
            val outBlock1 = ByteBuffer.allocate(5)
            Random.nextBytes(outBlock1.array())
            outStream.write(outBlock1.array())

            Then("Only one block should have been allocated")
            var position = outStream.buffers.last.position
            position shouldBe blockBuilder.headerSize + 5
            outStream.buffers.length shouldBe 1
            TestBlockHeader(outStream.buffers.head).blockLength shouldBe 5

            When("Writing another buffer that doesn't fit on the current block")
            val outBlock2 = ByteBuffer.allocate(10)
            Random.nextBytes(outBlock2.array())
            outStream.write(outBlock2.array())

            Then("Another block is allocated")
            position = outStream.buffers.last.position
            position shouldBe blockBuilder.headerSize + 10
            outStream.buffers.length shouldBe 2
            TestBlockHeader(outStream.buffers.last).blockLength shouldBe 10

            And("Reading from the same raw data")
            outStream.close() //so we flush the data
            val inStream = new ByteBufferBlockInputStream(
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
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val inputStream = new ByteBufferBlockInputStream(
                blockBuilder, buffers)

            Then("Reading from an empty stream returns -1")
            val bb = ByteBuffer.allocate(blockSize)
            inputStream.read(bb.array, 0, blockSize) shouldBe -1
            inputStream.read() shouldBe -1
            inputStream.read(bb.array) shouldBe -1

        }

        scenario("Reading byte by byte handles end of blocks") {
            Given("A byte buffer block stream")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new ByteBufferBlockOutputStream(
                blockBuilder, buffers, pool, allowSplit = true)
            When("Writing to the stream past the block boundary")
            val outBlock = ByteBuffer.allocate(11)
            Random.nextBytes(outBlock.array())
            outStream.write(outBlock.array())

            Then("Reading byte by byte from the input stream")
            val inStream = new ByteBufferBlockInputStream(blockBuilder,
                                                          buffers)
            val inBlock = ByteBuffer.allocate(1)
            Random.nextBytes(inBlock.array())

            for (i <- 0 until 11) {
                val result = inStream.read()
                result shouldBe outBlock.array()(i) & 0xFF
            }
            inStream.read() shouldBe -1

        }

    }

    feature("ByteBufferExpirationStream handles block expiration") {
        scenario("Blocks are removed after expiration time.") {
            Given("An expiration byte buffer block stream")
            val blockBuilder = TestExpirationBuilder
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new TestExpiringOutputStream(blockBuilder,
                                                      buffers,
                                                      pool,
                                                      allowSplit = true)
            When("Starting the clock")
            currentTime = 0

            And("Writing a single message to have a single block")
            val outBlock = ByteBuffer.allocate(blockSize)
            Random.nextBytes(outBlock.array())
            val beforeWrite = currentTime
            outStream.write(outBlock.array)
            currentTime += 1

            Then("The expiration markers should be correctly set")
            outStream.buffers should have size 1
            val header = TestExpirationBuilder(outStream.buffers.head)
            header.lastEntryTime shouldBe beforeWrite
            header.lastEntryTime should be < currentTime

            When("Waiting a timeout amount of time")
            currentTime += (timeout toMillis)

            Then("The block should be released and cleared (on-demand)")
            eventually {
                outStream.invalidateBlocks()
                outStream.buffers should have size 0
                outStream.bufferPool.available shouldBe poolSize
            }

            And("Writing a new message")
            outStream.write(outBlock.array())
            outStream.buffers should have size 1
        }

        scenario("Expiring a single block on a multiblock stream.") {
            Given("An expiration byte buffer block stream")
            val blockBuilder = TestExpirationBuilder
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new OnDemandArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new TestExpiringOutputStream(blockBuilder,
                                                      buffers,
                                                      pool,
                                                      allowSplit = true)

            When("Starting the clock")
            currentTime = 0

            And("Writing a single message bigger than a single block")
            val outBlock = ByteBuffer.allocate(blockSize + blockSize/2)
            Random.nextBytes(outBlock.array())
            outStream.write(outBlock.array)
            currentTime += 1

            def block(pos: Int): ByteBuffer = outStream.buffers.get(pos).get
            outStream.buffers should have size 2
            val header1 = TestExpirationBuilder(block(0))
            var header2 = TestExpirationBuilder(block(1))
            header1.lastEntryTime shouldBe header2.lastEntryTime
            header2.lastEntryTime shouldBe currentTime - 1

            When("Waiting half of the time and refreshing the 2nd block and flushing")
            currentTime += (timeout toMillis)/2
            outStream.write(outBlock.array(), 0, 1)

            Then("The first block timeout remains the same")
            header1 shouldBe TestExpirationBuilder(block(0))
            And("The new block timeouts and length should have been refreshed")
            val oldLength = header2.blockLength
            val oldLastEntryTime = header2.lastEntryTime
            header2 = TestExpirationBuilder(block(1))
            header2.lastEntryTime should be > oldLastEntryTime
            header2.lastEntryTime shouldBe currentTime
            header2.blockLength should be > oldLength
            header1.lastEntryTime should be < header2.lastEntryTime

            When("Waiting for the first block to expire")
            currentTime += (timeout toMillis)/2

            Then("The first block should be released")
            outStream.invalidateBlocks()
            val newHead = TestExpirationBuilder(block(0))
            Then("The new head should be the previous 2nd block")
            newHead shouldBe header2
            outStream.bufferPool.available shouldBe poolSize - 1
            outStream.buffers should have size 1
        }
    }
}


