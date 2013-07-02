/*
 * Copyright 2011 Midokura Europe SARL
 */
package org.midonet.midolman.host.updater;

import java.net.InetAddress;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import akka.actor.Actor;
import akka.testkit.TestActorRef;
import akka.testkit.TestKit;
import com.google.inject.*;
import org.apache.commons.configuration.HierarchicalConfiguration;
import org.apache.zookeeper.CreateMode;
import org.hamcrest.beans.HasPropertyWithValue;
import org.junit.Before;
import org.junit.Test;
import org.midonet.midolman.guice.serialization.SerializationModule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.version.DataWriteVersion;
import org.midonet.midolman.version.guice.VersionModule;
import scala.collection.JavaConversions;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.collection.IsCollectionWithSize.hasSize;
import static org.hamcrest.collection.IsMapContaining.hasEntry;
import static org.hamcrest.core.IsEqual.equalTo;
import static org.hamcrest.core.IsNull.notNullValue;

import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.guice.MidolmanModule;
import org.midonet.midolman.guice.MockCacheModule;
import org.midonet.midolman.guice.actors.TestableMidolmanActorsModule;
import org.midonet.midolman.guice.InterfaceScannerModule;
import org.midonet.midolman.guice.MockMonitoringStoreModule;
import org.midonet.midolman.guice.cluster.ClusterClientModule;
import org.midonet.midolman.guice.config.MockConfigProviderModule;
import org.midonet.midolman.guice.datapath.MockDatapathModule;
import org.midonet.midolman.guice.reactor.ReactorModule;
import org.midonet.midolman.guice.zookeeper.MockZookeeperConnectionModule;
import org.midonet.midolman.host.guice.HostModule;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.midolman.host.state.HostDirectory;
import org.midonet.midolman.host.state.HostZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkPathManager;

import static org.midonet.midolman.host.state.HostDirectory.Interface;


public class DefaultInterfaceDataUpdaterTest {

    InterfaceDataUpdater updater;
    HostZkManager hostManager;
    Directory directory;
    ZkPathManager pathManager;

    UUID hostID;
    HostDirectory.Metadata metadata;

    @Before
    public void setUp() throws Exception {

        final Directory cleanDirectory = new MockDirectory();
        pathManager = new ZkPathManager("");
        cleanDirectory.add("/hosts", null, CreateMode.PERSISTENT);
        cleanDirectory.add(pathManager.getWriteVersionPath(),
                DataWriteVersion.CURRENT.getBytes(), CreateMode.PERSISTENT);

        final HierarchicalConfiguration configuration =
                new HierarchicalConfiguration();
        configuration.addNodes(ZookeeperConfig.GROUP_NAME,
                Arrays.asList(new HierarchicalConfiguration.Node
                        ("midolman_root_key", "")
        ));

        Injector injector = Guice.createInjector(
            new VersionModule(),
            new SerializationModule(),
            new MockConfigProviderModule(configuration),
            new MockDatapathModule(),
            new MockCacheModule(),
            new MockZookeeperConnectionModule(cleanDirectory),
            new HostModule(),
            new ReactorModule(),
            new MockMonitoringStoreModule(),
            new ClusterClientModule(),
            new TestableMidolmanActorsModule(
                JavaConversions.asScalaMap(new HashMap<String, TestKit>()),
                JavaConversions.asScalaMap(new HashMap<String, TestActorRef<Actor>>())),
            new MidolmanModule(),
            new InterfaceScannerModule());

        directory = cleanDirectory;
        updater = injector.getInstance(InterfaceDataUpdater.class);
        hostManager = injector.getInstance(HostZkManager.class);

        hostID = UUID.randomUUID();
        metadata = new HostDirectory.Metadata();

        metadata.setName("test");
        metadata.setAddresses(new InetAddress[]{});

        hostManager.createHost(hostID, new HostDirectory.Metadata());
    }

    @Test
    public void testNoInterfaces() throws Exception {
        Map<String, Interface> interfaceMap;

        interfaceMap = assertStoreDescriptions();
        assertThat("The number of interfaces is zero",
                   interfaceMap.entrySet(), hasSize(0));
    }

    @Test
    public void testOneInterface() throws Exception {
        InterfaceDescription description = new InterfaceDescription(
                "testInterface");
        assertStoreDescriptions(description);
    }

