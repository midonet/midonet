package com.midokura.midolman.state;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Random;
import java.util.UUID;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.midokura.midolman.layer3.Route;
import com.midokura.midolman.state.PortDirectory.BridgePortConfig;
import com.midokura.midolman.state.PortDirectory.LogicalRouterPortConfig;
import com.midokura.midolman.state.PortDirectory.MaterializedRouterPortConfig;

public class TestPortDirectory {

    PortDirectory portDir;
    Random rand;

    @Before
    public void setUp() {
        Directory dir = new MockDirectory();
        portDir = new PortDirectory(dir);
        rand = new Random();
    }

    private class MyWatcher implements Runnable {
        int numCalls = 0;

        @Override
        public void run() {
            numCalls++;
        }

    }

    @Test
    public void test32BitUUID() {
        byte b = Byte.MIN_VALUE;
        int a = b;
        System.out.println(Integer.toHexString(a));

        int lBits1 = -5;
        UUID id1 = PortDirectory.intTo32BitUUID(lBits1);
        Assert.assertEquals(lBits1, PortDirectory.UUID32toInt(id1));
        lBits1 = 20;
        id1 = PortDirectory.intTo32BitUUID(lBits1);
        Assert.assertEquals(lBits1, PortDirectory.UUID32toInt(id1));
        lBits1 = Integer.MAX_VALUE;
        id1 = PortDirectory.intTo32BitUUID(lBits1);
        Assert.assertEquals(lBits1, PortDirectory.UUID32toInt(id1));
        lBits1 = Integer.MIN_VALUE;
        id1 = PortDirectory.intTo32BitUUID(lBits1);
        Assert.assertEquals(lBits1, PortDirectory.UUID32toInt(id1));

        id1 = PortDirectory.generate32BitUUID();
        UUID id2 = PortDirectory.intTo32BitUUID(PortDirectory.UUID32toInt(id1));
        Assert.assertTrue(id1.equals(id2));
    }

