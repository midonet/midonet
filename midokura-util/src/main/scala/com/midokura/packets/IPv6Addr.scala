// Copyright 2012 Midokura Inc.

package com.midokura.packets


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

    override def clone() = new IPv6Addr().setAddress(upperWord, lowerWord)
}
