/**
 * MockPortService.java - A mock class for port serivices.
 *
 * Copyright (c) 2011 Midokura KK. All rights reserved.
 */

package com.midokura.midolman.portservice;

import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.layer3.ServiceFlowController;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.packets.MAC;
import com.midokura.quagga.ZebraServer;
import com.midokura.quagga.BgpVtyConnection;
import com.midokura.midolman.state.AdRouteZkManager;
import com.midokura.midolman.state.BgpZkManager;
import com.midokura.midolman.state.PortDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.VpnZkManager;
import com.midokura.midolman.state.ZkStateSerializationException;
import com.midokura.midolman.state.BgpZkManager.BgpConfig;

import com.midokura.midolman.util.Net;

public class MockPortService implements PortService {

    private static final Logger log = LoggerFactory
            .getLogger(MockPortService.class);

    public static final String BGP_SERVICE_EXT_ID = "bgp";
    public static final short BGP_TCP_PORT = 179;
    private static final String BGP_PORT_NAME = "midobgp";

    protected OpenvSwitchDatabaseConnection ovsdb;
    // The external id key for port service.
    protected String portIdExtIdKey;
    protected String portServiceExtIdKey;

    protected ServiceFlowController controller;

    protected PortZkManager portMgr;
    protected RouteZkManager routeMgr;
    protected BgpZkManager bgpMgr;
    protected AdRouteZkManager adRouteMgr;
    protected VpnZkManager vpnMgr;

    protected ZebraServer zebra;
    protected BgpVtyConnection bgpd;
    protected Runtime runtime;

    private int bgpPortIdx = 0;
    private int bgpPortNum = BGP_TCP_PORT + bgpPortIdx;

    private Map<Integer, UUID> portNumToRemoteUUID;
    private Map<String, UUID> portNameToRemoteUUID;

    private boolean raiseException = false;

    public MockPortService(OpenvSwitchDatabaseConnection ovsdb,
            String portIdExtIdKey, String portServiceExtIdKey,
            PortZkManager portMgr, RouteZkManager routeMgr,
            BgpZkManager bgpMgr, AdRouteZkManager adRouteMgr,
            ZebraServer zebra, BgpVtyConnection bgpd, Runtime runtime) {
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

    public MockPortService(PortZkManager portMgr, BgpZkManager bgpMgr) {
        this.portMgr = portMgr;
        this.bgpMgr = bgpMgr;
        this.portNumToRemoteUUID = new HashMap<Integer, UUID>();
        this.portNameToRemoteUUID = new HashMap<String, UUID>();
    }

    public MockPortService(PortZkManager portMgr, VpnZkManager vpnMgr) {
        this.portMgr = portMgr;
        this.vpnMgr = vpnMgr;
        this.portNumToRemoteUUID = new HashMap<Integer, UUID>();
        this.portNameToRemoteUUID = new HashMap<String, UUID>();
    }

    public void enableException() {
        this.raiseException = true;
    }

    public void disableException() {
        this.raiseException = false;
    }

    @Override
    public void clear() {
        portNumToRemoteUUID.clear();
        portNameToRemoteUUID.clear();
        bgpPortIdx = 0;
    }

    @Override
    public void setController(ServiceFlowController controller) {
        this.controller = controller;
    }

    @Override
    public Set<String> getPorts(UUID portId) throws StateAccessException,
            ZkStateSerializationException {
        return new HashSet<String>();
    }

    @Override
    public void addPort(long datapathId, UUID portId, MAC mac)
        throws StateAccessException {
        if (this.raiseException) {
            throw new StateAccessException();
        }
        // Check service attributes in port configurations.
        if (bgpMgr != null) {
            List<UUID> bgpNodes = bgpMgr.list(portId);
            for (UUID bgpNode : bgpNodes) {
                log.debug("Add {}{} port {} num {} UUID {} to datapath {}",
                          new Object[] {BGP_PORT_NAME, bgpPortIdx,
                                        BGP_SERVICE_EXT_ID, bgpPortNum, portId,
                                        datapathId});
                portNumToRemoteUUID.put(bgpPortNum, portId);
                portNameToRemoteUUID.put(BGP_PORT_NAME + bgpPortIdx, portId);
                bgpPortIdx += 1;
            }
        } else if (vpnMgr != null) {
            List<UUID> vpnNodes = vpnMgr.list(portId);
            for (UUID vpnNode : vpnNodes) {
                log.debug("Add {}{} port {} num {} UUID {} to datapath {}",
                          new Object[] {BGP_PORT_NAME, bgpPortIdx,
                                        BGP_SERVICE_EXT_ID, bgpPortNum, portId,
                                        datapathId});
                portNumToRemoteUUID.put(bgpPortNum, portId);
                portNameToRemoteUUID.put(BGP_PORT_NAME + bgpPortIdx, portId);
                bgpPortIdx += 1;
            }
        }
    }

    @Override
    public void addPort(long datapathId, UUID portId)
        throws StateAccessException {
        if (this.raiseException) {
            throw new StateAccessException();
        }
        addPort(datapathId, portId, null);
    }

    @Override
    public UUID getRemotePort(String portName) {
        UUID portId = portNameToRemoteUUID.get(portName);
        return portId;
    }

    @Override
    public void configurePort(UUID portId, String portName)
            throws StateAccessException, IOException {
        log.debug("Configure port " + portId.toString());
        if (this.raiseException) {
            throw new StateAccessException();
        }
    }

    @Override
    public void configurePort(UUID portId)
            throws StateAccessException, IOException {
        log.debug("Configure port " + portId.toString());
        if (this.raiseException) {
            throw new StateAccessException();
        }
    }

    @Override
    public void delPort(UUID portId) {
        log.debug("delPort {}", portId);
    }

    @Override
    public void start(long datapathId, short localPortNum, short remotePortNum)
            throws StateAccessException {
        UUID remotePortId = portNumToRemoteUUID.get((int)localPortNum);
        log.debug("start: localPortNum {} remotePortId {}",
                  localPortNum, remotePortId);
        if (this.raiseException) {
            throw new StateAccessException();
        }
        PortDirectory.MaterializedRouterPortConfig portConfig =
                portMgr.get(remotePortId,
                        PortDirectory.MaterializedRouterPortConfig.class);
        int localAddr = portConfig.portAddr;

        for (UUID bgpNode : bgpMgr.list(remotePortId)) {
            BgpConfig bgpConfig = bgpMgr.get(bgpNode);
            int remoteAddr = Net.convertInetAddressToInt(bgpConfig.peerAddr);
            log.debug("Port service flows: local {} remote {} "
                    + "localAddr {} remoteAddr {} "
                    + "localPort {} remotePort {}", new Object[] {localPortNum,
                    remotePortNum, localAddr, remoteAddr, BGP_TCP_PORT,
                    BGP_TCP_PORT});
            controller.setServiceFlows(localPortNum, remotePortNum,
                    localAddr, remoteAddr, BGP_TCP_PORT, BGP_TCP_PORT);
        }
    }

    @Override
    public void start(UUID serviceId)
        throws StateAccessException, IOException {
        log.debug("start {}", serviceId);
        if (this.raiseException) {
            throw new StateAccessException();
        }
    }

    @Override
    public void stop(UUID serviceId) {
        log.debug("stop {}", serviceId);
    }
}
