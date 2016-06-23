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

import java.util.UUID

import org.midonet.cluster.rest_api.neutron.models.{FirewallLog, LoggingResource}
import org.midonet.cluster.rest_api.{NotFoundHttpException, ConflictHttpException}

trait FirewallLoggingApi {

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateLoggingResource(loggingResource: LoggingResource): Unit

    def deleteLoggingResource(id: UUID): Unit

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createFirewallLog(firewallLog: FirewallLog): Unit

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateFirewallLog(firewallLog: FirewallLog): Unit

    def deleteFirewallLog(id: UUID): Unit
}
