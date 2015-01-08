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
package org.midonet.cluster.storage

import com.google.inject.{Inject, Provider}
import org.apache.curator.framework.CuratorFramework

import org.midonet.cluster.data.storage.{Storage, ZookeeperObjectMapper}
import org.midonet.cluster.models.C3PO.C3POState
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._

/** This class build Zoom instances, ready to operate on all the models that are
  * supported by MidoNet. */
class ZoomProvider @Inject()(val curator: CuratorFramework)
    extends Provider[Storage] {

    override def get: Storage = {
        val storage = new ZookeeperObjectMapper("", curator)
        List(classOf[C3POState],
             classOf[Chain],
             classOf[FloatingIp],
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
             classOf[Router],
             classOf[Rule],
             classOf[SecurityGroup],
             classOf[VIP]
        ).foreach(storage.registerClass)
        storage.build()
        storage
    }
}
