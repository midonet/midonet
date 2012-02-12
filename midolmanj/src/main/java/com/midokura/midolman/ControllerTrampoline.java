/*
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman;

import com.midokura.midolman.eventloop.Reactor;
import com.midokura.midolman.layer3.NetworkController;
import com.midokura.midolman.openflow.Controller;
import com.midokura.midolman.openflow.ControllerStub;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.portservice.BgpPortService;
import com.midokura.midolman.portservice.NullPortService;
import com.midokura.midolman.portservice.OpenVpnPortService;
import com.midokura.midolman.portservice.PortService;
import com.midokura.midolman.portservice.VpnPortAgent;
import com.midokura.midolman.quagga.BgpVtyConnection;
import com.midokura.midolman.quagga.ZebraServer;
import com.midokura.midolman.quagga.ZebraServerImpl;
import com.midokura.midolman.state.*;
import com.midokura.midolman.state.BridgeZkManager.BridgeConfig;
import com.midokura.midolman.state.VpnZkManager.VpnType;
import com.midokura.midolman.util.Cache;
import com.midokura.midolman.util.MemcacheCache;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.KeeperException;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.openflow.protocol.OFFlowRemoved.OFFlowRemovedReason;
import org.openflow.protocol.OFMatch;
import org.openflow.protocol.OFMessage;
import org.openflow.protocol.OFPhysicalPort;
import org.openflow.protocol.OFPortStatus.OFPortReason;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.JMException;
import javax.management.ObjectName;
import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.Arrays;
import java.util.Properties;
import java.util.UUID;

public class ControllerTrampoline implements Controller {

    private static final Logger log =
        LoggerFactory.getLogger(ControllerTrampoline.class);

    public static final int CACHE_EXPIRATION_SECONDS = 60;

    private HierarchicalConfiguration config;
    private OpenvSwitchDatabaseConnection ovsdb;
    private Directory directory;
    private String basePath;
    private ZkPathManager pathMgr;
    private Reactor reactor;
    private String externalIdKey;

    private BridgeZkManager bridgeMgr;
    private ControllerStub controllerStub;

    /* directory is the "midonet root", not zkConnection.getRootDirectory() */
    public ControllerTrampoline(HierarchicalConfiguration config,
            OpenvSwitchDatabaseConnection ovsdb, Directory directory,
            Reactor reactor) throws KeeperException {

        this.config = config;
        this.ovsdb = ovsdb;
        this.directory = directory;
        this.reactor = reactor;
        this.basePath = config.configurationAt("midolman")
                              .getString("midolman_root_key");

        this.pathMgr = new ZkPathManager(basePath);
        this.bridgeMgr = new BridgeZkManager(directory, basePath);

        externalIdKey = config.configurationAt("openvswitch")
                              .getString("midolman_ext_id_key", "midolman-vnet");
    }

    @Override
    public void setControllerStub(ControllerStub controllerStub) {
        this.controllerStub = controllerStub;
    }

    /**
     * Create and configure the cache depending on the configuration.
     *
     * @return the cache
     */
    protected Cache createCache() throws IOException {
        String cacheType = config.getString("cache.type", "memcache");

        if (cacheType.equals("memcache")) {
            // set log4j logging for spymemcached client
            Properties props = System.getProperties();
            props.put("net.spy.log.LoggerImpl", "net.spy.memcached.compat.log.Log4JLogger");
            System.setProperties(props);

            String memcacheHosts = config.configurationAt("memcache")
                                         .getString("memcache_hosts");

            return new MemcacheCache(memcacheHosts, CACHE_EXPIRATION_SECONDS);
        } else {
            log.error("unknown cache type");
            return null;
        }
    }

    @Override
    public void onConnectionMade() {
        log.info("onConnectionMade");

        try {
            long datapathId = controllerStub.getFeatures().getDatapathId();

            // lookup midolman-vnet of datapath
            String uuid = ovsdb.getDatapathExternalId(datapathId, externalIdKey);

            if (uuid == null) {
                log.warn("onConnectionMade: datapath {} connected but has no relevant external id, ignore it", datapathId);
                return;
            }

            UUID deviceId = UUID.fromString(uuid);


            log.info("onConnectionMade: DP with UUID {}", deviceId);

            // TODO: is this the right way to check that a DP is for a VRN?
            // ----- No.  We should have a directory of VRN UUIDs in ZooKeeper,
            //       just like for Bridges and Routers.
            Controller newController;
            if (uuid.equals(config.configurationAt("vrn")
                                  .getString("router_network_id"))) {
                Directory portLocationDirectory =
                    directory.getSubDirectory(pathMgr.getVRNPortLocationsPath());

                PortToIntNwAddrMap portLocationMap =
                        new PortToIntNwAddrMap(portLocationDirectory);

                long idleFlowExpireMillis = config.configurationAt("openflow")
                                                  .getLong("flow_idle_expire_millis");

                IntIPv4 localNwAddr =
                    IntIPv4.fromString(config.configurationAt("openflow")
                                             .getString("public_ip_address"));

                Cache cache = createCache();

                PortZkManager portMgr = new PortZkManager(directory, basePath);
                RouteZkManager routeMgr = new RouteZkManager(directory, basePath);
                BgpZkManager bgpMgr = new BgpZkManager(directory, basePath);
                AdRouteZkManager adRouteMgr = new AdRouteZkManager(directory, basePath);

                PortService bgpPortService =
                    initializeBgpPortService(reactor, ovsdb,
                                             portMgr, routeMgr, bgpMgr, adRouteMgr);

                // Create VPN port agent for OpenVPN.
                VpnZkManager vpnMgr = new VpnZkManager(directory, basePath);
                OpenVpnPortService openVpnSvc =
                    new OpenVpnPortService(ovsdb,
                                           externalIdKey, "midolman_port_service",
                                           portMgr, vpnMgr);
                openVpnSvc.clear();
                long sessionId = 0;
                if (directory instanceof ZkDirectory) {
                    ZkDirectory zkDir = ZkDirectory.class.cast(directory);
                    sessionId = zkDir.zk.getSessionId();
                }

                VpnPortAgent vpnAgent =
                    new VpnPortAgent(sessionId, datapathId, vpnMgr);

                vpnAgent.setPortService(VpnType.OPENVPN_SERVER, openVpnSvc);
                vpnAgent.setPortService(VpnType.OPENVPN_TCP_SERVER, openVpnSvc);
                vpnAgent.setPortService(VpnType.OPENVPN_TCP_CLIENT, openVpnSvc);
                vpnAgent.start();

                newController = new NetworkController(
                        datapathId,
                        deviceId,
                        config.configurationAt("vrn").getInt("router_network_gre_key"),
                        portLocationMap,
                        (short)(idleFlowExpireMillis/1000),
                        localNwAddr,
                        portMgr,
                        new RouterZkManager(directory, basePath),
                        routeMgr,
                        new ChainZkManager(directory, basePath),
                        new RuleZkManager(directory, basePath),
                        ovsdb,
                        reactor,
                        cache,
                        externalIdKey,
                        bgpPortService);
            } else {
                BridgeConfig bridgeConfig;
                try {
                    bridgeConfig = bridgeMgr.get(deviceId).value;
                } catch (Exception e) {
                    log.info("can't handle this datapath, disconnecting", e);
                    controllerStub.close();
                    return;
                }
                log.info("Creating Bridge {}", uuid);

                Directory portLocationDirectory =
                    directory.getSubDirectory(
                            pathMgr.getBridgePortLocationsPath(deviceId));

                PortToIntNwAddrMap portLocationMap =
                    new PortToIntNwAddrMap(portLocationDirectory);

                Directory macPortDir =
                    directory.getSubDirectory(
                            pathMgr.getBridgeMacPortsPath(deviceId));
                MacPortMap macPortMap = new MacPortMap(macPortDir);

                long idleFlowExpireMillis = config.configurationAt("openflow")
                                                  .getLong("flow_idle_expire_millis");
                long flowExpireMillis = config.configurationAt("openflow")
                                              .getLong("flow_expire_millis");
                long macPortTimeoutMillis = config.configurationAt("bridge")
                                                  .getLong("mac_port_mapping_expire_millis");

                IntIPv4 localNwAddr =
                    IntIPv4.fromString(config.configurationAt("openflow")
                                             .getString("public_ip_address"));

                newController = new BridgeController(
                        datapathId,
                        deviceId,
                        bridgeConfig.greKey,
                        portLocationMap,
                        macPortMap,
                        flowExpireMillis,
                        idleFlowExpireMillis,
                        localNwAddr,
                        macPortTimeoutMillis,
                        ovsdb,
                        reactor,
                        externalIdKey);
            }
            controllerStub.setController(newController);
            controllerStub = null;
            newController.onConnectionMade();

            //ObjectName on = new ObjectName("com.midokura.midolman:type=Controller,name=" + deviceId);
            //ManagementFactory.getPlatformMBeanServer().registerMBean(newController, on);

        } catch (KeeperException e) {
            log.warn("ZK error", e);
        } catch (IOException e) {
            log.warn("IO error", e);
        //} catch (JMException e) {
        //    log.warn("JMX error", e);
        }
    }

    private PortService initializeBgpPortService(Reactor reactor,
                                                 OpenvSwitchDatabaseConnection ovsdb,
                                                 PortZkManager portMgr,
                                                 RouteZkManager routeMgr,
                                                 BgpZkManager bgpMgr,
                                                 AdRouteZkManager adRouteMgr)
        throws IOException {

        // The internal BGP daemon is started only when a switch connects
        // to midolmanj. In a two midolman daemons setup the order in which the
        // switch would connect is essentially random.
        // As such the only way to truly decide which of the daemons
        // (since it can be only one given the quagga package limitations)
        // has the BGP functionality enabled is to force it in the configuration file.
        boolean bgpEnabled = config.configurationAt("midolman")
                                           .getBoolean("enable_bgp", true);

        if (!bgpEnabled) {
            log.info("BGP disabled by configuration.");
            return new NullPortService();
        }

        try {
            File socketFile = new File("/var/run/quagga/zserv.api");
            File socketDir = socketFile.getParentFile();
            if (!socketDir.exists()) {
                socketDir.mkdirs();
                // Set permission to let quagga daemons write.
                socketDir.setWritable(true, false);
            }

            if (socketFile.exists())
                socketFile.delete();

            AFUNIXServerSocket server = AFUNIXServerSocket.newInstance();
            AFUNIXSocketAddress address = new AFUNIXSocketAddress(socketFile);

            ZebraServer zebraServer =
                new ZebraServerImpl(server, address, portMgr, routeMgr, ovsdb);

            BgpVtyConnection vtyConnection =
                new BgpVtyConnection("localhost", 2605, "zebra", bgpMgr, adRouteMgr);

            PortService bgpPortService =
                new BgpPortService(reactor, ovsdb,
                                   "midolman_port_id", "midolman_port_service",
                                   portMgr, routeMgr, bgpMgr, adRouteMgr,
                                   zebraServer, vtyConnection);

            return bgpPortService;
        } catch (IOException e) {
            log.error("Exception while starting up the BGP port service", e);
            return new NullPortService();
        }
    }

    @Override
    public void onConnectionLost() {
        log.info("onConnectionLost");
    }

    @Override
    public void onPacketIn(int bufferId, int totalLen, short inPort, byte[] data) {
        log.warn("onPacketIn");
        throw new UnsupportedOperationException();
    }

    @Override
    public void onFlowRemoved(OFMatch match, long cookie, short priority,
            OFFlowRemovedReason reason, int durationSeconds,
            int durationNanoseconds, short idleTimeout, long packetCount,
            long byteCount) {
        log.warn("onFlowRemoved");
//        throw new UnsupportedOperationException();
    }

    @Override
    public void onPortStatus(OFPhysicalPort port, OFPortReason status) {
        log.warn("onPortStatus");
        throw new UnsupportedOperationException();
    }

    @Override
    public void onMessage(OFMessage m) {
        log.warn("onMessage");
        throw new UnsupportedOperationException();
    }

}
