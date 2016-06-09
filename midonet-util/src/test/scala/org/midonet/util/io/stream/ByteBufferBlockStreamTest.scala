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

import java.nio.ByteBuffer

import scala.collection.mutable
import scala.concurrent.duration.Duration
import scala.util.Random

import com.google.common.io.Files

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.scalatest.time.{Seconds, Span}

import org.midonet.util.MidonetEventually
import org.midonet.util.collection.{ArrayObjectPool, ObjectPool}
import org.midonet.util.concurrent.NanoClock

@RunWith(classOf[JUnitRunner])
class ByteBufferBlockStreamTest extends FeatureSpec
                                        with Matchers
                                        with BeforeAndAfter
                                        with GivenWhenThen
                                        with MidonetEventually {

    val blockSize: Int = 10
    val poolSize: Int = 10
    val timeout: Duration = Duration("20s")

    implicit val patience: PatienceConfig = new PatienceConfig(Span(5, Seconds),
                                                               Span(1, Seconds))

    case class TestBlockHeader(override val blockLength: Int) extends BlockHeader

    object TestBlockHeader extends BlockHeaderBuilder[BlockHeader] {

        val LengthOffset = 0

        override val headerSize: Int = 4 //only the length

        override def update(buffer: ByteBuffer): Unit = {
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
                                    override val firstEntryTime: Long,
                                    override val lastEntryTime: Long)
        extends ExpirationBlockHeader

    object TestExpirationBuilder extends BlockHeaderBuilder[ExpirationBlockHeader] {

        val LengthOffset = 0
        val FirstTimeOffset = 4
        val LastTimeOffset = 12

        override val headerSize: Int = 4 + 8 + 8 // length + timestamps

        override def init(buffer: ByteBuffer): Unit = {
            buffer.putInt(LengthOffset, 0)
            buffer.putLong(FirstTimeOffset, NanoClock.DEFAULT.tick)
            buffer.putLong(LastTimeOffset, Long.MaxValue)
            buffer.position(headerSize)
        }

        override def update(buffer: ByteBuffer): Unit = {
            buffer.putInt(LengthOffset, buffer.position)
            buffer.putLong(LastTimeOffset, NanoClock.DEFAULT.tick)
        }

        override def apply(buffer: ByteBuffer): ExpirationBlockHeader = {
            TestExpirationHeader(buffer.getInt(LengthOffset),
                                 buffer.getLong(FirstTimeOffset),
                                 buffer.getLong(LastTimeOffset))
        }
    }

    class TestExpiringOutputStream
        (override val blockBuilder: BlockHeaderBuilder[ExpirationBlockHeader],
         override val buffers: mutable.Queue[ByteBuffer],
         override val bufferPool: ObjectPool[ByteBuffer])
    extends ByteBufferBlockOutputStream[ExpirationBlockHeader](
        blockBuilder, buffers, bufferPool) with ExpiringBlockStream {

        override val expirationTime: Long = timeout toNanos
    }

    object HeapBlockFactory {
        def allocate(pool: ObjectPool[ByteBuffer], size: Int): ByteBuffer = {
            ByteBuffer.allocate(size)
        }
    }

    before {
        val tmpDir = Files.createTempDir()
        System.setProperty("midolman.log.dir", System.getProperty("java.io.tmpdir"))
    }


    feature("ByteBufferBlockStream handles writing and reading") {
        scenario("Writing/reading a message smaller than the block size") {
            Given("A byte buffer block stream")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new ArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new ByteBufferBlockOutputStream(blockBuilder,
                                                            buffers,
                                                            pool)

            When("Writing to the stream")
            val outBlock = ByteBuffer.allocate(5)
            Random.nextBytes(outBlock.array())
            outStream.write(outBlock.array())

            Then("The byte buffer only contains the header")
            val position = outStream.buffers.head.position
            position shouldBe blockBuilder.headerSize + 5
            TestBlockHeader(outStream.buffers.head).blockLength shouldBe 5

            When("Closing and flushing the stream")
            outStream.close()

            Then("Only one block should have been allocated")
            outStream.buffers.length shouldBe 1
            outStream.buffers.head.position shouldBe blockBuilder.headerSize + 5
            TestBlockHeader(outStream.buffers.head).blockLength shouldBe 5

            And("Reading from the same raw data")
            val inStream = new ByteBufferBlockInputStream(blockBuilder,
                                                          buffers,
                                                          pool)

            Then("Message is reconstructed")
            outStream.close() //so we flush the data
            val inBlock = ByteBuffer.allocate(5)
            inStream.read(inBlock.array())
            inBlock.array() shouldBe outBlock.array()
        }
        
        scenario("Writingreading a message exactly the same block size") {
            Given("A byte buffer block stream")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new ArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new ByteBufferBlockOutputStream(blockBuilder,
                                                            buffers,
                                                            pool)

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
            val inStream = new ByteBufferBlockInputStream(blockBuilder,
                                                          buffers,
                                                          pool)

            Then("Message is reconstructed")
            outStream.close() //so we flush the data
            var inBlock = ByteBuffer.allocate(10)
            inStream.read(inBlock.array())
            inBlock.array() shouldBe outBlock1.array()
            inStream.read(inBlock.array())
            inBlock.array() shouldBe outBlock2.array()
        }


        scenario("Writing/reading a message bigger than the block size") {
            Given("A byte buffer block stream")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new ArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new ByteBufferBlockOutputStream(blockBuilder,
                                                            buffers,
                                                            pool)

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
            val inStream = new ByteBufferBlockInputStream(blockBuilder,
                                                          buffers,
                                                          pool)

            Then("Message is reconstructed")
            outStream.close() //so we flush the data
            var inBlock1 = ByteBuffer.allocate(11)
            inStream.read(inBlock1.array())
            inBlock1.array() shouldBe outBlock1.array()
            var inBlock2 = ByteBuffer.allocate(10)
            inStream.read(inBlock2.array())
            inBlock2.array() shouldBe outBlock2.array()
        }

        scenario("Reading from an empty stream") {
            Given("A byte buffer block stream")
            val blockBuilder = TestBlockHeader
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new ArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val inputStream = new ByteBufferBlockInputStream(blockBuilder,
                                                             buffers,
                                                             pool)

            Then("Reading from an empty stream returns -1")
            val bb = ByteBuffer.allocate(blockSize)
            inputStream.read(bb.array, 0, blockSize) shouldBe -1
            inputStream.read() shouldBe -1
            inputStream.read(bb.array) shouldBe -1

        }

    }

    feature("ByteBufferExpirationStream handles block expiration") {
        scenario("Blocks are removed after expiration time.") {
            Given("An expiration byte buffer block stream")
            val blockBuilder = TestExpirationBuilder
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new ArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new TestExpiringOutputStream(blockBuilder,
                                                      buffers,
                                                      pool)

            And("Writing a single message to have a single block")
            val outBlock = ByteBuffer.allocate(blockSize)
            Random.nextBytes(outBlock.array())
            val beforeWriting = NanoClock.DEFAULT.tick
            outStream.write(outBlock.array)

            Then("The expiration markers should be correctly set")
            outStream.buffers should have size 1
            val header = TestExpirationBuilder(outStream.buffers.head)
            header.firstEntryTime should be > beforeWriting
            header.lastEntryTime should be > beforeWriting

            When("Waiting a timeout amount of time")
            Thread.sleep(timeout toMillis)

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
            Given("A flow state storage stream")
            Given("An expiration byte buffer block stream")
            val blockBuilder = TestExpirationBuilder
            val testBlockSize = blockSize + blockBuilder.headerSize
            val buffers = mutable.Queue.empty[ByteBuffer]
            val pool = new ArrayObjectPool[ByteBuffer](
                poolSize, _ => ByteBuffer.allocate(testBlockSize))
            val outStream = new TestExpiringOutputStream(blockBuilder,
                                                      buffers,
                                                      pool)

            And("A stream of small writes up until we have two blocks.")
            val outBlock = ByteBuffer.allocate(1)
            (1 to 11) foreach { x =>
                Random.nextBytes(outBlock.array())
                outStream.write(outBlock.array)
                Thread.sleep(1)
            }
            def block(pos: Int) = outStream.buffers.get(pos).get
            outStream.buffers should have size 2
            val header1 = TestExpirationBuilder(block(0))
            val header2 = TestExpirationBuilder(block(1))
            header1.lastEntryTime should be < header2.firstEntryTime
            header2.firstEntryTime should be < header2.lastEntryTime

            When("Waiting half of the time and refreshing the 2nd block")
            Thread.sleep((timeout toMillis)/2)
            outStream.write(outBlock.array())

            Then("The first block timeout remains the same")
            header1 shouldBe TestExpirationBuilder(block(0))
            And("The new block timeouts should have been refreshed")
            val newHeader2 = TestExpirationBuilder(block(1))
            header2.firstEntryTime shouldBe newHeader2.firstEntryTime
            header2.lastEntryTime should be < newHeader2.lastEntryTime
            header2.blockLength should be < newHeader2.blockLength

            When("Waiting for the first block to expire")
            Thread.sleep((timeout toMillis)/2)

            Then("The first block should be released")
            eventually {
                outStream.invalidateBlocks()
                val newHead = TestExpirationBuilder(block(0))
                Then("The new head should be the previous 2nd block")
                newHead shouldBe newHeader2
                outStream.bufferPool.available shouldBe poolSize - 1
                outStream.buffers should have size 1
            }
        }
    }
}


