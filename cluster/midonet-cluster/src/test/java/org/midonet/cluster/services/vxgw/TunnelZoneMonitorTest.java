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
package org.midonet.cluster.services.vxgw;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.google.inject.Guice;
import com.google.inject.Injector;
import org.junit.Before;
import org.junit.Test;
import rx.Observable;
import rx.Subscription;

import org.midonet.cluster.ClusterTestUtils;
import org.midonet.cluster.test.RxTestUtils;
import org.midonet.cluster.services.vxgw.monitor.TunnelZoneMonitor;
import org.midonet.cluster.DataClient;
import org.midonet.cluster.EntityIdSetEvent;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZookeeperConnectionWatcher;

import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.junit.Assert.assertThat;

import static org.midonet.cluster.EntityIdSetEvent.Type.CREATE;
import static org.midonet.cluster.EntityIdSetEvent.Type.DELETE;
import static org.midonet.cluster.EntityIdSetEvent.Type.STATE;

public class TunnelZoneMonitorTest
    extends DeviceMonitorTestBase<UUID, TunnelZone> {

    /*
     * Midonet data client
     */
    private DataClient dataClient = null;
    private ZookeeperConnectionWatcher zkConnWatcher;

    private TunnelZone createTunnelZone(String name)
        throws StateAccessException, SerializationException{
        TunnelZone tzone = new TunnelZone();
        tzone.setName(name);
        tzone.setType(TunnelZone.Type.vxlan);
        dataClient.tunnelZonesCreate(tzone);
        return tzone;
    }

    private RxTestUtils.TestedObservable testTunnelZoneObservable(
        Observable<TunnelZone> obs) {
        return RxTestUtils.test(obs);
    }

    private RxTestUtils.TestedObservable testIdObservable(
        Observable<UUID> obs) {
        return RxTestUtils.test(obs);
    }

    private RxTestUtils.TestedObservable testEventObservable(
        Observable<EntityIdSetEvent<UUID>> obs) {
        return RxTestUtils.test(obs);
    }

    @Before
    public void before() throws Exception {
        Injector injector = Guice.createInjector(
            ClusterTestUtils.modules());

        Directory directory = injector.getInstance(Directory.class);
        ClusterTestUtils.setupZkTestDirectory(directory);

        this.dataClient = injector.getInstance(DataClient.class);
        this.zkConnWatcher = new ZookeeperConnectionWatcher();
    }

    @Test
    public void testBasic() throws Exception {

        TunnelZoneMonitor tzMon = new TunnelZoneMonitor(
            dataClient, zkConnWatcher);

        // Setup the observables
        RxTestUtils.TestedObservable updates =
            testTunnelZoneObservable(tzMon.getEntityObservable());
        updates.noElements().noErrors().notCompleted().subscribe();

        RxTestUtils.TestedObservable live =
            testEventObservable(tzMon.getEntityIdSetObservable());
        live.noElements().noErrors().notCompleted().subscribe();

        RxTestUtils.TestedObservable creations =
            testIdObservable(extractEvent(tzMon.getEntityIdSetObservable(),
                                          CREATE));
        creations.noElements().noErrors().notCompleted().subscribe();

        RxTestUtils.TestedObservable deletions =
            testIdObservable(extractEvent(tzMon.getEntityIdSetObservable(),
                                          DELETE));
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
    public void testTunnelZoneAddition() throws Exception {

        final List<UUID> creationList = new ArrayList<>();
        final List<UUID> updateList = new ArrayList<>();

        TunnelZoneMonitor tzMon = new TunnelZoneMonitor(dataClient,
                                                        zkConnWatcher);

        RxTestUtils.TestedObservable deletions =
            testIdObservable(extractEvent(tzMon.getEntityIdSetObservable(),
                                          DELETE));
        deletions.noElements().noErrors().notCompleted().subscribe();

        Subscription creations = addIdObservableToList(
            extractEvent(tzMon.getEntityIdSetObservable(), CREATE),
            creationList);
        Subscription updates = addDeviceObservableToList(
            tzMon.getEntityObservable(), updateList);

        // Create the tunnel zone
        TunnelZone tzone = createTunnelZone("tunnelzone1");

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();

        assertThat(creationList, containsInAnyOrder(tzone.getId()));
        assertThat(updateList, containsInAnyOrder(tzone.getId()));
        deletions.evaluate();
    }

    @Test
    public void testTunnelZoneEarlyAddition() throws Exception {

        final List<UUID> creationList = new ArrayList<>();
        final List<UUID> updateList = new ArrayList<>();
        final List<UUID> stateList = new ArrayList<>();

        // Create the tunnel zone
        TunnelZone tzone = createTunnelZone("tunnelzone1");

        TunnelZoneMonitor tzMon = new TunnelZoneMonitor(dataClient,
                                                        zkConnWatcher);

        RxTestUtils.TestedObservable deletions =
            testIdObservable(extractEvent(tzMon.getEntityIdSetObservable(),
                                          DELETE));
        deletions.noElements().noErrors().notCompleted().subscribe();

        Subscription creations = addIdObservableToList(
            extractEvent(tzMon.getEntityIdSetObservable(), CREATE),
            creationList);
        Subscription updates = addDeviceObservableToList(
            tzMon.getEntityObservable(), updateList);
        Subscription states = addIdObservableToList(
            extractEvent(tzMon.getEntityIdSetObservable(), STATE), stateList);

        tzMon.notifyState();

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();
        states.unsubscribe();

        assertThat(creationList, containsInAnyOrder());
        assertThat(updateList, containsInAnyOrder(tzone.getId()));
        assertThat(stateList, containsInAnyOrder(tzone.getId()));
        deletions.evaluate();
    }

    @Test
    public void testTunnelZoneRemoval() throws Exception {

        final List<UUID> creationList = new ArrayList<>();
        final List<UUID> updateList = new ArrayList<>();
        final List<UUID> deletionList = new ArrayList<>();

        TunnelZoneMonitor tzMon = new TunnelZoneMonitor(dataClient,
                                                        zkConnWatcher);

        Subscription creations = addIdObservableToList(
            extractEvent(tzMon.getEntityIdSetObservable(), CREATE),
            creationList);
        Subscription updates = addDeviceObservableToList(
            tzMon.getEntityObservable(), updateList);
        Subscription deletions = addIdObservableToList(
            extractEvent(tzMon.getEntityIdSetObservable(), DELETE),
            deletionList);

        // Create the tunnel zone
        TunnelZone tzone = createTunnelZone("tunnelzone1");

        dataClient.tunnelZonesDelete(tzone.getId());

        creations.unsubscribe();
        updates.unsubscribe();
        deletions.unsubscribe();

        assertThat(creationList, containsInAnyOrder(tzone.getId()));
        assertThat(updateList, containsInAnyOrder(tzone.getId()));
        assertThat(deletionList, containsInAnyOrder(tzone.getId()));
    }
}
