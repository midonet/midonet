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

package org.midonet.cluster.services.rest_api.resources

import java.util.UUID
import javax.ws.rs._
import javax.ws.rs.core.MediaType._

import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@ApiResource(version = 1, name = "qosPolicies",
             template = "qosPolicyTemplate")
@Path("qos_policies")
@RequestScoped
@AllowCreate(Array(APPLICATION_QOS_POLICY_JSON,
                   APPLICATION_JSON))
@AllowGet(Array(APPLICATION_QOS_POLICY_JSON,
                APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_QOS_POLICY_JSON,
                   APPLICATION_JSON))
@AllowList(Array(APPLICATION_QOS_POLICY_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowDelete
class QOSPolicyResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[QOSPolicy](resContext) {

    @Path("{id}/qos_bw_limit_rules")
    def bw_limit_rules(@PathParam("id") id: UUID): QOSPolicyRuleBWLimitResource = {
        new QOSPolicyRuleBWLimitResource(id, resContext)
    }

    @Path("{id}/qos_dscp_rules")
    def dscp_rules(@PathParam("id") id: UUID): QOSPolicyRuleDSCPResource = {
        new QOSPolicyRuleDSCPResource(id, resContext)
    }
}
