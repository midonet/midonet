package com.midokura.midonet.functional_test.topology;


import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.openvswitch.BridgeBuilder;
import com.midokura.midolman.openvswitch.BridgeFailMode;
import com.midokura.midolman.openvswitch.ControllerBuilder;
import com.midokura.midolman.openvswitch.ControllerConnectionMode;
import com.midokura.midolman.openvswitch.GrePortBuilder;
import com.midokura.midolman.openvswitch.OpenvSwitchDatabaseConnection;
import com.midokura.midolman.openvswitch.PortBuilder;
import com.midokura.packets.IntIPv4;
import static com.midokura.util.process.ProcessHelper.newProcess;

/**
 * Copyright 2011 Midokura Europe SARL User: rossella rossella@midokura.com
 * Date: 12/9/11 Time: 1:21 PM
 */
public class OvsBridge {

    public final static UUID L3UUID = UUID
            .fromString("01234567-0123-0123-aaaa-0123456789ab");
    private final static Logger log = LoggerFactory.getLogger(OvsBridge.class);

    OpenvSwitchDatabaseConnection ovsdb;
    String bridgeName;
    private String ovsBridgeController;

    public OvsBridge(OpenvSwitchDatabaseConnection ovsdb, String bridgeName,
                     String ovsBridgeController) {

        this.ovsdb = ovsdb;
        this.bridgeName = bridgeName;
        this.ovsBridgeController = ovsBridgeController;
        startBridge();
    }

    public OvsBridge(OpenvSwitchDatabaseConnection ovsdb, String bridgeName) {
        this(ovsdb, bridgeName, "tcp:127.0.0.1:6655");
    }

    public String getName() {
        return bridgeName;
    }

    public void remove() {
        ovsdb.delBridge(bridgeName);
    }

    void startBridge() {
        if (ovsdb.hasBridge(bridgeName))
            return;
        BridgeBuilder brBuilder = ovsdb.addBridge(bridgeName);
        brBuilder.externalId("midolman-vnet", L3UUID.toString());
        brBuilder.failMode(BridgeFailMode.SECURE);
        brBuilder.otherConfig("hwaddr", "02:aa:bb:11:22:33");
        brBuilder.build();
        // Add the Midolman controller.
        ControllerBuilder ctlBuilder = ovsdb.addBridgeOpenflowController(
                bridgeName, ovsBridgeController);
        ctlBuilder.connectionMode(ControllerConnectionMode.OUT_OF_BAND);
        ctlBuilder.build();
    }

    public void addServiceController(int port) {
        ControllerBuilder ctlBuilder = ovsdb.addBridgeOpenflowController(
                bridgeName, new StringBuilder("ptcp:").append(port).toString());
        ctlBuilder.connectionMode(ControllerConnectionMode.OUT_OF_BAND);
        ctlBuilder.build();
    }

    public void addSystemPort(UUID id, String name) {
        PortBuilder pBuilder = ovsdb.addSystemPort(bridgeName, name);
        pBuilder.externalId("midolman-vnet", id.toString());
        pBuilder.build();
    }

    public void addInternalPort(UUID id, String name, IntIPv4 ip, int nwLen) {
        PortBuilder pBuilder = ovsdb.addInternalPort(bridgeName, name);
        pBuilder.externalId("midolman-vnet", id.toString());
        pBuilder.build();
        try {
            Thread.sleep(1000);
            newProcess(
                String.format("sudo -n ip link set dev %s arp on " +
                                  "mtu 1400 multicast off up", name))
                .logOutput(log, "int_port")
                .runAndWait();

            newProcess(
                String.format("sudo -n ip addr add %s/%d dev %s",
                              ip.toString(), nwLen, name))
                .logOutput(log, "int_port")
                .runAndWait();
        } catch (InterruptedException e) {
            log.error("Error adding system port.", e);
        }
    }

    public void addGrePort(String name, String localIp, String remoteIp,
                           int greKey) {
        GrePortBuilder builder =
                ovsdb.addGrePort(bridgeName, name, remoteIp.toString())
                        .localIp(localIp);
        if (greKey == 0)
            builder.keyFlow();
        else
            builder.key(greKey);
        builder.build();
    }

    public void deletePort(String name) {
        ovsdb.delPort(name);
    }
}
