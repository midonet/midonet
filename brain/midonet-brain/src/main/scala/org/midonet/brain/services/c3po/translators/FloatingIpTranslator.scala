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

package org.midonet.brain.services.c3po.translators

import scala.collection.JavaConverters._

import org.midonet.brain.services.c3po.midonet.{Create, Delete, MidoOp}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.{FloatingIp, NeutronRouter}
import org.midonet.cluster.models.Topology.{Port, Route, Router}
import org.midonet.cluster.util.IPSubnetUtil
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for FloatingIp. */
class FloatingIpTranslator(protected val readOnlyStorage: ReadOnlyStorage)
        extends NeutronTranslator[FloatingIp] with RouteManager {
    import org.midonet.brain.services.c3po.translators.RouteManager._
    import org.midonet.brain.services.c3po.translators.RouterTranslator._
    implicit val storage: ReadOnlyStorage = readOnlyStorage

    override protected def translateCreate(fip: FloatingIp): MidoOpList = {
        // If a port is not assigned, there's nothing to do.
        if (!fip.hasPortId) return List()

        val midoOps = new MidoOpListBuffer

        midoOps ++= generateGwRouteCreateOp(fip)

        // TODO Add SNAT and DNAT rules to the tenant router, and adds it at the
        // 1st position of the rule chain.

        midoOps.toList
    }

    override protected def translateDelete(id: UUID): MidoOpList = {
        val midoOps = new MidoOpListBuffer

        val fip = storage.get(classOf[FloatingIp], id).await()
        if (fip.hasPortId) {
            midoOps += Delete(classOf[Route], fipGatewayRouteId(id))
        }

        // TODO Delete SNAT and DNAT rules.

        midoOps.toList
    }

    override protected def translateUpdate(fip: FloatingIp): MidoOpList = {
        val midoOps = new MidoOpListBuffer
        val oldFip = storage.get(classOf[FloatingIp], fip.getId).await()

        if (!oldFip.hasPortId && fip.hasPortId) {
            midoOps ++= generateGwRouteCreateOp(fip)
        } else if (oldFip.hasPortId && !fip.hasPortId) {
            midoOps += Delete(classOf[Route], fipGatewayRouteId(fip.getId))
        }
        // If the Floating IP is kept associated, no need to update the route.

        // TODO Update SNAT/DNAT rules.

        midoOps.toList
    }

    /* Generates a Create Op for the GW route. */
    private def generateGwRouteCreateOp(fip: FloatingIp)
    : Option[Create[Route]] = {
        val nr = storage.get(classOf[NeutronRouter], fip.getRouterId).await()
        if (!nr.hasGwPortId) None else {
            Some(Create(newNextHopPortRoute(nr.getGwPortId,
                                            id = fipGatewayRouteId(fip.getId),
                                            dstSubnet = IPSubnetUtil.fromAddr(
                                                    fip.getFloatingIpAddress))))
        }
    }
}