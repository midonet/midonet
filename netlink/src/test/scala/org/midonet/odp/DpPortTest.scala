/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

        describe("Stats translator") {

            val trans = DpPort.Stats.trans

            it("should allow serialization/deserialisation to be invariant") {
                val buf = getBuffer
                stats foreach { s =>
                    buf.clear
                    NetlinkMessage writeAttr (buf, s, trans)
                    buf.flip
                    val id = trans attrIdOf null
                    s shouldBe (NetlinkMessage readAttr (buf, id, trans))
                }
            }

            it("should read the same value after writing it") {
                val buf = getBuffer
                stats foreach { s =>
                    buf.clear
                    DpPort.Stats.trans.serializeInto(buf,s)
                    buf.flip
                    s shouldBe DpPort.Stats.trans.deserializeFrom(buf)
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
                    p shouldBe (DpPort.deserializer deserializeFrom buf)
                }
            }
        }
    }

    def getBuffer =  BytesUtil.instance allocate 256
}
