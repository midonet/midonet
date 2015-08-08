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

import scala.concurrent.Future
import scala.reflect.ClassTag

import org.midonet.cluster.rest_api.models.{Chain, UriResource}
import org.midonet.cluster.services.rest_api.resources.MidonetResource._

abstract class WithChainsResource[T >: Null <: UriResource]
                                 (resContext: ResourceContext)
                                 (implicit tag: ClassTag[T])
    extends MidonetResource[T](resContext) {

    protected def idOf(res: T): UUID
    protected def inboundFilterOf(res: T): UUID
    protected def outboundFilterOf(res: T): UUID
    protected def chainReferences(chain: Chain): JList[UUID]

    protected final def updateChainsOnCreate(res: T): Ops = {
        Future.sequence(Seq(addToChain(idOf(res), inboundFilterOf(res)),
                            addToChain(idOf(res), outboundFilterOf(res))))
            .map { _.flatten }
    }

    protected final def updateChainsOnUpdate(to: T, from: T): Ops = {
        val (addedInbound, removedInbound) =
            if (inboundFilterOf(to) != inboundFilterOf(from))
                (inboundFilterOf(to), inboundFilterOf(from))
            else
                (null, null)
        val (addedOutbound, removedOutbound) =
            if (outboundFilterOf(to) != outboundFilterOf(from))
                (outboundFilterOf(to), outboundFilterOf(from))
            else
                (null, null)
        Future.sequence(Seq(addToChain(idOf(to), addedInbound),
                            addToChain(idOf(to), addedOutbound),
                            removeFromChain(idOf(from), removedInbound),
                            removeFromChain(idOf(from), removedOutbound)))
            .map { _.flatten }
    }

    protected final def updateChainsOnDelete(res: T): Ops = {
        Future.sequence(Seq(removeFromChain(idOf(res), inboundFilterOf(res)),
                            removeFromChain(idOf(res), outboundFilterOf(res))))
            .map { _.flatten }
    }

    private def addToChain(portId: UUID, chainId: UUID): Ops = {
        if (chainId ne null) {
            getResource(classOf[Chain], chainId) map { chain =>
                chainReferences(chain).add(portId)
                Seq[Multi](Update(chain))
            }
        } else NoOps
    }

    private def removeFromChain(portId: UUID, chainId: UUID): Ops = {
        if (chainId ne null) {
            getResource(classOf[Chain], chainId) map { chain =>
                chainReferences(chain).remove(portId)
                Seq[Multi](Update(chain))
            }
        } else NoOps
    }
}
