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

import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology.{Chain, Dhcp, HealthMonitor, Host, IPAddrGroup, LoadBalancer, Network, Pool, PoolMember, Port, PortGroup, Route, Router, Rule, TunnelZone, Vip, Vtep}
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
        protoFromTxt(protoTxt, NeutronPort.newBuilder)
        .asInstanceOf[NeutronPort]

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

     def nHealthMonitorFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, NeutronHealthMonitor.newBuilder)
        .asInstanceOf[NeutronHealthMonitor]

     def nPortBindingFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, PortBinding.newBuilder)
        .asInstanceOf[PortBinding]

     def nVpnServiceFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, VpnService.newBuilder)
        .asInstanceOf[VpnService]

     def mNetworkFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Network.newBuilder).asInstanceOf[Network]

     def mDhcpFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Dhcp.newBuilder).asInstanceOf[Dhcp]

     def mRouterFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Router.newBuilder).asInstanceOf[Router]

     def mPoolFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Pool.newBuilder).asInstanceOf[Pool]

     def mPoolMemberFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, PoolMember.newBuilder).asInstanceOf[PoolMember]

     def mPortFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Port.newBuilder).asInstanceOf[Port]

     def mPortGroupFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, PortGroup.newBuilder).asInstanceOf[PortGroup]

     def mIPAddrGroupFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, IPAddrGroup.newBuilder).asInstanceOf[IPAddrGroup]

     def mChainFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Chain.newBuilder).asInstanceOf[Chain]

     def mHealthMonitorFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, HealthMonitor.newBuilder)
        .asInstanceOf[HealthMonitor]

     def mLoadBalancerFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, LoadBalancer.newBuilder)
        .asInstanceOf[LoadBalancer]

     def mRouteFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Route.newBuilder).asInstanceOf[Route]

     def mRuleFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Rule.newBuilder).asInstanceOf[Rule]

     def mTunnelZoneFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, TunnelZone.newBuilder).asInstanceOf[TunnelZone]

     def mVIPFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Vip.newBuilder).asInstanceOf[Vip]

     def mVtepFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Vtep.newBuilder).asInstanceOf[Vtep]

     def mHostFromTxt(protoTxt: String) =
        protoFromTxt(protoTxt, Host.newBuilder).asInstanceOf[Host]
}
