/**
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.ArrayList;

import mockit.Expectations;
import mockit.Mocked;
import org.junit.Before;
import org.junit.Test;
import org.midonet.brain.org.midonet.brain.test.RxTestUtils;
import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.services.vxgw.VxLanPeerSyncException;
import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.OvsDBSet;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;
import rx.subjects.BehaviorSubject;
import rx.subjects.Subject;

public class VtepBrokerTest {

    private VtepBroker vtepBroker = null;

    private String logicalSwitchName = "ls";
    private IPv4Addr ip = IPv4Addr.fromString("119.15.113.90");

    // A random mac to be used in tests
    private String sMac = "aa:bb:cc:dd:ee:01";
    private MAC mac = MAC.fromString(sMac);

    // This is the mock vtep's tunnel endpoint
    final IPv4Addr vxTunEndpoint = IPv4Addr.fromString("192.168.0.1");


    @Mocked
    private VtepDataClient vtepDataClient;

    @Before
    public void before() {
        // The VtepBroker should first describe the VTEP it connects to
        new Expectations() {{
            vtepDataClient.describe();
            times = 1;
            result = mockPhysicalSwitch("10.1.2.3", vxTunEndpoint.toString());
        }};
        vtepBroker = new VtepBroker(this.vtepDataClient);
    }

    @Test
    public void testBrokerAppliesUpdate() {
        new Expectations() {{
            vtepDataClient.addUcastMacRemote(
                logicalSwitchName, mac.toString(), ip.toString());
            times = 1;
            result = new Status(StatusCode.SUCCESS);
        }};

        vtepBroker.apply(new MacLocation(mac, logicalSwitchName, ip));
    }

    @Test(expected = VxLanPeerSyncException.class)
    public void testBrokerThrowsOnFailedUpdate() {
        new Expectations() {{
            vtepDataClient.addUcastMacRemote(
                logicalSwitchName, mac.toString(), ip.toString());
            times = 1;
            result = new Status(StatusCode.BADREQUEST);
        }};

        vtepBroker.apply(new MacLocation(mac, logicalSwitchName, ip));
    }

    @Test
    public void testBrokerDeletesUcastMacRemote() {
        new Expectations() {{
            vtepDataClient.delUcastMacRemote(mac.toString(), logicalSwitchName);
            result = new Status(StatusCode.SUCCESS);
            times = 1;
        }};
        vtepBroker.apply(new MacLocation(mac, logicalSwitchName, null));
    }

    /**
     * This one will need a bit of refactoring, setting a value then changing
     * it and verifying the calls, whatever they do.
     */
    @Test
    public void testUpdateHandlerUpdatesUcastMacRemote() {
        new Expectations() {{
            vtepDataClient.addUcastMacRemote(logicalSwitchName, mac.toString(),
                                             ip.toString());
            times = 1;
            result = new Status(StatusCode.SUCCESS);
        }};
        vtepBroker.apply(new MacLocation(mac, logicalSwitchName, ip));
    }

    /**
     * Tests the processing of an update from the VTEP corresponding to a new
     * mac entry being added to the Ucast_macs_local table, which should
     * generate a corresponding MacLocation that reports the mac being located
     * at the VTEP's vxlan tunnel ip.
     */
    @Test
    public void testObservableUpdatesMacAddition() {

        // Prepare an update consisting of a new row being added
        TableUpdates ups = makeLocalMacsUpdate(
            null,
            makeUcastLocal(mac.toString(), "122.11.2.2")
        );

        final LogicalSwitch ls = new LogicalSwitch(new UUID("meh"),
                                                  "Description", "ls0", 111);

        // This BehaviourSubject will replay the elements inserted for every
        // subscriber, so get it ready.
        final Subject<TableUpdates, TableUpdates> s =
            BehaviorSubject.create(ups);

        // The VtepBroker should subscribe to updates from the VTEP immediately
        new Expectations() {{
            vtepDataClient.observableUpdates();
            times = 1;
            result = s.asObservable();
        }};

        // The VtepBroker should process the update, fetching the logical switch
        // to which the update belongs to, since it's necessary to construct
        // the MacLocation
        new Expectations() {{
            vtepDataClient.getLogicalSwitch(new UUID("meh"));
            times = 1;
            result = ls;
        }};

        // All set, let's trigger the test:
        RxTestUtils.test(vtepBroker.observableUpdates())
                   .expect(new MacLocation(mac, ls.name, vxTunEndpoint))
                   .noErrors()
                   .notCompleted()
                   .subscribe()
                   .evaluate();
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
            makeUcastLocal(mac.toString(), "122.11.2.2"),
            null
        );

        // This is the vtep's tunnel endpoint where the MAC was located before
        // its removal.
        final IPv4Addr vxTunEndpoint = IPv4Addr.fromString("192.168.0.1");

        final LogicalSwitch ls = new LogicalSwitch(new UUID("meh"),
                                                  "Description", "ls0", 111);

        // This BehaviourSubject will replay the elements inserted for every
        // subscriber, so get it ready.
        final Subject<TableUpdates, TableUpdates> s =
            BehaviorSubject.create(ups);

        // The VtepBroker should subscribe to updates from the VTEP immediately
        new Expectations() {{
            vtepDataClient.observableUpdates();
            times = 1;
            result = s.asObservable();
        }};

        // The VtepBroker should process the update, fetching the logical switch
        // to which the update belongs to, since it's necessary to construct
        // the MacLocation
        new Expectations() {{
            vtepDataClient.getLogicalSwitch(new UUID("meh"));
            times = 1;
            result = ls;
        }};

        // All set, let's trigger the test:
        RxTestUtils.test(vtepBroker.observableUpdates())
                   .expect(new MacLocation(mac, ls.name, null))
                   .noErrors()
                   .notCompleted()
                   .subscribe()
                   .evaluate();
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

    /**
     * Make a mock physical switch with the given tunnel and management ips,
     * all other values hardcoded.
     */
    private PhysicalSwitch mockPhysicalSwitch(String mgmtIp, String tunIp) {
        final OvsDBSet<String> vtepMgmtIps = new OvsDBSet<>();
        final OvsDBSet<String> vtepTunIps = new OvsDBSet<>();
        vtepMgmtIps.add(mgmtIp);
        vtepTunIps.add(tunIp);
        return new PhysicalSwitch(
            new UUID("mock"), "mock", "mock_physical_switch",
            new ArrayList<String>(), vtepMgmtIps, vtepTunIps
        );
    }

}
