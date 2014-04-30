/*
* Copyright 2014 Midokura Europe SARL
*/
package org.midonet.brain.southbound.midonet;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.Module;
import com.google.inject.PrivateModule;

import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.brain.guice.BrainModule;
import org.midonet.brain.southbound.midonet.handlers.MacPortUpdateHandler;
import org.midonet.brain.southbound.vtep.events.MacPortUpdate;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.midolman.Setup;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.MockCacheModule;
import org.midonet.midolman.guice.MockMonitoringStoreModule;
import org.midonet.midolman.guice.cluster.ClusterClientModule;
import org.midonet.midolman.guice.config.MockConfigProviderModule;
import org.midonet.midolman.guice.config.TypedConfigModule;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.version.guice.VersionModule;
import org.midonet.packets.MAC;

import static org.junit.Assert.*;

/**
 * A mock MacPortUpdateHandler, which creates an update string from a mac-port
 * update, and stores it in a list.
 */
class MockMacPortUpdateHandler implements MacPortUpdateHandler {
    private List<String> notifications = null;

    public MockMacPortUpdateHandler() {
        this.notifications = new ArrayList<String>();
    }

    static String makeNotificationStr(
            UUID bridgeId, MAC key, UUID oldVal, UUID newVal) {
        return bridgeId + "," + key + "," + oldVal + "," + newVal;
    }

    static String makeNotificationStr(MacPortUpdate update) {
        return makeNotificationStr(update.bridgeId, update.mac,
               update.oldPortId, update.newPortId);
    }

    public List<String> getNotifications() {
        return this.notifications;
    }

    @Override
    public void handleMacPortUpdate(MacPortUpdate update) {
        String macPortUpdate = makeNotificationStr(update);
        System.out.println(macPortUpdate);
        notifications.add(macPortUpdate);
    }
}

/**
 * Sets up MockMacPortUpdateHandler as a MacPortUpdateHandler implementation,
 * and exposes both MacPortUpdateHandler and MockBridgeMacPortHandler. The
 * latter provides access to the list of generated update strings.
 */
class MockMacPortUpdateHandlerModule extends PrivateModule {
    @Override
    protected void configure() {
        bind(MacPortUpdateHandler.class)
                .to(MockMacPortUpdateHandler.class).asEagerSingleton();
        expose(MacPortUpdateHandler.class);
        bind(MockMacPortUpdateHandler.class).asEagerSingleton();
        expose(MockMacPortUpdateHandler.class);
    }
}

public class MidoVtepTest {
    private final static Logger log =
            LoggerFactory .getLogger(MidoVtepTest.class);

    private MidoVtep midoVtep = null;

    private Injector injector = null;
    private Directory directory = null;
    private DataClient dataClient = null;
    String zkRoot = "/test/v3/midolman";
    private List<String> notifications;

    private MAC macAddress1 = MAC.fromString("aa:bb:cc:dd:ee:01");
    private MAC macAddress2 = MAC.fromString("aa:bb:cc:dd:ee:02");
    private MAC macAddress3 = MAC.fromString("aa:bb:cc:dd:ee:03");

    @Before
    public void setUp() throws Exception {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        fillConfig(config);
        this.injector = Guice.createInjector(getModules(config));

        this.dataClient = injector.getInstance(DataClient.class);
        this.directory = injector.getInstance(Directory.class);
        this.midoVtep = injector.getInstance(MidoVtep.class);
        this.notifications =
                injector.getInstance(MockMacPortUpdateHandler.class)
                .getNotifications();

        this.setUpZkDirectory();
    }

    protected void setUpZkDirectory() throws InterruptedException, KeeperException {
        String[] nodes = zkRoot.split("/");
        String path = "/";

        for (String node : nodes) {
            if (!node.isEmpty()) {
                this.directory.add(path + node, null, CreateMode.PERSISTENT);
                path += node;
                path += "/";
            }
        }
        Setup.ensureZkDirectoryStructureExists(this.directory, zkRoot);
    }

