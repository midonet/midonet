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

import org.midonet.cluster.rest_api.neutron.models.L2GatewayConnection
import org.midonet.cluster.rest_api.{NotFoundHttpException, ConflictHttpException}

trait L2GatewayConnectionApi {

    @throws(classOf[NotFoundHttpException])
    def getL2GatewayConnection(id: UUID): L2GatewayConnection

    def getL2GatewayConnections: util.List[L2GatewayConnection]

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createL2GatewayConnection(vpn: L2GatewayConnection): Unit

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateL2GatewayConnection(vpn: L2GatewayConnection): Unit

    def deleteL2GatewayConnection(id: UUID): Unit
}
