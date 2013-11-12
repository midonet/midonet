/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.packets.util

import org.midonet.packets.{MAC, IPv4, IntIPv4, IPv4Addr}

object AddressConversions {
    implicit def stringToIp(addr: String): IntIPv4 = IntIPv4.fromString(addr)

    implicit def ipToInt(addr: IntIPv4): Int = addr.addressAsInt

    implicit def intToIp(addr: Int): IntIPv4 = new IntIPv4(addr, 32)

    implicit def ipToString(addr: IntIPv4): String = addr.toUnicastString

    implicit def ipToBytes(addr: IntIPv4): Array[Byte] =
        IPv4Addr.intToBytes(addr.addressAsInt)

    implicit def bytesToIp(addr: Array[Byte]): IntIPv4 =
        new IntIPv4(IPv4Addr.bytesToInt(addr))

    implicit def bytesToInt(addr: Array[Byte]): Int = ipToInt(bytesToIp(addr))

    implicit def intToBytes(addr: Int): Array[Byte] =
        ipToBytes(intToIp(addr))

    implicit def stringToMac(addr: String): MAC = MAC.fromString(addr)

    implicit def macToBytes(addr: MAC): Array[Byte] = addr.getAddress

    implicit def bytesToMac(addr: Array[Byte]): MAC = new MAC(addr)
}
