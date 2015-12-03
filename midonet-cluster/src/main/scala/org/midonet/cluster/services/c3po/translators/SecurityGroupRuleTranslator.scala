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

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.SecurityGroupRule
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.services.c3po.midonet._

class SecurityGroupRuleTranslator(storage: ReadOnlyStorage)
    extends Translator[SecurityGroupRule] with ChainManager {

    /*
     * Need to create the rule, but also need to add it to the security
     * group chain that it is assigned to.
     */
    protected override def translateCreate(sgr: SecurityGroupRule)
    : MidoOpList = {
        List(Create(SecurityGroupRuleManager.translate(sgr)))
    }

    protected override def translateUpdate(newSgr: SecurityGroupRule)
    : MidoOpList = {
        throw new IllegalArgumentException(
            "SecurityGroupRule update not supported.")
    }

    protected override def translateDelete(sgrId: UUID) : MidoOpList = {
        List(Delete(classOf[Rule], sgrId))
    }
}