    private void fillConfig(HierarchicalConfiguration config) {
        config.setProperty("midolman.midolman_root_key", this.zkRoot);
        config.setProperty("midolman.enable_monitoring", "false");
        config.setProperty("cassandra.servers", "localhost:9171");

        config.addNodes(ZookeeperConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node
                        ("midolman_root_key", zkRoot)));
    }

    private List<Module> getModules(HierarchicalConfiguration config) {
        List<Module> modules = new ArrayList<Module>();
        modules.add(new BrainModule());  // For MidolmanConfig
        // For MidoBridgeMacPortUpdateHandler
        modules.add(new MockMacPortUpdateHandlerModule());
        modules.add(new VersionModule());  // For Comparator
        modules.add(new SerializationModule());  // For Serializer
        modules.add(
                new TypedConfigModule<MidolmanConfig>(MidolmanConfig.class));
        modules.add(new MockConfigProviderModule(config));  // For ConfigProvider
        modules.add(new MockCacheModule());  // For cache
        // Directory and Reactor
        modules.add(new MockZookeeperConnectionModule());
        modules.add(new MockMonitoringStoreModule());  // For Store
        modules.add(new ClusterClientModule());
        return modules;
    }

    @Test
    public void testMidoVtepSubscribesToABridge() throws Exception {
        // Create a bridge and a port before starting MidoVtep.
        Bridge bridge = new Bridge();
        bridge.setName("Mock bridge");
        UUID bridgeId = dataClient.bridgesCreate(bridge);
        log.debug("Created a bridge: {}", bridgeId);

        Port<?, ?> port = new BridgePort();
        port.setDeviceId(bridgeId);
        UUID bridgePortId = dataClient.portsCreate(port);
        log.debug("Created a port: {}", bridgePortId);

        dataClient.bridgeAddMacPort(bridgeId,
                                    Bridge.UNTAGGED_VLAN_ID,
                                    macAddress1,
                                    bridgePortId);
        log.debug("Added a Mac port pair: " + macAddress1 + "/" + bridgePortId);

        midoVtep.start();

        // MidoVtep has a local copy of the bridge's mac table.
        Set<UUID> midoBridges = midoVtep.getMacTableOwnerIds();
        assertEquals(1, midoBridges.size());
        assertTrue("Bridge ID is in the set.", midoBridges.contains(bridgeId));
        assertTrue(midoVtep.containsMacEntry(
                bridgeId, macAddress1, bridgePortId));
    }

    @Test
    public void testMidoVtepSubscribesToTwoBridges() throws Exception {
        // Create two bridges and one bridge port per each.
        Bridge bridge = new Bridge();
        Bridge bridge2 = new Bridge();
        bridge.setName("Mock bridge1");
        bridge2.setName("Mock bridge2");
        UUID bridgeId = dataClient.bridgesCreate(bridge);
        UUID bridgeId2 = dataClient.bridgesCreate(bridge2);

        Port<?, ?> port = new BridgePort();
        Port<?, ?> port2 = new BridgePort();
        port.setDeviceId(bridgeId);
        port2.setDeviceId(bridgeId2);
        UUID bridgePortId = dataClient.portsCreate(port);
        UUID bridgePortId2 = dataClient.portsCreate(port2);
        dataClient.bridgeAddMacPort(bridgeId,
                                    Bridge.UNTAGGED_VLAN_ID,
                                    macAddress1,
                                    bridgePortId);
        log.debug("Added a Mac port pair: {}/{}", macAddress1, bridgePortId);
        dataClient.bridgeAddMacPort(bridgeId2,
                                    Bridge.UNTAGGED_VLAN_ID,
                                    macAddress2,
                                    bridgePortId2);
        log.debug("Added a Mac port pair: {}/{}", macAddress2, bridgePortId2);

        this.midoVtep.start();

        // MidoVtep has local copies of those bridges' Mac tables.
        Set<UUID> midoBridges = midoVtep.getMacTableOwnerIds();
        assertEquals(2, midoBridges.size());
        assertTrue("Bridge ID is in the set.", midoBridges.contains(bridgeId));
        assertTrue("Bridge ID is in the set.", midoBridges.contains(bridgeId2));
        assertTrue(midoVtep.containsMacEntry(
                bridgeId, macAddress1, bridgePortId));
        assertTrue(midoVtep.containsMacEntry(
                bridgeId2, macAddress2, bridgePortId2));
        assertFalse(midoVtep.containsMacEntry(
                bridgeId, macAddress3, bridgePortId));

        // The old Mac-port mapping is deleted and a new one is added.
        dataClient.bridgeDeleteMacPort(bridgeId,
                                    Bridge.UNTAGGED_VLAN_ID,
                                    macAddress1,
                                    bridgePortId);
        dataClient.bridgeAddMacPort(bridgeId,
                                    Bridge.UNTAGGED_VLAN_ID,
                                    macAddress3,
                                    bridgePortId);
        log.debug("Added a Mac port pair: {}/{}", macAddress3, bridgePortId);
        assertFalse(midoVtep.containsMacEntry(
                bridgeId, macAddress1, bridgePortId));
        assertTrue(midoVtep.containsMacEntry(
                bridgeId2, macAddress2, bridgePortId2));
        assertTrue(midoVtep.containsMacEntry(
                bridgeId, macAddress3, bridgePortId));
    }

    @Test
    public void testMidoVtepUpdatesBridgeMacTable() throws Exception {
        // Create a bridge and a port before starting MidoBridgesProxy.
        Bridge bridge = new Bridge();
        bridge.setName("Mock bridge");
        UUID bridgeId = dataClient.bridgesCreate(bridge);
        log.debug("Created a bridge: {}", bridgeId);

        Port<?, ?> port = new BridgePort();
        port.setDeviceId(bridgeId);
        UUID bridgePortId = dataClient.portsCreate(port);
        log.debug("Created a port: {}", bridgePortId);

        assertFalse("The Mac-port entry is not in the Bridge.",
                    dataClient.bridgeHasMacPort(bridgeId,
                                                Bridge.UNTAGGED_VLAN_ID,
                                                macAddress1,
                                                bridgePortId));

        midoVtep.start();
        assertTrue("MidoBridgesProxy can add a Mac entry.",
                   midoVtep.addMacEntry(bridgeId, macAddress1, bridgePortId));
        assertTrue("A new mac-port entry is added to the backend bridge.",
                   dataClient.bridgeHasMacPort(bridgeId,
                                               Bridge.UNTAGGED_VLAN_ID,
                                               macAddress1,
                                               bridgePortId));
    }

    @Test
    public void testMidoBridgesProxyNotifiedOnMacPortUpdate() throws Exception {
        // Create two bridges and one bridge port per each.
        Bridge bridge = new Bridge();
        bridge.setName("Mock bridge1");
        UUID bridgeId = dataClient.bridgesCreate(bridge);

        Port<?, ?> port = new BridgePort();
        port.setDeviceId(bridgeId);
        UUID bridgePortId = dataClient.portsCreate(port);
        dataClient.bridgeAddMacPort(bridgeId,
                                    Bridge.UNTAGGED_VLAN_ID,
                                    macAddress1,
                                    bridgePortId);

        this.midoVtep.start();

        // MidoVtep has local copies of those bridges' Mac tables.
        assertFalse(midoVtep.containsMacEntry(
                bridgeId, macAddress3, bridgePortId));

        // The old Mac-port mapping is deleted and a new one is added.
        dataClient.bridgeDeleteMacPort(
                bridgeId, Bridge.UNTAGGED_VLAN_ID, macAddress1, bridgePortId);
        log.debug("Deleted a Mac port pair: {}/{}", macAddress1, bridgePortId);
        dataClient.bridgeAddMacPort(bridgeId,
                                    Bridge.UNTAGGED_VLAN_ID,
                                    macAddress3,
                                    bridgePortId);
        log.debug("Added a Mac port pair: {}/{}", macAddress3, bridgePortId);
        assertEquals("MidoBridgesProxy receives Mac-entry update notifications",
                     3, this.notifications.size());
        assertEquals("1st notification",
                     MockMacPortUpdateHandler.makeNotificationStr(
                             bridgeId, macAddress1, null, bridgePortId),
                     this.notifications.get(0));
        assertEquals("2nd notification",
                     MockMacPortUpdateHandler.makeNotificationStr(
                             bridgeId, macAddress1, bridgePortId, null),
                     this.notifications.get(1));
        assertEquals("3rd notification",
                     MockMacPortUpdateHandler.makeNotificationStr(
                             bridgeId, macAddress3, null, bridgePortId),
                     this.notifications.get(2));
    }
}
