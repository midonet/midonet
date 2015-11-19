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

import javax.ws.rs.core.MediaType.APPLICATION_JSON

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped

import org.midonet.cluster.rest_api.annotation.{AllowCreate, AllowDelete, AllowGet, AllowList, _}
import org.midonet.cluster.rest_api.neutron.models.VPNService
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

@RequestScoped
@AllowGet(Array(NEUTRON_VPN_SERVICE_JSON_V1,
                APPLICATION_JSON))
@AllowList(Array(NEUTRON_VPN_SERVICE_JSON_V1,
                 APPLICATION_JSON))
@AllowCreate(Array(NEUTRON_VPN_SERVICE_JSON_V1,
                   APPLICATION_JSON))
@AllowDelete
class VPNServiceResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[VPNService](resContext)
