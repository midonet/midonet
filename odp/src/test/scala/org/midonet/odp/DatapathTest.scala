/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.odp;

import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.ArrayList

import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner

import org.midonet.netlink._
import org.midonet.netlink.messages._
import org.midonet.odp.family.DatapathFamily

@RunWith(classOf[JUnitRunner])
class DatapathTest extends FunSpec with Matchers {

    val stats = List.fill(1000) { Datapath.Stats.random }

    val dps = List.fill(1000) { Datapath.random }

    describe("Datapath") {

        describe("Stats") {
            it("should be invariant by serialization/deserialisation") {
                stats foreach { s =>
                    val bldr = new Builder(getBuffer)
                    bldr addAttr(Datapath.StatsAttr, s)
                    s shouldBe (Datapath.Stats buildFrom bldr.build())
                }
            }

            describe("translator") {
                it("should deserialize the same value after serialization") {
                    val buf = getBuffer
                    stats foreach { s =>
                        buf.clear
                        Datapath.Stats.trans.serializeInto(buf,s)
                        buf.flip
                        s shouldBe Datapath.Stats.trans.deserializeFrom(buf)
                    }
                }
            }
        }

        it("should be invariant by serialization/deserialisation") {
            val buf = getBuffer
            dps foreach { dp =>
                buf.clear
                dp serializeInto buf
                buf.flip
                dp shouldBe (Datapath buildFrom new NetlinkMessage(buf))
            }
        }

        describe("setDeserializer") {
            it("should deserialize a set of datapath messages correctly") {
                val dpSet = dps.toSet
                val buffers = dpSet.foldLeft(new ArrayList[ByteBuffer]()) {
                    case (ls,dp) =>
                        val buf = getBuffer
                        dp serializeInto buf
                        buf.flip
                        ls.add(buf)
                        ls
                }
                val deserializedDpSet = Datapath.setDeserializer apply buffers
                deserializedDpSet should have size dpSet.size
                dpSet foreach { dp =>
                    deserializedDpSet should contain(dp)
                }
            }
        }

        describe("deserializer") {
            it("should deserialize a port correctly") {
                val buf = getBuffer
                dps foreach { dp =>
                    buf.clear
                    dp serializeInto buf
                    buf.flip
                    val ls = new ArrayList[ByteBuffer]()
                    ls.add(buf)
                    dp shouldBe (Datapath.deserializer apply ls)
                }
            }
        }
    }

    def getBuffer =  BytesUtil.instance allocate 256
}
