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
package org.midonet.cluster

import java.util.{List => JList, Map => JMap, Set => JSet, UUID}

import org.apache.zookeeper.{CreateMode, Op, OpResult, Watcher}

import rx.Observable

import org.midonet.cluster.client._
import org.midonet.cluster.state._
import org.midonet.cluster.data._
import org.midonet.cluster.data.dhcp.{Subnet, Subnet6, V6Host}
import org.midonet.cluster.data.host.{Host, Interface, VirtualPortMapping}
import org.midonet.cluster.data.l4lb.{HealthMonitor, LoadBalancer, Pool, PoolMember, VIP}
import org.midonet.cluster.data.ports.{BridgePort, VlanMacPort, VxLanPort}
import org.midonet.midolman.host.state.HostDirectory
import org.midonet.midolman.state._
import org.midonet.midolman.state.l4lb.LBStatus
import org.midonet.packets.{IPv4Addr, IPv4Subnet, IPv6Subnet, MAC}

class ExplodingClient extends Client {
    override def getBridge(bridgeID: UUID, builder: BridgeBuilder) = explode()
    override def getRouter(routerID: UUID, builder: RouterBuilder) = explode()
    override def getChain(chainID: UUID, builder: ChainBuilder) = explode()
    override def getPort(portID: UUID, builder: PortBuilder) = explode()
    override def getHost(hostIdentifier: UUID, builder: HostBuilder) = explode()
    override def getTunnelZones(
        uuid: UUID, builders: TunnelZones.BuildersProvider) = explode()
    override def getIPAddrGroup(uuid: UUID,
                                builder: IPAddrGroupBuilder) = explode()
    override def getLoadBalancer(uuid: UUID,
                                 builder: LoadBalancerBuilder) = explode()

    override def getPool(uuid: UUID, builder: PoolBuilder) = explode()
    override def getPortGroup(uuid: UUID, builder: PortGroupBuilder) = explode()
    override def getPoolHealthMonitorMap(
        builder: PoolHealthMonitorMapBuilder) = explode()
    override def getHealthMonitor(uuid: UUID,
                                  builder: HealthMonitorBuilder) = explode()
    override def subscribeBgp(portID: UUID, builder: BGPListBuilder) = explode()

    def explode() = throw new RuntimeException(
        "Client shouldn't be used in new stack")
}

class ExplodingDataClient extends DataClient {
    def explode() = throw new RuntimeException(
        "DataClient shouldn't be used in new stack")

