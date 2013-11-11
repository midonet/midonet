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
    override def toString = IPv4Addr.intToString(addr)

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
    override def toBytes: Array[Byte] = IPv4Addr.intToBytes(addr)

}

object IPv4Addr {

    val r = new scala.util.Random

    def random = IPv4Addr(r.nextInt)

    def apply(s: String) = fromString(s)

    def apply(i: Int) = fromInt(i)

    def apply(bytes: Array[Byte]) = fromBytes(bytes)

    @JsonCreator
    def fromString(s: String) = new IPv4Addr(stringToInt(s))

    def fromInt(i: Int) = new IPv4Addr(i)

    def fromIntIPv4(ii: IntIPv4) = new IPv4Addr(ii.addressAsInt)

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
            bytes.foreach { byteStr =>
                val byteVal = byteStr.toInt
                if ((byteVal | 0xff) != 0xff)
                    throw illegalIPv4String(s)
                addr = (addr << 8) + byteVal
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
        var index = 0
        try {
            bytes.foreach { byteStr =>
                val byteVal = byteStr.toInt
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
