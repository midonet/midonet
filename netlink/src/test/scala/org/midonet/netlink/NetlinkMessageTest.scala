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

import org.midonet.netlink.messages.Builder

@org.junit.runner.RunWith(classOf[org.scalatest.junit.JUnitRunner])
class NetlinkMessageTest extends Suite with Matchers {

    type ByteConsumer  = ((Byte, NetlinkMessage.AttrKey[JByte])) => Unit
    type ShortConsumer = ((Short, NetlinkMessage.AttrKey[JShort])) => Unit
    type IntConsumer   = ((Int, NetlinkMessage.AttrKey[JInteger])) => Unit
    type LongConsumer  = ((Long, NetlinkMessage.AttrKey[JLong])) => Unit

    def testWritingReadingBytes() {
        val bldr = makeMsgBuilger()
        val data = ByteHelper.makeData(4)
        data foreach { case (value, attr) => bldr.addAttr(attr, value) }
        val msg = bldr.build()
        data foreach ByteHelper.bufferChecker(sliceOf(msg))
        data foreach ByteHelper.checkMessage(msg)
    }

    def testWritingReadingShortsNoPadding() {
        val bldr = makeMsgBuilger()
        val data = ShortHelper.makeData(4)
        data foreach { case (value, attr) => bldr.addAttrNoPad(attr, value) }
        val msg = bldr.build()
        data foreach ShortHelper.bufferCheckerNoPad(sliceOf(msg))
    }

    def testWritingReadingShorts() {
        val bldr = makeMsgBuilger()
        val data = ShortHelper.makeData(4)
        data foreach { case (value, attr) => bldr.addAttr(attr, value) }
        val msg = bldr.build()
        data foreach ShortHelper.bufferChecker(sliceOf(msg))
        data foreach ShortHelper.checkMessage(msg)
        Random.shuffle(data.toSeq) foreach ShortHelper.checkMessage(msg)
    }

    def testWritingReadingInts() {
        val bldr = makeMsgBuilger()
        val data = IntHelper.makeData(4)
        data foreach { case (value, attr) => bldr.addAttr(attr, value) }
        val msg = bldr.build()
        data foreach IntHelper.bufferChecker(sliceOf(msg))
        data foreach IntHelper.checkMessage(msg)
        Random.shuffle(data.toSeq) foreach IntHelper.checkMessage(msg)
    }

    def testWritingReadingLongs() {
        val bldr = makeMsgBuilger()
        val data = LongHelper.makeData(4)
        data foreach { case (value, attr) => bldr.addAttr(attr, value) }
        val msg = bldr.build()
        data foreach LongHelper.bufferChecker(sliceOf(msg))
        data foreach LongHelper.checkMessage(msg)
        Random.shuffle(data.toSeq) foreach LongHelper.checkMessage(msg)
    }

    def makeNLMsg(size: Int = 1024) =
        new NetlinkMessage(BytesUtil.instance allocate size)

    def makeMsgBuilger(size: Int = 1024) =
        new Builder(BytesUtil.instance allocate size)

    def readPadding(buf: ByteBuffer, byteNum: Int) {
        (0 until byteNum) foreach { _ => buf.get() }
    }

    object ByteHelper {
        def makeData(len: Int) = {
            val bytes = new Array[Byte](len)
            (0 until len) foreach { i => bytes(i) = i.toByte}
            bytes zip bytes.map{ NetlinkMessage.AttrKey.attr[JByte](_) }
        }
        def bufferChecker(buf: ByteBuffer): ByteConsumer = {
            case (value, attr) =>
                buf.getShort() should be(8) // len=2b + id=2b + value=4b
                buf.getShort() should be(attr.getId)
                buf.get() should be(value)
                readPadding(buf, 3)
        }
        def bufferCheckerNoPad(buf: ByteBuffer): ByteConsumer = {
            case (value, attr) =>
                buf.getShort() should be(5) // len=2b + id=2b + value=1b (ignore pad)
                buf.getShort() should be(attr.getId)
                buf.get() should be(value)
                readPadding(buf, 3)
        }
        def checkMessage(msg: NetlinkMessage): ByteConsumer = {
            case (value, attr) => msg.getAttrValueByte(attr) should be(value)
        }
    }

    object ShortHelper {
        def makeData(len: Int) = {
            val shorts = new Array[Short](len)
            (0 until len) foreach { i => shorts(i) = i.toShort}
            shorts zip shorts.map{  NetlinkMessage.AttrKey.attr[JShort](_) }
        }
        def bufferChecker(buf: ByteBuffer): ShortConsumer = {
            case (value, attr) =>
                buf.getShort() should be(8) // len=2b + id=2b + value=4b
                buf.getShort() should be(attr.getId)
                buf.getShort() should be(value)
                readPadding(buf, 2)
        }
        def bufferCheckerNoPad(buf: ByteBuffer): ShortConsumer = {
            case (value, attr) =>
                buf.getShort() should be(6) // len=2b + id=2b + value=2b (ignore pad)
                buf.getShort() should be(attr.getId)
                buf.getShort() should be(value)
                readPadding(buf, 2)
        }
        def checkMessage(msg: NetlinkMessage): ShortConsumer = {
            case (value, attr) => msg.getAttrValueShort(attr) should be(value)
        }
    }

    object IntHelper {
        def makeData(len: Int) = {
            val ints = new Array[Int](len)
            (0 until len) foreach { i => ints(i) = i}
            ints zip ints.map{ NetlinkMessage.AttrKey.attr[JInteger](_) }
        }
        def bufferChecker(buf: ByteBuffer): IntConsumer = {
            case (value, attr) =>
                buf.getShort() should be(8)
                buf.getShort() should be(attr.getId)
                buf.getInt() should be(value)
        }
        def checkMessage(msg: NetlinkMessage): IntConsumer = {
            case (value, attr) => msg.getAttrValueInt(attr) should be(value)
        }
    }

    object LongHelper {
        def makeData(len: Int) = {
            val longs = new Array[Long](len)
            (0 until len) foreach { l => longs(l) = l}
            longs zip longs.map {
                case l => NetlinkMessage.AttrKey.attr[JLong](l.toInt)
            }
        }
        def bufferChecker(buf: ByteBuffer): LongConsumer = {
            case (value, attr) =>
                buf.getShort() should be(12)
                buf.getShort() should be(attr.getId)
                buf.getLong() should be(value)
        }
        def checkMessage(msg: NetlinkMessage): LongConsumer = {
            case (value, attr) => msg.getAttrValueLong(attr) should be(value)
        }
    }

    def testWritingReadingBytesNoPadding() {
        val bldr = makeMsgBuilger()
        val data = ByteHelper.makeData(4)
        data foreach { case (value, attr) => bldr.addAttrNoPad(attr, value) }
        val msg = bldr.build()
        data foreach ByteHelper.bufferCheckerNoPad(sliceOf(msg))
    }

    def sliceOf(msg: NetlinkMessage) = BytesUtil.instance sliceOf msg.getBuffer

}
