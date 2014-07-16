/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.brain.southbound.vtep;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;

import com.google.inject.Inject;

import org.apache.commons.lang3.tuple.Pair;
import org.opendaylight.controller.sal.connection.ConnectionConstants;
import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.controller.sal.utils.Status;
import org.opendaylight.controller.sal.utils.StatusCode;
import org.opendaylight.ovsdb.lib.message.TableUpdates;
import org.opendaylight.ovsdb.lib.notation.UUID;
import org.opendaylight.ovsdb.lib.table.internal.Table;
import org.opendaylight.ovsdb.lib.table.vtep.Logical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Local;
import org.opendaylight.ovsdb.lib.table.vtep.Mcast_Macs_Remote;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Port;
import org.opendaylight.ovsdb.lib.table.vtep.Physical_Switch;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Local;
import org.opendaylight.ovsdb.lib.table.vtep.Ucast_Macs_Remote;
import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.opendaylight.ovsdb.plugin.ConnectionService;
import org.opendaylight.ovsdb.plugin.InventoryService;
import org.opendaylight.ovsdb.plugin.InventoryServiceInternal;
import org.opendaylight.ovsdb.plugin.StatusWithUuid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;

import org.midonet.brain.southbound.vtep.model.LogicalSwitch;
import org.midonet.brain.southbound.vtep.model.McastMac;
import org.midonet.brain.southbound.vtep.model.PhysicalPort;
import org.midonet.brain.southbound.vtep.model.PhysicalSwitch;
import org.midonet.brain.southbound.vtep.model.UcastMac;
import org.midonet.brain.southbound.vtep.model.VtepModelTranslator;
import org.midonet.packets.IPv4Addr;
import org.midonet.packets.MAC;

import static org.midonet.brain.southbound.vtep.VtepConstants.UNKNOWN_DST;

public class VtepDataClientImpl implements VtepDataClient {

    private static final Logger log =
        LoggerFactory.getLogger(VtepDataClientImpl.class);
    private static final String VTEP_NODE_NAME= "vtep";

    private final ConnectionService cnxnSrv;
    private final ConfigurationService cfgSrv;
    private final InventoryService invSrv;

    private Node node = null;

    private IPv4Addr mgmtIp  = null;
    private int mgmtPort;
    private PhysicalSwitch myPhysicalSwitch = null;

    private boolean started = false;

    private static final int CNXN_TIMEOUT_MILLIS = 5000;

    @Inject
    public VtepDataClientImpl(ConfigurationService confService,
                              ConnectionService cnxnService,
                              InventoryService invService) {
        this.cnxnSrv = cnxnService;
        this.cfgSrv = confService;
        this.invSrv = invService;
    }

    @Override
    public IPv4Addr getManagementIp() {
        return this.mgmtIp;
    }

    @Override
    public IPv4Addr getTunnelIp() {
        if (myPhysicalSwitch == null || myPhysicalSwitch.tunnelIps == null ||
            myPhysicalSwitch.tunnelIps.isEmpty()) {
            return null;
        }
        return IPv4Addr.apply(myPhysicalSwitch.tunnelIps.iterator().next());
    }

    @Override
    public int getManagementPort() {
        return this.mgmtPort;
    }

