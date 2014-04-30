package org.midonet.brain.southbound.vtep;

import com.google.common.collect.Sets;
import org.midonet.brain.southbound.vtep.model.*;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.IPv4Addr$;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;

import java.lang.Override;
import java.util.*;

public class VtepDataClientMock implements VtepDataClient {

    private String mgmtIp;
    private int mgmtPort;

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
        if (!this.mgmtIp.equals(mgmtIp.toString()) || this.mgmtPort != port)
            throw new IllegalStateException("Could not complete connection.");
    }

    @Override
    public void disconnect() {
        // Nothing to do.
    }

    @Override
    public List<PhysicalSwitch> listPhysicalSwitches() {
        return new ArrayList<>(physicalSwitches.values());
    }

    @Override
    public List<LogicalSwitch> listLogicalSwitches() {
        return new ArrayList<>(logicalSwitches.values());
    }

    @Override
    public List<PhysicalPort> listPhysicalPorts(UUID psUuid) {
        return new ArrayList<>(physicalPorts.values());
    }

    @Override
    public List<McastMac> listMcastMacsLocal() {
        return new ArrayList<>(mcastMacsLocal.values());
    }

    @Override
    public List<McastMac> listMcastMacsRemote() {
        return new ArrayList<>(mcastMacsRemote.values());
    }

    @Override
    public List<UcastMac> listUcastMacsLocal() {
        return new ArrayList<>(ucastMacsLocal.values());
    }

    @Override
    public List<UcastMac> listUcastMacsRemote() {
        return new ArrayList<>(ucastMacsRemote.values());
    }

    @Override
    public StatusWithUuid addLogicalSwitch(String name, int vni) {
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
        PhysicalPort pp = physicalPorts.get(portName);
        if (pp == null)
            return new Status(StatusCode.BADREQUEST, "Port not found.");

        LogicalSwitch ls = logicalSwitches.get(lsName);
        if (ls == null)
            return new Status(StatusCode.BADREQUEST,
                              "Logical switch not found");

        pp.vlanBindings.put(vlan, logicalSwitchUuids.get(lsName));

        for (String floodIp : floodIps)
            addMcastMacRemote(lsName, VtepDataClient.UNKNOWN_DST, floodIp);

        return new Status(StatusCode.SUCCESS);
    }

    @Override
    public Status addUcastMacRemote(String lsName, String mac, String ip) {
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

    private UUID getLocatorUuid(String ip) {
        UUID locatorUuid = locatorUuids.get(ip);
        if (locatorUuid == null) {
            locatorUuid = new UUID(java.util.UUID.randomUUID().toString());
            locatorUuids.put(ip, locatorUuid);
        }
        return locatorUuid;
    }
}
