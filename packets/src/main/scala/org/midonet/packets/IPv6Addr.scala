// Copyright 2012 Midokura Inc.

// TODO(jlm): Replace this with a wrapper around
//  http://code.google.com/p/java-ipv6/ ?

package org.midonet.packets

import java.lang.Long.parseLong

/**
 * An IPv6 address.
 *
 * TODO (galo): We can't make this immutable thanks to Jackson, who seems to be
 * unable (or unwilling) to use the constructor and needs an empty one, it must
 * be possible to instruct him correctly. I suspect this class will give trouble
 * as soon as we start serializing deserializing it because it does not provide
 * setters - I had this problem with IPv4Addr and that's there the addr is a
 * var. But we'll see when the time comes.
 */
class IPv6Addr(val upperWord: Long, val lowerWord: Long)
    extends IPAddr {
    type T = IPv6Addr

    // Required for Jackson deserialization
    def this() = this(0, 0)

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
    override def copy: IPv6Addr = new IPv6Addr(this.upperWord, this.lowerWord)

}

object IPv6Addr {

    // TODO: Support :: notation
    // TODO: Verify each piece is valid 1-4 digit hex.
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
