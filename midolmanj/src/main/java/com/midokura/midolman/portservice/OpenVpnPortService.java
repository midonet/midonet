/**
 * OpenVpnPortService.java - OpenVPN port service class.
 *
 * This file implements a port service for OpenVPN.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.portservice;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.ServiceFlowController;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.VpnZkManager;
import com.midokura.midolman.state.VpnZkManager.VpnConfig;
import com.midokura.midolman.state.VpnZkManager.VpnType;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.util.Net;
import com.midokura.midolman.util.Sudo;

public class OpenVpnPortService implements PortService {

    private static final Logger log = LoggerFactory
            .getLogger(OpenVpnPortService.class);
    private static String logDir;

    public static final String SERVICE_EXT_ID = "openvpn";
    private static final String VPN_PORT_NAME = "midovpn";
    private static final String[] OPENVPN_CMD = {
            "/usr/sbin/openvpn", "--mode", "p2p", "--dev-type", "tap"};
    private static final String OPENVPN_OPT_AUTH = "--auth";
    private static final String OPENVPN_OPT_DEV = "--dev";
    private static final String OPENVPN_OPT_ADDR = "--local";
    private static final String OPENVPN_OPT_PROTO = "--proto";
    private static final String OPENVPN_OPT_UDP = "udp";
    private static final String OPENVPN_OPT_TCP_SERVER = "tcp-server";
    private static final String OPENVPN_OPT_TCP_CLIENT = "tcp-client";
    private static final String OPENVPN_OPT_REMOTE = "--remote";
    private static final String OPENVPN_OPT_PORT = "--port";
    private static final String OPENVPN_OPT_LOG = "--log";
    private static final String OPENVPN_OPT_SYSLOG = "--syslog";

    protected OpenvSwitchDatabaseConnection ovsdb;

    protected String externalIdKey;
    protected String portServiceExtIdKey;

    protected PortZkManager portMgr;
    protected RouteZkManager routeMgr;
    protected VpnZkManager vpnMgr;

    private int vpnPortIdx = 0;
    private boolean run = false;

    protected ServiceFlowController controller;

    private Map<UUID, Process> vpnIdToProcess;

    public OpenVpnPortService(OpenvSwitchDatabaseConnection ovsdb,
                              String externalIdKey, String portServiceExtIdKey,
                              PortZkManager portMgr, VpnZkManager vpnMgr) {
        this.ovsdb = ovsdb;
        this.externalIdKey =externalIdKey;
        // "midolman_port_service"
        this.portServiceExtIdKey = portServiceExtIdKey;
        this.portMgr = portMgr;
        this.vpnMgr = vpnMgr;
        this.vpnIdToProcess = new HashMap<UUID, Process>();
        this.logDir = System.getProperty("midolman.log.dir");

        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override
            public void run() {
                clear();
            }
        });
    }

    public static PortService createVpnPortService(
            OpenvSwitchDatabaseConnection ovsdb, String externalIdKey,
            Directory directory, String basePath) {
        PortZkManager portMgr = new PortZkManager(directory,
                basePath);
        VpnZkManager vpnMgr = new VpnZkManager(directory, basePath);
        OpenVpnPortService openVpnSvc = new OpenVpnPortService(ovsdb,
                externalIdKey, "midolman_port_service", portMgr, vpnMgr);
        openVpnSvc.clear();

        return openVpnSvc;
    }

    @Override
    public void clear() {
        // Delete all openvpn ports.
        // TODO(yoshi): use delete port if possible.
        Set<String> portNames = ovsdb.getPortNamesByExternalId(
                portServiceExtIdKey, SERVICE_EXT_ID);
        for (String portName : portNames) {
            log.info("delete port {}", portName);
            ovsdb.delPort(portName);
            try {
                Sudo.sudoExec(String.format("ip link del %s", portName));
                log.debug("clear: ran ip link");
            } catch (InterruptedException e) {
                log.warn("clear", e);
            } catch (IOException e) {
                log.warn("clear", e);
            }
        }
        vpnPortIdx = 0;

        // killall openvpn processes.
        log.info("killall openvpn");
        try {
            Sudo.sudoExec("killall openvpn");
        } catch (InterruptedException e) {
            log.warn("clear", e);
        } catch (IOException e) {
            log.warn("clear", e);
        }
    }

    @Override
    public void setController(ServiceFlowController controller) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public Set<String> getPorts(UUID portId) throws StateAccessException {
        throw new RuntimeException("not implemented");
    }

    @Override
    public void addPort(long datapathId, UUID portId, MAC mac)
            throws StateAccessException {
        // Check if the port is already created.
        Set<String> portNames = ovsdb.getPortNamesByExternalId(
                externalIdKey, portId.toString());
        if (!portNames.isEmpty()) {
            return;
        }

        String portName = String.format(VPN_PORT_NAME + "%d", vpnPortIdx);
        // The length of interface names are limited to 16 bytes.
        if (portName.length() > 16) {
            throw new RuntimeException(
                    "The name of the vpn private port is too long");
        }

        log.info("Add {} port {} to datapath {}",
                new Object[]{SERVICE_EXT_ID, portName, datapathId});

        // Using the vpn configs at the head is sufficient to setup ports.
        List<ZkNodeEntry<UUID, VpnConfig>> vpnNodes = vpnMgr.list(portId);
        ZkNodeEntry<UUID, VpnConfig> vpnNode = vpnNodes.get(0);
        VpnConfig vpn = vpnNode.value;

        PortBuilder portBuilder;
        if (vpn.publicPortId.equals(portId)) {
            portBuilder = ovsdb.addInternalPort(datapathId, portName);
        } else if (vpn.privatePortId.equals(portId)) {
            try {
                Sudo.sudoExec(String.format(
                        "ip tuntap add dev %s mode tap", portName));
                log.debug("addPort: ran ip tuntap");
            } catch (InterruptedException e) {
                log.warn("addPort", e);
            } catch (IOException e) {
                log.warn("addPort", e);
            }
            portBuilder = ovsdb.addSystemPort(datapathId, portName);
        } else {
            throw new RuntimeException(
                    "Invalid port " + portId.toString() + "for vpn " +
                            vpnNode.key.toString());
        }
        // All the vpn ports are usual materialized router ports with
        // midolman-vnet.
        portBuilder.externalId(externalIdKey, portId.toString());
        portBuilder.externalId(portServiceExtIdKey, SERVICE_EXT_ID);
        if (mac != null) {
            portBuilder.ifMac(mac.toString());
        }
        // If there is an existing service port, ovs will return False.
        portBuilder.build();

        // hasPort() would block until previous transaction creating the
        // port finishes.
        if (!ovsdb.hasPort(portName)) {
            throw new RuntimeException("Port didn't get created");
        }

        // Bring the port up.
        try {
            Sudo.sudoExec(String.format(
                    "ip link set dev %s arp on mtu 1300 multicast off up",
                    portName));
            log.debug("addPort: ran ip link");
        } catch (IOException e) {
            log.warn("addPort", e);
        } catch (InterruptedException e) {
            log.warn("addPort", e);
        }
        // TODO(yoshi): instead of simply keep incrementing, create a map
        // that leases port names, so that we can reuse port names that are
        // unused.
        vpnPortIdx += 1;
    }

    @Override
    public void addPort(long datapathId, UUID portId)
            throws StateAccessException {
        addPort(datapathId, portId, null);
    }

    @Override
    public UUID getRemotePort(String portName) {
        throw new RuntimeException("not implemented");
    }

    @Override
    public void configurePort(UUID portId, String portName)
            throws StateAccessException, IOException, InterruptedException {
        PortConfig config = portMgr.get(portId).value;
        if (!(config instanceof PortDirectory.MaterializedRouterPortConfig)) {
            throw new RuntimeException(
                    "Target port isn't a MaterializedRouterPortConfig.");
        }
        PortDirectory.MaterializedRouterPortConfig portConfig =
                PortDirectory.MaterializedRouterPortConfig.class.cast(config);

        ZkNodeEntry<UUID, VpnConfig> vpnNode = vpnMgr.list(portId).get(0);
        VpnConfig vpn = vpnNode.value;
        if (vpn.publicPortId.equals(portId)) {
            // Set IP address of the port.
            Sudo.sudoExec(String.format(
                    "ip addr add %s/%d dev %s",
                    Net.convertIntAddressToString(portConfig.localNwAddr),
                    portConfig.nwLength, portName));
            log.debug("configurePort: ran ip addr");

            // We need different table number if we want to run different vpns
            // on the same machine.
            // In the table just one entry will be written, that is the defaut
            // gateway
            // We use & 0xfff to truncate the int, I didn't find what's the
            // maximum number
            // allowed for tables but till 12bit it's ok
            // TODO(rossella) delete the policy-based-rounting table during the
            // clean up, agree a
            // good strategy
            int tableNr = portId.hashCode() & 0xfff;

            // Add a rule and a route that transports packets from the
            // internal port to the gateway (the controller).
            try {
                Sudo.sudoExec(String.format(
                    "ip rule add from %s table %d",
                    Net.convertIntAddressToString(portConfig.localNwAddr),
                    tableNr));
            } catch (InterruptedException e) {
                log.warn("configurePort", e);
            }
            log.debug("configurePort: ran ip rule");

            try {
                Sudo.sudoExec(String.format(
                    "ip route add default via %s table %d",
                    Net.convertIntAddressToString(portConfig.portAddr),
                    tableNr));
            } catch (InterruptedException e) {
                log.warn("configurePort", e);
            }
            log.debug("configurePort: ran ip route");
        }
    }

    @Override
    public void configurePort(UUID portId)
            throws StateAccessException, IOException, InterruptedException {
        Set<String> portNames = ovsdb.getPortNamesByExternalId(
                externalIdKey, portId.toString());
        if (portNames.size() != 1) {
            throw new RuntimeException("Ports with same portId found.");
        }
        String portName = null;
        for (String p : portNames) {
            portName = p;
        }

        configurePort(portId, portName);
    }

    @Override
    public void delPort(UUID portId) {
        // TODO(yoshi): delete ip rules if the port is a public port.
        Set<String> portNames = ovsdb.getPortNamesByExternalId(
                externalIdKey, portId.toString());
        for (String portName : portNames) {
            log.info("delete port {}", portName);
            ovsdb.delPort(portName);
            try {
                Sudo.sudoExec(String.format("ip link del %s", portName));
                log.debug("delPort: ran ip link");
            } catch (InterruptedException e) {
                log.warn("delPort", e);
            } catch (IOException e) {
                log.warn("delPort", e);
            }
        }
    }

    @Override
    public void start(long datapathId, short localPortNum, short remotePortNum)
            throws StateAccessException, IOException {
        throw new RuntimeException("not implemented");
    }

    @Override
    public void start(UUID serviceId)
            throws StateAccessException, IOException {
        ZkNodeEntry<UUID, VpnConfig> vpnNode = vpnMgr.get(serviceId);
        UUID vpnId = vpnNode.key;
        VpnConfig vpn = vpnNode.value;

        PortConfig config = portMgr.get(vpn.publicPortId).value;
        if (!(config instanceof PortDirectory.MaterializedRouterPortConfig)) {
            throw new RuntimeException(
                    "Public port isn't a MaterializedRouterPortConfig.");
        }
        PortDirectory.MaterializedRouterPortConfig publicPort =
                PortDirectory.MaterializedRouterPortConfig.class.cast(config);
        String publicPortAddr = Net.convertIntAddressToString(
                publicPort.localNwAddr);
        Set<String> portNames = ovsdb.getPortNamesByExternalId(
                externalIdKey, vpn.publicPortId.toString());
        if (portNames.size() != 1) {
            throw new RuntimeException(
                    "Ports with same portId found.");
        }
        String publicPortName = null;
        for (String portName : portNames) {
            publicPortName = portName;
        }

        config = portMgr.get(vpn.privatePortId).value;
        if (!(config instanceof PortDirectory.MaterializedRouterPortConfig)) {
            throw new RuntimeException(
                    "Private port isn't a MaterializedRouterPortConfig.");
        }
        PortDirectory.MaterializedRouterPortConfig privatePort =
                PortDirectory.MaterializedRouterPortConfig.class.cast(config);
        portNames = ovsdb.getPortNamesByExternalId(
                externalIdKey, vpn.privatePortId.toString());
        if (portNames.size() != 1) {
            throw new RuntimeException(
                    "Ports with same portId found.");
        }
        String privatePortName = null;
        for (String portName : portNames) {
            privatePortName = portName;
        }

        log.info("launch openvpn: public port {} private {} address {} port {}",
                new Object[]{vpn.publicPortId, vpn.privatePortId,
                        publicPortAddr, vpn.port});

        List<String> openvpnCmd = new ArrayList(Arrays.asList(OPENVPN_CMD));
        openvpnCmd.add(OPENVPN_OPT_AUTH);
        // TODO(yoshi): support authentication and encryption.
        openvpnCmd.add("none");
        openvpnCmd.add(OPENVPN_OPT_DEV);
        openvpnCmd.add(privatePortName);

        // TODO(yoshi): add "--verb n" depending on log level.
        openvpnCmd.add(OPENVPN_OPT_PROTO);
        if (vpn.vpnType == VpnType.OPENVPN_SERVER) {
            openvpnCmd.add(OPENVPN_OPT_UDP);
        } else if (vpn.vpnType == VpnType.OPENVPN_TCP_SERVER) {
            openvpnCmd.add(OPENVPN_OPT_TCP_SERVER);
        } else if (vpn.vpnType == VpnType.OPENVPN_TCP_CLIENT ||
                   vpn.vpnType == VpnType.OPENVPN_CLIENT) {
            if (vpn.vpnType == VpnType.OPENVPN_TCP_CLIENT) {
                openvpnCmd.add(OPENVPN_OPT_TCP_CLIENT);
            } else if (vpn.vpnType == VpnType.OPENVPN_CLIENT) {
                openvpnCmd.add(OPENVPN_OPT_UDP);
            }
            // add remote if specified
            if (vpn.remoteIp != null) {
                openvpnCmd.add(OPENVPN_OPT_REMOTE);
                openvpnCmd.add(vpn.remoteIp.toString());
            } else {
                log.error("vpn client, invalid remote IP");
            }
        }
        openvpnCmd.add(OPENVPN_OPT_ADDR);
        openvpnCmd.add(publicPortAddr);
        openvpnCmd.add(OPENVPN_OPT_PORT);
        openvpnCmd.add(Integer.toString(vpn.port));
        if (logDir != null) {
            openvpnCmd.add(OPENVPN_OPT_LOG);
            openvpnCmd.add(logDir + "/" + publicPortName + ".log");
        } else {
            // redirect log output to syslog with publicPortName.
            log.info("openvpn's log is redirected to syslog {}",
                    publicPortName);
            openvpnCmd.add(OPENVPN_OPT_LOG);
            openvpnCmd.add(publicPortName);
        }
        log.info("openvpn cmd: {}", openvpnCmd.toString());
        ProcessBuilder pb = new ProcessBuilder(openvpnCmd);
        Process p = pb.start();
        vpnIdToProcess.put(vpnId, p);
    }

    @Override
    public void stop(UUID serviceId) {
        if (!vpnIdToProcess.containsKey(serviceId)) {
            log.warn("No openvpn process found for {}", serviceId);
            return;
        }
        Process p = vpnIdToProcess.remove(serviceId);
        log.info("stop openvpn process for serviceId {}", serviceId);
        p.destroy();
    }
}
