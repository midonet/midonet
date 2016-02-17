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
package org.midonet.cluster.services

import com.google.common.util.concurrent.AbstractService
import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework

import org.midonet.cluster.data.storage.FieldBinding.DeleteAction._
import org.midonet.cluster.data.storage.{OwnershipType, Storage, StorageWithOwnership, ZookeeperObjectMapper}
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.storage.MidonetBackendConfig

/** The trait that models the new Midonet Backend, managing all relevant
  * connections and APIs to interact with backend storages. */
abstract class MidonetBackend extends AbstractService {
    /** Indicates whether the new backend stack is active */
    def isEnabled = false
    /** Provides access to the Topology storage API */
    def store: Storage
    def ownershipStore: StorageWithOwnership

    /** Configures a brand new ZOOM instance with all the classes and bindings
      * supported by MidoNet. */
    final def setupBindings(): Unit = {
        List(classOf[AgentMembership],
             classOf[Chain],
             classOf[Dhcp],
             classOf[FloatingIp],
             classOf[IpAddrGroup],
             classOf[LoadBalancer],
             classOf[Network],
             classOf[NeutronConfig],
             classOf[NeutronHealthMonitor],
             classOf[NeutronLoadBalancerPool],
             classOf[NeutronLoadBalancerPoolHealthMonitor],
             classOf[NeutronLoadBalancerPoolMember],
             classOf[NeutronNetwork],
             classOf[NeutronPort],
             classOf[NeutronRouter],
             classOf[NeutronSubnet],
             classOf[NeutronVIP],
             classOf[Port],
             classOf[PortBinding],
             classOf[PortGroup],
             classOf[Route],
             classOf[Router],
             classOf[Rule],
             classOf[TunnelZone],
             classOf[SecurityGroup],
             classOf[VIP],
             classOf[Vtep],
             classOf[VtepBinding]
        ).foreach(store.registerClass)

        ownershipStore.registerClass(classOf[Host], OwnershipType.Exclusive)

        store.declareBinding(classOf[Network], "port_ids", ERROR,
                             classOf[Port], "network_id", CLEAR)
        store.declareBinding(classOf[Network], "dhcp_ids", ERROR,
                             classOf[Dhcp], "network_id", CLEAR)

        store.declareBinding(classOf[Port], "peer_id", CLEAR,
                             classOf[Port], "peer_id", CLEAR)
        store.declareBinding(classOf[Port], "route_ids", CASCADE,
                             classOf[Route], "next_hop_port_id", CLEAR)

        store.declareBinding(classOf[Router], "port_ids", ERROR,
                             classOf[Port], "router_id", CLEAR)
        store.declareBinding(classOf[Router], "route_ids", CASCADE,
                             classOf[Route], "router_id", CLEAR)

        store.declareBinding(classOf[Host], "tunnel_zone_ids", CLEAR,
                             classOf[TunnelZone], "host_ids", CLEAR)

        store.build()
    }

}

/** Class responsible for providing services to access to the new Storage
  * services. */
class MidonetBackendService @Inject() (cfg: MidonetBackendConfig,
                                       curator: CuratorFramework)
    extends MidonetBackend {

    private val zoom =
        new ZookeeperObjectMapper(cfg.rootKey + "/zoom", curator)

    override def store: Storage = zoom
    override def ownershipStore: StorageWithOwnership = zoom
    override def isEnabled = cfg.useNewStack

    protected override def doStart(): Unit = {
        try {
            if (cfg.curatorEnabled || cfg.useNewStack) {
                curator.start()
            }
            if (cfg.useNewStack) {
                setupBindings()
            }
            notifyStarted()
        } catch {
            case e: Exception => this.notifyFailed(e)
        }
    }

    protected def doStop(): Unit = {
        curator.close()
        notifyStopped()
    }
}