    @Override
    public synchronized void connect(final IPv4Addr mgmtIp, final int port) {

        if (started) {
            throw new IllegalStateException("Already started");
        }

        this.mgmtIp = mgmtIp;
        this.mgmtPort = port;

        Map<ConnectionConstants, String> params = new HashMap<>();
        params.put(ConnectionConstants.ADDRESS, mgmtIp.toString());
        params.put(ConnectionConstants.PORT, Integer.toString(port));

        cnxnSrv.init();
        invSrv.init();

        cnxnSrv.setInventoryServiceInternal(invSrv);
        cfgSrv.setInventoryServiceInternal(invSrv);
        cfgSrv.setConnectionServiceInternal(cnxnSrv);

        node = cnxnSrv.connect(VTEP_NODE_NAME, params);
        cfgSrv.setDefaultNode(node);

        log.debug("Connecting to VTEP on {}:{}, node {}",
                  new Object[]{mgmtIp, port, node.getID()});

        long timeoutAt = System.currentTimeMillis() + CNXN_TIMEOUT_MILLIS;
        while (!this.isReady() && System.currentTimeMillis() < timeoutAt) {
            log.debug("Waiting for inventory service initialization");
            try {
                Thread.sleep(CNXN_TIMEOUT_MILLIS / 10);
            } catch (InterruptedException e) {
                log.error("Interrupted while waiting for service init.");
                Thread.interrupted();
            }
        }

        if (this.isReady()) {
            this.myPhysicalSwitch = this.loadVtepDetails();
        }

        if (this.myPhysicalSwitch == null) {
            throw new IllegalStateException("Could not complete connection");
        }

        this.started = true;
    }

    public boolean isReady() {
        Map<String, ConcurrentMap<String, Table<?>>> cache =
            this.cnxnSrv.getInventoryServiceInternal().getCache(node);
        if (cache == null) {
            return false;
        }
        Map<String, Table<?>> psTableCache =
            cache.get(Physical_Switch.NAME.getName());

        // This is not 100% reliable but at least verifies that we have some
        // data loaded, all the rest of the tables don't necessarily have to
        // contain data.
        return psTableCache != null && !psTableCache.isEmpty();
    }

    private PhysicalSwitch loadVtepDetails() {
        List<PhysicalSwitch> pss = this.listPhysicalSwitches();
        String sMgmtIp = this.mgmtIp.toString();
        log.debug("Loading VTEP details, known VTEPs: {}", pss);
        for (PhysicalSwitch ps : pss) {
            if (ps.mgmtIps.contains(sMgmtIp)) {
                return ps;
            }
        }
        log.warn("Could not find physical switch!");
        return null;
    }

    @Override
    public synchronized void disconnect() {
        if (!started) {
            log.warn("Trying to disconnect client, but not connected");
        }
        log.info("Disconnecting..");
        cnxnSrv.disconnect(node);
        this.started = false;
    }

    /**
     * Returns the ovsdb internal cache for the given table, if it doesn't
     * exist or it's empty, returns an empty map.
     * @param tableName the requested table.
     * @return the cached contents, if any.
     */
    private Map<String, Table<?>> getTableCache(String tableName) {

        InventoryServiceInternal isi = cnxnSrv.getInventoryServiceInternal();
        if (isi == null) {
            return null;
        }

        Map<String, ConcurrentMap<String, Table<?>>> cache = isi.getCache(node);
        if (cache == null) {
            return null;
        }

        Map<String, Table<?>> tableCache = cache.get(tableName);
        if (tableCache == null) {
            tableCache = new HashMap<>(0);
        }
        return tableCache;
    }

