package com.midokura.midonet.smoketest.topology;

import com.midokura.midolman.openvswitch.*;

import java.util.UUID;

/**
 * Copyright 2011 Midokura Europe SARL
 * User: rossella rossella@midokura.com
 * Date: 12/9/11
 * Time: 1:21 PM
 */
public class OvsBridge {

    OpenvSwitchDatabaseConnection ovsdb;
    String bridgeName;
    private String ovsBridgeController;

    public OvsBridge(OpenvSwitchDatabaseConnection ovsdb, String bridgeName, String ovsBridgeController) {

        this.ovsdb = ovsdb;
        this.bridgeName = bridgeName;
        this.ovsBridgeController = ovsBridgeController;
        startBridge();
    }

    public OvsBridge(OpenvSwitchDatabaseConnection ovsdb, String bridgeName) {
        this.ovsdb = ovsdb;
        this.bridgeName = bridgeName;
        this.ovsBridgeController = "tcp:127.0.0.1:6633";
        startBridge();
    }

    void startBridge()
    {
        if (ovsdb.hasBridge(bridgeName))
            return;
        BridgeBuilder brBuilder = ovsdb.addBridge(bridgeName);
        brBuilder.externalId("midolman-vnet",
                "01234567-0123-0123-aaaa-0123456789ab");
        brBuilder.failMode(BridgeFailMode.SECURE);
        brBuilder.otherConfig("hwaddr", "02:aa:bb:11:22:33");
        brBuilder.build();
        // Add the Midolman controller.
        ControllerBuilder ctlBuilder = ovsdb.addBridgeOpenflowController(
                bridgeName, ovsBridgeController);
        ctlBuilder.connectionMode(ControllerConnectionMode.OUT_OF_BAND);
        ctlBuilder.build();
    }
    public void addSystemPort(UUID id, String tapName)
    {
        PortBuilder pBuilder = ovsdb.addSystemPort(bridgeName, tapName);
        pBuilder.externalId("midolman-vnet", id.toString());
        pBuilder.build();
    }
}
