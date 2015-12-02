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

package org.midonet.cluster.services.rest_api.neutron.plugin

import java.util
import java.util.UUID

import org.midonet.cluster.rest_api.neutron.models.{IPSecSiteConnection, VPNService}

// TODO: Declare exceptions
trait VpnServiceApi {

    def getVpnService(id: UUID): VPNService

    def getVpnServices: util.List[VPNService]

    def createVpnService(vpn: VPNService): Unit

    def deleteVpnService(id: UUID): Unit

    def getIpSecSiteConnection(id: UUID): IPSecSiteConnection

    def getIpSecSiteConnections: util.List[IPSecSiteConnection]

    def createIpSecSiteConnection(cnxn: IPSecSiteConnection): Unit

    def deleteIpSecSiteConnection(id: UUID): Unit
}
