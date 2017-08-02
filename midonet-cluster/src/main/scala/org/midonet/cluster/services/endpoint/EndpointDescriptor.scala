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

package org.midonet.cluster.services.endpoint

import scala.util.control.NonFatal
import scala.util.{Failure, Try}

import com.google.common.net.HostAndPort

import org.slf4j.LoggerFactory

import org.midonet.cluster.services.endpoint.comm.{AddressFilter, FixedFilter, SubnetFilter}
import org.midonet.packets.IPv4Subnet

trait EndpointDescriptor {
    def createFilter(): AddressFilter
}

case class FixedEndpointDescriptor(address: HostAndPort)
    extends EndpointDescriptor {

    override def createFilter(): AddressFilter = new FixedFilter(address)
}

case class SubnetEndpointDescriptor(subnet: IPv4Subnet)
    extends EndpointDescriptor {

    override def createFilter(): AddressFilter = new SubnetFilter(subnet)
}

object SubnetEndpointDescriptor {
    def apply(subnet: String): SubnetEndpointDescriptor =
        SubnetEndpointDescriptor(IPv4Subnet.fromCidr(subnet))
}

object EndpointDescriptor {
    private val log = LoggerFactory.getLogger(classOf[EndpointDescriptor])

    def apply(description: Option[String]): Option[EndpointDescriptor] = {
        description.flatMap { s =>
            if (s.isEmpty)
                None
            else
                trySubnetDescriptor(s).recoverWith {
                    case NonFatal(_) =>
                        tryAddressDescriptor(s).recoverWith {
                            case NonFatal(_) =>
                                log.error("No valid endpoint descriptor " +
                                              "could be formed from {}", s)
                                Failure(null)
                        }
                }.toOption
        }
    }

    private def trySubnetDescriptor(cidr: String): Try[EndpointDescriptor] =
        Try(SubnetEndpointDescriptor(cidr))

    private def tryAddressDescriptor(address: String): Try[EndpointDescriptor] =
        Try {
            val hp = HostAndPort.fromString(address)

            if (hp.hasPort && hp.getHostText.nonEmpty) {
                FixedEndpointDescriptor(hp)
            } else {
                throw new IllegalArgumentException("Couldn't find port " +
                                                           "or hostname")
            }
        }
}
