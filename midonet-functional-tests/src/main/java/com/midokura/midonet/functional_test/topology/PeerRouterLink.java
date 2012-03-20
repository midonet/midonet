/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.functional_test.topology;

import com.midokura.midolman.mgmt.data.dto.client.DtoLogicalRouterPort;
import com.midokura.midolman.mgmt.data.dto.client.DtoPeerRouterLink;
import com.midokura.midolman.mgmt.data.dto.client.DtoRoute;
import com.midokura.midolman.mgmt.data.dto.client.DtoRouter;
import com.midokura.midonet.functional_test.mocks.MidolmanMgmt;

public class PeerRouterLink {

    public static class Builder {
        MidolmanMgmt mgmt;
        DtoRouter router;
        DtoRouter peer;
        String localPrefix;
        String peerPrefix;
        DtoLogicalRouterPort logPort;

        public Builder(MidolmanMgmt mgmt, DtoRouter router) {
            this.mgmt = mgmt;
            this.router = router;
            this.logPort = new DtoLogicalRouterPort();
            logPort.setNetworkAddress("169.254.1.0");
            logPort.setNetworkLength(30);
            logPort.setPortAddress("169.254.1.1");
            logPort.setPeerPortAddress("169.254.1.2");
        }

        public Builder setPeer(Router r) {
            peer = r.dto;
            logPort.setPeerRouterId(peer.getId());
            return this;
        }

        /**
         * Set the /24 network that the peer should route to the 'local' router.
         * @param ipv4
         * @return
         */
        public Builder setLocalPrefix(String ipv4) {
            localPrefix = ipv4;
            return this;
        }

        /**
         * Set the /24 network that the 'local' router should route to the peer.
         * @param ipv4
         * @return
         */
        public Builder setPeerPrefix(String ipv4) {
            peerPrefix = ipv4;
            return this;
        }

        public PeerRouterLink build() {
            PeerRouterLink link = new PeerRouterLink(mgmt,
                    mgmt.linkRouterToPeer(router, logPort));
            // Create the route for the originating router.
            DtoRoute rt = new DtoRoute();
            rt.setDstNetworkAddr(peerPrefix);
            rt.setDstNetworkLength(24);
            rt.setSrcNetworkAddr("0.0.0.0");
            rt.setSrcNetworkLength(0);
            rt.setType(DtoRoute.Normal);
            rt.setNextHopPort(link.dto.getPortId());
            rt.setWeight(10);
            rt = mgmt.addRoute(router, rt);
            // Create the route for the peer router.
            rt = new DtoRoute();
            rt.setDstNetworkAddr(localPrefix);
            rt.setDstNetworkLength(24);
            rt.setSrcNetworkAddr("0.0.0.0");
            rt.setSrcNetworkLength(0);
            rt.setType(DtoRoute.Normal);
            rt.setNextHopPort(link.dto.getPeerPortId());
            rt.setWeight(10);
            rt = mgmt.addRoute(peer, rt);
            return link;
        }
    }

    MidolmanMgmt mgmt;
    public DtoPeerRouterLink dto;

    public PeerRouterLink(MidolmanMgmt mgmt, DtoPeerRouterLink dto) {
        this.mgmt = mgmt;
        this.dto = dto;
    }

    public void delete() {
        mgmt.delete(dto.getUri());
    }
}
