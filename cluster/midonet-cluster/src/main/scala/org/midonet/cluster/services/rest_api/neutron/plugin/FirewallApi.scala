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

import org.midonet.cluster.rest_api.neutron.models._
import org.midonet.cluster.rest_api.{ConflictHttpException, NotFoundHttpException}

trait FirewallApi {

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createFirewall(firewall: Firewall)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateFirewall(firewall: Firewall)

    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteFirewall(id: UUID)
}