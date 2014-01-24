/*
 * Copyright (c) 2012 Midokura Europe SARL, All Rights Reserved.
 */

// TODO(jlm): Replace this with a wrapper around
//  http://code.google.com/p/java-ipv6/ ?

package org.midonet.packets

import java.lang.Long.parseLong
import java.nio.ByteBuffer
import java.util.Random

import org.codehaus.jackson.annotate.JsonValue;
import org.codehaus.jackson.annotate.JsonCreator;

/**
 * An IPv6 address.
 */
class IPv6Addr(val upperWord: Long, val lowerWord: Long) extends IPAddr
                                                         with Ordered[IPv6Addr]{

    type T = IPv6Addr

    @JsonValue
    override def toString = {
        "%x:%x:%x:%x:%x:%x:%x:%x" format ((upperWord >> 48) & 0xffff,
                                          (upperWord >> 32) & 0xffff,
                                          (upperWord >> 16) & 0xffff,
                                          (upperWord >> 0) & 0xffff,
                                          (lowerWord >> 48) & 0xffff,
                                          (lowerWord >> 32) & 0xffff,
                                          (lowerWord >> 16) & 0xffff,
                                          (lowerWord >> 0) & 0xffff)
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

        val gap = (limit.lowerWord - this.lowerWord)
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

}

object IPv6Addr {

    val r = new Random

    def random = IPv6Addr(r.nextLong, r.nextLong)

    def apply(s: String) = fromString(s)

    def apply(upper: Long, lower: Long) = fromLong(upper, lower)

    // TODO: Support ":: (abbreviated)" notation
    // TODO: Verify each piece is valid 1-4 digit hex.
    @JsonCreator
    def fromString(s: String): IPv6Addr = {
        val unsplit = if (s.charAt(0) == '[' && s.charAt(s.length - 1) == ']')
                          s.substring(1, s.length - 1)
                      else s

        val pieces = unsplit.split(":")
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
