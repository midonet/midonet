/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import javax.annotation.Nonnull;
import javax.inject.Inject;
import javax.inject.Named;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.layer3.L3DevicePort;
import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.PathBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortConfigCache;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import com.midokura.midolman.state.zkManagers.PortZkManager;
import com.midokura.midolman.state.zkManagers.TunnelZoneZkManager;
import com.midokura.midolman.state.zkManagers.RouteZkManager;
import com.midokura.midonet.cluster.client.RouterBuilder;
import com.midokura.midonet.cluster.data.Bridge;
import com.midokura.midonet.cluster.data.BridgeName;
import com.midokura.midonet.cluster.data.Bridges;
import com.midokura.midonet.cluster.data.Host;
import com.midokura.midonet.cluster.data.Hosts;
import com.midokura.midonet.cluster.data.Port;
import com.midokura.midonet.cluster.data.Ports;
import com.midokura.midonet.cluster.data.TunnelZone;
import com.midokura.util.functors.Callback2;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.functors.Callback2;
import com.midokura.util.functors.CollectionFunctors;
import com.midokura.util.functors.Functor;


public class LocalDataClientImpl implements DataClient {

    @Inject
    private BridgeZkManager bridgeZkManager;

    @Inject
    private PortZkManager portZkManager;

    @Inject
    private PortConfigCache portCache;

    @Inject
    private RouteZkManager routeMgr;

    @Inject
    private HostZkManager hostZkManager;

    @Inject
    private TunnelZoneZkManager zonesZkManager;

    @Inject
    private PathBuilder pathBuilder;

    @Inject
    private ZkConfigSerializer serializer;

    @Inject
    private ClusterRouterManager routerManager;

    @Inject
    private ClusterBridgeManager bridgeManager;

    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    private Reactor reactor;

    Set<Callback2<UUID, Boolean>> subscriptionPortsActive =
        new HashSet<Callback2<UUID, Boolean>>();

    private final static Logger log =
        LoggerFactory.getLogger(LocalDataClientImpl.class);

    @Override
    public Bridge bridgesGetByName(String tenantId, String name)
        throws StateAccessException {
        log.debug("Entered: tenantId={}, name={}", tenantId, name);

        Bridge bridge = null;
        String path = pathBuilder.getTenantBridgeNamePath(tenantId, name)
                                 .toString();

        if (bridgeZkManager.exists(path)) {
            byte[] data = bridgeZkManager.get(path);
            BridgeName.Data bridgeNameData =
                serializer.deserialize(data, BridgeName.Data.class);
            bridge = bridgesGet(bridgeNameData.id);
        }

        log.debug("Exiting: bridge={}", bridge);
        return bridge;
    }

    @Override
    public UUID bridgesCreate(@Nonnull Bridge bridge)
        throws StateAccessException {
        log.debug("BridgeZkDaoImpl.create entered: bridge={}", bridge);

        if (bridge.getId() == null) {
            bridge.setId(UUID.randomUUID());
        }

        BridgeZkManager.BridgeConfig bridgeConfig =
            Bridges.toBridgeZkConfig(bridge);

        List<Op> ops =
            bridgeZkManager.prepareBridgeCreate(bridge.getId(), bridgeConfig);

//        BridgeName bridgeName = new BridgeName(bridge);
//
//        byte[] data = serializer.serialize(bridgeName);
//        ops.add(
//            bridgeZkManager.getPersistentCreateOp(
//                pathBuilder.getTenantBridgeNamePath(
//                    bridge.getProperty(Bridge.Property.tenant_id),
//                    bridgeConfig.name),
//                data));

        bridgeZkManager.multi(ops);

        log.debug("BridgeZkDaoImpl.create exiting: bridge={}", bridge);
        return bridge.getId();
    }


    @Override
    public Bridge bridgesGet(UUID id) throws StateAccessException {
        log.debug("Entered: id={}", id);

        Bridge bridge = null;
        if (bridgeZkManager.exists(id)) {
            bridge = Bridges.fromZkBridgeConfig(bridgeZkManager.get(id));
        }

        log.debug("Exiting: bridge={}", bridge);
        return bridge;
    }

    @Override
    public void bridgesDelete(UUID id) throws StateAccessException {
        bridgeZkManager.delete(id);
    }

    @Override
    public boolean portsExists(UUID id) throws StateAccessException {
        return portZkManager.exists(id);
    }

    @Override
    public UUID portsCreate(Port<?, ?> port) throws StateAccessException {
        return portZkManager.create(Ports.toPortConfig(port));
    }

