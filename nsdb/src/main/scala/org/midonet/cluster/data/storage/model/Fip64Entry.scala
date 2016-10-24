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

import org.midonet.packets.{IPv4Addr, IPv6Addr}

/**
  * An entry in the floating IPv6 table. An entry matches a floating IPv4
  * with a fixed IPv4 for a given tenant router.
  */
case class Fip64Entry(fixedIp: IPv4Addr, floatingIp: IPv6Addr, portId: UUID,
                      routerId: UUID) {
    override def toString = s"Fip64 [fixedIp=$fixedIp floatingIp=$floatingIp " +
                            s"portId=$portId routerId=$routerId]"

    def encode: String = s"$fixedIp;$floatingIp;$portId;$routerId"
}

object Fip64Entry {
    def decode(string: String): Fip64Entry = {
        val fields = string.split(";")
        if (fields.length != 4) {
            throw new SerializationException(
                s"Cannot decode $string as a FIP64 entry")
        }
        Fip64Entry(IPv4Addr(fields(0)), IPv6Addr(fields(1)),
                   UUID.fromString(fields(2)), UUID.fromString(fields(3)))
    }
}