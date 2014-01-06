// Copyright 2012 Midokura Inc.

package org.midonet.packets

/**
 * Common abstraction for any version of an IP address.
 */
import java.util.{NoSuchElementException, Random}

trait IPAddr {
    type T <: IPAddr
    def toString: String
    def toUrlString: String
    def toBytes: Array[Byte]

    /**
     * Provides an IPSubnet from this address and with the given length.
     *
     * @param len defaults to 128
     * @return
     */
    def subnet(len: Int): IPSubnet[T]

    /**
     * Provides a new copy of the IP address.
     *
     * @return
     */
    def copy: T

    /**
     * Get the next ip to the current one
     *
     * ®return
     */
    def next: T

    def randomTo(limit: T, rand: Random): T

}

object IPAddr {
    implicit def ipAddrToOrdered[T <: IPAddr](ip: T): Ordered[T] = ip match {
        case i: Ordered[_] => ip.asInstanceOf[Ordered[T]]
        case _ => throw new IllegalArgumentException()
    }

    def fromString(s: String): IPAddr = {
        if (s.contains(":"))
            IPv6Addr.fromString(s)
        else
            IPv4Addr.fromString(s)
    }

    def canonicalize(s: String): String = {
        try {
            fromString(s).toString
        } catch {
            case e: Exception =>
                throw new IllegalArgumentException("Not a valid IP address: " + s)
        }
    }

    def fromAddr[T <: IPAddr](ip: T): T = {
        val newIp: T = ip match {
            case ipv4: IPv4Addr => IPv4Addr.fromIPv4(ipv4).asInstanceOf[T]
            case ipv6: IPv6Addr => IPv6Addr.fromIPv6(ipv6).asInstanceOf[T]
        }
        newIp
    }
}

class IPAddrRange[T <: IPAddr](start: T, end: T)
                 (implicit o: T => Ordered[T]) extends Iterator[IPAddr] {

    private var index: T = IPAddr.fromAddr(start)

    override def hasNext: Boolean = index <= end
    override def next(): T = {
        if (!hasNext)
            throw new NoSuchElementException
        val curr = index
        index = index.next.asInstanceOf[T]
        curr
    }

}

/**
 * For usage from java, since IPAddrRange requires an implicit param.
 */
object IPAddrRangeBuilder {
    def range[T <: IPAddr](ip1: T, ip2: T): IPAddrRange[T] =
        new IPAddrRange(ip1, ip2)
}
