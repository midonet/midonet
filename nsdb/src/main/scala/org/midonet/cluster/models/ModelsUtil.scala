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

package org.midonet.cluster.models

import org.midonet.cluster.models.Neutron.AgentMembership
import org.midonet.cluster.models.Neutron.FloatingIp
import org.midonet.cluster.models.Neutron.NeutronConfig
import org.midonet.cluster.models.Neutron.NeutronHealthMonitor
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerPool
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerPoolHealthMonitor
import org.midonet.cluster.models.Neutron.NeutronLoadBalancerPoolMember
import org.midonet.cluster.models.Neutron.NeutronNetwork
import org.midonet.cluster.models.Neutron.NeutronPort
import org.midonet.cluster.models.Neutron.NeutronRoute
import org.midonet.cluster.models.Neutron.NeutronRouter
import org.midonet.cluster.models.Neutron.NeutronRouterInterface
import org.midonet.cluster.models.Neutron.NeutronSubnet
import org.midonet.cluster.models.Neutron.PortBinding
import org.midonet.cluster.models.Neutron.SecurityGroup
import org.midonet.cluster.models.Neutron.SecurityGroupRule
import org.midonet.cluster.models.Neutron.NeutronVIP
import org.midonet.cluster.models.Topology.Chain
import org.midonet.cluster.models.Topology.Dhcp
import org.midonet.cluster.models.Topology.Host
import org.midonet.cluster.models.Topology.IpAddrGroup
import org.midonet.cluster.models.Topology.Network
import org.midonet.cluster.models.Topology.Port
import org.midonet.cluster.models.Topology.PortGroup
import org.midonet.cluster.models.Topology.Route
import org.midonet.cluster.models.Topology.Router
import org.midonet.cluster.models.Topology.Rule
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.models.Topology.Vtep
import org.midonet.cluster.models.Topology.VtepBinding
import org.midonet.cluster.util.ProtobufUtil.protoFromTxt

/**
 * Utility functions to generate Midonet / Neutron model objects from text.
 */
object ModelsUtil {
     def nAgentMembershipFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, AgentMembership.newBuilder)
        .asInstanceOf[AgentMembership]

     def nConfigFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronConfig.newBuilder)
        .asInstanceOf[NeutronConfig]

     def nNetworkFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronNetwork.newBuilder)
        .asInstanceOf[NeutronNetwork]

     def nSubnetFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronSubnet.newBuilder)
        .asInstanceOf[NeutronSubnet]

     def nRouteFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronRoute.newBuilder)
        .asInstanceOf[NeutronRoute]

     def nPortFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronPort.newBuilder).asInstanceOf[NeutronPort]

     def nRouterFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronRouter.newBuilder)
        .asInstanceOf[NeutronRouter]

     def nRouterInterfaceFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronRouterInterface.newBuilder)
        .asInstanceOf[NeutronRouterInterface]

     def nFloatingIpFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, FloatingIp.newBuilder)
        .asInstanceOf[FloatingIp]

     def nVIPFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronVIP.newBuilder)
        .asInstanceOf[NeutronVIP]

     def nSecurityGroupFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, SecurityGroup.newBuilder)
        .asInstanceOf[SecurityGroup]

     def nSecurityGroupRuleFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, SecurityGroupRule.newBuilder)
        .asInstanceOf[SecurityGroupRule]

     def nLoadBalancerPoolFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronLoadBalancerPool.newBuilder)
        .asInstanceOf[NeutronLoadBalancerPool]

     def nLoadBalancerPoolMemberFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronLoadBalancerPoolMember.newBuilder)
        .asInstanceOf[NeutronLoadBalancerPoolMember]

     def nLoadBalancerPoolHealthMonitorFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronLoadBalancerPoolHealthMonitor.newBuilder)
        .asInstanceOf[NeutronLoadBalancerPoolHealthMonitor]

     def nHealthMonitorFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronHealthMonitor.newBuilder)
        .asInstanceOf[NeutronHealthMonitor]

     def nPortBindingFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, PortBinding.newBuilder)
        .asInstanceOf[PortBinding]

     def mNetworkFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Network.newBuilder).asInstanceOf[Network]

     def mDhcpFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Dhcp.newBuilder).asInstanceOf[Dhcp]

     def mRouterFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Router.newBuilder).asInstanceOf[Router]

     def mPortFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Port.newBuilder).asInstanceOf[Port]

     def mPortGroupFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, PortGroup.newBuilder).asInstanceOf[PortGroup]

     def mIpAddrGroupFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, IpAddrGroup.newBuilder).asInstanceOf[IpAddrGroup]

     def mChainFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Chain.newBuilder).asInstanceOf[Chain]

     def mRouteFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Route.newBuilder).asInstanceOf[Route]

     def mRuleFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Rule.newBuilder).asInstanceOf[Rule]

     def mTunnelZoneFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, TunnelZone.newBuilder).asInstanceOf[TunnelZone]

     def mVtepFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Vtep.newBuilder).asInstanceOf[Vtep]

     def mVtepBindingFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, VtepBinding.newBuilder).asInstanceOf[VtepBinding]

     def mHostFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Host.newBuilder).asInstanceOf[Host]
}