    @Test
    public void testUpdateInterfaceData() throws Exception {
        Map<String, Interface> interfaceMap;
        String testInterfaceName = "testInterface";

        InterfaceDescription description = new InterfaceDescription(
                testInterfaceName);
        description.setMac("11:11:11:11:11:11");

        interfaceMap = assertStoreDescriptions(description);

        HostDirectory.Interface hostInterface = interfaceMap.get(
                description.getName());

        assertThat("The interface object has the same name as a the one saved",
                   hostInterface.getMac(),
                   equalTo(description.getMac()));

        // update some data and validate that the returned values are properly
        // updated
        description.setMac("11:11:11:11:11:12");

        interfaceMap = assertStoreDescriptions(description);
        assertThat("The interface object has the same name as a the one saved",
                   interfaceMap.get(description.getName()).getMac(),
                   equalTo(description.getMac()));
    }

    @Test
    public void testRenameInterface() throws Exception {
        Map<String, HostDirectory.Interface> hostInterfaces;

        InterfaceDescription description = new InterfaceDescription(
                "test-before");
        description.setMac("11:11:11:11:11:11");

        hostInterfaces = assertStoreDescriptions(description);

        assertThat(
            "Exactly one interface should appear inside the datastore "
             +  "directory.", hostInterfaces.entrySet(), hasSize(1));


        Interface hostInterface = hostInterfaces.get(description.getName());
        assertThat("The new interface object should have the proper mac",
                   hostInterface.getMac(), equalTo(description.getMac()));

        // change the name
        description.setName("test-after");
        hostInterfaces = assertStoreDescriptions(description);
        Interface newHostInterface = hostInterfaces.get(description.getName());

        assertThat("The interface should have the same mac",
                   newHostInterface.getMac(), equalTo(description.getMac()));
    }

    @Test
    public void testTwoInterfaces() throws Exception {
        InterfaceDescription first = new InterfaceDescription("first");
        first.setMac("11:11:11:11:11:11");

        InterfaceDescription second = new InterfaceDescription("second");
        second.setMac("11:11:11:11:11:12");

        assertStoreDescriptions(first, second);

        // validate removal of an interface and adding of a new one
        second.setName("second-updated");
        assertStoreDescriptions(first, second);

        // validate removal of all interfaces
        assertStoreDescriptions();
    }

    @Test
    public void testNonUpdatedInterface() throws Exception {
        String name = "first";

        InterfaceDescription first = new InterfaceDescription(name);
        first.setMac("11:11:11:11:11:11");

        Map<String, Interface> boundInterfaces = assertStoreDescriptions(first);

        HostDirectory.Interface hostInterface = boundInterfaces.get(name);

        for ( int i = 0; i < 10; i++ ) {
            Map<String, Interface> newBoundInterfaces =
                    assertStoreDescriptions(first);

            assertThat(newBoundInterfaces.entrySet(),
                    hasSize(boundInterfaces.size()));
            assertThat(newBoundInterfaces,
                       hasEntry(equalTo(name),
                                HasPropertyWithValue.<Interface>hasProperty(
                                    "name", equalTo(hostInterface.getName()))
                       ));
        }

        assertStoreDescriptions(first);
    }

    /**
     * This method will take an array of descriptions, add them to the datastore
     * using the updater and after that will read the data directly from the
     * datastore, validate that all the entries have UUIDs and that the new
     * objects map properly to interface names.
     *
     * @param descriptions the descriptions that we want to store
     * @return a mapping from interface name to interface object.
     *
     * @throws StateAccessException when the datastore operations fail.
     */
    private Map<String, Interface> assertStoreDescriptions(
        InterfaceDescription... descriptions)
            throws StateAccessException, SerializationException {

        updater.updateInterfacesData(hostID, metadata, descriptions);

        Set<String> interfaces = hostManager.getInterfaces(hostID);

        assertThat("The datastore interfaces count should be correct one.",
                interfaces, hasSize(descriptions.length));

        Set<String> interfaceNames = new HashSet<String>();

        for (InterfaceDescription description : descriptions) {
            interfaceNames.add(description.getName());
        }

        Map<String, Interface> nameToHostInterfaceMap =
            new HashMap<String, Interface>();

        for (String interfaceName : interfaces) {
            Interface hostInterface =
                hostManager.getInterfaceData(hostID, interfaceName);

            assertThat("The interface object is not a null value",
                       hostInterface, notNullValue());

            assertThat("The interface object should have an known name",
                       interfaceNames, hasItem(hostInterface.getName()));

            interfaceNames.remove(hostInterface.getName());

            nameToHostInterfaceMap.put(hostInterface.getName(), hostInterface);
        }

        assertThat("No interfaces with unknown names should have been stored",
                   interfaceNames, hasSize(0));

        return nameToHostInterfaceMap;
    }
}
