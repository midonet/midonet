/*
 * Copyright (c) 2012-2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import javax.inject.Inject;
import javax.inject.Named;

import com.google.common.base.Function;

import org.apache.commons.lang.StringUtils;
import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.data.AdRoute;
import org.midonet.cluster.data.BGP;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Chain;
import org.midonet.cluster.data.ChainName;
import org.midonet.cluster.data.Converter;
import org.midonet.cluster.data.Entity.TaggableEntity;
import org.midonet.cluster.data.HostVersion;
import org.midonet.cluster.data.IpAddrGroup;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.PortGroup;
import org.midonet.cluster.data.PortGroupName;
import org.midonet.cluster.data.Route;
import org.midonet.cluster.data.Router;
import org.midonet.cluster.data.Rule;
import org.midonet.cluster.data.SystemState;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.VtepBinding;
import org.midonet.cluster.data.WriteVersion;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.data.host.Command;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.Interface;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.VlanMacPort;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.SystemDataProvider;
import org.midonet.midolman.guice.zookeeper.ZKConnectionProvider;
import org.midonet.midolman.host.commands.HostCommandGenerator;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.rules.RuleList;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.Ip4ToMacReplicatedMap;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.PortConfigCache;
import org.midonet.midolman.state.PortDirectory;
import org.midonet.midolman.state.PortDirectory.VxLanPortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkLeaderElectionWatcher;
import org.midonet.midolman.state.ZkManager;
import org.midonet.midolman.state.ZkUtil;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.midolman.state.zkManagers.AdRouteZkManager;
import org.midonet.midolman.state.zkManagers.BgpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpV6ZkManager;
import org.midonet.midolman.state.zkManagers.BridgeDhcpZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager;
import org.midonet.midolman.state.zkManagers.BridgeZkManager.BridgeConfig;
import org.midonet.midolman.state.zkManagers.ChainZkManager;
import org.midonet.midolman.state.zkManagers.IpAddrGroupZkManager;
import org.midonet.midolman.state.zkManagers.LicenseZkManager;
import org.midonet.midolman.state.zkManagers.PortGroupZkManager;
import org.midonet.midolman.state.zkManagers.PortSetZkManager;
import org.midonet.midolman.state.zkManagers.PortZkManager;
import org.midonet.midolman.state.zkManagers.RouteZkManager;
import org.midonet.midolman.state.zkManagers.RouterZkManager;
import org.midonet.midolman.state.zkManagers.RuleZkManager;
import org.midonet.midolman.state.zkManagers.TaggableConfig;
import org.midonet.midolman.state.zkManagers.TaggableConfigZkManager;
import org.midonet.midolman.state.zkManagers.TenantZkManager;
import org.midonet.midolman.state.zkManagers.TunnelZoneZkManager;
import org.midonet.midolman.state.zkManagers.VtepZkManager;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Subnet;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.MAC;
import org.midonet.util.collection.ListUtil;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.functors.Callback2;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;

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
    private TunnelZoneZkManager zonesZkManager;

    @Inject
    private PortSetZkManager portSetZkManager;

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
    private TaggableConfigZkManager taggableConfigZkManager;

    @Inject
    private SystemDataProvider systemDataProvider;

    @Inject
    private IpAddrGroupZkManager ipAddrGroupZkManager;

    @Inject
    private VtepZkManager vtepZkManager;

    @Inject
    private LicenseZkManager licenseZkManager;

    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    private Reactor reactor;

    final Queue<Callback2<UUID, Boolean>> subscriptionPortsActive =
        new ConcurrentLinkedQueue<Callback2<UUID, Boolean>>();

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
    public void adRoutesDelete(UUID id)
            throws StateAccessException, SerializationException {
        adRouteZkManager.delete(id);
    }

    @Override
    public UUID adRoutesCreate(@Nonnull AdRoute adRoute)
            throws StateAccessException, SerializationException {
        return adRouteZkManager.create(Converter.toAdRouteConfig(adRoute));
    }

    @Override
    public List<AdRoute> adRoutesFindByBgp(@Nonnull UUID bgpId)
            throws StateAccessException, SerializationException {
        List<UUID> adRouteIds = adRouteZkManager.list(bgpId);
        List<AdRoute> adRoutes = new ArrayList<AdRoute>();
        for (UUID adRouteId : adRouteIds) {
            adRoutes.add(adRoutesGet(adRouteId));
        }
        return adRoutes;
    }

    @Override
    public @CheckForNull BGP bgpGet(@Nonnull UUID id)
            throws StateAccessException, SerializationException {
        return new BGP(id, bgpZkManager.get(id));
    }

    @Override
    public void bgpDelete(UUID id)
            throws StateAccessException, SerializationException {
        bgpZkManager.delete(id);
    }

    @Override
    public UUID bgpCreate(@Nonnull BGP bgp)
            throws StateAccessException, SerializationException {
        return bgpZkManager.create(bgp);
    }

    @Override
    public List<BGP> bgpFindByPort(UUID portId)
            throws StateAccessException, SerializationException {
        List<UUID> bgpIds = bgpZkManager.list(portId);
        List<BGP> bgps = new ArrayList<BGP>();
        for (UUID bgpId : bgpIds) {
            bgps.add(bgpGet(bgpId));
        }
        return bgps;
    }

    public List<Bridge> bridgesFindByTenant(String tenantId) throws StateAccessException,
        SerializationException {
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

    @Override
    public Map<VlanMacPort, IPv4Addr> bridgeGetMacPortsWithVxTunnelEndpoint(
        @Nonnull UUID bridgeId) throws StateAccessException,
                                       SerializationException {

        Bridge bridge = bridgesGet(bridgeId);
        if (bridge.getVxLanPortId() == null) {
            log.warn("Requesting ports with vxlan tunnel port for a bridge " +
                     "that is not bound to a VTEP: {}", bridgeId);
            return new HashMap<>();
        }

        List<VlanMacPort> allPorts = bridgeGetMacPorts(bridgeId);
        Map<VlanMacPort, IPv4Addr> filtered = new HashMap<>();
        for (VlanMacPort vmp : allPorts) {
            Port<?, ?> p = portsGet(vmp.portId);
            if (p == null || !p.isExterior()) {
               continue;
            }
            // skip on null - caused by race condition
            // (disappeared/unbound port/bridge)
            IPv4Addr ip = vxlanTunnelEndpointForInternal(bridge, (BridgePort)p);
            if (ip == null)
                continue;
            filtered.put(vmp, ip);
        }
        return filtered;
    }

    /**
     * Returns a list of all MAC-port mappings for the specified bridge.
     * @throws StateAccessException
     */
    @Override
    public List<VlanMacPort> bridgeGetMacPorts(@Nonnull UUID bridgeId)
            throws StateAccessException {
        // Get entries for the untagged VLAN.
        List<VlanMacPort> allPorts = new LinkedList<VlanMacPort>();
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
        List<VlanMacPort> ports = new ArrayList<VlanMacPort>(portsMap.size());
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
            bridgeZkManager.getMacPortMapDirectory(bridgeId, vlanId), mac, portId);
    }

    @Override
    public boolean bridgeHasMacPort(@Nonnull UUID bridgeId, Short vlanId,
                                    @Nonnull MAC mac, @Nonnull UUID portId)
            throws StateAccessException {
        return MacPortMap.hasPersistentEntry(
            bridgeZkManager.getMacPortMapDirectory(bridgeId, vlanId), mac, portId);
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
        Ip4ToMacReplicatedMap.addPersistentEntry(
                bridgeZkManager.getIP4MacMapDirectory(bridgeId), ip4, mac);
    }

    @Override
    public boolean bridgeHasIP4MacPair(@Nonnull UUID bridgeId,
                                       @Nonnull IPv4Addr ip, @Nonnull MAC mac)
        throws StateAccessException {
        return Ip4ToMacReplicatedMap.hasPersistentEntry(
            bridgeZkManager.getIP4MacMapDirectory(bridgeId), ip, mac);
    }

    @Override
    public void bridgeDeleteIp4Mac(
            @Nonnull UUID bridgeId, @Nonnull IPv4Addr ip4, @Nonnull MAC mac)
            throws StateAccessException {
        Ip4ToMacReplicatedMap.deleteEntry(
            bridgeZkManager.getIP4MacMapDirectory(bridgeId), ip4, mac);
    }

    @Override
    public UUID bridgesCreate(@Nonnull Bridge bridge)
            throws StateAccessException, SerializationException {
        log.debug("bridgesCreate entered: bridge={}", bridge);

        if (bridge.getId() == null) {
            bridge.setId(UUID.randomUUID());
        }

        BridgeZkManager.BridgeConfig bridgeConfig = Converter.toBridgeConfig(
                bridge);

        List<Op> ops =
                bridgeZkManager.prepareBridgeCreate(bridge.getId(),
                        bridgeConfig);

        // Create the top level directories for
        String tenantId = bridge.getProperty(Bridge.Property.tenant_id);
        ops.addAll(tenantZkManager.prepareCreate(tenantId));

        zkManager.multi(ops);

        log.debug("bridgesCreate exiting: bridge={}", bridge);
        return bridge.getId();
    }

    @Override
    public void bridgesUpdate(@Nonnull Bridge bridge)
            throws StateAccessException, SerializationException,
            BridgeZkManager.VxLanPortIdUpdateException {
        List<Op> ops = new ArrayList<Op>();

        // Get the original data
        Bridge oldBridge = bridgesGet(bridge.getId());

        BridgeZkManager.BridgeConfig bridgeConfig = Converter.toBridgeConfig(
                bridge);

        // Update the config
        ops.addAll(bridgeZkManager.prepareUpdate(
                bridge.getId(), bridgeConfig, true));

        if (!ops.isEmpty()) {
            zkManager.multi(ops);
        }
    }

    @Override
    public List<Bridge> bridgesGetAll() throws StateAccessException,
            SerializationException {
        log.debug("bridgesGetAll entered");
        List<Bridge> bridges = new ArrayList<Bridge>();

        List<UUID> bridgeIds = bridgesGetAllIds();
        for (UUID id : bridgeIds) {
            Bridge bridge = bridgesGet(id);
            if (bridge != null) {
                bridges.add(bridge);
            }
        }

        log.debug("bridgesGetAll exiting: {} bridges found", bridges.size());
        return bridges;
    }

    @Override
    public List<Bridge> bridgesGetAllWithVxlanPort()
        throws StateAccessException, SerializationException {
        return ListUtil.filter(
            this.bridgesGetAll(),
            new Function<Bridge, Boolean>() {
                @Override
                public Boolean apply(@Nullable Bridge bridge) {
                    return bridge != null && bridge.getVxLanPortId() != null;
                }
            }
        );
    }

    @Override
    public EntityMonitor<UUID, BridgeConfig, Bridge> bridgesGetMonitor(
        ZookeeperConnectionWatcher zkConnection) {
        return new EntityMonitor<>(
            bridgeZkManager, zkConnection,
            new EntityMonitor.Transformer<UUID, BridgeConfig, Bridge> () {
                @Override
                public Bridge transform(UUID id, BridgeConfig data) {
                    Bridge bridge = Converter.fromBridgeConfig(data);
                    bridge.setId(id);
                    return bridge;
                }
            });
    }

    @Override
    public EntityIdSetMonitor<UUID> bridgesGetUuidSetMonitor(
        ZookeeperConnectionWatcher zkConnection)
        throws StateAccessException {
        return new EntityIdSetMonitor(bridgeZkManager, zkConnection);
    }

    @Override
    public List<UUID> bridgesGetAllIds() throws StateAccessException,
            SerializationException {
        return bridgeZkManager.getUuidList(pathBuilder.getBridgesPath());
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
            bridge = Converter.fromBridgeConfig(bridgeZkManager.get(id));
            bridge.setId(id);
        }

        log.debug("Exiting: bridge={}", bridge);
        return bridge;
    }

    @Override
    public void bridgesDelete(UUID id)
            throws StateAccessException, SerializationException {

        Bridge bridge = bridgesGet(id);
        if (bridge == null) {
            return;
        }

        List<Op> ops = bridgeZkManager.prepareBridgeDelete(id);

        String name = bridge.getData().name;
        if (StringUtils.isNotEmpty(name)) {
            String path = pathBuilder.getTenantBridgeNamePath(
                    bridge.getProperty(Bridge.Property.tenant_id), name);

            // For backward compatibility, delete only if it exists
            if (zkManager.exists(path)) {
                ops.add(zkManager.getDeleteOp(path));
            }
        }

        zkManager.multi(ops);
    }

    @Override
    public void tagsAdd(@Nonnull TaggableEntity taggable, UUID id, String tag)
            throws StateAccessException {
        TaggableConfig taggableConfig = Converter.toTaggableConfig(taggable);
        taggableConfigZkManager.create(id, taggableConfig, tag);
    }

    @Override
    public String tagsGet(@Nonnull TaggableEntity taggable, UUID id, String tag)
            throws StateAccessException {
        TaggableConfig taggableConfig = Converter.toTaggableConfig(taggable);
        return this.taggableConfigZkManager.get(id, taggableConfig, tag);
    }

    @Override
    public List<String> tagsList(@Nonnull TaggableEntity taggable, UUID id)
            throws StateAccessException {
        TaggableConfig taggableConfig = Converter.toTaggableConfig(taggable);
        return this.taggableConfigZkManager.listTags(id, taggableConfig, null);
    }

    @Override
    public void tagsDelete(@Nonnull TaggableEntity taggable, UUID id, String tag)
        throws StateAccessException {
        TaggableConfig taggableConfig = Converter.toTaggableConfig(taggable);
        this.taggableConfigZkManager.delete(id, taggableConfig, tag);
    }

     @Override
    public List<Chain> chainsGetAll() throws StateAccessException,
            SerializationException {
        log.debug("chainsGetAll entered");

        List<Chain> chains = new ArrayList<Chain>();

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

    @Override
    public void chainsDelete(UUID id)
            throws StateAccessException, SerializationException {
        Chain chain = chainsGet(id);
        if (chain == null) {
            return;
        }

        List<Op> ops = chainZkManager.prepareDelete(id);
        String path = pathBuilder.getTenantChainNamePath(
                chain.getProperty(Chain.Property.tenant_id),
                chain.getData().name);
        ops.add(zkManager.getDeleteOp(path));

        zkManager.multi(ops);
    }

    @Override
    public UUID chainsCreate(@Nonnull Chain chain)
            throws StateAccessException, SerializationException {
        log.debug("chainsCreate entered: chain={}", chain);

        if (chain.getId() == null) {
            chain.setId(UUID.randomUUID());
        }

        ChainZkManager.ChainConfig chainConfig =
                Converter.toChainConfig(chain);

        List<Op> ops =
                chainZkManager.prepareCreate(chain.getId(), chainConfig);

        // Create the top level directories for
        String tenantId = chain.getProperty(Chain.Property.tenant_id);
        ops.addAll(tenantZkManager.prepareCreate(tenantId));

        // Create the chain names directory if it does not exist
        String chainNamesPath = pathBuilder.getTenantChainNamesPath(tenantId);
        if (!zkManager.exists(chainNamesPath)) {
            ops.add(zkManager.getPersistentCreateOp(
                    chainNamesPath, null));
        }

        // Index the name
        String chainNamePath = pathBuilder.getTenantChainNamePath(tenantId,
                chainConfig.name);
        byte[] data = serializer.serialize(
                (new ChainName(chain)).getData());
        ops.add(zkManager.getPersistentCreateOp(chainNamePath, data));

        zkManager.multi(ops);

        log.debug("chainsCreate exiting: chain={}", chain);
        return chain.getId();
    }

    @Override
    public void subscribeToLocalActivePorts(@Nonnull Callback2<UUID, Boolean> cb) {
        subscriptionPortsActive.offer(cb);
    }

    @Override
    public UUID tunnelZonesCreate(@Nonnull TunnelZone zone)
            throws StateAccessException, SerializationException {
        return zonesZkManager.createZone(zone, null);
    }

    @Override
    public void tunnelZonesDelete(UUID uuid)
        throws StateAccessException {
        zonesZkManager.deleteZone(uuid);
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

        List<TunnelZone> tunnelZones = new ArrayList<TunnelZone>();

        for (UUID id : ids) {
            TunnelZone zone = tunnelZonesGet(id);
            if (zone != null) {
                tunnelZones.add(zone);
            }
        }

        return tunnelZones;
    }

    @Override
    public void tunnelZonesUpdate(@Nonnull TunnelZone zone)
            throws StateAccessException, SerializationException {
        zonesZkManager.updateZone(zone);
    }

    @Override
    public boolean tunnelZonesMembershipExists(UUID uuid, UUID hostId)
            throws StateAccessException {
        return zonesZkManager.membershipExists(uuid, hostId);
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
            if (tunnelZonesMembershipExists(tunnelZone.getId(), hostId)) {
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
    public UUID tunnelZonesAddMembership(UUID zoneId, TunnelZone.HostConfig hostConfig)
            throws StateAccessException, SerializationException {
        return zonesZkManager.addMembership(zoneId, hostConfig);
    }

    @Override
    public void tunnelZonesDeleteMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException {
        zonesZkManager.delMembership(zoneId, membershipId);
    }

    @Override
    public void portsSetLocalAndActive(final UUID portID,
                                       final boolean active) {
        // use the reactor thread for this operations
        reactor.submit(new Runnable() {

            @Override
            public void run() {
                PortConfig config = null;
                try {
                    config = portZkManager.get(portID);
                } catch (StateAccessException e) {
                    log.error("Error retrieving the configuration for port {}",
                            portID, e);
                } catch (SerializationException e) {
                    log.error("Error serializing the configuration for port " +
                            "{}", portID, e);
                }
                // update the subscribers
                for (Callback2<UUID, Boolean> cb : subscriptionPortsActive) {
                    cb.call(portID, active);
                }
                if (config instanceof PortDirectory.RouterPortConfig) {
                    UUID deviceId = config.device_id;
                    routerManager.updateRoutesBecauseLocalPortChangedStatus(
                            deviceId, portID, active);
                }
            }
        });
    }

    public @CheckForNull Chain chainsGetByName(@Nonnull String tenantId, String name)
            throws StateAccessException, SerializationException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        Chain chain = null;
        String path = pathBuilder.getTenantChainNamePath(tenantId, name);

        if (zkManager.exists(path)) {
            byte[] data = zkManager.get(path);
            ChainName.Data chainNameData =
                    serializer.deserialize(data,
                            ChainName.Data.class);
            chain = chainsGet(chainNameData.id);
        }

        log.debug("Exiting: chain={}", chain);
        return chain;
    }

    @Override
    public List<Chain> chainsFindByTenant(String tenantId)
            throws StateAccessException, SerializationException {
        log.debug("chainsFindByTenant entered: tenantId={}", tenantId);

        List<Chain> chains = new ArrayList<Chain>();

        String path = pathBuilder.getTenantChainNamesPath(tenantId);
        if (zkManager.exists(path)) {
            Set<String> chainNames = zkManager.getChildren(path);
            for (String name : chainNames) {
                Chain chain = chainsGetByName(tenantId, name);
                if (chain != null) {
                    chains.add(chain);
                }
            }
        }

        log.debug("chainsFindByTenant exiting: {} chains found",
                chains.size());
        return chains;
    }

    @Override
    public void dhcpSubnetsCreate(@Nonnull UUID bridgeId, @Nonnull Subnet subnet)
            throws StateAccessException, SerializationException {
        dhcpZkManager.createSubnet(bridgeId,
                Converter.toDhcpSubnetConfig(subnet));
    }

    @Override
    public void dhcpSubnetsUpdate(@Nonnull UUID bridgeId, @Nonnull Subnet subnet)
            throws StateAccessException, SerializationException {

        Subnet oldSubnet = dhcpSubnetsGet(bridgeId, subnet.getSubnetAddr());
        if (oldSubnet == null) {
            return;
        }

        // If isEnabled was not specified in the request, just set it to the
        // previous value.
        if (subnet.isEnabled() == null) {
            subnet.setEnabled(oldSubnet.isEnabled());
        }

        dhcpZkManager.updateSubnet(bridgeId,
                Converter.toDhcpSubnetConfig(subnet));
    }

    @Override
    public void dhcpSubnetsDelete(UUID bridgeId, IPv4Subnet subnetAddr)
            throws StateAccessException {
        dhcpZkManager.deleteSubnet(bridgeId, subnetAddr);
    }

    @Override
    public @CheckForNull Subnet dhcpSubnetsGet(UUID bridgeId, IPv4Subnet subnetAddr)
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
    public void dhcpHostsCreate(
            UUID bridgeId, IPv4Subnet subnet,
            org.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException, SerializationException {

        dhcpZkManager.addHost(bridgeId, subnet,
                Converter.toDhcpHostConfig(host));
    }

    @Override
    public void dhcpHostsUpdate(
            UUID bridgeId, IPv4Subnet subnet,
            org.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException, SerializationException {
        dhcpZkManager.updateHost(bridgeId, subnet, Converter.toDhcpHostConfig(host));
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
    public void dhcpHostsDelete(UUID bridgId, IPv4Subnet subnet, String mac)
            throws StateAccessException {
        dhcpZkManager.deleteHost(bridgId, subnet, mac);
    }

    @Override
    public
    List<org.midonet.cluster.data.dhcp.Host> dhcpHostsGetBySubnet(
            UUID bridgeId, IPv4Subnet subnet)
            throws StateAccessException, SerializationException {

        List<BridgeDhcpZkManager.Host> hostConfigs =
                dhcpZkManager.getHosts(bridgeId, subnet);
        List<org.midonet.cluster.data.dhcp.Host> hosts =
                new ArrayList<org.midonet.cluster.data.dhcp.Host>();
        for (BridgeDhcpZkManager.Host hostConfig : hostConfigs) {
            hosts.add(Converter.fromDhcpHostConfig(hostConfig));
        }

        return hosts;
    }

    @Override
    public void dhcpSubnet6Create(@Nonnull UUID bridgeId, @Nonnull Subnet6 subnet)
            throws StateAccessException, SerializationException {
        dhcpV6ZkManager.createSubnet6(bridgeId,
                Converter.toDhcpSubnet6Config(subnet));
    }

    @Override
    public void dhcpSubnet6Update(@Nonnull UUID bridgeId, @Nonnull Subnet6 subnet)
            throws StateAccessException, SerializationException {
        dhcpV6ZkManager.updateSubnet6(bridgeId,
                Converter.toDhcpSubnet6Config(subnet));
    }

    @Override
    public void dhcpSubnet6Delete(UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException {
        dhcpV6ZkManager.deleteSubnet6(bridgeId, prefix);
    }

    public @CheckForNull Subnet6 dhcpSubnet6Get(UUID bridgeId, IPv6Subnet prefix)
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

        List<IPv6Subnet> subnet6Configs = dhcpV6ZkManager.listSubnet6s(bridgeId);
        List<Subnet6> subnets = new ArrayList<Subnet6>(subnet6Configs.size());

        for (IPv6Subnet subnet6Config : subnet6Configs) {
            subnets.add(dhcpSubnet6Get(bridgeId, subnet6Config));
        }

        return subnets;
    }

    @Override
    public void dhcpV6HostCreate(
            UUID bridgeId, IPv6Subnet prefix, V6Host host)
            throws StateAccessException, SerializationException {

        dhcpV6ZkManager.addHost(bridgeId, prefix,
                Converter.toDhcpV6HostConfig(host));
    }

    @Override
    public void dhcpV6HostUpdate(
            UUID bridgeId, IPv6Subnet prefix, V6Host host)
            throws StateAccessException, SerializationException {
        dhcpV6ZkManager.updateHost(bridgeId, prefix,
                Converter.toDhcpV6HostConfig(host));
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
    public void dhcpV6HostDelete(UUID bridgId, IPv6Subnet prefix, String clientId)
            throws StateAccessException {
        dhcpV6ZkManager.deleteHost(bridgId, prefix, clientId);
    }

    @Override
    public
    List<V6Host> dhcpV6HostsGetByPrefix(
            UUID bridgeId, IPv6Subnet prefix)
            throws StateAccessException, SerializationException {

        List<BridgeDhcpV6ZkManager.Host> hostConfigs =
                dhcpV6ZkManager.getHosts(bridgeId, prefix);
        List<V6Host> hosts = new ArrayList<V6Host>();

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

            /* The flooding proxy weight might have not been initialized
             * for this host; if so, leave the default value set by Host
             * constructor; otherwise, set the stored value. */
            if (floodingProxyWeight != null)
                host.setFloodingProxyWeight(floodingProxyWeight);
        }

        return host;
    }

    @Override
    public void hostsDelete(UUID hostId) throws StateAccessException {
        hostZkManager.deleteHost(hostId);
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
            throws StateAccessException, SerializationException {
        Collection<UUID> ids = hostZkManager.getHostIds();

        List<Host> hosts = new ArrayList<Host>();

        for (UUID id : ids) {
            try {
                Host host = hostsGet(id);
                if (host != null) {
                    hosts.add(host);
                }
            } catch (StateAccessException e) {
                log.warn(
                        "Tried to read the information of a host that vanished "
                                + "or become corrupted: {}", id, e);
            }
        }

        return hosts;
    }

    @Override
    public EntityMonitor<UUID, HostDirectory.Metadata, Host> hostsGetMonitor(
        ZookeeperConnectionWatcher zkConnection) {
        return new EntityMonitor<>(
            hostZkManager, zkConnection,
            new EntityMonitor.Transformer<UUID, HostDirectory.Metadata, Host> () {
                @Override
                public Host transform(UUID id, HostDirectory.Metadata data) {
                    Host host = Converter.fromHostConfig(data);
                    host.setId(id);
                    Integer floodingProxyWeight = null;
                    try {
                        floodingProxyWeight =
                            hostZkManager.getFloodingProxyWeight(id);
                    } catch (StateAccessException | SerializationException e) {
                    }
                    try {
                        host.setIsAlive(hostsIsAlive(id));
                    } catch (StateAccessException ex) { }
                    if (floodingProxyWeight != null)
                        host.setFloodingProxyWeight(floodingProxyWeight);
                    return host;
                }
            });
    }

    @Override
    public EntityIdSetMonitor<UUID> hostsGetUuidSetMonitor(
        ZookeeperConnectionWatcher zkConnection)
        throws StateAccessException {
        return new EntityIdSetMonitor(hostZkManager, zkConnection);
    }

    @Override
    public List<Interface> interfacesGetByHost(UUID hostId)
            throws StateAccessException, SerializationException {
        List<Interface> interfaces = new ArrayList<Interface>();

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
                                + "{}.",
                        new Object[] { hostId, interfaceName, e });
            }
        }

        return interfaces;
    }

    @Override
    public @CheckForNull Interface interfacesGet(UUID hostId, String interfaceName)
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
    public Integer commandsCreateForInterfaceupdate(UUID hostId,
                                                    String curInterfaceId,
                                                    Interface newInterface)
            throws StateAccessException, SerializationException {

        HostCommandGenerator commandGenerator = new HostCommandGenerator();

        HostDirectory.Interface curHostInterface = null;

        if (curInterfaceId != null) {
            curHostInterface = hostZkManager.getInterfaceData(hostId,
                    curInterfaceId);
        }

        HostDirectory.Interface newHostInterface =
                Converter.toHostInterfaceConfig(newInterface);

        HostDirectory.Command command = commandGenerator.createUpdateCommand(
                curHostInterface, newHostInterface);

        return hostZkManager.createHostCommandId(hostId, command);
    }

    @Override
    public List<Command> commandsGetByHost(UUID hostId)
            throws StateAccessException, SerializationException {
        List<Integer> commandsIds = hostZkManager.getCommandIds(hostId);
        List<Command> commands = new ArrayList<Command>();
        for (Integer commandsId : commandsIds) {

            Command hostCommand = commandsGet(hostId, commandsId);

            if (hostCommand != null) {
                commands.add(hostCommand);
            }
        }

        return commands;
    }

    @Override
    public @CheckForNull Command commandsGet(UUID hostId, Integer id)
            throws StateAccessException, SerializationException {
        Command command = null;

        try {
            HostDirectory.Command hostCommand =
                    hostZkManager.getCommandData(hostId, id);

            HostDirectory.ErrorLogItem errorLogItem =
                    hostZkManager.getErrorLogData(hostId, id);

            command = Converter.fromHostCommandConfig(hostCommand);
            command.setId(id);

            if (errorLogItem != null) {
                command.setErrorLogItem(
                        Converter.fromHostErrorLogItemConfig(errorLogItem));
            }

        } catch (StateAccessException e) {
            log.warn("Could not read command with id {} from datastore "
                    + "(for host: {})", new Object[] { id, hostId, e });
            throw e;
        }

        return command;
    }

    @Override
    public void commandsDelete(UUID hostId, Integer id)
            throws StateAccessException {
        hostZkManager.deleteHostCommand(hostId, id);
    }

    @Override
    public List<VirtualPortMapping> hostsGetVirtualPortMappingsByHost(
            UUID hostId) throws StateAccessException, SerializationException {
        Set<HostDirectory.VirtualPortMapping> zkMaps =
                hostZkManager.getVirtualPortMappings(hostId, null);

        List<VirtualPortMapping> maps =
                new ArrayList<VirtualPortMapping>(zkMaps.size());

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
    public void portsDelete(UUID id)
            throws StateAccessException, SerializationException {
        portZkManager.delete(id);
    }

    @Override
    public List<BridgePort> portsFindByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException {

        Collection<UUID> ids = portZkManager.getBridgePortIDs(bridgeId);
        List<BridgePort> ports = new ArrayList<BridgePort>();
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
    public UUID portsCreate(@Nonnull final Port<?,?> port)
            throws StateAccessException, SerializationException {
        return portZkManager.create(Converter.toPortConfig(port));
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
            port.setId(id);
        }

        return port;
    }

    @Override
    public void portsUpdate(@Nonnull Port<?,?> port)
            throws StateAccessException, SerializationException {
        log.debug("portsUpdate entered: port={}", port);

        // Whatever sent is what gets stored.
        portZkManager.update(UUID.fromString(port.getId().toString()),
                Converter.toPortConfig(port));

        log.debug("portsUpdate exiting");
    }

    @Override
    public void portsLink(@Nonnull UUID portId, @Nonnull UUID peerPortId)
            throws StateAccessException, SerializationException {

        portZkManager.link(portId, peerPortId);

    }

    @Override
    public void portsUnlink(@Nonnull UUID portId)
            throws StateAccessException, SerializationException {
        portZkManager.unlink(portId);
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
    public void ipAddrGroupsDelete(UUID id)
            throws StateAccessException, SerializationException {
        ipAddrGroupZkManager.delete(id);
    }

    @Override
    public UUID ipAddrGroupsCreate(@Nonnull IpAddrGroup ipAddrGroup)
            throws StateAccessException, SerializationException {
        return ipAddrGroupZkManager.create(
                Converter.toIpAddrGroupConfig(ipAddrGroup));
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
    public void ipAddrGroupAddAddr(@Nonnull UUID id, @Nonnull String addr)
            throws StateAccessException, SerializationException {
        ipAddrGroupZkManager.addAddr(id, addr);
    }

    @Override
    public void ipAddrGroupRemoveAddr(UUID id, String addr)
            throws StateAccessException, SerializationException {
        ipAddrGroupZkManager.removeAddr(id, addr);
    }

    @Override
    public Set<String> getAddrsByIpAddrGroup(UUID id)
            throws StateAccessException, SerializationException {
        return ipAddrGroupZkManager.getAddrs(id);
    }

    @Override
    public PortGroup portGroupsGetByName(String tenantId, String name)
            throws StateAccessException, SerializationException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        PortGroup portGroup = null;
        String path = pathBuilder.getTenantPortGroupNamePath(tenantId, name);

        if (zkManager.exists(path)) {
            byte[] data = zkManager.get(path);
            PortGroupName.Data portGroupNameData =
                    serializer.deserialize(data, PortGroupName.Data.class);
            portGroup = portGroupsGet(portGroupNameData.id);
        }

        log.debug("Exiting: portGroup={}", portGroup);
        return portGroup;
    }

    @Override
    public List<PortGroup> portGroupsFindByTenant(String tenantId)
            throws StateAccessException, SerializationException {
        log.debug("portGroupsFindByTenant entered: tenantId={}", tenantId);

        List<PortGroup> portGroups = new ArrayList<>();

        String path = pathBuilder.getTenantPortGroupNamesPath(tenantId);
        if (zkManager.exists(path)) {
            Set<String> portGroupNames = zkManager.getChildren(path);
            for (String name : portGroupNames) {
                PortGroup portGroup = portGroupsGetByName(tenantId, name);
                if (portGroup != null) {
                    portGroups.add(portGroup);
                }
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
    public void portGroupsAddPortMembership(@Nonnull UUID id,
                                            @Nonnull UUID portId)
            throws StateAccessException, SerializationException {
        portGroupZkManager.addPortToPortGroup(id, portId);
    }

    @Override
    public void portGroupsRemovePortMembership(UUID id, UUID portId)
            throws StateAccessException, SerializationException {
        portGroupZkManager.removePortFromPortGroup(id, portId);
    }

    @Override
    public UUID portGroupsCreate(@Nonnull PortGroup portGroup)
            throws StateAccessException, SerializationException {
        log.debug("portGroupsCreate entered: portGroup={}", portGroup);

        if (portGroup.getId() == null) {
            portGroup.setId(UUID.randomUUID());
        }

        PortGroupZkManager.PortGroupConfig portGroupConfig =
                Converter.toPortGroupConfig(portGroup);

        List<Op> ops =
                portGroupZkManager.prepareCreate(portGroup.getId(),
                        portGroupConfig);

        // Create the top level directories for
        String tenantId = portGroup.getProperty(PortGroup.Property.tenant_id);
        ops.addAll(tenantZkManager.prepareCreate(tenantId));

        // Create the portGroup names directory if it does not exist
        String portGroupNamesPath = pathBuilder.getTenantPortGroupNamesPath(
                tenantId);
        if (!zkManager.exists(portGroupNamesPath)) {
            ops.add(zkManager.getPersistentCreateOp(
                    portGroupNamesPath, null));
        }

        // Index the name
        String portGroupNamePath = pathBuilder.getTenantPortGroupNamePath(
                tenantId, portGroupConfig.name);
        byte[] data = serializer.serialize(
                (new PortGroupName(portGroup)).getData());
        ops.add(zkManager.getPersistentCreateOp(portGroupNamePath,
                data));

        zkManager.multi(ops);

        log.debug("portGroupsCreate exiting: portGroup={}", portGroup);
        return portGroup.getId();
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

    @Override
    public void portGroupsDelete(UUID id)
            throws StateAccessException, SerializationException {

        PortGroup portGroup = portGroupsGet(id);
        if (portGroup == null) {
            return;
        }

        List<Op> ops = portGroupZkManager.prepareDelete(id);
        String path = pathBuilder.getTenantPortGroupNamePath(
                portGroup.getProperty(PortGroup.Property.tenant_id),
                portGroup.getData().name);
        ops.add(zkManager.getDeleteOp(path));

        zkManager.multi(ops);
    }

    @Override
    public UUID hostsCreate(@Nonnull UUID hostId, @Nonnull Host host)
            throws StateAccessException, SerializationException {
        hostZkManager.createHost(hostId, Converter.toHostConfig(host));
        return hostId;
    }

    @Override
    public void hostsAddVrnPortMapping(@Nonnull UUID hostId, @Nonnull UUID portId,
                                       @Nonnull String localPortName)
            throws StateAccessException, SerializationException {
        hostZkManager.addVirtualPortMapping(
                hostId, new HostDirectory.VirtualPortMapping(portId, localPortName));
    }

    /**
     * Does the same thing as @hostsAddVrnPortMapping(),
     * except this returns the updated port object.
     */
    @Override
    public Port<?,?> hostsAddVrnPortMappingAndReturnPort(
                    @Nonnull UUID hostId, @Nonnull UUID portId,
                    @Nonnull String localPortName)
            throws StateAccessException, SerializationException {
        return
            hostZkManager.addVirtualPortMapping(
                    hostId,
                    new HostDirectory.VirtualPortMapping(portId, localPortName));
    }

    @Override
    public void hostsAddDatapathMapping(@Nonnull UUID hostId, @Nonnull String datapathName)
            throws StateAccessException, SerializationException {
        hostZkManager.addVirtualDatapathMapping(hostId, datapathName);
    }

    @Override
    public void hostsDelVrnPortMapping(UUID hostId, UUID portId)
            throws StateAccessException, SerializationException {
        hostZkManager.delVirtualPortMapping(hostId, portId);
    }

    @Override
    public void hostsSetFloodingProxyWeight(UUID hostId, int weight)
            throws StateAccessException, SerializationException {
        hostZkManager.setFloodingProxyWeight(hostId, weight);
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
    public void routersDelete(UUID id)
            throws StateAccessException, SerializationException {
        Router router = routersGet(id);
        if (router == null) {
            return;
        }

        List<Op> ops = routerZkManager.prepareRouterDelete(id);

        String name = router.getData().name;
        if (StringUtils.isNotEmpty(name)) {

            String path = pathBuilder.getTenantRouterNamePath(
                    router.getProperty(Router.Property.tenant_id), name);

            // For backward compatibility, delete only if it exists
            if (zkManager.exists(path)) {
                ops.add(zkManager.getDeleteOp(path));
            }
        }

        zkManager.multi(ops);
    }

    @Override
    public UUID routersCreate(@Nonnull Router router)
            throws StateAccessException, SerializationException {
        log.debug("routersCreate entered: router={}", router);

        if (router.getId() == null) {
            router.setId(UUID.randomUUID());
        }

        RouterZkManager.RouterConfig routerConfig =
                Converter.toRouterConfig(router);

        List<Op> ops =
                routerZkManager.prepareRouterCreate(router.getId(),
                        routerConfig);

        // Create the top level directories
        String tenantId = router.getProperty(Router.Property.tenant_id);
        ops.addAll(tenantZkManager.prepareCreate(tenantId));

        zkManager.multi(ops);

        log.debug("routersCreate exiting: router={}", router);
        return router.getId();
    }

    @Override
    public void routersUpdate(@Nonnull Router router) throws
            StateAccessException, SerializationException {
        // Get the original data
        Router oldRouter = routersGet(router.getId());
        RouterZkManager.RouterConfig routerConfig = Converter.toRouterConfig(
                router);
        List<Op> ops = new ArrayList<>();

        // Update the config
        ops.addAll(routerZkManager.prepareUpdate(router.getId(), routerConfig));

        if (!ops.isEmpty()) {
            zkManager.multi(ops);
        }
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

        log.debug("routersFindByTenant exiting: {} routers found", routers.size());
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
        return ruleZkManager.create(Converter.toRuleConfig(rule),
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
    public void portSetsAsyncAddHost(@Nonnull UUID portSetId, @Nonnull UUID hostId,
                                     DirectoryCallback.Add callback) {
        portSetZkManager.addMemberAsync(portSetId, hostId, callback);
    }

    @Override
    public void portSetsAddHost(@Nonnull UUID portSetId, @Nonnull UUID hostId)
        throws StateAccessException {
        portSetZkManager.addMember(portSetId, hostId);
    }

    @Override
    public void portSetsAsyncDelHost(UUID portSetId, UUID hostId,
                                     DirectoryCallback.Void callback) {
        portSetZkManager.delMemberAsync(portSetId, hostId, callback);
    }

    @Override
    public void portSetsDelHost(UUID portSetId, UUID hostId)
        throws StateAccessException {
        portSetZkManager.delMember(portSetId, hostId);
    }

    @Override
    public Set<UUID> portSetsGet(UUID portSetId)
            throws SerializationException, StateAccessException {
        return portSetZkManager.getPortSet(portSetId, null);
    }

    /**
     * Gets all the tenants stored in the data store.
     *
     * @return Set containing tenant IDs
     * @throws StateAccessException
     */
    @Override
    public Set<String> tenantsGetAll() throws StateAccessException {
        return tenantZkManager.list();
    }

    /**
     * Get the current write version.
     *
     * @return current write version.
     */
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
        systemState.setState(upgrade ? SystemState.State.UPGRADE.toString()
                                     : SystemState.State.ACTIVE.toString());
        systemState.setAvailability(
                readonly ? SystemState.Availability.READONLY.toString()
                : SystemState.Availability.READWRITE.toString());
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
    public Integer registerAsHealthMonitorNode(
            ZkLeaderElectionWatcher.ExecuteOnBecomingLeader cb)
            throws StateAccessException {
        String path = pathBuilder.getHealthMonitorLeaderDirPath();
        return ZkLeaderElectionWatcher.registerLeaderNode(cb, path, zkManager);
    }

    @Override
    public void removeHealthMonitorLeaderNode(Integer node)
            throws StateAccessException {
        if (node == null)
            return;
        String path = pathBuilder.getHealthMonitorLeaderDirPath() + "/" +
                StringUtils.repeat("0", 10 - node.toString().length()) +
                node.toString();
        zkManager.delete(path);
    }

    @Override
    public void vtepCreate(VTEP vtep)
            throws StateAccessException, SerializationException {
        VtepZkManager.VtepConfig config = Converter.toVtepConfig(vtep);
        zkManager.multi(vtepZkManager.prepareCreate(vtep.getId(), config));
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
    public void vtepDelete(IPv4Addr ipAddr)
            throws StateAccessException, SerializationException {
        List<Op> deleteNoOwner = new ArrayList<Op>(
            vtepZkManager.prepareDelete(ipAddr));
        List<Op> deleteOwner = new ArrayList<Op>(
            vtepZkManager.prepareDeleteOwner(ipAddr));
        deleteOwner.addAll(deleteNoOwner);

        try {
            zkManager.multi(deleteOwner);
        } catch (NoStatePathException e) {
            zkManager.multi(deleteNoOwner);
        }
    }

    @Override
    public void vtepAddBinding(@Nonnull IPv4Addr ipAddr,
                               @Nonnull String portName, short vlanId,
                               @Nonnull UUID networkId)
            throws StateAccessException {
        zkManager.multi(vtepZkManager.prepareCreateBinding(ipAddr, portName,
                                                           vlanId, networkId));
    }

    @Override
    public void vtepDeleteBinding(@Nonnull IPv4Addr ipAddr,
                                  @Nonnull String portName, short vlanId)
            throws StateAccessException {
        zkManager.multi(
                vtepZkManager.prepareDeleteBinding(ipAddr, portName, vlanId));
    }

    @Override
    public List<VtepBinding> vtepGetBindings(@Nonnull IPv4Addr ipAddr)
            throws StateAccessException {
        return vtepZkManager.getBindings(ipAddr);
    }

    @Override
    public VtepBinding vtepGetBinding(@Nonnull IPv4Addr ipAddr,
                                      @Nonnull String portName, short vlanId)
            throws StateAccessException {
        return vtepZkManager.getBinding(ipAddr, portName, vlanId);
    }


    @Override
    public EntityMonitor<IPv4Addr, VtepZkManager.VtepConfig, VTEP> vtepsGetMonitor(
        ZookeeperConnectionWatcher zkConnection) {
        return new EntityMonitor<>(
            vtepZkManager, zkConnection,
            new EntityMonitor.Transformer<IPv4Addr, VtepZkManager.VtepConfig,
                VTEP> () {
                @Override
                public VTEP transform(IPv4Addr ipAddr,
                                      VtepZkManager.VtepConfig data) {
                    VTEP vtep = Converter.fromVtepConfig(data);
                    vtep.setId(ipAddr);
                    return vtep;
                }
            });
    }

    @Override
    public EntityIdSetMonitor<IPv4Addr> vtepsGetAllSetMonitor(
        ZookeeperConnectionWatcher zkConnection)
        throws StateAccessException {
        return new EntityIdSetMonitor(vtepZkManager, zkConnection);
    }


    @Override
    public int getNewVni() throws StateAccessException {
        return vtepZkManager.getNewVni();
    }

    @Override
    public IPv4Addr vxlanTunnelEndpointFor(UUID id)
        throws SerializationException, StateAccessException {
        BridgePort port = (BridgePort) portsGet(id);
        return (port == null)? null: vxlanTunnelEndpointFor(port);
    }

    @Override
    public IPv4Addr vxlanTunnelEndpointFor(BridgePort port)
        throws SerializationException, StateAccessException {

        if (!port.isExterior()) {
            throw new IllegalArgumentException("Port " + port.getId() + " is " +
                                               "not exterior");
        }

        Bridge b = bridgesGet(port.getDeviceId());
        if (null == b) {
            log.warn("Bridge {} for port {} does not exist anymore",
                     port.getDeviceId(), port.getId());
            return null;
        }

        return vxlanTunnelEndpointForInternal(b, port);
    }

    /*
     * Utility method to use internaly. It assumes that the caller has verified
     * that both the bridge and port are non-null.
     * It's convenient to slice this out so other methods can fetch the
     * parameters as they prefer, and then use this to retrieve the vxlan
     * tunnel endpoint. The method returns a null IP if the endpoint cannot
     * be retrieved for any reason (bridge unbound, etc.)
     *
     * @param b a bridge, expected to exist, be bound to a VTEP, and contain the
     *          given port
     * @param port a bridge port on the given bridge, that must be exterior
     * @return the IP address of the bound host's membership in the VTEP's
     * tunnel zone.
     */
    private IPv4Addr vxlanTunnelEndpointForInternal(Bridge b, BridgePort port)
        throws SerializationException, StateAccessException {

        if (b.getVxLanPortId() == null) {
            log.warn("Bridge {} is not bound to a vtep", b.getId());
            return null;
        }
        VxLanPort vxlanPort = (VxLanPort)this.portsGet(b.getVxLanPortId());
        if (vxlanPort == null) {
            log.warn("VxLanPort {} at bridge {} does not exist anymore",
                     b.getVxLanPortId(), b.getId());
            return null;
        }
        IPv4Addr vtepMgmtIp = vxlanPort.getMgmtIpAddr();
        log.debug("Port's bridge {} has a VxLanPort {}", port.getId(),
                  vxlanPort.getId());

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
                      "binding to VTEP {}",
                      new Object[]{port.getId(), b.getId(), hostId, tzId,
                          vtepMgmtIp});
            return null;
        }

        return hostCfg.getIp();
    }

    @Override
    public VxLanPort bridgeCreateVxLanPort(
            UUID bridgeId, IPv4Addr mgmtIp, int mgmtPort, int vni)
            throws StateAccessException, SerializationException {

        BridgeConfig bridgeConfig = bridgeZkManager.get(bridgeId);
        if (bridgeConfig.vxLanPortId != null) {
            throw new IllegalStateException(
                    "Attempted to create VxLanPort for bridge " + bridgeId +
                    ", which already has one: " + bridgeConfig.vxLanPortId);
        }

        VxLanPort port = new VxLanPort(bridgeId, mgmtIp, mgmtPort, vni);
        PortConfig portConfig = Converter.toPortConfig(port);

        List<Op> ops = new ArrayList<>();
        ops.addAll(portZkManager.prepareCreate(port.getId(), portConfig));

        bridgeConfig.vxLanPortId = port.getId();
        try {
            ops.addAll(bridgeZkManager.prepareUpdate(
                    bridgeId, bridgeConfig, false));
        } catch (BridgeZkManager.VxLanPortIdUpdateException ex) {
            // Should never happen, since this exception is only
            // thrown when userUpdate is true.
            throw new RuntimeException(ex);
        }

        zkManager.multi(ops);
        return port;
    }

    @Override
    public void bridgeDeleteVxLanPort(UUID bridgeId)
            throws SerializationException, StateAccessException {

        // Make sure the bridge has a VXLAN port.
        BridgeConfig bridgeConfig = bridgeZkManager.get(bridgeId);
        UUID vxLanPortId = bridgeConfig.vxLanPortId;
        if (vxLanPortId == null) {
            throw new IllegalStateException(
                    "Attempted to delete VxLanPort for bridge " +
                    bridgeId + ", which has no VxLanPort.");
        }

        List<Op> ops = new ArrayList<>();

        // Clear bridge's vxLanPortId property.
        bridgeConfig.vxLanPortId = null;
        try {
            ops.addAll(bridgeZkManager.prepareUpdate(
                    bridgeId, bridgeConfig, false));
        } catch (BridgeZkManager.VxLanPortIdUpdateException ex) {
            // This should never happen when userUpdate arg is false.
            throw new RuntimeException("Unexpected exception", ex);
        }

        // Delete the port.
        VxLanPortConfig portConfig =
                (VxLanPortConfig)portZkManager.get(vxLanPortId);
        ops.addAll(portZkManager.prepareDelete(vxLanPortId, portConfig));

        // Delete its bindings in Zookeeper.
        ops.addAll(vtepZkManager.prepareDeleteAllBindings(
                IPv4Addr.fromString(portConfig.mgmtIpAddr),
                portConfig.device_id));

        zkManager.multi(ops);
    }

    @Override
    public UUID tryOwnVtep(IPv4Addr mgmtIp, UUID nodeId)
    throws SerializationException, StateAccessException {
        return this.vtepZkManager.tryOwnVtep(mgmtIp, nodeId);
    }

    @Override
    public boolean portWatch(UUID portId, Directory.TypedWatcher typedWatcher)
        throws StateAccessException, SerializationException {
        return null != portZkManager.get(portId, typedWatcher);
    }

    @Override
    public void vxLanPortIdsAsyncGet(DirectoryCallback<Set<UUID>> callback,
                                     Directory.TypedWatcher watcher)
            throws StateAccessException {
        portZkManager.getVxLanPortIdsAsync(callback, watcher);
    }

    @Override
    public void licenseCreate(UUID licenseId, byte[] licenseData)
            throws StateAccessException {
        licenseZkManager.create(new LicenseZkManager.LicenseConfig(licenseId,
                                                                   licenseData));
    }

    @Override
    public void licenseDelete(UUID licenseId) throws StateAccessException {
        licenseZkManager.delete(licenseId);
    }

    @Override
    public byte[] licenseSelect(UUID licenseId) throws StateAccessException {
        return licenseZkManager.select(licenseId);
    }

    @Override
    public Collection<UUID> licenseList() throws StateAccessException {
        return licenseZkManager.list();
    }
}
