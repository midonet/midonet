// Copyright 2012 Midokura Inc.

package com.midokura.packets

trait IPAddr extends Cloneable {
    def toString(): String
    def toUrlString(): String
}

object IPAddr {
    def fromString(s: String): IPAddr
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

    override def equals(Object o): Boolean = {
        if (this eq o) return true
        if (o == null || getClass != o.getClass) return false
        return address == ((IPv4Addr)o).address
    }

    override def hashCode() = address
}
