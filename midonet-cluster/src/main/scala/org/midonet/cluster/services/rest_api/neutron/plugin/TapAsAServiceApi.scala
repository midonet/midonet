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

package org.midonet.cluster.services.rest_api.neutron.plugin

import java.util
import java.util.UUID

import org.midonet.cluster.rest_api.neutron.models.{TapFlow, TapService}
import org.midonet.cluster.rest_api.{ConflictHttpException, NotFoundHttpException}

trait TapAsAServiceApi {
    def createTapFlow(flow: TapFlow): Unit
    def updateTapFlow(flow: TapFlow): Unit
    def deleteTapFlow(id: UUID): Unit
    def getTapFlow(id: UUID): TapFlow
    def getTapFlows: util.List[TapFlow]

    def createTapService(service: TapService): Unit
    def updateTapService(service: TapService): Unit
    def deleteTapService(id: UUID): Unit
    def getTapService(id: UUID): TapService
    def getTapServices: util.List[TapService]
}
