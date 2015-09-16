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
package org.midonet.cluster;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import com.google.common.base.Strings;

import org.apache.commons.lang3.tuple.MutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.AdRoute;
import org.midonet.cluster.data.BGP;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Chain;
import org.midonet.cluster.data.Converter;
import org.midonet.cluster.data.HostVersion;
import org.midonet.cluster.data.IpAddrGroup;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.PortGroup;
import org.midonet.cluster.data.Route;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.SystemState;
import org.midonet.cluster.data.TraceRequest;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.cluster.data.WriteVersion;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.Interface;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.cluster.data.l4lb.HealthMonitor;
import org.midonet.cluster.data.l4lb.LoadBalancer;
import org.midonet.cluster.data.l4lb.Pool;
import org.midonet.cluster.data.l4lb.PoolMember;
import org.midonet.cluster.data.l4lb.VIP;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.VlanMacPort;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.cluster.data.rules.TraceRule;
import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.cluster.zookeeper.ZkConnectionProvider;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.rules.RuleList;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.Ip4ToMacReplicatedMap;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PoolHealthMonitorMappingStatus;
import org.midonet.midolman.state.PortConfigCache;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.ZkUtil;
import org.midonet.midolman.state.l4lb.MappingStatusException;
import org.midonet.midolman.state.zkManagers.AdRouteZkManager;
import org.midonet.midolman.state.zkManagers.BgpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpV6ZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.ConfigGetter;
import org.midonet.midolman.state.zkManagers.HealthMonitorZkManager;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager;
import org.midonet.midolman.state.zkManagers.LoadBalancerZkManager;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig.HealthMonitorConfigWithId;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig.LoadBalancerConfigWithId;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig.PoolMemberConfigWithId;
import org.midonet.midolman.state.zkManagers.PoolHealthMonitorZkManager.PoolHealthMonitorConfig.VipConfigWithId;
import org.midonet.midolman.state.zkManagers.PoolMemberZkManager;
import org.midonet.midolman.state.zkManagers.PoolZkManager;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.RouteZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.state.zkManagers.RuleZkManager;
import org.midonet.midolman.state.zkManagers.TenantZkManager;
import org.midonet.midolman.state.zkManagers.TraceRequestZkManager;
import org.midonet.midolman.state.zkManagers.TunnelZoneZkManager;
import org.midonet.midolman.state.zkManagers.VipZkManager;
import org.midonet.midolman.state.zkManagers.VtepZkManager;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.MAC;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;

import static com.google.common.base.Preconditions.checkNotNull;
import static org.midonet.cluster.data.Rule.RuleIndexOutOfBoundsException;

@SuppressWarnings("unused")
public class LocalDataClientImpl implements DataClient {

    @Inject
    private TenantZkManager tenantZkManager;

    @Inject
    private BridgeDhcpZkManager dhcpZkManager;

    @Inject
    private BridgeDhcpV6ZkManager dhcpV6ZkManager;

    @Inject
    private BgpZkManager bgpZkManager;

    @Inject
    private AdRouteZkManager adRouteZkManager;

    @Inject
    private BridgeZkManager bridgeZkManager;

    @Inject
    private ChainZkManager chainZkManager;

    @Inject
    private RouteZkManager routeZkManager;

    @Inject
    private RouterZkManager routerZkManager;

    @Inject
    private RuleZkManager ruleZkManager;

    @Inject
    private PortZkManager portZkManager;

    @Inject
    private PortConfigCache portCache;

    @Inject
    private RouteZkManager routeMgr;

    @Inject
    private PortGroupZkManager portGroupZkManager;

    @Inject
    private HostZkManager hostZkManager;

    @Inject
    private LoadBalancerZkManager loadBalancerZkManager;

    @Inject
    private HealthMonitorZkManager healthMonitorZkManager;

    @Inject
    private PoolMemberZkManager poolMemberZkManager;

    @Inject
    private PoolZkManager poolZkManager;

    @Inject
    private VipZkManager vipZkManager;

    @Inject
    private TunnelZoneZkManager zonesZkManager;

    @Inject
    private ZkManager zkManager;

    @Inject
    private PathBuilder pathBuilder;

    @Inject
    private ClusterRouterManager routerManager;

    @Inject
    private ClusterBridgeManager bridgeManager;

    @Inject
    private Serializer serializer;

    @Inject
    private SystemDataProvider systemDataProvider;

    @Inject
    private IpAddrGroupZkManager ipAddrGroupZkManager;

    @Inject
    private TraceRequestZkManager traceReqZkManager;

    @Inject
    private VtepZkManager vtepZkManager;

    @Inject
    @Named(ZkConnectionProvider.DIRECTORY_REACTOR_TAG)
    private Reactor reactor;

    private final static Logger log =
            LoggerFactory.getLogger(LocalDataClientImpl.class);

    @Override
    public @CheckForNull AdRoute adRoutesGet(UUID id)
            throws StateAccessException, SerializationException {
        log.debug("Entered: id={}", id);

        AdRoute adRoute = null;
        if (adRouteZkManager.exists(id)) {
            adRoute = Converter.fromAdRouteConfig(adRouteZkManager.get(id));
            adRoute.setId(id);
        }

        log.debug("Exiting: adRoute={}", adRoute);
        return adRoute;
    }

    @Override
    public List<AdRoute> adRoutesFindByBgp(@Nonnull UUID bgpId)
            throws StateAccessException, SerializationException {
        List<UUID> adRouteIds = adRouteZkManager.list(bgpId);
        List<AdRoute> adRoutes = new ArrayList<>();
        for (UUID adRouteId : adRouteIds) {
            adRoutes.add(adRoutesGet(adRouteId));
        }
        return adRoutes;
    }

    @Override
    public void bgpSetStatus(@Nonnull UUID id, @Nonnull String status)
            throws StateAccessException, SerializationException {
        bgpZkManager.setStatus(id, status);
    }

    @Override
    public @CheckForNull BGP bgpGet(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        return new BGP(id, bgpZkManager.get(id)).setStatus(bgpZkManager.getStatus(id));
    }

    @Override
    public List<BGP> bgpFindByPort(UUID portId)
            throws StateAccessException, SerializationException {
        List<UUID> bgpIds = bgpZkManager.list(portId);
        List<BGP> bgps = new ArrayList<>();
        for (UUID bgpId : bgpIds) {
            bgps.add(bgpGet(bgpId));
        }
        return bgps;
    }

    public List<Bridge> bridgesFindByTenant(String tenantId)
        throws StateAccessException, SerializationException {
        log.debug("bridgesFindByTenant entered: tenantId={}", tenantId);

        List<Bridge> bridges = bridgesGetAll();

        for (Iterator<Bridge> it = bridges.iterator(); it.hasNext();) {
            if (!it.next().hasTenantId(tenantId)) {
                it.remove();
            }
        }

        log.debug("bridgesFindByTenant exiting: {} bridges found", bridges.size());
        return bridges;
    }

    /**
     * Returns a list of all MAC-port mappings for the specified bridge.
     * @throws StateAccessException
     */
    @Override
    public List<VlanMacPort> bridgeGetMacPorts(@Nonnull UUID bridgeId)
            throws StateAccessException {
        // Get entries for the untagged VLAN.
        List<VlanMacPort> allPorts = new LinkedList<>();
        allPorts.addAll(bridgeGetMacPorts(bridgeId, Bridge.UNTAGGED_VLAN_ID));

        // Get entries for the other VLANs.
        short[] vlanIds = bridgeZkManager.getVlanIds(bridgeId);
        for (short vlanId : vlanIds)
            allPorts.addAll(bridgeGetMacPorts(bridgeId, vlanId));
        return allPorts;
    }

    /**
     * Gets MAC-port mappings for the specified bridge and VLAN.
     * @param bridgeId Bridge whose MAC-port mappings are requested.
     * @param vlanId VLAN whose MAC-port mappings are requested. The value
     *               Bridge.UNTAGGED_VLAN_ID indicates the untagged VLAN.
     * @return List of MAC-port mappings.
     * @throws StateAccessException
     */
    @Override
    public List<VlanMacPort> bridgeGetMacPorts(
            @Nonnull UUID bridgeId, short vlanId)
            throws StateAccessException {
        Map<MAC, UUID> portsMap = MacPortMap.getAsMap(
                bridgeZkManager.getMacPortMapDirectory(bridgeId, vlanId));
        List<VlanMacPort> ports = new ArrayList<>(portsMap.size());
        for (Map.Entry<MAC, UUID> e : portsMap.entrySet()) {
            ports.add(new VlanMacPort(e.getKey(), e.getValue(), vlanId));
        }
        return ports;
    }

