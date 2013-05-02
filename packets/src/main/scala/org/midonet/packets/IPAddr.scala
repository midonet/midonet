// Copyright 2012 Midokura Inc.

package org.midonet.packets

/**
 * Common abstraction for any version of an IP address.
 */
trait IPAddr {
    type T <: IPAddr
    def toString: String
    def toUrlString: String

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
}

object IPAddr {
    def fromString(s: String): IPAddr = {
        if (s.contains(":"))
            IPv6Addr.fromString(s)
        else
            IPv4Addr.fromString(s)
    }

    def fromIntIPv4(ii: IntIPv4): IPv4Addr = {
        if (ii == null) null
        else new IPv4Addr(ii.addressAsInt)
    }
}
