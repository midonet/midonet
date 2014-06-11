/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp;

import java.nio.ByteBuffer
import java.nio.ByteOrder

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.netlink._
import org.midonet.odp.family.PortFamily

@RunWith(classOf[JUnitRunner])
class DpPortTest extends FunSpec with Matchers {

    val stats = List.fill(1000) { DpPort.Stats.random }

    val ports = List.fill(1000) { DpPort.random }

    describe("DpPort") {

        describe("Stats") {
            it("should be invariant by serialization/deserialisation") {
                stats foreach { s =>
                    val buf = getBuffer
                    NetlinkMessage writeAttr (buf, s, DpPort.Stats.trans)
                    buf.flip
                    s shouldBe (DpPort.Stats buildFrom buf)
                }
            }

            describe("translator") {
                it("should deserialize the same value after serialization") {
                    val buf = getBuffer
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
            val buf = getBuffer
            ports foreach { p =>
                buf.clear
                buf putInt 42 // write datapath index
                p serializeInto buf
                buf.flip
                p shouldBe (DpPort buildFrom buf)
            }
        }

        it ("should return coherent FlowActionOutput") {
            ports foreach { p =>
                p.getPortNo() shouldBe p.toOutputAction.getPortNumber()
            }
        }

        describe("deserializer") {
            it("should deserialize a port correctly") {
                val buf = getBuffer
                ports foreach { p =>
                    buf.clear
                    buf putInt 42 // write datapath index
                    p serializeInto buf
                    buf.flip
                    p shouldBe (DpPort.deserializer apply buf)
                }
            }
        }
    }

    def getBuffer =  BytesUtil.instance allocate 256
}
