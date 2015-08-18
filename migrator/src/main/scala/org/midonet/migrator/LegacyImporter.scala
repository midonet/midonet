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

import scala.collection.JavaConverters._
import scala.collection.breakOut

import com.google.inject.Inject

import org.midonet.cluster.data.{dhcp, host}
import org.midonet.cluster.rest_api.conversion._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.{data, DataClient}
import org.midonet.packets.IPv4Subnet

class LegacyImporter @Inject() (dataClient: DataClient) {

    private def toDtos[S, T](vals: java.util.List[S], converter: S => T)
    : List[T] = vals.asScala.map(converter)(breakOut)

    def listBridges: List[Bridge] =
        toDtos(dataClient.bridgesGetAll, BridgeDataConverter.fromData)

    def listChains: List[Chain] =
        toDtos(dataClient.chainsGetAll,
               (c: data.Chain) => ChainDataConverter.fromData(c, null))

    def listDhcpSubnets(bridgeId: UUID): List[DhcpSubnet] =
        toDtos(dataClient.dhcpSubnetsGetByBridge(bridgeId),
               (s: dhcp.Subnet) =>
                   DhcpSubnetDataConverter.fromData(s, bridgeId, null))

    def listDhcpHosts(bridgeId: UUID, subnet: IPv4Subnet): List[DhcpHost] =
        toDtos(dataClient.dhcpHostsGetBySubnet(bridgeId, subnet),
               (h: dhcp.Host) => DhcpHostDataConverter.fromData(h, null))

    def listHosts: List[Host] =
        toDtos(dataClient.hostsGetAll,
               (h: host.Host) => HostDataConverter.fromData(h, null))

    def listPorts: List[Port] =
        toDtos(dataClient.portsGetAll,
               (p: data.Port[_, _]) => PortDataConverter.fromData(p, null))

    def listPortGroups: List[PortGroup] =
        toDtos(dataClient.portGroupsGetAll,
               (g: data.PortGroup) => PortGroupDataConverter.fromData(g, null))

    def listRouters: List[Router] =
        toDtos(dataClient.routersGetAll,
               (r: data.Router) => RouterDataConverter.fromData(r, null))

    def listRules(chainId: UUID): List[Rule] =
        toDtos(dataClient.rulesFindByChain(chainId),
               (r: data.Rule[_, _]) => RuleDataConverter.fromData(r, null))

    def listTunnelZones: List[TunnelZone] =
        toDtos(dataClient.tunnelZonesGetAll,
               (tz: data.TunnelZone) =>
                   TunnelZoneDataConverter.fromData(tz, null))
}
