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

package org.midonet

import java.util.NoSuchElementException
import java.util.concurrent.ExecutorService

import scala.collection.JavaConversions._

import com.typesafe.scalalogging.Logger

import rx.Scheduler

import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.Commons.{IPAddress, IPSubnet}
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.packets.{IPv4Addr, IPv4Subnet}

package object containers {

    /**
      * Wraps context variables such as the backend storage, executor and
      * logger.
      */
    case class Context(store: Storage,
                       stateStore: StateStorage,
                       executor: ExecutorService,
                       scheduler: Scheduler,
                       log: Logger)

    private val LinkLocalSubnetRange = "169.254.0.0/16"
    private val SubnetPrefix = 30
    private val RouterAddressOffset = 1
    private val ContainerAddressOffset = 2

    @throws[NoSuchElementException]
    def findUnusedSubnet(ports: Seq[Port]): IPSubnet = {
        def valid(containerSubnet: IPv4Subnet): Boolean = {
            ports forall { port =>
                val portSubnet = new IPv4Subnet(port.getPortSubnet.getAddress,
                                                port.getPortSubnet.getPrefixLength)
                // Check there's no overlapping between current subnets in the
                // router and the subnet for the container
                !portSubnet.containsAddress(containerSubnet.toNetworkAddress) &&
                !portSubnet.containsAddress(containerSubnet.toBroadcastAddress)
            }
        }

        IPSubnetUtil.toProto(
            IPv4Subnet.iterator(IPv4Subnet.fromCidr(LinkLocalSubnetRange),
                                SubnetPrefix)
                .find(valid).get.toString)
    }

    /**
      * Get the associated ip address of the router port for a given container
      * subnet.
      */
    def routerPortAddress(subnet: IPSubnet): IPAddress = {
        val s = new IPv4Subnet(subnet.getAddress, SubnetPrefix)
        IPAddressUtil.toProto(IPv4Addr(s.getIntAddress + RouterAddressOffset))
    }

    /**
      * Get the associated up address of the container port for a given
      * container subnet. This is the ip inside the container namespace.
      */
    def containerPortAddress(subnet: IPSubnet): IPAddress = {
        val s = new IPv4Subnet(subnet.getAddress, SubnetPrefix)
        IPAddressUtil.toProto(IPv4Addr(s.getIntAddress + ContainerAddressOffset))
    }

}
