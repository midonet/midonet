/*
 * Copyright 2015 Midokura SARL
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

// TODO(jlm): Replace this with a wrapper around
//  http://code.google.com/p/java-ipv6/ ?

package org.midonet.packets

import java.lang.Long.parseLong
import java.nio.ByteBuffer
import java.util.Random

import com.fasterxml.jackson.annotation.{JsonCreator, JsonValue}

/**
 * An IPv6 address.
 */
class IPv6Addr(val upperWord: Long, val lowerWord: Long) extends IPAddr
                                                         with Ordered[IPv6Addr]{

    type T = IPv6Addr

    private var sAddr: String = null // allow lazy init

    @JsonValue
    override def toString = {
        if (sAddr == null) {
            sAddr = "%x:%x:%x:%x:%x:%x:%x:%x" format (
                      (upperWord >> 48) & 0xffff,
                      (upperWord >> 32) & 0xffff,
                      (upperWord >> 16) & 0xffff,
                      (upperWord >> 0) & 0xffff,
                      (lowerWord >> 48) & 0xffff,
                      (lowerWord >> 32) & 0xffff,
                      (lowerWord >> 16) & 0xffff,
                      (lowerWord >> 0) & 0xffff)
        }
        sAddr
    }

    override def toUrlString = '[' + toString() + ']'

    // See "Programming in Scala" sec. 30.4
    override def equals(o: Any): Boolean = {
        o match {
            case t: IPv6Addr => t.canEqual(this) &&
                                t.upperWord == this.upperWord &&
                                t.lowerWord == this.lowerWord
            case _ => false
        }
    }

    def canEqual(o: Any) = o.isInstanceOf[IPv6Addr]

    override def hashCode() = {
        val longHash = upperWord ^ lowerWord
        ((longHash ^ (longHash >> 32)) & 0xffffffff).toInt
    }

    override def subnet(len: Int = 128): IPv6Subnet = new IPv6Subnet(this, len)

    override def next: IPv6Addr = {
        val nextLower = lowerWord + 1
                        // if sign change when lowerWord++, carry
        val nextUpper = upperWord + (if (nextLower == 0) 1 else 0)
        new IPv6Addr(nextUpper, nextLower)
    }

    def compare(that: IPv6Addr): Int = {
        if (this.upperWord == that.upperWord)
            this.lowerWord.compare(that.lowerWord)
        else if (this.upperWord > that.upperWord) 1
        else -1
    }

    /**
     * Returns a random IPv6 address between this and limit, within the same /64
     * range. Both this and limit must share at least in the same /64 prefix.
     */
    override def randomTo(limit: IPv6Addr, rand: Random): IPv6Addr = {
        if (this.upperWord != limit.upperWord)
            throw new IllegalArgumentException("IPv6.randomTo only supported" +
                " for ranges belonging to the same /64 subnet")

        if (this.lowerWord == limit.lowerWord)
            return new IPv6Addr(upperWord, lowerWord)

        val gap = limit.lowerWord - this.lowerWord
        val nextLower = lowerWord + (rand.nextLong % gap)
        new IPv6Addr(upperWord, nextLower)

    }

    override def toBytes: Array[Byte] = {
        val bb = ByteBuffer.allocate(16)
        bb.putLong(upperWord)
        bb.putLong(lowerWord)
        bb.array()
    }

    override def range(that: IPv6Addr) =
        if (that < this) IPAddr.range(that, this) else IPAddr.range(this, that)

    override def isMcast: Boolean =
        throw new UnsupportedOperationException
}

object IPv6Addr {

    val AnyAddress = IPv6Addr(0L, 0L)

    val r = new Random

    def random = IPv6Addr(r.nextLong, r.nextLong)

    def apply(s: String) = fromString(s)

    def apply(upper: Long, lower: Long) = fromLong(upper, lower)

    def convertToLongNotation(s: String): String = {

          val n = s.count(_ == ':') - 2 // subtract 2 for '::'
          s match {
              case "::" => ("0:" * 8).dropRight(1)
              case x if s.startsWith("::") =>
                  // '::' exists at the beginning => 7 potential '0:' slots
                  x.replace("::", "0:" * (7 - n))
              case x if s.endsWith("::") =>
                  // '::' exists at the end => 7 potential '0:' slots
                  x.replace("::", ":" + ("0:" * (7 - n))).dropRight(1)
              case x if s.contains("::") =>
                  // '::' exists in the middle => 6 potential '0:' slots
                  x.replace("::", ":" + ("0:" * (6 - n)))
              case _ => s
        }
    }

    def illegalIPv6Bytes = new IllegalArgumentException(
        "byte array representing an IPv6 address must have length 16 exactly")

    private def buildQuadWord(addr: Array[Byte], start: Int): Long = {
        var quadWord = 0L
        var shift = 56
        val end = start + 8
        var i = start
        while (i < end) {
            quadWord |= (addr(i).toLong & 0xff) << shift
            shift -= 8
            i += 1
        }
        quadWord
    }
    
    def fromBytes(addr: Array[Byte]): IPv6Addr = {
        if (addr == null || addr.length != 16)
            throw illegalIPv6Bytes
        apply(buildQuadWord(addr, 0), buildQuadWord(addr, 8))
    }

    def fromInts(addr: Array[Int]): IPv6Addr = {
        if (addr == null || addr.length != 4)
            throw illegalIPv6Bytes
        apply((addr(0).toLong << 32) | (addr(1) & 0xFFFFFFFFL),
              (addr(2).toLong << 32) | (addr(3) & 0xFFFFFFFFL))
    }

    // TODO: Verify each piece is valid 1-4 digit hex.
    @JsonCreator
    def fromString(s: String): IPv6Addr = {
        val unsplit = if (s.charAt(0) == '[' && s.charAt(s.length - 1) == ']')
                          s.substring(1, s.length - 1)
                      else s
        val longNotation = convertToLongNotation(unsplit)
        val pieces = longNotation.split(":")
        val ip = if (pieces.size == 8)
                        new IPv6Addr((parseLong(pieces(0), 16) << 48) |
                                     (parseLong(pieces(1), 16) << 32) |
                                     (parseLong(pieces(2), 16) << 16) |
                                     (parseLong(pieces(3), 16) << 0),
                                     (parseLong(pieces(4), 16) << 48) |
                                     (parseLong(pieces(5), 16) << 32) |
                                     (parseLong(pieces(6), 16) << 16) |
                                     (parseLong(pieces(7), 16) << 0))
                 else null
        ip
    }

    def fromLong(upper: Long, lower: Long) = new IPv6Addr(upper, lower)

    def fromIPv6(other: IPv6Addr) =
        new IPv6Addr(other.upperWord, other.lowerWord)

}
