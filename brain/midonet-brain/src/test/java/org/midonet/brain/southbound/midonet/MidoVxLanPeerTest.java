/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.brain.southbound.midonet;

import java.util.Set;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Before;
import org.junit.Test;

import rx.Observable;

import org.midonet.brain.BrainTestUtils;
import org.midonet.brain.test.RxTestUtils;
import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.southbound.vtep.VtepConstants;
import org.midonet.brain.southbound.vtep.VtepMAC;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.Port;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.host.Host;
import org.midonet.cluster.data.ports.BridgePort;
import org.midonet.cluster.data.ports.VxLanPort;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.Ip4ToMacReplicatedMap;
import org.midonet.midolman.state.MacPortMap;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

public class MidoVxLanPeerTest {

    // Class under test
    private MidoVxLanPeer midoVxLanPeer = null;

    private DataClient dataClient = null;

    private VtepMAC vMac1 = VtepMAC.fromString("aa:bb:cc:dd:ee:01");
    private VtepMAC vMac2 = VtepMAC.fromString("aa:bb:cc:dd:ee:02");
    private VtepMAC vMac3 = VtepMAC.fromString("aa:bb:cc:dd:ee:03");

    private MAC mac1 = vMac1.IEEE802();
    private MAC mac2 = vMac2.IEEE802();
    private MAC mac3 = vMac3.IEEE802();

    private IPv4Addr macIp1 = IPv4Addr.fromString("10.0.5.1");
    private IPv4Addr macIp2 = IPv4Addr.fromString("10.0.5.2");
    private IPv4Addr macIp3 = IPv4Addr.fromString("10.0.5.3");

    private final IPv4Addr tzHostIp = IPv4Addr.apply("192.168.1.200");
    private final int bridgePortVNI = 42;
    private final int bridgePortVNIAlt = 44;
    private final String bridgePortIface = "eth0";

    private final IPv4Addr tunnelZoneVtepIP = IPv4Addr.apply("192.168.1.100");
    private final IPv4Addr tunnelZoneVtepIPAlt = IPv4Addr.apply("192.168.1.110");
    private final int vtepMgmtPort = 6632;
    private final int vtepMgmtPortAlt = 6632;

    private UUID hostId = null;

    @Before
    public void setUp() throws Exception {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        BrainTestUtils.fillTestConfig(config);
        Injector injector = Guice.createInjector(
            BrainTestUtils.modules(config));

        Directory directory = injector.getInstance(Directory.class);
        BrainTestUtils.setupZkTestDirectory(directory);

        this.dataClient = injector.getInstance(DataClient.class);
        this.midoVxLanPeer = new MidoVxLanPeer(this.dataClient);

        Host host = new Host();
        host.setName("MidoMacBrokerTestHost");
        hostId = dataClient.hostsCreate(UUID.randomUUID(), host);

        TunnelZone tz = new TunnelZone();
        tz.setName("test");
        UUID tzId = dataClient.tunnelZonesCreate(tz);
        TunnelZone.HostConfig zoneHost = new TunnelZone.HostConfig(hostId);
        zoneHost.setIp(tzHostIp);
        dataClient.tunnelZonesAddMembership(tzId, zoneHost);

        VTEP vtep = new VTEP();
        vtep.setId(tunnelZoneVtepIP);
        vtep.setMgmtPort(vtepMgmtPort);
        vtep.setTunnelZone(tzId);
        dataClient.vtepCreate(vtep);

        VTEP vtepAlt = new VTEP();
        vtepAlt.setId(tunnelZoneVtepIPAlt);
        vtepAlt.setMgmtPort(vtepMgmtPortAlt);
        vtepAlt.setTunnelZone(tzId);
        dataClient.vtepCreate(vtepAlt);
    }

    /*
     * Makes a bridge with a fake binding to the vtep.
     */
    private UUID makeBridge(String name) throws SerializationException,
                                                StateAccessException {
        Bridge bridge = new Bridge();
        bridge.setName(name);
        UUID bridgeId = dataClient.bridgesCreate(bridge);
        // Fake a binding
        dataClient.bridgeCreateVxLanPort(bridgeId, tunnelZoneVtepIP,
                                         vtepMgmtPort, bridgePortVNI,
                                         tunnelZoneVtepIP, UUID.randomUUID());
        return bridgeId;
    }

