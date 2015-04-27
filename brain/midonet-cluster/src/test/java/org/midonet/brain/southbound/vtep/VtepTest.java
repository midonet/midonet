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
package org.midonet.brain.southbound.vtep;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;


import org.junit.Before;
import org.junit.Test;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.OvsDBSet;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;

import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.services.vxgw.VxLanPeerSyncException;
import org.midonet.brain.test.RxTestUtils;
import org.midonet.cluster.data.vtep.model.LogicalSwitch;
import org.midonet.cluster.data.vtep.model.MacLocation;
import org.midonet.cluster.data.vtep.model.McastMac;
import org.midonet.cluster.data.vtep.model.UcastMac;
import org.midonet.cluster.data.vtep.model.VtepMAC;
import org.midonet.packets.IPv4Addr;

import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;
import static org.midonet.brain.southbound.vtep.VtepConstants.bridgeIdToLogicalSwitchName;

public class VtepTest {

    private VtepBroker vtepBroker = null;

    /* This Subject gives the Observable to the mock VtepDataClient,
     * can be used to fake updates. */
    private Subject<TableUpdates, TableUpdates> vtepUpdStream =
        PublishSubject.create();

    private String lsName = "ls";
    // This is the tunnel IP of a fake midonet host
    private IPv4Addr midoVxTunIp = IPv4Addr.fromString("10.9.9.9");
    // This is the management ip of the vtep
    private IPv4Addr mgmtIp = IPv4Addr.fromString("10.1.2.3");
    // This is the management UDP port of the vtep
    private int mgmtPort = 6632;
    // This is the mock vtep's tunnel endpoint
    final IPv4Addr vxTunEndpoint = IPv4Addr.fromString("192.168.0.1");

    private String sMac1 = "aa:bb:cc:dd:ee:01";
    private VtepMAC mac1 = VtepMAC.fromString(sMac1);
    private IPv4Addr macIp1 = IPv4Addr.fromString("10.0.3.1");

    private String sMac2 = "aa:bb:cc:dd:ee:02";
    private VtepMAC mac2 = VtepMAC.fromString(sMac2);
    private IPv4Addr macIp2 = IPv4Addr.fromString("10.0.3.2");

    // A sample entry for the test VTEP
    private Physical_Switch physicalSwitch;

    private UUID mehUuid = new UUID(0, 111);

    @Mocked
    private VtepDataClient vtepDataClient;

    @Before
    public void before() {
        new NonStrictExpectations() {{
            vtepDataClient.getManagementIp(); result = mgmtIp;
            vtepDataClient.getManagementPort(); result = mgmtPort;
            vtepDataClient.updatesObservable();
            result = vtepUpdStream.asObservable();
        }};
        vtepBroker = new VtepBroker(this.vtepDataClient);
        physicalSwitch = new Physical_Switch();
        physicalSwitch.setDescription("description");
        physicalSwitch.setName("vtep");
        OvsDBSet<org.opendaylight.ovsdb.lib.notation.UUID> ports =
            new OvsDBSet<>();
        physicalSwitch.setPorts(ports);
        OvsDBSet<String> mgmtIps = new OvsDBSet<>();
        mgmtIps.add(mgmtIp.toString());
        physicalSwitch.setManagement_ips(mgmtIps);
        OvsDBSet<String> tunnelIps = new OvsDBSet<>();
        tunnelIps.add(vxTunEndpoint.toString());
        physicalSwitch.setTunnel_ips(tunnelIps);
    }

    @Test
    public void testBrokerAppliesUpdate() throws Exception {
        new Expectations() {{
            vtepDataClient.listUcastMacsRemote();
            times = 1; result = new ArrayList<UcastMac>();

            vtepDataClient.addUcastMacRemote(lsName, mac1.IEEE802(),
                                             macIp1, midoVxTunIp);
            times = 1;
            result = new Status(StatusCode.SUCCESS);
        }};

        vtepBroker.apply(new MacLocation(mac1, macIp1, lsName, midoVxTunIp));
    }

