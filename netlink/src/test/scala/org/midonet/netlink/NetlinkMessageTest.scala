/*
 * Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.
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

  def testWritingReadingBytes() {
    val bldr = makeMsgBuilger()
    val data = ByteHelper.makeData(4)
    data foreach { case (value, attr) => bldr.addAttr(attr, value) }
    val msg = bldr.build()
    data foreach ByteHelper.bufferChecker(msg.getBuffer.slice)
    data foreach ByteHelper.checkMessage(msg)
  }

  def testWritingReadingShortsNoPadding() {
    val bldr = makeMsgBuilger()
    val data = ShortHelper.makeData(4)
    data foreach { case (value, attr) => bldr.addAttrNoPad(attr, value) }
    val msg = bldr.build()
    data foreach ShortHelper.bufferCheckerNoPad(msg.getBuffer.slice)
  }

  def testWritingReadingShorts() {
    val bldr = makeMsgBuilger()
    val data = ShortHelper.makeData(4)
    data foreach { case (value, attr) => bldr.addAttr(attr, value) }
    val msg = bldr.build()
    data foreach ShortHelper.bufferChecker(msg.getBuffer().slice())
    data foreach ShortHelper.checkMessage(msg)
    Random.shuffle(data.toSeq) foreach ShortHelper.checkMessage(msg)
  }

  def testWritingReadingInts() {
    val bldr = makeMsgBuilger()
    val data = IntHelper.makeData(4)
    data foreach { case (value, attr) => bldr.addAttr(attr, value) }
    val msg = bldr.build()
    data foreach IntHelper.bufferChecker(msg.getBuffer.slice)
    data foreach IntHelper.checkMessage(msg)
    Random.shuffle(data.toSeq) foreach IntHelper.checkMessage(msg)
  }

  def testWritingReadingLongs() {
    val bldr = makeMsgBuilger()
    val data = LongHelper.makeData(4)
    data foreach { case (value, attr) => bldr.addAttr(attr, value) }
    val msg = bldr.build()
    data foreach LongHelper.bufferChecker(msg.getBuffer.slice)
    data foreach LongHelper.checkMessage(msg)
    Random.shuffle(data.toSeq) foreach LongHelper.checkMessage(msg)
  }

  def makeNLMsg(size: Int = 1024) =
    new NetlinkMessage(ByteBuffer.allocate(size))

  def makeMsgBuilger(size: Int = 1024) =
    NetlinkMessage.newMessageBuilder(ByteBuffer.allocate(size))

  def readPadding(buf: ByteBuffer, byteNum: Int) {
    (0 until byteNum) foreach { _ => buf.get() }
  }

 object ByteHelper {
    def makeData(len: Int) = {
      val bytes = new Array[Byte](len)
      (0 until len) foreach { i => bytes(i) = i.toByte}
      bytes zip bytes.map{ new NetlinkMessage.AttrKey[java.lang.Byte](_) }
    }
    def bufferChecker(buf: ByteBuffer):
      ((Byte,NetlinkMessage.AttrKey[JByte])) => Unit = {
        case (value, attr) =>
          buf.getShort() should be(8) // len=2b + id=2b + value=4b
          buf.getShort() should be(attr.getId)
          buf.get() should be(value)
          readPadding(buf, 3)
      }
    def bufferCheckerNoPad(buf: ByteBuffer):
      ((Byte,NetlinkMessage.AttrKey[JByte])) => Unit = {
        case (value, attr) =>
          buf.getShort() should be(5) // len=2b + id=2b + value=1b (ignore pad)
          buf.getShort() should be(attr.getId)
          buf.get() should be(value)
          readPadding(buf, 3)
      }
    def checkMessage(msg: NetlinkMessage):
      ((Byte,NetlinkMessage.AttrKey[JByte])) => Unit =
        { case (value, attr) => msg.getAttrValueByte(attr) should be(value) }
  }

 object ShortHelper {
    def makeData(len: Int) = {
      val shorts = new Array[Short](len)
      (0 until len) foreach { i => shorts(i) = i.toShort}
      shorts zip shorts.map{ new NetlinkMessage.AttrKey[java.lang.Short](_) }
    }
    def bufferChecker(buf: ByteBuffer):
      ((Short,NetlinkMessage.AttrKey[JShort])) => Unit = {
        case (value, attr) =>
          buf.getShort() should be(8) // len=2b + id=2b + value=4b
          buf.getShort() should be(attr.getId)
          buf.getShort() should be(value)
          readPadding(buf, 2)
      }
    def bufferCheckerNoPad(buf: ByteBuffer):
      ((Short,NetlinkMessage.AttrKey[JShort])) => Unit = {
        case (value, attr) =>
          buf.getShort() should be(6) // len=2b + id=2b + value=2b (ignore pad)
          buf.getShort() should be(attr.getId)
          buf.getShort() should be(value)
          readPadding(buf, 2)
      }
    def checkMessage(msg: NetlinkMessage):
      ((Short,NetlinkMessage.AttrKey[JShort])) => Unit =
        { case (value, attr) => msg.getAttrValueShort(attr) should be(value) }
  }

 object IntHelper {
    def makeData(len: Int) = {
      val ints = new Array[Int](len)
      (0 until len) foreach { i => ints(i) = i}
      ints zip ints.map{ new NetlinkMessage.AttrKey[java.lang.Integer](_) }
    }
    def bufferChecker(buf: ByteBuffer):
      ((Int,NetlinkMessage.AttrKey[JInteger])) => Unit = {
        case (value, attr) =>
          buf.getShort() should be(8)
          buf.getShort() should be(attr.getId)
          buf.getInt() should be(value)
      }
    def checkMessage(msg: NetlinkMessage):
      ((Int,NetlinkMessage.AttrKey[JInteger])) => Unit =
        { case (value, attr) => msg.getAttrValueInt(attr) should be(value) }
  }

  object LongHelper {
    def makeData(len: Int) = {
      val longs = new Array[Long](len)
      (0 until len) foreach { l => longs(l) = l}
      longs zip longs.map {
        case l =>
          new NetlinkMessage.AttrKey[java.lang.Long](l.toInt)
      }
    }
    def bufferChecker(buf: ByteBuffer):
      ((Long,NetlinkMessage.AttrKey[JLong])) => Unit = {
        case (value, attr) =>
          buf.getShort() should be(12)
          buf.getShort() should be(attr.getId)
          buf.getLong() should be(value)
      }
    def checkMessage(msg: NetlinkMessage):
      ((Long,NetlinkMessage.AttrKey[JLong])) => Unit =
        { case (value, attr) => msg.getAttrValueLong(attr) should be(value) }
  }

  def testWritingReadingBytesNoPadding() {
    val bldr = makeMsgBuilger()
    val data = ByteHelper.makeData(4)
    data foreach { case (value, attr) => bldr.addAttrNoPad(attr, value) }
    val msg = bldr.build()
    data foreach ByteHelper.bufferCheckerNoPad(msg.getBuffer.slice)
  }

}