    override def adRoutesGet(id: UUID): AdRoute = explode()
    override def adRoutesDelete(id: UUID): Unit = explode()
    override def adRoutesCreate(adRoute: AdRoute): UUID = explode()
    override def adRoutesFindByBgp(bgpId: UUID): JList[AdRoute] = explode()
    override def bgpSetStatus(id: UUID, status: String): Unit = explode()
    override def bgpGet(id: UUID): BGP = explode()
    override def bgpDelete(id: UUID): Unit = explode()
    override def bgpCreate(bgp: BGP): UUID = explode()
    override def bgpFindByPort(portId: UUID): JList[BGP] = explode()
    override def bridgeExists(id: UUID): Boolean = explode()
    override def bridgesGet(id: UUID): Bridge = explode()
    override def bridgesDelete(id: UUID): Unit = explode()
    override def bridgesCreate(bridge: Bridge): UUID = explode()
    override def bridgesUpdate(bridge: Bridge): Unit = explode()
    override def bridgesGetAll(): JList[Bridge] = explode()
    override def bridgesGetUuidSetMonitor(
        zkConnection: ZookeeperConnectionWatcher): EntityIdSetMonitor[UUID] = explode()
    override def bridgesFindByTenant(tenantId: String): JList[Bridge] = explode()
    override def ensureBridgeHasVlanDirectory(bridgeId: UUID): Unit = explode()
    override def bridgeHasMacTable(bridgeId: UUID, vlanId: Short): Boolean = explode()
    override def bridgeGetMacTable(bridgeId: UUID, vlanId: Short,
                                   ephemeral: Boolean): MacPortMap = explode()
    override def bridgeAddMacPort(bridgeId: UUID, vlanId: Short,
                                  mac: MAC, portId: UUID): Unit = explode()
    override def bridgeHasMacPort(bridgeId: UUID, vlanId: java.lang.Short,
                                  mac: MAC, portId: UUID): Boolean = explode()
    override def bridgeGetMacPorts(bridgeId: UUID): JList[VlanMacPort] = explode()
    override def bridgeGetMacPorts(bridgeId: UUID,
                                   vlanId: Short): JList[VlanMacPort] = explode()
    override def bridgeDeleteMacPort(bridgeId: UUID, vlanId: java.lang.Short,
                                     mac: MAC, portId: UUID): Unit = explode()
    override def bridgeAddIp4Mac(bridgeId: UUID, ip4: IPv4Addr,
                                 mac: MAC): Unit = explode()
    override def bridgeAddLearnedIp4Mac(bridgeId: UUID, ip4: IPv4Addr,
                                        mac: MAC): Unit = explode()
    override def bridgeHasIP4MacPair(bridgeId: UUID,
                                     ip: IPv4Addr, mac: MAC): Boolean = explode()
    override def bridgeGetIP4MacPairs(
        bridgeId: UUID): JMap[IPv4Addr, MAC] = explode()
    override def bridgeDeleteIp4Mac(bridgeId: UUID, ip4: IPv4Addr,
                                    mac: MAC): Unit = explode()
    override def bridgeDeleteLearnedIp4Mac(bridgeId: UUID,
                                           ip4: IPv4Addr,
                                           mac: MAC): Unit = explode()
    override def bridgeGetIp4ByMac(bridgeId: UUID,
                                   mac: MAC): JSet[IPv4Addr] = explode()
    override def bridgeGetArpTable(
        bridgeId: UUID): Ip4ToMacReplicatedMap = explode()
    override def chainsGet(id: UUID): Chain = explode()
    override def chainsDelete(id: UUID): Unit = explode()
    override def chainsCreate(chain: Chain): UUID = explode()
    override def chainsGetAll(): JList[Chain] = explode()
    override def chainsFindByTenant(tenantId: String): JList[Chain] = explode()
    override def dhcpSubnetsCreate(bridgeId: UUID,
                                   subnet: Subnet): Unit = explode()
    override def dhcpSubnetsUpdate(bridgeId: UUID,
                                   subnet: Subnet): Unit = explode()
    override def dhcpSubnetsDelete(bridgeId: UUID,
                                   subnetAddr: IPv4Subnet): Unit = explode()
    override def dhcpSubnetsGet(bridgeId: UUID,
                                subnetAddr: IPv4Subnet): Subnet = explode()
    override def dhcpSubnetsGetByBridge(
        bridgeId: UUID): JList[Subnet] = explode()
    override def dhcpSubnetsGetByBridgeEnabled(
        bridgeId: UUID): JList[Subnet] = explode()
    override def dhcpHostsCreate(bridgeId: UUID, subnet: IPv4Subnet,
                                 host: org.midonet.cluster.data.dhcp.Host): Unit = explode()
    override def dhcpHostsUpdate(bridgeId: UUID, subnet: IPv4Subnet,
                                 host: org.midonet.cluster.data.dhcp.Host): Unit = explode()
    override def dhcpHostsGet(bridgeId: UUID, subnet: IPv4Subnet,
                              mac: String): org.midonet.cluster.data.dhcp.Host = explode()
    override def dhcpHostsDelete(bridgId: UUID, subnet: IPv4Subnet,
                                 mac: String): Unit = explode()
    override def dhcpHostsGetBySubnet(bridgeId: UUID,
                                      subnet: IPv4Subnet): JList[org.midonet.cluster.data.dhcp.Host] = explode()

