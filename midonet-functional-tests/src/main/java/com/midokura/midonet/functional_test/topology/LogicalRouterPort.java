/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoRoute;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.packets.MAC;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class LogicalRouterPort {

    public static class Builder {
        private final MidolmanMgmt mgmt;
        private final DtoRouter router;
        private final DtoLogicalRouterPort port;

        public Builder(MidolmanMgmt mgmt, DtoRouter router) {
            this.mgmt = mgmt;
            this.router = router;
            port = new DtoLogicalRouterPort();
        }

        public Builder setNetworkAddress(String addr) {
            port.setNetworkAddress(addr);
            return this;
        }

        public Builder setNetworkLength(int len) {
            port.setNetworkLength(len);
            return this;
        }

        public Builder setPortAddress(String addr) {
            port.setPortAddress(addr);
            return this;
        }

        public LogicalRouterPort build() {
            DtoLogicalRouterPort p = mgmt.addLogicalRouterPort(router, port);
            return new LogicalRouterPort(mgmt, p, router);
        }
    }

    MidolmanMgmt mgmt;
    public DtoLogicalRouterPort port;
    public DtoRouter router;

    LogicalRouterPort(MidolmanMgmt mgmt, DtoLogicalRouterPort port,
            DtoRouter router) {
        this.mgmt = mgmt;
        this.port = port;
        this.router = router;
    }

    public void link(LogicalRouterPort peerPort, String localPrefixIpv4,
            String peerPrefixIpv4) {
        port.setPeerId(peerPort.port.getId());
        mgmt.linkRouterToPeer(port);
        // Create the route for the originating router.
        DtoRoute rt = new DtoRoute();
        rt.setDstNetworkAddr(peerPrefixIpv4);
        rt.setDstNetworkLength(24);
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setType(DtoRoute.Normal);
        rt.setNextHopPort(port.getId());
        rt.setWeight(10);
        rt = mgmt.addRoute(router, rt);
        // Create the route for the peer router.
        rt = new DtoRoute();
        rt.setDstNetworkAddr(localPrefixIpv4);
        rt.setDstNetworkLength(24);
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setType(DtoRoute.Normal);
        rt.setNextHopPort(peerPort.port.getId());
        rt.setWeight(10);
        mgmt.addRoute(peerPort.router, rt);
    }

    public void link(LogicalBridgePort peerPort) {
        port.setPeerId(peerPort.port.getId());
        mgmt.linkRouterToPeer(port);
        DtoRoute rt = new DtoRoute();
        rt.setDstNetworkAddr(port.getNetworkAddress());
        rt.setDstNetworkLength(port.getNetworkLength());
        rt.setSrcNetworkAddr("0.0.0.0");
        rt.setSrcNetworkLength(0);
        rt.setType(DtoRoute.Normal);
        rt.setNextHopPort(port.getId());
        rt.setWeight(10);
        rt = mgmt.addRoute(router, rt);
    }

    public void unlink() {
        port.setPeerId(null); // Null peerId indicates unlink.
        mgmt.linkRouterToPeer(port);
    }

    public void delete() {
        mgmt.delete(port.getUri());
    }

    public IntIPv4 getIpAddr() {
        return IntIPv4.fromString(port.getPortAddress());
    }

    public MAC getMacAddr() {
        return MAC.fromString(port.getPortMac());
    }
}
