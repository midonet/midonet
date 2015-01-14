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
package org.midonet.cluster.southbound.vtep;

import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import scala.Option;
import scala.runtime.BoxedUnit;
import scala.util.Try;

import javax.annotation.Nonnull;

import rx.Observable;
import rx.Observer;
import rx.Scheduler;
import rx.schedulers.Schedulers;
import rx.subjects.BehaviorSubject;
import rx.subjects.Subject;

import org.midonet.cluster.data.vtep.VtepConfigException;
import org.midonet.cluster.data.vtep.VtepConnection;
import org.midonet.cluster.data.vtep.VtepDataClient;
import org.midonet.cluster.data.vtep.VtepDataClientClass;
import org.midonet.cluster.data.vtep.VtepStateException;
import org.midonet.cluster.data.vtep.model.LogicalSwitch;
import org.midonet.cluster.data.vtep.model.MacLocation;
import org.midonet.cluster.data.vtep.model.PhysicalPort;
import org.midonet.cluster.data.vtep.model.PhysicalSwitch;
import org.midonet.cluster.data.vtep.model.VtepBinding;
import org.midonet.cluster.data.vtep.model.VtepEndPoint;
import org.midonet.cluster.data.vtep.model.VtepEntry;
import org.midonet.packets.IPv4Addr;
import org.midonet.util.concurrent.NamedThreadFactory;
import org.midonet.vtep.OvsdbVtepData;
import org.midonet.vtep.mock.InMemoryOvsdbVtep;
import org.midonet.vtep.schema.LogicalSwitchTable;
import org.midonet.vtep.schema.PhysicalPortTable;
import org.midonet.vtep.schema.PhysicalSwitchTable;

import static scala.collection.JavaConversions.asJavaCollection;

public class VtepDataClientMock extends VtepDataClientClass {

    private final VtepEndPoint endPoint;
    private final InMemoryOvsdbVtep vtep;
    private final PhysicalPortTable portTable;
    private final LogicalSwitchTable lsTable;

    private final OvsdbVtepData data;

    private final ExecutorService executor = Executors.newSingleThreadExecutor(
        new NamedThreadFactory("VtepDataClientMock"));
    private final Scheduler scheduler = Schedulers.from(executor);

    private final IPv4Addr mgmtIp;
    private final int mgmtPort;

    private final Map<String, UUID> physicalPortIds = new HashMap<>();

    protected boolean connected = false;

    private final Subject<State$.Value, State$.Value> stateSubject =
        BehaviorSubject.create(State$.MODULE$.DISCONNECTED());
    private final Observable<State$.Value> stateObservable =
        stateSubject.asObservable().subscribeOn(scheduler).observeOn(scheduler);

    public VtepDataClientMock(String ip, final int mgmtPort,
                              String name, String desc,
                              Set<String> tunnelIps,
                              Collection<String> portNames) {
        this.mgmtIp = IPv4Addr.fromString(ip);
        this.mgmtPort = mgmtPort;
        this.endPoint = VtepEndPoint.apply(mgmtIp, mgmtPort);
        this.vtep = new InMemoryOvsdbVtep(mgmtIp, mgmtPort);
        this.portTable = new PhysicalPortTable(vtep.databaseSchema());
        this.lsTable = new LogicalSwitchTable(vtep.databaseSchema());

        PhysicalSwitchTable psTable =
            new PhysicalSwitchTable(vtep.databaseSchema());

        Set<UUID> portIds = new HashSet<>();
        for (String pn : portNames) {
            PhysicalPort p =
                PhysicalPort.apply(UUID.randomUUID(), pn, pn + "-desc");
            vtep.putEntry(portTable, p, PhysicalPort.class);
            physicalPortIds.put(p.name(), p.uuid());
            portIds.add(p.uuid());
        }

        Set<IPv4Addr> ipSet = new HashSet<>();
        for (String ipStr : tunnelIps) {
            ipSet.add(IPv4Addr.fromString(ipStr));
        }
        PhysicalSwitch ps = PhysicalSwitch.apply(
            UUID.randomUUID(), name, desc,
            portIds, Collections.singleton(mgmtIp), ipSet);

        vtep.putEntry(psTable, ps, PhysicalSwitch.class);

        data = new OvsdbVtepData(endPoint, vtep.getHandle(),
                                 vtep.databaseSchema(), executor);
    }

    @Override
    public IPv4Addr getManagementIp() {
        return mgmtIp;
    }

    @Override
    public Option<IPv4Addr> vxlanTunnelIp() {
        return data.vxlanTunnelIp();
    }

    @Override
    public int getManagementPort() {
        return mgmtPort;
    }

    public VtepDataClient connect(IPv4Addr mgmtIp, int port)
        throws VtepStateException {
        if (!this.mgmtIp.equals(mgmtIp) || this.mgmtPort != port)
            throw new VtepStateException(new VtepEndPoint(mgmtIp, port),
                                         "Could not complete connection.");
        this.connect(UUID.randomUUID());
        return this;
    }

