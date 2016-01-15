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

import java.util.UUID

import scala.collection.JavaConverters._
import scala.collection.immutable.TreeSet

import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Neutron.{IPSecSiteConnection, VpnService}
import org.midonet.cluster.models.Topology.{Port, Route, ServiceContainer}
import org.midonet.cluster.services.c3po.C3POStorageManager.{Create, Delete, Update}
import org.midonet.cluster.services.c3po.translators.IPSecSiteConnectionTranslator._
import org.midonet.cluster.util.UUIDUtil
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.containers
import org.midonet.util.concurrent.toFutureOps

class IPSecSiteConnectionTranslator(protected val storage: ReadOnlyStorage)
        extends Translator[IPSecSiteConnection]
        with RouteManager {

    /* Implement the following for CREATE/UPDATE/DELETE of the model */
    override protected def translateCreate(cnxn: IPSecSiteConnection)
    : OperationList = {
        val vpn = storage.get(classOf[VpnService], cnxn.getVpnserviceId).await()

        createRemoteRoutes(cnxn, vpn).map(Create(_))
    }

    override protected def translateDelete(id: Commons.UUID): OperationList = {
        // retainHighLevelModel() should delete the IPSecSiteConnection, and
        // this should cascade to the routes due to Zoom bindings.
        List()
    }

    override protected def translateUpdate(cnxn: IPSecSiteConnection)
    : OperationList = {
        val vpn = storage.get(classOf[VpnService], cnxn.getVpnserviceId).await()
        val (delRoutes, addRoutes, currentRoutes) = updateRemoteRoutes(cnxn, vpn)

        val newCnxn = cnxn.toBuilder
            .addAllRouteIds(currentRoutes.map(toProto).asJava).build

        delRoutes.map(r => Delete(classOf[Route], r.getId)) ++
        addRoutes.map(Create(_)) ++
        List(Update(newCnxn))

    }

    /** Generate options to create routes for Cartesian product of cnxn's
      * local CIDRs and peer CIDRs.
      */
    private def createRemoteRoutes(cnxn: IPSecSiteConnection,
                                  vpn: VpnService): List[Route] = {
        val container = storage.get(classOf[ServiceContainer],
                                    vpn.getContainerId).await()
        val routerPort = storage.get(classOf[Port],
                                     container.getPortId).await()
        val routerPortId = routerPort.getId
        val routerPortSubnet = routerPort.getPortSubnet

        val localPeerCidrPairs = for {
            localCidr <- cnxn.getLocalCidrsList.asScala;
            peerCidr <- cnxn.getPeerCidrsList.asScala
        } yield (localCidr, peerCidr)

        localPeerCidrPairs.map { case (localCidr, peerCidr) =>
            newNextHopPortRoute(
                id = UUIDUtil.randomUuidProto,
                ipSecSiteCnxnId = cnxn.getId,
                nextHopPortId = routerPortId,
                nextHopGwIpAddr = containers.containerPortAddress(routerPortSubnet),
                srcSubnet = localCidr,
                dstSubnet = peerCidr)
        }.toList
    }

    private def updateRemoteRoutes(cnxn: IPSecSiteConnection,
                                   vpn: VpnService): (List[Route],
                                                      List[Route],
                                                      List[UUID]) = {

        val oldCnxn = storage.get(classOf[IPSecSiteConnection], cnxn.getId).await()
        val oldRoutes = TreeSet(storage.getAll(classOf[Route],
                                               oldCnxn.getRouteIdsList)
                                    .await(): _*) (routeOrdering)
        val newRoutes = TreeSet(createRemoteRoutes(cnxn, vpn): _*) (routeOrdering)

        val toDelete = oldRoutes &~ newRoutes
        val toCreate = newRoutes &~ oldRoutes
        // keep the old route ids + the new ones
        val updatedIds = ((oldRoutes & newRoutes) | toCreate).map(_.getId.asJava)
        (toDelete.toList, toCreate.toList, updatedIds.toList)
    }
}

object IPSecSiteConnectionTranslator {

    private val routeOrdering = Ordering.fromLessThan[Route](
        (a: Route, b: Route) => {
            a.getSrcSubnet != b.getSrcSubnet ||
            a.getDstSubnet != b.getDstSubnet
        })
}
