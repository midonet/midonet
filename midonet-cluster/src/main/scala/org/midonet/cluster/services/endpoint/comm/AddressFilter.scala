/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.services.endpoint.comm

import scala.collection.IterableLike
import scala.collection.generic.CanBuildFrom
import scala.util.control.NonFatal

import com.google.common.net.HostAndPort

import org.midonet.packets.{IPv4Addr, IPv4Subnet}

/**
  * An address filter trait for filtering a collection of addresses according
  * to some rule.
  */
trait AddressFilter {
    /**
      * Filter addresses according to some rule.
      * @param addresses Addresses to filter.
      * @return Filtered addresses.
      */
    def filter[T <: Iterable[HostAndPort]]
        (addresses: T with IterableLike[HostAndPort, T])
        (implicit cbf: CanBuildFrom[T, HostAndPort, T]): T
}

/**
  * An address filter that doesn't actually filter anything.
  */
object NoFilter extends AddressFilter {
    /**
      * A pass-through filter.
      *
      * @param addresses Addresses to filter.
      * @return Filtered addresses which are equal to the provided ones.
      */
    override def filter[T <: Iterable[HostAndPort]]
        (addresses: T with IterableLike[HostAndPort, T])
        (implicit cbf: CanBuildFrom[T, HostAndPort, T]): T = addresses
}

/**
  * An address filter that filters addresses looking for a predetermined address
  * @param address The address we are interested in.
  */
class FixedFilter(address: HostAndPort) extends AddressFilter {
    /**
      * Filter addresses where the host part matches the provided address.
      *
      * @param addresses Addresses to filter.
      * @return Filtered addresses.
      */
    override def filter[T <: Iterable[HostAndPort]]
        (addresses: T with IterableLike[HostAndPort, T])
        (implicit cbf: CanBuildFrom[T, HostAndPort, T]): T =
        addresses.filter(hp => address == hp)
}

/**
  * An address filter that filters addresses based on their subnet.
  * @param subnet Subnet of the addresses we are interested in.
  */
class SubnetFilter(subnet: IPv4Subnet) extends AddressFilter {
    /**
      * Filter addresses according to some rule.
      *
      * @param addresses Addresses to filter.
      * @return Filtered addresses.
      */
    override def filter[T <: Iterable[HostAndPort]]
        (addresses: T with IterableLike[HostAndPort, T])
        (implicit cbf: CanBuildFrom[T, HostAndPort, T]): T =
        addresses.filter { hp =>
            try {
                val ip = IPv4Addr.fromString(hp.getHostText)
                subnet.containsAddress(ip)
            } catch {
                case NonFatal(_) =>
                    false
            }
        }
}

object SubnetFilter {
    def apply(subnet: String): SubnetFilter =
        new SubnetFilter(IPv4Subnet.fromCidr(subnet))
}

