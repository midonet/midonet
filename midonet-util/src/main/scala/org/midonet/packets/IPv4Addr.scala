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

package org.midonet.packets

import java.util.Random

import com.fasterxml.jackson.annotation.{JsonValue, JsonCreator}

/**
 * An IPv4 address.
 *
 * As convention please prefer the static builder IPv4Addr.toInt to the
 * constructor IPv4Addr(int).
 */
class IPv4Addr(val addr: Int) extends IPAddr with Ordered[IPv4Addr] {
    type T = IPv4Addr

    private var sAddr: String = null // not val to allow lazy init

    override def toUrlString = toString()

    @JsonCreator
    def this(sAddr: String) = {
        this(IPv4Addr.stringToInt(sAddr))
        this.sAddr = sAddr
    }

    @JsonValue
    override def toString = {
        if (sAddr == null) {
            sAddr = IPv4Addr.intToString(addr)
        }
        sAddr
    }

    // See "Programming in Scala" sec. 30.4
    override def equals(o: Any): Boolean = {
        o match {
            case t: IPv4Addr => t.canEqual(this) && t.addr == this.addr
            case _ => false
        }
    }

    // This works because integers are represented using two's complement
    override def next: IPv4Addr = new IPv4Addr(addr + 1)

    def canEqual(o: Any) = o.isInstanceOf[IPv4Addr]

    override def hashCode() = addr
    override def subnet(len: Int = 32): IPv4Subnet = new IPv4Subnet(this, len)
    override def randomTo(limit: IPv4Addr, rand: Random): IPv4Addr = {
        if (this > limit)
            throw new IllegalArgumentException("Limit is lower than this ip")

        IPv4Addr.fromInt(rand.nextInt(limit.addr - addr + 1) + addr)
    }

    def compare(that: IPv4Addr): Int = Integer.compare(addr, that.addr)
    def toInt = addr
    override def toBytes: Array[Byte] = IPv4Addr.intToBytes(addr)

    override def range(that: IPv4Addr) =
        if (that < this) IPAddr.range(that, this) else IPAddr.range(this, that)

    def isMcast: Boolean =
        (addr >>> 28) == 0xE
}

object IPv4Addr {

    val AnyAddress = IPv4Addr(0)

    val r = new Random

    def random = IPv4Addr(r.nextInt)

    def randomPrivate = fromInt((r.nextInt & 0xffffff) | (10 << 24))

    def apply(s: String) = fromString(s)

    def apply(i: Int) = fromInt(i)

    def apply(bytes: Array[Byte]) = fromBytes(bytes)

    @JsonCreator
    def fromString(s: String) = new IPv4Addr(stringToInt(s))

    def fromInt(i: Int) = new IPv4Addr(i)

    def fromBytes(addr: Array[Byte]) = new IPv4Addr(bytesToInt(addr))

    def fromIPv4(ipv4: IPv4Addr) = ipv4

    def intToString(addr: Int) =
            "%d.%d.%d.%d" format ((addr >> 24) & 0xff,
                                  (addr >> 16) & 0xff,
                                  (addr >> 8) & 0xff,
                                  (addr >> 0) & 0xff)

    def intToBytes(addr: Int) = {
        val addrBytes = new Array[Byte](4)
        addrBytes(0) = ((addr >> 24) & 0xff).toByte
        addrBytes(1) = ((addr >> 16) & 0xff).toByte
        addrBytes(2) = ((addr >> 8) & 0xff).toByte
        addrBytes(3) = ((addr >> 0) & 0xff).toByte
        addrBytes
    }

    def illegalIPv4String(str: String) = new IllegalArgumentException(
        "IPv4 string must be 4 numbers between 0 and 255 joined with 3 '.' " +
            "but was " + str)

    def stringToInt(s: String) = {
        if (s == null)
            throw illegalIPv4String(s)
        val bytes = s split '.'
        if (bytes.length != 4)
            throw illegalIPv4String(s)
        var addr = 0
        try {
            var index = 0
            while (index < 4) {
                val byteVal = bytes(index).toInt
                if ((byteVal | 0xff) != 0xff)
                    throw illegalIPv4String(s)
                addr = (addr << 8) + byteVal
                index += 1
            }
        } catch {
            case _: NumberFormatException => throw illegalIPv4String(s)
        }
        addr
    }

    def stringToBytes(s: String) = {
        if (s == null)
            throw illegalIPv4String(s)
        val bytes = s split '.'
        if (bytes.length != 4)
            throw illegalIPv4String(s)
        val addrBytes = new Array[Byte](4)
        try {
            var index = 0
            while (index < 4) {
                val byteVal = bytes(index).toInt
                if ((byteVal | 0xff) != 0xff)
                    throw illegalIPv4String(s)
                addrBytes(index) = byteVal.toByte
                index += 1
            }
        } catch {
            case _: NumberFormatException => throw illegalIPv4String(s)
        }
        addrBytes
    }

    val illegalIPv4Bytes = new IllegalArgumentException(
        "byte array representing an IPv4 String must have length 4 exactly")

    def bytesToInt(addr: Array[Byte]) = {
        if (addr == null || addr.length != 4)
            throw illegalIPv4Bytes
        ((addr(0) << 24) & 0xff000000) |
            ((addr(1) << 16) & 0x00ff0000) |
            ((addr(2) <<  8) & 0x0000ff00) |
            ((addr(3) <<  0) & 0x000000ff)
    }

    def bytesToString(addr: Array[Byte]) = {
        if (addr == null || addr.length != 4)
            throw illegalIPv4Bytes
        "%d.%d.%d.%d" format (
            addr(0) & 0xff, addr(1) & 0xff, addr(2) & 0xff, addr(3) & 0xff)
    }

}
