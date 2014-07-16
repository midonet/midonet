/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Before;
import org.junit.Test;

import rx.Observable;
import rx.Subscription;

import org.midonet.brain.BrainTestUtils;
import org.midonet.brain.org.midonet.brain.test.RxTestUtils;
import org.midonet.brain.services.vxgw.monitor.BridgeMonitor;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.cluster.data.Bridge;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.cluster.data.VTEP;
import org.midonet.cluster.data.host.Host;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;
import org.midonet.packets.IPv4Addr;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertThat;
import static org.midonet.cluster.EntityIdSetEvent.Type.CREATE;
import static org.midonet.cluster.EntityIdSetEvent.Type.DELETE;
import static org.midonet.cluster.EntityIdSetEvent.Type.STATE;

public class BridgeMonitorTest extends DeviceMonitorTestBase<UUID, Bridge> {

    /*
     * Vtep parameters
     */
    private static final IPv4Addr vtepMgmtIp = IPv4Addr.apply("192.169.0.20");
    private static final int vtepMgmntPort = 6632;
    private static final int bridgePortVNI = 42;
    private VTEP vtep = null;

    /*
     * Host parameters
     */
    private IPv4Addr tunnelZoneHostIp = IPv4Addr.apply("192.169.0.100");
    private UUID hostId = null;
    private Host host = null;

    /*
     * Midonet data client
     */
    private DataClient dataClient = null;
    private ZookeeperConnectionWatcher zkConnWatcher;

    private UUID makeUnboundBridge(String name) throws SerializationException,
                                                       StateAccessException {
        Bridge bridge = new Bridge();
        bridge.setName(name);
        return dataClient.bridgesCreate(bridge);
    }

    private UUID makeBoundBridge(String name) throws SerializationException,
                                                StateAccessException {
        UUID bridgeId = makeUnboundBridge(name);
        dataClient.bridgeCreateVxLanPort(bridgeId, vtepMgmtIp, vtepMgmntPort,
                                         bridgePortVNI, vtepMgmtIp,
                                         UUID.randomUUID());
        return bridgeId;
    }

    private RxTestUtils.TestedObservable testBridgeObservable(
        Observable<Bridge> obs) {
        return RxTestUtils.test(obs);
    }

    private RxTestUtils.TestedObservable testUUIDObservable(
        Observable<UUID> obs) {
        return RxTestUtils.test(obs);
    }

    private RxTestUtils.TestedObservable testEventObservable(
        Observable<EntityIdSetEvent<UUID>> obs) {
        return RxTestUtils.test(obs);
    }

    @Before
    public void before() throws Exception {
        HierarchicalConfiguration config = new HierarchicalConfiguration();
        BrainTestUtils.fillTestConfig(config);
        Injector injector = Guice.createInjector(
            BrainTestUtils.modules(config));

        Directory directory = injector.getInstance(Directory.class);
        BrainTestUtils.setupZkTestDirectory(directory);

        this.dataClient = injector.getInstance(DataClient.class);
        this.zkConnWatcher = new ZookeeperConnectionWatcher();

        host = new Host();
        host.setName("TestHost");
        hostId = dataClient.hostsCreate(UUID.randomUUID(), host);

        TunnelZone tz = new TunnelZone();
        tz.setName("TestTz");
        UUID tzId = dataClient.tunnelZonesCreate(tz);
        TunnelZone.HostConfig zoneHost = new TunnelZone.HostConfig(hostId);
        zoneHost.setIp(tunnelZoneHostIp.toIntIPv4());
        dataClient.tunnelZonesAddMembership(tzId, zoneHost);

        vtep = new VTEP();
        vtep.setId(vtepMgmtIp);
        vtep.setMgmtPort(vtepMgmntPort);
        vtep.setTunnelZone(tzId);
        dataClient.vtepCreate(vtep);
    }

    /**
     * Check the initial settings (no changes)
     */
    @Test
    public void testBasic() throws Exception {

        BridgeMonitor bMon = new BridgeMonitor(dataClient, zkConnWatcher);

        // Extract the observables
        RxTestUtils.TestedObservable updates =
            testBridgeObservable(bMon.getEntityObservable());
        updates.noElements()
               .noErrors()
               .notCompleted()
               .subscribe();
        RxTestUtils.TestedObservable live =
            testEventObservable(bMon.getEntityIdSetObservable());
        live.noElements()
            .noErrors()
            .notCompleted()
            .subscribe();
        RxTestUtils.TestedObservable creations =
            testUUIDObservable(extractEvent(bMon.getEntityIdSetObservable(),
                                            CREATE));
        creations.noElements()
                 .noErrors()
                 .notCompleted()
                 .subscribe();
        RxTestUtils.TestedObservable deletions =
            testUUIDObservable(extractEvent(bMon.getEntityIdSetObservable(),
                                            DELETE));
        deletions.noElements()
                 .noErrors()
                 .notCompleted()
                 .subscribe();

        updates.unsubscribe();
        live.unsubscribe();
        creations.unsubscribe();
        deletions.unsubscribe();

        updates.evaluate();
        live.evaluate();
        creations.evaluate();
        deletions.evaluate();
    }

