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
import java.util.concurrent.TimeUnit;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;

import org.apache.commons.lang3.tuple.Pair;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;

import rx.Observable;
import rx.Subscription;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.cluster.data.vtep.VtepException;
import org.midonet.cluster.data.vtep.VtepStateException;
import org.midonet.cluster.data.vtep.model.LogicalSwitch;
import org.midonet.cluster.data.vtep.model.McastMac;
import org.midonet.cluster.data.vtep.model.PhysicalPort;
import org.midonet.cluster.data.vtep.model.PhysicalSwitch;
import org.midonet.cluster.data.vtep.model.UcastMac;
import org.midonet.cluster.data.vtep.model.VtepEndPoint;
import org.midonet.cluster.data.vtep.model.VtepMAC;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback;

import static scala.collection.JavaConversions.setAsJavaSet;
import static scala.collection.JavaConversions.mapAsJavaMap;
import static org.midonet.cluster.southbound.vtep.model.VtepModelTranslator.fromMido;

public class VtepDataClientMock implements VtepDataClient {

    protected String mgmtIp;
    protected int mgmtPort;
    protected Set<String> tunnelIps;
    protected boolean connected = false;

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

    private final Subject<State, State> stateSubject = PublishSubject.create();

    public VtepDataClientMock(String mgmtIp, int mgmtPort,
                              String name, String desc,
                              Set<String> tunnelIps,
                              Collection<String> portNames) {
        this.mgmtIp = mgmtIp;
        this.mgmtPort = mgmtPort;
        this.tunnelIps = tunnelIps;
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
    public IPv4Addr getTunnelIp() {
        return tunnelIps.isEmpty() ? null :
               IPv4Addr.fromString(tunnelIps.iterator().next());
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

        if (!connected) {

            connected = true;
            stateSubject.onNext(State.CONNECTED);
        }

        return this;
    }

    @Override
    public void disconnect(java.util.UUID user, boolean lazyDisconnect) {
        assertConnected();
        connected = false;
        stateSubject.onNext(State.DISCONNECTED);
    }

    @Override
    public VtepDataClient awaitConnected() {
        if (!connected)
            throw new UnsupportedOperationException(
                "Cannot await asynchronously on a mock client.");
        return this;
    }

    @Override
    public VtepDataClient awaitConnected(long time, TimeUnit unit) {
        if (!connected)
            throw new UnsupportedOperationException(
                "Cannot await asynchronously on a mock client.");
        return this;
    }

    @Override
    public VtepDataClient awaitDisconnected() {
        if (connected)
            throw new UnsupportedOperationException(
                "Cannot await asynchronously on a mock client.");
        return this;
    }

    @Override
    public VtepDataClient awaitDisconnected(long time, TimeUnit unit) {
        if (connected)
            throw new UnsupportedOperationException(
                "Cannot await asynchronously on a mock client.");
        return this;
    }

    @Override
    public VtepDataClient awaitState(State state) {
        throw new UnsupportedOperationException(
            "Cannot await asynchronously on a mock client.");
    }

    @Override
    public VtepDataClient awaitState(State state, long timeout, TimeUnit unit) {
        throw new UnsupportedOperationException(
            "Cannot await asynchronously on a mock client.");
    }

    /**
     * Specifies a callback method to execute when the VTEP becomes connected.
     *
     * @param callback A callback instance.
     */
    @Override
    public Subscription onConnected(
        @Nonnull Callback<VtepDataClient, VtepException> callback) {
        if (connected) {
            callback.onSuccess(this);
        } else {
            callback.onError(new VtepStateException(
                new VtepEndPoint(IPv4Addr.fromString(mgmtIp), mgmtPort),
                "VTEP not connected."));
        }
        return Observable.never().subscribe();
    }

    @Override
    public State getState() {
        return connected ? State.CONNECTED : State.DISCONNECTED;
    }

    @Override
    public Observable<State> stateObservable() {
        return stateSubject.asObservable();
    }

    @Override
    public Observable<TableUpdates> updatesObservable() {
        assertConnected();
        return Observable.never(); // No tests use this for now.
    }

    @Override
    public @Nonnull List<PhysicalSwitch> listPhysicalSwitches() {
        assertConnected();
        return new ArrayList<>(physicalSwitches.values());
    }

    @Override
    public @Nonnull List<LogicalSwitch> listLogicalSwitches() {
        assertConnected();
        return new ArrayList<>(logicalSwitches.values());
    }

    @Override
    public @Nonnull List<PhysicalPort> listPhysicalPorts(UUID psUuid) {
        assertConnected();
        return new ArrayList<>(physicalPorts.values());
    }

    @Override
    public @Nonnull List<McastMac> listMcastMacsLocal() {
        assertConnected();
        return new ArrayList<>(mcastMacsLocal.values());
    }

    @Override
    public @Nonnull List<McastMac> listMcastMacsRemote() {
        assertConnected();
        return new ArrayList<>(mcastMacsRemote.values());
    }

    @Override
    public @Nonnull List<UcastMac> listUcastMacsLocal() {
        assertConnected();
        return new ArrayList<>(ucastMacsLocal.values());
    }

    @Override
    public @Nonnull List<UcastMac> listUcastMacsRemote() {
        assertConnected();
        List<UcastMac> entryList = new ArrayList<>();
        for (String m: ucastMacsRemote.keySet()) {
            entryList.addAll(ucastMacsRemote.get(m));
        }
        return entryList;
    }

    @Override
    public LogicalSwitch getLogicalSwitch(@Nonnull UUID lsId) {
        assertConnected();
        for (LogicalSwitch ls : this.logicalSwitches.values()) {
            if (ls.uuid().equals(lsId)) {
                return ls;
            }
        }
        return null;
    }

    @Override
    public LogicalSwitch getLogicalSwitch(@Nonnull String lsName) {
        assertConnected();
        return this.logicalSwitches.get(lsName);
    }

    @Override
    public StatusWithUuid addLogicalSwitch(@Nonnull String lsName, int vni) {
        assertConnected();

        LogicalSwitch ls = logicalSwitches.get(lsName);
        if (ls != null) {
            return new StatusWithUuid(StatusCode.CONFLICT,
                    "A logical switch named " + lsName + " already exists.");
        }

        UUID uuid = UUID.randomUUID();
        ls = new LogicalSwitch(uuid, lsName, vni, lsName + "-desc");
        logicalSwitches.put(lsName, ls);
        logicalSwitchUuids.put(lsName, uuid);
        return new StatusWithUuid(StatusCode.SUCCESS, fromMido(uuid));
    }

    @Override
    public Status bindVlan(@Nonnull String lsName, @Nonnull String portName,
                           short vlan, int vni,
                           @Nullable Collection<IPv4Addr> floodIps) {
        assertConnected();

        PhysicalPort pp = physicalPorts.get(portName);
        if (pp == null)
            return new Status(StatusCode.NOTFOUND,
                    "Physical port " + portName + " not found");

        LogicalSwitch ls = logicalSwitches.get(lsName);
        if (ls == null) {
            this.addLogicalSwitch(lsName, vni);
        }

        Map<Integer, UUID> bindings = new HashMap<>();
        bindings.putAll(mapAsJavaMap(pp.vlanBindings()));
        bindings.put((int)vlan, logicalSwitchUuids.get(lsName));

        PhysicalPort updated = PhysicalPort.apply(
            pp.uuid(), pp.name(), pp.description(), bindings,
            mapAsJavaMap(pp.vlanStats()), setAsJavaSet(pp.portFaultStatus()));
        physicalPorts.put(portName, updated);

        if (null != floodIps) {
            for (IPv4Addr floodIp : floodIps)
                addMcastMacRemote(lsName, VtepMAC.UNKNOWN_DST(), floodIp);
        }

        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Status addBindings(
        @Nonnull UUID lsId, @Nonnull Collection<Pair<String, Short>> bindings) {
        return null;
    }

    @Override
    public Status deleteLogicalSwitch(@Nonnull String name) {
        assertConnected();

        LogicalSwitch ls = logicalSwitches.remove(name);
        if (ls == null) {
            return new Status(StatusCode.NOTFOUND,
                              "Logical switch doesn't exist: " + name);
        }

        final UUID lsId = logicalSwitchUuids.remove(name);
        if (lsId == null) {
            throw new IllegalStateException("Logical switch found, but not in "+
                                            "the ids map: most likely a bug in"+
                                            "VtepDataClientMock");
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

        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Status addUcastMacRemote(@Nonnull String lsName, @Nonnull MAC mac,
                                    @Nullable IPv4Addr macIp,
                                    @Nonnull IPv4Addr tunnelEndPoint) {
        assertConnected();

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            return new Status(StatusCode.BADREQUEST,
                              "Logical switch not found.");

        UcastMac ucastMac = UcastMac.apply(
            lsUuid, VtepMAC.fromMac(mac), macIp,
            getLocatorUuid(tunnelEndPoint.toString()));

        Set<UcastMac> set = ucastMacsRemote.get(mac.toString());
        if (set == null) {
            set = new HashSet<>();
            ucastMacsRemote.put(mac.toString(), set);
        }
        set.add(ucastMac);
        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Status addMcastMacRemote(@Nonnull String lsName,
                                    @Nonnull VtepMAC mac,
                                    @Nonnull IPv4Addr ip) {
        assertConnected();

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            return new Status(StatusCode.BADREQUEST,
                    "Logical switch not found.");

        // HACK: This just gets a locator for the specified IP address and
        // uses it as a locator set UUID. If this mock ever actually needs
        // to distinguish between locators and locator sets, this will
        // need to change.
        McastMac mcastMac = McastMac.apply(
            lsUuid, mac, getLocatorUuid(ip.toString()));
        mcastMacsRemote.put(mac.toString(), mcastMac);
        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Status deleteUcastMacRemote(@Nonnull String lsName, @Nonnull MAC mac,
                                       @Nonnull IPv4Addr macIp) {
        assertConnected();

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            return new Status(StatusCode.BADREQUEST,
                              "Logical switch not found.");
        Status st = new Status(StatusCode.NOTFOUND);
        Set<UcastMac> set = this.ucastMacsRemote.get(mac.toString());
        if (set == null) {
            return st;
        }
        for (UcastMac umr: set) {
            if (umr.mac().mac().equals(mac) &&
                umr.logicalSwitchId().equals(lsUuid) &&
                macIp.equals(umr.ipAddr())) {
                set.remove(umr);
                if (set.isEmpty()) {
                    this.ucastMacsRemote.remove(mac.toString());
                }
                st = new Status(StatusCode.SUCCESS);
            }
        }
        return st;
    }

    @Override
    public Status deleteAllUcastMacRemote(@Nonnull String lsName,
                                          @Nonnull MAC mac) {
        assertConnected();

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            return new Status(StatusCode.BADREQUEST,
                              "Logical switch not found.");
        Status st = new Status(StatusCode.NOTFOUND);
        Set<UcastMac> set = this.ucastMacsRemote.get(mac.toString());
        if (set == null) {
            return st;
        }
        for (UcastMac umr: set) {
            if (umr.mac().mac().equals(mac) &&
                umr.logicalSwitchId().equals(lsUuid)) {
                set.remove(umr);
                if (set.isEmpty()) {
                    this.ucastMacsRemote.remove(mac.toString());
                }
                st = new Status(StatusCode.SUCCESS);
            }
        }
        return st;
    }

    @Override
    public Status deleteAllMcastMacRemote(@Nonnull String lsName,
                                          @Nonnull VtepMAC mac) {
        assertConnected();

        if (this.mcastMacsRemote.removeAll(mac.toString()).isEmpty()) {
            return new Status(StatusCode.NOTFOUND);
        }
        return new Status(StatusCode.SUCCESS);
    }


    @Override
    public Status deleteBinding(@Nonnull String portName, short vlan) {
        assertConnected();

        PhysicalPort pport = physicalPorts.get(portName);
        if (pport == null) {
            return new Status(StatusCode.NOTFOUND, "Port not found");
        }
        Map<Integer, UUID> bindings = new HashMap<>();
        bindings.putAll(mapAsJavaMap(pport.vlanBindings()));
        if (bindings.remove((int) vlan) == null) {
            return new Status(StatusCode.NOTFOUND);
        } else {
            PhysicalPort updated = PhysicalPort.apply(
                pport.uuid(), pport.name(), pport.description(), bindings,
                mapAsJavaMap(pport.vlanStats()),
                setAsJavaSet(pport.portFaultStatus())
            );
            physicalPorts.put(portName, updated);
            return new Status(StatusCode.SUCCESS);
        }
    }

    @Override
    public List<Pair<UUID, Short>> listPortVlanBindings(UUID lsId) {
        throw new UnsupportedOperationException();
    }

    @Override
    public Status clearBindings(UUID lsUuid) {
        throw new UnsupportedOperationException();
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
