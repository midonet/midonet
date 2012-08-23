/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Inject;
import javax.inject.Named;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.config.ZookeeperConfig;
import com.midokura.midolman.guice.zookeeper.ZKConnectionProvider;
import com.midokura.midolman.host.state.HostDirectory;
import com.midokura.midolman.host.state.HostZkManager;
import com.midokura.midolman.state.ArpCacheEntry;
import com.midokura.midolman.state.ArpTable;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.MacPortMap;
import com.midokura.midolman.state.ReplicatedMap;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkDirectory;
import com.midokura.midolman.state.ZkPathManager;
import com.midokura.midolman.state.zkManagers.BgpZkManager;
import com.midokura.midolman.state.zkManagers.BridgeZkManager;
import com.midokura.midolman.state.zkManagers.RouterZkManager;
import com.midokura.midonet.cluster.client.ArpCache;
import com.midokura.midonet.cluster.client.BridgeBuilder;
import com.midokura.midonet.cluster.client.ChainBuilder;
import com.midokura.midonet.cluster.client.LocalStateBuilder;
import com.midokura.midonet.cluster.client.MacLearningTable;
import com.midokura.midonet.cluster.client.PortBuilders;
import com.midokura.midonet.cluster.client.RouterBuilder;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.util.eventloop.Reactor;
import com.midokura.util.functors.Callback1;
import com.midokura.util.functors.Callback3;

/**
 * Implementation of the Cluster.Client using ZooKeeper
 * Assumption:
 * - No cache, the caller of this class will have to implement its own cache
 * - Only one builder for UUID is allowed
 * - Right now it's single-threaded, we don't assure a thread-safe behaviour
 */
public class LocalClientImpl implements Client {

    private static final Logger log = LoggerFactory
        .getLogger(LocalClientImpl.class);

    @Inject
    HostZkManager hostZkManager;

    @Inject
    BgpZkManager bgpZkManager;

    @Inject
    BridgeZkManager bridgeMgr;

    @Inject
    RouterZkManager routerMgr;
    
    @Inject
    ZookeeperConfig zkConfig;

    @Inject
    Directory dir;

