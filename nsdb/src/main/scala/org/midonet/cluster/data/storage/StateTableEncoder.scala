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

package org.midonet.cluster.data.storage

import java.util.UUID

import scala.util.control.NonFatal

import org.midonet.cluster.data.storage.StateTableEncoder.PersistentVersion
import org.midonet.cluster.data.storage.model.Fip64Entry
import org.midonet.cluster.rpc.State.KeyValue
import org.midonet.packets.{IPv4Addr, MAC}

object StateTableEncoder {
    final val PersistentVersion = Int.MaxValue

    @inline private def keyValueToUUID(kv: KeyValue): UUID = {
        val msb = (kv.getDataVariable.byteAt(0).toLong << 56) |
                  ((kv.getDataVariable.byteAt(1).toLong & 0xFF) << 48) |
                  ((kv.getDataVariable.byteAt(2).toLong & 0xFF) << 40) |
                  ((kv.getDataVariable.byteAt(3).toLong & 0xFF) << 32) |
                  ((kv.getDataVariable.byteAt(4).toLong & 0xFF) << 24) |
                  ((kv.getDataVariable.byteAt(5).toLong & 0xFF) << 16) |
                  ((kv.getDataVariable.byteAt(6).toLong & 0xFF) << 8) |
                  (kv.getDataVariable.byteAt(7).toLong & 0xFF)
        val lsb = (kv.getDataVariable.byteAt(8).toLong << 56) |
                  ((kv.getDataVariable.byteAt(9).toLong & 0xFF) << 48) |
                  ((kv.getDataVariable.byteAt(10).toLong & 0xFF) << 40) |
                  ((kv.getDataVariable.byteAt(11).toLong & 0xFF) << 32) |
                  ((kv.getDataVariable.byteAt(12).toLong & 0xFF) << 24) |
                  ((kv.getDataVariable.byteAt(13).toLong & 0xFF) << 16) |
                  ((kv.getDataVariable.byteAt(14).toLong & 0xFF) << 8) |
                  (kv.getDataVariable.byteAt(15).toLong & 0xFF)
        new UUID(msb, lsb)
    }

    trait MacToIdEncoder extends StateTableEncoder[MAC, UUID] {
        @inline protected override def encodeKey(mac: MAC): String = {
            mac.toString
        }

        @inline protected override def decodeKey(string: String): MAC = {
            MAC.fromString(string)
        }

        @inline protected override def encodeValue(id: UUID): String = {
            id.toString
        }

        @inline protected override def decodeValue(string: String): UUID = {
            UUID.fromString(string)
        }

        @inline protected override def decodeKey(kv: KeyValue): MAC = {
            new MAC(kv.getData64)
        }

        @inline protected override def decodeValue(kv: KeyValue): UUID =
            keyValueToUUID(kv)
    }
    object MacToIdEncoder extends MacToIdEncoder

    trait Ip4ToMacEncoder extends StateTableEncoder[IPv4Addr, MAC] {
        @inline protected override def encodeKey(address: IPv4Addr): String = {
            address.toString
        }

        @inline protected override def decodeKey(string: String): IPv4Addr = {
            IPv4Addr(string)
        }

        @inline protected override def encodeValue(mac: MAC): String = {
            mac.toString
        }

        @inline protected override def decodeValue(string: String): MAC = {
            MAC.fromString(string)
        }

        @inline protected override def decodeKey(kv: KeyValue): IPv4Addr = {
            new IPv4Addr(kv.getData32)
        }

        @inline protected override def decodeValue(kv: KeyValue): MAC = {
            new MAC(kv.getData64)
        }
    }
    object Ip4ToMacEncoder extends Ip4ToMacEncoder

    trait MacToIp4Encoder extends StateTableEncoder[MAC, IPv4Addr] {
        @inline protected override def encodeKey(mac: MAC): String = {
            mac.toString
        }

        @inline protected override def decodeKey(string: String): MAC = {
            MAC.fromString(string)
        }

        @inline protected override def encodeValue(address: IPv4Addr): String = {
            address.toString
        }

        @inline protected override def decodeValue(string: String): IPv4Addr = {
            IPv4Addr(string)
        }

        @inline protected override def decodeKey(kv: KeyValue): MAC = {
            new MAC(kv.getData64)
        }

        @inline protected override def decodeValue(kv: KeyValue): IPv4Addr = {
            new IPv4Addr(kv.getData32)
        }
    }
    object MacToIp4Encoder extends MacToIp4Encoder

    object Fip64Encoder extends Fip64Encoder {

        object DefaultValue extends AnyRef
    }

    trait Fip64Encoder extends StateTableEncoder[Fip64Entry, AnyRef] {

        import Fip64Encoder._

        @inline protected override def encodeKey(entry: Fip64Entry): String = {
            entry.encode
        }

        @inline protected override def decodeKey(string: String): Fip64Entry = {
            Fip64Entry.decode(string)
        }

        @inline protected override def encodeValue(n: AnyRef): String = {
            "0"
        }

        @inline protected override def decodeValue(string: String): AnyRef = {
            DefaultValue
        }

        @inline protected override def decodeKey(kv: KeyValue): Fip64Entry = {
            Fip64Entry.decode(kv.getDataVariable.toStringUtf8)
        }

        @inline protected override def decodeValue(kv: KeyValue): AnyRef = {
            DefaultValue
        }
    }

    object GatewayHostEncoder extends GatewayHostEncoder {

        object DefaultValue extends AnyRef
    }

    trait GatewayHostEncoder extends StateTableEncoder[UUID, AnyRef] {

        import GatewayHostEncoder._

        @inline protected override def encodeKey(entry: UUID): String = {
            entry.toString
        }

        @inline protected override def decodeKey(string: String): UUID = {
            UUID.fromString(string)
        }

        @inline protected override def encodeValue(n: AnyRef): String = {
            "0"
        }

        @inline protected override def decodeValue(string: String): AnyRef = {
            DefaultValue
        }

        @inline protected override def decodeKey(kv: KeyValue): UUID =
            keyValueToUUID(kv)

        @inline protected override def decodeValue(kv: KeyValue): AnyRef = {
            DefaultValue
        }
    }
}

/**
  * A base trait that handles the encoding for a [[StateTable]] with the
  * given generic arguments.
  */
trait StateTableEncoder[K, V] {

    protected def encodeKey(key: K): String

    protected def decodeKey(string: String): K

    protected def encodeValue(value: V): String

    protected def decodeValue(string: String): V

    protected def decodeKey(kv: KeyValue): K

    protected def decodeValue(kv: KeyValue): V

    /**
      * @return The encoded string for the specified key, value and version
      *         3-tuple.
      */
    def encodePath(key: K, value: V, version: Int): String = {
        s"/${encodeKey(key)},${encodeValue(value)}," +
        s"${"%010d".format(version)}"
    }

    /**
      * @return The encoded string for the specified persistent key-value entry.
      */
    def encodePersistentPath(key: K, value: V): String = {
        encodePath(key, value, PersistentVersion)
    }

    /**
      * @return The decoded key, value and version 3-tuple for the specified
      *         path.
      */
    def decodePath(path: String): (K, V, Int) = {
        val string = if (path.startsWith("/")) path.substring(1)
                     else path
        val tokens = string.split(",")
        if (tokens.length != 3)
            return null
        try {
            (decodeKey(tokens(0)), decodeValue(tokens(1)),
                Integer.parseInt(tokens(2)))
        } catch {
            case NonFatal(_) => null
        }
    }

}