    /*
     * Creates a fake exterior port, bound to hostId and a random interface name
     */
    private UUID addPort(UUID bridgeId, MAC mac) throws SerializationException,
                                                        StateAccessException {
        Port<?, ?> port = new BridgePort();
        port.setDeviceId(bridgeId);
        port.setHostId(hostId);
        port.setInterfaceName("eth-"+bridgeId);
        UUID bridgePortId = dataClient.portsCreate(port);
        port = dataClient.portsGet(bridgePortId);
        dataClient.hostsAddVrnPortMappingAndReturnPort(hostId,
                                                       bridgePortId,
                                                       bridgePortIface);

        port.setInterfaceName(bridgePortIface);
        port.setHostId(hostId);
        dataClient.portsUpdate(port);
        dataClient.bridgeAddMacPort(bridgeId, Bridge.UNTAGGED_VLAN_ID,
            mac, bridgePortId);
        return bridgePortId;
    }

    @Test
    public void testApplyResilientToNullUpdates() {
        midoVxLanPeer.apply(null); // expect no NPE
    }

    @Test
    public void testBrokerStartsStopsWatchingTwoBridgeTables()
        throws Exception {
        // Create two bridges and one bridge port per each.
        UUID bridgeId1 = makeBridge("bridge1");
        UUID bridgeId2 = makeBridge("bridge2");
        UUID bridgePort1 = addPort(bridgeId1, mac1);
        UUID bridgePort2 = addPort(bridgeId2, mac2);

        assertTrue(midoVxLanPeer.watch(bridgeId1));
        assertTrue(midoVxLanPeer.watch(bridgeId2));

        // Check that a bridge cannot be watched twice.
        assertTrue(!midoVxLanPeer.watch(bridgeId1));

        // MidoVtep has local copies of those bridges' Mac tables.
        Set<UUID> midoBridges = midoVxLanPeer.getMacTableOwnerIds();
        assertThat(midoBridges, containsInAnyOrder(bridgeId1, bridgeId2));
        assertNull(midoVxLanPeer.getPort(bridgeId1, vMac3.IEEE802()));

        // The old Mac-port mapping is deleted and a new one is added.
        dataClient.bridgeDeleteMacPort(bridgeId1, Bridge.UNTAGGED_VLAN_ID,
                                       mac1, bridgePort1);

        UUID bridgePort3 = addPort(bridgeId1, mac3);
        assertNull(midoVxLanPeer.getPort(bridgeId1, mac1));
        assertEquals(bridgePort2, midoVxLanPeer.getPort(bridgeId2, mac2));
        assertEquals(bridgePort3, midoVxLanPeer.getPort(bridgeId1, mac3));
    }

    @Test
    public void testBridgeVxLanPortRemoval()
        throws Exception {
        // Create two bridges and one bridge port per each.
        UUID bridgeId1 = makeBridge("bridge1");
        UUID bridgeId2 = makeBridge("bridge2");

        assertTrue(midoVxLanPeer.watch(bridgeId1));
        assertTrue(midoVxLanPeer.watch(bridgeId2));

        // MidoVtep has local copies of those bridges' Mac tables.
        Set<UUID> midoBridges = midoVxLanPeer.getMacTableOwnerIds();
        assertThat(midoBridges, containsInAnyOrder(bridgeId1, bridgeId2));

        // updating the bridge1 vxlanport should maintain the monitoring
        // by reinstalling the watch (allowing the detection of vxlan
        // port removals occurring afterwards.
        UUID vxLanPortId1 = dataClient.bridgesGet(bridgeId1).getVxLanPortId();
        VxLanPort vxLanPort1 = (VxLanPort)dataClient.portsGet(vxLanPortId1);
        dataClient.portsUpdate(vxLanPort1);
        assertThat(midoBridges, containsInAnyOrder(bridgeId1, bridgeId2));

        // Removing the vxlan port should remove the bridge from the monitored
        // list
        dataClient.bridgeDeleteVxLanPort(bridgeId1);
        assertThat(midoBridges, containsInAnyOrder(bridgeId2));

        // Removing the vxlan port should remove the bridge from the monitored
        // list
        dataClient.bridgeDeleteVxLanPort(bridgeId2);

        assertThat(midoBridges, emptyIterable());
    }

    @Test
    public void testMidoVtepUpdatesBridgeMacTable() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        UUID bridgePortId = addPort(bridgeId, mac1);

        assertTrue(midoVxLanPeer.watch(bridgeId));

