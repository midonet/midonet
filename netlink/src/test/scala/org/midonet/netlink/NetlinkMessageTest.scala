/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
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
                (NetlinkMessage getAttrValueByte (buf, id)) shouldBe value
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
                (NetlinkMessage getAttrValueShort (buf, id)) shouldBe value
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
                (NetlinkMessage getAttrValueInt (buf, id)) shouldBe value
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
                (NetlinkMessage getAttrValueLong (buf, id)) shouldBe value
        }
    }
}
