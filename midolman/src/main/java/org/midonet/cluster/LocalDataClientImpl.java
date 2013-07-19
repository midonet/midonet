/*
 * Copyright 2012 Midokura Europe SARL
 * Copyright 2013 Midokura Pte Ltd
 */
package org.midonet.cluster;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
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

import org.apache.zookeeper.Op;
import org.midonet.midolman.rules.RuleList;
import org.midonet.packets.IPv4Addr;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cache.Cache;
import static org.midonet.midolman.guice.CacheModule.TRACE_MESSAGES;
import static org.midonet.midolman.guice.CacheModule.TRACE_INDEX;
import org.midonet.midolman.guice.zookeeper.ZKConnectionProvider;
import org.midonet.midolman.host.commands.HostCommandGenerator;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.monitoring.store.Store;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.*;
import org.midonet.midolman.state.zkManagers.*;
import org.midonet.cluster.data.*;
import org.midonet.cluster.data.Entity.TaggableEntity;
import org.midonet.cluster.data.dhcp.Subnet;
import org.midonet.cluster.data.dhcp.Subnet6;
import org.midonet.cluster.data.dhcp.V6Host;
import org.midonet.cluster.data.host.Command;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.host.Interface;
import org.midonet.cluster.data.host.VirtualPortMapping;
import org.midonet.cluster.data.ports.LogicalBridgePort;
import org.midonet.cluster.data.ports.LogicalRouterPort;
import org.midonet.packets.IntIPv4;
import org.midonet.packets.IPv6Subnet;
import org.midonet.packets.MAC;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.functors.Callback2;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;

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
    private VlanAwareBridgeZkManager vlanBridgeZkManager;

    @Inject
    private TraceConditionZkManager traceConditionZkManager;

    @Inject
    private ZkManager zkManager;

    @Inject
    private PathBuilder pathBuilder;

    @Inject
    private ClusterRouterManager routerManager;

    @Inject
    private ClusterBridgeManager bridgeManager;

    @Inject
    private ClusterVlanBridgeManager vlanBridgeManager;

    @Inject
    private Serializer serializer;

    @Inject
    private TaggableConfigZkManager taggableConfigZkManager;

    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    private Reactor reactor;

    final Queue<Callback2<UUID, Boolean>> subscriptionPortsActive =
        new ConcurrentLinkedQueue<Callback2<UUID, Boolean>>();

    @Inject
    private Store monitoringStore;

    @Inject @Nullable
    @TRACE_INDEX
    private Cache traceIndexCache;

    @Inject @Nullable
    @TRACE_MESSAGES
    private Cache traceMessageCache;

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
        return bgpZkManager.getBGP(id);
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

    @Override
    public @CheckForNull VlanAwareBridge vlanBridgesGetByName(String tenantId,
                                                              String name)
            throws StateAccessException, SerializationException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        VlanAwareBridge bridge = null;
        String path = pathBuilder.getTenantVlanBridgeNamePath(tenantId, name);

        if (zkManager.exists(path)) {
            byte[] data = zkManager.get(path);
            VlanBridgeName.Data nameData =
                    serializer.deserialize(data,
                            VlanBridgeName.Data.class);
            bridge = vlanBridgesGet(nameData.id);
        }

        log.debug("Exiting: vlan-bridge={}", bridge);
        return bridge;
    }

    @Override
    public @CheckForNull Bridge bridgesGetByName(String tenantId, String name)
            throws StateAccessException, SerializationException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        Bridge bridge = null;
        String path = pathBuilder.getTenantBridgeNamePath(tenantId, name);

        if (zkManager.exists(path)) {
            byte[] data = zkManager.get(path);
            BridgeName.Data bridgeNameData =
                    serializer.deserialize(data,
                            BridgeName.Data.class);
            bridge = bridgesGet(bridgeNameData.id);
        }

        log.debug("Exiting: bridge={}", bridge);
        return bridge;
    }

    @Override
    public List<Bridge> bridgesFindByTenant(String tenantId)
            throws StateAccessException, SerializationException {
        log.debug("bridgesFindByTenant entered: tenantId={}", tenantId);

        List<Bridge> bridges = new ArrayList<Bridge>();

        String path = pathBuilder.getTenantBridgeNamesPath(tenantId);
        if (zkManager.exists(path)) {
            Set<String> bridgeNames = zkManager.getChildren(path);
            for (String name : bridgeNames) {
                Bridge bridge = bridgesGetByName(tenantId, name);
                if (bridge != null) {
                    bridges.add(bridge);
                }
            }
        }

        log.debug("bridgesFindByTenant exiting: {} bridges found",
                bridges.size());
        return bridges;
    }

    @Override
    public Map<MAC, UUID> bridgeGetMacPorts(@Nonnull UUID bridgeId)
            throws StateAccessException {
        return MacPortMap.getAsMap(
            bridgeZkManager.getMacPortMapDirectory(bridgeId));
    }

    @Override
    public void bridgeAddMacPort(
        @Nonnull UUID bridgeId, @Nonnull MAC mac, @Nonnull UUID portId)
            throws StateAccessException {
        MacPortMap.addPersistentEntry(
            bridgeZkManager.getMacPortMapDirectory(bridgeId), mac, portId);
    }

    @Override
    public boolean bridgeHasMacPort(
        @Nonnull UUID bridgeId, @Nonnull MAC mac, @Nonnull UUID portId)
            throws StateAccessException {
        return MacPortMap.hasPersistentEntry(
            bridgeZkManager.getMacPortMapDirectory(bridgeId), mac, portId);
    }

    @Override
    public void bridgeDeleteMacPort(
            @Nonnull UUID bridgeId, @Nonnull MAC mac, @Nonnull UUID portId)
            throws StateAccessException {
        MacPortMap.deleteEntry(
            bridgeZkManager.getMacPortMapDirectory(bridgeId), mac, portId);
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
    public UUID vlanBridgesCreate(@Nonnull VlanAwareBridge bridge)
            throws StateAccessException, SerializationException {

        log.debug("vlanBridgesCreate entered: vlan-bridge={}", bridge);
        if (bridge.getId() == null) {
            bridge.setId(UUID.randomUUID());
        }

        VlanAwareBridgeZkManager.VlanBridgeConfig config =
            Converter.toVlanBridgeConfig(bridge);

        List<Op> ops = vlanBridgeZkManager
                       .prepareVlanBridgeCreate(bridge.getId(), config);

        // Create the top level directories for
        String tenantId = bridge.getProperty(
                                 VlanAwareBridge.Property.tenant_id);
        ops.addAll(tenantZkManager.prepareCreate(tenantId));

        // Create the bridge names directory if it does not exist
        String bridgeNamesPath = pathBuilder
            .getTenantVlanBridgeNamesPath(tenantId);
        if (!zkManager.exists(bridgeNamesPath)) {
            ops.add(zkManager
                    .getPersistentCreateOp(bridgeNamesPath, null));
        }

        // Index the name
        String bridgeNamePath = pathBuilder
            .getTenantVlanBridgeNamePath(tenantId, config.getName());
        byte[] data = serializer
            .serialize((new VlanBridgeName(bridge)).getData());
        ops.add(zkManager.getPersistentCreateOp(bridgeNamePath, data));

        zkManager.multi(ops);

        log.debug("VlanBridgeZkDaoImpl.create exiting: vlan-bridge={}", bridge);
        return bridge.getId();
    }

    @Override
    public List<VlanAwareBridge> vlanBridgesFindByTenant(String tenantId)
            throws StateAccessException, SerializationException {
        List<VlanAwareBridge> bridges = new ArrayList<VlanAwareBridge>();

        String path = pathBuilder.getTenantVlanBridgeNamesPath(tenantId);
        if (zkManager.exists(path)) {
            Set<String> bridgeNames = zkManager.getChildren(path);
            for (String name : bridgeNames) {
                VlanAwareBridge bridge = vlanBridgesGetByName(tenantId, name);
                if (bridge != null) {
                    bridges.add(bridge);
                }
            }
        }
        return bridges;
    }

    @Override
    public VlanAwareBridge vlanBridgesGet(UUID id)
            throws StateAccessException, SerializationException {

        VlanAwareBridge bridge = null;
        if (vlanBridgeZkManager.exists(id)) {
            bridge = Converter.fromVLANBridgeConfig(vlanBridgeZkManager.get(id));
            bridge.setId(id);
        }

        return bridge;
    }

    @Override
    public void vlanBridgesUpdate(@Nonnull VlanAwareBridge bridge)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Get the original data
        VlanAwareBridge oldBridge = vlanBridgesGet(bridge.getId());

        VlanAwareBridgeZkManager.VlanBridgeConfig config =
            Converter.toVlanBridgeConfig(bridge);

        // Update the config
        Op op = vlanBridgeZkManager.prepareUpdate(bridge.getId(), config);
        if (op != null) {
            ops.add(op);
        }

        // Update index if the name changed
        String oldName = oldBridge.getData().name;
        String newName = config.getName();
        if (oldName == null ? newName != null : !oldName.equals(newName)) {
            String tenantId = oldBridge
                              .getProperty(VlanAwareBridge.Property.tenant_id);

            String path = pathBuilder.getTenantVlanBridgeNamePath(tenantId, oldName);
            ops.add(zkManager.getDeleteOp(path));

            path = pathBuilder.getTenantVlanBridgeNamePath(tenantId, newName);
            byte[] data = serializer.serialize(
                    new VlanBridgeName(bridge).getData());
            ops.add(zkManager.getPersistentCreateOp(path, data));
        }

        if (ops.size() > 0) {
            zkManager.multi(ops);
        }
    }

    @Override
    public void vlanBridgesDelete(UUID id)
            throws StateAccessException, SerializationException {
        VlanAwareBridge bridge = vlanBridgesGet(id);
        if (bridge == null) {
            return;
        }
        List<Op> ops = vlanBridgeZkManager.prepareVlanBridgeDelete(id);
        String path = pathBuilder.getTenantVlanBridgeNamePath(
            bridge.getProperty(VlanAwareBridge.Property.tenant_id),
            bridge.getData().name);
        ops.add(zkManager.getDeleteOp(path));
        zkManager.multi(ops);
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

        // Create the bridge names directory if it does not exist
        String bridgeNamesPath = pathBuilder.getTenantBridgeNamesPath(tenantId);
        if (!zkManager.exists(bridgeNamesPath)) {
            ops.add(zkManager.getPersistentCreateOp(
                    bridgeNamesPath, null));
        }

        // Index the name
        String bridgeNamePath = pathBuilder.getTenantBridgeNamePath(tenantId,
                bridgeConfig.name);
        byte[] data = serializer.serialize((
                new BridgeName(bridge)).getData());
        ops.add(zkManager.getPersistentCreateOp(bridgeNamePath, data));

        zkManager.multi(ops);

        log.debug("BridgeZkDaoImpl.create exiting: bridge={}", bridge);
        return bridge.getId();
    }

    @Override
    public void bridgesUpdate(@Nonnull Bridge bridge)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Get the original data
        Bridge oldBridge = bridgesGet(bridge.getId());

        BridgeZkManager.BridgeConfig bridgeConfig = Converter.toBridgeConfig(
            bridge);

        // Update the config
        Op op = bridgeZkManager.prepareUpdate(bridge.getId(), bridgeConfig);
        if (op != null) {
            ops.add(op);
        }

        // Update index if the name changed
        String oldName = oldBridge.getData().name;
        String newName = bridgeConfig.name;
        if (oldName == null ? newName != null : !oldName.equals(newName)) {

            String tenantId = oldBridge.getProperty(Bridge.Property.tenant_id);

            String path = pathBuilder.getTenantBridgeNamePath(tenantId,
                    oldBridge.getData().name);
            ops.add(zkManager.getDeleteOp(path));

            path = pathBuilder.getTenantBridgeNamePath(tenantId,
                    bridgeConfig.name);
            byte[] data = serializer.serialize(
                    new BridgeName(bridge).getData());
            ops.add(zkManager.getPersistentCreateOp(path, data));
        }

        if (ops.size() > 0) {
            zkManager.multi(ops);
        }
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
        String path = pathBuilder.getTenantBridgeNamePath(
                bridge.getProperty(Bridge.Property.tenant_id),
                bridge.getData().name);
        ops.add(zkManager.getDeleteOp(path));

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

        List<Op> ops = chainZkManager.prepareChainDelete(id);
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
                chainZkManager.prepareChainCreate(chain.getId(), chainConfig);

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
    public UUID tunnelZonesCreate(@Nonnull TunnelZone<?, ?> zone)
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
    public @CheckForNull TunnelZone<?, ?> tunnelZonesGet(UUID uuid)
            throws StateAccessException, SerializationException {
        return zonesZkManager.getZone(uuid, null);
    }

    @Override
    public List<TunnelZone<?, ?>> tunnelZonesGetAll()
            throws StateAccessException, SerializationException {
        Collection<UUID> ids = zonesZkManager.getZoneIds();

        List<TunnelZone<?, ?>> tunnelZones = new ArrayList<TunnelZone<?, ?>>();

        for (UUID id : ids) {
            TunnelZone zone = tunnelZonesGet(id);
            if (zone != null) {
                tunnelZones.add(zone);
            }
        }

        return tunnelZones;
    }

    @Override
    public void tunnelZonesUpdate(@Nonnull TunnelZone<?, ?> zone)
            throws StateAccessException, SerializationException {
        zonesZkManager.updateZone(zone);
    }

    @Override
    public boolean tunnelZonesMembershipExists(UUID uuid, UUID hostId)
            throws StateAccessException {
        return zonesZkManager.membershipExists(uuid, hostId);
    }

    @Override
    public @CheckForNull TunnelZone.HostConfig<?, ?> tunnelZonesGetMembership(UUID id,
                                                                UUID hostId)
            throws StateAccessException, SerializationException {
        return zonesZkManager.getZoneMembership(id, hostId, null);
    }

    @Override
    public Set<TunnelZone.HostConfig<?, ?>> tunnelZonesGetMemberships(
            final UUID uuid)
        throws StateAccessException {

        return CollectionFunctors.map(
            zonesZkManager.getZoneMemberships(uuid, null),
            new Functor<UUID, TunnelZone.HostConfig<?, ?>>() {
                @Override
                public TunnelZone.HostConfig<?, ?> apply(UUID arg0) {
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
            new HashSet<TunnelZone.HostConfig<?, ?>>()
        );
    }

    @Override
    public UUID tunnelZonesAddMembership(UUID zoneId, TunnelZone.HostConfig<?, ?> hostConfig)
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
                if (config instanceof PortDirectory.MaterializedRouterPortConfig) {
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
        dhcpZkManager.updateSubnet(bridgeId,
                                   Converter.toDhcpSubnetConfig(subnet));
    }

    @Override
    public void dhcpSubnetsDelete(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException {
        dhcpZkManager.deleteSubnet(bridgeId, subnetAddr);
    }

    @Override
    public @CheckForNull Subnet dhcpSubnetsGet(UUID bridgeId, IntIPv4 subnetAddr)
            throws StateAccessException, SerializationException {

        Subnet subnet = null;
        if (dhcpZkManager.existsSubnet(bridgeId, subnetAddr)) {
            BridgeDhcpZkManager.Subnet subnetConfig =
                dhcpZkManager.getSubnet(bridgeId, subnetAddr);

            subnet = Converter.fromDhcpSubnetConfig(subnetConfig);
            subnet.setId(subnetAddr.toString());
        }

        return subnet;
    }

    @Override
    public List<Subnet> dhcpSubnetsGetByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException {

        List<IntIPv4> subnetConfigs = dhcpZkManager.listSubnets(bridgeId);
        List<Subnet> subnets = new ArrayList<Subnet>(subnetConfigs.size());

        for (IntIPv4 subnetConfig : subnetConfigs) {
            subnets.add(dhcpSubnetsGet(bridgeId, subnetConfig));
        }

        return subnets;
    }

    @Override
    public void dhcpHostsCreate(
            UUID bridgeId, IntIPv4 subnet,
            org.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException, SerializationException {

        dhcpZkManager.addHost(bridgeId, subnet,
                Converter.toDhcpHostConfig(host));
    }

    @Override
    public void dhcpHostsUpdate(
            UUID bridgeId, IntIPv4 subnet,
            org.midonet.cluster.data.dhcp.Host host)
            throws StateAccessException, SerializationException {
        dhcpZkManager.updateHost(bridgeId, subnet,
                                 Converter.toDhcpHostConfig(host));
    }

    @Override
    public @CheckForNull org.midonet.cluster.data.dhcp.Host dhcpHostsGet(
            UUID bridgeId, IntIPv4 subnet, String mac)
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
    public void dhcpHostsDelete(UUID bridgId, IntIPv4 subnet, String mac)
            throws StateAccessException {
        dhcpZkManager.deleteHost(bridgId, subnet, mac);
    }

    @Override
    public
    List<org.midonet.cluster.data.dhcp.Host> dhcpHostsGetBySubnet(
            UUID bridgeId, IntIPv4 subnet)
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
            HostDirectory.Metadata hostMetadata =
                    hostZkManager.getHostMetadata(hostId);

            if (hostMetadata == null) {
                log.error("Failed to fetch metadata for host {}", hostId);
                return null;
            }

            host = Converter.fromHostConfig(hostMetadata);
            host.setId(hostId);
            host.setIsAlive(hostsIsAlive(hostId));
        }

        return host;
    }

    @Override
    public void hostsDelete(UUID hostId) throws StateAccessException {
        hostZkManager.deleteHost(hostId);
    }

    @Override
    public boolean hostsExists(UUID hostId) throws StateAccessException {
        return hostZkManager.hostExists(hostId);
    }

    @Override
    public boolean hostsIsAlive(UUID hostId) throws StateAccessException {
        return hostZkManager.isAlive(hostId);
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
    public List<Port<?, ?>> trunkPortsFindByVlanBridge(UUID bridgeId)
            throws StateAccessException, SerializationException {

        Set<UUID> ids = portZkManager.getVlanBridgeTrunkPortIDs(bridgeId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>();
        for (UUID id : ids) {
            ports.add(portsGet(id));
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> interiorPortsFindByVlanBridge(UUID bridgeId)
            throws StateAccessException, SerializationException {

        Set<UUID> ids = portZkManager.getVlanBridgeLogicalPortIDs(bridgeId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>();
        for (UUID id : ids) {
            ports.add(portsGet(id));
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsFindByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException {

        Set<UUID> ids = portZkManager.getBridgePortIDs(bridgeId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>();
        for (UUID id : ids) {
            ports.add(portsGet(id));
        }

        ids = portZkManager.getBridgeLogicalPortIDs(bridgeId);
        for (UUID id : ids) {
            ports.add(portsGet(id));
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsFindPeersByBridge(UUID bridgeId)
            throws StateAccessException, SerializationException {

        Set<UUID> ids = portZkManager.getBridgeLogicalPortIDs(bridgeId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>();
        for (UUID id : ids) {
            Port<?, ?> portData = portsGet(id);
            if (portData instanceof LogicalBridgePort &&
                    ((LogicalBridgePort) portData).getPeerId() != null) {
                ports.add(portsGet(((LogicalBridgePort) portData).getPeerId()));
            }
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsFindByRouter(UUID routerId)
            throws StateAccessException, SerializationException {

        Set<UUID> ids = portZkManager.getRouterPortIDs(routerId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>();
        for (UUID id : ids) {
            ports.add(portsGet(id));
        }

        return ports;
    }

    @Override
    public List<Port<?, ?>> portsFindPeersByRouter(UUID routerId)
            throws StateAccessException, SerializationException {

        Set<UUID> ids = portZkManager.getRouterPortIDs(routerId);
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>();
        for (UUID id : ids) {
            Port<?, ?> portData = portsGet(id);
            if (portData instanceof LogicalRouterPort &&
                    ((LogicalRouterPort) portData).getPeerId() != null) {
                ports.add(portsGet(((LogicalRouterPort) portData).getPeerId()));
            }
        }

        return ports;
    }

    @Override
    public UUID portsCreate(@Nonnull final Port port)
            throws StateAccessException, SerializationException {
        return portZkManager.create(Converter.toPortConfig(port));
    }

    @Override
    public @CheckForNull Port portsGet(UUID id)
            throws StateAccessException, SerializationException {
        Port port = null;
        if (portZkManager.exists(id)) {
            port = Converter.fromPortConfig(portZkManager.get(id));
            port.setId(id);
        }

        return port;
    }

    @Override
    public void portsUpdate(@Nonnull Port port)
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
        List<Port<?, ?>> ports = new ArrayList<Port<?, ?>>(portIds.size());
        for (UUID portId : portIds) {
            ports.add(portsGet(portId));
        }

        return ports;
    }

    @Override
    public PortGroup portGroupsGetByName(String tenantId, String name)
            throws StateAccessException, SerializationException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        PortGroup portGroup = null;
        String path = pathBuilder.getTenantPortGroupNamePath(tenantId, name)
                .toString();

        if (zkManager.exists(path)) {
            byte[] data = zkManager.get(path);
            PortGroupName.Data portGroupNameData =
                    serializer.deserialize(data,
                            PortGroupName.Data.class);
            portGroup = portGroupsGet(portGroupNameData.id);
        }

        log.debug("Exiting: portGroup={}", portGroup);
        return portGroup;
    }

    @Override
    public List<PortGroup> portGroupsFindByTenant(String tenantId)
            throws StateAccessException, SerializationException {
        log.debug("portGroupsFindByTenant entered: tenantId={}", tenantId);

        List<PortGroup> portGroups = new ArrayList<PortGroup>();

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

        List<PortGroup> portGroups = new ArrayList<PortGroup>();

        if (portsExists(portId)) {
            Port port = portsGet(portId);
            Set<UUID> portGroupIds = port.getPortGroups();
            for (UUID portGroupId : portGroupIds) {
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
    public @CheckForNull PortGroup portGroupsGet(UUID id)
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
    public Map<String, Long> metricsGetTSPoints(String type,
                                                String targetIdentifier,
                                                String metricName,
                                                long timeStart,
                                                long timeEnd) {
        return monitoringStore.getTSPoints(type, targetIdentifier, metricName,
                timeStart, timeEnd);
    }

    @Override
    public void metricsAddTypeToTarget(@Nonnull String targetIdentifier, @Nonnull String type) {
        monitoringStore.addMetricTypeToTarget(targetIdentifier, type);
    }

    @Override
    public List<String> metricsGetTypeForTarget(String targetIdentifier) {
        return monitoringStore.getMetricsTypeForTarget(targetIdentifier);
    }

    @Override
    public void metricsAddToType(@Nonnull String type, @Nonnull String metricName) {
        monitoringStore.addMetricToType(type, metricName);
    }

    @Override
    public List<String> metricsGetForType(String type) {
        return monitoringStore.getMetricsForType(type);
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
        List<Route> routes = new ArrayList<Route>();
        for (UUID id : routeIds) {
            routes.add(routesGet(id));
        }
        return routes;

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
        String path = pathBuilder.getTenantRouterNamePath(
                router.getProperty(Router.Property.tenant_id),
                router.getData().name);
        ops.add(zkManager.getDeleteOp(path));

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

        // Create the router names directory if it does not exist
        String routerNamesPath = pathBuilder.getTenantRouterNamesPath(tenantId);
        if (!zkManager.exists(routerNamesPath)) {
            ops.add(zkManager.getPersistentCreateOp(
                    routerNamesPath, null));
        }

        // Index the name
        String routerNamePath = pathBuilder.getTenantRouterNamePath(tenantId,
                routerConfig.name);
        byte[] data = serializer.serialize(
                (new RouterName(router)).getData());
        ops.add(zkManager.getPersistentCreateOp(routerNamePath, data));

        zkManager.multi(ops);

        log.debug("routersCreate exiting: router={}", router);
        return router.getId();
    }

    @Override
    public void routersUpdate(@Nonnull Router router) throws
            StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();

        // Get the original data
        Router oldRouter = routersGet(router.getId());

        RouterZkManager.RouterConfig routerConfig = Converter.toRouterConfig(
            router);


        // Update the config
        Op op = routerZkManager.prepareUpdate(router.getId(), routerConfig);
        if (op != null) {
            ops.add(op);
        }

        // Update index if the name changed
        String oldName = oldRouter.getData().name;
        String newName = routerConfig.name;
        if (oldName == null ? newName != null : !oldName.equals(newName)) {

            String tenantId = oldRouter.getProperty(Router.Property.tenant_id);

            String path = pathBuilder.getTenantRouterNamePath(tenantId,
                    oldRouter.getData().name);
            ops.add(zkManager.getDeleteOp(path));

            path = pathBuilder.getTenantRouterNamePath(tenantId,
                    routerConfig.name);
            byte[] data = serializer.serialize(
                    new RouterName(router).getData());
            ops.add(zkManager.getPersistentCreateOp(path, data));
        }

        if (ops.size() > 0) {
            zkManager.multi(ops);
        }
    }

    @Override
    public @CheckForNull Router routersGetByName(@Nonnull String tenantId, @Nonnull String name)
            throws StateAccessException, SerializationException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        Router router = null;
        String path = pathBuilder.getTenantRouterNamePath(tenantId, name)
                .toString();

        if (zkManager.exists(path)) {
            byte[] data = zkManager.get(path);
            RouterName.Data routerNameData =
                    serializer.deserialize(data, RouterName.Data.class);
            router = routersGet(routerNameData.id);
        }

        log.debug("Exiting: router={}", router);
        return router;
    }

    @Override
    public List<Router> routersFindByTenant(String tenantId)
            throws StateAccessException, SerializationException {
        log.debug("routersFindByTenant entered: tenantId={}", tenantId);

        List<Router> routers = new ArrayList<Router>();

        String path = pathBuilder.getTenantRouterNamesPath(tenantId);
        if (zkManager.exists(path)) {
            Set<String> routerNames = zkManager.getChildren(path);
            for (String name : routerNames) {
                Router router = routersGetByName(tenantId, name);
                if (router != null) {
                    routers.add(router);
                }
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

        Rule rule = null;
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
        List<Rule<?, ?>> rules = new ArrayList<Rule<?, ?>>();

        int position = 1;
        for (UUID id : ruleIds) {
            Rule rule = rulesGet(id);
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
    public Set<UUID> portSetsGet(UUID portSetId) throws StateAccessException {
        return portSetZkManager.getPortSet(portSetId, null);
    }

    /* Trace condition methods */
    @Override
    public UUID traceConditionCreate(@Nonnull TraceCondition traceCondition)
        throws StateAccessException, SerializationException
    {
        return traceConditionZkManager.create(traceCondition.getCondition());
    }

    @Override
    public void traceConditionDelete(UUID uuid)
        throws StateAccessException
    {
        traceConditionZkManager.delete(uuid);
    }

    @Override
    public boolean traceConditionExists(UUID uuid) throws StateAccessException
    {
        return traceConditionZkManager.exists(uuid);
    }

    public @CheckForNull TraceCondition traceConditionGet(UUID uuid)
        throws StateAccessException, SerializationException
    {
        Condition condition = traceConditionZkManager.get(uuid);
        TraceCondition traceCondition = new TraceCondition(uuid, condition);
        return traceCondition;
    }

    @Override
    public List<TraceCondition> traceConditionsGetAll()
        throws StateAccessException, SerializationException
    {
        Collection<UUID> ids = traceConditionZkManager.getIds();
        List<TraceCondition> traceConditions = new ArrayList<TraceCondition>();

        for (UUID id : ids) {
            TraceCondition traceCondition = traceConditionGet(id);
            if (traceCondition != null) {
                traceConditions.add(traceCondition);
            }
        }

        return traceConditions;
    }

    @Override
    public Map<String, String> traceIdList(int maxEntries) {
        return(traceIndexCache.dump(maxEntries));
    }

    @Override
    public void traceIdDelete(UUID traceId) {
        traceIndexCache.delete(traceId.toString());
    }

    @Override
    public List<String> packetTraceGet(UUID traceId) {
        List<String> retList = new ArrayList<String>();
        String key = "trace" + traceId;
        int numTraceMessages = 0;
        String numTraceMessagesStr = traceIndexCache.get(traceId.toString());
        if (numTraceMessagesStr == null) return null;
        numTraceMessages = Integer.parseInt(numTraceMessagesStr);
        for (int traceStep = 1; traceStep <= numTraceMessages; traceStep++) {
            key = key + ":" + traceStep;
            String retVal = traceMessageCache.get(key);
            if (retVal == null) {
                log.error("Number of Trace Messages out of sync " +
                        "with Trace Index table");
                break;
            }
            retList.add(retVal);
        }
        return retList;
    }

    @Override
    public void packetTraceDelete(UUID traceId) {
        int numTraceMessages = 0;
        String numTraceMessagesStr = traceIndexCache.get(traceId.toString());
        String key = "trace" + traceId;
        if (numTraceMessagesStr == null) return;
        numTraceMessages = Integer.parseInt(numTraceMessagesStr);
        for (int traceStep = 1; traceStep <= numTraceMessages; traceStep++) {
            key = key + ":" + traceStep;
            traceMessageCache.delete(key);
        }
    }
}
