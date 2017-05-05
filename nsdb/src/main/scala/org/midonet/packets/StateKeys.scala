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

package org.midonet.packets

import java.util.UUID

import scala.concurrent.duration._

import org.midonet.packets.FlowStateStore.IdleExpiration

object FlowStateStore {

    val DEFAULT_EXPIRATION = 60 seconds

    trait IdleExpiration {
        var expiresAfter: Duration = DEFAULT_EXPIRATION
    }

    trait StateSerializer[T] {
        def toBytes(value: T): Array[Byte]
        def fromBytes(bytes: Array[Byte]): T
    }
}

object NatState {
    sealed abstract class KeyType {
        def inverse: KeyType
    }
    case object FWD_SNAT extends KeyType {
        def inverse = REV_SNAT
    }
    case object FWD_DNAT extends KeyType {
        def inverse = REV_DNAT
    }
    case object FWD_STICKY_DNAT extends KeyType {
        def inverse = REV_STICKY_DNAT
    }
    case object REV_SNAT extends KeyType {
        def inverse = FWD_SNAT
    }
    case object REV_DNAT extends KeyType {
        def inverse = FWD_DNAT
    }
    case object REV_STICKY_DNAT extends KeyType {
        def inverse = FWD_STICKY_DNAT
    }

    trait NatKeyAllocator[T] {
        def apply(keyType: KeyType,
                  networkSrc: IPv4Addr,
                  transportSrc: Int,
                  networkDst: IPv4Addr,
                  transportDst: Int,
                  networkProtocol: Byte,
                  deviceId: UUID): T
    }

    object NatKeyStore extends NatKeyAllocator[NatKeyStore]

    case class NatKeyStore(var keyType: KeyType,
                           var networkSrc: IPv4Addr,
                           var transportSrc: Int,
                           var networkDst: IPv4Addr,
                           var transportDst: Int,
                           var networkProtocol: Byte,
                           var deviceId: UUID) extends IdleExpiration {
        override def toString = s"nat:$keyType:$networkSrc:$transportSrc:" +
                                s"$networkDst:$transportDst:$networkProtocol:" +
                                s"$deviceId"
    }

    case class NatBinding(var networkAddress: IPv4Addr, var transportPort: Int)

}

object ConnTrackState {

    trait ConnTrackKeyAllocator[T] {
        def apply(networkSrc: IPAddr,
                  icmpIdOrTransportSrc: Int,
                  networkDst: IPAddr,
                  icmpIdOrTransportDst: Int,
                  networkProtocol: Byte,
                  deviceId: UUID): T
    }

    object ConnTrackKeyStore extends ConnTrackKeyAllocator[ConnTrackKeyStore]

    case class ConnTrackKeyStore(var networkSrc: IPAddr,
                                 var icmpIdOrTransportSrc: Int,
                                 var networkDst: IPAddr,
                                 var icmpIdOrTransportDst: Int,
                                 var networkProtocol: Byte,
                                 var deviceId: UUID) extends IdleExpiration {
        override def toString = s"conntrack:$networkSrc:$icmpIdOrTransportSrc:" +
                                s"$networkDst:$icmpIdOrTransportDst:" +
                                s"$networkProtocol:$deviceId"

    }
}

object TraceState {

    trait TraceKeyAllocator[T] {
        def apply(ethSrc: MAC, ethDst: MAC, etherType: Short,
                  networkSrc: IPAddr, networkDst: IPAddr,
                  networkProto: Byte, srcPort: Int, dstPort: Int): T
    }

    object TraceKeyStore extends TraceKeyAllocator[TraceKeyStore]

    case class TraceKeyStore(ethSrc: MAC, ethDst: MAC, etherType: Short,
                             networkSrc: IPAddr, networkDst: IPAddr,
                             networkProto: Byte, srcPort: Int, dstPort: Int)
        extends IdleExpiration {

        expiresAfter = 5 seconds
    }
}
