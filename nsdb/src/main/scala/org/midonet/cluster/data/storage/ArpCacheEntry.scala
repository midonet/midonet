/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.cluster.data.storage

import org.midonet.packets.MAC

object ArpCacheEntry {

    @throws(classOf[IllegalArgumentException])
    def decode(str: String): ArpCacheEntry = {
        val fields: Array[String] = str.split(";")
        if (fields.length != 4) {
            throw new IllegalArgumentException(str)
        }
        new ArpCacheEntry(
            if (fields(0) == "null") {
                null
            } else {
                MAC.fromString(fields(0))
            },
            fields(1).toLong, fields(2).toLong, fields(3).toLong)
    }
}

class ArpCacheEntry(val macAddr: MAC, val expiry: Long, val stale: Long,
                    val lastArp: Long) extends Cloneable {

    override def clone: ArpCacheEntry = {
        return new ArpCacheEntry(macAddr, expiry, stale, lastArp)
    }

    def encode: String = {
        (if (macAddr == null) {
            "null"
        } else {
            macAddr.toString
        }) + ";" +
               expiry + ";" + stale + ";" + lastArp
    }

    override def toString: String =
        "ArpCacheEntry [macAddr=" + macAddr + ", expiry=" + expiry +
               ", stale=" + stale + ", lastArp=" + lastArp + "]"

    override def equals(o: Any): Boolean = {
        if (o == null || (getClass ne o.getClass)) false

        val that: ArpCacheEntry = o.asInstanceOf[ArpCacheEntry]
        if (this eq that) true
        if (expiry != that.expiry) false
        if (lastArp != that.lastArp) false
        if (stale != that.stale) false
        if (macAddr != null && !(macAddr == that.macAddr)) false
        if (macAddr == null && (that.macAddr != null)) false
        true
    }

    override def hashCode: Int = {
        var result: Int = if (macAddr != null) {
            macAddr.hashCode
        } else {
            0
        }
        result = 31 * result + (expiry ^ (expiry >>> 32)).toInt
        result = 31 * result + (stale ^ (stale >>> 32)).toInt
        result = 31 * result + (lastArp ^ (lastArp >>> 32)).toInt
        return result
    }
}