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

import org.midonet.cluster.rest_api.{NotFoundHttpException, ConflictHttpException}
import org.midonet.cluster.rest_api.neutron.models.{IPSecSiteConnection, VpnService}

trait VpnServiceApi {

    @throws(classOf[NotFoundHttpException])
    def getVpnService(id: UUID): VpnService

    def getVpnServices: util.List[VpnService]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createVpnService(vpn: VpnService): Unit

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateVpnService(vpn: VpnService): Unit

    def deleteVpnService(id: UUID): Unit

    @throws(classOf[NotFoundHttpException])
    def getIpSecSiteConnection(id: UUID): IPSecSiteConnection

    def getIpSecSiteConnections: util.List[IPSecSiteConnection]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createIpSecSiteConnection(cnxn: IPSecSiteConnection): Unit

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateIpSecSiteConnection(cnxn: IPSecSiteConnection): Unit

    def deleteIpSecSiteConnection(id: UUID): Unit
}
