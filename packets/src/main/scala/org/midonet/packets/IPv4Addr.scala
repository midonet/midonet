// Copyright 2013 Midokura Inc.

package org.midonet.packets

import java.util.Random
import org.codehaus.jackson.annotate.JsonValue;
import org.codehaus.jackson.annotate.JsonCreator;

/**
 * An IPv4 address.
 *
 * As convention please prefer the static builder IPv4Addr.toInt to the
 * constructor IPv4Addr(int).
 */
class IPv4Addr(val addr: Int) extends IPAddr with Ordered[IPv4Addr] {
    type T = IPv4Addr

    // Required for Jackson deserialization
    def this() = this(0)

    override def toUrlString = toString()

    @JsonValue
    override def toString = IPv4Addr.intToIpStr(addr)

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
    override def copy: IPv4Addr = new IPv4Addr(this.addr)
    override def randomTo(limit: IPv4Addr, rand: Random): IPv4Addr = {
        if (this > limit)
            throw new IllegalArgumentException("Limit is lower than this ip")

        IPv4Addr.fromInt(rand.nextInt(limit.addr - addr + 1) + addr)
    }

    def compare(that: IPv4Addr): Int = this.addr.compare(that.toInt)
    def toIntIPv4 = new IntIPv4(addr)
    def toInt = addr
    override def toBytes: Array[Byte] = IPv4.toIPv4AddressBytes(addr)

}

object IPv4Addr {

    val r = new scala.util.Random

    def random = IPv4Addr(r.nextInt)

    def apply(s: String): IPv4Addr = fromString(s)

    def apply(i: Int): IPv4Addr = fromInt(i)

    def apply(bytes: Array[Byte]): IPv4Addr = fromBytes(bytes)

    @JsonCreator
    def fromString(s: String): IPv4Addr =
        new IPv4Addr(Net.convertStringAddressToInt(s))

    def fromInt(i: Int): IPv4Addr =
        new IPv4Addr(i)

    def fromIntIPv4(ii: IntIPv4): IPv4Addr =
        new IPv4Addr(ii.addressAsInt)

    def fromBytes(addr: Array[Byte]) = IPv4Addr(bytesToInt(addr))

    def fromIPv4(ipv4: IPv4Addr) = ipv4

    def intToIpStr(addr: Int): String =
        "%d.%d.%d.%d" format ((addr >> 24) & 0xff,
                              (addr >> 16) & 0xff,
                              (addr >> 8) & 0xff,
                              (addr >> 0) & 0xff)

    def bytesToInt(addr: Array[Byte]) = {
        if (addr.length != 4)
            throw new IllegalArgumentException

        ((addr(0) << 24) & 0xFF000000) |
            ((addr(1) << 16) & 0x00FF0000) |
            ((addr(2) <<  8) & 0x0000FF00) |
            ((addr(3) <<  0) & 0x000000FF)
    }

}

