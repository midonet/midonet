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

import com.google.common.base.MoreObjects

import org.apache.commons.lang.SerializationException

import org.midonet.packets.MAC
import org.midonet.packets.MAC.InvalidMacException

object ArpEntry {

    @throws[InvalidMacException]
    @throws[NumberFormatException]
    @throws[SerializationException]
    def decode(string: String): ArpEntry = {
        val fields = string.split(";")
        if (fields.length != 4) {
            throw new SerializationException(s"Cannot decode $string as ARP entry")
        }
        ArpEntry(
            if (fields(0) == "null") null else MAC.fromString(fields(0)),
            java.lang.Long.parseLong(fields(1)),
            java.lang.Long.parseLong(fields(2)),
            java.lang.Long.parseLong(fields(3)))
    }

}

final case class ArpEntry(mac: MAC, expiry: Long, stale: Long, lastArp: Long) {

    override def toString: String = {
        MoreObjects.toStringHelper(this).omitNullValues
            .add("mac", mac)
            .add("expiry", expiry)
            .add("stale", stale)
            .add("lastArp", lastArp)
            .toString
    }

    def encode: String =  s"$mac;$expiry;$stale;$lastArp"

}