    @Test
    public void testBrokerDoesntDupeUcastWithIp() throws Exception {
        final UUID locatorId = UUID.randomUUID();
        final UUID lsId = UUID.randomUUID();
        new Expectations() {{
            vtepDataClient.listUcastMacsRemote();
            times = 1; result = Collections.singletonList(
                UcastMac.apply(lsId, mac1, macIp1, locatorId));
        }};
        vtepBroker.apply(new MacLocation(mac1, macIp1, lsName, midoVxTunIp));
    }

    @Test
    public void testBrokerDoesntDupeUcastWithoutIp() throws Exception {
        final UUID locatorId = UUID.randomUUID();
        final UUID lsId = UUID.randomUUID();
        new Expectations() {{
            vtepDataClient.listUcastMacsRemote();
            times = 1; result = Collections.singletonList(
                UcastMac.apply(lsId, mac1, locatorId));
        }};
        vtepBroker.apply(new MacLocation(mac1, null, lsName, midoVxTunIp));
    }

    @Test
    public void testBrokerDoesntDupeMcastWithIp() throws Exception {
        final UUID locatorId = UUID.randomUUID();
        final UUID lsId = UUID.randomUUID();
        new Expectations() {{
            vtepDataClient.listMcastMacsRemote();
            times = 1; result = Collections.singletonList(
                McastMac.apply(lsId, VtepMAC.UNKNOWN_DST(), macIp1, locatorId));
        }};
        vtepBroker.apply(new MacLocation(VtepMAC.UNKNOWN_DST(), macIp1, lsName,
                                         midoVxTunIp));
    }

    @Test
    public void testBrokerDoesntDupeMcastWithoutIp() throws Exception {
        final UUID locatorId = UUID.randomUUID();
        final UUID lsId = UUID.randomUUID();
        new Expectations() {{
            vtepDataClient.listMcastMacsRemote();
            times = 1; result = Collections.singletonList(
                McastMac.apply(lsId, VtepMAC.UNKNOWN_DST(), locatorId));
        }};
        vtepBroker.apply(new MacLocation(VtepMAC.UNKNOWN_DST(), null, lsName,
                                         midoVxTunIp));
    }

    @Test
    public void testBrokerAppliesUpdateNullIp() throws Exception {
        new Expectations() {{
            vtepDataClient.listUcastMacsRemote();
            times = 1; result = new ArrayList<UcastMac>();

            vtepDataClient.addUcastMacRemote(lsName, mac1.IEEE802(), null,
                                             midoVxTunIp);
            times = 1; result = new Status(StatusCode.SUCCESS);
        }};

        vtepBroker.apply(new MacLocation(mac1, null, lsName,
                                         midoVxTunIp));
    }

    @Test(expected = VxLanPeerSyncException.class)
    public void testBrokerThrowsOnFailedUpdate() throws Exception {
        new Expectations() {{
            vtepDataClient.listUcastMacsRemote();
            times = 1; result = new ArrayList<UcastMac>();

            vtepDataClient.addUcastMacRemote(lsName, mac1.IEEE802(),
                                             macIp1, midoVxTunIp);
            times = 1;
            result = new Status(StatusCode.BADREQUEST);
        }};

        vtepBroker.apply(new MacLocation(mac1, macIp1, lsName, midoVxTunIp));
    }

    @Test
    public void testBrokerDeletesUcastMacRemote() throws Exception {
        new Expectations() {{
            vtepDataClient.deleteAllUcastMacRemote(lsName, mac1.IEEE802());
            result = new Status(StatusCode.SUCCESS);
            times = 1;
        }};
        vtepBroker.apply(new MacLocation(mac1, null, lsName, null));
    }

    /**
     * This one will need a bit of refactoring, setting a value then changing
     * it and verifying the calls, whatever they do.
     */
    @Test
    public void testUpdateHandlerUpdatesUcastMacRemote() throws Exception {
        new Expectations() {{
            vtepDataClient.listUcastMacsRemote();
            times = 1; result = new ArrayList<UcastMac>();

            vtepDataClient.addUcastMacRemote(lsName, mac1.IEEE802(), macIp1,
                                             midoVxTunIp);
            times = 1;
            result = new Status(StatusCode.SUCCESS);
        }};
        vtepBroker.apply(new MacLocation(mac1, macIp1, lsName, midoVxTunIp));
    }

