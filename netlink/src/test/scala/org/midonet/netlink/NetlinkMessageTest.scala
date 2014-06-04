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

    def testWritingReadingBytes() {
        val buf = makeBuffer()
        val data = ByteHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeByteAttr (buf, id, value)
        }
        buf.flip
        val msg = new NetlinkMessage(buf)
        data foreach (ByteHelper bufferChecker sliceOf(msg))
        data foreach (ByteHelper checkMessage msg)
        (Random shuffle data.toSeq) foreach (ByteHelper checkMessage msg)
    }

    def testWritingReadingShortsNoPadding() {
        val buf = makeBuffer()
        val data = ShortHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeShortAttrNoPad (buf, id, value)
        }
        buf.flip
        val msg = new NetlinkMessage(buf)
        data foreach (ShortHelper bufferCheckerNoPad sliceOf(msg))
    }

    def testWritingReadingShorts() {
        val buf = makeBuffer()
        val data = ShortHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeShortAttr (buf, id, value)
        }
        buf.flip
        val msg = new NetlinkMessage(buf)
        data foreach (ShortHelper bufferChecker sliceOf(msg))
        data foreach (ShortHelper checkMessage msg)
        (Random shuffle data.toSeq) foreach (ShortHelper checkMessage msg)
    }

    def testWritingReadingInts() {
        val buf = makeBuffer()
        val data = IntHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeIntAttr (buf, id, value)
        }
        buf.flip
        val msg = new NetlinkMessage(buf)
        data foreach (IntHelper bufferChecker sliceOf(msg))
        data foreach (IntHelper checkMessage msg)
        (Random shuffle data.toSeq) foreach (IntHelper checkMessage msg)
    }

    def testWritingReadingLongs() {
        val buf = makeBuffer()
        val data = LongHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeLongAttr (buf, id, value)
        }
        buf.flip
        val msg = new NetlinkMessage(buf)
        data foreach (LongHelper bufferChecker sliceOf(msg))
        data foreach (LongHelper checkMessage msg)
        (Random shuffle data.toSeq) foreach (LongHelper checkMessage msg)
    }

    def makeNLMsg(size: Int = 1024) = new NetlinkMessage(makeBuffer(size))

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
        def checkMessage(msg: NetlinkMessage): ByteConsumer = {
            case (value, id) => (msg getAttrValueByte id) shouldBe value
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
        def checkMessage(msg: NetlinkMessage): ShortConsumer = {
            case (value, id) => (msg getAttrValueShort id) shouldBe value
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
        def checkMessage(msg: NetlinkMessage): IntConsumer = {
            case (value, id) => (msg getAttrValueInt id) shouldBe value
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
        def checkMessage(msg: NetlinkMessage): LongConsumer = {
            case (value, id) =>
                (msg getAttrValueLong id) shouldBe value
        }
    }

    def testWritingReadingBytesNoPadding() {
        val buf = makeBuffer()
        val data = ByteHelper makeData 4
        data foreach { case (value, id) =>
            NetlinkMessage writeByteAttrNoPad (buf, id, value)
        }
        buf.flip
        val msg = new NetlinkMessage(buf)
        data foreach (ByteHelper bufferCheckerNoPad sliceOf(msg))
    }

    def sliceOf(msg: NetlinkMessage) = BytesUtil.instance sliceOf msg.getBuffer

}