        assertTrue("A new mac-port entry is added to the backend bridge.",
                   dataClient.bridgeHasMacPort(bridgeId,
                                               Bridge.UNTAGGED_VLAN_ID,
                                               mac1, bridgePortId)
        );

        assertEquals(bridgePortId,
                     midoVxLanPeer.getPort(bridgeId, mac1));
    }

    @Test
    public void testNotifiedOnMacPortUpdate() throws Exception {

        UUID bridgeId = makeBridge("bridge");
        String lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);

        RxTestUtils.TestedObservable<MacLocation> testedObs =
            RxTestUtils.test(midoVxLanPeer.observableUpdates());
        testedObs.expect( // preseed, then deletion, then read
                    new MacLocation(vMac1, null, lsName, tzHostIp),
                    new MacLocation(vMac1, null, lsName, null),
                    new MacLocation(vMac3, null, lsName, tzHostIp))
                 .noErrors()
                 .notCompleted()
                 .subscribe();

        // add a port with a mac-port assoc, should be preseeded
        final UUID bridgePortId = addPort(bridgeId, mac1);

        // start watching the bridge
        assertTrue(midoVxLanPeer.watch(bridgeId));

        // The old Mac-port mapping is deleted and a new one is added.
        dataClient.bridgeDeleteMacPort(bridgeId, Bridge.UNTAGGED_VLAN_ID,
                                       mac1, bridgePortId);
        dataClient.bridgeAddMacPort(bridgeId, Bridge.UNTAGGED_VLAN_ID,
                                    vMac3.IEEE802(), bridgePortId);

        testedObs.unsubscribe();

        midoVxLanPeer.stop();

        testedObs.evaluate();
    }

    @Test
    public void testNotifiedOnIpChanges() throws Exception {

        UUID bridgeId = makeBridge("bridge");
        String lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);

        RxTestUtils.TestedObservable<MacLocation> testedObs =
            RxTestUtils.test(midoVxLanPeer.observableUpdates());
        testedObs
            .expect(
                new MacLocation(vMac1, null, lsName, tzHostIp),   // preseed
                new MacLocation(vMac1, macIp1, lsName, tzHostIp), // learned
                new MacLocation(vMac1, macIp2, lsName, tzHostIp), // learned
                new MacLocation(vMac1, macIp2, lsName, null))     // forget
            .noErrors()
            .notCompleted()
            .subscribe();

        // add a port with a mac-port assoc, should be preseeded
        addPort(bridgeId, mac1);

        // start watching the bridge
        assertTrue(midoVxLanPeer.watch(bridgeId));

        // learn an IP
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac1);

        // learn an IP change
        dataClient.bridgeAddIp4Mac(bridgeId, macIp2, mac1);

        // forget IP
        dataClient.bridgeDeleteIp4Mac(bridgeId, macIp2, mac1);

        testedObs.unsubscribe();

        midoVxLanPeer.stop();

        testedObs.evaluate();
    }

    @Test
    public void testMacLocationApplies() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        String  lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);
        assertTrue(midoVxLanPeer.watch(bridgeId));
        UUID vxLanPortId = dataClient.bridgesGet(bridgeId).getVxLanPortId();

        MacLocation ml;

        // add a mapping
        ml = new MacLocation(vMac1, null, lsName, tunnelZoneVtepIP);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly mapped", vxLanPortId,
                     midoVxLanPeer.getPort(bridgeId, mac1));

        // remove the mapping
        ml = new MacLocation(vMac1, null, lsName, null);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly unmapped", null,
                     midoVxLanPeer.getPort(bridgeId, mac1));

    }

    @Test
    public void testMacLocationAppliesIpUpdates() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        String  lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);
        assertTrue(midoVxLanPeer.watch(bridgeId));
        UUID vxLanPortId = dataClient.bridgesGet(bridgeId).getVxLanPortId();

        MacLocation ml;

        // add a mapping
        ml = new MacLocation(vMac1, macIp1, lsName, tunnelZoneVtepIP);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly mapped", vxLanPortId,
                     midoVxLanPeer.getPort(bridgeId, mac1));

        // check if learned
        assertTrue(dataClient.bridgeHasIP4MacPair(bridgeId, macIp1, mac1));

        // remove the mapping
        ml = new MacLocation(vMac1, null, lsName, null);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly unmapped", null,
                     midoVxLanPeer.getPort(bridgeId, mac1));

        // check if forgotten
        assertTrue(!dataClient.bridgeHasIP4MacPair(bridgeId, macIp1, mac1));

        // add a new mapping...
        ml = new MacLocation(vMac2, macIp2, lsName, tunnelZoneVtepIP);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly mapped", vxLanPortId,
                     midoVxLanPeer.getPort(bridgeId, vMac2.IEEE802()));
        assertTrue(dataClient.bridgeHasIP4MacPair(bridgeId, macIp2,
                                                  vMac2.IEEE802()));

        // add a new mapping for the same mac
        ml = new MacLocation(vMac2, macIp3, lsName, tunnelZoneVtepIP);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly mapped", vxLanPortId,
                     midoVxLanPeer.getPort(bridgeId, vMac2.IEEE802()));
        assertTrue(dataClient.bridgeHasIP4MacPair(bridgeId, macIp3,
                                                  vMac2.IEEE802()));
        assertTrue(dataClient.bridgeHasIP4MacPair(bridgeId, macIp2,
                                                  vMac2.IEEE802()));

        // and forget everything about that mac
        ml = new MacLocation(vMac2, null, lsName, null);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is not mapped", null,
                     midoVxLanPeer.getPort(bridgeId, vMac2.IEEE802()));
        assertTrue(!dataClient.bridgeHasIP4MacPair(bridgeId, macIp3,
                                                   vMac2.IEEE802()));
        assertTrue(!dataClient.bridgeHasIP4MacPair(bridgeId, macIp2,
                                                   vMac2.IEEE802()));

    }

    @Test
    public void testMacLocationFlow() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        String  lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);
        assertTrue(midoVxLanPeer.watch(bridgeId));
        UUID vxLanPortId = dataClient.bridgesGet(bridgeId).getVxLanPortId();

        // extract the observable and test it
        Observable<MacLocation> obs = midoVxLanPeer.observableUpdates();
        RxTestUtils.TestedObservable testedObs = RxTestUtils.test(obs);
        testedObs.expect( // creation of port with mac2
                         new MacLocation(vMac2, null, lsName, tzHostIp),
                         // creation of port with mac3
                         new MacLocation(vMac3, null, lsName, tzHostIp),
                         // assign macIp1 to mac2
                         new MacLocation(vMac2, macIp1, lsName, tzHostIp),
                         // move macIp1 from mac2 to mac3
                         new MacLocation(vMac2, macIp1, lsName, null),
                         new MacLocation(vMac3, macIp1, lsName, tzHostIp),
                         // add a macIp3 to mac3
                         new MacLocation(vMac3, macIp3, lsName, tzHostIp)
                        )
                 .noErrors()
                 .completes()
                 .subscribe();

        MacLocation ml;

        // add a mapping
        // update from the vtep should not be forwarded via observable
        ml = new MacLocation(vMac1, null, lsName, tunnelZoneVtepIP);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly mapped", vxLanPortId,
                     midoVxLanPeer.getPort(bridgeId, mac1));

        // remove the mapping
        // update from the vtep should not be forwarded via observable
        ml = new MacLocation(vMac1, null, lsName, null);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly unmapped", null,
                     midoVxLanPeer.getPort(bridgeId, mac1));

        // add 2 ports not from the vtep
        addPort(bridgeId, mac2);
        addPort(bridgeId, mac3);

        // notify a new ip 2 mac mapping
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac2);

        // change ip from mac2 to mac3
        // must explicitly remove previous mapping
        // FIXME: is this the correct behaviour?
        dataClient.bridgeDeleteIp4Mac(bridgeId, macIp1, mac2);
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac3);

        // Add a new ip to mac3
        dataClient.bridgeAddIp4Mac(bridgeId, macIp3, mac3);

        assertTrue(!dataClient.bridgeHasIP4MacPair(bridgeId, macIp1, mac2));
        assertTrue(dataClient.bridgeHasIP4MacPair(bridgeId, macIp1, mac3));
        assertTrue(dataClient.bridgeHasIP4MacPair(bridgeId, macIp3, mac3));

        midoVxLanPeer.stop();

        testedObs.unsubscribe();
        testedObs.evaluate();
    }

   @Test
    public void testSubscriptionLifecycle() throws Exception {

        UUID bridgeId = makeBridge("bridge");
        assertTrue(midoVxLanPeer.watch(bridgeId));

       // add a port before subscribing, should go unnoticed
       addPort(bridgeId, mac1);

       // extract the observable and test it
       Observable<MacLocation> obs = midoVxLanPeer.observableUpdates();
       RxTestUtils.TestedObservable testedObs = RxTestUtils.test(obs);
       testedObs.noElements()
                .noErrors()
                .completes()
                .subscribe();

       midoVxLanPeer.stop();

       testedObs.unsubscribe();
       testedObs.evaluate();
    }

    @Test
    public void testMacPortMapBehavior() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        MacPortMap macPortMap = dataClient.bridgeGetMacTable(
            bridgeId, Bridge.UNTAGGED_VLAN_ID, false);
        macPortMap.start();

        //UUID port1 = UUID.randomUUID();
        //UUID port2 = UUID.randomUUID();
        UUID port1 = new UUID(0, 2);
        UUID port2 = new UUID(0, 1);

        // a first value is set
        macPortMap.put(mac1, port1);
        assertEquals(port1, macPortMap.get(mac1));
        assertTrue(macPortMap.containsKey(mac1));
        assertTrue(macPortMap.containsValue(port1));
        assertTrue(dataClient.bridgeHasMacPort(bridgeId,
                                               Bridge.UNTAGGED_VLAN_ID,
                                               mac1, port1));

        // remove value
        macPortMap.removeIfOwner(mac1);
        assertNull(macPortMap.get(mac1));
        assertTrue(!macPortMap.containsKey(mac1));
        assertTrue(!macPortMap.containsValue(port1));
        assertTrue(!dataClient.bridgeHasMacPort(bridgeId,
                                                Bridge.UNTAGGED_VLAN_ID,
                                                mac1, port1));


        // set a different value
        //macPortMap.put(mac1, port2);
        dataClient.bridgeAddMacPort(bridgeId, Bridge.UNTAGGED_VLAN_ID,
                                    mac1, port2);
        assertEquals(port2, macPortMap.get(mac1));
        assertTrue(macPortMap.containsKey(mac1));
        assertTrue(macPortMap.containsValue(port2));
        assertTrue(dataClient.bridgeHasMacPort(bridgeId,
                                               Bridge.UNTAGGED_VLAN_ID,
                                               mac1, port2));

        // try to re-set the same value (is silently ignored)
        macPortMap.put(mac1, port2);
        assertEquals(port2, macPortMap.get(mac1));
        assertTrue(macPortMap.containsKey(mac1));
        assertTrue(macPortMap.containsValue(port2));
        assertTrue(dataClient.bridgeHasMacPort(bridgeId,
                                               Bridge.UNTAGGED_VLAN_ID,
                                               mac1, port2));

        // try to re-set the same value via dataclient causes a complain
        try {
            dataClient.bridgeAddMacPort(bridgeId, Bridge.UNTAGGED_VLAN_ID,
                                        mac1, port2);
            fail("Adding a previously existing mac->port");
        } catch (StateAccessException e) {
            // ok
        }
        assertEquals(port2, macPortMap.get(mac1));
        assertTrue(macPortMap.containsKey(mac1));
        assertTrue(macPortMap.containsValue(port2));
        assertTrue(dataClient.bridgeHasMacPort(bridgeId,
                                               Bridge.UNTAGGED_VLAN_ID,
                                               mac1, port2));

        // associate another value to the same key
        // FIXME: the new value should be rejected, or the previous one removed
        // BREAKS the replicated map (entries cannot be deleted).
        //macPortMap.put(mac1, port1);
        dataClient.bridgeAddMacPort(bridgeId, Bridge.UNTAGGED_VLAN_ID,
                                    mac1, port1);
        assertTrue(macPortMap.containsKey(mac1));
        assertTrue(dataClient.bridgeHasMacPort(bridgeId,
                                               Bridge.UNTAGGED_VLAN_ID,
                                               mac1, port1));
        assertTrue(dataClient.bridgeHasMacPort(bridgeId,
                                               Bridge.UNTAGGED_VLAN_ID,
                                               mac1, port2));

        // FIXME: the following should work
        // FIXME: Or setting multiple values for the same key (above) should not
        // remove value
        //dataClient.bridgeDeleteMacPort(bridgeId, Bridge.UNTAGGED_VLAN_ID,
        //                               mac1, port1);
        //dataClient.bridgeDeleteMacPort(bridgeId, Bridge.UNTAGGED_VLAN_ID,
        //                               mac1, port2);
        //assertNull(macPortMap.get(mac1));
        //assertTrue(!macPortMap.containsKey(mac1));
        //assertTrue(!dataClient.bridgeHasMacPort(bridgeId,
        //                                        Bridge.UNTAGGED_VLAN_ID,
        //                                        mac1, port1));
        //assertTrue(!dataClient.bridgeHasMacPort(bridgeId,
        //                                        Bridge.UNTAGGED_VLAN_ID,
        //                                        mac1, port2));

        macPortMap.stop();
    }

    @Test
    public void testIp4ToMacMapBehavior() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        Ip4ToMacReplicatedMap ipMacMap = dataClient.bridgeGetArpTable(bridgeId);
        ipMacMap.start();

        // sanity check
        assertNull(dataClient.bridgeGetIp4Mac(bridgeId, macIp1));

        // Set a first persistent value
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                              macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertEquals(mac1, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac1, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(ipMacMap.containsValue(mac1));

        // Repeating does not cause an exception, but is ignored
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                              macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertEquals(mac1, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac1, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(ipMacMap.containsValue(mac1));

        // Removing the value
        dataClient.bridgeDeleteIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertNull(dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertNull(ipMacMap.get(macIp1));
        assertTrue(!ipMacMap.containsKey(macIp1));
        assertTrue(!ipMacMap.containsValue(mac1));

        // Removing the value again has no effect
        dataClient.bridgeDeleteIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                          macIp1, mac1));
        assertNull(dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertNull(ipMacMap.get(macIp1));
        assertTrue(!ipMacMap.containsKey(macIp1));
        assertTrue(!ipMacMap.containsValue(mac1));

        // Set a learned value
        dataClient.bridgeAddLearnedIp4Mac(bridgeId, macIp1, mac2);
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac2));
        assertTrue(dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                           macIp1, mac2));
        assertEquals(mac2, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac2, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(!ipMacMap.containsValue(mac1));
        assertTrue(ipMacMap.containsValue(mac2));

        // Repeating does not cause an exception, but it is ignored
        dataClient.bridgeAddLearnedIp4Mac(bridgeId, macIp1, mac2);
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac2));
        assertTrue(dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                           macIp1, mac2));
        assertEquals(mac2, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac2, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(!ipMacMap.containsValue(mac1));
        assertTrue(ipMacMap.containsValue(mac2));

        // Removing the value
        dataClient.bridgeDeleteIp4Mac(bridgeId, macIp1, mac2);
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac2));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac2));
        assertNull(dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertNull(ipMacMap.get(macIp1));
        assertTrue(!ipMacMap.containsKey(macIp1));
        assertTrue(!ipMacMap.containsValue(mac1));
        assertTrue(!ipMacMap.containsValue(mac2));

        ipMacMap.stop();
    }

    @Test
    public void testLearnedOnPersistentIp4ToMacMapBehavior() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        Ip4ToMacReplicatedMap ipMacMap = dataClient.bridgeGetArpTable(bridgeId);
        ipMacMap.start();

        // Set a first persistent value
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                              macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                          macIp1, mac1));
        assertEquals(mac1, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac1, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(ipMacMap.containsValue(mac1));

        // Repeating does not cause an exception
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                              macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertEquals(mac1, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac1, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(ipMacMap.containsValue(mac1));

        // Setting a the same learned value should be ignored
        dataClient.bridgeAddLearnedIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                              macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertEquals(mac1, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac1, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(ipMacMap.containsValue(mac1));

        // Removing the value
        dataClient.bridgeDeleteIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertNull(dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertNull(ipMacMap.get(macIp1));
        assertTrue(!ipMacMap.containsKey(macIp1));
        assertTrue(!ipMacMap.containsValue(mac1));

        // Setting a different learned value should also be ignored
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac1);
        dataClient.bridgeAddLearnedIp4Mac(bridgeId, macIp1, mac2);
        assertTrue(dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                              macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac2));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac2));
        assertEquals(mac1, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac1, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(ipMacMap.containsValue(mac1));
        assertTrue(!ipMacMap.containsValue(mac2));

        // Removing the value
        dataClient.bridgeDeleteIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId, macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                          macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId, macIp1,
                                                               mac2));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                          macIp1, mac2));
        assertNull(dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertNull(ipMacMap.get(macIp1));
        assertTrue(!ipMacMap.containsKey(macIp1));
        assertTrue(!ipMacMap.containsValue(mac1));
        assertTrue(!ipMacMap.containsValue(mac2));

        ipMacMap.stop();
    }

    @Test
    public void testPersistentOnLearnedIp4ToMacMapBehavior() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        Ip4ToMacReplicatedMap ipMacMap = dataClient.bridgeGetArpTable(bridgeId);
        ipMacMap.start();

        // Set a first learned value
        dataClient.bridgeAddLearnedIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac1));
        assertTrue(dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                           macIp1, mac1));
        assertEquals(mac1, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac1, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(ipMacMap.containsValue(mac1));

        // Repeating does not cause an exception
        dataClient.bridgeAddLearnedIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac1));
        assertTrue(dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                           macIp1, mac1));
        assertEquals(mac1, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac1, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(ipMacMap.containsValue(mac1));

        // Setting a the same persistent value should be overwrite the previous
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                              macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertEquals(mac1, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac1, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(ipMacMap.containsValue(mac1));

        // Removing the value
        dataClient.bridgeDeleteIp4Mac(bridgeId, macIp1, mac1);
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                          macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac1));
        assertNull(dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertNull(ipMacMap.get(macIp1));
        assertTrue(!ipMacMap.containsKey(macIp1));
        assertTrue(!ipMacMap.containsValue(mac1));
        assertTrue(!ipMacMap.containsValue(mac2));

        // Setting a different persistent value should prevail
        dataClient.bridgeAddLearnedIp4Mac(bridgeId, macIp1, mac1);
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac2);
        assertTrue(dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                              macIp1, mac2));
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac2));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertEquals(mac2, dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertEquals(mac2, ipMacMap.get(macIp1));
        assertTrue(ipMacMap.containsKey(macIp1));
        assertTrue(!ipMacMap.containsValue(mac1));
        assertTrue(ipMacMap.containsValue(mac2));

        // Removing the value
        dataClient.bridgeDeleteIp4Mac(bridgeId, macIp1, mac2);
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac1));
        assertTrue(!dataClient.bridgeCheckPersistentIP4MacPair(bridgeId,
                                                               macIp1, mac2));
        assertTrue(!dataClient.bridgeCheckLearnedIP4MacPair(bridgeId,
                                                            macIp1, mac2));
        assertNull(dataClient.bridgeGetIp4Mac(bridgeId, macIp1));
        assertNull(ipMacMap.get(macIp1));
        assertTrue(!ipMacMap.containsKey(macIp1));
        assertTrue(!ipMacMap.containsValue(mac1));

        ipMacMap.stop();
    }

    @Test
    public void testMacLocationFlowHint() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        String  lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);
        assertTrue(midoVxLanPeer.watch(bridgeId));
        UUID vxLanPortId = dataClient.bridgesGet(bridgeId).getVxLanPortId();

        // extract the observable and test it
        Observable<MacLocation> obs = midoVxLanPeer.observableUpdates();
        RxTestUtils.TestedObservable testedObs = RxTestUtils.test(obs);
        testedObs.expect(// creation of ports
                         new MacLocation(vMac1, null, lsName, tzHostIp),
                         new MacLocation(vMac2, null, lsName, tzHostIp),
                         // assign macIp1 to mac1 (hint)
                         new MacLocation(vMac1, macIp1, lsName, tzHostIp),
                         // assign macIp1 to mac2 (not hint)
                         new MacLocation(vMac1, macIp1, lsName, null),
                         new MacLocation(vMac2, macIp1, lsName, tzHostIp),
                         // assign macIp1 to mac1 again (not hint)
                         new MacLocation(vMac2, macIp1, lsName, null)
                         )
                 .noErrors()
                 .completes()
                 .subscribe();

        MacLocation ml;

        // add ports not from the vtep
        addPort(bridgeId, mac1);
        addPort(bridgeId, mac2);

        // map an ip via hint interface
        dataClient.bridgeAddLearnedIp4Mac(bridgeId, macIp1, mac1);

        // map ip to a different mac via regular interface
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac2);

        // previous mapping must have disappeared
        assertTrue(!dataClient.bridgeHasIP4MacPair(bridgeId, macIp1, mac1));
        assertTrue(dataClient.bridgeHasIP4MacPair(bridgeId, macIp1, mac2));

        // try to overwrite via hint interface (should be ignored)
        dataClient.bridgeAddLearnedIp4Mac(bridgeId, macIp1, mac1);

        // try to overwrite via regular interface (value should be replaced)
        // Note: the old value is removed, but the new one is not set
        // because the mac has no associated port.
        dataClient.bridgeAddIp4Mac(bridgeId, macIp1, mac3);

        assertTrue(!dataClient.bridgeHasIP4MacPair(bridgeId, macIp1, mac1));
        assertTrue(!dataClient.bridgeHasIP4MacPair(bridgeId, macIp1, mac2));
        assertTrue(dataClient.bridgeHasIP4MacPair(bridgeId, macIp1, mac3));

        midoVxLanPeer.stop();

        testedObs.unsubscribe();
        testedObs.evaluate();
    }

    @Test
    public void testSubscriptionLifecycleOnBridgeRemoval() throws Exception {

        UUID bridgeId = makeBridge("bridge");
        String lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);
        assertTrue(midoVxLanPeer.watch(bridgeId));

        // extract the observable
        Observable<MacLocation> obs = midoVxLanPeer.observableUpdates();
        RxTestUtils.TestedObservable testedObs = RxTestUtils.test(obs);
        testedObs.expect(new MacLocation(vMac1, null, lsName, tzHostIp))
                 .noErrors()
                 .completes()
                 .subscribe();

        // add a port: should be notified
        addPort(bridgeId, mac1);

        // Removing the vxlan port should stop bridge from being monitored
        dataClient.bridgeDeleteVxLanPort(bridgeId);

        // add a port: should not be notified
        addPort(bridgeId, mac3);

        midoVxLanPeer.stop();

        testedObs.unsubscribe();
        testedObs.evaluate();
    }

    @Test
    public void testBridgeChangingPeer() throws Exception {

        UUID bridgeId = makeBridge("bridge");
        String lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);
        assertTrue(midoVxLanPeer.watch(bridgeId));

        // extract the observable
        Observable<MacLocation> obs = midoVxLanPeer.observableUpdates();
        RxTestUtils.TestedObservable testedObs = RxTestUtils.test(obs);
        testedObs.expect(new MacLocation(vMac1, null, lsName, tzHostIp))
                 .noErrors()
                 .completes()
                 .subscribe();

        // add a port: should be notified
        addPort(bridgeId, mac1);

        // Removing the vxlan port should stop bridge from being monitored
        dataClient.bridgeDeleteVxLanPort(bridgeId);

        // add a port: should not be notified
        addPort(bridgeId, mac3);

        // Create a new vxlan port for the bridge and associate it to another
        // vxlan peer
        // NOTE: not sure if this can happen in the real world, but helps us
        // to test that all settings from previous vxlanpeer are correctly
        // removed.
        MidoVxLanPeer altPeer = new MidoVxLanPeer(this.dataClient);
        dataClient.bridgeCreateVxLanPort(bridgeId, tunnelZoneVtepIPAlt,
                                         vtepMgmtPortAlt, bridgePortVNIAlt,
                                         tunnelZoneVtepIPAlt,
                                         UUID.randomUUID());
        assertTrue(altPeer.watch(bridgeId));

        // extract the observable
        Observable<MacLocation> obsAlt = altPeer.observableUpdates();
        RxTestUtils.TestedObservable testedObsAlt = RxTestUtils.test(obsAlt);
        testedObsAlt.expect(new MacLocation(vMac2, null, lsName, tzHostIp))
                 .noErrors()
                 .completes()
                 .subscribe();

        // add a port: should be notified to altPeer and not to vxlanpeer
        addPort(bridgeId, mac2);

        midoVxLanPeer.stop();
        altPeer.stop();

        testedObs.unsubscribe();
        testedObs.evaluate();
        testedObsAlt.unsubscribe();
        testedObsAlt.evaluate();
    }

    @Test
    public void testUnsubscribe() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        String lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);

        assertTrue(midoVxLanPeer.watch(bridgeId));

        Observable<MacLocation> obs = midoVxLanPeer.observableUpdates();
        RxTestUtils.TestedObservable testedObs = RxTestUtils.test(obs);
        testedObs.expect(new MacLocation(vMac1, null, lsName, tzHostIp))
                 .noErrors()
                 .notCompleted()
                 .subscribe();

        addPort(bridgeId, mac1);
        // let's unsubscribe, expect no onNext or onComplete calls
        testedObs.unsubscribe();
        // Add another port, we should not get a notification
        addPort(bridgeId, mac1);

        // The stop completes the observable, but we should not get any
        // onCompletes since we already unsubscribed
        midoVxLanPeer.stop();
        testedObs.evaluate();

    }

}