    @Override
    public void connect(UUID user) {
        if (!connected) {
            connected = true;
            stateSubject.onNext(State$.MODULE$.CONNECTED());
            stateSubject.onNext(State$.MODULE$.READY());
        }
    }

    @Override
    public void disconnect(UUID user) {
        assertConnected();
        connected = false;
        stateSubject.onNext(State$.MODULE$.DISCONNECTING());
        stateSubject.onNext(State$.MODULE$.DISCONNECTED());
    }

    @Override
    public void dispose() {
        connected = false;
        stateSubject.onNext(State$.MODULE$.DISPOSED());
    }

    @Override
    public State$.Value getState() {
        return stateObservable.take(1).toBlocking().first();
    }

    @Override
    public Observable<State$.Value> observable() {
        return stateObservable;
    }

    @Override
    public Option<VtepConnection.VtepHandle> getHandle() {
        // no test use this
        return Option.apply((VtepConnection.VtepHandle) null);
    }

    @Override
    public Observable<MacLocation> macLocalUpdates() {
        assertConnected();
        return data.macLocalUpdates();
    }

    @Override
    public @Nonnull scala.collection.immutable.Set<PhysicalSwitch>
        listPhysicalSwitches() {
        assertConnected();
        return data.listPhysicalSwitches();
    }

    @Override
    public @Nonnull scala.collection.immutable.Set<LogicalSwitch>
        listLogicalSwitches() {
        assertConnected();
        return data.listLogicalSwitches();
    }

    @Override
    public @Nonnull scala.collection.immutable.Set<PhysicalPort>
        physicalPorts(UUID psUuid) {
        assertConnected();
        return data.physicalPorts(psUuid);
    }

    @Override
    public scala.collection.Seq<MacLocation> currentMacLocal() {
        // not used
        return data.currentMacLocal();
    }

    @Override
    public scala.collection.Seq<MacLocation> currentMacLocal(UUID nwId) {
        // not used
        return data.currentMacLocal(nwId);
    }

    @Override
    public Try<LogicalSwitch> ensureLogicalSwitch(@Nonnull UUID networkId,
                                                  int vni) {
        assertConnected();
        return data.ensureLogicalSwitch(networkId, vni);
    }

    public void createNonMidonetSwitch(String lsName, int vni) {
        assertConnected();
        LogicalSwitch ls = findLs(lsName);
        if (ls == null) {
            ls = LogicalSwitch.apply(lsName, vni, lsName + "-desc");
            vtep.putEntry(lsTable, ls, LogicalSwitch.class);
        }
    }

    @Override
    public Try<BoxedUnit> createBinding(@Nonnull String portName, short vlan,
                                   UUID networkId) {
        assertConnected();
        return data.createBinding(portName, vlan, networkId);
    }

    public void createNonMidonetBinding(@Nonnull String portName,
                                        short vlan, String lsName)
        throws VtepConfigException {
        assertConnected();
        UUID pId = physicalPortIds.get(portName);
        PhysicalPort pp = (pId == null)? null:
                          (PhysicalPort)vtep.getTable(portTable).getOrElse(pId, null);
        if (pp == null) {
            throw new VtepConfigException(
                endPoint, "Physical port " + portName + " not found");
        }
        LogicalSwitch ls = findLs(lsName);
        if (ls == null) {
            throw new VtepConfigException(
                endPoint, "Logical switch " + lsName + " not found");
        }

        PhysicalPort port = pp.newBinding((int)vlan, ls.uuid());
        vtep.putEntry(portTable, port, PhysicalPort.class);
    }

    @Override
    public Try<BoxedUnit> ensureBindings(
        @Nonnull UUID networkId,
        @Nonnull scala.collection.Iterable<VtepBinding> bindings) {
        return data.ensureBindings(networkId, bindings);
    }

    @Override
    public Try<BoxedUnit> removeLogicalSwitch(@Nonnull UUID networkId) {
        assertConnected();
        return data.removeLogicalSwitch(networkId);
    }

    @Override
    public Observer<MacLocation> macRemoteUpdater() {
        // Not used
        return data.macRemoteUpdater();
    }

    @Override
    public Try<BoxedUnit> removeBinding(@Nonnull String portName, short vlan) {
        assertConnected();
        return data.removeBinding(portName, vlan);
    }

    private void assertConnected() {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected");
    }

    private LogicalSwitch findLs(String lsName) {
        for (VtepEntry e : asJavaCollection(vtep.getTable(lsTable).values())) {
            LogicalSwitch ls = (LogicalSwitch)e;
            if (ls.name().equals(lsName)) {
                return ls;
            }
        }
        return null;
    }

}