    /**
     * We inject it because we want to use the same {@link Reactor} as {@link ZkDirectory}
     */
    @Inject
    @Named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG)
    Reactor reactorLoop;
    
    // bridge maps
    Map<UUID, BridgeBuilder> bridgeBuilderMap = new ConcurrentHashMap<UUID, BridgeBuilder>();
    Map<UUID, BridgeZkManager.BridgeConfig> bridgeMap = new ConcurrentHashMap<UUID, BridgeZkManager.BridgeConfig>();
    
    // router maps
    Map<UUID, RouterBuilder> routerBuilderMap = new ConcurrentHashMap<UUID, RouterBuilder>();
    Map<UUID, RouterZkManager.RouterConfig> routerMap = new ConcurrentHashMap<UUID, RouterZkManager.RouterConfig>();

    @Override
    public void getBridge(UUID bridgeID, BridgeBuilder builder) {
        // asynchronous call, we will process it later
        bridgeBuilderMap.put(bridgeID, builder);
        reactorLoop.submit(getBridgeConf(bridgeID, builder));
        log.info("getBridge {}", bridgeID);
    }

    @Override
    public void getRouter(UUID routerID, RouterBuilder builder) {
        // asynchronous call, we will process it later
        routerBuilderMap.put(routerID, builder);
        reactorLoop.submit(getRouterConf(routerID, builder));
        log.info("getRouter {}", routerID);
    }

    @Override
    public void getChain(UUID chainID, ChainBuilder builder) {
    }

    @Override
    public void getType(UUID portID, Callback1<PortType> cb) {

    }

    @Override
    public void getPort(UUID portID,
                        PortBuilders.InteriorBridgePortBuilder builder) {
    }

    @Override
    public void getPort(UUID portID,
                        PortBuilders.ExteriorBridgePortBuilder builder) {
        builder.start().setTunnelKey(1l).build();
    }

    @Override
    public void getPort(UUID portID,
                        PortBuilders.InteriorRouterPortBuilder builder) {
    }

    @Override
    public void getPort(UUID portID,
                        PortBuilders.ExteriorRouterPortBuilder builder) {

    }

    Map<UUID, LocalStateBuilder> localStateBuilders =
        new HashMap<UUID, LocalStateBuilder>();

    @Override
    public void getLocalStateFor(UUID hostIdentifier,
                                 LocalStateBuilder builder) {
        localStateBuilders.put(hostIdentifier, builder);
        triggerUpdate(hostIdentifier);
    }

    @Override
    public void setLocalVrnDatapath(UUID hostIdentifier, String datapathName) {
        try {
            hostZkManager.addVirtualDatapathMapping(hostIdentifier,
                                                    datapathName);
            triggerUpdate(hostIdentifier);
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
        }
    }

    private void triggerUpdate(UUID hostIdentifier) {
        try {

            LocalStateBuilder builder = localStateBuilders.get(hostIdentifier);
            if (builder == null)
                return;

            builder.setDatapathName(
                hostZkManager.getVirtualDatapathMapping(hostIdentifier));

            Set<HostDirectory.VirtualPortMapping> portMappings =
                hostZkManager.getVirtualPortMappings(hostIdentifier);

            for (HostDirectory.VirtualPortMapping portMapping : portMappings) {
                builder.addLocalPortInterface(portMapping.getVirtualPortId(),
                                              portMapping.getLocalDeviceName());
            }

            builder.build();
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
        }
    }

    @Override
    public void setLocalVrnPortMapping(UUID hostIdentifier, UUID portId,
                                       String tapName) {
        try {
            hostZkManager.addVirtualPortMapping(hostIdentifier,
                                                new HostDirectory.VirtualPortMapping(
                                                    portId, tapName));
            triggerUpdate(hostIdentifier);
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
        }
    }

    @Override
    public void removeLocalPortMapping(UUID hostIdentifier, UUID portId) {
        try {
            hostZkManager.removeVirtualPortMapping(hostIdentifier, portId);
            triggerUpdate(hostIdentifier);
        } catch (StateAccessException e) {
            log.error("Exception: ", e);
        }
    }

    Runnable getBridgeConf(final UUID id,
                           final BridgeBuilder builder) {
        return new Runnable() {

            @Override
            public void run() {
                log.info("Updating configuration for bridge {}", id);
                BridgeZkManager.BridgeConfig config = null;
                try {
                    config = bridgeMgr.get(id, watchBridge(id));
                } catch (StateAccessException e) {
                    // TODO send error message?
                    log.error("Cannot retrieve the configuration for bridge {}",
                              id, e);
                }

                if (config != null) {
                    MacPortMap macPortMap = null;

                    // we don't need to get the macPortMap again if it's an update
                    if (!bridgeMap.containsKey(id)) {
                        try {
                            ZkPathManager pathManager = new ZkPathManager(
                                zkConfig.getMidolmanRootKey());
                            macPortMap = new MacPortMap(dir.getSubDirectory(
                                pathManager.getBridgeMacPortsPath(id)));
                        } catch (KeeperException e) {
                            log.error(
                                "Error retrieving MacPortTable for bridge {}",
                                id, e);
                        }
                        if (macPortMap != null)
                            macPortMap.start();
                    }
                    bridgeMap.put(id, config);
                    buildBridgeFromConfig(id, config, builder, macPortMap);
                    log.info("Update configuration for bridge {}", id);
                }
            }
        };

    }

    /**
     * Get the conf for a router.
     * @param id
     * @param builder
     * @return
     */
    Runnable getRouterConf(final UUID id,
                           final RouterBuilder builder) {
        return new Runnable() {

            @Override
            public void run() {
                log.info("Updating configuration for router {}", id);
                RouterZkManager.RouterConfig config = null;
                try {
                    config = routerMgr.get(id, watchRouter(id));
                } catch (StateAccessException e) {
                    log.error("Cannot retrieve the configuration for bridge {}",
                              id, e);
                }

                if (config != null) {
                    ArpTable arpTable = null; 
                    if (!routerMap.containsKey(id)) {
                        try {
                            arpTable = new ArpTable(routerMgr.getArpTableDirectory(id)); 
                        } catch (StateAccessException e) {
                            log.error(
                                "Error retrieving MacPortTable for bridge {}",
                                id, e);
                        }
                        if (arpTable != null)
                            arpTable.start();
                    }
                    routerMap.put(id, config);
                    buildRouterFromConfig(id, config, builder, arpTable);
                    log.info("Update configuration for router {}", id);
                }
            }
        };

    }


    Runnable watchBridge(final UUID id) {
        return new Runnable() {
            @Override
            public void run() {
                // return fast and update later
                reactorLoop.submit(getBridgeConf(id,
                                                 (BridgeBuilder) bridgeBuilderMap
                                                     .get(id)));
                log.info("Added watcher for bridge {}", id);
            }
        };
    }
    
    Runnable watchRouter(final UUID id) {
        return new Runnable() {
            @Override
            public void run() {
                // return fast and update later
                reactorLoop.submit(getRouterConf(id,
                                                 (RouterBuilder) routerBuilderMap
                                                     .get(id)));
                log.info("Added watcher for router {}", id);
            }
        };
    }

    void buildBridgeFromConfig(UUID id, BridgeZkManager.BridgeConfig config,
                               BridgeBuilder builder, MacPortMap macPortMap) {

        //builder.setID(id)
        builder.setInFilter(config.inboundFilter)
               .setOutFilter(config.outboundFilter);
        builder.setTunnelKey(config.greKey);
        builder.setMacLearningTable(new MacLearningTableImpl(macPortMap) {
        });
        builder.build();

    }
    
    void buildRouterFromConfig(UUID id, RouterZkManager.RouterConfig config,
                               RouterBuilder builder, ArpTable arpTable) {

        builder.setInFilter(config.inboundFilter).setOutFilter(config.outboundFilter);
        builder.setArpCache(new ArpCacheImpl(arpTable)); 
        builder.build();

    }
    
    class ArpCacheImpl implements ArpCache {

        ArpTable arpTable; 
       
        ArpCacheImpl(ArpTable arpTable) {
            this.arpTable = arpTable; 
        }
        
        @Override
        public void get(final IntIPv4 ipAddr, final Callback1<ArpCacheEntry> cb) {
           reactorLoop.submit( new Runnable() {

            @Override
            public void run() {
               cb.call(arpTable.get(ipAddr)); 
            }}) ; 
        }

        @Override
        public void add(final IntIPv4 ipAddr, final ArpCacheEntry entry) {
           reactorLoop.submit( new Runnable() {

            @Override
            public void run() {
                try {
                    arpTable.put(ipAddr, entry);
                } catch (Exception e) {
                   log.error("Failed adding ARP entry. IP: {} MAC: {}", new Object[]{ipAddr, entry});  
                } 
            }}) ; 
        }

        @Override
        public void remove(final IntIPv4 ipAddr) {
           reactorLoop.submit(new Runnable() {

            @Override
            public void run() {
               try {
                arpTable.removeIfOwner(ipAddr);
            } catch (Exception e) {
                log.error("Could not remove Arp entry for IP: {}", ipAddr); 
            }  
            }}); 
        }
        
    }

    class MacLearningTableImpl implements MacLearningTable {

        MacPortMap map;

        MacLearningTableImpl(MacPortMap map) {
            this.map = map;
        }

        @Override
        public void get(final MAC mac, final Callback1<UUID> cb) {
            reactorLoop.submit(new Runnable() {
                @Override
                public void run() {
                    cb.call(map.get(mac));
                }
            });
            log.info("Got mac {}", mac);
        }

        @Override
        public void add(final MAC mac, final UUID portID) {

            reactorLoop.submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        map.put(mac, portID);
                    } catch (Exception e) {
                        log.error("Failed adding mac {} to port {}",
                                  new Object[]{mac, portID, e});
                    }
                }
            });
            log.info("Added mac {} to port {} for bridge {}",
                     new Object[]{mac, portID});
        }

        @Override
        public void remove(final MAC mac, final UUID portID) {
            reactorLoop.submit(new Runnable() {

                @Override
                public void run() {
                    try {
                        map.removeIfOwner(mac);
                    } catch (Exception e) {
                        log.error("Failed removing mac {} from port {}",
                                  new Object[]{mac, portID, e});
                    }
                }
            });

        }


        @Override
        public void notify(final Callback3<MAC, UUID, UUID> cb) {
            reactorLoop.submit(new Runnable() {

                @Override
                public void run() {
                    map.addWatcher(new ReplicatedMap.Watcher<MAC, UUID>() {
                        @Override
                        public void processChange(MAC key, UUID oldValue,
                                                  UUID newValue) {
                            cb.call(key, oldValue, newValue);
                        }
                    });
                }
            });

        }
    }

}
