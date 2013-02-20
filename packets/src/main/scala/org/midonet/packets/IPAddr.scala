// Copyright 2012 Midokura Inc.

package org.midonet.packets


trait IPAddr extends Cloneable {
    def toString(): String
    def toUrlString(): String
    def toIntIPv4(): IntIPv4
}

object IPAddr {
    def fromString(s: String): IPAddr = {
        if (s.contains(":"))
            IPv6Addr.fromString(s)
        else
            IPv4Addr.fromString(s)
    }

    def fromIntIPv4(ii: IntIPv4) = new IPv4Addr().setIntAddress(ii.addressAsInt)
}

class IPv4Addr extends IPAddr {
    private var address: Int = 0
    
    def getIntAddress() = address
    def setIntAddress(addr: Int) = { address = addr; this }
    override def toUrlString() = toString()
    override def toString() = {
        "%d.%d.%d.%d" format ((address >> 24) & 0xff,
                              (address >> 16) & 0xff,
                              (address >> 8) & 0xff,
                              (address >> 0) & 0xff)
    }

    // See "Programming in Scala" sec. 30.4
    override def equals(o: Any): Boolean = {
        o match {
            case t: IPv4Addr => 
                t.canEqual(this) && t.address == this.address
            case _ => false
        }
    }

    def canEqual(o: Any) = o.isInstanceOf[IPv4Addr]

    override def hashCode() = address

    override def clone() = new IPv4Addr().setIntAddress(address)

    override def toIntIPv4() = new IntIPv4(address)
}

object IPv4Addr {
    import IPv4Addr._

    def fromString(s: String): IPv4Addr = {
        val i: Int = Net.convertStringAddressToInt(s)
        new IPv4Addr().setIntAddress(i)
    }
}
