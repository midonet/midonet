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

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import scala.Option;
import scala.runtime.BoxedUnit;
import scala.util.Failure;
import scala.util.Success;
import scala.util.Try;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;


import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;

import rx.Observable;
import rx.Observer;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.cluster.data.vtep.VtepConfigException;
import org.midonet.cluster.data.vtep.VtepConnection;
import org.midonet.cluster.data.vtep.VtepDataClient;
import org.midonet.cluster.data.vtep.VtepStateException;
import org.midonet.cluster.data.vtep.model.LogicalSwitch;
import org.midonet.cluster.data.vtep.model.MacLocation;
import org.midonet.cluster.data.vtep.model.McastMac;
import org.midonet.cluster.data.vtep.model.PhysicalPort;
import org.midonet.cluster.data.vtep.model.PhysicalSwitch;
import org.midonet.cluster.data.vtep.model.UcastMac;
import org.midonet.cluster.data.vtep.model.VtepBinding;
import org.midonet.cluster.data.vtep.model.VtepEndPoint;
import org.midonet.cluster.data.vtep.model.VtepMAC;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static scala.collection.JavaConversions.collectionAsScalaIterable;
import static scala.collection.JavaConversions.mapAsJavaMap;
import static scala.collection.JavaConversions.setAsJavaSet;
import static org.midonet.cluster.data.vtep.VtepConnection.State.DISCONNECTED;
import static org.midonet.cluster.data.vtep.VtepConnection.State.READY;

public class VtepDataClientMock implements VtepDataClient {

    protected String mgmtIp;
    protected int mgmtPort;
    protected Set<String> tunnelIps;
    protected boolean connected = false;
    protected VtepEndPoint endPoint;

    protected final Map<String, PhysicalSwitch> physicalSwitches =
            new HashMap<>();
    protected final Map<String, LogicalSwitch> logicalSwitches =
            new HashMap<>();
    protected final Map<String, PhysicalPort> physicalPorts = new HashMap<>();
    protected final Map<String, UUID> logicalSwitchUuids = new HashMap<>();
    protected final Map<String, UUID> locatorUuids = new HashMap<>();
    protected final Map<String, McastMac> mcastMacsLocal = new HashMap<>();
    protected final ListMultimap<String, McastMac> mcastMacsRemote =
        ArrayListMultimap.create();
    protected final Map<String, UcastMac> ucastMacsLocal = new HashMap<>();
    // FIXME: The *Macs* maps above shouls probably be multimaps, also.
    // Changing them may not be important for the current tests, but we may
    // need it in the future.
    protected final Map<String, Set<UcastMac>> ucastMacsRemote =
        new HashMap<>();

    private final Subject<State$.Value, State$.Value> stateSubject =
        PublishSubject.create();

    public VtepDataClientMock(String mgmtIp, int mgmtPort,
                              String name, String desc,
                              Set<String> tunnelIps,
                              Collection<String> portNames) {
        this.mgmtIp = mgmtIp;
        this.mgmtPort = mgmtPort;
        this.tunnelIps = tunnelIps;
        this.endPoint = VtepEndPoint.apply(mgmtIp, mgmtPort);
        Set<IPv4Addr> tunnels = new HashSet<>();
        for (String str: tunnelIps) {
            tunnels.add(IPv4Addr.fromString(str));
        }

        Set<UUID> portIds = new HashSet<>();
        for (String portName : portNames) {
            PhysicalPort pp = PhysicalPort.apply(UUID.randomUUID(),
                                                 portName, portName + "-desc");
            physicalPorts.put(portName, pp);
            portIds.add(pp.uuid());
        }

        PhysicalSwitch ps = PhysicalSwitch.apply(
            UUID.randomUUID(), name, desc, portIds,
            Sets.newHashSet(IPv4Addr.fromString(mgmtIp)), tunnels);
        physicalSwitches.put(mgmtIp, ps);

    }

    @Override
    public IPv4Addr getManagementIp() {
        return IPv4Addr.fromString(mgmtIp);
    }

    @Override
    public Option<IPv4Addr> vxlanTunnelIp() {
        return tunnelIps.isEmpty() ? Option.apply((IPv4Addr)null) :
               Option.apply(IPv4Addr.fromString(tunnelIps.iterator().next()));
    }

    @Override
    public int getManagementPort() {
        return mgmtPort;
    }

