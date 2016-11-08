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

import java.util.concurrent.ExecutorService
import java.util.{Comparator, NoSuchElementException, TreeMap}

import rx.Scheduler

import org.midonet.cluster.data.storage.{StateStorage, Storage}
import org.midonet.cluster.models.Commons.IPVersion
import org.midonet.cluster.models.Topology.Port
import org.midonet.packets.{IPv4Addr, IPv4Subnet}
import org.midonet.util.logging.Logger

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

    private val LocalSubnetBeginAddress = 0xA9FE0000 //169.254.0.0
    private val LocalSubnetEndAddress = 0xA9FEFFFC // 169.254.255.252
    private val LocalSubnetPrefix = 30
    private val LocalSubnetMask = 0xFFFFFFFF << (32 - LocalSubnetPrefix)
    private val LocalSubnetSize = 0xFFFFFFFF >>> LocalSubnetPrefix
    private val RouterAddressOffset = 1
    private val ContainerAddressOffset = 2

    @throws[NoSuchElementException]
    def findLocalSubnet(ports: Seq[Port]): IPv4Subnet = {
        // Finds an available 169.254.x.y/30 (0xA9FE.x.y/30) subnet within the
        // 169.254.0.0/16 range excluding the subnets of the router ports that
        // already have been allocated within the same range. The method works
        // by storing the allocated subnets in an ordered map, where the key is
        // the beginning of an allocated range, and the value is end, and then
        // iterating through the allocated ranges to find an available /30
        // range with a 0xA9FE prefix.
        val subnets = new TreeMap[Int, Int](
            new Comparator[Int]() {
                @Override
                def compare(a: Int, b: Int): Int = {
                    Integer.compareUnsigned(a, b)
                }
            })
        for (port <- ports if port.getPortSubnet.getVersion == IPVersion.V4) {
            val portSubnet = new IPv4Subnet(port.getPortSubnet.getAddress,
                                            port.getPortSubnet.getPrefixLength)
            val subnetBegin = portSubnet.toNetworkAddress.toInt
            val subnetEnd = portSubnet.toBroadcastAddress.toInt
            subnets.put(subnetBegin, subnetEnd)
        }
        // We begin the iteration from the first eligible address: 169.254.0.0
        // and the last eligible subnet address is: 169.254.255.252.
        var selection = LocalSubnetBeginAddress
        while (Integer.compareUnsigned(selection, LocalSubnetEndAddress) < 0) {
            // Get the first allocated range at an address equal to or greater
            // than the selected subnet.
            val subnet = subnets.ceilingEntry(selection)
            // If such an entry is not allocated or its beginning address is
            // greater than the end of the /30 we selected, return the current
            // selection.
            if ((subnet eq null) ||
                Integer.compareUnsigned(subnet.getKey, selection + LocalSubnetSize) > 0)
                return new IPv4Subnet(selection, LocalSubnetPrefix)
            // Else, select the beginning of next /30 greater than the end of
            // the current entry range.
            selection = (subnet.getValue & LocalSubnetMask) + LocalSubnetSize + 1
        }
        throw new NoSuchElementException("There is no free /30 local subnet in " +
                                         "the router for the 169.254.0.0/16 range.")
    }

    /**
      * Get the associated ip address of the router port for a given container
      * subnet.
      */
    def routerPortAddress(subnet: IPv4Subnet): IPv4Addr = {
        IPv4Addr(subnet.getIntAddress + RouterAddressOffset)
    }

    /**
      * Get the associated up address of the container port for a given
      * container subnet. This is the ip inside the container namespace.
      */
    def containerPortAddress(subnet: IPv4Subnet): IPv4Addr = {
        IPv4Addr(subnet.getIntAddress + ContainerAddressOffset)
    }

}