    @Test
    public void testBridgeAddition() throws Exception {

        final List<UUID> creationList = new ArrayList<>();
        final List<UUID> updateList = new ArrayList<>();
        BridgeMonitor bMon = new BridgeMonitor(dataClient, zkConnWatcher);

        // Extract the observables
        RxTestUtils.TestedObservable deletions =
            testUUIDObservable(extractEvent(bMon.getEntityIdSetObservable(),
                                            DELETE));
        deletions.noElements()
                 .noErrors()
                 .notCompleted()
                 .subscribe();
        Subscription updates = addDeviceObservableToList(
            bMon.getEntityObservable(), updateList);
        Subscription creations = addIdObservableToList(
            extractEvent(bMon.getEntityIdSetObservable(), CREATE),
            creationList);

        // Create bridge
        UUID bridgeId = makeUnboundBridge("bridge1");

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();

        assertThat(creationList, containsInAnyOrder(bridgeId));
        assertThat(updateList, containsInAnyOrder(bridgeId));
        deletions.evaluate();
    }

    @Test
    public void testBridgeEarlyAddition() throws Exception {

        final List<UUID> creationList = new ArrayList<>();
        final List<UUID> updateList = new ArrayList<>();
        final List<UUID> stateList = new ArrayList<>();

        // Create bridge
        UUID bridgeId = makeUnboundBridge("bridge1");

        // Create the bridge monitor
        BridgeMonitor bMon = new BridgeMonitor(dataClient, zkConnWatcher);

        // Extract the observables
        RxTestUtils.TestedObservable deletions =
            testUUIDObservable(extractEvent(bMon.getEntityIdSetObservable(),
                                            DELETE));
        deletions.noElements()
            .noErrors()
            .notCompleted()
            .subscribe();
        Subscription updates = addDeviceObservableToList(
            bMon.getEntityObservable(), updateList);
        Subscription creations = addIdObservableToList(
            extractEvent(bMon.getEntityIdSetObservable(), CREATE), creationList);
        Subscription states = addIdObservableToList(
            extractEvent(bMon.getEntityIdSetObservable(), STATE), stateList);

        bMon.notifyState();

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();
        states.unsubscribe();

        assertThat(creationList, containsInAnyOrder());
        assertThat(updateList, containsInAnyOrder(bridgeId));
        assertThat(stateList, containsInAnyOrder(bridgeId));
        deletions.evaluate();
    }


    @Test
    public void testBridgeUpdate() throws Exception {

        final List<UUID> creationList = new ArrayList<>();
        final List<UUID> updateList = new ArrayList<>();
        BridgeMonitor bMon = new BridgeMonitor(dataClient, zkConnWatcher);

        // Extract the observables
        RxTestUtils.TestedObservable deletions =
            testUUIDObservable(extractEvent(bMon.getEntityIdSetObservable(),
                                            DELETE));
        deletions.noElements()
                 .noErrors()
                 .notCompleted()
                 .subscribe();

        Subscription creations = addIdObservableToList(
            extractEvent(bMon.getEntityIdSetObservable(), CREATE),
            creationList);
        Subscription updates = addDeviceObservableToList(
            bMon.getEntityObservable(), updateList);

        // Create bridge and update vxlan port
        UUID bridgeId = makeBoundBridge("bridge1");

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();

        assertThat(creationList, containsInAnyOrder(bridgeId));
        assertThat(updateList, containsInAnyOrder(bridgeId, bridgeId));
        deletions.evaluate();
    }

    @Test
    public void testBridgeRemoval() throws Exception {

        final List<UUID> creationList = new ArrayList<>();
        final List<UUID> updateList = new ArrayList<>();
        final List<UUID> deletionList = new ArrayList<>();
        BridgeMonitor bMon = new BridgeMonitor(dataClient, zkConnWatcher);

        // Extract the observables
        Subscription deletions = addIdObservableToList(
            extractEvent(bMon.getEntityIdSetObservable(), DELETE),
            deletionList);
        Subscription creations = addIdObservableToList(
            extractEvent(bMon.getEntityIdSetObservable(), CREATE),
            creationList);
        Subscription updates = addDeviceObservableToList(
            bMon.getEntityObservable(), updateList);

        // Create bridge and update vxlan port
        UUID bridgeId = makeBoundBridge("bridge1");

        // Remove bridge
        dataClient.bridgesDelete(bridgeId);

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();

        assertThat(creationList, containsInAnyOrder(bridgeId));
        assertThat(updateList, containsInAnyOrder(bridgeId, bridgeId));
        assertThat(deletionList, containsInAnyOrder(bridgeId));
    }

    @Test
    public void testBridgeVxLanPortRemoval() throws Exception {

        final List<UUID> creationList = new ArrayList<>();
        final List<UUID> updateList = new ArrayList<>();
        BridgeMonitor bMon = new BridgeMonitor(dataClient, zkConnWatcher);

        // Extract the observables
        RxTestUtils.TestedObservable deletions =
            testUUIDObservable(extractEvent(bMon.getEntityIdSetObservable(),
                                            DELETE));
        deletions.noElements()
                 .noErrors()
                 .notCompleted()
                 .subscribe();

        Subscription creations = addIdObservableToList(
            extractEvent(bMon.getEntityIdSetObservable(), CREATE),
            creationList);
        Subscription updates = addDeviceObservableToList(
            bMon.getEntityObservable(), updateList);

        // Create bridge and update vxlan port
        UUID bridgeId = makeBoundBridge("bridge1");

        // Remove vxlan port
        dataClient.bridgeDeleteVxLanPort(bridgeId);

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();

        assertThat(creationList, containsInAnyOrder(bridgeId));
        assertThat(updateList,
                   containsInAnyOrder(bridgeId, bridgeId, bridgeId));
        deletions.evaluate();
    }
}