    override def dhcpSubnet6Create(bridgeId: UUID, subnet: Subnet6): Unit = explode()
    override def dhcpSubnet6Update(bridgeId: UUID, subnet: Subnet6): Unit = explode()
    override def dhcpSubnet6Delete(bridgeId: UUID, prefix: IPv6Subnet): Unit = explode()
    override def dhcpSubnet6Get(bridgeId: UUID, prefix: IPv6Subnet): Subnet6 = explode()
    override def dhcpSubnet6sGetByBridge(bridgeId: UUID): JList[Subnet6] = explode()
    override def dhcpV6HostCreate(bridgeId: UUID, prefix: IPv6Subnet,
                                  host: V6Host): Unit = explode()
    override def dhcpV6HostUpdate(bridgeId: UUID, prefix: IPv6Subnet,
                                  host: V6Host): Unit = explode()
    override def dhcpV6HostGet(
        bridgeId: UUID, prefix: IPv6Subnet, clientId: String): V6Host = explode()
    override def dhcpV6HostDelete(bridgId: UUID,
                                  prefix: IPv6Subnet, clientId: String ): Unit = explode()
    override def dhcpV6HostsGetByPrefix(
        bridgeId: UUID, prefix: IPv6Subnet): JList[V6Host] = explode()
    override def tunnelZonesCreate(zone: TunnelZone): UUID = explode()
    override def tunnelZonesDelete(uuid: UUID): Unit = explode()
    override def tunnelZonesExists(uuid: UUID): Boolean = explode()
    override def tunnelZonesGet(uuid: UUID): TunnelZone = explode()
    override def tunnelZonesGetAll(): JList[TunnelZone] = explode()
    override def tunnelZonesUpdate(zone: TunnelZone): Unit = explode()
    override def tunnelZonesGetMemberships(
        uuid: UUID): JSet[TunnelZone.HostConfig] = explode()
    override def tunnelZonesGetMembership(
        uuid: UUID, hostId: UUID): TunnelZone.HostConfig = explode()
    override def tunnelZonesContainHost(hostId: UUID): Boolean = explode()
    override def tunnelZonesAddMembership(
        zoneId: UUID, hostConfig: TunnelZone.HostConfig): UUID = explode()
    override def tunnelZonesDeleteMembership(zoneId: UUID,
                                             membershipId: UUID): Unit = explode()
    override def tunnelZonesGetMonitor(zkConnection: ZookeeperConnectionWatcher): EntityMonitor[UUID, TunnelZone.Data, TunnelZone] = explode()
    override def tunnelZonesGetUuidSetMonitor(
        zkConnection: ZookeeperConnectionWatcher): EntityIdSetMonitor[_] = explode()
    override def tunnelZonesGetMembershipsMonitor(
        zoneId: UUID, zkConnection: ZookeeperConnectionWatcher): EntityIdSetMonitor[UUID] = explode()
    override def hostsCreate(id: UUID, host: Host): UUID = explode()
    override def loadBalancerGet(id: UUID): LoadBalancer = explode()
    override def loadBalancerDelete(id: UUID): Unit = explode()
    override def loadBalancerCreate(loadBalancer: LoadBalancer): UUID = explode()
    override def loadBalancerUpdate(loadBalancer: LoadBalancer): Unit = explode()
    override def loadBalancersGetAll(): JList[LoadBalancer] = explode()
    override def loadBalancerGetPools(id: UUID): JList[Pool] = explode()
    override def loadBalancerGetVips(id: UUID): JList[VIP] = explode()

