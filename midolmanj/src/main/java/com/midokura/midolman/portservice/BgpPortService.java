/**
 * BgpPortService.java - BGP port service class.
 *
 * This file implements a port service for Quagga's BGP.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.portservice;

import java.io.File;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.ServiceFlowController;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.OpenvSwitchException;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.util.Net;
import com.midokura.midolman.util.Sudo;
import com.midokura.packets.MAC;
import com.midokura.quagga.BgpConnection;
import com.midokura.quagga.BgpVtyConnection;
import com.midokura.quagga.ZebraServer;
import com.midokura.quagga.ZebraServerImpl;
import com.midokura.util.eventloop.Reactor;

public class BgpPortService implements PortService {

    private static final Logger log = LoggerFactory
        .getLogger(PortService.class);

    public static final String BGP_SERVICE_EXT_ID = "bgp";
    private static final short BGP_TCP_PORT = 179;
    private static final String BGP_PORT_NAME = "midobgp";

    protected Reactor reactor;
    protected OpenvSwitchDatabaseConnection ovsdb;
    // The external id key for port service.
    protected String portIdExtIdKey;
    protected String portServiceExtIdKey;

    protected ServiceFlowController controller;

    protected PortZkManager portMgr;
    protected RouteZkManager routeMgr;
    protected BgpZkManager bgpMgr;
    protected AdRouteZkManager adRouteMgr;

    protected ZebraServer zebra;
    protected BgpConnection bgpd;

    private int bgpPortIdx = 0;
    private boolean run = false;

    private Process bgpdProcess;

    public BgpPortService(Reactor reactor, OpenvSwitchDatabaseConnection ovsdb,
                          String portIdExtIdKey, String portServiceExtIdKey,
                          PortZkManager portMgr, RouteZkManager routeMgr,
                          BgpZkManager bgpMgr, AdRouteZkManager adRouteMgr,
                          ZebraServer zebra, BgpConnection bgpd) {
        this.reactor = reactor;
        this.ovsdb = ovsdb;
        // "midolman_port_id"
        this.portIdExtIdKey = portIdExtIdKey;
        // "midolman_port_service"
        this.portServiceExtIdKey = portServiceExtIdKey;
        this.portMgr = portMgr;
        this.routeMgr = routeMgr;
        this.bgpMgr = bgpMgr;
        this.adRouteMgr = adRouteMgr;
        this.zebra = zebra;
        this.bgpd = bgpd;
    }

    public BgpPortService(Reactor reactor, OpenvSwitchDatabaseConnection ovsdb,
                          String portIdExtIdKey, String portServiceExtIdKey,
                          PortZkManager portMgr, RouteZkManager routeMgr,
                          BgpZkManager bgpMgr, AdRouteZkManager adRouteMgr,
                          ZebraServer zebra, BgpConnection bgpd,
                          ServiceFlowController controller) {
        this(reactor, ovsdb, portIdExtIdKey, portServiceExtIdKey, portMgr,
             routeMgr, bgpMgr, adRouteMgr, zebra, bgpd);
        this.controller = controller;
    }

    public static PortService createBgpPortService(Reactor reactor,
            OpenvSwitchDatabaseConnection ovsdb, Directory directory,
            String basePath) throws IOException {

        // The internal BGP daemon is started only when a switch connects
        // to midolmanj. In a two midolman daemons setup the order in which the
        // switch would connect is essentially random.
        // As such the only way to truly decide which of the daemons
        // (since it can be only one given the quagga package limitations)
        // has the BGP functionality enabled is to force it in the configuration
        // file.
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

        PortZkManager portMgr = new PortZkManager(directory,
                basePath);
        RouteZkManager routeMgr = new RouteZkManager(directory, basePath);
        BgpZkManager bgpMgr = new BgpZkManager(directory, basePath);
        AdRouteZkManager adRouteMgr = new AdRouteZkManager(directory, basePath);
        ZebraServer zebraServer = new ZebraServerImpl(server, address,
                portMgr, routeMgr, ovsdb);

        BgpVtyConnection vtyConnection = new BgpVtyConnection("localhost",
                2605, "zebra", bgpMgr, adRouteMgr);

        PortService bgpPortService = new BgpPortService(reactor, ovsdb,
                "midolman_port_id", "midolman_port_service", portMgr,
                routeMgr, bgpMgr, adRouteMgr, zebraServer, vtyConnection);

        return bgpPortService;
    }

    @Override
    public void clear() {
        throw new RuntimeException("not implemented");
    }

    @Override
    public void setController(ServiceFlowController controller) {
        this.controller = controller;
    }

    @Override
    public Set<String> getPorts(UUID portId) throws StateAccessException {
         return ovsdb.getPortNamesByExternalId(portIdExtIdKey,
                                               portId.toString());
    }

    @Override
    public void addPort(final long datapathId, final UUID portId,
                        final MAC mac) throws StateAccessException {
        // Check service attributes in port configurations.
        List<UUID> bgpNodes = bgpMgr.list(
            portId, new Runnable() {
                public void run() {
                    try {
                        Set<String> servicePorts =
                            ovsdb.getPortNamesByExternalId(portIdExtIdKey,
                                                           portId.toString());
                        for (String portName : servicePorts) {
                            if (portName.contains(BGP_PORT_NAME)) {
                                return;
                            }
                        }
                        addPort(datapathId, portId, mac);
                    } catch(Exception e) {
                        log.warn("addPort", e);
                    }
                }
            });

        for (UUID bgpNode : bgpNodes) {
            // TODO(yoshi): consider delete and recreate.
            String portName = String.format(BGP_PORT_NAME + "%d", bgpPortIdx);
            // The length of interface names are limited to 16 bytes.
            if (portName.length() > 16) {
                throw new RuntimeException(
                    "The name of the service port is too long");
            }

            log.info("Add {} port {} to datapath {}",
                                   new Object[] {BGP_SERVICE_EXT_ID, portName, datapathId});
            PortBuilder portBuilder = ovsdb.addInternalPort(datapathId,
                                                            portName);
            portBuilder.externalId(portIdExtIdKey, portId.toString());
            portBuilder.externalId(portServiceExtIdKey, BGP_SERVICE_EXT_ID);
            if (mac != null) {
                portBuilder.ifMac(mac.toString());
            }
            // If there is an existing service port, ovs will return False.
            portBuilder.build();

            bgpPortIdx += 1;
        }
    }

    @Override
    public void addPort(long datapathId, UUID portId)
        throws StateAccessException {
        addPort(datapathId, portId, null);
    }

    @Override
    public UUID getRemotePort(String portName)
        throws OpenvSwitchException.NotFoundException
    {
        String service = ovsdb.getPortExternalId(portName,
                                                 portServiceExtIdKey);
        if (!BGP_SERVICE_EXT_ID.equals(service)) {
            log.info("No service type found for this port");
            return null;
        }
        String extId = ovsdb.getPortExternalId(portName,
                                               portIdExtIdKey);
        if (extId == null) {
            log.info("No remote port found for this service port ");
            return null;
        }

        return UUID.fromString(extId);
    }

    @Override
    public void configurePort(UUID portId, String portName)
        throws StateAccessException, IOException, InterruptedException {
        log.debug("configurePort: {} {}", portId, portName);

        // Turn on ARP and link up the interface.
        // mtu 1300 is to avoid ovs dropping packets.
        // TODO(yoshi): Make MTU variable configurable.
        Sudo.sudoExec(String.format(
                "ip link set dev %s arp on mtu 1300 multicast off up",
                portName));

        log.debug("configurePort: ran ip link");

        // Assume that materialized port config is already there.
        // "TODO(pino): convert this to use PortConfigCache.get"
        PortDirectory.MaterializedRouterPortConfig portConfig = portMgr.get(
                portId, PortDirectory.MaterializedRouterPortConfig.class);

        // Give the interface the address in vport configuration.
        Sudo.sudoExec(String.format(
                "ip addr add %s/%d dev %s",
                Net.convertIntAddressToString(portConfig.portAddr),
                portConfig.nwLength, portName));

        log.debug("configurePort: ran ip addr");
    }

    @Override
    public void configurePort(UUID portId)
        throws StateAccessException, IOException, InterruptedException {
        for (String portName : this.getPorts(portId)) {
            configurePort(portId, portName);
        }
    }

    @Override
    public void delPort(UUID portId) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public void start(final long datapathId, final short localPortNum,
                      final short remotePortNum)
        throws StateAccessException, IOException {
        UUID remotePortId = UUID.fromString(
            ovsdb.getPortExternalId(datapathId, localPortNum, portIdExtIdKey));

        // "TODO(pino): convert this to use PortConfigCache.get"
        PortDirectory.MaterializedRouterPortConfig portConfig = portMgr.get(
                remotePortId,
                PortDirectory.MaterializedRouterPortConfig.class);
        final int localAddr = portConfig.portAddr;

        for (final UUID bgpId : bgpMgr.list(
                 remotePortId, new Runnable() {
                     @Override
                     public void run() {
                         try {
                             start(datapathId, localPortNum, remotePortNum);
                         } catch(Exception e) {
                             log.warn("start", e);
                         }
                     }
                 })) {
            final BgpConfig bgpConfig = bgpMgr.get(bgpId);
            int remoteAddr = Net.convertInetAddressToInt(bgpConfig.peerAddr);
            log.info("Port service flows: local {} remote {} " +
                                   "localAddr {} remoteAddr {} " +
                                   "localPort {} remotePort {}",
                                   new Object[] {localPortNum, remotePortNum,
                                   localAddr, remoteAddr,
                                   BGP_TCP_PORT, BGP_TCP_PORT});
            assert(controller != null);
            controller.setServiceFlows(localPortNum, remotePortNum, localAddr,
                                       remoteAddr, BGP_TCP_PORT, BGP_TCP_PORT);
            if (!this.run) {
                try {
                    Sudo.sudoExec("killall bgpd");
                } catch (InterruptedException e) {
                    log.warn("exception killing bgpd: ", e);
                }
                zebra.start();

                log.debug("start: launching bgpd");
                bgpdProcess = Runtime.getRuntime().exec("sudo /usr/lib/quagga/bgpd");
                Runtime.getRuntime().addShutdownHook(new Thread() {
                    @Override
                    public void run() {
                        log.info("killing bgpd");
                        // Calling killall because bgpdProcess.destroy()
                        // doesn't seem to work.
                        try {
                            Sudo.sudoExec("killall bgpd");
                        } catch (IOException e) {
                            log.warn("killall bgpd", e);
                        } catch (InterruptedException e) {
                            log.warn("killall bgpd", e);
                        }
                    }
                });

                // Need to wait for bgpd to come up before sending command.
                reactor.schedule(new Runnable() {
                    public void run() {
                        try {
                            log.debug("start,Runnable.run: setting bgp config");
                            bgpd.create(Net.convertIntToInetAddress(localAddr),
                                        bgpId, bgpConfig);
                        } catch(Exception e) {
                            e.printStackTrace();
                        }
                    }
                }, 1000, TimeUnit.MILLISECONDS);
                this.run = true;
            } else {
                bgpd.create(Net.convertIntToInetAddress(localAddr), bgpId,
                            bgpConfig);
            }
        }
    }

    @Override
    public void start(UUID serviceId)
        throws StateAccessException, IOException {
        throw new RuntimeException("not implemented");
    }

    @Override
    public void stop(UUID serviceId) {
        throw new RuntimeException("not implemented");
    }
}
