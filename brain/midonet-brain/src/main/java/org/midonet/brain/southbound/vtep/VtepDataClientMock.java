/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.google.common.collect.ArrayListMultimap;
import com.google.common.collect.ListMultimap;
import com.google.common.collect.Sets;

import org.apache.commons.lang3.tuple.Pair;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.plugin.Connection;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;

import rx.Observable;
import rx.subjects.PublishSubject;
import rx.subjects.Subject;

import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

public class VtepDataClientMock implements VtepDataClient {

    private static final String VTEP_NODE_NAME= "vtep";

    protected String mgmtIp;
    protected int mgmtPort;
    protected Set<String> tunnelIps;
    protected boolean connected = false;
    protected Connection connection = null;

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

    private final Subject<Connection, Connection> connectSubject =
        PublishSubject.create();
    private final Subject<Connection, Connection> disconnectSubject =
        PublishSubject.create();

    public VtepDataClientMock(String mgmtIp, int mgmtPort,
                              String name, String desc,
                              Set<String> tunnelIps,
                              Collection<String> portNames) {
        this.mgmtIp = mgmtIp;
        this.mgmtPort = mgmtPort;
        this.tunnelIps = tunnelIps;
        PhysicalSwitch ps = new PhysicalSwitch(
                new UUID(java.util.UUID.randomUUID().toString()),
                desc, name, portNames, Sets.newHashSet(mgmtIp), tunnelIps);
        physicalSwitches.put(mgmtIp, ps);

        for (String portName : portNames) {
            PhysicalPort pp = new PhysicalPort(portName + "-desc", portName);
            physicalPorts.put(portName, pp);
        }
    }

    @Override
    public IPv4Addr getManagementIp() {
        return IPv4Addr.fromString(this.mgmtIp);
    }

    @Override
    public IPv4Addr getTunnelIp() {
        return IPv4Addr.fromString(this.tunnelIps.iterator().next());
    }

    @Override
    public int getManagementPort() {
        return this.mgmtPort;
    }

    @Override
    public void connect(IPv4Addr mgmtIp, int port) {
        if (connected)
            throw new IllegalStateException("VTEP client already connected.");
        if (!this.mgmtIp.equals(mgmtIp.toString()) || this.mgmtPort != port)
            throw new IllegalStateException("Could not complete connection.");
        connected = true;
        connectSubject.onNext(connection =
                                  new Connection(VTEP_NODE_NAME, null));
    }

    @Override
    public void disconnect() {
        assertConnected();
        connected = false;
        disconnectSubject.onNext(connection);
        connection = null;
    }

    @Override
    public List<PhysicalSwitch> listPhysicalSwitches() {
        assertConnected();
        return new ArrayList<>(physicalSwitches.values());
    }

    @Override
    public List<LogicalSwitch> listLogicalSwitches() {
        assertConnected();
        return new ArrayList<>(logicalSwitches.values());
    }

    @Override
    public List<PhysicalPort> listPhysicalPorts(UUID psUuid) {
        assertConnected();
        return new ArrayList<>(physicalPorts.values());
    }

    @Override
    public List<McastMac> listMcastMacsLocal() {
        assertConnected();
        return new ArrayList<>(mcastMacsLocal.values());
    }

    @Override
    public List<McastMac> listMcastMacsRemote() {
        assertConnected();
        return new ArrayList<>(mcastMacsRemote.values());
    }

    @Override
    public List<UcastMac> listUcastMacsLocal() {
        assertConnected();
        return new ArrayList<>(ucastMacsLocal.values());
    }

    @Override
    public List<UcastMac> listUcastMacsRemote() {
        assertConnected();
        List<UcastMac> entryList = new ArrayList<>();
        for (String m: ucastMacsRemote.keySet()) {
            entryList.addAll(ucastMacsRemote.get(m));
        }
        return entryList;
    }

    @Override
    public LogicalSwitch getLogicalSwitch(UUID id) {
        assertConnected();
        for (LogicalSwitch ls : this.logicalSwitches.values()) {
            if (ls.uuid.equals(id)) {
                return ls;
            }
        }
        return null;
    }

    @Override
    public LogicalSwitch getLogicalSwitch(String name) {
        assertConnected();
        return this.logicalSwitches.get(name);
    }

    @Override
    public StatusWithUuid addLogicalSwitch(String name, int vni) {
        assertConnected();

        LogicalSwitch ls = logicalSwitches.get(name);
        if (ls != null) {
            return new StatusWithUuid(StatusCode.CONFLICT,
                    "A logical switch named " + name + " already exists.");
        }

        UUID uuid = new UUID(java.util.UUID.randomUUID().toString());
        ls = new LogicalSwitch(uuid, name + "-desc", name, vni);
        logicalSwitches.put(name, ls);
        logicalSwitchUuids.put(name, uuid);
        return new StatusWithUuid(StatusCode.SUCCESS, uuid);
    }

