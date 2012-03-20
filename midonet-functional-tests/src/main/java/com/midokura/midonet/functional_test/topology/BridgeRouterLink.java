/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoBridge;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridgeRouterLink;
import com.midokura.midolman.mgmt.data.dto.client.DtoBridgeRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoPeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.client.DtoRoute;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class BridgeRouterLink {

    public static class Builder {
        MidolmanMgmt mgmt;
        DtoRouter router;
        DtoBridge bridge;
        DtoBridgeRouterPort logPort;

        public Builder(MidolmanMgmt mgmt, DtoRouter router,
                       Bridge br, IntIPv4 subnet) {
            this.mgmt = mgmt;
            this.router = router;
            this.bridge = br.dto;
            this.logPort = new DtoBridgeRouterPort();
            logPort.setBridgeId(bridge.getId());
            logPort.setNetworkAddress(subnet.toUnicastString());
            logPort.setNetworkLength(subnet.getMaskLength());
            logPort.setPortAddress(
                    new IntIPv4(subnet.address + 1).toUnicastString());
        }

        public BridgeRouterLink build() {
            BridgeRouterLink link = new BridgeRouterLink(mgmt,
                    mgmt.linkRouterToBridge(router, logPort));
            // Create a route.
            DtoRoute rt = new DtoRoute();
            rt.setDstNetworkAddr(logPort.getNetworkAddress());
            rt.setDstNetworkLength(logPort.getNetworkLength());
            rt.setSrcNetworkAddr("0.0.0.0");
            rt.setSrcNetworkLength(0);
            rt.setType(DtoRoute.Normal);
            rt.setNextHopPort(link.dto.getRouterPortId());
            rt.setWeight(10);
            rt = mgmt.addRoute(router, rt);
            return link;
        }
    }

    MidolmanMgmt mgmt;
    public DtoBridgeRouterLink dto;

    public BridgeRouterLink(MidolmanMgmt mgmt, DtoBridgeRouterLink dto) {
        this.mgmt = mgmt;
        this.dto = dto;
    }

    public void delete() {
        mgmt.delete(dto.getUri());
    }
}
