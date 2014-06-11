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
import org.midonet.odp.family.DatapathFamily

@RunWith(classOf[JUnitRunner])
class DatapathTest extends FunSpec with Matchers {

    val stats = List.fill(1000) { Datapath.Stats.random }

    val dps = List.fill(1000) { Datapath.random }

    describe("Datapath") {

        describe("Stats") {
            it("should be invariant by serialization/deserialisation") {
                stats foreach { s =>
                    val buf = getBuffer
                    NetlinkMessage writeAttr (buf, s, Datapath.Stats.trans)
                    buf.flip
                    s shouldBe (Datapath.Stats buildFrom buf)
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
                dp shouldBe (Datapath buildFrom buf)
            }
        }

        describe("deserializer") {
            it("should deserialize a port correctly") {
                val buf = getBuffer
                dps foreach { dp =>
                    buf.clear
                    dp serializeInto buf
                    buf.flip
                    dp shouldBe (Datapath.deserializer apply buf)
                }
            }
        }
    }

    def getBuffer =  BytesUtil.instance allocate 256
}
