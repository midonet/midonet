package com.midokura.midolman.state;

import java.io.IOException;
import java.net.InetAddress;
import java.util.HashSet;
import java.util.Random;
import java.util.UUID;

import org.apache.zookeeper.KeeperException.NoChildrenForEphemeralsException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

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
    public void testAddGetUpdateDeleteBridgePort() throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException, IOException,
            ClassNotFoundException {
        UUID portId = new UUID(rand.nextLong(), rand.nextLong());
        UUID bridgeId = new UUID(rand.nextLong(), rand.nextLong());
        PortDirectory.BridgePortConfig port = new PortDirectory.BridgePortConfig(
                bridgeId);
        portDir.addPort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfiguration(portId, null, null));
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
                portDir.getPortConfiguration(portId, null, null));
        MyWatcher watch = new MyWatcher();
        try {
            portDir.getPortConfiguration(portId, null, watch);
            Assert.fail("Shouldn't watch routes on a bridge port");
        } catch (IllegalArgumentException e) {
        }
        portDir.getPortConfiguration(portId, watch, null);
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
            portDir.getPortConfiguration(portId, null, null);
            Assert.fail("Should have thrown NoNodeException");
        } catch (NoNodeException e) {
        }
    }

    @Test
    public void testAddGetUpdateDeleteMaterializedRouterPort()
            throws NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException, IOException,
            ClassNotFoundException {
        UUID portId = new UUID(rand.nextLong(), rand.nextLong());
        UUID routerId = new UUID(rand.nextLong(), rand.nextLong());
        PortDirectory.MaterializedRouterPortConfig port = new PortDirectory.MaterializedRouterPortConfig(
                routerId, InetAddress.getByName("1.2.3.0"), 24,
                InetAddress.getByName("1.2.3.1"), new HashSet<Route>(),
                InetAddress.getByName("1.2.3.5"), 32, new HashSet<BGP>());
        portDir.addPort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfiguration(portId, null, null));
        UUID routerId1 = new UUID(rand.nextLong(), rand.nextLong());
        Assert.assertNotSame(routerId1, routerId);
        port.device_id = routerId1;
        port.localNetworkAddr = InetAddress.getByName("1.2.3.8");
        try {
            portDir.addPort(portId, port);
            Assert.fail("Should not be able to add same port twice");
        } catch (NodeExistsException e) {
        }
        portDir.updatePort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfiguration(portId, null, null));
        MyWatcher portWatch = new MyWatcher();
        MyWatcher routesWatch = new MyWatcher();
        // Register the watchers.
        portDir.getPortConfiguration(portId, portWatch, routesWatch);
        port.device_id = routerId;
        Assert.assertEquals(0, portWatch.numCalls);
        Assert.assertEquals(0, routesWatch.numCalls);
        portDir.updatePort(portId, port);
        Assert.assertEquals(1, portWatch.numCalls);
        Assert.assertEquals(0, routesWatch.numCalls);
        // Re-register the port watcher.
        portDir.getPortConfiguration(portId, portWatch, null);
        Route rt = new Route(InetAddress.getByName("0.0.0.0"), 0,
                InetAddress.getByName("1.2.3.15"), 32, Route.NextHop.PORT,
                portId, null, 5, "attrs");
        port.routes.add(rt);
        portDir.updatePort(portId, port);
        Assert.assertEquals(2, portWatch.numCalls);
        Assert.assertEquals(1, routesWatch.numCalls);
        // The watcher won't be called again if we don't re-register.
        port.routes.remove(rt);
        rt.dstNetworkLength = 24;
        port.routes.add(rt);
        port.networkLength = 16;
        portDir.updatePort(portId, port);
        Assert.assertEquals(2, portWatch.numCalls);
        Assert.assertEquals(1, routesWatch.numCalls);
        portDir.deletePort(portId);
        try {
            portDir.getPortConfiguration(portId, null, null);
            Assert.fail("Should have thrown NoNodeException");
        } catch (NoNodeException e) {
        }
    }

    @Test
    public void testAddGetUpdateDeleteLogicalRouterPort()
            throws NoNodeException, NodeExistsException,
            NoChildrenForEphemeralsException, IOException,
            ClassNotFoundException {
        UUID portId = new UUID(rand.nextLong(), rand.nextLong());
        UUID peerPortId = new UUID(rand.nextLong(), rand.nextLong());
        UUID routerId = new UUID(rand.nextLong(), rand.nextLong());
        PortDirectory.LogicalRouterPortConfig port = new PortDirectory.LogicalRouterPortConfig(
                routerId, InetAddress.getByName("1.2.3.0"), 24,
                InetAddress.getByName("1.2.3.1"), new HashSet<Route>(),
                peerPortId);
        portDir.addPort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfiguration(portId, null, null));
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
                portDir.getPortConfiguration(portId, null, null));
        MyWatcher portWatch = new MyWatcher();
        MyWatcher routesWatch = new MyWatcher();
        // Register the watchers.
        portDir.getPortConfiguration(portId, portWatch, routesWatch);
        port.device_id = routerId;
        port.peer_uuid = peerPortId;
        Assert.assertEquals(0, portWatch.numCalls);
        Assert.assertEquals(0, routesWatch.numCalls);
        portDir.updatePort(portId, port);
        Assert.assertEquals(1, portWatch.numCalls);
        Assert.assertEquals(0, routesWatch.numCalls);
        // Re-register the port watcher.
        portDir.getPortConfiguration(portId, portWatch, null);
        Route rt1 = new Route(InetAddress.getByName("0.0.0.0"), 0,
                InetAddress.getByName("1.2.3.15"), 32, Route.NextHop.PORT,
                portId, null, 5, "attrs");
        Route rt2 = new Route(InetAddress.getByName("0.0.0.0"), 0,
                InetAddress.getByName("1.2.3.0"), 24, Route.NextHop.PORT,
                portId, null, 5, "attrs");
        port.routes.add(rt1);
        port.routes.add(rt2);
        portDir.updatePort(portId, port);
        Assert.assertEquals(2, portWatch.numCalls);
        Assert.assertEquals(1, routesWatch.numCalls);
        // The watcher won't be called again if we don't re-register.
        port.routes.remove(rt1);
        rt1.attributes = "other attrs";
        rt1.nextHopGateway = InetAddress.getByName("1.2.3.254");
        port.routes.add(rt1);
        port.networkLength = 16;
        portDir.updatePort(portId, port);
        Assert.assertEquals(port,
                portDir.getPortConfiguration(portId, null, null));
        Assert.assertEquals(2, portWatch.numCalls);
        Assert.assertEquals(1, routesWatch.numCalls);
        portDir.deletePort(portId);
        try {
            portDir.getPortConfiguration(portId, null, null);
            Assert.fail("Should have thrown NoNodeException");
        } catch (NoNodeException e) {
        }
    }
}
