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

package org.midonet.cluster.services.c3po.translators

import org.midonet.cluster.data.storage.{ReadOnlyStorage, Transaction}
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{SecurityGroup, SecurityGroupRule}
import org.midonet.cluster.models.Topology.Rule

class SecurityGroupRuleTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[SecurityGroupRule] with ChainManager {

    /*
     * Need to create the rule, but also need to add it to the security
     * group chain that it is assigned to.
     */
    protected override def translateCreate(tx: Transaction,
                                           sgr: SecurityGroupRule)
    : OperationList = {
        val sgId = sgr.getSecurityGroupId
        val sg = tx.get(classOf[SecurityGroup], sgId)
        val updatedSg = sg.toBuilder.addSecurityGroupRules(sgr).build()
        SecurityGroupRuleManager.translate(sgr).foreach(tx.update(_, null))
        tx.update(updatedSg)
        List()
    }

    protected override def translateUpdate(tx: Transaction,
                                           newSgr: SecurityGroupRule)
    : OperationList = {
        throw new IllegalArgumentException(
            "SecurityGroupRule update not supported.")
    }

    // Intentionally overriding the UUID overload instead of the
    // SecurityGroupRule overload, to bypass the exists check in super
    // implementation. Prior to 5.2 (change bfb99f08), when translating the
    // creation of a Neutron SecurityGroup with embedded rules, we didn't create
    // a corresponding top-level Neutron SecurityGroupRule in ZK. This means
    // that sometimes we'll get a request to delete a SecurityGroupRule that
    // doesn't exist as a top-level SGR in ZK, but we still need to delete the
    // corresponding Midonet rules.
    protected override def translateDelete(tx: Transaction,
                                           sgrId: UUID): OperationList = {
        if (tx.exists(classOf[SecurityGroupRule], sgrId)) {
            val sgr = tx.get(classOf[SecurityGroupRule], sgrId)
            val sgId = sgr.getSecurityGroupId
            val sg = tx.get(classOf[SecurityGroup], sgId)
            val i = sg.getSecurityGroupRulesList.indexOf(sgr)
            if (i >= 0) {
                tx.update(sg.toBuilder.removeSecurityGroupRules(i).build())
            }
        }
        tx.delete(classOf[Rule], sgrId)
        tx.delete(classOf[Rule], SecurityGroupRuleManager.nonHeaderRuleId(sgrId))
        List()
    }
}
