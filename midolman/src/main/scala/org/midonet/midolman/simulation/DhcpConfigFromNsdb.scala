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

package org.midonet.midolman.simulation

import java.util.UUID

import scala.collection.JavaConversions._
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

import akka.actor.ActorSystem

import com.google.common.annotations.VisibleForTesting

import org.midonet.cluster.data.dhcp.{Host, Opt121, Subnet}
import org.midonet.cluster.models.Topology
import org.midonet.cluster.util.IPAddressUtil._
import org.midonet.cluster.util.IPSubnetUtil._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.topology.VirtualTopology._
import org.midonet.packets.{IPv4Addr, MAC}

/** This class enables access to DHCP resources.
  *
  * TODO: integrate with the VTA so that Subnets are cached.
  */
class DhcpConfigFromNsdb(vt: VirtualTopology)
                        (implicit val as: ActorSystem, val ec: ExecutionContext)
    extends DhcpConfig {

    private final val timeout = 3 seconds

    override def bridgeDhcpSubnets(deviceId: UUID): Seq[Subnet] = {
        val f = Future.sequence (
            tryGet[Bridge](deviceId).subnetIds.map {
                vt.store.get(classOf[Topology.Dhcp], _) map toSubnet
            }
        ) recover { case _ => Seq.empty[Subnet] }
        Await.result(f, timeout)
    }

    override def dhcpHost(deviceId: UUID, subnet: Subnet, srcMac: String)
    : Option[Host] = {
        val f = vt.store.get(classOf[Topology.Dhcp], subnet.getId) map {
            _.getHostsList.find(_.getMac == srcMac).map(toHost)
        } recover { case _ => None }
        Await.result(f, timeout)
    }

    @VisibleForTesting
    private[simulation] def toHost(protoHost: Topology.Dhcp.Host): Host = {
        val h = new Host()
        h.setId(h.getId)
        if (protoHost.hasIpAddress)
            h.setIp(protoHost.getIpAddress.asIPv4Address)
        if (protoHost.hasMac)
            h.setMAC(MAC.fromString(protoHost.getMac))
        if (protoHost.hasName)
            h.setName(protoHost.getName)
        // h.setExtraDhcpOpts() unused yet?
        h
    }

    /** Converts a DHCP Proto object into a legacy cluster Subnet, as used by
      * the Agent.
      */
    @VisibleForTesting
    private[simulation] def toSubnet(dhcp: Topology.Dhcp): Subnet = {
        val subnet = new Subnet
        // Mandatory fields.
        subnet.setId(dhcp.getId.asJava.toString)
        subnet.setSubnetAddr(fromV4Proto(dhcp.getSubnetAddress))

        // Optional fields
        if (dhcp.hasDefaultGateway)
            subnet.setDefaultGateway(dhcp.getDefaultGateway.asIPv4Address)
        if (dhcp.hasEnabled)
            subnet.setEnabled(dhcp.getEnabled)
        if (dhcp.hasInterfaceMtu)
            subnet.setInterfaceMTU(dhcp.getInterfaceMtu.toShort)
        if (dhcp.hasServerAddress) {
            subnet.setServerAddr(dhcp.getServerAddress.asIPv4Address)
        } else if (dhcp.hasDefaultGateway) {
            // If the server address is not set, use the default gateway.
            subnet.setServerAddr(dhcp.getDefaultGateway.asIPv4Address)
        } else {
            // Or else, the network broadcast address minus 1.
            subnet.setServerAddr(
                IPv4Addr(subnet.getSubnetAddr.toBroadcastAddress.toInt - 1))
        }
        subnet.setOpt121Routes(dhcp.getOpt121RoutesList.map(opt121 => {
            val o = new Opt121
            if (opt121.hasGateway)
                o.setGateway(opt121.getGateway.asIPv4Address)
            if (opt121.hasDstSubnet)
                o.setRtDstSubnet(fromV4Proto(opt121.getDstSubnet))
            o
        }))
        subnet.setDnsServerAddrs(dhcp.getDnsServerAddressList.map(toIPv4Addr))
        subnet
    }
}


