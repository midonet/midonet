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

            val trans = Datapath.Stats.trans

            it("should be invariant by serialization/deserialisation") {
                stats foreach { s =>
                    val buf = getBuffer
                    NetlinkMessage writeAttr (buf, s, trans)
                    buf.flip
                    val id = trans attrIdOf null
                    s shouldBe (NetlinkMessage readAttr (buf, id, trans))
                }
            }

            describe("translator") {
                it("should deserialize the same value after serialization") {
                    val buf = getBuffer
                    stats foreach { s =>
                        buf.clear
                        trans serializeInto (buf,s)
                        buf.flip
                        s shouldBe (trans deserializeFrom buf)
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
                    dp shouldBe (Datapath.deserializer deserializeFrom buf)
                }
            }
        }
    }

    def getBuffer =  BytesUtil.instance allocate 256
}
