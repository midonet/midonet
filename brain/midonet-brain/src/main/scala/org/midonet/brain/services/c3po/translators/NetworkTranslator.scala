/*
 * Copyright 2014 Midokura SARL
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

import scala.collection.mutable.ListBuffer

import com.google.protobuf.Message

import org.midonet.brain.services.c3po.midonet.{Create, Delete, MidoOp, Update}
import org.midonet.brain.services.c3po.translators.RouterTranslator.{providerRouterId, providerRouterName}
import org.midonet.cluster.data.storage.ReadOnlyStorage
import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Topology.{Network, Router}
import org.midonet.util.concurrent.toFutureOps

/** Provides a Neutron model translator for Network. */
class NetworkTranslator(storage: ReadOnlyStorage)
    extends NeutronTranslator[NeutronNetwork] {

    override protected def translateCreate(nn: NeutronNetwork)
    : List[MidoOp[_ <: Message]] = {
        val ops = new ListBuffer[MidoOp[_ <: Message]]()
        ops ++= ensureProviderRouterExists(nn)
        ops += Create(translate(nn))
        ops.toList
    }

    override protected def translateUpdate(nn: NeutronNetwork)
    : List[MidoOp[_ <: Message]] = {
        val ops = new ListBuffer[MidoOp[_ <: Message]]()
        ops += Update(translate(nn))
        ops.toList
    }

    override protected def translateDelete(id: UUID)
    : List[MidoOp[_ <: Message]] = {
        val ops = new ListBuffer[MidoOp[_ <: Message]]()
        ops += Delete(classOf[Network], id)
        ops.toList
    }

    @inline
    private def translate(network: NeutronNetwork) = Network.newBuilder()
        .setId(network.getId)
        .setTenantId(network.getTenantId)
        .setName(network.getName)
        .setAdminStateUp(network.getAdminStateUp)
        .build

    /**
     * Return an Option which has an operation to create the provider router
     * iff it does not already exist.
     */
    private def ensureProviderRouterExists(nn: NeutronNetwork)
    : Option[Create[Router]] = {
        if (!nn.hasExternal || !nn.getExternal ||
            storage.exists(classOf[Router], providerRouterId).await()) {
            None
        } else {
            Some(Create(Router.newBuilder()
                              .setAdminStateUp(true)
                              .setId(providerRouterId)
                              .setName(providerRouterName).build()))
        }
    }
}
