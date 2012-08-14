/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midolman.test;

import java.util.Collections;
import java.util.UUID;

import org.openflow.protocol.OFFeaturesReply;
import org.openflow.protocol.OFPhysicalPort;

import com.midokura.midolman.CookieMonster;
import com.midokura.util.eventloop.MockReactor;
import com.midokura.midolman.openflow.MockControllerStub;
import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.packets.IntIPv4;
import com.midokura.packets.MAC;
import com.midokura.midolman.portservice.NullPortService;
import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.cache.Cache;
import com.midokura.midolman.vrn.VRNController;


public class Host {
    VRNController controller;
    MockOpenvSwitchDatabaseConnection ovsDbConnection;
    MockControllerStub stub;

    public Host(Directory baseDirectory, String basePath,
                IntIPv4 hostAddr, Cache cache)
            throws StateAccessException {
        ovsDbConnection = new MockOpenvSwitchDatabaseConnection();
        // register the bridge metadata in ovs
        ovsDbConnection.setDatapathExternalId(getDatapathId(),
                getExternalIdKey(),
                getMidonetDatapathId().toString());

        controller = new VRNController(
                baseDirectory, basePath, hostAddr,
                ovsDbConnection /* ovsdb */,
                new MockReactor(), cache,
                getExternalIdKey(),
                UUID.fromString(getMidonetDatapathId()),
                false /* useNxm */,
                new NullPortService(), new NullPortService(), 1450);

        // set the stub
        stub = new MockControllerStub();
        OFFeaturesReply features = new OFFeaturesReply();
        features.setDatapathId(getDatapathId());
        features.setPorts(Collections.<OFPhysicalPort>emptyList());
        stub.setFeatures(features);
        controller.setControllerStub(stub);

        // simulate the bridge connection
        controller.onConnectionMade();
    }

    public String getExternalIdKey() {
        return "midonet";
    }

    public long getDatapathId() {
        return 47;
    }

    protected String getMidonetDatapathId() {
        return "01234567-0123-0123-aaaa-0123456789ab";
    }

    public OFPhysicalPort makeGrePort(short portNum, String name,
                                      IntIPv4 remoteIP) {
        OFPhysicalPort physicalPort = new OFPhysicalPort();
        physicalPort.setHardwareAddress(MAC.random().getAddress());
        physicalPort.setPortNumber(portNum);
        physicalPort.setName(name);
        return physicalPort;
    }

    public OFPhysicalPort makePhysicalPort(short portNum, UUID portID) {
        ovsDbConnection.setPortExternalId(getDatapathId(), portNum,
                getExternalIdKey(), portID.toString());

        OFPhysicalPort physicalPort = new OFPhysicalPort();
        physicalPort.setHardwareAddress(MAC.random().getAddress());
        physicalPort.setPortNumber(portNum);
        physicalPort.setName("Tap" + portNum);
        return physicalPort;
    }

    public VRNController getController() {
        return controller;
    }

    public CookieMonster getCookieManager() {
        return controller.getCookieMgr();
    }

    public MockControllerStub getStub() {
        return stub;
    }
}
