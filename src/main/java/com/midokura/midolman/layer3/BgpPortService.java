package com.midokura.midolman.layer3;

import com.midokura.midolman.L3DevicePort;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.midolman.quagga.BgpConnection;
import com.midokura.midolman.quagga.ZebraServer;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.RouterPortConfig;
import com.midokura.midolman.state.PortDirectory.PortConfig;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.util.Net;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.io.IOException;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BgpPortService implements PortService {

    private static final Logger log = LoggerFactory
        .getLogger(PortService.class);

    public static final String BGP_SERVICE_EXT_ID = "bgp";
    private static final short BGP_TCP_PORT = 179;
    private static final String BGP_PORT_NAME = "midobgp";

    protected OpenvSwitchDatabaseConnection ovsdb;
    // The external id key for port service.
    protected String portIdExtIdKey;
    protected String portServiceExtIdKey;

    protected NetworkController controller;

    protected PortZkManager portMgr;
    protected RouteZkManager routeMgr;
    protected BgpZkManager bgpMgr;
    protected AdRouteZkManager adRouteMgr;

    protected ZebraServer zebra;
    protected BgpConnection bgpd;
    protected Runtime runtime;

    private int bgpPortIdx = 0;


    public BgpPortService(OpenvSwitchDatabaseConnection ovsdb,
                          String portIdExtIdKey, String portServiceExtIdKey,
                          PortZkManager portMgr, RouteZkManager routeMgr,
                          BgpZkManager bgpMgr, AdRouteZkManager adRouteMgr,
                          ZebraServer zebra, BgpConnection bgpd,
                          Runtime runtime) {
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
        this.runtime = runtime;
    }

    @Override
    public void setController(NetworkController controller) {
        this.controller = controller;
    }

    @Override
    public Set<String> getPorts(L3DevicePort port) throws KeeperException,
        InterruptedException, ZkStateSerializationException {
        UUID portId = port.getId();
        return ovsdb.getPortNamesByExternalId(portIdExtIdKey,
                                              portId.toString());
    }

    private void addPort(final long datapathId, final UUID portId) throws
        KeeperException, InterruptedException, ZkStateSerializationException {
        // Check service attributes in port configurations.
        List<ZkNodeEntry<UUID, BgpConfig>> bgpNodes = bgpMgr.list(
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
                        addPort(datapathId, portId);
                    } catch(Exception e) {
                        log.warn("addPort", e);
                    }
                }
            });

        for (ZkNodeEntry<UUID, BgpConfig> bgpNode : bgpNodes) {
            // TODO(yoshi): consider delete and recreate.
            String portName = String.format(BGP_PORT_NAME + "%d", bgpPortIdx);
            // The length of interface names are limited to 16 bytes.
            if (portName.length() > 16) {
                throw new RuntimeException(
                    "The name of the service port is too long");
            }

            log.info(String.format("Add %s port %s to datapath %d",
                                   BGP_SERVICE_EXT_ID, portName, datapathId));
            PortBuilder portBuilder = ovsdb.addInternalPort(datapathId,
                                                            portName);
            portBuilder.externalId(portIdExtIdKey, portId.toString());
            portBuilder.externalId(portServiceExtIdKey, BGP_SERVICE_EXT_ID);
            // If there is an existing service port, ovs will return False.
            portBuilder.build();

            bgpPortIdx += 1;
        }
    }

    @Override
    public void addPort(long datapathId, L3DevicePort port)
        throws KeeperException, InterruptedException,
        ZkStateSerializationException {
        UUID portId = port.getId();
        this.addPort(datapathId, portId);
    }

    @Override
    public UUID getRemotePort(long datapathId, short portNum,
                              String portName) {
        String service = ovsdb.getPortExternalId(datapathId, portNum,
                                                 portServiceExtIdKey);
        if (!BGP_SERVICE_EXT_ID.equals(service)) {
            log.info("No service type found for this port");
            return null;
        }
        String extId = ovsdb.getPortExternalId(datapathId, portNum,
                                               portIdExtIdKey);
        if (extId == null) {
            log.info("No remote port found for this service port ");
            return null;
        }

        return UUID.fromString(extId);
    }

    @Override
    public void configurePort(long datapathId, UUID portId, String portName)
        throws KeeperException, InterruptedException,
        ZkStateSerializationException, IOException {
        // Turn on ARP and link up the interface.
        // mtu 1300 is to avoid ovs dropping packets.
        // TODO(yoshi): Make MTU variable configurable.
        runtime.exec(
            String.format(
                "ip link set dev %s arp on mtu 1300 multicast off up",
                portName));
        // Assume that materialized port config is already there.
        PortConfig config = portMgr.get(portId).value;
        if (!(config instanceof MaterializedRouterPortConfig)) {
            throw new RuntimeException(
                "Target port isn't a MaterializedRouterPortConfig.");
        }
        MaterializedRouterPortConfig portConfig =
            MaterializedRouterPortConfig.class.cast(config);
        // Give the interface the address in vport configuration.
        Runtime.getRuntime().exec(
            String.format(
                "ip link addr add %s/%d dev %s",
                Net.convertIntAddressToString(portConfig.portAddr),
                portConfig.nwLength, portName));
    }

    public void start(final short localPortNum, final L3DevicePort remotePort)
        throws
        KeeperException, InterruptedException, ZkStateSerializationException,
        IOException {
        UUID remotePortId = remotePort.getId();
        short remotePortNum = remotePort.getNum();
        MaterializedRouterPortConfig portConfig = remotePort.getVirtualConfig();
        int localAddr = portConfig.portAddr;

        for (ZkNodeEntry<UUID, BgpConfig> bgpNode : bgpMgr.list(
                 remotePortId, new Runnable() {
                     @Override
                     public void run() {
                         try {
                             start(localPortNum, remotePort);
                         } catch(Exception e) {
                             log.warn("start", e);
                         }
                     }
                 })) {
            BgpConfig bgpConfig = bgpNode.value;
            int remoteAddr = Net.convertInetAddressToInt(bgpConfig.peerAddr);
            log.info(String.format("Port service flows: local %d remote %d " +
                                   "localAddr %d remoteAddr %d " +
                                   "localPort %d remotePort %d",
                                   localPortNum, remotePortNum,
                                   localAddr, remoteAddr,
                                   BGP_TCP_PORT, BGP_TCP_PORT));
            controller.setServicePortFlows(localPortNum, remotePortNum,
                                           localAddr, remoteAddr,
                                           BGP_TCP_PORT, BGP_TCP_PORT);
            // Call Zebra. Check only one is running.
            // Call Vty.
        }
    }
}