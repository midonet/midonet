/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import com.google.inject.Guice;
import com.google.inject.Injector;
import com.google.inject.PrivateModule;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.opendaylight.controller.sal.connection.ConnectionConstants;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.message.NodeUpdateNotification;
import org.opendaylight.ovsdb.lib.notation.OvsDBMap;
import org.opendaylight.ovsdb.lib.notation.OvsDBSet;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.internal.Table;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Port;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.Connection;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryService;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.subjects.PublishSubject;

import mockit.Expectations;
import mockit.Mocked;

import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class VtepDataClientImplTest {

    private static final Logger log =
        LoggerFactory.getLogger(VtepDataClientImplTest.class);

    private static class TestModule extends PrivateModule {
        @Override
        public void configure() {
            requireBinding(ConfigurationService.class);
            requireBinding(ConnectionService.class);
            requireBinding(InventoryService.class);
        }
    }

    private class TestVtepDataClientImpl extends VtepDataClientImpl {
        public final Connection connection;
        private final Executor executor;

        public TestVtepDataClientImpl(VtepEndPoint endPoint,
                                      ConfigurationService configurationService,
                                      ConnectionService connectionService) {
            super(endPoint, configurationService, connectionService);
            connection = new Connection(connectionId.toString(), null);
            executor = Executors.newSingleThreadExecutor();
        }

        public void simulateConnect() {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    connectObservable.onNext(connection);
                }
            });
        }
        public void simulateDisconnect() {
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    disconnectObservable.onNext(connection);
                }
            });
        }
    }

    private static final int STRESS_COUNT = 8192;

    private static final VtepEndPoint END_POINT =
        VtepEndPoint.apply("10.9.9.9", 6632);
    private static final VtepEndPoint MOCK_END_POINT =
        VtepEndPoint.apply("192.168.1.1", 6632);
    private static final IPv4Addr TUNNEL_IP = IPv4Addr.fromString("10.0.0.1");

    private static final String PHYSICAL_PORT_0 = "port0";
    private static final String PHYSICAL_PORT_1 = "port1";

    private static final String MOCK_NAME = "vtep-name";
    private static final String MOCK_DESCRIPTION = "vtep-description";
    private static final UUID MOCK_PHYSICAL_SWITCH_ID = new UUID("ps");

    private final PublishSubject<Connection> connectObservable =
        PublishSubject.create();
    private final PublishSubject<Connection> disconnectObservable =
        PublishSubject.create();
    private final PublishSubject<NodeUpdateNotification> updatesObservable =
        PublishSubject.create();

    private VtepDataClientFactory provider;

    @Mocked
    private ConnectionService cnxnSrv;
    @Mocked
    private ConfigurationService cfgSrv;
    @Mocked
    private InventoryService invSrv;
    @Mocked
    private Node node;

    @Before
    public void setup() {
        Injector injector = Guice.createInjector(new TestModule());
        provider = injector.getInstance(VtepDataClientFactory.class);
    }

    @After
    public void cleanup() {
        provider.dispose();
    }

    private TestVtepDataClientImpl testConnect(
        final Map<String, ConcurrentMap<String, Table<?>>> cache,
        java.util.UUID user) throws VtepStateException {

        // Connection parameters.
        final Map<ConnectionConstants, String> params = new HashMap<>();
        params.put(ConnectionConstants.ADDRESS,
                   MOCK_END_POINT.mgmtIp.toString());
        params.put(ConnectionConstants.PORT,
                   Integer.toString(MOCK_END_POINT.mgmtPort));

        // Basic expectations in all connections
        new Expectations() {{
            // Observables setup
            cnxnSrv.connectedObservable(); times = 1;
            result = connectObservable;
            cnxnSrv.disconnectedObservable(); times = 1;
            result = disconnectObservable;
            cnxnSrv.updatesObservable(); times = 1;
            result = updatesObservable;
            // Connect
            cnxnSrv.connect(anyString, params); times = 1; result = node;
            // On connect
            cnxnSrv.getInventoryServiceInternal(); times = 1; result = invSrv;
            invSrv.getCache(node); times = 1; result = cache;
        }};


        TestVtepDataClientImpl client = new TestVtepDataClientImpl(
            MOCK_END_POINT, cfgSrv, cnxnSrv);
        client.connect(user);
        client.simulateConnect();
        client.awaitConnected();
        return client;
    }

    private void testDisconnect(TestVtepDataClientImpl client,
                                java.util.UUID user) throws VtepStateException {
        // Disconnect
        new Expectations() {{
            cnxnSrv.disconnect(node);
        }};

        client.disconnect(user, false);
        client.simulateDisconnect();
        client.awaitDisconnected();
    }

    private Map<String, ConcurrentMap<String, Table<?>>> createMockCache() {
        OvsDBSet<UUID> ports = new OvsDBSet<>();
        OvsDBSet<String> ips = new OvsDBSet<>();
        OvsDBSet<String> tunnelIps = new OvsDBSet<>();

        ips.add(MOCK_END_POINT.mgmtIp.toString());
        tunnelIps.add(TUNNEL_IP.toString());
        ports.add(new UUID(PHYSICAL_PORT_0));
        ports.add(new UUID(PHYSICAL_PORT_1));

        Physical_Switch ps = new Physical_Switch();
        ps.setDescription(MOCK_DESCRIPTION);
        ps.setName(MOCK_NAME);
        ps.setManagement_ips(ips);
        ps.setTunnel_ips(tunnelIps);
        ps.setPorts(ports);

        Physical_Port p1 = new Physical_Port();
        p1.setName(PHYSICAL_PORT_0);
        p1.setVlan_bindings(new OvsDBMap<BigInteger, UUID>());
        p1.setVlan_stats(new OvsDBMap<BigInteger, UUID>());

        Physical_Port p2 = new Physical_Port();
        p2.setName(PHYSICAL_PORT_1);
        p2.setVlan_bindings(new OvsDBMap<BigInteger, UUID>());
        p2.setVlan_stats(new OvsDBMap<BigInteger, UUID>());

        Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            new ConcurrentHashMap<>();

        ConcurrentMap<String, Table<?>> psRows = new ConcurrentHashMap<>();
        psRows.put(MOCK_PHYSICAL_SWITCH_ID.toString(), ps);

        ConcurrentMap<String, Table<?>> portRows = new ConcurrentHashMap<>();
        portRows.put(PHYSICAL_PORT_0, p1);
        portRows.put(PHYSICAL_PORT_1, p2);

        mockCache.put(Physical_Switch.NAME.getName(), psRows);
        mockCache.put(Physical_Port.NAME.getName(), portRows);

        return mockCache;
    }

    @Test
    public void testConnectionDisconnection() throws Exception {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();

        TestVtepDataClientImpl client = testConnect(cache, user);

        testDisconnect(client, user);
    }

    @Test
    public void testListPhysicalSwitches() throws Exception {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();

        TestVtepDataClientImpl client = testConnect(cache, user);

        new Expectations() {{
            cnxnSrv.getInventoryServiceInternal(); times = 1; result = invSrv;
            invSrv.getCache(node); times = 1; result = cache;
        }};

        List<PhysicalSwitch> pss = client.listPhysicalSwitches();
        assertEquals(1, pss.size());
        assertEquals(MOCK_DESCRIPTION, pss.get(0).description);
        assertEquals(MOCK_NAME, pss.get(0).name);
        assertEquals(MOCK_END_POINT.mgmtIp.toString(),
                     pss.get(0).mgmtIps.iterator().next());
        assertEquals(TUNNEL_IP.toString(),
                     pss.get(0).tunnelIps.iterator().next());
        assertThat(pss.get(0).ports,
                   containsInAnyOrder(PHYSICAL_PORT_0, PHYSICAL_PORT_1));

        testDisconnect(client, user);
    }

    @Test
    public void testListPhysicalPorts() throws Exception {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();

        TestVtepDataClientImpl client = testConnect(cache, user);

        new Expectations() {{
            cnxnSrv.getInventoryServiceInternal(); times = 1; result = invSrv;
            invSrv.getCache(node); times = 1; result = cache;
            cnxnSrv.getInventoryServiceInternal(); times = 1; result = invSrv;
            invSrv.getCache(node); times = 1; result = cache;
        }};

        List<PhysicalPort> pps =
            client.listPhysicalPorts(MOCK_PHYSICAL_SWITCH_ID);

        // Only the port name is part of the identity, enough to compare
        assertThat(pps, containsInAnyOrder(
            new PhysicalPort("", PHYSICAL_PORT_0),
            new PhysicalPort("", PHYSICAL_PORT_1)));

        testDisconnect(client, user);
    }

    @Test
    public void testAddLogicalSwitchSuccess() throws Exception {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();

        TestVtepDataClientImpl client = testConnect(cache, user);

        new Expectations() {{
            cfgSrv.vtepAddLogicalSwitch(node, "ls", 10); times = 1;
            result = new StatusWithUuid(StatusCode.SUCCESS, new UUID("uuid"));
        }};

        StatusWithUuid status = client.addLogicalSwitch("ls", 10);
        assertEquals(StatusCode.SUCCESS, status.getCode());

        testDisconnect(client, user);
    }

    @Test
    public void testAddLogicalSwitchFailure() throws Exception {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();

        TestVtepDataClientImpl client = testConnect(cache, user);

        new Expectations() {{
            cfgSrv.vtepAddLogicalSwitch(node, "ls", 10); times = 1;
            result = new StatusWithUuid(StatusCode.BADREQUEST,
                                        new UUID("uuid"));
        }};

        StatusWithUuid status = client.addLogicalSwitch("ls", 10);
        assertEquals(StatusCode.BADREQUEST, status.getCode());

        testDisconnect(client, user);
    }

    @Test
    public void testBindVlan() throws Exception {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();

        TestVtepDataClientImpl client = testConnect(cache, user);

        new Expectations() {{
            cfgSrv.vtepBindVlan(node, "ls", "port", (short) 10, 100,
                                Arrays.asList("10.3.2.1")); times = 1;
            result = new Status(StatusCode.SUCCESS);
        }};

        Status status = client.bindVlan(
            "ls", "port", (short) 10, 100,
            Arrays.asList(IPv4Addr.apply("10.3.2.1")));
        assertEquals(StatusCode.SUCCESS, status.getCode());

        testDisconnect(client, user);
    }

    private void testAddMcastMacRemote(final VtepMAC mac, final IPv4Addr ip)
        throws VtepStateException {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();

        TestVtepDataClientImpl client = testConnect(cache, user);

        new Expectations() {{
            cfgSrv.vtepAddMcastMacRemote(node, "ls", mac.toString(),
                                         ip.toString()); times = 1;
            result = new StatusWithUuid(StatusCode.SUCCESS, new UUID("uuid"));
        }};

        Status status = client.addMcastMacRemote("ls", mac, ip);
        assertEquals(StatusCode.SUCCESS, status.getCode());

        testDisconnect(client, user);
    }

    @Test
    public void testAddMcastMacRemoteNormalMac() throws Exception {
        testAddMcastMacRemote(VtepMAC.fromString("ff:ff:ff:ff:ff:ff"),
                              IPv4Addr.apply("10.2.1.3"));
    }

    @Test
    public void testAddMcastMacRemoteUnknownDst() throws Exception {
        testAddMcastMacRemote(VtepMAC.UNKNOWN_DST, IPv4Addr.apply("10.2.1.3"));
    }

    private void testAddUcastMacRemote(final MAC mac, final IPv4Addr macIp,
                                       final IPv4Addr vtepIp)
        throws VtepStateException {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();

        TestVtepDataClientImpl client = testConnect(cache, user);

        new Expectations() {{
            cfgSrv.vtepAddUcastMacRemote(node, "ls", mac.toString(),
                                         vtepIp.toString(),
                                         null != macIp ? macIp.toString() : null
            );
            times = 1;
            result = new StatusWithUuid(StatusCode.SUCCESS, new UUID("uuid"));
        }};

        Status status = client.addUcastMacRemote(
            "ls", mac, macIp, vtepIp);
        assertEquals(StatusCode.SUCCESS, status.getCode());

        testDisconnect(client, user);
    }

    @Test
    public void testAddUcastMacRemote() throws Exception {
        testAddUcastMacRemote(MAC.fromString("aa:bb:cc:dd:ee:ff"),
                              IPv4Addr.apply("10.0.2.1"),
                              IPv4Addr.apply("10.0.3.3"));
    }

    @Test
    public void testAddMcastUcastRemoteNullIp() throws Exception {
        testAddUcastMacRemote(MAC.fromString("aa:bb:cc:dd:ee:ff"),
                              null,
                              IPv4Addr.apply("10.0.3.3"));
    }

    @Test
    public void testDeleteUcastMacRemote() throws Exception {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();
        final MAC mac = MAC.fromString("aa:bb:cc:dd:ee:ff");
        final IPv4Addr ip = IPv4Addr.apply("10.0.3.3");

        TestVtepDataClientImpl client = testConnect(cache, user);

        new Expectations() {{
            cfgSrv.vtepDelUcastMacRemote(node, "ls", mac.toString(),
                                         ip.toString());
            times = 1;
            result = new StatusWithUuid(StatusCode.SUCCESS, new UUID("uuid"));
        }};

        Status status = client.deleteUcastMacRemote("ls", mac, ip);
        assertEquals(StatusCode.SUCCESS, status.getCode());

        testDisconnect(client, user);
    }

    @Test
    public void testDeleteAllUcastMacRemote() throws Exception {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();
        final MAC mac = MAC.fromString("aa:bb:cc:dd:ee:ff");

        TestVtepDataClientImpl client = testConnect(cache, user);

        new Expectations() {{
            cfgSrv.vtepDelUcastMacRemote(node, "ls", mac.toString());
            times = 1;
            result = new StatusWithUuid(StatusCode.SUCCESS, new UUID("uuid"));
        }};

        Status status = client.deleteAllUcastMacRemote("ls", mac);
        assertEquals(StatusCode.SUCCESS, status.getCode());

        testDisconnect(client, user);
    }

    @Test
    public void testDeleteBinding() throws Exception {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();

        TestVtepDataClientImpl client = testConnect(cache, user);

        new Expectations() {{
            cfgSrv.vtepDelBinding(node, MOCK_PHYSICAL_SWITCH_ID,
                                  PHYSICAL_PORT_0, (short)10);
            times = 1;
            result = new StatusWithUuid(StatusCode.SUCCESS, new UUID("uuid"));
        }};

        Status status = client.deleteBinding(PHYSICAL_PORT_0, (short)10);
        assertEquals(StatusCode.SUCCESS, status.getCode());

        testDisconnect(client, user);
    }

    @Test
    public void testDeleteBindingVtepDisconnected() throws Exception {
        final Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.createMockCache();
        final java.util.UUID user = java.util.UUID.randomUUID();

        TestVtepDataClientImpl client = testConnect(cache, user);

        testDisconnect(client, user);

        new Expectations() {{
            cfgSrv.vtepDelBinding(node, MOCK_PHYSICAL_SWITCH_ID,
                                  PHYSICAL_PORT_0, (short)10);
            times = 0;
        }};

        Status status = client.deleteBinding(PHYSICAL_PORT_0, (short)10);
        assertEquals(StatusCode.NOSERVICE, status.getCode());
    }

    /**
     * Tests the connection lifecycle without using lazy disconnect.
     */
    @Ignore @Test
    public void testConnectionLifecycleWithoutWait() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        assertTrue(VtepDataClient.State.CONNECTED == client.getState());

        client.disconnect(owner, false);

        assertTrue(VtepDataClient.State.DISPOSED == client.getState());

        client.awaitDisconnected();

        assertTrue(VtepDataClient.State.DISPOSED == client.getState());
    }

    /**
     * Tests the connection lifecycle using lazy disconnect.
     */
    @Ignore @Test
    public void testConnectionLifecycleWithLazyDisconnect() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        assertTrue(VtepDataClient.State.CONNECTED == client.getState());

        client.disconnect(owner, true);

        client.awaitDisconnected();

        assertTrue(VtepDataClient.State.DISPOSED == client.getState());
    }

    @Ignore @Test
    public void testMultipleConnections() throws Exception {
        java.util.UUID owner1 = java.util.UUID.randomUUID();
        java.util.UUID owner2 = java.util.UUID.randomUUID();

        VtepDataClient client1 = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner1);
        VtepDataClient client2 = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner2);

        client1.awaitConnected();
        client2.awaitConnected();

        client1.disconnect(owner1, false);
        client2.disconnect(owner2, false);

        client1.awaitDisconnected();
        client2.awaitDisconnected();
    }

    @Ignore @Test
    public void testMultipleConnectionsLazyDisconnect() throws Exception {
        java.util.UUID owner1 = java.util.UUID.randomUUID();
        java.util.UUID owner2 = java.util.UUID.randomUUID();

        VtepDataClient client1 = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner1);
        VtepDataClient client2 = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner2);

        client1.awaitConnected();
        client2.awaitConnected();

        client1.disconnect(owner1, false);
        client1.disconnect(owner1, true);

        client1.awaitDisconnected();
        client2.awaitDisconnected();
    }

    @Ignore @Test
    public void testConnectionDisposal() throws Exception {
        java.util.UUID owner1 = java.util.UUID.randomUUID();
        java.util.UUID owner2 = java.util.UUID.randomUUID();

        VtepDataClient client1 = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner1);
        VtepDataClient client2 = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner2);

        client1.awaitConnected();
        client2.awaitConnected();

        provider.dispose();

        client1.awaitDisconnected();
        client2.awaitDisconnected();
    }

    @Ignore @Test
    public void testConnectionStress() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        for (int index = 0; index < STRESS_COUNT; index++) {
            VtepDataClient client = provider.connect(
                END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

            log.info("TEST {}", index);

            client.awaitConnected();

            assertTrue(VtepDataClient.State.CONNECTED == client.getState());

            client.disconnect(owner, false);

            client.awaitDisconnected();

            assertTrue(VtepDataClient.State.DISPOSED == client.getState());
        }
    }

    /**
     * Tests connection establishment, when the VTEP is disconnected.
     */
    @Ignore @Test
    public void testConnectionFailure() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        try {
            client.awaitConnected();
            assertTrue(false);
        } catch (VtepStateException e) {
            assertTrue(true);
        }

        client.awaitState(VtepDataClient.State.CONNECTED);

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testVtepListPhysicalSwitchesAndPorts() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        Collection<PhysicalSwitch> psList = client.listPhysicalSwitches();
        for (PhysicalSwitch ps : psList) {
            log.info("Physical switch: {}", ps);
            Collection<PhysicalPort> ppList = client.listPhysicalPorts(ps.uuid);
            log.info("Physical ports: {}", ppList);
        }

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testVtepListLogicalSwitches() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        Collection<LogicalSwitch> lsList = client.listLogicalSwitches();
        for (LogicalSwitch ls : lsList) {
            log.info("Logical switch: {}", ls);
        }

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testVtepListUcastMacsLocal() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        Collection<UcastMac> macs = client.listUcastMacsLocal();
        log.info("Ucast local MACs: {}", macs);

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testVtepListMcastMacLocal() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        Collection<McastMac> macs = client.listMcastMacsLocal();
        log.info("Mcast local MACs: {}", macs);

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testVtepListUcastMacsRemote() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        Collection<UcastMac> macs = client.listUcastMacsRemote();
        log.info("Ucast remote MACs: {}", macs);

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testVtepListMcastMacRemote() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        Collection<McastMac> macs = client.listMcastMacsRemote();
        log.info("Mcast remote MACs: {}", macs);

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testVtepGetAddDeleteLogicalSwitch() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        LogicalSwitch ls = addLogicalSwitch(client);

        Collection<LogicalSwitch> lsList = client.listLogicalSwitches();
        assertTrue(lsList.contains(ls));

        deleteLogicalSwitch(client, ls);

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testClearLogicalSwitches() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        Collection<LogicalSwitch> lsList = client.listLogicalSwitches();
        log.info("Logical switches : {}", lsList);

        for (LogicalSwitch ls : lsList) {
            if (ls.name.startsWith("mn-")) {
                log.info("Deleting logical switch {}", ls.name);
                Status status = client.deleteLogicalSwitch(ls.name);
                assertTrue(status.isSuccess());
            }
        }

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testVtepAddDeleteBinding() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        LogicalSwitch ls = addLogicalSwitch(client);
        assertNotNull(ls);

        List<Pair<String, Short>> bindings = new ArrayList<>();
        bindings.add(new ImmutablePair<>(PHYSICAL_PORT_0, (short)100));
        bindings.add(new ImmutablePair<>(PHYSICAL_PORT_0, (short) 101));
        bindings.add(new ImmutablePair<>(PHYSICAL_PORT_0, (short)102));

        Status status = client.addBindings(ls.uuid, bindings);
        assertTrue(status.isSuccess());

        Collection<Pair<org.opendaylight.ovsdb.lib.notation.UUID, Short>> bList
            = client.listPortVlanBindings(ls.uuid);
        log.info("Bindings for logical switch {} : {}", ls.uuid, bList);

        status = client.deleteBinding(PHYSICAL_PORT_0, (short)100);
        assertTrue(status.isSuccess());
        status = client.deleteBinding(PHYSICAL_PORT_0, (short)101);
        assertTrue(status.isSuccess());
        status = client.deleteBinding(PHYSICAL_PORT_0, (short) 102);
        assertTrue(status.isSuccess());

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testVtepAddDeleteUcastMacRemote() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        MAC mac = MAC.fromString("00:11:22:33:44:55");
        IPv4Addr macIp = IPv4Addr.fromString("192.168.1.1");
        IPv4Addr tunnelIp = IPv4Addr.fromString("10.0.0.1");

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        LogicalSwitch ls = addLogicalSwitch(client);
        assertNotNull(ls);

        Status status = client.addUcastMacRemote(ls.name, mac, macIp, tunnelIp);
        assertTrue(status.isSuccess());

        status = client.deleteAllUcastMacRemote(ls.name, mac);
        assertTrue(status.isSuccess());

        status = client.deleteLogicalSwitch(ls.name);
        assertTrue(status.isSuccess());

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    @Ignore @Test
    public void testVtepAddDeleteMcastMacRemote() throws Exception {
        java.util.UUID owner = java.util.UUID.randomUUID();

        VtepMAC mac = VtepMAC.UNKNOWN_DST;
        IPv4Addr tunnelIp = IPv4Addr.fromString("10.0.0.1");

        VtepDataClient client = provider.connect(
            END_POINT.mgmtIp, END_POINT.mgmtPort, owner);

        client.awaitConnected();

        LogicalSwitch ls = addLogicalSwitch(client);
        assertNotNull(ls);

        Status status = client.addMcastMacRemote(ls.name, mac, tunnelIp);
        assertTrue(status.isSuccess());

        status = client.deleteAllMcastMacRemote(ls.name, mac);
        assertTrue(status.isSuccess());

        status = client.deleteLogicalSwitch(ls.name);
        assertTrue(status.isSuccess());

        client.disconnect(owner, false);

        client.awaitDisconnected();
    }

    public static LogicalSwitch addLogicalSwitch(VtepDataClient client)
        throws VtepNotConnectedException {
        StatusWithUuid status = client.addLogicalSwitch(
            "mn-" + java.util.UUID.randomUUID(), 1);
        assertTrue(status.isSuccess());
        log.info("Add logical switch: {}", status.getUuid());

        LogicalSwitch ls = client.getLogicalSwitch(status.getUuid());
        log.info("Logical switch: {}", ls);
        assertNotNull(ls);
        assertEquals(ls.uuid, status.getUuid());

        return ls;
    }

    public static void deleteLogicalSwitch(VtepDataClient client,
                                           LogicalSwitch ls) {
        Status status = client.deleteLogicalSwitch(ls.name);
        assertTrue(status.isSuccess());
    }

}
