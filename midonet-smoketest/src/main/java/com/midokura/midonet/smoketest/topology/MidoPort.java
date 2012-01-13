/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.midokura.midolman.packets.IntIPv4;
import com.midokura.midolman.state.VpnZkManager;
import com.midokura.midonet.smoketest.mgmt.DtoBgp;
import com.midokura.midonet.smoketest.mgmt.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mgmt.DtoRoute;
import com.midokura.midonet.smoketest.mgmt.DtoRouter;
import com.midokura.midonet.smoketest.mgmt.DtoVpn;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class MidoPort {

    public static class VMPortBuilder {
        private MidolmanMgmt mgmt;
        private DtoRouter router;
        private DtoMaterializedRouterPort port;

        public VMPortBuilder(MidolmanMgmt mgmt, DtoRouter router) {
            this.mgmt = mgmt;
            this.router = router;
            port = new DtoMaterializedRouterPort();
            port.setLocalNetworkLength(32);
            port.setNetworkLength(24);
        }

        public VMPortBuilder setVMAddress(IntIPv4 addr) {
            int mask = ~0 << 8;
            IntIPv4 netAddr = new IntIPv4(addr.address & mask);
            port.setNetworkAddress(netAddr.toString());
            port.setLocalNetworkAddress(addr.toString());
            // The router port's address is 1 + the network address.
            IntIPv4 portAddr = new IntIPv4(1 + netAddr.address);
            port.setPortAddress(portAddr.toString());
            return this;
        }

        public MidoPort build() {
            DtoMaterializedRouterPort p = mgmt.addRouterPort(router, port);
            DtoRoute rt = new DtoRoute();
            rt.setDstNetworkAddr(p.getLocalNetworkAddress());
            rt.setDstNetworkLength(p.getLocalNetworkLength());
            rt.setSrcNetworkAddr("0.0.0.0");
            rt.setSrcNetworkLength(0);
            rt.setType(DtoRoute.Normal);
            rt.setNextHopPort(p.getId());
            rt.setWeight(10);
            rt = mgmt.addRoute(router, rt);
            return new MidoPort(mgmt, p);
        }
    }

    /**
     * A GatewayPort is a Materialized Port that will have local link addresses,
     * connect to a gateway and forward many routes.
     */
    public static class GWPortBuilder {
        private MidolmanMgmt mgmt;
        private DtoRouter router;
        private DtoMaterializedRouterPort port;
        private IntIPv4 peerIp;
        private List<IntIPv4> routes;

        public GWPortBuilder(MidolmanMgmt mgmt, DtoRouter router) {
            this.mgmt = mgmt;
            this.router = router;
            port = new DtoMaterializedRouterPort();
            port.setLocalNetworkLength(30);
            port.setNetworkLength(30);
            routes = new ArrayList<IntIPv4>();
        }

        public GWPortBuilder setLocalLink(IntIPv4 localIp, IntIPv4 peerIp) {
            this.peerIp = peerIp;
            port.setPortAddress(localIp.toString());
            int mask = ~0 << 8;
            IntIPv4 netAddr = new IntIPv4(localIp.address & mask);
            port.setNetworkAddress(netAddr.toString());
            port.setLocalNetworkAddress(netAddr.toString());
            return this;
        }

        public GWPortBuilder addRoute(IntIPv4 nwDst) {
            routes.add(nwDst);
            return this;
        }

        public MidoPort build() {
            DtoMaterializedRouterPort p = mgmt.addRouterPort(router, port);
            for (IntIPv4 dst : routes) {
                DtoRoute rt = new DtoRoute();
                rt.setDstNetworkAddr(dst.toString());
                rt.setDstNetworkLength(24);
                rt.setSrcNetworkAddr("0.0.0.0");
                rt.setSrcNetworkLength(0);
                rt.setType(DtoRoute.Normal);
                rt.setNextHopPort(p.getId());
                rt.setNextHopGateway(peerIp.toString());
                rt.setWeight(10);
                rt = mgmt.addRoute(router, rt);
            }
            return new MidoPort(mgmt, p);
        }
    }

    public static class VPNPortBuilder {
        private MidolmanMgmt mgmt;
        private DtoRouter router;
        private DtoMaterializedRouterPort port;
        private DtoVpn vpn;

        public VPNPortBuilder(MidolmanMgmt mgmt, DtoRouter router) {
            this.mgmt = mgmt;
            this.router = router;
            this.port = new DtoMaterializedRouterPort();
            this.vpn = new DtoVpn();
        }

        public VPNPortBuilder setVpnType(VpnZkManager.VpnType type) {
            vpn.setVpnType(type);
            return this;
        }

        public VPNPortBuilder setLocalIp(IntIPv4 addr) {
            int mask = ~0 << 8;
            IntIPv4 netAddr = new IntIPv4(addr.address & mask);
            port.setNetworkAddress(netAddr.toString());
            port.setNetworkLength(24);
            port.setLocalNetworkAddress(addr.toString());
            port.setLocalNetworkLength(24);
            // The router port's address is 1 + the network address.
            IntIPv4 portAddr = new IntIPv4(1 + netAddr.address);
            port.setPortAddress(portAddr.toString());
            return this;
        }

        public VPNPortBuilder setPrivatePortId(UUID vportId) {
            vpn.setPrivatePortId(vportId);
            return this;
        }

        public VPNPortBuilder setLayer4Port(int layer4Port) {
            vpn.setPort(layer4Port);
            return this;
        }

        public VPNPortBuilder setRemoteIp(String remoteIp) {
            vpn.setRemoteIp(remoteIp);
            return this;
        }

        public MidoPort build() {
            DtoMaterializedRouterPort p = mgmt.addRouterPort(router, port);
            mgmt.addVpn(p, vpn);
            DtoRoute rt = new DtoRoute();
            rt.setDstNetworkAddr(port.getLocalNetworkAddress());
            rt.setDstNetworkLength(port.getNetworkLength());
            rt.setSrcNetworkAddr("0.0.0.0");
            rt.setSrcNetworkLength(0);
            rt.setType(DtoRoute.Normal);
            rt.setNextHopPort(p.getId());
            rt.setWeight(10);
            rt = mgmt.addRoute(router, rt);
            return new MidoPort(mgmt, p);
        }
    }

    MidolmanMgmt mgmt;
    public DtoMaterializedRouterPort port;

    MidoPort(MidolmanMgmt mgmt, DtoMaterializedRouterPort port) {
        this.mgmt = mgmt;
        this.port = port;
    }

    public void delete() {
        mgmt.delete(port.getUri());
    }

    public Bgp.Builder addBgp() {

        return new Bgp.Builder() {

            int localAS;
            int peerAS;
            String peerAddress;

            @Override
            public Bgp.Builder setLocalAs(int localAS) {
                this.localAS = localAS;
                return this;
            }

            @Override
            public Bgp.Builder setPeer(int peerAS, String peerAddress) {
                this.peerAS = peerAS;
                this.peerAddress = peerAddress;

                return this;
            }

            @Override
            public Bgp build() {
                DtoBgp bgp = new DtoBgp();

                bgp.setLocalAS(localAS);
                bgp.setPeerAS(peerAS);
                bgp.setPeerAddr(peerAddress);

                return new Bgp(mgmt, mgmt.addBGP(port, bgp));
            }
        };
    }
}