    @Override
    public void ensureBridgeHasVlanDirectory(@Nonnull UUID bridgeId)
            throws StateAccessException {
        bridgeZkManager.ensureBridgeHasVlanDirectory(bridgeId);
    }

    @Override
    public boolean bridgeHasMacTable(@Nonnull UUID bridgeId, short vlanId)
            throws StateAccessException {
        return bridgeZkManager.hasVlanMacTable(bridgeId, vlanId);
    }

    @Override
    public MacPortMap bridgeGetMacTable(
            @Nonnull UUID bridgeId, short vlanId, boolean ephemeral)
            throws StateAccessException {
        return new MacPortMap(
                bridgeZkManager.getMacPortMapDirectory(bridgeId, vlanId),
                ephemeral);
    }

    @Override
    public void bridgeAddMacPort(@Nonnull UUID bridgeId, short vlanId,
                                 @Nonnull MAC mac, @Nonnull UUID portId)
            throws StateAccessException {
        MacPortMap.addPersistentEntry(
            bridgeZkManager.getMacPortMapDirectory(bridgeId, vlanId), mac,
            portId);
    }

    @Override
    public boolean bridgeHasMacPort(@Nonnull UUID bridgeId, Short vlanId,
                                    @Nonnull MAC mac, @Nonnull UUID portId)
            throws StateAccessException {
        return MacPortMap.hasPersistentEntry(
            bridgeZkManager.getMacPortMapDirectory(bridgeId, vlanId), mac,
            portId);
    }

    @Override
    public void bridgeDeleteMacPort(@Nonnull UUID bridgeId, Short vlanId,
                                    @Nonnull MAC mac, @Nonnull UUID portId)
            throws StateAccessException {
        MacPortMap.deleteEntry(
            bridgeZkManager.getMacPortMapDirectory(bridgeId, vlanId),
            mac, portId);
    }

    @Override
    public Map<IPv4Addr, MAC> bridgeGetIP4MacPairs(@Nonnull UUID bridgeId)
        throws StateAccessException {
        return Ip4ToMacReplicatedMap.getAsMap(
            bridgeZkManager.getIP4MacMapDirectory(bridgeId));
    }

    @Override
    public void bridgeAddIp4Mac(
            @Nonnull UUID bridgeId, @Nonnull IPv4Addr ip4, @Nonnull MAC mac)
            throws StateAccessException {
        // TODO: potential race conditions
        // The following code fixes the unobvious behavior of
        // replicated maps, but is still subject to potential race conditions
        // See MN-2637
        Directory dir = bridgeZkManager.getIP4MacMapDirectory(bridgeId);
        if (Ip4ToMacReplicatedMap.hasPersistentEntry(dir, ip4, mac)) {
            return;
        }
        MAC oldMac = Ip4ToMacReplicatedMap.getEntry(dir, ip4);
        if (oldMac != null) {
            Ip4ToMacReplicatedMap.deleteEntry(dir, ip4, oldMac);
        }
        Ip4ToMacReplicatedMap.addPersistentEntry(
            bridgeZkManager.getIP4MacMapDirectory(bridgeId), ip4, mac);
    }

    @Override
    public boolean bridgeHasIP4MacPair(@Nonnull UUID bridgeId,
                                       @Nonnull IPv4Addr ip, @Nonnull MAC mac)
        throws StateAccessException {
        Directory dir = bridgeZkManager.getIP4MacMapDirectory(bridgeId);
        return Ip4ToMacReplicatedMap.hasPersistentEntry(dir, ip, mac) ||
               Ip4ToMacReplicatedMap.hasLearnedEntry(dir, ip, mac);
    }

    @Override
    public void bridgeDeleteIp4Mac(
            @Nonnull UUID bridgeId, @Nonnull IPv4Addr ip4, @Nonnull MAC mac)
            throws StateAccessException {
        Ip4ToMacReplicatedMap.deleteEntry(
            bridgeZkManager.getIP4MacMapDirectory(bridgeId), ip4, mac);
    }

    @Override
    public List<Bridge> bridgesGetAll() throws StateAccessException,
            SerializationException {
        log.debug("bridgesGetAll entered");
        List<Bridge> bridges = new ArrayList<>();

        for (UUID id :
            bridgeZkManager.getUuidList(pathBuilder.getBridgesPath())) {
            Bridge bridge = bridgesGet(id);
            if (bridge != null) {
                bridges.add(bridge);
            }
        }

        log.debug("bridgesGetAll exiting: {} bridges found", bridges.size());
        return bridges;
    }

    @Override
    public boolean bridgeExists(UUID id) throws StateAccessException {
        return bridgeZkManager.exists(id);
    }

    @Override
    public @CheckForNull Bridge bridgesGet(UUID id)
            throws StateAccessException, SerializationException {
        log.debug("Entered: id={}", id);

        Bridge bridge = null;
        if (bridgeZkManager.exists(id)) {
            BridgeConfig bridgeCfg = bridgeZkManager.get(id);
            if (bridgeCfg.vxLanPortId != null) {
                log.info("Migrating legacy vxlanPortId property to " +
                         "vxlanPortIds on bridge {}", id);
                if (!bridgeCfg.vxLanPortIds.contains(bridgeCfg.vxLanPortId)) {
                    bridgeCfg.vxLanPortIds.add(0, bridgeCfg.vxLanPortId);
                }
                bridgeCfg.vxLanPortId = null;
                bridgeZkManager.update(id, bridgeCfg);
                bridgeCfg = bridgeZkManager.get(id);
            }
            bridge = Converter.fromBridgeConfig(bridgeCfg);
            bridge.setId(id);
        }

        log.debug("Exiting: bridge={}", bridge);
        return bridge;
    }

     @Override
     public List<Chain> chainsGetAll() throws StateAccessException,
                                              SerializationException {
        log.debug("chainsGetAll entered");

        List<Chain> chains = new ArrayList<>();

        String path = pathBuilder.getChainsPath();
        if (zkManager.exists(path)) {
            Set<String> chainIds = zkManager.getChildren(path);
            for (String id : chainIds) {
                Chain chain = chainsGet(UUID.fromString(id));
                if (chain != null) {
                    chains.add(chain);
                }
            }
        }

        log.debug("chainsGetAll exiting: {} chains found", chains.size());
        return chains;
    }

    @Override
    public @CheckForNull Chain chainsGet(UUID id)
            throws StateAccessException, SerializationException {
        log.debug("Entered: id={}", id);

        Chain chain = null;
        if (chainZkManager.exists(id)) {
            chain = Converter.fromChainConfig(chainZkManager.get(id));
            chain.setId(id);
        }

        log.debug("Exiting: chain={}", chain);
        return chain;
    }

    private UUID prepareChainsCreate(@Nonnull Chain chain, List<Op> ops)
            throws StateAccessException, SerializationException {
        if (chain.getId() == null) {
            chain.setId(UUID.randomUUID());
        }

        ChainZkManager.ChainConfig chainConfig =
                Converter.toChainConfig(chain);

        ops.addAll(chainZkManager.prepareCreate(chain.getId(), chainConfig));

        // Create the top level directories for
        String tenantId = chain.getProperty(Chain.Property.tenant_id);
        if (!Strings.isNullOrEmpty(tenantId)) {
            ops.addAll(tenantZkManager.prepareCreate(tenantId));
        }
        return chain.getId();
    }

    @Override
    public boolean tunnelZonesExists(UUID uuid) throws StateAccessException {
        return zonesZkManager.exists(uuid);
    }

    @Override
    public @CheckForNull TunnelZone tunnelZonesGet(UUID uuid)
            throws StateAccessException, SerializationException {
        return zonesZkManager.getZone(uuid, null);
    }

    @Override
    public List<TunnelZone> tunnelZonesGetAll()
            throws StateAccessException, SerializationException {
        Collection<UUID> ids = zonesZkManager.getZoneIds();

        List<TunnelZone> tunnelZones = new ArrayList<>();

        for (UUID id : ids) {
            TunnelZone zone = tunnelZonesGet(id);
            if (zone != null) {
                tunnelZones.add(zone);
            }
        }

        return tunnelZones;
    }

    @Override
    public @CheckForNull TunnelZone.HostConfig tunnelZonesGetMembership(UUID id,
                                                                UUID hostId)
            throws StateAccessException, SerializationException {
        return zonesZkManager.getZoneMembership(id, hostId, null);
    }

    @Override
    public boolean tunnelZonesContainHost(UUID hostId)
            throws StateAccessException, SerializationException {
        List<TunnelZone> tunnelZones = tunnelZonesGetAll();
        boolean hostExistsInTunnelZone = false;
        for (TunnelZone tunnelZone : tunnelZones) {
            if (zonesZkManager.membershipExists(tunnelZone.getId(), hostId)) {
                hostExistsInTunnelZone = true;
                break;
            }
        }
        return hostExistsInTunnelZone;
    }

