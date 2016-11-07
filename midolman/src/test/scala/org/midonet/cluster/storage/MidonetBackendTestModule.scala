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
package org.midonet.cluster.storage

import java.util.UUID

import com.codahale.metrics.MetricRegistry
import com.typesafe.config.Config

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.mockito.Mockito._

import rx.subjects.BehaviorSubject

import org.midonet.cluster.data.storage.model.{ArpEntry, Fip64Entry}
import org.midonet.cluster.data.storage.{InMemoryStorage, StateStorage, StateTableStorage, Storage}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.models.Topology.Router
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.discovery.{FakeDiscovery, MidonetDiscovery}
import org.midonet.cluster.services.state.client.StateTableClient
import org.midonet.conf.MidoTestConfigurator
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.eventloop.Reactor

class MidonetTestBackend (curatorParam: CuratorFramework) extends MidonetBackend {

    def this() = {
        this(mock(classOf[CuratorFramework]))
    }

    private val inMemoryZoom: InMemoryStorage = new InMemoryStorage()
    inMemoryZoom.registerTable(classOf[Topology.Network], classOf[MAC],
                               classOf[UUID], MidonetBackend.MacTable,
                               classOf[MacIdStateTable])
    inMemoryZoom.registerTable(classOf[Topology.Network], classOf[IPv4Addr],
                               classOf[MAC], MidonetBackend.Ip4MacTable,
                               classOf[Ip4MacStateTable])
    inMemoryZoom.registerTable(classOf[Router], classOf[IPv4Addr],
                               classOf[ArpEntry], MidonetBackend.ArpTable,
                               classOf[ArpStateTable])
    inMemoryZoom.registerTable(classOf[Topology.Port], classOf[MAC],
                               classOf[IPv4Addr], MidonetBackend.PeeringTable,
                               classOf[MacIp4StateTable])
    inMemoryZoom.registerTable(classOf[Fip64Entry],
                               classOf[AnyRef], MidonetBackend.Fip64Table,
                               classOf[Fip64StateTable])
    val connectionState =
        BehaviorSubject.create[ConnectionState](ConnectionState.CONNECTED)

    override def store: Storage = inMemoryZoom
    override def stateStore: StateStorage = inMemoryZoom
    override def stateTableStore: StateTableStorage = inMemoryZoom
    override def stateTableClient: StateTableClient = null
    override def curator: CuratorFramework = curatorParam
    override def failFastCurator: CuratorFramework = curatorParam
    override def reactor: Reactor = null
    override def failFastConnectionState =
        connectionState.asObservable()

    override def doStart(): Unit = {
        MidonetBackend.setupBindings(store, stateStore)
        notifyStarted()
    }

    override def doStop(): Unit = notifyStopped()
}

object MidonetBackendTestModule {
    def apply() = new MidonetBackendTestModule
}

/** Provides all dependencies for the new backend, using a FAKE zookeeper. */
class MidonetBackendTestModule(cfg: Config = MidoTestConfigurator.forAgents())
    extends MidonetBackendModule(new MidonetBackendConfig(cfg), None,
                                 new MetricRegistry) {

    override protected def bindCuratorFramework() = {
        val curator = mock(classOf[CuratorFramework])
        bind(classOf[CuratorFramework]).toInstance(curator)
        curator
    }

    override protected def failFastCuratorFramework() = {
        mock(classOf[CuratorFramework])
    }

    override protected def backend(curatorFramework: CuratorFramework,
                                   failFastCurator: CuratorFramework,
                                   discovery: MidonetDiscovery) = {
        new MidonetTestBackend
    }

    override protected def bindDiscovery(curator: CuratorFramework) = {
        val discovery = new FakeDiscovery
        bind(classOf[MidonetDiscovery]).toInstance(discovery)
        discovery
    }
}