    @Override
    public Status bindVlan(String lsName, String portName,
                           int vlan, Integer vni, List<IPv4Addr> floodIps) {
        assertConnected();

        PhysicalPort pp = physicalPorts.get(portName);
        if (pp == null)
            return new Status(StatusCode.NOTFOUND,
                    "Physical port " + portName + " not found");

        LogicalSwitch ls = logicalSwitches.get(lsName);
        if (ls == null && vni != null) {
            this.addLogicalSwitch(lsName, vni);
        } else if (vni == null) {
            return new Status(StatusCode.BADREQUEST,
                              "Logical switch " + lsName + " not found");
        }

        pp.vlanBindings.put(vlan, logicalSwitchUuids.get(lsName));

        for (IPv4Addr floodIp : floodIps)
            addMcastMacRemote(lsName, VtepMAC.UNKNOWN_DST, floodIp);

        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Status addBindings(UUID lsUuid,
                              List<Pair<String, Integer>> portVlanPairs) {
        return null;
    }

    @Override
    public Status deleteLogicalSwitch(String name) {
        assertConnected();

        LogicalSwitch ls = logicalSwitches.remove(name);
        if (ls == null) {
            return new Status(StatusCode.NOTFOUND,
                              "Logical switch doesn't exist: " + name);
        }

        UUID lsId = logicalSwitchUuids.remove(name);
        if (lsId == null) {
            throw new IllegalStateException("Logical switch found, but not in "+
                                            "the ids map: most likely a bug in"+
                                            "VtepDataClientMock");
        }

        // Remove all bindings to the given logical switch
        for (Map.Entry<String, PhysicalPort> pport : physicalPorts.entrySet()) {
            Iterator<Map.Entry<Integer, UUID>> it =
                pport.getValue().vlanBindings.entrySet().iterator();
            while (it.hasNext()) {
                if (lsId.equals(it.next().getValue()))
                    it.remove();
            }
        }

        return new Status(StatusCode.SUCCESS);
    }


    @Override
    public Status addUcastMacRemote(String lsName, MAC mac, IPv4Addr macIp,
                                    IPv4Addr tunnelEndPoint) {
        assertConnected();

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            return new Status(StatusCode.BADREQUEST,
                              "Logical switch not found.");

        UcastMac ucastMac = new UcastMac(
            mac, lsUuid, getLocatorUuid(tunnelEndPoint.toString()), macIp);
        Set<UcastMac> set = ucastMacsRemote.get(mac.toString());
        if (set == null) {
            set = new HashSet<>();
            ucastMacsRemote.put(mac.toString(), set);
        }
        set.add(ucastMac);
        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Status addMcastMacRemote(String lsName, VtepMAC mac, IPv4Addr ip) {
        assertConnected();

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            return new Status(StatusCode.BADREQUEST,
                    "Logical switch not found.");

        // HACK: This just gets a locator for the specified IP address and
        // uses it as a locator set UUID. If this mock ever actually needs
        // to distinguish between locators and locator sets, this will
        // need to change.
        McastMac mcastMac = new McastMac(
            mac.toString(), lsUuid, getLocatorUuid(ip.toString()), null);
        mcastMacsRemote.put(mac.toString(), mcastMac);
        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Status delUcastMacRemote(String lsName, MAC mac, IPv4Addr macIp) {
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
            if (umr.mac.equals(mac.toString()) &&
                umr.logicalSwitch.equals(lsUuid) &&
                (macIp == null || macIp.toString().equals(umr.ipAddr))) {
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
    public Status delUcastMacRemoteAllIps(String lsName, MAC mac) {
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
            if (umr.mac.equals(mac.toString()) &&
                umr.logicalSwitch.equals(lsUuid)) {
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
    public Status delMcastMacRemoteAllIps(String lsName, VtepMAC vMac) {
        assertConnected();

        if (this.mcastMacsRemote.removeAll(vMac.toString()).isEmpty()) {
            return new Status(StatusCode.NOTFOUND);
        }
        return new Status(StatusCode.SUCCESS);
    }

    public Observable<Connection> connectObservable() {
        return connectSubject.asObservable();
    }

    @Override
    public Observable<Connection> disconnectObservable() {
        return disconnectSubject.asObservable();
    }

    @Override
    public Observable<TableUpdates> updatesObservable() {
        assertConnected();
        return Observable.never(); // No tests use this for now.
    }

    public Status deleteBinding(String portName, int vlanId) {
        assertConnected();

        PhysicalPort pport = physicalPorts.get(portName);
        if (pport == null) {
            return new Status(StatusCode.NOTFOUND, "Port not found");
        }
        if (pport.vlanBindings.remove(vlanId) == null) {
            return new Status(StatusCode.NOTFOUND);
        } else {
            return new Status(StatusCode.SUCCESS);
        }
    }

    @Override
    public List<Pair<UUID, Integer>> listPortVlanBindings(UUID lsId) {
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
            locatorUuid = new UUID(java.util.UUID.randomUUID().toString());
            locatorUuids.put(ip, locatorUuid);
        }
        return locatorUuid;
    }

    private void assertConnected() {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected");
    }
}
