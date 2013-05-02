// Copyright 2013 Midokura Inc.

package org.midonet.packets

/**
 * An IPv4 address.
 *
 * As convention please prefer the static builder IPv4Addr.toInt to the
 * constructor IPv4Addr(int).
 *
 * TODO (galo): We can't make this immutable thanks to Jackson, who seems to be
 * unable to use the constructor and needs an empty one, it must be possible to
 * instruct him correctly.
 */
class IPv4Addr(private val addr: Int) extends IPAddr {
    type T = IPv4Addr

    // Required for Jackson deserialization
    def this() = this(0)

    override def toUrlString = toString()
    override def toString = { "%d.%d.%d.%d" format ((addr >> 24) & 0xff,
                                                    (addr >> 16) & 0xff,
                                                    (addr >> 8) & 0xff,
                                                    (addr >> 0) & 0xff)
    }

    // See "Programming in Scala" sec. 30.4
    override def equals(o: Any): Boolean = {
        o match {
            case t: IPv4Addr => t.canEqual(this) && t.addr == this.addr
            case _ => false
        }
    }

    def canEqual(o: Any) = o.isInstanceOf[IPv4Addr]

    override def hashCode() = addr
    override def subnet(len: Int = 32): IPv4Subnet = new IPv4Subnet(this, len)
    override def copy: IPv4Addr = new IPv4Addr(this.addr)

    def toIntIPv4 = new IntIPv4(addr)
    def toInt = addr

}

object IPv4Addr {

    def fromString(s: String): IPv4Addr = {
        val i: Int = Net.convertStringAddressToInt(s)
        new IPv4Addr(i)
    }

    def fromInt(i: Int): IPv4Addr = {
        new IPv4Addr(i)
    }

    def fromIntIPv4(ii: IntIPv4): IPv4Addr = {
        new IPv4Addr(ii.addressAsInt)
    }

    def fromBytes(addr: Array[Byte]) = {
        if (addr.length != 4)
            throw new IllegalArgumentException

        new IPv4Addr(((addr(0) << 24) & 0xFF000000) |
                     ((addr(1) << 16) & 0x00FF0000) |
                     ((addr(2) <<  8) & 0x0000FF00) |
                     ((addr(3) <<  0) & 0x000000FF))
    }

    def fromIPv4(ipv4: IPv4Addr) = {
        new IPv4Addr(ipv4.addr)
    }

}

