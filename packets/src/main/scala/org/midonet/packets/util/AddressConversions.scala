/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.packets.util

import org.midonet.packets.{MAC, IPv4Addr}

object AddressConversions {
    implicit def stringToIp(addr: String): IPv4Addr = IPv4Addr(addr)

    implicit def ipToInt(addr: IPv4Addr): Int = addr.toInt

    implicit def intToIp(addr: Int): IPv4Addr = IPv4Addr(addr)

    implicit def ipToString(addr: IPv4Addr): String = addr.toString

    implicit def ipToBytes(addr: IPv4Addr): Array[Byte] = addr.toBytes

    implicit def bytesToIp(addr: Array[Byte]): IPv4Addr = IPv4Addr.apply(addr)

    implicit def bytesToInt(addr: Array[Byte]): Int = ipToInt(bytesToIp(addr))

    implicit def intToBytes(addr: Int): Array[Byte] = ipToBytes(intToIp(addr))

    implicit def stringToMac(addr: String): MAC = MAC.fromString(addr)

    implicit def macToBytes(addr: MAC): Array[Byte] = addr.getAddress

    implicit def bytesToMac(addr: Array[Byte]): MAC = new MAC(addr)
}
