/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.netlink

import java.nio.ByteBuffer
import java.lang.{Byte => JByte}
import java.lang.{Short => JShort}
import java.lang.{Integer => JInteger}
import java.lang.{Long => JLong}
import scala.util.Random

import org.scalatest._

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class NetlinkMessageTest extends Suite with Matchers {

    type ByteConsumer  = ((Byte,Short)) => Unit
    type ShortConsumer = ((Short,Short)) => Unit
    type IntConsumer   = ((Int,Short)) => Unit
    type LongConsumer  = ((Long,Short)) => Unit

    def testWritingReadingBytesNoPadding() {
        val buf = makeBuffer()
        val data = ByteHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeByteAttrNoPad (buf, id, value)
        }
        buf.flip
        data foreach (ByteHelper bufferCheckerNoPad buf)
    }

    def testWritingReadingBytes() {
        val buf = makeBuffer()
        val data = ByteHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeByteAttr (buf, id, value)
        }
        buf.flip
        data foreach (ByteHelper bufferChecker (BytesUtil.instance sliceOf buf))
        data foreach (ByteHelper checkMessage buf)
        (Random shuffle data.toSeq) foreach (ByteHelper checkMessage buf)
    }

    def testWritingReadingShortsNoPadding() {
        val buf = makeBuffer()
        val data = ShortHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeShortAttrNoPad (buf, id, value)
        }
        buf.flip
        data foreach (ShortHelper bufferCheckerNoPad buf)
    }

    def testWritingReadingShorts() {
        val buf = makeBuffer()
        val data = ShortHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeShortAttr (buf, id, value)
        }
        buf.flip
        data foreach (ShortHelper bufferChecker (BytesUtil.instance sliceOf buf))
        data foreach (ShortHelper checkMessage buf)
        (Random shuffle data.toSeq) foreach (ShortHelper checkMessage buf)
    }

    def testWritingReadingInts() {
        val buf = makeBuffer()
        val data = IntHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeIntAttr (buf, id, value)
        }
        buf.flip
        data foreach (IntHelper bufferChecker (BytesUtil.instance sliceOf buf))
        data foreach (IntHelper checkMessage buf)
        (Random shuffle data.toSeq) foreach (IntHelper checkMessage buf)
    }

    def testWritingReadingLongs() {
        val buf = makeBuffer()
        val data = LongHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeLongAttr (buf, id, value)
        }
        buf.flip
        data foreach (LongHelper bufferChecker (BytesUtil.instance sliceOf buf))
        data foreach (LongHelper checkMessage buf)
        (Random shuffle data.toSeq) foreach (LongHelper checkMessage buf)
    }

    def makeBuffer(size: Int = 1024) = BytesUtil.instance allocate size

    def readPadding(buf: ByteBuffer, byteNum: Int) {
        (0 until byteNum) foreach { _ => buf.get() }
    }

    object ByteHelper {
        def makeData(len: Int) = {
            val bytes = new Array[Byte](len)
            (0 until len) foreach { i => bytes(i) = i.toByte }
            bytes zip bytes.map { _.toShort }
        }
        def bufferChecker(buf: ByteBuffer): ByteConsumer = {
            case (value, id) =>
                buf.getShort() shouldBe 8 // len=2b + id=2b + value=4b
                buf.getShort() shouldBe id
                buf.get() shouldBe value
                readPadding(buf, 3)
        }
        def bufferCheckerNoPad(buf: ByteBuffer): ByteConsumer = {
            case (value, id) =>
                buf.getShort() shouldBe 5 // len=2b + id=2b + value=1b (ignore pad)
                buf.getShort() shouldBe id
                buf.get() shouldBe value
                readPadding(buf, 3)
        }
        def checkMessage(buf: ByteBuffer): ByteConsumer = {
            case (value, id) =>
                buf get (NetlinkMessage seekAttribute (buf, id)) shouldBe value
        }
    }

    object ShortHelper {
        def makeData(len: Int) = {
            val shorts = new Array[Short](len)
            (0 until len) foreach { i => shorts(i) = i.toShort}
            shorts zip shorts
        }
        def bufferChecker(buf: ByteBuffer): ShortConsumer = {
            case (value, id) =>
                buf.getShort() shouldBe 8 // len=2b + id=2b + value=4b
                buf.getShort() shouldBe id
                buf.getShort() shouldBe value
                readPadding(buf, 2)
        }
        def bufferCheckerNoPad(buf: ByteBuffer): ShortConsumer = {
            case (value, id) =>
                buf.getShort() shouldBe 6 // len=2b + id=2b + value=2b (ignore pad)
                buf.getShort() shouldBe id
                buf.getShort() shouldBe value
                readPadding(buf, 2)
        }
        def checkMessage(buf: ByteBuffer): ShortConsumer = {
            case (value, id) =>
                buf getShort (NetlinkMessage seekAttribute (buf, id)) shouldBe value
        }
    }

    object IntHelper {
        def makeData(len: Int) = {
            val ints = new Array[Int](len)
            (0 until len) foreach { i => ints(i) = i}
            ints zip ints.map { _.toShort }
        }
        def bufferChecker(buf: ByteBuffer): IntConsumer = {
            case (value, id) =>
                buf.getShort() shouldBe 8
                buf.getShort() shouldBe id
                buf.getInt() shouldBe value
        }
        def checkMessage(buf: ByteBuffer): IntConsumer = {
            case (value, id) =>
                buf getInt (NetlinkMessage seekAttribute (buf, id)) shouldBe value
        }
    }

    object LongHelper {
        def makeData(len: Int) = {
            val longs = new Array[Long](len)
            (0 until len) foreach { l => longs(l) = l}
            longs zip longs.map { _.toShort }
        }
        def bufferChecker(buf: ByteBuffer): LongConsumer = {
            case (value, id) =>
                buf.getShort() shouldBe 12
                buf.getShort() shouldBe id
                buf.getLong() shouldBe value
        }
        def checkMessage(buf: ByteBuffer): LongConsumer = {
            case (value, id) =>
                buf getLong (NetlinkMessage seekAttribute (buf, id)) shouldBe value
        }
    }

    def testAlign() {
        def trivialAlign(x: Int): Int = if ((x & 3) == 0) x else (x & ~3) + 4
        List.fill(1000) { Random nextInt 0x10000 }
            .map { x => (NetlinkMessage align x, trivialAlign(x)) }
            .foreach { case (x,y) => x shouldBe y }
    }

    def testAlignBuffer() {
        val buf = ByteBuffer allocate 512
        (1 to 1000) foreach { _ =>
            val pos = Random nextInt 500
            buf position pos
            NetlinkMessage alignBuffer buf
            val aligned = buf.position
            (aligned & 3) shouldBe 0
            aligned shouldBe (NetlinkMessage align pos)
        }
    }

    def testSeekIntAttribute() {
        val buf = ByteBuffer allocate 1024
        (1 to 1000) foreach { _ =>
            val l = generateIntAttrs(2 + (Random nextInt 10))
            l foreach { case (id,attr) =>
                NetlinkMessage writeIntAttr (buf, id, attr)
            }
            buf.flip
            (Random shuffle l) foreach { case (id,attr) =>
                val pos = NetlinkMessage seekAttribute (buf,id)
                pos should not be < (0)
                (buf getInt pos) shouldBe attr
            }
            buf.clear
        }
    }

    def testSeekStringAttribute() {
        val buf = ByteBuffer allocate 1024
        (1 to 1000) foreach { _ =>
            val l = generateStringAttrs(2 + (Random nextInt 10))
            l foreach { case (id,s) =>
                NetlinkMessage writeStringAttr (buf, id, s)
            }
            buf.flip
            (Random shuffle l) foreach { case (id,s) =>
                val pos = NetlinkMessage seekAttribute (buf,id)
                pos should not be < (0)
                (NetlinkMessage parseStringAttr (buf,pos)) shouldBe s
            }
            buf.clear
        }
    }

    def testReadAttribute() {
        val reader = new Reader[Array[Byte]] {
            def deserializeFrom(buf: ByteBuffer) = {
                val bytes = new Array[Byte](buf.remaining - 1) // ignore '\0'
                buf get bytes
                bytes
            }
        }
        val buf = ByteBuffer allocate 1024
        (1 to 1000) foreach { _ =>
            buf.clear
            val l = generateStringAttrs(2 + (Random nextInt 10))
            l foreach { case (id,s) =>
                NetlinkMessage writeStringAttr (buf, id, s)
            }
            buf.flip
            (Random shuffle l) foreach { case (id,s) =>
                (NetlinkMessage readAttr (buf, id, reader)) shouldBe s.getBytes
            }
        }
    }

    def testScanIntAttributes() {
        val buf = ByteBuffer allocate 1024
        (1 to 1000) foreach { _ =>
            val l = generateIntAttrs(2 + (Random nextInt 10))
            l foreach { case (id,attr) =>
                NetlinkMessage writeIntAttr (buf, id, attr)
            }
            buf.flip
            val handler = new Handler(_.getInt)
            NetlinkMessage scanAttributes (buf, handler)
            handler.set should have size(l.size)
            l foreach { handler.set should contain(_) }
            buf.clear
        }
    }

    def testScanStringAttributes() {
        val buf = ByteBuffer allocate 1024
        (1 to 1000) foreach { _ =>
            val l = generateStringAttrs(2 + (Random nextInt 10))
            l foreach { case (id,s) =>
                NetlinkMessage writeStringAttr (buf, id, s)
            }
            buf.flip
            val handler = new Handler ( buf =>
                NetlinkMessage parseStringAttr (buf,buf.position)
            )
            NetlinkMessage scanAttributes (buf, handler)
            handler.set should have size(l.size)
            l foreach { handler.set should contain(_) }
            buf.clear
        }
    }

    class Handler[T](trans: ByteBuffer => T) extends AttributeHandler {
        var set = Set.empty[(Short,T)]
        def use(buf: ByteBuffer, id: Short) { set += Tuple2(id, trans(buf)) }
    }

    def generateIntAttrs(n: Int): List[(Short,Int)] =
        Random shuffle List.tabulate(n) { i: Int => (i.toShort, i) }

    def generateStringAttrs(n: Int): List[(Short,String)] =
        Random shuffle List.tabulate(n) { i: Int =>
            (i.toShort, Random.alphanumeric.take(4 + (Random nextInt 10)) mkString "")
        }
}
