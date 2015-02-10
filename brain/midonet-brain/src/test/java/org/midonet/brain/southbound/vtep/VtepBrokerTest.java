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

import org.junit.Before;
import org.junit.Test;
import org.opendaylight.ovsdb.lib.message.TableUpdate;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.OvsDBSet;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;

import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.services.vxgw.MacLocation;
import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.test.RxTestUtils;
import org.midonet.packets.IPv4Addr;

import mockit.Expectations;
import mockit.Mocked;
import mockit.NonStrictExpectations;

public class VtepBrokerTest {

    private VtepBroker vtepBroker = null;

    /* This Subject gives the Observable to the mock VtepDataClient,
     * can be used to fake updates. */
    private Subject<TableUpdates, TableUpdates> vtepUpdStream =
        PublishSubject.create();

    // This is the management ip of the vtep
    private IPv4Addr mgmtIp = IPv4Addr.fromString("10.1.2.3");
    // This is the management UDP port of the vtep
    private int mgmtPort = 6632;
    // This is the mock vtep's tunnel endpoint
    final IPv4Addr vxTunEndpoint = IPv4Addr.fromString("192.168.0.1");

    private String sMac1 = "aa:bb:cc:dd:ee:01";
    private VtepMAC mac1 = VtepMAC.fromString(sMac1);
    private IPv4Addr macIp1 = IPv4Addr.fromString("10.0.3.1");

    // A sample entry for the test VTEP
    private Physical_Switch physicalSwitch;

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
        OvsDBSet<UUID> ports = new OvsDBSet<>();
        physicalSwitch.setPorts(ports);
        OvsDBSet<String> mgmtIps = new OvsDBSet<>();
        mgmtIps.add(mgmtIp.toString());
        physicalSwitch.setManagement_ips(mgmtIps);
        OvsDBSet<String> tunnelIps = new OvsDBSet<>();
        tunnelIps.add(vxTunEndpoint.toString());
        physicalSwitch.setTunnel_ips(tunnelIps);
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
        final LogicalSwitch ls = new LogicalSwitch(new UUID("meh"), "dsc",
                                                   "ls0", 111);

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
            vtepDataClient.getLogicalSwitch(new UUID("meh"));
            times = 1; result = ls;
            vtepDataClient.getTunnelIp(); times = 1; result = vxTunEndpoint;
        }};

        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vtepBroker.observableUpdates())
                       .expect(new MacLocation(mac1, macIp1, ls.name,
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
            vtepDataClient.getLogicalSwitch(new UUID("meh"));
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

        final LogicalSwitch ls = new LogicalSwitch(new UUID("meh"),
                                                  "Description", "ls0", 111);

        // The VtepBroker should process the update, fetching the logical switch
        // to which the update belongs to, since it's necessary to construct
        // the MacLocation
        new Expectations() {{
            vtepDataClient.getTunnelIp(); times = 1; result = vxTunEndpoint;
            vtepDataClient.getLogicalSwitch(new UUID("meh"));
            times = 1;
            result = ls;
        }};

        RxTestUtils.TestedObservable obs =
            RxTestUtils.test(vtepBroker.observableUpdates())
                       .expect(new MacLocation(mac1, macIp1, ls.name, null))
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
