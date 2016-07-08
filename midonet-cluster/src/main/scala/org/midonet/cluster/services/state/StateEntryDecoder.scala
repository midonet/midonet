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

package org.midonet.cluster.services.state

import java.nio.{ByteBuffer, ByteOrder}
import java.util.UUID

import com.google.protobuf.ByteString

import org.midonet.cluster.rpc.State.KeyValue
import org.midonet.packets.MAC.InvalidMacException
import org.midonet.packets.{IPv4Addr, MAC}

object StateEntryDecoder {

    private val MacClass = classOf[MAC]
    private val Ip4Class = classOf[IPv4Addr]
    private val UuidClass = classOf[UUID]

    private object DefaultDecoder extends StateEntryDecoder {
        override def decode(string: String): KeyValue = {
            KeyValue.newBuilder()
                    .setDataVariable(ByteString.copyFromUtf8(string))
                    .build()
        }
    }

    private object MacDecoder extends StateEntryDecoder {
        @throws[InvalidMacException]
        override def decode(string: String): KeyValue = {
            KeyValue.newBuilder()
                    .setData64(MAC.stringToLong(string))
                    .build()
        }
    }

    private object Ip4Decoder extends StateEntryDecoder {
        @throws[IllegalArgumentException]
        override def decode(string: String): KeyValue = {
            KeyValue.newBuilder()
                    .setData32(IPv4Addr.stringToInt(string))
                    .build()
        }
    }

    private object UuidDecoder extends StateEntryDecoder {
        @throws[IllegalArgumentException]
        override def decode(string: String): KeyValue = {
            val id = UUID.fromString(string)
            KeyValue.newBuilder()
                    .setDataVariable(ByteString.copyFrom(
                        ByteBuffer.allocate(16)
                            .order(ByteOrder.BIG_ENDIAN)
                            .putLong(id.getMostSignificantBits)
                            .putLong(id.getLeastSignificantBits)
                            .rewind().asInstanceOf[ByteBuffer]))
                    .build()
        }
    }

    /**
      * @return A [[StateEntryDecoder]] for the specified class.
      */
    def get(clazz: Class[_]): StateEntryDecoder = {
        clazz match {
            case MacClass => MacDecoder
            case Ip4Class => Ip4Decoder
            case UuidClass => UuidDecoder
            case _ => DefaultDecoder
        }
    }

}

trait StateEntryDecoder {

    @throws[Exception]
    def decode(string: String): KeyValue

}
