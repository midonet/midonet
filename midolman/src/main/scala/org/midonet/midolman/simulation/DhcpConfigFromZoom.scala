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

import java.util
import java.util.UUID

import scala.collection.JavaConversions._
import scala.collection.JavaConverters._
import scala.collection.mutable
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
import org.midonet.packets.{IPv4Subnet, MAC}

/** This class enables access to DHCP resources via the new storage stack.
  *
  * TODO: integrate with the VTA so that Subnets are cached.
  */
class DhcpConfigFromZoom(vt: VirtualTopology)
                        (implicit val as: ActorSystem, val ec: ExecutionContext)
    extends DhcpConfig {

    override def bridgeDhcpSubnets(deviceId: UUID): util.List[Subnet] = {
        val f = Future.sequence (
            tryGet[Bridge](deviceId).subnetIds.map {
                vt.store.get(classOf[Topology.Dhcp], _) map toSubnet
            }
        )
        Await.result(f, 3.seconds).asJava
    }

    override def dhcpHost(deviceId: UUID, subnetAddr: IPv4Subnet,
                          srcMac: String): Option[Host] = {
        val f = Future.sequence (
            tryGet[Bridge](deviceId).subnetIds.map {
                vt.store.get(classOf[Topology.Dhcp], _)
            }
        )

        Await.result(f, 3.seconds) filter {
            _.getSubnetAddress == subnetAddr
        } map {             // also with the host we're looking for
            _.getHostsList filter { _.getMac == srcMac }
        } match {           // and if found a host, build the sim object
            case protoHosts: mutable.Buffer[_] if protoHosts.nonEmpty =>
                Some(toHost(protoHosts.get(0).asInstanceOf[Topology.Dhcp.Host]))
            case _ =>
                None
        }
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
        val s = new Subnet
        s.setId(dhcp.getId.asJava.toString)
        if (dhcp.hasDefaultGateway)
            s.setDefaultGateway(dhcp.getDefaultGateway.asIPv4Address)
        if (dhcp.hasEnabled)
            s.setEnabled(dhcp.getEnabled)
        if (dhcp.hasInterfaceMtu)
            s.setInterfaceMTU(dhcp.getInterfaceMtu.toShort)
        if (dhcp.hasServerAddress)
            s.setServerAddr(dhcp.getServerAddress.asIPv4Address)
        if (dhcp.hasSubnetAddress)
            s.setSubnetAddr(fromV4Proto(dhcp.getSubnetAddress))
        s.setOpt121Routes(dhcp.getOpt121RoutesList.map(opt121 => {
            val o = new Opt121
            if (opt121.hasGateway)
                o.setGateway(opt121.getGateway.asIPv4Address)
            if (opt121.hasDstSubnet)
                o.setRtDstSubnet(fromV4Proto(opt121.getDstSubnet))
            o
        }))
        s.setDnsServerAddrs(dhcp.getDnsServerAddressList.map(toIPv4Addr))
        s
    }
}