    override def healthMonitorGet(id: UUID): HealthMonitor = explode()
    override def healthMonitorDelete(id: UUID): Unit = explode()
    override def healthMonitorCreate(healthMonitor: HealthMonitor): UUID = explode()
    override def healthMonitorUpdate(healthMonitor: HealthMonitor): Unit = explode()
    override def healthMonitorsGetAll(): JList[HealthMonitor] = explode()
    override def healthMonitorGetPools(id: UUID): JList[Pool] = explode()
    override def poolMemberExists(id: UUID): Boolean = explode()
    override def poolMemberGet(id: UUID): PoolMember = explode()
    override def poolMemberDelete(id: UUID): Unit = explode()
    override def poolMemberCreate(poolMember: PoolMember): UUID = explode()
    override def poolMemberUpdate(poolMember: PoolMember): Unit = explode()
    override def poolMemberUpdateStatus(poolMemberId: UUID,
                                        status: LBStatus): Unit = explode()
    override def poolMembersGetAll(): JList[PoolMember] = explode()
    override def poolGet(id: UUID): Pool = explode()
    override def poolDelete(id: UUID): Unit = explode()
    override def poolCreate(pool: Pool): UUID = explode()
    override def poolUpdate(pool: Pool): Unit = explode()
    override def poolsGetAll(): JList[Pool] = explode()
    override def poolGetMembers(id: UUID): JList[PoolMember] = explode()
    override def poolGetVips(id: UUID): JList[VIP] = explode()
    override def poolSetMapStatus(id: UUID, status: PoolHealthMonitorMappingStatus): Unit = explode()
    override def vipGet(id: UUID): VIP = explode()
    override def vipDelete(id: UUID): Unit = explode()
    override def vipCreate(vip: VIP): UUID = explode()
    override def vipUpdate(vip: VIP): Unit = explode()
    override def vipGetAll(): JList[VIP] = explode()
    override def hostsGet(hostId: UUID): Host = explode()
    override def hostsDelete(hostId: UUID): Unit = explode()
    override def hostsExists(hostId: UUID): Boolean = explode()
    override def hostsIsAlive(hostId: UUID): Boolean = explode()
    override def hostsIsAlive(hostId: UUID, watcher: Watcher): Boolean = explode()
    override def hostsHasPortBindings(hostId: UUID): Boolean = explode()
    override def hostsGetAll(): JList[Host] = explode()
    override def hostsGetMonitor(
        zkConnection: ZookeeperConnectionWatcher): EntityMonitor[UUID, HostDirectory.Metadata, Host] = explode()
    override def hostsGetUuidSetMonitor(
        zkConnection: ZookeeperConnectionWatcher): EntityIdSetMonitor[UUID] = explode()
    override def interfacesGetByHost(hostId: UUID): JList[Interface] = explode()
    override def interfacesGet(hostId: UUID,
                               interfaceName: String): Interface = explode()
    override def hostsGetVirtualPortMappingsByHost(
        hostId: UUID): JList[VirtualPortMapping] = explode()
    override def hostsVirtualPortMappingExists(hostId: UUID,
                                               portId: UUID): Boolean = explode()
    override def hostsGetVirtualPortMapping(
        hostId: UUID, portId: UUID): VirtualPortMapping = explode()
    override def hostsAddVrnPortMapping(hostId: UUID, portId: UUID,
                                        localPortName: String): Unit = explode()
    override def hostsAddVrnPortMappingAndReturnPort(
        hostId: UUID, portId: UUID,
        localPortName: String): Port[_,_] = explode()
    override def hostsDelVrnPortMapping(hostId: UUID, portId: UUID): Unit = explode()
    override def hostsGetFloodingProxyWeight(hostId: UUID,
                                             watcher: Watcher): Integer = explode()
    override def hostsSetFloodingProxyWeight(hostId: UUID,
                                             weight: Int): Unit = explode()
    override def portsExists(id: UUID): Boolean = explode()
    override def portsCreate(port: Port[_, _]): UUID = explode()
    override def portsDelete(id: UUID): Unit = explode()
    override def portsFindByBridge(bridgeId: UUID): JList[BridgePort] = explode()
    override def portsFindPeersByBridge(
        bridgeId: UUID): JList[Port[_, _]] = explode()
    override def portsFindByRouter(
        routerId: UUID): JList[Port[_, _]] = explode()
    override def portsFindPeersByRouter(
        routerId: UUID): JList[Port[_, _]] = explode()
    override def portsGetAll(): JList[Port[_, _]] = explode()
    override def portsGet(id: UUID): Port[_, _] = explode()
    override def portsUpdate(port: Port[_, _]): Unit = explode()
    override def portsLink(portId: UUID, peerPortId: UUID): Unit = explode()
    override def portsUnlink(portId: UUID): Unit = explode()
    override def portsFindByPortGroup(
        portGroupId: UUID): JList[Port[_, _]] = explode()
    override def ipAddrGroupsGet(id: UUID): IpAddrGroup = explode()
    override def ipAddrGroupsDelete(id: UUID): Unit = explode()
    override def ipAddrGroupsCreate(ipAddrGroup: IpAddrGroup): UUID = explode()
    override def ipAddrGroupsExists(id: UUID): Boolean = explode()
    override def ipAddrGroupsGetAll(): JList[IpAddrGroup] = explode()
    override def ipAddrGroupAddAddr(id: UUID, addr: String): Unit = explode()
    override def ipAddrGroupRemoveAddr(id: UUID, addr: String): Unit = explode()
    override def ipAddrGroupHasAddr(id: UUID, addr: String): Boolean = explode()
    override def getAddrsByIpAddrGroup(id: UUID): JSet[String] = explode()
    override def portGroupsGet(id: UUID): PortGroup = explode()
    override def portGroupsDelete(id: UUID): Unit = explode()
    override def portGroupsCreate(portGroup: PortGroup): UUID = explode()
    override def portGroupsUpdate(portGroup: PortGroup): Unit = explode()
    override def portGroupsExists(id: UUID): Boolean = explode()
    override def portGroupsGetAll(): JList[PortGroup] = explode()
    override def portGroupsFindByPort(portId: UUID): JList[PortGroup] = explode()
    override def portGroupsFindByTenant(
        tenantId: String): JList[PortGroup] = explode()
    override def portGroupsIsPortMember(id: UUID,
                                        portId: UUID): Boolean = explode()
    override def portGroupsAddPortMembership(id: UUID,
                                             portId: UUID): Unit = explode()
    override def portGroupsRemovePortMembership(id: UUID,
                                                portId: UUID): Unit = explode()
    override def routesGet(id: UUID): Route = explode()
    override def routesDelete(id: UUID): Unit = explode()
    override def routesCreate(route: Route): UUID = explode()
    override def routesCreateEphemeral(route: Route): UUID = explode()
    override def routesFindByRouter(routerId: UUID): JList[Route] = explode()
    override def routerExists(id: UUID): Boolean = explode()
    override def routersGet(id: UUID): Router = explode()
    override def routersDelete(id: UUID): Unit = explode()
    override def routersCreate(router: Router): UUID = explode()
    override def routersUpdate(router: Router): Unit = explode()
    override def routersGetAll(): JList[Router] = explode()
    override def routersFindByTenant(tenantId: String): JList[Router] = explode()
    override def rulesGet(id: UUID): Rule[_, _]  = explode()
    override def rulesDelete(id: UUID): Unit = explode()
    override def rulesCreate(rule: Rule[_, _]): UUID = explode()
    override def rulesFindByChain(chainId: UUID): JList[Rule[_, _]] = explode()
    override def tenantsGetAll(): JSet[String] = explode()
    override def writeVersionGet(): WriteVersion = explode()
    override def writeVersionUpdate(newVersion: WriteVersion): Unit = explode()
    override def systemStateGet(): SystemState = explode()
    override def systemStateUpdate(systemState: SystemState): Unit = explode()
    override def hostVersionsGet(): JList[HostVersion] = explode()
    override def traceRequestGet(id: UUID): TraceRequest = explode()
    override def traceRequestDelete(id: UUID): Unit = explode()
    override def traceRequestCreate(request: TraceRequest): UUID = explode()
    override def traceRequestCreate(request: TraceRequest,
                                    enabled: Boolean): UUID = explode()
    override def traceRequestGetAll(): JList[TraceRequest] = explode()
    override def traceRequestFindByTenant(
        tenantId: String): JList[TraceRequest] = explode()
    override def traceRequestEnable(id: UUID): Unit = explode()
    override def traceRequestDisable(id: UUID): Unit = explode()
    override def getPrecedingHealthMonitorLeader(
        myNode: Integer): Integer = explode()

