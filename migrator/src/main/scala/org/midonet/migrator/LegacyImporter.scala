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
import org.midonet.cluster.rest_api.conversion._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.southbound.vtep.VtepClusterClient
import org.midonet.packets.IPv4Subnet

class LegacyImporter @Inject() (dataClient: DataClient,
                                vtepClient: VtepClusterClient) {

    def listBridges: Seq[Bridge] =
        dataClient.bridgesGetAll.map(BridgeDataConverter.fromData)

    def listChains: Seq[Chain] =
        for (c <- dataClient.chainsGetAll)
            yield ChainDataConverter.fromData(c, null)

    def listDhcpSubnets(bridgeId: UUID): Seq[DhcpSubnet] =
        for (s <- dataClient.dhcpSubnetsGetByBridge(bridgeId))
            yield DhcpSubnetDataConverter.fromData(s, bridgeId, null)

    def listDhcpHosts(bridgeId: UUID, subnet: IPv4Subnet): Seq[DhcpHost] =
        for (h <- dataClient.dhcpHostsGetBySubnet(bridgeId, subnet))
            yield DhcpHostDataConverter.fromData(h, null)

    def listHealthMonitors: Seq[HealthMonitor] =
        for (hm <- dataClient.healthMonitorsGetAll)
            yield HealthMonitorDataConverter.fromData(hm, null)

    def listHosts: Seq[Host] =
        for (h <- dataClient.hostsGetAll)
            yield HostDataConverter.fromData(h, null)

    def listIpAddrGroups: Seq[IpAddrGroup] =
        for (ipg <- dataClient.ipAddrGroupsGetAll)
            yield new IpAddrGroup(ipg)

    def listIpAddrGroupAddrs(ipgId: UUID): Seq[IpAddrGroupAddr] =
        for (addr <- dataClient.getAddrsByIpAddrGroup(ipgId).toIndexedSeq) yield
            if (addr.contains('.')) new Ipv4AddrGroupAddr(ipgId, addr)
            else new Ipv6AddrGroupAddr(ipgId, addr)

    def listLoadBalancers: Seq[LoadBalancer] =
        for (lb <- dataClient.loadBalancersGetAll)
            yield LoadBalancerDataConverter.fromData(lb, null)

    def listPools: Seq[Pool] =
        for (p <- dataClient.poolsGetAll)
            yield PoolDataConverter.fromData(p, null)

    def listPoolMembers: Seq[PoolMember] =
        for (pm <- dataClient.poolMembersGetAll)
            yield PoolMemberDataConverter.fromData(pm, null)

    def listPorts: Seq[Port] =
        for (p <- dataClient.portsGetAll)
            yield PortDataConverter.fromData(p, null)

    def listPortGroups: Seq[PortGroup] =
        for (pg <- dataClient.portGroupsGetAll)
            yield PortGroupDataConverter.fromData(pg, null)

    def listRoutes(routerId: UUID): Seq[Route] =
        for (r <- dataClient.routesFindByRouter(routerId))
            yield RouteDataConverter.fromData(r, null)

    def listRouters: Seq[Router] =
        for (r <- dataClient.routersGetAll)
            yield RouterDataConverter.fromData(r, null)

    def listRules(chainId: UUID): Seq[Rule] =
        for (r <- dataClient.rulesFindByChain(chainId))
            yield RuleDataConverter.fromData(r, null)

    def listTraceRequests: Seq[TraceRequest] =
        for (tr <- dataClient.traceRequestGetAll)
            yield TraceRequestDataConverter.fromData(tr, null)

    def listTunnelZones: Seq[TunnelZone] =
        for (tz <- dataClient.tunnelZonesGetAll)
            yield TunnelZoneDataConverter.fromData(tz, null)

    def listVips: Seq[Vip] =
        for (vip <- dataClient.vipGetAll)
            yield VIPDataConverter.fromData(vip, null)
}
