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
import org.slf4j.LoggerFactory

import org.midonet.cluster.config.ZookeeperConfig
import org.midonet.cluster.data.storage.FieldBinding.DeleteAction.{CLEAR, ERROR}
import org.midonet.cluster.data.storage.{Storage, ZookeeperObjectMapper}
import org.midonet.cluster.models.C3PO.C3POState
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._

/** Class responsible for providing services to access to the new Storage
  * services
  * 
  * TODO: remove ZookeeperConfig in favour of MidonetBackendConfig
  */
class MidonetBackendService @Inject() (zkCfg: ZookeeperConfig,
                                       curator: CuratorFramework)
    extends AbstractService {

    private val log = LoggerFactory.getLogger(classOf[MidonetBackendService])

    val storage: Storage =
        new ZookeeperObjectMapper(zkCfg.getZkRootPath + "/zoom", curator)

    protected override def doStart(): Unit = {
        try {
            if (zkCfg.getCuratorEnabled) {
                log.debug("Curator service is enabled")
                curator.start()
            }
            if (zkCfg.getClusterStorageEnabled) {
                initStorage()
            }
            notifyStarted()
        }
        catch {
            case e: Exception => this.notifyFailed(e)
        }
    }

    protected def doStop(): Unit = {
        curator.close()
        notifyStopped()
    }

    private def initStorage(): Unit = {
        List(classOf[C3POState],
             classOf[Chain],
             classOf[Dhcp],
             classOf[DhcpV6],
             classOf[FloatingIp],
             classOf[Host],
             classOf[IpAddrGroup],
             classOf[Network],
             classOf[NeutronHealthMonitor],
             classOf[NeutronLoadBalancerPool],
             classOf[NeutronLoadBalancerPoolHealthMonitor],
             classOf[NeutronLoadBalancerPoolMember],
             classOf[NeutronNetwork],
             classOf[NeutronPort],
             classOf[NeutronRouter],
             classOf[NeutronSubnet],
             classOf[Port],
             classOf[PortGroup],
             classOf[Route],
             classOf[Router],
             classOf[Rule],
             classOf[TunnelZone],
             classOf[SecurityGroup],
             classOf[VIP],
             classOf[Vtep],
             classOf[VtepBinding]
        ).foreach(storage.registerClass)
        storage.declareBinding(classOf[Network], "port_ids", ERROR,
                               classOf[Port], "network_id", CLEAR)
        storage.declareBinding(classOf[Router], "port_ids", ERROR,
                               classOf[Port], "router_id", CLEAR)
    }
}