    override def registerAsHealthMonitorNode(
        cb: ZkLeaderElectionWatcher.ExecuteOnBecomingLeader): Integer = explode()
    override def removeHealthMonitorLeaderNode(node: Integer): Unit = explode()
    override def vtepCreate(vtep: VTEP): Unit = explode()
    override def vtepGet(ipAddr: IPv4Addr): VTEP = explode()
    override def vtepsGetAll(): JList[VTEP] = explode()
    override def vtepDelete(ipAddr: IPv4Addr): Unit = explode()
    override def vtepUpdate(vtep: VTEP): Unit = explode()
    override def vtepAddBinding(ipAddr: IPv4Addr,
                                portName: String, vlanId: Short,
                                networkId: UUID): Unit = explode()
    override def vtepDeleteBinding(ipAddr: IPv4Addr, portName: String,
                                   vlanId: Short): Unit = explode()
    override def vtepGetBindings(ipAddr: IPv4Addr): JList[VtepBinding] = explode()
    override def vtepGetBinding(ipAddr: IPv4Addr, portName: String,
                                vlanId: Short): VtepBinding = explode()
    override def getNewVni: Int = explode()
    override def bridgeGetVtepBindings(id: UUID,
                                       mgmtIp: IPv4Addr): JList[VtepBinding] = explode()
    override def bridgeCreateVxLanPort(
            bridgeId: UUID, mgmtIp: IPv4Addr, mgmtPort: Int, vni: Int,
            tunnelIp: IPv4Addr, tunnelZoneId: UUID): VxLanPort = explode()
    override def bridgeDeleteVxLanPort(bridgeId: UUID,
                                       vxLanPort: IPv4Addr): Unit = explode()
    override def bridgeDeleteVxLanPort(port: VxLanPort): Unit = explode()
    override def tryOwnVtep(mgmtIp: IPv4Addr, ownerId: UUID): UUID = explode()
    override def vxlanTunnelEndpointFor(bridgePortId: UUID): IPv4Addr = explode()
    override def bridgeGetAndWatch(
        id: UUID, watcher: Directory.TypedWatcher): Bridge = explode()
    override def vxLanPortIdsAsyncGet(
        callback: DirectoryCallback[JSet[UUID]], watcher: Directory.TypedWatcher) = explode()
    override def getIp4MacMap(bridgeId: UUID): Ip4ToMacReplicatedMap = explode()
}

