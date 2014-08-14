/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.junit.Before;
import org.junit.Test;
import org.opendaylight.controller.sal.connection.ConnectionConstants;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.notation.OvsDBMap;
import org.opendaylight.ovsdb.lib.notation.OvsDBSet;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.internal.Table;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Port;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryService;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;

import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;

import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThat;

public class VtepDataClientImplTest {

    private static final String MOCK_DESCRIPTION = "DESC";
    private static final String MOCK_NAME = "NAME";
    private static final String PHYS_PORT_1 = "eth1";
    private static final String PHYS_PORT_2 = "eth2";
    private static final UUID PHYS_SWITCH_UUID = new UUID("vtep");

    private VtepDataClientImpl vtepDataClient = null;
    private IPv4Addr ip = IPv4Addr.apply("192.158.0.1");
    private IPv4Addr tunIp = IPv4Addr.apply("10.1.1.1");
    private int port = 6632;

    private Physical_Switch mockPhysicalSwitch = null;

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
        this.vtepDataClient = new VtepDataClientImpl(cfgSrv, cnxnSrv, invSrv);
    }

    private void successfulConnection(
        final Map<String, ConcurrentMap<String, Table<?>>> mockCache) {

        // The format of the node name matters to cheat the ODL stuff inside
        final Map<ConnectionConstants, String> params = new HashMap<>();
        params.put(ConnectionConstants.ADDRESS, ip.toString());
        params.put(ConnectionConstants.PORT, Integer.toString(port));

        // Basic expectations in all connections
        new Expectations() {{
            // init
            cnxnSrv.init();
            invSrv.init();
            cnxnSrv.setInventoryServiceInternal(invSrv);
            cfgSrv.setInventoryServiceInternal(invSrv);
            cfgSrv.setConnectionServiceInternal(cnxnSrv);
            cnxnSrv.connect("vtep", params); times = 1; result = node;
            cfgSrv.setDefaultNode(node);
            // Pretend that there is no response from the vtep yet
            cnxnSrv.getInventoryServiceInternal(); times = 1; result = invSrv;
            invSrv.getCache(node); times = 1; result = null;
            cnxnSrv.getInventoryServiceInternal(); times = 1; result = invSrv;
            invSrv.getCache(node); times = 1; result = null;
            // This one will return a cache
            cnxnSrv.getInventoryServiceInternal(); result = invSrv;
            invSrv.getCache(node); result = mockCache;
            // Now we verify that we're indeed ready
            cnxnSrv.getInventoryServiceInternal(); result = invSrv;
            invSrv.getCache(node); result = mockCache;
            // Now we load the cache
            cnxnSrv.getInventoryServiceInternal(); result = invSrv;
            invSrv.getCache(node); result = mockCache;
        }};

        this.vtepDataClient.connect(ip, port);

    }

    private Map<String, ConcurrentMap<String, Table<?>>> makeMockCache() {
        OvsDBSet<UUID> ports = new OvsDBSet<>();
        OvsDBSet<String> ips = new OvsDBSet<>();
        OvsDBSet<String> tunnelIps = new OvsDBSet<>();

        ips.add(ip.toString());
        tunnelIps.add(tunIp.toString());
        ports.add(new UUID(PHYS_PORT_1));
        ports.add(new UUID(PHYS_PORT_2));

        Physical_Switch ps = new Physical_Switch();
        ps.setDescription(MOCK_DESCRIPTION);
        ps.setName(MOCK_NAME);
        ps.setManagement_ips(ips);
        ps.setTunnel_ips(tunnelIps);
        ps.setPorts(ports);

        Physical_Port p1 = new Physical_Port();
        p1.setName(PHYS_PORT_1);
        p1.setVlan_bindings(new OvsDBMap<BigInteger, UUID>());
        p1.setVlan_stats(new OvsDBMap<BigInteger, UUID>());

        Physical_Port p2 = new Physical_Port();
        p2.setName(PHYS_PORT_2);
        p2.setVlan_bindings(new OvsDBMap<BigInteger, UUID>());
        p2.setVlan_stats(new OvsDBMap<BigInteger, UUID>());

        Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            new ConcurrentHashMap<>();

        ConcurrentMap<String, Table<?>> psRows = new ConcurrentHashMap<>();
        psRows.put(PHYS_SWITCH_UUID.toString(), ps);

        ConcurrentMap<String, Table<?>> portRows = new ConcurrentHashMap<>();
        portRows.put(PHYS_PORT_1, p1);
        portRows.put(PHYS_PORT_2, p2);

        mockCache.put(Physical_Switch.NAME.getName(), psRows);
        mockCache.put(Physical_Port.NAME.getName(), portRows);

        return mockCache;
    }

    /**
     * Connects, and sets up expectations for a single call to an operation
     * checking in the cache.
     */
    private Map<String, ConcurrentMap<String, Table<?>>>
    prepareNormalOperation() {
        final Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            this.makeMockCache();
        successfulConnection(mockCache);
        new NonStrictExpectations() {{
            cnxnSrv.getInventoryServiceInternal();
                minTimes = 1; result = invSrv;
            invSrv.getCache(node); minTimes = 1; result = mockCache;
        }};
        return mockCache;
    }

    @Test
    public void testConnectionDisconnection() {
        final Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            this.makeMockCache();

        // Go through a full connection process
        successfulConnection(mockCache);

        // Disconnect
        new Expectations() {{ cnxnSrv.disconnect(node); }};

        vtepDataClient.disconnect();
    }

    @Test
    public void testListPhysicalSwitches() {
        prepareNormalOperation();
        List<PhysicalSwitch> pss = vtepDataClient.listPhysicalSwitches();
        assertEquals(1, pss.size());
        assertEquals(MOCK_DESCRIPTION, pss.get(0).description);
        assertEquals(MOCK_NAME, pss.get(0).name);
        assertEquals(ip.toString(), pss.get(0).mgmtIps.iterator().next());
        assertEquals(tunIp.toString(), pss.get(0).tunnelIps.iterator().next());
        assertThat(pss.get(0).ports,
                   containsInAnyOrder(PHYS_PORT_1, PHYS_PORT_2));
    }

    @Test
    public void testListPhysicalPorts() {
        prepareNormalOperation();
        List<PhysicalPort> pps =
            vtepDataClient.listPhysicalPorts(PHYS_SWITCH_UUID);

        // only the port name is part of the identity, enough to compare
        assertThat(pps, containsInAnyOrder(new PhysicalPort("", PHYS_PORT_1),
                                           new PhysicalPort("", PHYS_PORT_2)));
    }

    private Status testAddLogicalSwitchAndReturn(final StatusWithUuid st) {
        final Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            this.makeMockCache();
        successfulConnection(mockCache);
        new Expectations() {{
            cfgSrv.vtepAddLogicalSwitch("ls", 10);
            times = 1;
            result = st;
        }};
        return vtepDataClient.addLogicalSwitch("ls", 10);
    }

    @Test
    public void testAddLogicalSwitch() {
        Status st = testAddLogicalSwitchAndReturn(
            new StatusWithUuid(StatusCode.SUCCESS, new UUID("uuid")));
        assertEquals(StatusCode.SUCCESS, st.getCode());
    }

    @Test
    public void testAddLogicalSwitchFailure() {
        Status st = testAddLogicalSwitchAndReturn(
            new StatusWithUuid(StatusCode.BADREQUEST));
        assertEquals(StatusCode.BADREQUEST, st.getCode());
    }

    @Test
    public void testBindVlan() {
        final Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            this.makeMockCache();
        successfulConnection(mockCache);

        final List<IPv4Addr> floodIps =
            Arrays.asList(IPv4Addr.apply("10.3.2.1"));
        new Expectations() {{
            cfgSrv.vtepBindVlan("ls", "port", 10, 100,
                                Arrays.asList("10.3.2.1"));
            times = 1;
            result = new Status(StatusCode.SUCCESS);
        }};
        Status s = vtepDataClient.bindVlan("ls", "port", 10, 100, floodIps);
        assertEquals(StatusCode.SUCCESS, s.getCode());
    }

    @Test
    public void testAddMcastMacRemoteNormalMac() {
        this.testAddMcastMacRemoteWithMac(
            VtepMAC.fromString("ff:ff:ff:ff:ff:ff"));
    }

    @Test
    public void testAddMcastMacRemoteUnknownDst() {
        this.testAddMcastMacRemoteWithMac(VtepMAC.UNKNOWN_DST);
    }

    private void testAddMcastMacRemoteWithMac(final VtepMAC mac) {
        final Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            this.makeMockCache();
        successfulConnection(mockCache);
        new Expectations() {{
            cfgSrv.vtepAddMcastMacRemote("ls", mac.toString(), "10.2.1.3");
            times = 1;
            result = new StatusWithUuid(StatusCode.SUCCESS, new UUID("hello"));
        }};
        Status st = vtepDataClient.addMcastMacRemote(
            "ls", mac, IPv4Addr.apply("10.2.1.3"));
        assertEquals(StatusCode.SUCCESS, st.getCode());
    }

    @Test
    public void testAddMcastUcastRemote() {
        final Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            this.makeMockCache();
        successfulConnection(mockCache);
        final String sMac = "aa:bb:cc:dd:ee:ff";
        final MAC mac = MAC.fromString(sMac);
        final String sVtepIp = "10.2.1.3";
        final IPv4Addr vtepIp = IPv4Addr.apply(sVtepIp);
        new Expectations() {{
            cfgSrv.vtepAddUcastMacRemote("ls", sMac, sVtepIp, null);
            times = 1;
            result = new StatusWithUuid(StatusCode.SUCCESS, new UUID("hello"));
        }};
        Status st = vtepDataClient.addUcastMacRemote("ls", mac, null, vtepIp);
        assertEquals(StatusCode.SUCCESS, st.getCode());
    }

    @Test
    public void testDelUcastMacRemote() {
        final Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            this.makeMockCache();
        successfulConnection(mockCache);
        final String mac = "aa:bb:cc:dd:ee:ff";
        new Expectations() {{
            cfgSrv.vtepDelUcastMacRemote("ls", mac);
            times = 1;
            result = new StatusWithUuid(StatusCode.SUCCESS, new UUID("hello"));
        }};
        Status st = vtepDataClient.delUcastMacRemote("ls", MAC.fromString(mac),
                                                     null);
        assertEquals(StatusCode.SUCCESS, st.getCode());
    }

    @Test
    public void testDeleteBindingNoPhysicalSwitch() {
        final Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            this.makeMockCache();
        successfulConnection(mockCache);

        // Remove the switch from the cache
        mockCache.remove(Physical_Switch.NAME.getName());

        // Now binding deletions will fail
        new NonStrictExpectations() {{
            cnxnSrv.getInventoryServiceInternal();
            minTimes = 1; result = invSrv;
            invSrv.getCache(node); minTimes = 1; result = mockCache;
        }};

        Status st = vtepDataClient.deleteBinding(PHYS_PORT_1, 10);
        assertEquals(StatusCode.NOTFOUND, st.getCode());
    }

    @Test // see MN-2545
    public void testDeleteBindingVtepDisconnected() {
        final Map<String, ConcurrentMap<String, Table<?>>> mockCache =
            this.makeMockCache();
        successfulConnection(mockCache);

        // Now return null table cache, pretending that we lost connection
        new Expectations() {{
            cnxnSrv.getInventoryServiceInternal();
            minTimes = 1; result = invSrv;
            invSrv.getCache(node); minTimes = 1; result = null;
        }};

        Status st = vtepDataClient.deleteBinding(PHYS_PORT_1, 10);
        assertEquals(StatusCode.NOSERVICE, st.getCode());
    }

    @Test
    public void testDeleteBinding() {
        prepareNormalOperation();
        new Expectations() {{
            cfgSrv.vtepDelBinding(MOCK_NAME, PHYS_PORT_1, 10);
            times = 1; result = new Status(StatusCode.SUCCESS);
        }};
        Status st = vtepDataClient.deleteBinding(PHYS_PORT_1, 10);
        assertEquals(StatusCode.SUCCESS, st.getCode());
    }

}
