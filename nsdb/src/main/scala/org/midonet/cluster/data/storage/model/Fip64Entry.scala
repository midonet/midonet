/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.data.storage.model

import java.util.UUID

import org.apache.commons.lang.SerializationException

import org.midonet.packets.{IPv4Addr, IPv4Subnet, IPv6Addr}

/**
  * An entry in the floating IPv6 table. An entry matches a floating IPv4
  * with a fixed IPv4 for a given tenant router.
  */
case class Fip64Entry(fixedIp: IPv4Addr, floatingIp: IPv6Addr,
                      natPool: IPv4Subnet, portId: UUID,
                      routerId: UUID) {
    override def toString = s"Fip64 [fixedIp=$fixedIp floatingIp=$floatingIp " +
                            s"natPool=$natPool portId=$portId " +
                            s"routerId=$routerId]"

    def encode: String = s"$fixedIp;$floatingIp;${natPool.getIntAddress}" +
        s";${natPool.getPrefixLen};$portId;$routerId"
}

object Fip64Entry {
    def decode(string: String): Fip64Entry = {
        val fields = string.split(";")
        if (fields.length != 6) {
            throw new SerializationException(
                s"Cannot decode $string as a FIP64 entry")
        }
        val fixedIp = IPv4Addr(fields(0))
        val floatingIp = IPv6Addr(fields(1))
        val natPool = new IPv4Subnet(fields(2).toInt, fields(3).toInt)
        val portId = UUID.fromString(fields(4))
        val routerId = UUID.fromString(fields(5))
        Fip64Entry(fixedIp, floatingIp, natPool, portId, routerId)
    }
}