    /**
     * Tests the processing of an update from the VTEP corresponding to a new
     * mac entry being added to the Ucast_macs_local table, which should
     * generate a corresponding MacLocation that reports the mac being located
     * at the VTEP's vxlan tunnel ip.
     */
    @Test
    public void testObservableUpdatesMacAddition() throws Exception {

        // Our logical switch
        final UUID lsId = new UUID(0, 111);
        final LogicalSwitch ls = new LogicalSwitch(lsId, "ls0", 111, "dsc");

        // Prepare an update consisting of a new row being added
        TableUpdates ups = makeLocalMacsUpdate(
            null, makeUcastLocal(mac1.toString(), macIp1.toString())
        );
        feedPhysicalSwitchUpdate(ups);

        // The VtepBroker should process the update doing two things:
        // - Intercept the initial feeding into the Physical_Switch, grabbing
        //   the vtep's tunnel IP
        // - Process the Ucast_Macs_Local update, use the tunnel IP in the
        //   resulting MacLocation emitted from the observable.
        new Expectations() {{
            vtepDataClient.getTunnelIp(); times = 1; result = vxTunEndpoint;
            vtepDataClient.getLogicalSwitch(lsId);
            times = 1; result = ls;
            vtepDataClient.getTunnelIp(); times = 1; result = vxTunEndpoint;
        }};

        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vtepBroker.observableUpdates())
                       .expect(new MacLocation(mac1, macIp1, ls.name(),
                                               vxTunEndpoint))
                       .noErrors()
                       .notCompleted()
                       .subscribe();

        vtepUpdStream.onNext(ups);

