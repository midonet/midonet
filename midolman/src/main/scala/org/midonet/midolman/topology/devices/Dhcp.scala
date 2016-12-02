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

package org.midonet.midolman.topology.devices

import java.util.{UUID, List => JList}

import org.midonet.cluster.data.{Zoom, ZoomClass, ZoomField, ZoomObject}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil, MACUtil}
import org.midonet.midolman.topology.devices.Dhcp.Opt121Route
import org.midonet.packets.{IPv4Addr, IPv4Subnet, MAC}

object Dhcp {

    @ZoomClass(clazz = classOf[Topology.Dhcp.Opt121Route])
    case class Opt121Route @Zoom()(@ZoomField(name = "dst_subnet",
                                              converter = classOf[IPSubnetUtil.Converter])
                                   destinationSubnet: IPv4Subnet,
                                   @ZoomField(name = "gateway",
                                              converter = classOf[IPAddressUtil.Converter])
                                   gateway: IPv4Addr) extends ZoomObject {

        override def toString = {
            s"Opt121Route [destinationSubnet=$destinationSubnet " +
            s"gateway=$gateway]"
        }
    }

    @ZoomClass(clazz = classOf[Topology.Dhcp.Host.ExtraDhcpOpt])
    case class ExtraDhcpOption @Zoom()(@ZoomField(name = "name")
                                       name: String,
                                       @ZoomField(name = "value")
                                       value: String) extends ZoomObject {

        override def toString = {
            s"ExtraDhcpOption [name=$name value=$value]"
        }
    }

    @ZoomClass(clazz = classOf[Topology.Dhcp.Host])
    case class Host @Zoom()(@ZoomField(name = "mac",
                                       converter = classOf[MACUtil.Converter])
                            mac: MAC,
                            @ZoomField(name = "ip_address",
                                       converter = classOf[IPAddressUtil.Converter])
                            address: IPv4Addr,
                            @ZoomField(name = "name")
                            name: String,
                            @ZoomField(name = "extra_dhcp_opts")
                            extraDhcpOptions: JList[ExtraDhcpOption])
        extends ZoomObject {

        override def toString = {
            s"Host [mac=$mac address=$address name=$name " +
            s"extraDhcpOptions=$extraDhcpOptions]"
        }
    }

}

@ZoomClass(clazz = classOf[Topology.Dhcp])
case class Dhcp @Zoom()(@ZoomField(name = "id")
                        id: UUID,
                        @ZoomField(name = "network_id")
                        networkId: UUID,
                        @ZoomField(name = "subnet_address",
                                   converter = classOf[IPSubnetUtil.Converter])
                        subnetAddress: IPv4Subnet,
                        @ZoomField(name = "server_address",
                                   converter = classOf[IPAddressUtil.Converter])
                        serverAddress: IPv4Addr,
                        @ZoomField(name = "dns_server_address",
                                   converter = classOf[IPAddressUtil.Converter])
                        dnsServerAddress: JList[IPv4Addr],
                        @ZoomField(name = "default_gateway",
                                   converter = classOf[IPAddressUtil.Converter])
                        defautGateway: IPv4Addr,
                        @ZoomField(name = "interface_mtu")
                        interfaceMtu: Short,
                        @ZoomField(name = "opt121_routes")
                        opt121Routes: JList[Opt121Route],
                        @ZoomField(name = "hosts")
                        hosts: JList[Dhcp.Host],
                        @ZoomField(name = "enabled")
                        enabled: Boolean) extends ZoomObject {

    override def toString = {
        s"Dhcp [id=$id networkId=$networkId subnetAddress=$subnetAddress " +
        s"serverAddress=$serverAddress dnsServerAddress=$dnsServerAddress " +
        s"defaultGateway=$defautGateway interfaceMtu=$interfaceMtu " +
        s"opt121Routes=$opt121Routes hosts=$hosts enabled=$enabled]"
    }
}