    @Override
    public List<PhysicalSwitch> listPhysicalSwitches() {
        Map<String, Table<?>> tableCache =
            getTableCache(Physical_Switch.NAME.getName());
        List<PhysicalSwitch> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Physical Switch {} {}", e.getKey(), e.getValue());
            Physical_Switch ovsdbPs = (Physical_Switch)e.getValue();
            res.add(VtepModelTranslator.toMido(ovsdbPs, new UUID(e.getKey())));
        }
        return res;
    }

    /*
     * TODO replace this implementation with an actual query to the OVSDB,
     * that'll require moving the code to the ConfigurationService probably,
     * similarly as is done for the bind operation.
     *
     * Right now, this is effectively assuming that there is a single physical
     * switch.
     */
    @Override
    public List<PhysicalPort> listPhysicalPorts(UUID psUUID) {
        Map<String, Table<?>> tableCache =
            getTableCache(Physical_Port.NAME.getName());
        List<PhysicalPort> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Physical Port {} {}", e.getKey(), e.getValue());
            Physical_Port ovsdbPort = (Physical_Port)e.getValue();
            res.add(VtepModelTranslator.toMido(ovsdbPort));
        }
        return res;
    }

    @Override
    public List<LogicalSwitch> listLogicalSwitches() {
        Map<String, Table<?>> tableCache =
            getTableCache(Logical_Switch.NAME.getName());
        List<LogicalSwitch> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Logical Switch {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Logical_Switch)e.getValue(),
                                               new UUID(e.getKey())));
        }
        return res;
    }

    @Override
    public List<McastMac> listMcastMacsLocal() {
        log.debug("Listing mcast macs local");
        String tableName = Mcast_Macs_Local.NAME.getName();
        Map<String, Table<?>> tableCache = getTableCache(tableName);
        List<McastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Mac {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Mcast_Macs_Local)e.getValue()));
        }
        return res;
    }

    @Override
    public List<McastMac> listMcastMacsRemote() {
        log.debug("Listing mcast macs remote");
        String tableName = Mcast_Macs_Remote.NAME.getName();
        Map<String, Table<?>> tableCache = getTableCache(tableName);
        List<McastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Mac {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Mcast_Macs_Remote)e.getValue()));
        }
        return res;
    }

    @Override
    public List<UcastMac> listUcastMacsLocal() {
        log.debug("Listing ucast macs local");
        String tableName = Ucast_Macs_Local.NAME.getName();
        Map<String, Table<?>> tableCache = getTableCache(tableName);
        List<UcastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Mac {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Ucast_Macs_Local)e.getValue()));
        }
        return res;
    }

    @Override
    public List<UcastMac> listUcastMacsRemote() {
        log.debug("Listing ucast macs remote");
        String tableName = Ucast_Macs_Remote.NAME.getName();
        Map<String, Table<?>> tableCache = getTableCache(tableName);
        List<UcastMac> res = new ArrayList<>(tableCache.size());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            log.debug("Found Mac {} {}", e.getKey(), e.getValue());
            res.add(VtepModelTranslator.toMido((Ucast_Macs_Remote)e.getValue()));
        }
        return res;
    }

    @Override
    public LogicalSwitch getLogicalSwitch(UUID id) {
        log.debug("Fetching logical switch {}", id);
        Map<String, Table<?>> tableCache =
            getTableCache(Logical_Switch.NAME.getName());
        for (Map.Entry<String, Table<?>> e : tableCache.entrySet()) {
            if (e.getKey().equals(id.toString())) {
                log.debug("Found logical switch {} {}", e.getKey(), e.getValue());
                return VtepModelTranslator.toMido((Logical_Switch)e.getValue(),
                                                  new UUID(e.getKey()));
            }
        }
        return null;
    }

    @Override
    public LogicalSwitch getLogicalSwitch(String name) {
        Logical_Switch ls = cfgSrv.vtepGetLogicalSwitch(node, name);
        if (ls == null) {
            return null;
        }
        return VtepModelTranslator.toMido(ls, ls.getId());
    }

    @Override
    public StatusWithUuid addLogicalSwitch(String name, int vni) {
        log.debug("Add logical switch {} with vni {}", name, vni);
        StatusWithUuid st = cfgSrv.vtepAddLogicalSwitch(name, vni);
        if (!st.isSuccess()) {
            log.warn("Add logical switch failed: {}", st);
        }
        return st;
    }

    @Override
    public Status bindVlan(String lsName, String portName, int vlan,
                            Integer vni, List<String> floodIps) {
        log.debug("Bind vlan {} on phys. port {} to logical switch {}, vni {}, "
                + "and adding ips: {}",
                  new Object[]{lsName, portName, vlan, vni, floodIps});
        Status st = cfgSrv.vtepBindVlan(lsName, portName, vlan, vni, floodIps);
        if (!st.isSuccess()) {
            log.warn("Bind vlan failed: {}", st);
        }
        return st;
    }

    @Override
    public Status addBindings(UUID lsUuid,
                              List<Pair<String, Integer>> portVlanPairs) {
        log.debug("Bind logical switch {} to port-vlans {}",
                  lsUuid, portVlanPairs);
        Status st = cfgSrv.vtepAddBindings(lsUuid, portVlanPairs);
        if (!st.isSuccess()) {
            log.warn("Add bindings failed: {}", st);
        }
        return st;
    }

    @Override
    public Status addUcastMacRemote(String lsName, String mac, String ip) {
        log.debug("Adding Ucast Mac Remote: {} {} {}",
                  new Object[]{lsName, mac, ip});
        assert(IPv4Addr.fromString(ip) != null);
        assert (MAC.fromString(mac) != null);
        StatusWithUuid st = cfgSrv.vtepAddUcastRemote(lsName, mac, ip, null);
        if (!st.isSuccess()) {
            log.error("Could not add Ucast Mac Remote: {}", st);
        }
        return st;
    }

    @Override
    public Status addMcastMacRemote(String lsName, String mac, String ip) {
        log.debug("Adding Mcast Mac Remote: {} {} {}",
                  new Object[]{lsName, mac, ip});
        assert(IPv4Addr.fromString(ip) != null);
        assert(UNKNOWN_DST.equals(mac) || (MAC.fromString(mac) != null));
        StatusWithUuid st = cfgSrv.vtepAddMcastRemote(lsName, mac, ip);
        if (!st.isSuccess()) {
            log.error("Could not add Mcast Mac Remote: {} - {}",
                      st.getCode(), st.getDescription());
        }
        return st;
    }

    @Override
    public Status delUcastMacRemote(String mac, String lsName) {
        log.debug("Deleting mac {} from logical switch {}", mac, lsName);
        assert(MAC.fromString(mac) != null);
        assert(lsName != null);
        Status st = cfgSrv._vtepDelUcastMacRemote(mac, lsName);
        if (!st.isSuccess()) {
            log.error("Could not remove Ucast Mac Remote: {} - {}",
                      st.getCode(), st.getDescription());
        }
        return st;
    }

    @Override
    public Observable<TableUpdates> observableUpdates() {
       return this.cnxnSrv.observableUpdates();
    }

    // TODO: this assumes that we have a single VTEP in the Physical_Switch
    // table, which may not be true.
    @Override
    public Status deleteBinding(String portName, int vlanId) {
        log.debug("Removing binding of port {} and vlan {}", portName, vlanId);
        Map<String, Table<?>> psCache =
            this.getTableCache(Physical_Switch.NAME.getName());
        if (psCache == null || psCache.isEmpty()) {
            log.warn("Cannot find any physical switches in VTEP db");
            return new Status(StatusCode.NOTFOUND, "Physical Switch missing");
        } else if (psCache.size() > 1) {
            log.warn("Physical_Switch table contains more than one entry, " +
                     "this is still not supported, and may have unexpected " +
                     "results");
        }
        Physical_Switch ps = (Physical_Switch)psCache.values().iterator().next();
        Status st = cfgSrv.vtepDelBinding(ps.getName(), portName, vlanId);
        if (!st.isSuccess()) {
            log.error("Could not delete vtep binding, {}: {}",
                      st.getCode(), st.getDescription());
        }
        return st;
    }

    @Override
    public Status deleteLogicalSwitch(String name) {
        log.debug("Removing logical switch {}", name);
        Status st = cfgSrv.vtepDelLogicalSwitch(name);
        if (!st.isSuccess()) {
            log.error("Could not delete logical switch, {}: {}",
                      st.getCode(), st.getDescription());
        }
        return st;
    }

    @Override
    public List<Pair<UUID, Integer>> listPortVlanBindings(UUID lsUuid) {
        return cfgSrv.vtepPortVlanBindings(lsUuid);
    }

    @Override
    public Status clearBindings(UUID lsUuid) {
        return cfgSrv.vtepClearBindings(lsUuid);
    }

}
