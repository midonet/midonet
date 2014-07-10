/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.services.vxgw;

import java.util.ArrayList;
import java.util.List;

import com.google.inject.Guice;
import com.google.inject.Injector;

import org.apache.commons.configuration.HierarchicalConfiguration;
import org.junit.Before;
import org.junit.Test;

import rx.Observable;
import rx.Subscription;

import org.midonet.brain.BrainTestUtils;
import org.midonet.brain.org.midonet.brain.test.RxTestUtils;
import org.midonet.brain.services.vxgw.monitor.VtepMonitor;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.cluster.data.VTEP;
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

public class VtepMonitorTest extends DeviceMonitorTestBase<IPv4Addr, VTEP> {

    /**
     * VTEP parameters
     */
    private static final IPv4Addr vtepMgmtIp =
        IPv4Addr.fromString("192.168.0.1");

    /**
     * Midonet data client
     */
    private DataClient dataClient = null;
    private ZookeeperConnectionWatcher zkConnWatcher;

    private VTEP createVtep(IPv4Addr ipAddr) throws SerializationException,
                                                    StateAccessException {
        VTEP vtep = new VTEP();
        vtep.setId(ipAddr);
        dataClient.vtepCreate(vtep);
        return vtep;
    }

    private RxTestUtils.TestedObservable testVtepObservable(
        Observable<VTEP> obs) {
        return RxTestUtils.test(obs);
    }

    private RxTestUtils.TestedObservable testIdObservable(
        Observable<IPv4Addr> obs) {
        return RxTestUtils.test(obs);
    }

    private RxTestUtils.TestedObservable testEventObservable(
        Observable<EntityIdSetEvent<IPv4Addr>> obs) {
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

        dataClient = injector.getInstance(DataClient.class);
        zkConnWatcher = new ZookeeperConnectionWatcher();
    }

    @Test
    public void testBasic() throws Exception {

        VtepMonitor vMon = new VtepMonitor(dataClient, zkConnWatcher);

        // Setup the observables
        RxTestUtils.TestedObservable updates =
            testVtepObservable(vMon.getEntityObservable());
        updates.noElements().noErrors().notCompleted().subscribe();

        RxTestUtils.TestedObservable live =
            testEventObservable(vMon.getEntityIdSetObservable());
        live.noElements().noErrors().notCompleted().subscribe();

        RxTestUtils.TestedObservable creations =
            testIdObservable(
                extractEvent(vMon.getEntityIdSetObservable(), CREATE));
        creations.noElements().noErrors().notCompleted().subscribe();

        RxTestUtils.TestedObservable deletions =
            testIdObservable(
                extractEvent(vMon.getEntityIdSetObservable(), DELETE));
        deletions.noElements().noErrors().notCompleted().subscribe();

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
    public void testVtepAddition() throws Exception {

        final List<IPv4Addr> creationList = new ArrayList<>();
        final List<IPv4Addr> updateList = new ArrayList<>();

        VtepMonitor vMon = new VtepMonitor(dataClient, zkConnWatcher);

        RxTestUtils.TestedObservable deletions =
            testIdObservable(
                extractEvent(vMon.getEntityIdSetObservable(), DELETE));
        deletions.noElements().noErrors().notCompleted().subscribe();

        Subscription creations = addIdObservableToList(
            extractEvent(vMon.getEntityIdSetObservable(), CREATE),
            creationList);
        Subscription updates = addDeviceObservableToList(
            vMon.getEntityObservable(), updateList);

        // Create VTEP
        VTEP vtep = createVtep(vtepMgmtIp);

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();

        assertThat(creationList, containsInAnyOrder(vtep.getId()));
        assertThat(updateList, containsInAnyOrder(vtep.getId()));
        deletions.evaluate();
    }

    @Test
    public void testVtepEarlyAddition() throws Exception {

        final List<IPv4Addr> creationList = new ArrayList<>();
        final List<IPv4Addr> updateList = new ArrayList<>();
        final List<IPv4Addr> stateList = new ArrayList<>();

        // Create VTEP
        VTEP vtep = createVtep(vtepMgmtIp);

        VtepMonitor vMon = new VtepMonitor(dataClient, zkConnWatcher);

        RxTestUtils.TestedObservable deletions =
            testIdObservable(
                extractEvent(vMon.getEntityIdSetObservable(), DELETE));
        deletions.noElements().noErrors().notCompleted().subscribe();

        Subscription creations = addIdObservableToList(
            extractEvent(vMon.getEntityIdSetObservable(), CREATE), creationList);
        Subscription updates = addDeviceObservableToList(
            vMon.getEntityObservable(), updateList);
        Subscription states = addIdObservableToList(
            extractEvent(vMon.getEntityIdSetObservable(), STATE), stateList);

        vMon.notifyState();

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();
        states.unsubscribe();

        assertThat(creationList, containsInAnyOrder());
        assertThat(updateList, containsInAnyOrder(vtep.getId()));
        assertThat(stateList, containsInAnyOrder(vtep.getId()));
        deletions.evaluate();
    }

    @Test
    public void testVtepRemoval() throws Exception {

        final List<IPv4Addr> creationList = new ArrayList<>();
        final List<IPv4Addr> updateList = new ArrayList<>();
        final List<IPv4Addr> deletionList = new ArrayList<>();

        VtepMonitor vMon = new VtepMonitor(dataClient, zkConnWatcher);

        Subscription creations = addIdObservableToList(
            extractEvent(vMon.getEntityIdSetObservable(), CREATE),
            creationList);
        Subscription updates = addDeviceObservableToList(
            vMon.getEntityObservable(), updateList);
        Subscription deletions = addIdObservableToList(
            extractEvent(vMon.getEntityIdSetObservable(), DELETE),
            deletionList);

        // Create VTEP
        VTEP vtep = createVtep(vtepMgmtIp);

        dataClient.vtepDelete(vtepMgmtIp);

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();

        assertThat(creationList, containsInAnyOrder(vtep.getId()));
        assertThat(updateList, containsInAnyOrder(vtep.getId()));
        assertThat(deletionList, containsInAnyOrder(vtep.getId()));
    }
}