    public VtepDataClient connect(IPv4Addr mgmtIp, int port)
        throws VtepStateException {
        if (!this.mgmtIp.equals(mgmtIp.toString()) || this.mgmtPort != port)
            throw new VtepStateException(new VtepEndPoint(mgmtIp, port),
                                         "Could not complete connection.");
        this.connect(UUID.randomUUID());
        return this;
    }

    @Override
    public void connect(UUID user) {
        if (!connected) {
            connected = true;
            stateSubject.onNext(State.READY());
        }
    }

    @Override
    public void disconnect(UUID user) {
        assertConnected();
        connected = false;
        stateSubject.onNext(State.DISCONNECTED());
    }

    @Override
    public void dispose() {
        connected = false;
        stateSubject.onNext(State.DISPOSED());
    }

    @Override
    public State$.Value getState() {
        return connected ? READY() : DISCONNECTED();
    }

    @Override
    public Observable<State$.Value> observable() {
        return stateSubject.asObservable();
    }

    @Override
    public Option<VtepConnection.VtepHandle> getHandle() {
        // no test use this
        return Option.apply((VtepConnection.VtepHandle) null);
    }

    @Override
    public Observable<MacLocation> macLocalUpdates() {
        assertConnected();
        return Observable.never(); // No tests use this for now.
    }

    @Override
    public @Nonnull scala.collection.immutable.Set<PhysicalSwitch>
        listPhysicalSwitches() {
        assertConnected();
        return collectionAsScalaIterable(physicalSwitches.values()).toSet();
    }

    @Override
    public @Nonnull scala.collection.immutable.Set<LogicalSwitch>
        listLogicalSwitches() {
        assertConnected();
        return collectionAsScalaIterable(logicalSwitches.values()).toSet();
    }

    @Override
    public @Nonnull scala.collection.immutable.Set<PhysicalPort>
        physicalPorts(UUID psUuid) {
        assertConnected();
        return collectionAsScalaIterable(physicalPorts.values()).toSet();
    }

    @Override
    public scala.collection.Seq<MacLocation> currentMacLocal() {
        // not used
        return null;
    }

    @Override
    public scala.collection.Seq<MacLocation> currentMacLocal(UUID nwId) {
        // not used
        return null;
    }

    public @Nonnull List<McastMac> listMcastMacsLocal() {
        assertConnected();
        return new ArrayList<>(mcastMacsLocal.values());
    }

    public @Nonnull List<McastMac> listMcastMacsRemote() {
        assertConnected();
        return new ArrayList<>(mcastMacsRemote.values());
    }

    public @Nonnull List<UcastMac> listUcastMacsLocal() {
        assertConnected();
        return new ArrayList<>(ucastMacsLocal.values());
    }

    public @Nonnull List<UcastMac> listUcastMacsRemote() {
        assertConnected();
        List<UcastMac> entryList = new ArrayList<>();
        for (String m: ucastMacsRemote.keySet()) {
            entryList.addAll(ucastMacsRemote.get(m));
        }
        return entryList;
    }

    public LogicalSwitch getLogicalSwitch(@Nonnull UUID lsId) {
        assertConnected();
        for (LogicalSwitch ls : this.logicalSwitches.values()) {
            if (ls.uuid().equals(lsId)) {
                return ls;
            }
        }
        return null;
    }

    public LogicalSwitch getLogicalSwitch(@Nonnull String lsName) {
        assertConnected();
        return this.logicalSwitches.get(lsName);
    }

    @Override
    public Try<LogicalSwitch> ensureLogicalSwitch(@Nonnull UUID networkId,
                                                  int vni) {
        assertConnected();
        String lsName = LogicalSwitch.networkIdToLsName(networkId);
        LogicalSwitch ls = logicalSwitches.get(lsName);
        if (ls == null) {
            UUID uuid = UUID.randomUUID();
            ls = LogicalSwitch.apply(networkId, vni, lsName + "-desc");
            logicalSwitches.put(lsName, ls);
            logicalSwitchUuids.put(lsName, ls.uuid());
        }
        return Success.apply(ls);
    }

    public void createNonMidonetSwitch(String lsName, int vni) {
        assertConnected();
        LogicalSwitch ls = logicalSwitches.get(lsName);
        if (ls == null) {
            ls = LogicalSwitch.apply(lsName, vni, lsName + "-desc");
            logicalSwitches.put(lsName, ls);
            logicalSwitchUuids.put(lsName, ls.uuid());
        }
    }

