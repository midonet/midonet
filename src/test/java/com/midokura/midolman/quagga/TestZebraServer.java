// Copyright 2011 Midokura Inc.

package com.midokura.midolman.quagga;

import java.io.File;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.newsclub.net.unix.AFUNIXServerSocket;
import org.newsclub.net.unix.AFUNIXSocketAddress;

import com.midokura.midolman.openvswitch.MockOpenvSwitchDatabaseConnection;
import com.midokura.midolman.state.MockDirectory;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.RouteZkManager;


class TestZebraServer {
    private ZebraServer zebra;
 
    @Before
    public void makeZebraServer() {
        MockOpenvSwitchDatabaseConnection ovsdb = 
                                new MockOpenvSwitchDatabaseConnection();
        MockDirectory directory = new MockDirectory();
        String basePath = "/base";
        PortZkManager portMgr = new PortZkManager(directory, basePath);
        RouteZkManager routeMgr = new RouteZkManager(directory, basePath);
        File socketFile = File.createTempFile("testzebra", ".sock");
        socketFile.delete();
        AFUNIXServerSocket server = AFUNIXServerSocket.newInstance();
        AFUNIXSocketAddress address = new AFUNIXSocketAddress(socketFile);
        zebra = new ZebraServer(server, address, portMgr, routeMgr, ovsdb);
    }

    @Test
    public void testZebraStart() {
        try {
            zebra.start();
        } catch (Exception e) {
            Assert.fail("zebra.start() threw an exception: " + e);
        }
    }
}