    @Test
    public void testAddGetUpdateDeleteBridgePort() throws IOException,
            ClassNotFoundException, KeeperException, InterruptedException {
        UUID portId = new UUID(rand.nextLong(), rand.nextLong());
        UUID bridgeId = new UUID(rand.nextLong(), rand.nextLong());
        BridgePortConfig port = new BridgePortConfig(
                bridgeId);
        portDir.addPort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfig(portId, null, null));
        UUID bridgeId1 = new UUID(rand.nextLong(), rand.nextLong());
        Assert.assertNotSame(bridgeId1, bridgeId);
        port.device_id = bridgeId1;
        try {
            portDir.addPort(portId, port);
            Assert.fail("Should not be able to add same port twice");
        } catch (NodeExistsException e) {
        }
        portDir.updatePort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfig(portId, null, null));
        MyWatcher watch = new MyWatcher();
        try {
            portDir.getPortConfig(portId, null, watch);
            Assert.fail("Shouldn't watch routes on a bridge port");
        } catch (IllegalArgumentException e) {
        }
        portDir.getPortConfig(portId, watch, null);
        port.device_id = bridgeId;
        Assert.assertEquals(0, watch.numCalls);
        portDir.updatePort(portId, port);
        Assert.assertEquals(1, watch.numCalls);
        // The watcher won't be called again if we don't re-register.
        port.device_id = bridgeId1;
        portDir.updatePort(portId, port);
        Assert.assertEquals(1, watch.numCalls);
        portDir.deletePort(portId);
        try {
            portDir.getPortConfig(portId, null, null);
            Assert.fail("Should have thrown NoNodeException");
        } catch (NoNodeException e) {
        }
    }

    @Test
    public void testAddGetUpdateDeleteMaterializedRouterPort()
            throws IOException, ClassNotFoundException, KeeperException,
            InterruptedException {
        UUID portId = new UUID(rand.nextLong(), rand.nextLong());
        UUID routerId = new UUID(rand.nextLong(), rand.nextLong());
        MaterializedRouterPortConfig port = new MaterializedRouterPortConfig(
                routerId, 0x01020300, 24, 0x01020301, new HashSet<Route>(),
                0x01020305, 32, new HashSet<BGP>());
        portDir.addPort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfig(portId, null, null));
        UUID routerId1 = new UUID(rand.nextLong(), rand.nextLong());
        Assert.assertNotSame(routerId1, routerId);
        port.device_id = routerId1;
        port.localNwAddr = 0x01020308;
        try {
            portDir.addPort(portId, port);
            Assert.fail("Should not be able to add same port twice");
        } catch (NodeExistsException e) {
        }
        portDir.updatePort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfig(portId, null, null));
        MyWatcher portWatch = new MyWatcher();
        MyWatcher routesWatch = new MyWatcher();
        // Register the watchers.
        portDir.getPortConfig(portId, portWatch, routesWatch);
        port.device_id = routerId;
        Assert.assertEquals(0, portWatch.numCalls);
        Assert.assertEquals(0, routesWatch.numCalls);
        portDir.updatePort(portId, port);
        Assert.assertEquals(1, portWatch.numCalls);
        Assert.assertEquals(0, routesWatch.numCalls);
        // Re-register the port watcher.
        portDir.getPortConfig(portId, portWatch, null);
        Route rt = new Route(0, 0, 0x0102030f, 32, Route.NextHop.PORT, portId,
                0, 5, "attrs", null);
        port.routes.add(rt);
        portDir.updatePort(portId, port);
        Assert.assertEquals(2, portWatch.numCalls);
        Assert.assertEquals(1, routesWatch.numCalls);
        // The watcher won't be called again if we don't re-register.
        port.routes.remove(rt);
        rt.dstNetworkLength = 24;
        port.routes.add(rt);
        port.nwLength = 16;
        portDir.updatePort(portId, port);
        Assert.assertEquals(2, portWatch.numCalls);
        Assert.assertEquals(1, routesWatch.numCalls);
        portDir.deletePort(portId);
        try {
            portDir.getPortConfig(portId, null, null);
            Assert.fail("Should have thrown NoNodeException");
        } catch (NoNodeException e) {
        }
    }

    @Test
    public void testAddGetUpdateDeleteLogicalRouterPort() throws IOException,
            ClassNotFoundException, KeeperException, InterruptedException {
        UUID portId = new UUID(rand.nextLong(), rand.nextLong());
        UUID peerPortId = new UUID(rand.nextLong(), rand.nextLong());
        UUID routerId = new UUID(rand.nextLong(), rand.nextLong());
        LogicalRouterPortConfig port = new LogicalRouterPortConfig(
                routerId, 0x01020300, 24, 0x01020301, new HashSet<Route>(),
                peerPortId);
        portDir.addPort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfig(portId, null, null));
        UUID routerId1 = new UUID(rand.nextLong(), rand.nextLong());
        UUID peerPortId1 = new UUID(rand.nextLong(), rand.nextLong());
        Assert.assertNotSame(routerId, routerId1);
        Assert.assertNotSame(peerPortId, peerPortId1);
        port.device_id = routerId1;
        port.peer_uuid = peerPortId1;
        try {
            portDir.addPort(portId, port);
            Assert.fail("Should not be able to add same port twice");
        } catch (NodeExistsException e) {
        }
        portDir.updatePort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfig(portId, null, null));
        MyWatcher portWatch = new MyWatcher();
        MyWatcher routesWatch = new MyWatcher();
        // Register the watchers.
        portDir.getPortConfig(portId, portWatch, routesWatch);
        port.device_id = routerId;
        port.peer_uuid = peerPortId;
        Assert.assertEquals(0, portWatch.numCalls);
        Assert.assertEquals(0, routesWatch.numCalls);
        portDir.updatePort(portId, port);
        Assert.assertEquals(1, portWatch.numCalls);
        Assert.assertEquals(0, routesWatch.numCalls);
        // Re-register the port watcher.
        portDir.getPortConfig(portId, portWatch, null);
        Route rt1 = new Route(0, 0, 0x0102030f, 32, Route.NextHop.PORT, portId,
                0, 5, "attrs", null);
        Route rt2 = new Route(0, 0, 0x01020300, 24, Route.NextHop.PORT, portId,
                0, 5, "attrs", null);
        port.routes.add(rt1);
        port.routes.add(rt2);
        portDir.updatePort(portId, port);
        Assert.assertEquals(2, portWatch.numCalls);
        Assert.assertEquals(1, routesWatch.numCalls);
        // The watcher won't be called again if we don't re-register.
        port.routes.remove(rt1);
        rt1.attributes = "other attrs";
        rt1.nextHopGateway = 0x010203fe;
        port.routes.add(rt1);
        port.nwLength = 16;
        portDir.updatePort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfig(portId, null, null));
        Assert.assertEquals(2, portWatch.numCalls);
        Assert.assertEquals(1, routesWatch.numCalls);
        portDir.deletePort(portId);
        try {
            portDir.getPortConfig(portId, null, null);
            Assert.fail("Should have thrown NoNodeException");
        } catch (NoNodeException e) {
        }
    }
}
