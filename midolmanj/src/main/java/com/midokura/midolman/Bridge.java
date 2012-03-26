/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman;

import com.midokura.midolman.packets.Ethernet;
import com.midokura.midolman.packets.MAC;
import com.midokura.midolman.state.BridgeZkManager;
import com.midokura.midolman.state.MacPortMap;
import org.apache.zookeeper.KeeperException;
import org.openflow.protocol.OFMatch;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;


public class Bridge implements ForwardingElement {

    Logger log = LoggerFactory.getLogger(Bridge.class);

    UUID bridgeId;
    Set<UUID> localPorts = new HashSet<UUID>();
    private MacPortMap macPortMap;
    private BridgeZkManager bridgeZkManager;

    public Bridge(UUID bridgeId, MacPortMap macPortMap_,
                  BridgeZkManager bridgeZkManager_) throws KeeperException {

        this.bridgeId = bridgeId;

        // To get bridgeConfig, to get GRE key
        bridgeZkManager = bridgeZkManager_;
        macPortMap = macPortMap_;
    }

    /*
    * Bridge logical ports should be listed separately from other bridge ports
    * under the bridge in BridgeZkManager.
    *
    * On Bridge create in the JVM (triggered usually by local bridge port add):
    * Call updateLogicalPorts
    * updateLogicalPorts does:
    *   zk->list( /bridges/id/log-ports, this.watcher)
    *   do diff from previous list
    *   for each new logical-port:
    *     get the peer-id, lookup the LogicalRouterPortConfig
    *     get the port-addr and mac-addr from the LRPC
    *     add mac-addr => log-port-id to FilteringDB
    *     add port-addr => mac-addr to ARP cache
    *   for each removed logical-port:
    *     cleanup
    *   // do nothing for logical ports that have not changed.
    *
    * watcher.run() does:
    *   updateLogicalPorts
    *
    * When you receive an ARP:
    *   extract IP (tpa)
    *   look-up in ARP-cache => mac
    *   lookup mac in FDB => port
    *   return FORWARD to port
    *
    * On receiving any packet:
    * check for DHCP (match->tp_src private VpnZkManager macPortMap;and tp_dst have already been parsed,
    * look for 67, 68) if DHCP then call DHCP handler inside the bridge
    * (DHCP object is created from Bridge constructor, watch /bridge/id/dhcp)
    * PINO- WE DECIDED TO HANDLE DHCP IN VRN_CONTROLLER WITHOUT SIMULATING.
    */

    // TODO(abel) remove this dirty hack
    @Override
    public void process (ForwardInfo fwdInfo) {
        // TODO(pino): naive implementation - forward to Bridge's PortSet
        // TODO(pino): do we need to be able to list all the logical ports on
        // this bridge in order to pre-seed its Filtering Database with those
        // ports' addresses?
        // TODO(pino): do we need to be able to list all routers and dhcp
        // servers so that we can intercept ARP requests?
        // This information is not currently available in MM, only MM-mgmt.

        // Check for malformed input
        if (fwdInfo.getInPortId() == null) {
            // TODO log
            drop(fwdInfo);
            return;
        }

        // Is the input port in this bridge?
        if (!localPorts.contains(fwdInfo.getInPortId())) {
            // TODO log  runtime exception
            drop(fwdInfo);
            return;
        }

        // TODO(abel) We don't support multicast right now
        MAC dstMac = fwdInfo.getPktIn().getDestinationMACAddress();
        if (Ethernet.isMcast(dstMac)) {
            drop(fwdInfo);
            return;
        }

        // TODO(abel) We don't support broadcast right now
        if (Ethernet.isBroadcast(dstMac)) {
            drop(fwdInfo);
            return;
        }

        // Find destination port
        UUID dstPort = macPortMap.get(dstMac);
        if (dstPort == null) {
            drop(fwdInfo);
        }

        forward(fwdInfo, dstPort);
    }

    @Override
    public void addPort(UUID portId) {
        localPorts.add(portId);
    }

    @Override
    public void removePort(UUID portId) {
        localPorts.remove(portId);
    }

    @Override
    public UUID getId() {
        return bridgeId;
    }

    @Override
    public void freeFlowResources(OFMatch match, UUID inPortId) {
        // don't do here, as we don't deal with flows here
    }

    @Override
    public void destroy() {


    }

    private void drop (ForwardInfo forwardInfo) {
        forwardInfo.setAction(Action.DROP);
    }

    private void forward(ForwardInfo forwardInfo, UUID dstPort) {
        forwardInfo.setAction(Action.FORWARD);
        forwardInfo.setOutPortId(dstPort);
    }
}