    @Override
    public Port<?, ?> portsGet(UUID id) throws StateAccessException {
        return Ports.fromPortConfig(portZkManager.get(id));
    }

    @Override
    public void subscribeToLocalActivePorts(Callback2<UUID, Boolean> cb) {
        //TODO(ross) notify when the port goes down
        subscriptionPortsActive.add(cb);
    }

    @Override
    public UUID tunnelZonesCreate(TunnelZone<?, ?> zone)
        throws StateAccessException {
        return zonesZkManager.createZone(zone, null);
    }

    @Override
    public void tunnelZonesDelete(UUID uuid)
        throws StateAccessException {
        zonesZkManager.deleteZone(uuid);
    }

    @Override
    public TunnelZone<?, ?> tunnelZonesGet(UUID uuid)
        throws StateAccessException {
        return zonesZkManager.getZone(uuid, null);
    }

    @Override
    public Set<TunnelZone.HostConfig<?, ?>> tunnelZonesGetMembership(final UUID uuid)
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
                    }
                }
            },
            new HashSet<TunnelZone.HostConfig<?, ?>>()
        );
    }

    @Override
    public UUID tunnelZonesAddMembership(UUID zoneId, TunnelZone.HostConfig<?, ?> hostConfig)
        throws StateAccessException {
        zonesZkManager.delMembership(zoneId, hostConfig.getId());
        return zonesZkManager.addMembership(zoneId, hostConfig);
    }

    @Override
    public void tunnelZonesDeleteMembership(UUID zoneId, UUID membershipId)
        throws StateAccessException {
        zonesZkManager.delMembership(zoneId, membershipId);
    }

    @Override
    public UUID hostsCreate(UUID hostId, Host host)
        throws StateAccessException {
        hostZkManager.createHost(hostId, Hosts.toHostConfig(host));
        return hostId;
    }

    @Override
    public void hostsAddVrnPortMapping(UUID hostId, UUID portId,
                                       String localPortName)
        throws StateAccessException {
        hostZkManager.addVirtualPortMapping(hostId,
                                            new HostDirectory.VirtualPortMapping(
                                                portId, localPortName));
    }

    @Override
    public void hostsAddDatapathMapping(UUID hostId, String datapathName)
        throws StateAccessException {
        hostZkManager.addVirtualDatapathMapping(hostId, datapathName);
    }

    @Override
    public void hostsRemoveVrnPortMapping(UUID hostId, UUID portId)
        throws StateAccessException {
        hostZkManager.removeVirtualPortMapping(hostId, portId);
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
                }
                // update the subscribers
                for (Callback2<UUID, Boolean> cb : subscriptionPortsActive) {
                    cb.call(portID, active);
                }
                // If it's a MaterializedBridgePort, invalidate the flows for flooded
                // packet because when those were update this port was probably
                // inactive and wasn't taken into consideration when installing
                // the flow for the flood.
                if (config instanceof PortDirectory.MaterializedBridgePortConfig) {
                    bridgeManager.getBuilder(config.device_id)
                                 .setMaterializedPortActive(
                                     portID,
                                     ((PortDirectory.MaterializedRouterPortConfig)
                                         config)
                                         .getHwAddr(),
                                     active);
                    //TODO(ross) add to port set
                } else if (config instanceof PortDirectory.MaterializedRouterPortConfig) {
                    final UUID deviceId = config.device_id;
                    try {
                        L3DevicePort port = new L3DevicePort(portCache,
                                                             routeMgr, portID);
                        RouterBuilder builder = routerManager.getBuilder(
                            deviceId);
                        // register a watcher
                        port.addListener(new RouterPortListener(builder));
                        for (Route rt : port.getRoutes()) {
                            if (active) {
                                builder.addRoute(rt);
                            } else {
                                builder.removeRoute(rt);
                            }
                        }
                        builder.build();
                    } catch (Exception e) {
                        log.error(
                            "Error creating the L3DevicePort for port {} ",
                            portID, e);
                    }

                }
            }
        });
    }

    private class RouterPortListener implements L3DevicePort.Listener {
        private RouterBuilder builder;

        private RouterPortListener(RouterBuilder builder) {
            this.builder = builder;
        }

        @Override
        public void routesChanged(UUID portId, Collection<Route> added,
                                  Collection<Route> removed) {
            for (Route rt : added) {
                log.debug("{} routesChanged adding {} to table", builder, rt);
                    builder.addRoute(rt);
            }
            for (Route rt : removed) {
                log.debug("{} routesChanged removing {} from table", builder,
                          rt);
                    builder.removeRoute(rt);
                }
            }
        }
}
