/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp.flows;

import java.nio.ByteBuffer
import java.util.{List => JList}
import scala.collection.JavaConversions.asScalaBuffer

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.netlink.BytesUtil
import org.midonet.netlink.NetlinkMessage
import org.midonet.netlink.Writer
import org.midonet.odp.OpenVSwitch
import org.midonet.odp.{Flow, FlowMatch}

@RunWith(classOf[JUnitRunner])
class FlowTest extends FunSpec with Matchers {

    val keyLists = List.fill(1000) { FlowKeys.randomKeys() }
    val actLists = List.fill(1000) { FlowActions.randomActions() }

    val buf = BytesUtil.instance allocate 1024 * 1024

    describe("a List of FlowKeys") {
        it("can be serialized in a ByteBuffer and deserialized back from it") {
            keyLists foreach {
                writeReadList(_, FlowKeys.writer) { case (buf,id) =>
                    NetlinkMessage.readAttr(buf, id, FlowMatch.reader).getKeys
                }
            }
        }
    }

    describe("a List of FlowActions") {
        it("can be serialized in a ByteBuffer and deserialized back from it") {
            actLists foreach {
                writeReadList(_, FlowActions.writer) { case (buf,id) =>
                    NetlinkMessage.readAttr(buf, id, FlowActions.reader)
                }
            }
        }
    }

    describe("a Flow") {
        it("can be serialized in a ByteBuffer and deserialized back from it.") {
            (keyLists zip actLists) foreach { case (keys, actions) =>
                buf.clear
                Flow describeOneRequest (buf, 42, keys, actions)
                val flow = (new Flow (keys, actions))
                (Flow.deserializer deserializeFrom buf) shouldBe flow
            }
        }
    }

    def writeReadList[T](ls: JList[T], writer: Writer[T])
                        (builder: (ByteBuffer, Short) => JList[T]) {
        buf.clear
        val id: Short = NetlinkMessage nested 42.toShort
        NetlinkMessage writeAttrSeq (buf, id, ls, writer)
        buf.flip
        val read = builder(buf,id)
        read should have size(ls.size)
        (ls zip read) foreach { case (a,b) => a shouldBe b }
    }
}
