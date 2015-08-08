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

package org.midonet.cluster.services.rest_api.resources

import java.util.{List => JList, UUID}

import scala.collection.JavaConverters._
import scala.collection.mutable.ListBuffer
import scala.concurrent.{ExecutionContext, Future}

import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.services.rest_api.resources.MidonetResource._
import org.midonet.cluster.services.rest_api.resources.WithChainsResource.{BridgeChains, PortChains, ResourceChains, RouterChains}

object WithChainsResource {

    trait ResourceChains {
        def id: UUID
        def inboundFilterId: UUID
        def outboundFilterId: UUID
        def chainReferences(chain: Chain): JList[UUID]
    }

    case class BridgeChains(bridge: Bridge) extends ResourceChains {
        override def id: UUID = bridge.id
        override def inboundFilterId: UUID = bridge.inboundFilterId
        override def outboundFilterId: UUID = bridge.outboundFilterId
        override def chainReferences(chain: Chain): JList[UUID] = chain.networkIds
    }

    case class RouterChains(router: Router) extends ResourceChains {
        override def id: UUID = router.id
        override def inboundFilterId: UUID = router.inboundFilterId
        override def outboundFilterId: UUID = router.outboundFilterId
        override def chainReferences(chain: Chain): JList[UUID] = chain.routerIds
    }

    case class PortChains(port: Port) extends ResourceChains {
        override def id: UUID = port.id
        override def inboundFilterId: UUID = port.inboundFilterId
        override def outboundFilterId: UUID = port.outboundFilterId
        override def chainReferences(chain: Chain): JList[UUID] = chain.portIds
    }

}

/**
 * Provides utility methods for managing resource with chains.
 */
trait WithChainsResource {

    protected implicit def executionContext: ExecutionContext

    protected def getResource[U >: Null <: UriResource](clazz: Class[U], id: Any)
    : Future[U]

    protected def listResources[U >: Null <: UriResource](clazz: Class[U],
                                                          ids: Seq[Any])
    : Future[Seq[U]]

    /** Updates the chains back-references when creating the specified bridge. */
    protected final def updateChainsOnCreate(bridge: Bridge): Ops = {
        updateChainsOnCreate(BridgeChains(bridge))
    }

    /** Updates the chains back-references when creating the specified router. */
    protected final def updateChainsOnCreate(router: Router): Ops = {
        updateChainsOnCreate(RouterChains(router))
    }

    /** Updates the chains back-references when creating the specified port. */
    protected final def updateChainsOnCreate(port: Port): Ops = {
        updateChainsOnCreate(PortChains(port))
    }

    /** Updates the chains back-references when updating the specified bridge. */
    protected final def updateChainsOnUpdate(to: Bridge, from: Bridge): Ops = {
        updateChainsOnUpdate(BridgeChains(to), BridgeChains(from))
    }

    /** Updates the chains back-references when updating the specified router. */
    protected final def updateChainsOnUpdate(to: Router, from: Router): Ops = {
        updateChainsOnUpdate(RouterChains(to), RouterChains(from))
    }

    /** Updates the chains back-references when updating the specified port. */
    protected final def updateChainsOnUpdate(to: Port, from: Port): Ops = {
        updateChainsOnUpdate(PortChains(to), PortChains(from))
    }

    /** Updates the chains back-references when deleting the specified bridge.
      * Since the ports are deleted by the ZOOM bindings, we also clear the
      * back-references for the bridge's ports. */
    protected final def updateChainsOnDelete(bridge: Bridge): Ops = {
        updateChainsOnDelete(BridgeChains(bridge)) flatMap {
            updateChainsOnDeletePorts(_, bridge.portIds)
        }
    }

    /** Updates the chains back-references when deleting the specified router.
      * Since the ports are deleted by the ZOOM bindings, we also clear the
      * back-references for the routers's ports. */
    protected final def updateChainsOnDelete(router: Router): Ops = {
        updateChainsOnDelete(RouterChains(router)) flatMap {
            updateChainsOnDeletePorts(_, router.portIds)
        }
    }

    /** Updates the chains back-references when deleting the specified port. */
    protected final def updateChainsOnDelete(port: Port): Ops = {
        updateChainsOnDelete(PortChains(port))
    }

    private def updateChainsOnCreate(res: ResourceChains): Ops = {
        Future.sequence(Seq(addToChain(res, res.inboundFilterId),
                            addToChain(res, res.outboundFilterId)))
            .map { _.flatten }
    }

    private def updateChainsOnUpdate(to: ResourceChains, from: ResourceChains)
    : Ops = {
        val ops = new ListBuffer[Ops]
        if (to.inboundFilterId != from.inboundFilterId) {
            ops += addToChain(to, to.inboundFilterId)
            ops += removeFromChain(from, from.inboundFilterId)
        }
        if (to.outboundFilterId != from.outboundFilterId) {
            ops += addToChain(to, to.outboundFilterId)
            ops += removeFromChain(from, from.outboundFilterId)
        }
        Future.sequence(ops.toSeq).map(_.flatten)
    }

    private def updateChainsOnDelete(res: ResourceChains): Ops = {
        Future.sequence(Seq(removeFromChain(res, res.inboundFilterId),
                            removeFromChain(res, res.outboundFilterId)))
            .map { _.flatten }
    }

    private def updateChainsOnDeletePorts(ops: Seq[Multi], portIds: JList[UUID])
    : Ops = {
        if (portIds eq null) return Future.successful(ops)
        listResources(classOf[Port], portIds.asScala) flatMap { ports =>
            val futures = for (port <- ports) yield updateChainsOnDelete(port)
            Future.sequence(futures)
        } map { o => ops ++ o.flatten }
    }

    private def addToChain(res: ResourceChains, chainId: UUID): Ops = {
        if (chainId ne null) {
            getResource(classOf[Chain], chainId) map { chain =>
                res.chainReferences(chain).add(res.id)
                Seq[Multi](Update(chain))
            }
        } else NoOps
    }

    private def removeFromChain(res: ResourceChains, chainId: UUID): Ops = {
        if (chainId ne null) {
            getResource(classOf[Chain], chainId) map { chain =>
                res.chainReferences(chain).remove(res.id)
                Seq[Multi](Update(chain))
            }
        } else NoOps
    }

}
