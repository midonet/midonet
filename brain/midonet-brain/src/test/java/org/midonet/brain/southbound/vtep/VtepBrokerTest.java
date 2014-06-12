/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.Arrays;

import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;

import org.junit.Before;
import org.junit.Test;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.OvsDBSet;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;

import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.org.midonet.brain.test.RxTestUtils;
import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.services.vxgw.VxLanPeerSyncException;
import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

public class VtepBrokerTest {

    private VtepBroker vtepBroker = null;

    /* This Subject gives the Observable to the mock VtepDataClient,
     * can be used to fake updates. */
    private Subject<TableUpdates, TableUpdates> vtepUpdStream =
        PublishSubject.create();

    private String logicalSwitchName = "ls";
    // This is the tunnel IP of a fake midonet host
    private IPv4Addr midoVxTunIp = IPv4Addr.fromString("119.15.113.90");
    // This is the management ip of the vtep
    private IPv4Addr mgmtIp = IPv4Addr.fromString("10.1.2.3");
    // This is the management UDP port of the vtep
    private int mgmtPort = 6632;
    // This is the mock vtep's tunnel endpoint
    final IPv4Addr vxTunEndpoint = IPv4Addr.fromString("192.168.0.1");

    // MACs used in tests
    private String sMac1 = "aa:bb:cc:dd:ee:01";
    private MAC mac1 = MAC.fromString(sMac1);

    private String sMac2 = "aa:bb:cc:dd:ee:02";
    private MAC mac2 = MAC.fromString(sMac2);

    // A sample entry for the test VTEP
    private Physical_Switch physicalSwitch;

    @Mocked
    private VtepDataClient vtepDataClient;

    @Before
    public void before() {
        new NonStrictExpectations() {{
            vtepDataClient.getManagementIp(); result = mgmtIp;
            vtepDataClient.getManagementPort(); result = mgmtPort;
            vtepDataClient.observableUpdates();
            result = vtepUpdStream.asObservable();
        }};
        vtepBroker = new VtepBroker(this.vtepDataClient);
        physicalSwitch = new Physical_Switch();
        physicalSwitch.setDescription("description");
        physicalSwitch.setName("vtep");
        OvsDBSet<UUID> ports = new OvsDBSet<>();
        physicalSwitch.setPorts(ports);
        OvsDBSet<String> mgmtIps = new OvsDBSet<>();
        mgmtIps.add(mgmtIp.toString());
        physicalSwitch.setManagement_ips(mgmtIps);
        OvsDBSet<String> tunnelIps = new OvsDBSet<>();
        tunnelIps.add(vxTunEndpoint.toString());
        physicalSwitch.setTunnel_ips(tunnelIps);
    }

    @Test
    public void testBrokerAppliesUpdate() {
        new Expectations() {{
            vtepDataClient.addUcastMacRemote(
                logicalSwitchName, mac1.toString(), midoVxTunIp.toString());
            times = 1;
            result = new Status(StatusCode.SUCCESS);
        }};

        vtepBroker.apply(new MacLocation(mac1, logicalSwitchName, midoVxTunIp));
    }

    @Test(expected = VxLanPeerSyncException.class)
    public void testBrokerThrowsOnFailedUpdate() {
        new Expectations() {{
            vtepDataClient.addUcastMacRemote(
                logicalSwitchName, mac1.toString(), midoVxTunIp.toString());
            times = 1;
            result = new Status(StatusCode.BADREQUEST);
        }};

        vtepBroker.apply(new MacLocation(mac1, logicalSwitchName, midoVxTunIp));
    }

    @Test
    public void testBrokerDeletesUcastMacRemote() {
        new Expectations() {{
            vtepDataClient.delUcastMacRemote(mac1.toString(), logicalSwitchName);
            result = new Status(StatusCode.SUCCESS);
            times = 1;
        }};
        vtepBroker.apply(new MacLocation(mac1, logicalSwitchName, null));
    }

    /**
     * This one will need a bit of refactoring, setting a value then changing
     * it and verifying the calls, whatever they do.
     */
    @Test
    public void testUpdateHandlerUpdatesUcastMacRemote() {
        new Expectations() {{
            vtepDataClient.addUcastMacRemote(logicalSwitchName, mac1.toString(),
                                             midoVxTunIp.toString());
            times = 1;
            result = new Status(StatusCode.SUCCESS);
        }};
        vtepBroker.apply(new MacLocation(mac1, logicalSwitchName, midoVxTunIp));
    }