    @Override
    public Set<TunnelZone.HostConfig> tunnelZonesGetMemberships(
            final UUID uuid)
        throws StateAccessException {

        return CollectionFunctors.map(
            zonesZkManager.getZoneMemberships(uuid, null),
            new Functor<UUID, TunnelZone.HostConfig>() {
                @Override
                public TunnelZone.HostConfig apply(UUID arg0) {
                    try {
                        return zonesZkManager.getZoneMembership(uuid, arg0,
                                                                null);
                    } catch (StateAccessException e) {
                        //
                        return null;
                    } catch (SerializationException e) {
                        return null;
                    }

                }
            },
            new HashSet<TunnelZone.HostConfig>()
        );
    }

    @Override
    public List<Chain> chainsFindByTenant(String tenantId)
            throws StateAccessException, SerializationException {
        log.debug("chainsFindByTenant entered: tenantId={}", tenantId);

        List<Chain> chains = chainsGetAll();
        for (Iterator<Chain> it = chains.iterator(); it.hasNext();) {
            if (!it.next().hasTenantId(tenantId)) {
                it.remove();
            }
        }

        log.debug("chainsFindByTenant exiting: {} chains found",
                  chains.size());
        return chains;
    }

    @Override
    public @CheckForNull Subnet dhcpSubnetsGet(UUID bridgeId,
                                               IPv4Subnet subnetAddr)
            throws StateAccessException, SerializationException {

        Subnet subnet = null;
        if (dhcpZkManager.existsSubnet(bridgeId, subnetAddr)) {
            BridgeDhcpZkManager.Subnet subnetConfig =
                dhcpZkManager.getSubnet(bridgeId, subnetAddr);

            subnet = Converter.fromDhcpSubnetConfig(subnetConfig);
            subnet.setId(subnetAddr.toZkString());
        }

        return subnet;
    }

    @Override
    public List<Subnet> dhcpSubnetsGetByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException {

        List<IPv4Subnet> subnetConfigs = dhcpZkManager.listSubnets(bridgeId);
        List<Subnet> subnets = new ArrayList<>(subnetConfigs.size());

        for (IPv4Subnet subnetAddr : subnetConfigs) {
            subnets.add(dhcpSubnetsGet(bridgeId, subnetAddr));
        }

        return subnets;
    }

    @Override
    public List<Subnet> dhcpSubnetsGetByBridgeEnabled(UUID bridgeId)
            throws StateAccessException, SerializationException {

        List<BridgeDhcpZkManager.Subnet> subnetConfigs =
                dhcpZkManager.getEnabledSubnets(bridgeId);
        List<Subnet> subnets = new ArrayList<>(subnetConfigs.size());

        for (BridgeDhcpZkManager.Subnet subnetConfig : subnetConfigs) {
            subnets.add(dhcpSubnetsGet(bridgeId, subnetConfig.getSubnetAddr()));
        }

        return subnets;
    }

    @Override
    public @CheckForNull org.midonet.cluster.data.dhcp.Host dhcpHostsGet(
            UUID bridgeId, IPv4Subnet subnet, String mac)
            throws StateAccessException, SerializationException {

        org.midonet.cluster.data.dhcp.Host host = null;
        if (dhcpZkManager.existsHost(bridgeId, subnet, mac)) {
            BridgeDhcpZkManager.Host hostConfig =
                    dhcpZkManager.getHost(bridgeId, subnet, mac);
            host = Converter.fromDhcpHostConfig(hostConfig);
            host.setId(MAC.fromString(mac));
        }

        return host;
    }

    @Override
    public
    List<org.midonet.cluster.data.dhcp.Host> dhcpHostsGetBySubnet(
            UUID bridgeId, IPv4Subnet subnet)
            throws StateAccessException, SerializationException {

        List<BridgeDhcpZkManager.Host> hostConfigs =
                dhcpZkManager.getHosts(bridgeId, subnet);
        List<org.midonet.cluster.data.dhcp.Host> hosts =
                new ArrayList<>();
        for (BridgeDhcpZkManager.Host hostConfig : hostConfigs) {
            hosts.add(Converter.fromDhcpHostConfig(hostConfig));
        }

        return hosts;
    }

    public @CheckForNull Subnet6 dhcpSubnet6Get(UUID bridgeId,
                                                IPv6Subnet prefix)
            throws StateAccessException, SerializationException {

        Subnet6 subnet = null;
        if (dhcpV6ZkManager.existsSubnet6(bridgeId, prefix)) {
            BridgeDhcpV6ZkManager.Subnet6 subnetConfig =
                dhcpV6ZkManager.getSubnet6(bridgeId, prefix);

            subnet = Converter.fromDhcpSubnet6Config(subnetConfig);
            subnet.setPrefix(prefix);
        }

        return subnet;
    }

    @Override
    public List<Subnet6> dhcpSubnet6sGetByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException {

        List<IPv6Subnet> subnet6Configs =
            dhcpV6ZkManager.listSubnet6s(bridgeId);
        List<Subnet6> subnets = new ArrayList<>(subnet6Configs.size());

        for (IPv6Subnet subnet6Config : subnet6Configs) {
            subnets.add(dhcpSubnet6Get(bridgeId, subnet6Config));
        }

        return subnets;
    }

    @Override
    public @CheckForNull V6Host dhcpV6HostGet(
            UUID bridgeId, IPv6Subnet prefix, String clientId)
            throws StateAccessException, SerializationException {

        V6Host host = null;
        if (dhcpV6ZkManager.existsHost(bridgeId, prefix, clientId)) {
            BridgeDhcpV6ZkManager.Host hostConfig =
                    dhcpV6ZkManager.getHost(bridgeId, prefix, clientId);
            host = Converter.fromDhcpV6HostConfig(hostConfig);
            host.setId(clientId);
        }

        return host;
    }

