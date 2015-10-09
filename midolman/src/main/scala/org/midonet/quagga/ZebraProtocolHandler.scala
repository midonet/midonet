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

package org.midonet.quagga

import org.midonet.packets.{IPv4Subnet, IPv4Addr}
import org.midonet.quagga.ZebraProtocol.RIBType

case class ZebraPath(ribType: RIBType.Value, gateway: IPv4Addr, distance: Byte)

trait ZebraProtocolHandler {
    def addRoutes(destination: IPv4Subnet, routes: Set[ZebraPath])

    def removeRoute(ribType: RIBType.Value, destination: IPv4Subnet,
                    gateway: IPv4Addr)
}