class ExplodingLegacyStorage extends LegacyStorage {
    def explode() = throw new RuntimeException(
        "LegacyStorage shouldn't be used in new stack")

    override def bridgeMacTable(bridgeId: UUID, vlanId: Short,
                                ephemeral: Boolean): MacPortMap = explode()
    override def bridgeIp4MacMap(bridgeId: UUID): Ip4ToMacReplicatedMap = explode()
    override def routerRoutingTable(routerId: UUID): ReplicatedSet[org.midonet.midolman.layer3.Route] = explode()
    override def routerArpTable(routerId: UUID): ArpTable = explode()
    override def setPortActive(portId: UUID, host: UUID, active: Boolean)
    : Observable[PortConfig] = explode()
}


class ExplodingZkManager extends ZkManager(null, null) {
    def explode() = throw new RuntimeException(
        "ZkManager shouldn't be used in new stack")

    override def asyncGet(relativePath: String,
                          data: DirectoryCallback[Array[Byte]],
                          watcher: Directory.TypedWatcher): Unit = explode()
    override def asyncGetChildren(relativePath: String,
                                  childrenCallback: DirectoryCallback[JSet[String]],
                                  watcher: Directory.TypedWatcher): Unit = explode()
    override def asyncAdd(relativePath: String, data: Array[Byte],
                          mode: CreateMode,
                          cb: DirectoryCallback[String]): Unit = explode()
    override def asyncDelete(relativePath: String,
                             callback: DirectoryCallback[Void]): Unit = explode()

    override def add(path: String, data: Array[Byte], mode: CreateMode): String = explode()
    override def asyncMultiPathGet(paths: JSet[String],
                                   cb: DirectoryCallback[JSet[Array[Byte]]]): Unit = explode()
    override def getDirectory: Directory = explode()
    override def getSubDirectory(path: String): Directory = explode()
    override def exists(path: String): Boolean = explode()
    override def exists(path: String,  watcher: Watcher): Boolean = explode()


    override def exists(path: String, watcher: Runnable): Boolean = explode()
    override def asyncExists(relativePath: String,
                             cb: DirectoryCallback[java.lang.Boolean]): Unit = explode()
    override def addPersistent_safe(path: String,
                                    data: Array[Byte]): String = explode()
    override def addPersistent(path: String, data: Array[Byte]): String = explode()
    override def addEphemeral(path: String, data: Array[Byte]): String = explode()
    override def ensureEphemeral(path: String, data: Array[Byte]): String = explode()
    override def ensureEphemeralAsync(path: String, data: Array[Byte],
                                      cb: DirectoryCallback[String]): Unit = explode()
    override def deleteEphemeral(path: String): Unit = explode()
    override def addPersistentSequential(path: String, data: Array[Byte]): String = explode()
    override def addEphemeralSequential(path: String, data: Array[Byte]): String = explode()
    override def delete(path: String): Unit = explode()
    override def get(path: String): Array[Byte] = explode()
    override def get(path: String, watcher: Runnable): Array[Byte] = explode()
    override def getWithVersion(path: String,
                                watcher: Runnable): JMap.Entry[Array[Byte], java.lang.Integer] = explode()
    override def getChildren(path: String): JSet[String] = explode()
    override def getChildren(path: String, watcher: Runnable): JSet[String] = explode()
    override def multi(ops: JList[Op]): JList[OpResult] = explode()
    override def update(path: String, data: Array[Byte]): Unit = explode()
    override def update(path: String, data: Array[Byte], version: Int): Unit = explode()
    override def getPersistentCreateOp(path: String, data: Array[Byte]): Op = explode()
    override def getPersistentCreateOps(paths: String*): JList[Op] = explode()
    override def getEphemeralCreateOp(path: String, data: Array[Byte]): Op = explode()
    override def getDeleteOp(path: String): Op = explode()
    override def getDeleteOps(paths: String*): JList[Op] = explode()
    override def getSetDataOp(path: String, data: Array[Byte]): Op = explode()
    override def removeLastOp(op: JList[Op], path: String): Unit = explode()
    override def getRecursiveDeleteOps(root: String): JList[Op] = explode()
    override def disconnect(): Unit = explode()
}
