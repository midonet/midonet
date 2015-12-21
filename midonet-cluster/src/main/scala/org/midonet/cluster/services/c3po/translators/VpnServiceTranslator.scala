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
import org.midonet.cluster.models.Neutron.VpnService
import org.midonet.cluster.services.c3po.C3POStorageManager.{Operation,Update}
import org.midonet.util.concurrent.toFutureOps

class VpnServiceTranslator(protected val storage: ReadOnlyStorage)
    extends Translator[VpnService]
            with ChainManager with RouteManager with RuleManager {
    override protected def translateCreate(vpn: VpnService): OperationList =
        List()

    override protected def translateDelete(vpnId: UUID): OperationList =
        List()

    override protected def translateUpdate(vpn: VpnService): OperationList = {
        // No Midonet-specific changes, but changes to the VPNService are
        // handled in retainHighLevelModel().
        List()
    }

    override protected def retainHighLevelModel(op: Operation[VpnService])
    : List[Operation[VpnService]] = op match {
        case Update(vpn, _) =>
            // Need to override update to make sure only certain fields are
            // updated, to avoid overwriting ipsec_site_conn_ids, which Neutron
            // doesn't know about.
            val oldVpn = storage.get(classOf[VpnService], vpn.getId).await()
            val newVpn = vpn.toBuilder()
                .addAllIpsecSiteConnectionIds(oldVpn.getIpsecSiteConnectionIdsList).build()
            List(Update(newVpn))
        case _ => super.retainHighLevelModel(op)
    }
}

