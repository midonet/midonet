/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman;

import java.util.UUID;

import org.openflow.protocol.OFMatch;

public class Bridge implements ForwardingElement {

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
     * check for DHCP (match->tp_src and tp_dst have already been parsed,
     * look for 67, 68) if DHCP then call DHCP handler inside the bridge
     * (DHCP object is created from Bridge constructor, watch /bridge/id/dhcp)
     * PINO- WE DECIDED TO HANDLE DHCP IN VRN_CONTROLLER WITHOUT SIMULATING.
     */
    @Override
    public void process(ForwardingElement.ForwardInfo fwdInfo) {
        // TODO(pino): naive implementation - forward to Bridge's PortSet
        // TODO(pino): do we need to be able to list all the logical ports on
        // this bridge in order to pre-seed its Filtering Database with those
        // ports' addresses?
        // TODO(pino): do we need to be able to list all routers and dhcp
        // servers so that we can intercept ARP requests?
        // This information is not currently available in MM, only MM-mgmt.
    }

    @Override
    public void addPort(UUID portId) {

    }

    @Override
    public void removePort(UUID portId) {

    }

    @Override
    public UUID getId() {
        return null;  // TODO
    }

    @Override
    public void freeFlowResources(OFMatch match) {
    }
}