        obs.evaluate();
    }

    /**
     * Covers the case where the VtepBroker hasn't yet been able to intercept
     * the vxlan tunnel IP of the VTEP. In this case, it should not be able
     * to emit MacLocation items even if the VTEP reports changes in the table.
     */
    @Test
    public void testObservableUpdatesMacAdditionResilientToLSDeletions()
        throws Exception {

        // Prepare an update consisting of a new row being added
        TableUpdates ups = makeLocalMacsUpdate(
            null, makeUcastLocal(mac1.toString(), vxTunEndpoint.toString())
        );
        feedPhysicalSwitchUpdate(ups);

        // Don't find the logical switch
        new Expectations() {{
            vtepDataClient.getTunnelIp(); times = 1; result = vxTunEndpoint;
            vtepDataClient.getLogicalSwitch(new UUID(0, 111));
            times = 1; result = null;
        }};

        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vtepBroker.observableUpdates())
                .noElements()
                .noErrors()
                .notCompleted()
                .subscribe();

        vtepUpdStream.onNext(ups);

        obs.unsubscribe();
        obs.evaluate();
    }

    /**
     * Tests the processing of an update from the VTEP corresponding to a mac
     * entry being removed from the Ucast_macs_local table, which should
     * generate a corresponding MacLocation that reports the mac not being
     * located anymore at the VTEP's vxlan tunnel ip.
     */
    @Test
    public void testObservableUpdatesMacRemoval() throws Exception {

        // Prepare an update consisting of a new row being added
        TableUpdates ups = makeLocalMacsUpdate(
            makeUcastLocal(mac1.toString(), macIp1.toString()), null
        );
        feedPhysicalSwitchUpdate(ups);

        final LogicalSwitch ls = new LogicalSwitch(new UUID(0, 111), "ls0", 111,
                                                  "Description");

        // The VtepBroker should process the update, fetching the logical switch
        // to which the update belongs to, since it's necessary to construct
        // the MacLocation
        new Expectations() {{
            vtepDataClient.getTunnelIp(); times = 1; result = vxTunEndpoint;
            vtepDataClient.getLogicalSwitch(new UUID(0, 111));
            times = 1;
            result = ls;
        }};

        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vtepBroker.observableUpdates())
                       .expect(new MacLocation(mac1, macIp1, ls.name(), null))
                       .noErrors()
                       .notCompleted()
                       .subscribe();

        vtepUpdStream.onNext(ups);

        obs.evaluate();
    }

    @Test
    public void testUpdateWithUnknownVxlanTunnelEndpoint() {
        VtepBroker vb = new VtepBroker(vtepDataClient);
        // Even though we publish an update, we expect no MacLocations because
        // the broker doesn't know the vtep's vxlan tunnel IP.
        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vb.observableUpdates());
        obs.noElements()
            .noErrors()
            .notCompleted()
            .subscribe();

        TableUpdates ups = makeLocalMacsUpdate(
            makeUcastLocal(mac1.toString(), vxTunEndpoint.toString()), null
        );
        vtepUpdStream.onNext(ups);

        obs.evaluate();
    }

    /**
     * Covers the case where the VtepBroker hasn't yet been able to intercept
     * the vxlan tunnel IP of the VTEP. In this case, it should not be able
     * to emit MacLocation items even if the VTEP reports changes in the table.
     */
    @Test
    public void testAdvertiseMacsUnknownVxlanTunnelEndpoint()
        throws Exception {
        VtepBroker vb = new VtepBroker(vtepDataClient);
        // Even though we publish an update, we expect no MacLocations because
        // the broker doesn't know the vtep's vxlan tunnel IP.
        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vb.observableUpdates());
        obs.noElements()
            .noErrors()
            .notCompleted()
            .subscribe();

        vb.advertiseMacs();
        obs.evaluate();
    }

    @Test
    public void testAdvertiseMacs() throws Exception {

        new Expectations() {{
            vtepDataClient.getTunnelIp(); times = 2; result = vxTunEndpoint;
        }};

        // Make sure to feed the update with a vxlan tunnel ip so the VtepBroker
        // is able to capture the IP and process MAC updates.
        vtepUpdStream.onNext(tableUpdatesWithTunnelIp());

        final UUID lsId1 = UUID.randomUUID();
        final UUID lsId2 = UUID.randomUUID();
        final UUID loc1 = UUID.randomUUID();
        final UUID loc2 = UUID.randomUUID();

        new Expectations() {{
            vtepDataClient.listUcastMacsLocal();
            times = 1;
            result = Arrays.asList(
                UcastMac.apply(lsId1, sMac1, macIp1, loc1),
                UcastMac.apply(lsId2, sMac2, macIp2, loc2)
            );
        }};

        new Expectations() {{
            vtepDataClient.getLogicalSwitch(withAny(UUID.randomUUID()));
            times = 2;
            result = new Object[] {
                new LogicalSwitch(lsId1, "meh1", 1, "dd"),
                new LogicalSwitch(lsId2, "meh2", 2, "oo")
            };
        }};

        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vtepBroker.observableUpdates());
        obs.expect(new MacLocation(mac1, macIp1, "meh1", vxTunEndpoint),
                   new MacLocation(mac2, macIp2, "meh2", vxTunEndpoint))
           .noErrors()
           .notCompleted()
           .subscribe();

        vtepBroker.advertiseMacs();

        obs.evaluate();
    }

    /**
     * If a Logical Switch is deleted while a MacLocation is processed, we don't
     * want to die: the VtepBroker should just ignore the MacLocation.
     */
    @Test
    public void testAdvertiseMacsResilientToLogicalSwitchDeletions()
        throws Exception {

        VtepBroker vb = new VtepBroker(vtepDataClient);

        new Expectations() {{
            vtepDataClient.getTunnelIp(); times = 3; result = vxTunEndpoint;
        }};

        // Make sure to feed the update with a vxlan tunnel ip so the VtepBroker
        // is able to capture the IP and process MAC updates.
        vtepUpdStream.onNext(tableUpdatesWithTunnelIp());

        final UUID lsId = UUID.randomUUID();
        final UUID loc1 = UUID.randomUUID();
        final UUID loc2 = UUID.randomUUID();

        new Expectations() {{
            vtepDataClient.listUcastMacsLocal();
            times = 1;
            result = Arrays.asList(
                UcastMac.apply(lsId, VtepMAC.fromString(sMac1), loc1),
                UcastMac.apply(lsId, VtepMAC.fromString(sMac2), loc2)
            );
        }};

        new Expectations() {{
            vtepDataClient.getLogicalSwitch(withAny(UUID.randomUUID()));
            times = 2;
            result = new Object[] {
                new LogicalSwitch(lsId, "meh2", 2, "oo"),
                null // the switch goes away on the second call
            };
        }};

        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vb.observableUpdates());
        obs.expect(new MacLocation(mac1, null, "meh2", vxTunEndpoint))
            .noErrors()
            .notCompleted()
            .subscribe();

        vb.advertiseMacs();

        obs.evaluate();
    }

    @Test
    public void testApplyResilientToNullMacLocations() {
        vtepBroker.apply(null); // expect no NPE
    }

    @Test
    public void testPruneUnwantedLogicalSwitches() throws Exception {

        final java.util.UUID boundNetworkId = java.util.UUID.randomUUID();

        final String oldLs =
            bridgeIdToLogicalSwitchName(java.util.UUID.randomUUID());
        final String nonMidoLs = "private_logical_switch";
        final String curLs = bridgeIdToLogicalSwitchName(boundNetworkId);

        // The id of the network that does have a binding
        List<java.util.UUID> ids = Collections.singletonList(boundNetworkId);

        final List<LogicalSwitch> lsList = Arrays.asList(
            new LogicalSwitch(UUID.randomUUID(), oldLs, 1, "midonet unwanted"),
            new LogicalSwitch(UUID.randomUUID(), nonMidoLs, 2, "non midonet"),
            new LogicalSwitch(UUID.randomUUID(), curLs, 3, "midonet wanted")
        );

        new Expectations() {{
            vtepDataClient.listLogicalSwitches();
            result = lsList;
            times = 1;
            vtepDataClient.deleteLogicalSwitch(oldLs);
            result = new Status(StatusCode.SUCCESS);
            times = 1;
        }};

        vtepBroker.pruneUnwantedLogicalSwitches(ids);
    }

    private Ucast_Macs_Local makeUcastLocal(String mac, String ip) {
        OvsDBSet<org.opendaylight.ovsdb.lib.notation.UUID> logSwitch =
            new OvsDBSet<>();
        logSwitch.add(
            new org.opendaylight.ovsdb.lib.notation.UUID(mehUuid.toString()));
        Ucast_Macs_Local val = new Ucast_Macs_Local();
        val.setIpaddr(ip);
        val.setMac(mac);
        val.setLocator(new OvsDBSet<org.opendaylight.ovsdb.lib.notation.UUID>());
        val.setLogical_switch(logSwitch);
        return val;
    }

    private TableUpdates makeLocalMacsUpdate(Ucast_Macs_Local oldRow,
                                             Ucast_Macs_Local newRow) {

        TableUpdate.Row<Ucast_Macs_Local> row = new TableUpdate.Row<>();
        row.setNew(newRow);
        row.setOld(oldRow);

        TableUpdate<Ucast_Macs_Local> upd = new TableUpdate<>();
        upd.set(java.util.UUID.randomUUID().toString(), row);

        TableUpdates ups = new TableUpdates();
        ups.setUcast_Macs_LocalUpdate(upd);
        return ups;
    }

    private TableUpdates tableUpdatesWithTunnelIp() {
        TableUpdates u = new TableUpdates();
        feedPhysicalSwitchUpdate(u);
        return u;
    }

    /**
     * Includes a change to a PhysicalSwitch row representing the VTEP that the
     * VtepClient connects to. This happens in real life upon connection to the
     * VTEP, where the device sends a snapshot of its current DB contents. The
     * VtepBroker uses this to grab the vxlan tunnel IP of the VTEP.
     */
    private void feedPhysicalSwitchUpdate(TableUpdates ups) {
        // Include the initial config fed into the VtepBroker
        TableUpdate.Row<Physical_Switch> psUp = new TableUpdate.Row<>();
        psUp.setOld(null);
        psUp.setNew(physicalSwitch);
        TableUpdate<Physical_Switch> update = new TableUpdate<>();
        update.set("testId", psUp);
        ups.setPhysicalSwitchUpdate(update);
    }

}
