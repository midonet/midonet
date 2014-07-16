/*
* Copyright 2014 Midokura Europe SARL
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
import org.midonet.brain.org.midonet.brain.test.RxTestUtils;
import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.southbound.vtep.VtepConstants;
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
import org.midonet.midolman.state.StateAccessException;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.emptyIterable;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThat;
import static org.junit.Assert.assertTrue;

public class MidoVxLanPeerTest {

    // Class under test
    private MidoVxLanPeer midoVxLanPeer = null;

    private DataClient dataClient = null;

    private MAC mac1 = MAC.fromString("aa:bb:cc:dd:ee:01");
    private MAC mac2 = MAC.fromString("aa:bb:cc:dd:ee:02");
    private MAC mac3 = MAC.fromString("aa:bb:cc:dd:ee:03");

    private final IPv4Addr tunnelZoneHostIP = IPv4Addr.apply("192.168.1.200");
    private final int bridgePortVNI = 42;
    private final int bridgePortVNIAlt = 44;
    private final String bridgePortIface = "eth0";

    private final IPv4Addr tunnelZoneVtepIP = IPv4Addr.apply("192.168.1.100");
    private final IPv4Addr tunnelZoneVtepIPAlt = IPv4Addr.apply("192.168.1.110");
    private final int vtepMgmtPort = 6632;
    private final int vtepMgmtPortAlt = 6632;

    private Host host = null;
    private UUID hostId = null;
    private VTEP vtep = null;
    private VTEP vtepAlt = null;

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

        host = new Host();
        host.setName("MidoMacBrokerTestHost");
        hostId = dataClient.hostsCreate(UUID.randomUUID(), host);

        TunnelZone tz = new TunnelZone();
        tz.setName("test");
        UUID tzId = dataClient.tunnelZonesCreate(tz);
        TunnelZone.HostConfig zoneHost = new TunnelZone.HostConfig(hostId);
        zoneHost.setIp(tunnelZoneHostIP.toIntIPv4());
        dataClient.tunnelZonesAddMembership(tzId, zoneHost);

        vtep = new VTEP();
        vtep.setId(tunnelZoneVtepIP);
        vtep.setMgmtPort(vtepMgmtPort);
        vtep.setTunnelZone(tzId);
        dataClient.vtepCreate(vtep);

        vtepAlt = new VTEP();
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
        assertNull(midoVxLanPeer.getPort(bridgeId1, mac3));

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

        assertEquals(bridgePortId, midoVxLanPeer.getPort(bridgeId, mac1));
    }

    @Test
    public void testNotifiedOnMacPortUpdate() throws Exception {

        UUID bridgeId = makeBridge("bridge");
        String lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);

        RxTestUtils.TestedObservable<MacLocation> testedObs =
            RxTestUtils.test(midoVxLanPeer.observableUpdates());
        testedObs.expect(
                    new MacLocation(mac1, lsName, tunnelZoneHostIP), // preseed
                    new MacLocation(mac1, lsName, null),             // deletion
                    new MacLocation(mac3, lsName, tunnelZoneHostIP)) // read
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
                                    mac3, bridgePortId);

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
        ml = new MacLocation(mac1, lsName, tunnelZoneVtepIP);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly mapped", vxLanPortId,
                     midoVxLanPeer.getPort(bridgeId, mac1));

        // remove the mapping
        ml = new MacLocation(mac1, lsName, null);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly unmapped", null,
                     midoVxLanPeer.getPort(bridgeId, mac1));

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
        testedObs.expect(new MacLocation(mac2, lsName, tunnelZoneHostIP))
                 .noErrors()
                 .completes()
                 .subscribe();

        MacLocation ml;

        // add a mapping
        // update from the vtep should not be forwarded via observable
        ml = new MacLocation(mac1, lsName, tunnelZoneVtepIP);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly mapped", vxLanPortId,
                     midoVxLanPeer.getPort(bridgeId, mac1));

        // remove the mapping
        // update from the vtep should not be forwarded via observable
        ml = new MacLocation(mac1, lsName, null);
        midoVxLanPeer.apply(ml);
        assertEquals("Port is correctly unmapped", null,
                     midoVxLanPeer.getPort(bridgeId, mac1));

        // add a mapping not from the vtep
        addPort(bridgeId, mac2);

        midoVxLanPeer.stop();

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
        testedObs.expect(new MacLocation(mac1, lsName, tunnelZoneHostIP))
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
        testedObs.expect(new MacLocation(mac1, lsName, tunnelZoneHostIP))
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
        testedObsAlt.expect(new MacLocation(mac2, lsName, tunnelZoneHostIP))
                 .noErrors()
                 .completes()
                 .subscribe();

        // add a port: should be notified to altPeer and not to vxlanpeer
        addPort(bridgeId, mac2);

        midoVxLanPeer.stop();
        altPeer.stop();

        testedObs.evaluate();
        testedObsAlt.evaluate();
    }
    @Test
    public void testUnsubscribe() throws Exception {
        UUID bridgeId = makeBridge("bridge");
        String lsName = VtepConstants.bridgeIdToLogicalSwitchName(bridgeId);

        assertTrue(midoVxLanPeer.watch(bridgeId));

        Observable<MacLocation> obs = midoVxLanPeer.observableUpdates();
        RxTestUtils.TestedObservable testedObs = RxTestUtils.test(obs);
        testedObs.expect(new MacLocation(mac1, lsName, tunnelZoneHostIP))
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

