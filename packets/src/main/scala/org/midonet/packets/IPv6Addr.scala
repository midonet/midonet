// Copyright 2012 Midokura Inc.

// TODO(jlm): Replace this with a wrapper around
//  http://code.google.com/p/java-ipv6/ ?


package org.midonet.packets

import java.lang.Long.parseLong


class IPv6Addr extends IPAddr {
    private var upperWord: Long = 0
    private var lowerWord: Long = 0

    def getUpperWord() = upperWord
    def getLowerWord() = lowerWord
    def setAddress(upper: Long, lower: Long) = {
        upperWord = upper
        lowerWord = lower
        this
    }
    override def toString() = {
        "%x:%x:%x:%x:%x:%x:%x:%x" format ((upperWord >> 48) & 0xffff,
                                          (upperWord >> 32) & 0xffff,
                                          (upperWord >> 16) & 0xffff,
                                          (upperWord >> 0) & 0xffff,
                                          (lowerWord >> 48) & 0xffff,
                                          (lowerWord >> 32) & 0xffff,
                                          (lowerWord >> 16) & 0xffff,
                                          (lowerWord >> 0) & 0xffff)
    }
    override def toUrlString() = '[' + toString() + ']'

    // See "Programming in Scala" sec. 30.4
    override def equals(o: Any): Boolean = {
        o match {
            case t: IPv6Addr =>
                t.canEqual(this) && t.upperWord == this.upperWord &&
                                    t.lowerWord == this.lowerWord
            case _ => false
        }
    }

    def canEqual(o: Any) = o.isInstanceOf[IPv6Addr]

    override def hashCode() = {
        val longHash = upperWord ^ lowerWord
        ((longHash ^ (longHash >> 32)) & 0xffffffff).toInt
    }

    override def clone_() = new IPv6Addr().setAddress(upperWord, lowerWord)
    override def clone() = clone_

    override def toIntIPv4() = throw new IllegalArgumentException
}

object IPv6Addr {
    def fromString(s: String): IPv6Addr = {
        val unsplit = if (s.charAt(0) == '[' && s.charAt(s.length - 1) == ']')
                          s.substring(1, s.length - 1)
                      else
                          s
        val pieces = unsplit.split(":")
        if (pieces.size != 8)
            return null         //XXX: Support :: notation
        //XXX: Verify each piece is valid 1-4 digit hex.
        new IPv6Addr().setAddress(
                (parseLong(pieces(0), 16) << 48) |
                (parseLong(pieces(1), 16) << 32) |
                (parseLong(pieces(2), 16) << 16) |
                (parseLong(pieces(3), 16) << 0),
                (parseLong(pieces(4), 16) << 48) |
                (parseLong(pieces(5), 16) << 32) |
                (parseLong(pieces(6), 16) << 16) |
                (parseLong(pieces(7), 16) << 0))
    }
}
