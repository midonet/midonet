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
import javax.ws.rs.Path
import javax.ws.rs.core.MediaType._

import scala.collection.JavaConverters._
import com.google.inject.Inject
import com.google.inject.servlet.RequestScoped
import org.midonet.cluster.rest_api.annotation._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext

@RequestScoped
@ApiResource(version = 1, template = "qosDscpRuleTemplate")
@Path("qos_dscp_rules")
@AllowGet(Array(APPLICATION_QOS_RULE_DSCP_JSON,
                APPLICATION_JSON))
@AllowUpdate(Array(APPLICATION_QOS_RULE_DSCP_JSON,
                   APPLICATION_JSON))
@AllowDelete
class QOSRuleDSCPResource @Inject()(resContext: ResourceContext)
    extends MidonetResource[QOSRuleDSCP](resContext) {
}

@RequestScoped
@AllowList(Array(APPLICATION_QOS_RULE_DSCP_COLLECTION_JSON,
                 APPLICATION_JSON))
@AllowCreate(Array(APPLICATION_QOS_RULE_DSCP_JSON,
                   APPLICATION_JSON))
class QOSPolicyRuleDSCPResource @Inject()(policyId: UUID,
                                          resContext: ResourceContext)
    extends MidonetResource[QOSRuleDSCP](resContext) {

    protected override def listIds: Seq[Any] = {
        getResource(classOf[QOSPolicy], policyId).dscpMarkingRuleIds.asScala
    }

    protected override def createFilter(rule: QOSRuleDSCP, tx: ResourceTransaction)
    : Unit = {
        rule.policyId = policyId
        tx.create(rule)
    }
}