    @Override
    public Try<BoxedUnit> createBinding(@Nonnull String portName, short vlan,
                                   UUID networkId) {
        assertConnected();
        PhysicalPort pp = physicalPorts.get(portName);
        if (pp == null) {
            return Failure.apply(new VtepConfigException(
                endPoint, "Physical port " + portName + " not found"));
        }
        String lsName = LogicalSwitch.networkIdToLsName(networkId);
        LogicalSwitch ls = logicalSwitches.get(lsName);
        if (ls == null) {
            return Failure.apply(new VtepConfigException(
                endPoint, "Logical switch " + lsName + " not found"));
        }

        Map<Integer, UUID> bindings = new HashMap<>();
        bindings.putAll(mapAsJavaMap(pp.vlanBindings()));
        bindings.put((int)vlan, logicalSwitchUuids.get(lsName));

        PhysicalPort updated = PhysicalPort.apply(
            pp.uuid(), pp.name(), pp.description(), bindings,
            mapAsJavaMap(pp.vlanStats()), setAsJavaSet(pp.portFaultStatus()));
        physicalPorts.put(portName, updated);

        return Success.apply(BoxedUnit.UNIT);
    }

    public void createNonMidonetBinding(@Nonnull String portName,
                                        short vlan, String lsName)
        throws VtepConfigException {
        assertConnected();
        PhysicalPort pp = physicalPorts.get(portName);
        if (pp == null) {
            throw new VtepConfigException(
                endPoint, "Physical port " + portName + " not found");
        }
        LogicalSwitch ls = logicalSwitches.get(lsName);
        if (ls == null) {
            throw new VtepConfigException(
                endPoint, "Logical switch " + lsName + " not found");
        }

        Map<Integer, UUID> bindings = new HashMap<>();
        bindings.putAll(mapAsJavaMap(pp.vlanBindings()));
        bindings.put((int)vlan, logicalSwitchUuids.get(lsName));

        PhysicalPort updated = PhysicalPort.apply(
            pp.uuid(), pp.name(), pp.description(), bindings,
            mapAsJavaMap(pp.vlanStats()), setAsJavaSet(pp.portFaultStatus()));
        physicalPorts.put(portName, updated);
    }

    @Override
    public Try<BoxedUnit> ensureBindings(
        @Nonnull UUID networkId,
        @Nonnull scala.collection.Iterable<VtepBinding> bindings) {
        return Failure.apply(new UnsupportedOperationException());
    }

    @Override
    public Try<BoxedUnit> removeLogicalSwitch(@Nonnull UUID networkId) {
        assertConnected();

        String lsName = LogicalSwitch.networkIdToLsName(networkId);
        LogicalSwitch ls = logicalSwitches.remove(lsName);

        if (ls == null) {
            return Failure.apply(new VtepConfigException(
                endPoint, "Logical switch " + lsName + " not found"));
        }

        final UUID lsId = logicalSwitchUuids.remove(lsName);
        if (lsId == null) {
            return Failure.apply(
                new IllegalStateException("Logical switch found, but not in "+
                                          "the ids map: most likely a bug in"+
                                          "VtepDataClientMock"));
        }

        // Remove all bindings to the given logical switch
        for (String portName: physicalPorts.keySet()) {
            PhysicalPort pp = physicalPorts.get(portName);
            Map<Integer, UUID> bindings = new HashMap<>();
            for (Map.Entry<Integer, UUID> e:
                mapAsJavaMap(pp.vlanBindings()).entrySet()) {
                if (e.getValue() != lsId)
                    bindings.put(e.getKey(), e.getValue());
            }
            PhysicalPort updated = PhysicalPort.apply(
                pp.uuid(), pp.name(), pp.description(), bindings,
                mapAsJavaMap(pp.vlanStats()),
                setAsJavaSet(pp.portFaultStatus()));
            physicalPorts.put(portName, updated);
        }

        return Success.apply(BoxedUnit.UNIT);
    }

    @Override
    public Observer<MacLocation> macRemoteUpdater() {
        // Not used
        return new Observer<MacLocation>() {
            @Override public void onCompleted() {doFail();}
            @Override public void onError(Throwable e) {doFail();}
            @Override public void onNext(MacLocation macLocation) {doFail();}
            private void doFail() throws UnsupportedOperationException {
                throw new UnsupportedOperationException();
            }
        };
    }

