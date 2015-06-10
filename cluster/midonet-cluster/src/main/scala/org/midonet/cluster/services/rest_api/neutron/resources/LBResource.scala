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

package org.midonet.cluster.services.rest_api.neutron.resources

import javax.ws.rs.Path
import javax.ws.rs.core.UriInfo

import com.google.inject.Inject

import org.midonet.cluster.rest_api.neutron.NeutronResourceUris
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin

class LBResource @Inject() (uriInfo: UriInfo, api: NeutronZoomPlugin) {

    @Path(Array(NeutronResourceUris.HEALTH_MONITORS))
    def getHealthMonitorResource: HealthMonitorResource = {
        new HealthMonitorResource(uriInfo, api)
    }

    @Path(Array(NeutronResourceUris.MEMBERS))
    def getMemberResource: MemberResource = new MemberResource(uriInfo, api)

    @Path(Array(NeutronResourceUris.POOLS))
    def getPoolResource: PoolResource =
        new PoolResource(uriInfo, api)

    @Path(Array(NeutronResourceUris.VIPS))
    def getVipResource: VipResource = new VipResource(uriInfo, api)

    @Path(Array(NeutronResourceUris.POOL_HEALTH_MONITOR))
    def getPoolHealthMonitorResource: PoolHealthMonitorResource = {
        new PoolHealthMonitorResource()
    }
}