    @Override
    public
    List<V6Host> dhcpV6HostsGetByPrefix(
            UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException, SerializationException {

        List<BridgeDhcpV6ZkManager.Host> hostConfigs =
                dhcpV6ZkManager.getHosts(bridgeId, prefix);
        List<V6Host> hosts = new ArrayList<>();

        for (BridgeDhcpV6ZkManager.Host hostConfig : hostConfigs) {
            hosts.add(Converter.fromDhcpV6HostConfig(hostConfig));
        }

        return hosts;
    }

    @Override
    public @CheckForNull Host hostsGet(UUID hostId)
            throws StateAccessException, SerializationException {

        Host host = null;
        if (hostsExists(hostId)) {
            HostDirectory.Metadata hostMetadata = hostZkManager.get(hostId);
            if (hostMetadata == null) {
                log.error("Failed to fetch metadata for host {}", hostId);
                return null;
            }
            Integer floodingProxyWeight =
                hostZkManager.getFloodingProxyWeight(hostId);

            host = Converter.fromHostConfig(hostMetadata);
            host.setId(hostId);
            host.setIsAlive(hostsIsAlive(hostId));
            host.setInterfaces(interfacesGetByHost(hostId));

            /* The flooding proxy weight might have not been initialized
             * for this host; if so, leave the default value set by Host
             * constructor; otherwise, set the stored value. */
            if (floodingProxyWeight != null)
                host.setFloodingProxyWeight(floodingProxyWeight);
        }

        return host;
    }

    @Override
    public boolean hostsExists(UUID hostId) throws StateAccessException {
        return hostZkManager.exists(hostId);
    }

    @Override
    public boolean hostsIsAlive(UUID hostId) throws StateAccessException {
        return hostZkManager.isAlive(hostId);
    }

    @Override
    public boolean hostsHasPortBindings(UUID hostId)
            throws StateAccessException {
        return hostZkManager.hasPortBindings(hostId);
    }

    @Override
    public List<Host> hostsGetAll()
            throws StateAccessException {
        Collection<UUID> ids = hostZkManager.getHostIds();

        List<Host> hosts = new ArrayList<>();

        for (UUID id : ids) {
            try {
                Host host = hostsGet(id);
                if (host != null) {
                    hosts.add(host);
                }
            } catch (StateAccessException | SerializationException e) {
                log.warn("Cannot get host {} while enumerating hosts", id);
            }
        }

        return hosts;
    }

    @Override
    public List<Interface> interfacesGetByHost(UUID hostId)
            throws StateAccessException, SerializationException {
        List<Interface> interfaces = new ArrayList<>();

        Collection<String> interfaceNames =
                hostZkManager.getInterfaces(hostId);
        for (String interfaceName : interfaceNames) {
            try {
                Interface anInterface = interfacesGet(hostId, interfaceName);
                if (anInterface != null) {
                    interfaces.add(anInterface);
                }
            } catch (StateAccessException e) {
                log.warn(
                        "An interface description went missing in action while "
                                + "we were looking for it host: {}, interface: "
                                + "{}.", hostId, interfaceName, e);
            }
        }

        return interfaces;
    }

    @Override
    public @CheckForNull Interface interfacesGet(UUID hostId,
                                                 String interfaceName)
            throws StateAccessException, SerializationException {
        Interface anInterface = null;

        if (hostZkManager.existsInterface(hostId, interfaceName)) {
            HostDirectory.Interface interfaceData =
                    hostZkManager.getInterfaceData(hostId, interfaceName);
            anInterface = Converter.fromHostInterfaceConfig(interfaceData);
            anInterface.setId(interfaceName);
        }

        return anInterface;
    }

    @Override
    public List<VirtualPortMapping> hostsGetVirtualPortMappingsByHost(
            UUID hostId) throws StateAccessException, SerializationException {
        Set<HostDirectory.VirtualPortMapping> zkMaps =
                hostZkManager.getVirtualPortMappings(hostId, null);

        List<VirtualPortMapping> maps = new ArrayList<>(zkMaps.size());

        for(HostDirectory.VirtualPortMapping zkMap : zkMaps) {
            maps.add(Converter.fromHostVirtPortMappingConfig(zkMap));
        }

        return maps;
    }

    @Override
    public boolean hostsVirtualPortMappingExists(UUID hostId, UUID portId)
            throws StateAccessException {
        return hostZkManager.virtualPortMappingExists(hostId, portId);
    }

    @Override
    public @CheckForNull VirtualPortMapping hostsGetVirtualPortMapping(
            UUID hostId, UUID portId)
            throws StateAccessException, SerializationException {
        HostDirectory.VirtualPortMapping mapping =
                hostZkManager.getVirtualPortMapping(hostId, portId);

        if (mapping == null) {
            return null;
        }

        return Converter.fromHostVirtPortMappingConfig(mapping);
    }

    @Override
    public boolean portsExists(UUID id) throws StateAccessException {
        return portZkManager.exists(id);
    }

    @Override
    public List<BridgePort> portsFindByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException {

        Collection<UUID> ids = portZkManager.getBridgePortIDs(bridgeId);
        List<BridgePort> ports = new ArrayList<>();
        for (UUID id : ids) {
            Port<?, ?> port = portsGet(id);
            if (port instanceof BridgePort) {
                // Skip the VxLanPort, since it's not really a
                // BridgePort and is accessible in other ways.
                ports.add((BridgePort) portsGet(id));
            }
        }

        ids = portZkManager.getBridgeLogicalPortIDs(bridgeId);
        for (UUID id : ids) {
            ports.add((BridgePort) portsGet(id));
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsFindPeersByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException {

        Collection<UUID> ids = portZkManager.getBridgeLogicalPortIDs(bridgeId);
        List<Port<?, ?>> ports = new ArrayList<>();
        for (UUID id : ids) {
            Port<?, ?> portData = portsGet(id);
            if (portData.getPeerId() != null) {
                ports.add(portsGet(portData.getPeerId()));
            }
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsFindByRouter(UUID routerId)
            throws StateAccessException, SerializationException {

        Collection<UUID> ids = portZkManager.getRouterPortIDs(routerId);
        List<Port<?, ?>> ports = new ArrayList<>();
        for (UUID id : ids) {
            ports.add(portsGet(id));
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsFindPeersByRouter(UUID routerId)
            throws StateAccessException, SerializationException {

        Collection<UUID> ids = portZkManager.getRouterPortIDs(routerId);
        List<Port<?, ?>> ports = new ArrayList<>();
        for (UUID id : ids) {
            Port<?, ?> portData = portsGet(id);
            if (portData.getPeerId() != null) {
                ports.add(portsGet(portData.getPeerId()));
            }
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsGetAll()
            throws StateAccessException, SerializationException {
        log.debug("portsGetAll entered");
        List<Port<?, ?>> ports = new ArrayList<>();

        String path = pathBuilder.getPortsPath();
        if (zkManager.exists(path)) {
            Set<String> portIds = zkManager.getChildren(path);
            for (String id : portIds) {
                Port<?, ?> port = portsGet(UUID.fromString(id));
                if (port != null) {
                    ports.add(port);
                }
            }
        }

        log.debug("portsGetAll exiting: {} routers found", ports.size());
        return ports;
    }

    @Override
    public @CheckForNull Port<?,?> portsGet(UUID id)
            throws StateAccessException, SerializationException {
        Port<?,?> port = null;
        if (portZkManager.exists(id)) {
            port = Converter.fromPortConfig(portZkManager.get(id));
            port.setActive(portZkManager.isActivePort(id));
            port.setId(id);
        }

        return port;
    }

    @Override
    public List<Port<?, ?>> portsFindByPortGroup(UUID portGroupId)
            throws StateAccessException, SerializationException {
        Set<UUID> portIds = portZkManager.getPortGroupPortIds(portGroupId);
        List<Port<?, ?>> ports = new ArrayList<>(portIds.size());
        for (UUID portId : portIds) {
            ports.add(portsGet(portId));
        }

        return ports;
    }

    @Override
    public IpAddrGroup ipAddrGroupsGet(UUID id)
            throws StateAccessException, SerializationException {
        IpAddrGroup g = Converter.fromIpAddrGroupConfig(
            ipAddrGroupZkManager.get(id));
        g.setId(id);
        return g;
    }

    @Override
    public boolean ipAddrGroupsExists(UUID id) throws StateAccessException {
        return ipAddrGroupZkManager.exists(id);
    }

    @Override
    public List<IpAddrGroup> ipAddrGroupsGetAll()
            throws StateAccessException, SerializationException {
        Set<UUID> ids = ipAddrGroupZkManager.getAllIds();

        List<IpAddrGroup> groups = new ArrayList<>();
        for (UUID id : ids) {
            IpAddrGroupZkManager.IpAddrGroupConfig config =
                    ipAddrGroupZkManager.get(id);
            IpAddrGroup group = Converter.fromIpAddrGroupConfig(config);
            group.setId(id);
            groups.add(group);
        }
        return groups;
    }

    @Override
    public boolean ipAddrGroupHasAddr(UUID id, String addr)
            throws StateAccessException {
        return ipAddrGroupZkManager.isMember(id, addr);
    }

    @Override
    public Set<String> getAddrsByIpAddrGroup(UUID id)
            throws StateAccessException, SerializationException {
        return ipAddrGroupZkManager.getAddrs(id);
    }

    @Override
    public List<PortGroup> portGroupsFindByTenant(String tenantId)
            throws StateAccessException, SerializationException {
        log.debug("portGroupsFindByTenant entered: tenantId={}", tenantId);

        List<PortGroup> portGroups = portGroupsGetAll();
        for (Iterator<PortGroup> it = portGroups.iterator(); it.hasNext();) {
            if (!it.next().hasTenantId(tenantId)) {
                it.remove();
            }
        }

        log.debug("portGroupsFindByTenant exiting: {} portGroups found",
                  portGroups.size());
        return portGroups;
    }

    @Override
    public List<PortGroup> portGroupsFindByPort(UUID portId)
            throws StateAccessException, SerializationException {
        log.debug("portGroupsFindByPort entered: portId={}", portId);

        List<PortGroup> portGroups = new ArrayList<>();

        if (portsExists(portId)) {
            for (UUID portGroupId : portsGet(portId).getPortGroups()) {
                PortGroup portGroup = portGroupsGet(portGroupId);
                if (portGroup != null) {
                    portGroups.add(portGroup);
                }
            }
        }

        log.debug("portGroupsFindByPort exiting: {} portGroups found",
                  portGroups.size());
        return portGroups;
    }

    @Override
    public boolean portGroupsIsPortMember(@Nonnull UUID id,
                                          @Nonnull UUID portId)
        throws StateAccessException {
        return portGroupZkManager.portIsMember(id, portId);
    }

    @Override
    public boolean portGroupsExists(UUID id) throws StateAccessException {
        return portGroupZkManager.exists(id);
    }

    @Override
    public List<PortGroup> portGroupsGetAll() throws StateAccessException,
            SerializationException {
        log.debug("portGroupsGetAll entered");
        List<PortGroup> portGroups = new ArrayList<>();

        String path = pathBuilder.getPortGroupsPath();
        if (zkManager.exists(path)) {
            Set<String> portGroupIds = zkManager.getChildren(path);
            for (String id : portGroupIds) {
                PortGroup portGroup = portGroupsGet(UUID.fromString(id));
                if (portGroup != null) {
                    portGroups.add(portGroup);
                }
            }
        }

        log.debug("portGroupsGetAll exiting: {} port groups found",
                  portGroups.size());
        return portGroups;
    }

    @Override
    @CheckForNull
    public PortGroup portGroupsGet(UUID id)
            throws StateAccessException, SerializationException {
        log.debug("Entered: id={}", id);

        PortGroup portGroup = null;
        if (portGroupZkManager.exists(id)) {
            portGroup = Converter.fromPortGroupConfig(
                    portGroupZkManager.get(id));
            portGroup.setId(id);
        }

        log.debug("Exiting: portGroup={}", portGroup);
        return portGroup;
    }

    /* load balancer related methods */

    @Override
    @CheckForNull
    public LoadBalancer loadBalancerGet(UUID id)
        throws StateAccessException, SerializationException {
        LoadBalancer loadBalancer = null;
        if (loadBalancerZkManager.exists(id)) {
            loadBalancer = Converter.fromLoadBalancerConfig(
                loadBalancerZkManager.get(id));
            loadBalancer.setId(id);
        }

        return loadBalancer;
    }

    @Override
    public List<LoadBalancer> loadBalancersGetAll()
        throws StateAccessException, SerializationException {
        List<LoadBalancer> loadBalancers = new ArrayList<>();

        String path = pathBuilder.getLoadBalancersPath();
        if (zkManager.exists(path)) {
            Set<String> loadBalancerIds = zkManager.getChildren(path);
            for (String id : loadBalancerIds) {
                LoadBalancer loadBalancer =
                    loadBalancerGet(UUID.fromString(id));
                if (loadBalancer != null) {
                    loadBalancers.add(loadBalancer);
                }
            }
        }

        return loadBalancers;
    }

    @Override
    public List<Pool> loadBalancerGetPools(UUID id)
        throws StateAccessException, SerializationException {
        Set<UUID> poolIds = loadBalancerZkManager.getPoolIds(id);
        List<Pool> pools = new ArrayList<>(poolIds.size());
        for (UUID poolId : poolIds) {
            Pool pool = Converter.fromPoolConfig(poolZkManager.get(poolId));
            pool.setId(poolId);
            pools.add(pool);
        }

        return pools;
    }

    @Override
    public List<VIP> loadBalancerGetVips(UUID id)
        throws StateAccessException, SerializationException {
        Set<UUID> vipIds = loadBalancerZkManager.getVipIds(id);
        List<VIP> vips = new ArrayList<>(vipIds.size());
        for (UUID vipId : vipIds) {
            VIP vip = Converter.fromVipConfig(vipZkManager.get(vipId));
            vip.setId(vipId);
            vips.add(vip);
        }
        return vips;
    }

    private void validatePoolConfigMappingStatus(UUID poolId)
        throws MappingStatusException, StateAccessException,
               SerializationException {
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
        if (poolConfig.isImmutable()) {
            throw new MappingStatusException(
                poolConfig.mappingStatus.toString());
        }
    }

    /*
     * Returns the pair of the mapping path and the mapping config. If the
     * given pool is not associated with any health monitor, it returns `null`.
     */
    private MutablePair<String, PoolHealthMonitorConfig>
    preparePoolHealthMonitorMappings(
        @Nonnull UUID poolId,
        @Nonnull PoolZkManager.PoolConfig poolConfig,
        ConfigGetter<UUID, PoolMemberZkManager.PoolMemberConfig> poolMemberConfigGetter,
        ConfigGetter<UUID, VipZkManager.VipConfig> vipConfigGetter)
        throws MappingStatusException, SerializationException,
               StateAccessException {
        UUID healthMonitorId = poolConfig.healthMonitorId;
        // If the health monitor ID is null, the mapping should not be created
        // and therefore `null` is returned.
        if (healthMonitorId == null) {
            return null;
        }
        // If `mappingStatus` property of Pool is in PENDING_*, it throws the
        // exception and prevent the mapping from being updated.
        validatePoolConfigMappingStatus(poolId);

        String mappingPath = pathBuilder.getPoolHealthMonitorMappingsPath(
            poolId, healthMonitorId);

        assert poolConfig.loadBalancerId != null;
        UUID loadBalancerId = checkNotNull(
            poolConfig.loadBalancerId, "LoadBalancer ID is null.");
        LoadBalancerConfigWithId loadBalancerConfig =
            new LoadBalancerConfigWithId(
                loadBalancerZkManager.get(loadBalancerId));

        List<UUID> memberIds = poolZkManager.getMemberIds(poolId);
        List<PoolMemberConfigWithId> memberConfigs =
            new ArrayList<>(memberIds.size());
        for (UUID memberId : memberIds) {
            PoolMemberZkManager.PoolMemberConfig config =
                poolMemberConfigGetter.get(memberId);
            if (config != null) {
                config.id = memberId;
                memberConfigs.add(new PoolMemberConfigWithId(config));
            }
        }

        List<UUID> vipIds = poolZkManager.getVipIds(poolId);
        List<VipConfigWithId> vipConfigs =
            new ArrayList<>(vipIds.size());
        for (UUID vipId : vipIds) {
            VipZkManager.VipConfig config = vipConfigGetter.get(vipId);
            if (config != null) {
                config.id = vipId;
                vipConfigs.add(new VipConfigWithId(config));
            }
        }

        HealthMonitorConfigWithId healthMonitorConfig =
            new HealthMonitorConfigWithId(
                healthMonitorZkManager.get(healthMonitorId));

        PoolHealthMonitorConfig mappingConfig = new PoolHealthMonitorConfig(
            loadBalancerConfig, vipConfigs, memberConfigs, healthMonitorConfig);
        return new MutablePair<>(mappingPath, mappingConfig);
    }

    private List<Op> preparePoolHealthMonitorMappingUpdate(
        Pair<String, PoolHealthMonitorConfig> pair)
        throws SerializationException {
        List<Op> ops = new ArrayList<>();
        if (pair != null) {
            String mappingPath = pair.getLeft();
            PoolHealthMonitorConfig mappingConfig = pair.getRight();
            ops.add(Op.setData(mappingPath,
                               serializer.serialize(mappingConfig), -1));
        }
        return ops;
    }

    /* health monitors related methods */
    private List<Pair<String, PoolHealthMonitorConfig>>
    buildPoolHealthMonitorMappings(UUID healthMonitorId,
                                   @Nullable HealthMonitorZkManager.HealthMonitorConfig config)
        throws MappingStatusException, SerializationException,
               StateAccessException {
        List<UUID> poolIds = healthMonitorZkManager.getPoolIds(healthMonitorId);
        List<Pair<String, PoolHealthMonitorConfig>> pairs = new ArrayList<>();

        for (UUID poolId : poolIds) {
            PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
            MutablePair<String, PoolHealthMonitorConfig> pair =
                preparePoolHealthMonitorMappings(poolId, poolConfig,
                                                 poolMemberZkManager,
                                                 vipZkManager);

            if (pair != null) {
                // Update health monitor config, which can be null
                PoolHealthMonitorConfig updatedMappingConfig = pair.getRight();
                updatedMappingConfig.healthMonitorConfig =
                    new HealthMonitorConfigWithId(config);
                pair.setRight(updatedMappingConfig);
                pairs.add(pair);
            }
        }
        return pairs;
    }

    @Override
    @CheckForNull
    public HealthMonitor healthMonitorGet(UUID id)
        throws StateAccessException, SerializationException {
        HealthMonitor healthMonitor = null;
        if (healthMonitorZkManager.exists(id)) {
            healthMonitor = Converter.fromHealthMonitorConfig(
                healthMonitorZkManager.get(id));
            healthMonitor.setId(id);
        }

        return healthMonitor;
    }

    @Override
    public List<HealthMonitor> healthMonitorsGetAll()
        throws StateAccessException, SerializationException {
        List<HealthMonitor> healthMonitors = new ArrayList<>();

        String path = pathBuilder.getHealthMonitorsPath();
        if (zkManager.exists(path)) {
            Set<String> healthMonitorIds = zkManager.getChildren(path);
            for (String id : healthMonitorIds) {
                HealthMonitor healthMonitor
                    = healthMonitorGet(UUID.fromString(id));
                if (healthMonitor != null) {
                    healthMonitors.add(healthMonitor);
                }
            }
        }

        return healthMonitors;
    }

    @Override
    public List<Pool> healthMonitorGetPools(@Nonnull UUID id)
        throws StateAccessException, SerializationException {
        List<UUID> poolIds = healthMonitorZkManager.getPoolIds(id);
        List<Pool> pools = new ArrayList<>(poolIds.size());
        for (UUID poolId : poolIds) {
            Pool pool = Converter.fromPoolConfig(poolZkManager.get(poolId));
            pool.setId(poolId);
            pools.add(pool);
        }
        return pools;
    }

    /* pool member related methods */
    private Pair<String, PoolHealthMonitorConfig>
    buildPoolHealthMonitorMappings(final UUID poolMemberId,
                                   final @Nonnull PoolMemberZkManager.PoolMemberConfig config,
                                   final boolean deletePoolMember)
        throws MappingStatusException, SerializationException,
               StateAccessException {
        UUID poolId = checkNotNull(config.poolId, "Pool ID is null.");
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);

        // Since we haven't deleted/updated this PoolMember in Zookeeper yet,
        // preparePoolHealthMonitorMappings() will get an outdated version of
        // this PoolMember when it fetches the Pool's members. The ConfigGetter
        // intercepts the request for this PoolMember and returns the updated
        // PoolMemberConfig, or null if it's been deleted.
        ConfigGetter<UUID, PoolMemberZkManager.PoolMemberConfig> configGetter =
            new ConfigGetter<UUID, PoolMemberZkManager.PoolMemberConfig>() {
                @Override
                public PoolMemberZkManager.PoolMemberConfig get(UUID key)
                    throws StateAccessException, SerializationException {
                    if (key.equals(poolMemberId)) {
                        return deletePoolMember ? null : config;
                    }
                    return poolMemberZkManager.get(key);
                }
            };

        return preparePoolHealthMonitorMappings(
            poolId, poolConfig, configGetter, vipZkManager);
    }

    private List<Op> buildPoolMappingStatusUpdate(
        PoolMemberZkManager.PoolMemberConfig poolMemberConfig)
        throws StateAccessException, SerializationException {
        UUID poolId = poolMemberConfig.poolId;
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
        // Indicate the mapping is being updated.
        poolConfig.mappingStatus = PoolHealthMonitorMappingStatus.PENDING_UPDATE;
        return Arrays.asList(Op.setData(pathBuilder.getPoolPath(poolId),
                                        serializer.serialize(poolConfig), -1));
    }

    @Override
    @CheckForNull
    public boolean poolMemberExists(UUID id)
        throws StateAccessException {
        return poolMemberZkManager.exists(id);
    }

    @Override
    @CheckForNull
    public PoolMember poolMemberGet(UUID id)
        throws StateAccessException, SerializationException {
        PoolMember poolMember = null;
        if (poolMemberZkManager.exists(id)) {
            poolMember =
                Converter.fromPoolMemberConfig(poolMemberZkManager.get(id));
            poolMember.setId(id);
        }

        return poolMember;
    }

    @Override
    public List<PoolMember> poolMembersGetAll()
        throws StateAccessException, SerializationException {
        List<PoolMember> poolMembers = new ArrayList<>();

        String path = pathBuilder.getPoolMembersPath();
        if (zkManager.exists(path)) {
            Set<String> poolMemberIds = zkManager.getChildren(path);
            for (String id : poolMemberIds) {
                PoolMember poolMember = poolMemberGet(UUID.fromString(id));
                if (poolMember != null) {
                    poolMembers.add(poolMember);
                }
            }
        }

        return poolMembers;
    }


    /* pool related methods */
    private List<Op> prepareDeletePoolHealthMonitorMappingOps(UUID poolId)
        throws SerializationException, StateAccessException {
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
        List<Op> ops = new ArrayList<>();
        if (poolConfig.healthMonitorId != null) {
            ops.add(Op.delete(pathBuilder.getPoolHealthMonitorMappingsPath(
                poolId, poolConfig.healthMonitorId), -1));
        }
        return ops;
    }

    @Override
    @CheckForNull
    public Pool poolGet(UUID id)
        throws StateAccessException, SerializationException {
        Pool pool = null;
        if (poolZkManager.exists(id)) {
            pool = Converter.fromPoolConfig(poolZkManager.get(id));
            pool.setId(id);
        }

        return pool;
    }

    @Override
    public List<Pool> poolsGetAll() throws StateAccessException,
                                           SerializationException {
        List<Pool> pools = new ArrayList<>();

        String path = pathBuilder.getPoolsPath();
        if (zkManager.exists(path)) {
            Set<String> poolIds = zkManager.getChildren(path);
            for (String id : poolIds) {
                Pool pool = poolGet(UUID.fromString(id));
                if (pool != null) {
                    pools.add(pool);
                }
            }
        }

        return pools;
    }

    @Override
    public List<PoolMember> poolGetMembers(@Nonnull UUID id)
        throws StateAccessException, SerializationException {
        List<UUID> memberIds = poolZkManager.getMemberIds(id);
        List<PoolMember> members = new ArrayList<>(memberIds.size());
        for (UUID memberId : memberIds) {
            PoolMember member = Converter.fromPoolMemberConfig(
                poolMemberZkManager.get(memberId));
            member.setId(memberId);
            members.add(member);
        }
        return members;
    }

    @Override
    public List<VIP> poolGetVips(@Nonnull UUID id)
        throws StateAccessException, SerializationException {
        List<UUID> vipIds = poolZkManager.getVipIds(id);
        List<VIP> vips = new ArrayList<>(vipIds.size());
        for (UUID vipId : vipIds) {
            VIP vip = Converter.fromVipConfig(vipZkManager.get(vipId));
            vip.setId(vipId);
            vips.add(vip);
        }
        return vips;
    }

    @Override
    public void poolSetMapStatus(UUID id,
                                 PoolHealthMonitorMappingStatus status)
        throws StateAccessException, SerializationException {
        PoolZkManager.PoolConfig pool = poolZkManager.get(id);
        if (pool == null) {
            return;
        }
        pool.mappingStatus = status;
        zkManager.multi(poolZkManager.prepareUpdate(id, pool));
    }

    private Pair<String, PoolHealthMonitorConfig>
    buildPoolHealthMonitorMappings(final UUID vipId,
                                   final @Nonnull VipZkManager.VipConfig config,
                                   final boolean deleteVip)
        throws MappingStatusException, SerializationException,
               StateAccessException {
        UUID poolId = checkNotNull(config.poolId, "Pool ID is null.");
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);

        // Since we haven't deleted/updated this VIP in Zookeeper yet,
        // preparePoolHealthMonitorMappings() will get an outdated version of
        // this VIP when it fetches the Pool's vips. The ConfigGetter
        // intercepts the request for this VIP and returns the updated
        // VipConfig, or null if it's been deleted.
        ConfigGetter<UUID, VipZkManager.VipConfig> configGetter =
            new ConfigGetter<UUID, VipZkManager.VipConfig>() {
                @Override
                public VipZkManager.VipConfig get(UUID key)
                    throws StateAccessException, SerializationException {
                    if (key.equals(vipId)) {
                        return deleteVip ? null : config;
                    }
                    return vipZkManager.get(key);
                }
            };

        return preparePoolHealthMonitorMappings(
            poolId, poolConfig, poolMemberZkManager, configGetter);
    }

    private List<Op> buildPoolMappingStatusUpdate(
        VipZkManager.VipConfig vipConfig)
        throws StateAccessException, SerializationException {
        UUID poolId = vipConfig.poolId;
        PoolZkManager.PoolConfig poolConfig = poolZkManager.get(poolId);
        // Indicate the mapping is being updated.
        poolConfig.mappingStatus =
            PoolHealthMonitorMappingStatus.PENDING_UPDATE;
        return Arrays.asList(Op.setData(pathBuilder.getPoolPath(poolId),
                                        serializer.serialize(poolConfig), -1));
    }

    @Override
    @CheckForNull
    public VIP vipGet(UUID id)
        throws StateAccessException, SerializationException {
        VIP vip = null;
        if (vipZkManager.exists(id)) {
            vip = Converter.fromVipConfig(vipZkManager.get(id));
            vip.setId(id);
        }
        return vip;
    }

    @Override
    public List<VIP> vipGetAll()
        throws StateAccessException, SerializationException {
        List<VIP> vips = new ArrayList<>();

        String path = pathBuilder.getVipsPath();
        if (zkManager.exists(path)) {
            Set<String> vipIds = zkManager.getChildren(path);
            for (String id : vipIds) {
                VIP vip = vipGet(UUID.fromString(id));
                if (vip != null) {
                    vips.add(vip);
                }
            }
        }

        return vips;
    }

    @Override
    public @CheckForNull Route routesGet(UUID id)
            throws StateAccessException, SerializationException {
        log.debug("Entered: id={}", id);

        Route route = null;
        if (routeZkManager.exists(id)) {
            route = Converter.fromRouteConfig(routeZkManager.get(id));
            route.setId(id);
        }

        log.debug("Exiting: route={}", route);
        return route;
    }

    @Override
    public void routesDelete(UUID id)
            throws StateAccessException, SerializationException {
        routeZkManager.delete(id);
    }

    @Override
    public UUID routesCreate(@Nonnull Route route)
            throws StateAccessException, SerializationException {
        return routeZkManager.create(Converter.toRouteConfig(route));
    }

    @Override
    public UUID routesCreateEphemeral(@Nonnull Route route)
            throws StateAccessException, SerializationException {
        return routeZkManager.create(Converter.toRouteConfig(route), false);
    }

    @Override
    public List<Route> routesFindByRouter(UUID routerId)
            throws StateAccessException, SerializationException {

        List<UUID> routeIds = routeZkManager.list(routerId);
        List<Route> routes = new ArrayList<>();
        for (UUID id : routeIds) {
            routes.add(routesGet(id));
        }
        return routes;

    }

    @Override
    public boolean routerExists(UUID id) throws StateAccessException {
        return routerZkManager.exists(id);
    }

    @Override
    public List<Router> routersGetAll() throws StateAccessException,
            SerializationException {
        log.debug("routersGetAll entered");
        List<Router> routers = new ArrayList<>();

        String path = pathBuilder.getRoutersPath();
        if (zkManager.exists(path)) {
            Set<String> routerIds = zkManager.getChildren(path);
            for (String id : routerIds) {
                Router router = routersGet(UUID.fromString(id));
                if (router != null) {
                    routers.add(router);
                }
            }
        }

        log.debug("routersGetAll exiting: {} routers found", routers.size());
        return routers;
    }

    @Override
    public @CheckForNull Router routersGet(UUID id)
            throws StateAccessException, SerializationException {
        log.debug("Entered: id={}", id);

        Router router = null;
        if (routerZkManager.exists(id)) {
            router = Converter.fromRouterConfig(routerZkManager.get(id));
            router.setId(id);
        }

        log.debug("Exiting: router={}", router);
        return router;
    }

    @Override
    public List<Router> routersFindByTenant(String tenantId) throws StateAccessException,
        SerializationException {
        log.debug("routersFindByTenant entered: tenantId={}", tenantId);

        List<Router> routers = routersGetAll();

        for (Iterator<Router> it = routers.iterator(); it.hasNext();) {
            if (!it.next().hasTenantId(tenantId)) {
                it.remove();
            }
        }

        log.debug("routersFindByTenant exiting: {} routers found",
                  routers.size());
        return routers;
    }

    @Override
    public @CheckForNull Rule<?, ?> rulesGet(UUID id)
            throws StateAccessException, SerializationException {
        log.debug("Entered: id={}", id);

        Rule<?,?> rule = null;
        if (ruleZkManager.exists(id)) {
            rule = Converter.fromRuleConfig(ruleZkManager.get(id));
            rule.setId(id);
            // Find position of rule in chain
            RuleList ruleList = ruleZkManager.getRuleList(rule.getChainId());
            int position = ruleList.getRuleList().indexOf(id) + 1;
            rule.setPosition(position);
        }

        log.debug("Exiting: rule={}", rule);
        return rule;
    }

    @Override
    public void rulesDelete(UUID id)
            throws StateAccessException, SerializationException {
        ruleZkManager.delete(id);
    }

    @Override
    public UUID rulesCreate(@Nonnull Rule<?, ?> rule)
            throws StateAccessException, RuleIndexOutOfBoundsException,
            SerializationException {
        return ruleZkManager.create(rule.getId(), Converter.toRuleConfig(rule),
                                    rule.getPosition());
    }

    @Override
    public List<Rule<?, ?>> rulesFindByChain(UUID chainId)
            throws StateAccessException, SerializationException {
        List<UUID> ruleIds = ruleZkManager.getRuleList(chainId).getRuleList();
        List<Rule<?, ?>> rules = new ArrayList<>();

        int position = 1;
        for (UUID id : ruleIds) {
            Rule<?,?> rule = rulesGet(id);
            rule.setPosition(position);
            position++;
            rules.add(rule);
        }
        return rules;
    }

    @Override
    public Set<String> tenantsGetAll() throws StateAccessException {
        return tenantZkManager.list();
    }

    public WriteVersion writeVersionGet()
        throws StateAccessException {
        WriteVersion writeVersion = new WriteVersion();
        String version = systemDataProvider.getWriteVersion();
        writeVersion.setVersion(version);
        return writeVersion;
    }

    /**
     * Overwrites the current write version with the string supplied
     * @param newVersion The new version to set the write version to.
     */
    public void writeVersionUpdate(WriteVersion newVersion)
            throws StateAccessException {
        systemDataProvider.setWriteVersion(newVersion.getVersion());
    }

    /**
     * Get the system state info.
     *
     * @return System State info.
     * @throws StateAccessException
     */
    public SystemState systemStateGet()
            throws StateAccessException {
        SystemState systemState = new SystemState();
        boolean upgrade = systemDataProvider.systemUpgradeStateExists();
        boolean readonly = systemDataProvider.configReadOnly();
        String writeVersion = systemDataProvider.getWriteVersion();
        systemState.setState(upgrade ? SystemState.State.UPGRADE.toString()
                                     : SystemState.State.ACTIVE.toString());
        systemState.setAvailability(
            readonly ? SystemState.Availability.READONLY.toString()
                     : SystemState.Availability.READWRITE.toString());
        systemState.setWriteVersion(writeVersion);
        return systemState;
    }

    /**
     * Update the system state info.
     *
     * @param systemState the new system state
     * @throws StateAccessException
     */
    public void systemStateUpdate(SystemState systemState)
            throws StateAccessException {

        systemDataProvider.setOperationState(systemState.getState());
        systemDataProvider.setConfigState(systemState.getAvailability());
        systemDataProvider.setWriteVersion(systemState.getWriteVersion());
    }

    /**
     * Get the Host Version info
     *
     * @return A list containing HostVersion objects, each of which
     *   contains version information about a specific host.
     * @throws StateAccessException
     */
    public List<HostVersion> hostVersionsGet()
            throws StateAccessException {
        List<HostVersion> hostVersionList = new ArrayList<>();
        List<String> versions = systemDataProvider.getVersionsInDeployment();
        for (String version : versions) {
            List<String> hosts = systemDataProvider.getHostsWithVersion(version);
            for (String host : hosts) {
                HostVersion hostVersion = new HostVersion();
                hostVersion.setHostId(UUID.fromString(host));
                hostVersion.setVersion(version);
                hostVersionList.add(hostVersion);
            }
        }
        return hostVersionList;
    }

    /**
     * Trace request methods
     */
    @Override
    @CheckForNull
    public TraceRequest traceRequestGet(UUID id)
            throws StateAccessException, SerializationException {
        if (traceReqZkManager.exists(id)) {
            TraceRequestZkManager.TraceRequestConfig config
                = traceReqZkManager.get(id);
            if (config != null) {
                return Converter.fromTraceRequestConfig(config).setId(id);
            }
        }
        return null;
    }

    private boolean checkTraceRequestDeviceExists(TraceRequest traceRequest)
            throws StateAccessException, SerializationException {
        switch (traceRequest.getDeviceType()) {
            case ROUTER:
                return routersGet(traceRequest.getDeviceId()) != null;
            case BRIDGE:
                return bridgesGet(traceRequest.getDeviceId()) != null;
            case PORT:
                return portsGet(traceRequest.getDeviceId()) != null;
        }
        return false;
    }

    @Override
    public List<TraceRequest> traceRequestGetAll()
            throws StateAccessException, SerializationException {
        List<TraceRequest> traceRequests = new ArrayList<>();
        String path = pathBuilder.getTraceRequestsPath();
        if (zkManager.exists(path)) {
            Set<String> trIds = zkManager.getChildren(path);
            for (String id : trIds) {
                TraceRequest tr = traceRequestGet(UUID.fromString(id));
                if (tr != null) {
                    traceRequests.add(tr);
                }
            }
        }
        return traceRequests;
    }

    @Override
    public List<TraceRequest> traceRequestFindByTenant(String tenantId)
            throws StateAccessException, SerializationException {
        List<TraceRequest> traceRequests = traceRequestGetAll();
        Iterator<TraceRequest> iter = traceRequests.iterator();
        while (iter.hasNext()) {
            TraceRequest t = iter.next();
            String ownerId = null;
            switch (t.getDeviceType()) {
            case ROUTER:
                Router r = routersGet(t.getDeviceId());
                if (r == null
                    || !r.hasTenantId(tenantId)) {
                    iter.remove();
                }
                break;
            case BRIDGE:
                Bridge b = bridgesGet(t.getDeviceId());
                if (b == null
                    || !b.hasTenantId(tenantId)) {
                    iter.remove();
                }
                break;
            case PORT:
                Port p = portsGet(t.getDeviceId());
                if (p == null) {
                    iter.remove();
                } else {
                    UUID portDevice = p.getDeviceId();
                    Bridge bridge = bridgesGet(portDevice);
                    Router router = routersGet(portDevice);
                    if (bridge != null) {
                        if (!bridge.hasTenantId(tenantId)) {
                            iter.remove();
                        }
                    } else if (router != null) {
                        if (!router.hasTenantId(tenantId)) {
                            iter.remove();
                        }
                    } else {
                        // port doesn't have a device, so can't belong
                        // to a tenant
                        iter.remove();
                    }
                }
                break;
            default:
                // no device, so no owner
                iter.remove();
            }
        }
        return traceRequests;
    }

    /**
     * If the agent crashes after this is called but before it
     * is assigned to a device, a chain will be orphaned.
     */
    private UUID traceFindOrCreateChain(TraceRequest request)
            throws StateAccessException, SerializationException {
        String name = traceReqZkManager.traceRequestChainName(
                request.getId());
        for (Chain chain : chainsGetAll()) {
            if (chain.getName().equals(name)) {
                return chain.getId();
            }
        }
        return null;
    }

    private UUID traceFindOrCreateRule(UUID chainId, TraceRequest request,
                                       List<Op> ops)
            throws StateAccessException, SerializationException,
            RuleIndexOutOfBoundsException {
        for (Rule<?,?> r : rulesFindByChain(chainId)) {
            if (r instanceof TraceRule) {
                TraceRule rule = (TraceRule)r;
                if (Objects.equals(rule.getRequestId(), request.getId())) {
                    return rule.getId(); // only one rule per request for now
                }
            }
        }

        TraceRule rule = new TraceRule(request.getId(),
                                       request.getCondition(),
                                       request.getLimit());
        rule.setChainId(chainId).setPosition(1).setId(UUID.randomUUID());

        ops.addAll(ruleZkManager.prepareInsertPositionOrdering(
            rule.getId(), Converter.toRuleConfig(rule), 1));
        return rule.getId();
    }

    private List<Op> traceRequestPrepareDisable(TraceRequest request)
            throws StateAccessException, SerializationException {
        return traceReqZkManager.prepareDisable(request.getId());
    }

    public void traceRequestDisable(UUID id)
            throws StateAccessException, SerializationException {
        log.debug("Entered(traceRequestEnable): id={}", id);
        TraceRequest request = traceRequestGet(id);
        if (request == null) {
            throw new IllegalStateException("Trace request does not exist");
        }
        zkManager.multi(traceRequestPrepareDisable(request));
        log.debug("Exited(traceRequestDisable): id={}", id);
    }

    @Override
    public Integer getPrecedingHealthMonitorLeader(Integer myNode)
            throws StateAccessException {
        String path = pathBuilder.getHealthMonitorLeaderDirPath();
        Set<String> set = zkManager.getChildren(path);

        String seqNumPath
                = ZkUtil.getNextLowerSequenceNumberPath(set, myNode);
        return seqNumPath == null ? null :
                ZkUtil.getSequenceNumberFromPath(seqNumPath);
    }

    @Override
    public VTEP vtepGet(IPv4Addr ipAddr)
            throws StateAccessException, SerializationException {
        if (!vtepZkManager.exists(ipAddr))
            return null;

        VTEP vtep = Converter.fromVtepConfig(vtepZkManager.get(ipAddr));
        vtep.setId(ipAddr);
        return vtep;
    }

    @Override
    public List<VTEP> vtepsGetAll()
            throws StateAccessException, SerializationException {
        List<VTEP> vteps = new ArrayList<>();

        String path = pathBuilder.getVtepsPath();
        if (zkManager.exists(path)) {
            Set<String> vtepIps = zkManager.getChildren(path);
            for (String vtepIp : vtepIps) {
                VTEP vtep = vtepGet(IPv4Addr.fromString(vtepIp));
                if (vtep != null) {
                    vteps.add(vtep);
                }
            }
        }

        return vteps;
    }

    @Override
    public List<VtepBinding> vtepGetBindings(@Nonnull IPv4Addr ipAddr)
            throws StateAccessException {
        return vtepZkManager.getBindings(ipAddr);
    }

    @Override
    public List<VtepBinding> bridgeGetVtepBindings(@Nonnull UUID bridgeId,
                                                   IPv4Addr vtepMgmtIp)
        throws StateAccessException, SerializationException {
        List<VtepBinding> bindings = new ArrayList<>();
        Bridge br = bridgesGet(bridgeId);
        if (br == null || br.getVxLanPortIds() == null) {
            return bindings;
        }

        for (UUID id : br.getVxLanPortIds()) {  // NPE safe
            VxLanPort vxLanPort = (VxLanPort)portsGet(id);
            if (vxLanPort == null) {
                continue;
            }
            IPv4Addr portMgmtIp = vxLanPort.getMgmtIpAddr();
            if (vtepMgmtIp != null &&
                !portMgmtIp.equals(vtepMgmtIp)) {
                continue;
            }
            List<VtepBinding> all = vtepZkManager.getBindings(portMgmtIp);
            for (VtepBinding b : all) {
                if (b.getNetworkId().equals(bridgeId)) {
                    bindings.add(b);
                }
            }
        }
        return bindings;
    }

    @Override
    public VtepBinding vtepGetBinding(@Nonnull IPv4Addr ipAddr,
                                      @Nonnull String portName, short vlanId)
            throws StateAccessException {
        return vtepZkManager.getBinding(ipAddr, portName, vlanId);
    }

    /*
     * Utility method to use internaly. It assumes that the caller has verified
     * that both the bridge and port are non-null.
     *
     * It's convenient to slice this out so other methods can fetch the
     * parameters as they prefer, and then use this to retrieve the vxlan
     * tunnel endpoint. The method returns a null IP if the endpoint cannot
     * be retrieved for any reason (bridge unbound, etc.)
     *
     * Note that this method will return null if the bridge is not bound to
     * any VTEP. This is because we won't be able to figure out the tunnel zone
     * from which to extract the host's IP.
     *
     * @param b a bridge, expected to exist, be bound to a VTEP, and contain the
     *          given port.
     * @param port a bridge port on the given bridge, that must be exterior
     * @return the IP address of the bound host's membership in the VTEP's
     *         tunnel zone.
     */
    private IPv4Addr vxlanTunnelEndpointForInternal(Bridge b, BridgePort port)
        throws SerializationException, StateAccessException {

        // We can take a random vxlan port, since all are forced to be in the
        // same tz

        if (b.getVxLanPortIds() == null) {
            return null;
        }

        VxLanPort vxlanPort = null;
        int i = 0;
        while (vxlanPort == null && i < b.getVxLanPortIds().size()) {
            vxlanPort = (VxLanPort)portsGet(b.getVxLanPortIds().get(i++));
            if (vxlanPort == null) {
                log.warn("VxLanPort {} at bridge {} does not exist anymore",
                         b.getVxLanPortIds(), b.getId());
            }
        }

        if (vxlanPort == null) {
            log.warn("Can't find VTEP tunnel zone on bridge {} - is it even " +
                     "bound to a VTEP?", b.getId());
            return null;
        }

        IPv4Addr vtepMgmtIp = vxlanPort.getMgmtIpAddr();
        log.debug("Port's bridge {} is bound to one or more VTEPs", b.getId());

        // We will need the host where the given BridgePort is bound
        UUID hostId = port.getHostId();
        if (hostId == null) {
            log.error("Port {} is not bound to a host", port.getId());
            return null;
        }

        // Let's get the tunnel zone this host should be in, and get the IP of
        // the host in that tunnel zone.
        VTEP vtep = vtepGet(vtepMgmtIp);
        if (vtep == null) {
            log.warn("Vtep {} bound to bridge {} does not exist anymore",
                     vtepMgmtIp, b.getId());
            return null;
        }

        UUID tzId = vtep.getTunnelZoneId();
        TunnelZone.HostConfig hostCfg =
            this.tunnelZonesGetMembership(tzId, hostId);
        if (hostCfg == null) {
            log.error("Port {} on bridge {} bount to an interface in host {} " +
                      "was expected to belong to tunnel zone {} through " +
                      "binding to VTEP {}", port.getId(), b.getId(), hostId,
                      tzId, vtepMgmtIp);
            return null;
        }

        return hostCfg.getIp();
    }

    @Override
    public void vxLanPortIdsAsyncGet(DirectoryCallback<Set<UUID>> callback,
                                     Directory.TypedWatcher watcher)
            throws StateAccessException {
        portZkManager.getVxLanPortIdsAsync(callback, watcher);
    }

    @Override
    public Ip4ToMacReplicatedMap getIp4MacMap(UUID bridgeId)
        throws StateAccessException {
        return new Ip4ToMacReplicatedMap(
            bridgeZkManager.getIP4MacMapDirectory(bridgeId)
        );
    }
}