    public void addUcastMacRemote(@Nonnull String lsName, @Nonnull MAC mac,
                                  @Nullable IPv4Addr macIp,
                                  @Nonnull IPv4Addr tunnelEndPoint)
        throws VtepConfigException {
        assertConnected();

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            throw new VtepConfigException(endPoint, "Logical switch not found");

        UcastMac ucastMac = UcastMac.apply(
            lsUuid, VtepMAC.fromMac(mac), macIp,
            getLocatorUuid(tunnelEndPoint.toString()));

        Set<UcastMac> set = ucastMacsRemote.get(mac.toString());
        if (set == null) {
            set = new HashSet<>();
            ucastMacsRemote.put(mac.toString(), set);
        }
        set.add(ucastMac);
    }

    public void addMcastMacRemote(@Nonnull String lsName,
                                  @Nonnull VtepMAC mac,
                                  @Nonnull IPv4Addr ip)
        throws VtepConfigException {
        assertConnected();

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            throw new VtepConfigException(endPoint, "Logical switch not found");

        // HACK: This just gets a locator for the specified IP address and
        // uses it as a locator set UUID. If this mock ever actually needs
        // to distinguish between locators and locator sets, this will
        // need to change.
        McastMac mcastMac = McastMac.apply(
            lsUuid, mac, getLocatorUuid(ip.toString()));
        mcastMacsRemote.put(mac.toString(), mcastMac);
    }

    public void deleteUcastMacRemote(@Nonnull String lsName, @Nonnull MAC mac,
                                     @Nonnull IPv4Addr macIp)
        throws VtepConfigException {
        assertConnected();

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            throw new VtepConfigException(endPoint, "Logical switch not found");

        Set<UcastMac> set = this.ucastMacsRemote.get(mac.toString());
        if (set == null)
            return;

        for (UcastMac umr: set) {
            if (umr.mac().mac().equals(mac) &&
                umr.logicalSwitchId().equals(lsUuid) &&
                macIp.equals(umr.ipAddr())) {
                set.remove(umr);
                if (set.isEmpty()) {
                    this.ucastMacsRemote.remove(mac.toString());
                }
            }
        }
    }

    public void deleteAllUcastMacRemote(@Nonnull String lsName,
                                        @Nonnull MAC mac)
        throws VtepConfigException {
        assertConnected();

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            throw new VtepConfigException(endPoint, "Logical switch not found");

        Set<UcastMac> set = this.ucastMacsRemote.get(mac.toString());
        if (set == null)
            return;

        for (UcastMac umr: set) {
            if (umr.mac().mac().equals(mac) &&
                umr.logicalSwitchId().equals(lsUuid)) {
                set.remove(umr);
                if (set.isEmpty()) {
                    this.ucastMacsRemote.remove(mac.toString());
                }
            }
        }
    }

    public void deleteAllMcastMacRemote(@Nonnull String lsName,
                                        @Nonnull VtepMAC mac)
        throws VtepConfigException {
        assertConnected();

        this.mcastMacsRemote.removeAll(mac.toString());
    }


    @Override
    public Try<BoxedUnit> removeBinding(@Nonnull String portName, short vlan) {
        assertConnected();

        PhysicalPort pport = physicalPorts.get(portName);
        if (pport == null) {
            return Failure.apply(
                new VtepConfigException(endPoint, "Port not found"));
        }
        Map<Integer, UUID> bindings = new HashMap<>();
        bindings.putAll(mapAsJavaMap(pport.vlanBindings()));
        if (bindings.remove((int) vlan) != null) {
            PhysicalPort updated = PhysicalPort.apply(
                pport.uuid(), pport.name(), pport.description(), bindings,
                mapAsJavaMap(pport.vlanStats()),
                setAsJavaSet(pport.portFaultStatus())
            );
            physicalPorts.put(portName, updated);
        }
        return Success.apply(BoxedUnit.UNIT);
    }

    private UUID getLocatorUuid(String ip) {
        assertConnected();

        UUID locatorUuid = locatorUuids.get(ip);
        if (locatorUuid == null) {
            locatorUuid = UUID.randomUUID();
            locatorUuids.put(ip, locatorUuid);
        }
        return locatorUuid;
    }

    private void assertConnected() {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected");
    }
}
