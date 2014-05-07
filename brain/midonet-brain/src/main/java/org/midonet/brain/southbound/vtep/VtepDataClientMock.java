package org.midonet.brain.southbound.vtep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.packets.IPv4Addr;

import com.google.common.collect.Sets;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import rx.Observable;

public class VtepDataClientMock implements VtepDataClient {

    protected String mgmtIp;
    protected int mgmtPort;
    protected boolean connected = false;

    protected final Map<String, PhysicalSwitch> physicalSwitches =
            new HashMap<>();
    protected final Map<String, LogicalSwitch> logicalSwitches =
            new HashMap<>();
    protected final Map<String, PhysicalPort> physicalPorts = new HashMap<>();
    protected final Map<String, UUID> logicalSwitchUuids = new HashMap<>();
    protected final Map<String, UUID> locatorUuids = new HashMap<>();
    protected final Map<String, McastMac> mcastMacsLocal = new HashMap<>();
    protected final Map<String, McastMac> mcastMacsRemote = new HashMap<>();
    protected final Map<String, UcastMac> ucastMacsLocal = new HashMap<>();
    protected final Map<String, UcastMac> ucastMacsRemote = new HashMap<>();

    public VtepDataClientMock(String mgmtIp, int mgmtPort,
                              String name, String desc,
                              Set<String> tunnelIps,
                              Collection<String> portNames) {
        this.mgmtIp = mgmtIp;
        this.mgmtPort = mgmtPort;
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
    public void connect(IPv4Addr mgmtIp, int port) {
        if (this.connected)
            throw new IllegalStateException("VTEP client already connected.");
        if (!this.mgmtIp.equals(mgmtIp.toString()) || this.mgmtPort != port)
            throw new IllegalStateException("Could not complete connection.");
        this.connected = true;
    }

    @Override
    public void disconnect() {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");
        this.connected = false;
    }

    @Override
    public List<PhysicalSwitch> listPhysicalSwitches() {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");
        return new ArrayList<>(physicalSwitches.values());
    }

    @Override
    public List<LogicalSwitch> listLogicalSwitches() {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");
        return new ArrayList<>(logicalSwitches.values());
    }

    @Override
    public List<PhysicalPort> listPhysicalPorts(UUID psUuid) {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");
        return new ArrayList<>(physicalPorts.values());
    }

    @Override
    public List<McastMac> listMcastMacsLocal() {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");
        return new ArrayList<>(mcastMacsLocal.values());
    }

    @Override
    public List<McastMac> listMcastMacsRemote() {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");
        return new ArrayList<>(mcastMacsRemote.values());
    }

    @Override
    public List<UcastMac> listUcastMacsLocal() {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");
        return new ArrayList<>(ucastMacsLocal.values());
    }

    @Override
    public List<UcastMac> listUcastMacsRemote() {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");
        return new ArrayList<>(ucastMacsRemote.values());
    }

    @Override
    public StatusWithUuid addLogicalSwitch(String name, int vni) {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");

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
                           int vlan, Integer vni, List<String> floodIps) {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");

        PhysicalPort pp = physicalPorts.get(portName);
        if (pp == null)
            return new Status(StatusCode.NOTFOUND,
                    "Physical port " + portName + " not found");

        LogicalSwitch ls = logicalSwitches.get(lsName);
        if (ls == null)
            return new Status(StatusCode.BADREQUEST,
                              "Logical switch " + lsName + " not found");

        pp.vlanBindings.put(vlan, logicalSwitchUuids.get(lsName));

        for (String floodIp : floodIps)
            addMcastMacRemote(lsName, VtepConstants.UNKNOWN_DST, floodIp);

        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Status deleteLogicalSwitch(String name) {

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
    public Status addUcastMacRemote(String lsName, String mac, String ip) {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            return new Status(StatusCode.BADREQUEST,
                              "Logical switch not found.");

        UcastMac ucastMac = new UcastMac(mac, lsUuid, getLocatorUuid(ip), ip);
        ucastMacsRemote.put(mac, ucastMac);
        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Status addMcastMacRemote(String lsName, String mac, String ip) {
        if (!connected)
            throw new IllegalStateException("VTEP client not connected.");

        UUID lsUuid = logicalSwitchUuids.get(lsName);
        if (lsUuid == null)
            return new Status(StatusCode.BADREQUEST,
                    "Logical switch not found.");

        // HACK: This just gets a locator for the specified IP address and
        // uses it as a locator set UUID. If this mock ever actually needs
        // to distinguish between locators and locator sets, this will
        // need to change.
        McastMac mcastMac = new McastMac(mac, lsUuid, getLocatorUuid(ip), ip);
        mcastMacsRemote.put(mac, mcastMac);
        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Observable<TableUpdates> observableLocalMacTable() {
        return Observable.never(); // No tests use this for now.
    }

    public Status deleteBinding(String portName, int vlanId) {
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

    private UUID getLocatorUuid(String ip) {
        UUID locatorUuid = locatorUuids.get(ip);
        if (locatorUuid == null) {
            locatorUuid = new UUID(java.util.UUID.randomUUID().toString());
            locatorUuids.put(ip, locatorUuid);
        }
        return locatorUuid;
    }
}
