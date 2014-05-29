/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp;

import java.nio.ByteBuffer
import java.util.ArrayList

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.netlink._
import org.midonet.netlink.messages._
import org.midonet.odp.family.PortFamily

@RunWith(classOf[JUnitRunner])
class DpPortTest extends FunSpec with Matchers {

    val stats = List.fill(1000) { DpPort.Stats.random }

    val ports = List.fill(1000) { DpPort.random }

    describe("DpPort") {

        describe("Stats") {
            it("should be invariant by serialization/deserialisation") {
                stats foreach { s =>
                    val bldr = new Builder(ByteBuffer allocate 256)
                    bldr addAttr(PortFamily.Attr.STATS, s)
                    s shouldBe (DpPort.Stats buildFrom bldr.build())
                }
            }

            describe("translator") {
                it("should deserialize the same value after serialization") {
                    val buf = ByteBuffer allocate 256
                    stats foreach { s =>
                        buf.clear
                        DpPort.Stats.trans.serializeInto(buf,s)
                        buf.flip
                        s shouldBe DpPort.Stats.trans.deserializeFrom(buf)
                    }
                }
            }
        }

        it("should be invariant by serialization/deserialisation") {
            val buf = ByteBuffer allocate 256
            ports foreach { p =>
                buf.clear
                buf putInt 42 // write datapath index
                p serializeInto buf
                buf.flip
                p shouldBe (DpPort buildFrom new NetlinkMessage(buf))
            }
        }

        it ("should return coherent FlowActionOutput") {
            ports foreach { p =>
                p.getPortNo() shouldBe p.toOutputAction.getPortNumber()
            }
        }

        describe("setDeserializer") {
            it("should deserialize a set of port message correctly") {
                val portSet = ports.toSet
                val buffers = portSet.foldLeft(new ArrayList[ByteBuffer]()) {
                    case (ls,p) =>
                        val buf = ByteBuffer allocate 256
                        buf putInt 42 // write datapath index
                        p serializeInto buf
                        buf.flip
                        ls.add(buf)
                        ls
                }
                val deserializedPortSet = DpPort.setDeserializer apply buffers
                deserializedPortSet should have size portSet.size
                portSet foreach { p =>
                    deserializedPortSet should contain(p)
                }
            }
        }

        describe("deserializer") {
            it("should deserialize a port correctly") {
                val buf = ByteBuffer allocate 256
                ports foreach { p =>
                    buf.clear
                    buf putInt 42 // write datapath index
                    p serializeInto buf
                    buf.flip
                    val ls = new ArrayList[ByteBuffer]()
                    ls.add(buf)
                    p shouldBe (DpPort.deserializer apply ls)
                }
            }
        }
    }

}
