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

package org.midonet.migrator

import java.util.UUID

import scala.collection.JavaConversions._

import com.google.inject.Inject

import org.midonet.cluster.DataClient
import org.midonet.cluster.rest_api.models._
import org.midonet.migrator.converters._
import org.midonet.migrator.models.{Bgp, AdRoute}
import org.midonet.packets.{IPv6Subnet, IPv4Addr, IPv4Subnet}

class LegacyImporter @Inject() (dataClient: DataClient) {

    def listAdRoutes(bgpId: UUID): Seq[AdRoute] =
        dataClient.adRoutesFindByBgp(bgpId).map(AdRouteDataConverter.fromData)

    def listBgps(routerId: UUID): Seq[Bgp] =
        dataClient.portsFindByRouter(routerId).toSeq.flatMap {
            case routerPort: org.midonet.cluster.data.ports.RouterPort =>
                routerPort.getBgps.map(BgpDataConverter.fromData).toSeq
            case _ => Seq.empty[Bgp]
        }

    def listBridges: Seq[Bridge] =
        dataClient.bridgesGetAll.map(BridgeDataConverter.fromData)

    def listChains: Seq[Chain] =
        dataClient.chainsGetAll.map(ChainDataConverter.fromData)

    def listDhcpSubnets(bridgeId: UUID): Seq[DhcpSubnet] =
        for (s <- dataClient.dhcpSubnetsGetByBridge(bridgeId))
            yield DhcpSubnetDataConverter.fromData(s, bridgeId)

    def listDhcpSubnet6s(bridgeId: UUID): Seq[DhcpSubnet6] =
        for (s <- dataClient.dhcpSubnet6sGetByBridge(bridgeId))
            yield DhcpSubnetDataConverter.fromDataV6(s, bridgeId)

    def listDhcpHosts(bridgeId: UUID, subnet: IPv4Subnet): Seq[DhcpHost] =
        dataClient.dhcpHostsGetBySubnet(bridgeId, subnet)
            .map(DhcpHostDataConverter.fromData)

    def listDhcpV6Hosts(bridgeId: UUID, subnet: IPv6Subnet): Seq[DhcpV6Host] =
        dataClient.dhcpV6HostsGetByPrefix(bridgeId, subnet)
            .map(DhcpHostDataConverter.fromData)

    def listHealthMonitors: Seq[HealthMonitor] =
        dataClient.healthMonitorsGetAll.map(HealthMonitorDataConverter.fromData)

    def listHosts: Seq[Host] =
        dataClient.hostsGetAll.map(HostDataConverter.fromData)

    def listIpAddrGroups: Seq[IpAddrGroup] =
        for (ipg <- dataClient.ipAddrGroupsGetAll)
            yield new IpAddrGroup(ipg)

    def listIpAddrGroupAddrs(ipgId: UUID): Seq[IpAddrGroupAddr] =
        for (addr <- dataClient.getAddrsByIpAddrGroup(ipgId).toIndexedSeq) yield
            if (addr.contains('.')) new Ipv4AddrGroupAddr(ipgId, addr)
            else new Ipv6AddrGroupAddr(ipgId, addr)

    def listLoadBalancers: Seq[LoadBalancer] =
        dataClient.loadBalancersGetAll.map(LoadBalancerDataConverter.fromData)

    def listPools: Seq[Pool] =
        dataClient.poolsGetAll.map(PoolDataConverter.fromData)

    def listPoolMembers(poolId: UUID): Seq[PoolMember] =
        dataClient.poolGetMembers(poolId).map(PoolMemberDataConverter.fromData)

    def listPorts: Seq[Port] =
        dataClient.portsGetAll.map(PortDataConverter.fromData)

    def listPortGroups: Seq[PortGroup] =
        dataClient.portGroupsGetAll.map(PortGroupDataConverter.fromData)

    def listRoutes(routerId: UUID): Seq[Route] =
        dataClient.routesFindByRouter(routerId).map(RouteDataConverter.fromData)

    def listRouters: Seq[Router] =
        dataClient.routersGetAll.map(RouterDataConverter.fromData)

    def listRules(chainId: UUID): Seq[Rule] =
        dataClient.rulesFindByChain(chainId).map(RuleDataConverter.fromData)

    def listTraceRequests: Seq[TraceRequest] =
        dataClient.traceRequestGetAll.map(TraceRequestDataConverter.fromData)

    def listTunnelZones: Seq[TunnelZone] =
        dataClient.tunnelZonesGetAll.map(TunnelZoneDataConverter.fromData)

    def listVips: Seq[Vip] = dataClient.vipGetAll.map(VipDataConverter.fromData)

    def listVteps: Seq[Vtep] =
        dataClient.vtepsGetAll.map(VtepDataConverter.fromData)

    def listVtepBindings(addr: IPv4Addr): Seq[VtepBinding] =
        dataClient.vtepGetBindings(addr).map(VtepBindingDataConverter.fromData)
}