    /**
     * Tests the processing of an update from the VTEP corresponding to a new
     * mac entry being added to the Ucast_macs_local table, which should
     * generate a corresponding MacLocation that reports the mac being located
     * at the VTEP's vxlan tunnel ip.
     */
    @Test
    public void testObservableUpdatesMacAddition() {

        // Our logical switch
        final LogicalSwitch ls = new LogicalSwitch(new UUID("meh"), "dsc",
                                                   "ls0", 111);

        // Prepare an update consisting of a new row being added
        TableUpdates ups = makeLocalMacsUpdate(
            null, makeUcastLocal(mac1.toString(), vxTunEndpoint.toString())
        );
        feedPhysicalSwitchUpdate(ups);

        // The VtepBroker should process the update doing two things:
        // - Intercept the initial feeding into the Physical_Switch, grabbing
        //   the vtep's tunnel IP
        // - Process the Ucast_Macs_Local update, use the tunnel IP in the
        //   resulting MacLocation emitted from the observable.
        new Expectations() {{
            vtepDataClient.getLogicalSwitch(new UUID("meh"));
            times = 1; result = ls;
        }};

        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vtepBroker.observableUpdates())
                       .expect(new MacLocation(mac1, ls.name, vxTunEndpoint))
                       .noErrors()
                       .notCompleted()
                       .subscribe();

        vtepUpdStream.onNext(ups);

        obs.evaluate();
    }

    /**
     * Tests the processing of an update from the VTEP corresponding to a mac
     * entry being removed from the Ucast_macs_local table, which should
     * generate a corresponding MacLocation that reports the mac not being
     * located anymore at the VTEP's vxlan tunnel ip.
     */
    @Test
    public void testObservableUpdatesMacRemoval() {

        // Prepare an update consisting of a new row being added
        TableUpdates ups = makeLocalMacsUpdate(
            makeUcastLocal(mac1.toString(), vxTunEndpoint.toString()), null
        );
        feedPhysicalSwitchUpdate(ups);

        final LogicalSwitch ls = new LogicalSwitch(new UUID("meh"),
                                                  "Description", "ls0", 111);

        // The VtepBroker should process the update, fetching the logical switch
        // to which the update belongs to, since it's necessary to construct
        // the MacLocation
        new Expectations() {{
            vtepDataClient.getLogicalSwitch(new UUID("meh"));
            times = 1;
            result = ls;
        }};

        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vtepBroker.observableUpdates())
                       .expect(new MacLocation(mac1, ls.name, null))
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
    public void testAdvertiseMacsUnknownVxlanTunnelEndpoint() {
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
    public void testAdvertiseMacs() {

        VtepBroker vb = new VtepBroker(vtepDataClient);

        // Make sure to feed the update with a vxlan tunnel ip so the VtepBroker
        // is able to capture the IP and process MAC updates.
        vtepUpdStream.onNext(tableUpdatesWithTunnelIp());

        final UUID lsId1 = new UUID("blah1");
        final UUID lsId2 = new UUID("blah2");

        new Expectations() {{
            vtepDataClient.listUcastMacsLocal();
            times = 1;
            result = Arrays.asList(
                new UcastMac(sMac1, lsId1, new UUID("loc1"), null),
                new UcastMac(sMac2, lsId2, new UUID("loc2"), null)
            );
        }};

        new Expectations() {{
            vtepDataClient.getLogicalSwitch(withAny(new UUID("")));
            times = 2;
            result = new Object[] {
                new LogicalSwitch(lsId1, "dd", "meh1", 1),
                new LogicalSwitch(lsId2, "oo", "meh2", 2)
            };
        }};

        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vb.observableUpdates());
        obs.expect(new MacLocation(mac1, "meh1", vxTunEndpoint),
                   new MacLocation(mac2, "meh2", vxTunEndpoint))
           .noErrors()
           .notCompleted()
           .subscribe();

        vb.advertiseMacs();

        obs.evaluate();
    }

    private Ucast_Macs_Local makeUcastLocal(String mac, String ip) {
        OvsDBSet<UUID> logSwitch = new OvsDBSet<>();
        logSwitch.add(new UUID("meh"));
        Ucast_Macs_Local val = new Ucast_Macs_Local();
        val.setIpaddr(ip);
        val.setMac(mac);
        val.setLocator(new OvsDBSet<UUID>());
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
     * @param ups
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
