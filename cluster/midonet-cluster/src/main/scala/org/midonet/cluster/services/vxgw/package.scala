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

package org.midonet.cluster.services

import java.util.UUID

import org.midonet.packets.IPv4Addr

package object vxgw {

    // Top level logger
    val vxgwLog = "org.midonet.vxgw"

    // For control of the hardware VTEP
    def vxgwVtepControlLog(ip: IPv4Addr, port: Int)
        = s"org.midonet.vxgw.vxgw-controller-vtep-${ip.toString.replaceAll("\\.", "_")}:$port"
    // For interaction with the MidoNet side of a VxGW
    def vxgwMidonetLog(networkId: UUID)
        = s"org.midonet.vxgw.vxgw-controller-midonet-$networkId"
    // For management of a VxGW joining a Network with Hardware VTEPs
    def vxgwMgmtLog(networkId: UUID)
        = s"org.midonet.vxgw.vxgw-manager.$networkId"

}
