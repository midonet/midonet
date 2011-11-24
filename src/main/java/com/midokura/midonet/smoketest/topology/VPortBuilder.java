/*
 * Copyright 2011 Midokura Europe SARL
 */

package com.midokura.midonet.smoketest.topology;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openvswitch.BridgeBuilder;
import com.midokura.midolman.openvswitch.BridgeFailMode;
import com.midokura.midolman.openvswitch.ControllerBuilder;
import com.midokura.midolman.openvswitch.ControllerConnectionMode;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.midolman.util.Net;
import com.midokura.midonet.smoketest.mgmt.DtoMaterializedRouterPort;
import com.midokura.midonet.smoketest.mgmt.DtoRoute;
import com.midokura.midonet.smoketest.mgmt.DtoRouter;
import com.midokura.midonet.smoketest.mocks.MidolmanMgmt;

public class VPortBuilder {
    private final static Logger log = LoggerFactory
            .getLogger(VPortBuilder.class);
    private OpenvSwitchDatabaseConnection ovsdb;
    private MidolmanMgmt mgmt;
    private DtoRouter router;
    private DtoMaterializedRouterPort port;
    private String ovsBridgeName;
    private String ovsBridgeController;
    private String ovsPortName;

    VPortBuilder(OpenvSwitchDatabaseConnection ovsdb, MidolmanMgmt mgmt,
            DtoRouter router) {
        this.ovsdb = ovsdb;
        this.mgmt = mgmt;
        this.router = router;
        port = new DtoMaterializedRouterPort();
        port.setLocalNetworkLength(32);
        port.setNetworkLength(24);
        this.ovsBridgeName = "smoke-br";
        this.ovsBridgeController = "tcp:127.0.0.1:6633";
    }

    public VPortBuilder setDestination(String ip4) {
        port.setNetworkAddress(ip4);
        port.setLocalNetworkAddress(ip4);
        setPortAddress();
        return this;
    }

    private void setPortAddress() {
        // The port address is set to 1 + the masked network address.
        if (port.getNetworkAddress() == null)
            return;
        int netAddr = Net.convertStringAddressToInt(port.getNetworkAddress());
        int mask = ~0 << (32 - port.getNetworkLength());
        int pAddr = 1 + (netAddr & mask);
        port.setPortAddress(Net.convertIntAddressToString(pAddr));
    }

    public VPortBuilder setNetworkLength(int length) {
        port.setNetworkLength(length);
        setPortAddress();
        return this;
    }

    public VPortBuilder setLocalNetworkLength(int length) {
        port.setLocalNetworkLength(length);
        return this;
    }

    public VPortBuilder setOVSBridgeName(String name) {
        ovsBridgeName = name;
        return this;
    }

    public VPortBuilder setOVSBridgeController(String target) {
        ovsBridgeController = target;
        return this;
    }

    public VPortBuilder setOVSPortName(String name) {
        ovsPortName = name;
        return this;
    }

    private DtoMaterializedRouterPort buildVPort() {
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
        return p;
    }

    public TapPort buildTap() {
        DtoMaterializedRouterPort vport = buildVPort();
        String portName = ovsPortName == null ? "tapPort1" : ovsPortName;
        addOVSBridge();
        try {
            Process p = Runtime.getRuntime().exec(
                    String.format("sudo -n ip tuntap add dev %s mode tap user pino group pino",
                            portName));
            p.waitFor();
            log.debug("\"sudo -n ip tuntap add dev {} mode tap\" returned: {}",
                    portName, p.exitValue());
            p = Runtime.getRuntime().exec(
                    String.format("sudo -n ip link set dev %s arp off multicast off up", portName));
            p.waitFor();
            log.debug("\"sudo -n ip link set dev {} up\" exited with: {}",
                    portName, p.exitValue());
        } catch (InterruptedException e) {
            log.error("InterruptedException {}", e);
            return null;
        } catch (IOException e) {
            log.error("IOException {}", e);
            return null;
        }
        PortBuilder pBuilder = ovsdb.addSystemPort(ovsBridgeName, portName);
        pBuilder.externalId("midolman-vnet", vport.getId().toString());
        pBuilder.build();
        return new TapPort(mgmt, vport, portName);
    }

    public InternalPort buildInternal() {
        DtoMaterializedRouterPort vport = buildVPort();
        String portName = ovsPortName == null ? "intPort1" : ovsPortName;
        addOVSBridge();
        PortBuilder pBuilder = ovsdb.addInternalPort(ovsBridgeName, portName);
        pBuilder.externalId("midolman-vnet", vport.getId().toString());
        pBuilder.build();
        try {
            Thread.sleep(1000);
            Process p = Runtime
                    .getRuntime()
                    .exec(String
                            .format("sudo -n ip link set dev %s arp on mtu 1400 multicast off up",
                                    portName));
            p.waitFor();
            log.info("ip link set arp,mtu,multicast - returned {}",
                    p.exitValue());
            p = Runtime.getRuntime().exec(
                    String.format("sudo -n ip addr add %s/%s dev %s",
                            vport.getLocalNetworkAddress(), 24, portName));
            p.waitFor();
            log.info("ip addr add - returned {}", p.exitValue());
        } catch (InterruptedException e) {
            log.error("InterruptedException {}", e);
            return null;
        } catch (IOException e) {
            log.error("IOException {}", e);
            return null;
        }
        return new InternalPort(mgmt, vport, portName);
    }

    public VMPort buildVM() {
        addOVSBridge();
        return null;
    }

    private void addOVSBridge() {
        if (ovsdb.hasBridge(ovsBridgeName))
            return;
        BridgeBuilder brBuilder = ovsdb.addBridge(ovsBridgeName);
        brBuilder.externalId("midolman-vnet",
                "01234567-0123-0123-aaaa-0123456789ab");
        brBuilder.failMode(BridgeFailMode.SECURE);
        brBuilder.otherConfig("hwaddr", "02:aa:bb:11:22:33");
        brBuilder.build();
        // Add the Midolman controller.
        ControllerBuilder ctlBuilder = ovsdb.addBridgeOpenflowController(
                ovsBridgeName, ovsBridgeController);
        ctlBuilder.connectionMode(ControllerConnectionMode.OUT_OF_BAND);
        ctlBuilder.build();
        // TODO(pino): Add a service controller. This test will launch the
        // service
        // controller in order to query the switch statistics.
        // ctlBuilder = ovsdb.addBridgeOpenflowController(
        // brName, "ptcp:6634");
        // ctlBuilder.build();
    }
}
