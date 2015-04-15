/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.vtep;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

import org.opendaylight.ovsdb.lib.OvsdbClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import rx.Observable;
import rx.Scheduler;
import rx.Subscriber;
import rx.Subscription;
import rx.functions.Func1;
import rx.schedulers.Schedulers;

import org.midonet.cluster.data.vtep.VtepException;
import org.midonet.cluster.data.vtep.model.McastMac;
import org.midonet.cluster.data.vtep.model.UcastMac;
import org.midonet.cluster.data.vtep.model.VtepEndPoint;
import org.midonet.packets.IPv4Addr;
import org.midonet.cluster.data.vtep.model.LogicalSwitch;
import org.midonet.cluster.data.vtep.model.MacEntry;
import org.midonet.cluster.data.vtep.model.MacLocation;
import org.midonet.cluster.data.vtep.model.PhysicalLocator;
import org.midonet.cluster.data.vtep.model.VtepMAC;
import org.midonet.vtep.model.MacUpdate;
import org.midonet.vtep.model.McastMacUpdate;
import org.midonet.vtep.model.UcastMacUpdate;
import org.midonet.vtep.schema.MacsTable;
import org.midonet.util.concurrent.Expectation;

import static org.midonet.vtep.OvsdbUtil.endPointFromOvsdbClient;
import scala.Option;


/**
 * A class to handle data exchanges with an Ovsdb-based VTEP
 */
public class OvsdbVtepLegacyData {

    static private final Logger log =
        LoggerFactory.getLogger(OvsdbVtepLegacyData.class);

    /**
     * Get an ovsdb vtep data instance making sure that operations are made
     * in the specified executor thread.
     */
    static public Expectation<OvsdbVtepLegacyData>
    get(final OvsdbClient client, final ExecutorService vtepThread) {
        final Expectation<OvsdbVtepLegacyData> result = new Expectation<>();

        Runnable task = new Runnable() {
            @Override
            public void run() {
                try {
                    OvsdbVtepBackend backend = new OvsdbVtepBackend(client);
                    OvsdbVtepLegacyData vtep =
                        new OvsdbVtepLegacyData(vtepThread, client, backend);
                    result.success(vtep);
                } catch (Exception exc) {
                    result.failure(exc);
                    vtepThread.shutdown();
                }
            }
        };
        vtepThread.submit(task);
        return result;
    }

    private final ExecutorService executor;
    private final Scheduler scheduler;
    private final VtepEndPoint endPoint;
    private final OvsdbVtepBackend backend;

    private OvsdbVtepLegacyData(ExecutorService executor, OvsdbClient client,
                                OvsdbVtepBackend backend) {
        this.executor = executor;
        this.scheduler = Schedulers.from(executor);
        this.backend = backend;
        this.endPoint = endPointFromOvsdbClient(client);
    }

    // Cache static table contents
    private final ConcurrentHashMap<UUID, LogicalSwitch>
        logicalSwitches = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LogicalSwitch>
        logicalSwitchesByName = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, PhysicalLocator>
        physicalLocators = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<IPv4Addr, Set<PhysicalLocator>>
        physicalLocatorsByIp = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>>
        physicalLocatorSets = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<UUID, Set<UUID>>
        reversePhysicalLocatorSets = new ConcurrentHashMap<>();


    /**
     * Get the 'main' tunnel ip address for this particular vtep end point.
     * Note: we assume there is a single tunnel ip address per vtep... in case
     * there were more than one, we make sure we always return the same one.
     */
    public Option<IPv4Addr> getTunnelIp() {
        return backend.pSwitch.tunnelIp();
    }

    /**
     * Get the list of logical switches directly from the VTEP.
     * This flushes and rebuilds the local cache. Note that this
     * method is intended for testing and debugging purposes, mainly.
     */
    public Expectation<Set<LogicalSwitch>> getLogicalSwitches() {
        final Expectation<Set<LogicalSwitch>> result = new Expectation<>();
        Runnable task = new Runnable() {
            @Override public void run() {
                try {
                    Set<LogicalSwitch> lsSet =
                        backend.retrieveLogicalSwitch(null);
                    logicalSwitches.clear();
                    logicalSwitchesByName.clear();
                    for (LogicalSwitch ls : lsSet) {
                        logicalSwitches.put(ls.uuid(), ls);
                        logicalSwitchesByName.put(ls.name(), ls);
                    }
                    result.success(lsSet);
                } catch (Throwable exc) {
                    result.failure(exc);
                }
            }
        };
        executor.submit(task);
        return result;
    }

    /**
     * Get the logical switch for the given internal id. It checks the cache
     * and, if not present, it tries to retrieve the information from the
     * VTEP.
     */
    private LogicalSwitch getLogicalSwitch(UUID id) throws Exception {
        LogicalSwitch ls = logicalSwitches.get(id);
        if (ls == null) {
            Set<LogicalSwitch> lsSet = backend.retrieveLogicalSwitch(
                backend.lsTable.getUuidMatcher(id));
            if (lsSet.isEmpty())
                return null;
            ls = lsSet.iterator().next(); // there should be a single one
            logicalSwitches.put(ls.uuid(), ls);
            logicalSwitchesByName.put(ls.name(), ls);
        }
        return ls;
    }

    /**
     * Get the logical switch for the given midonet name. It checks the cache
     * and, if not present, it tries to retrieve the information from the
     * VTEP. Note that, according to OVSDB specs, the 'name' should be unique
     * in the table.
     */
    private LogicalSwitch getLogicalSwitch(String name) throws Exception {
        LogicalSwitch ls = logicalSwitchesByName.get(name);
        if (ls == null) {
            Set<LogicalSwitch> lsSet = backend.retrieveLogicalSwitch(
                backend.lsTable.getNameMatcher(name));
            if (lsSet.isEmpty())
                return null;
            ls = lsSet.iterator().next(); // there should be a single one
            logicalSwitches.put(ls.uuid(), ls);
            logicalSwitchesByName.put(ls.name(), ls);
        }
        return ls;
    }

    /**
     * Acquire a logical switch with the given name and try to create it if
     * none exists.
     */
    private LogicalSwitch acquireLogicalSwitch(String name, Integer vni)
        throws Exception {
        LogicalSwitch ls = getLogicalSwitch(name);
        if (ls == null) {
            ls = backend.createLogicalSwitch(
                new LogicalSwitch(null, name, vni, null));
            if (ls != null) {
                logicalSwitches.put(ls.uuid(), ls);
                logicalSwitchesByName.put(ls.name(), ls);
            }
        }
        return ls;
    }

    public Expectation<LogicalSwitch> ensureLogicalSwitch(
        final String name, final Integer vni) {
        final Expectation<LogicalSwitch> result = new Expectation<>();
        Runnable task = new Runnable() {
            @Override public void run() {

            }
        };
        executor.submit(task);
        return result;
    }

    /**
     * Get the list of physical locators directly from the VTEP.
     * This flushes and rebuilds the local cache. Note that this
     * method is intended for testing and debugging purposes, mainly.
     */
    public Expectation<Set<PhysicalLocator>> getPhysicalLocators() {
        final Expectation<Set<PhysicalLocator>> result = new Expectation<>();
        Runnable task = new Runnable() {
            @Override public void run() {
                try {
                    Set<PhysicalLocator> locators =
                        backend.retrievePhysicalLocator(null);
                    physicalLocators.clear();
                    physicalLocatorsByIp.clear();
                    for (PhysicalLocator pl : locators) {
                        physicalLocators.put(pl.uuid(), pl);
                        physicalLocatorsByIp.putIfAbsent(
                            pl.dstIp(), new HashSet<PhysicalLocator>());
                        physicalLocatorsByIp.get(pl.dstIp()).add(pl);
                    }
                    result.success(locators);
                } catch (Throwable exc) {
                    result.failure(exc);
                }
            }
        };
        executor.submit(task);
        return result;
    }

    /**
     * Retrieve the physical locator associated to a tunnel endpoint ip.
     * Note that OVSDB specs do not forbid multiple physical locators for the
     * same ip, so we return a set, which may be empty.
     */
    private Set<PhysicalLocator> getPhysicalLocators(IPv4Addr ip)
        throws Exception {
        Set<PhysicalLocator> locators = physicalLocatorsByIp.get(ip);
        if (locators == null) {
            locators = backend.retrievePhysicalLocator(
                backend.plTable.getDstIpMatcher(ip));
            if (!locators.isEmpty()) {
                physicalLocatorsByIp.putIfAbsent(
                    ip, new HashSet<PhysicalLocator>());
                for (PhysicalLocator it: locators) {
                    physicalLocators.put(it.uuid(), it);
                    physicalLocatorsByIp.get(it.dstIp()).add(it);
                }
            }
        }
        return locators;
    }

    /**
     * Retrieve the information about a specific physical locator.
     * Can be null if the locator does not exist.
     */
    private PhysicalLocator getPhysicalLocator(UUID id) throws Exception {
        PhysicalLocator pl = physicalLocators.get(id);
        if (pl == null) {
            Set<PhysicalLocator> locators = backend.retrievePhysicalLocator(
                backend.plTable.getUuidMatcher(id));
            if (locators.isEmpty())
                return null;
            for (PhysicalLocator it: locators) {
                physicalLocators.put(it.uuid(), it);
                physicalLocatorsByIp.putIfAbsent(
                    it.dstIp(), new HashSet<PhysicalLocator>());
                physicalLocatorsByIp.get(it.dstIp()).add(it);
            }
            pl = locators.iterator().next();
        }
        return pl;
    }

    /**
     * Acquire a physical locator associated to a given tunnel endpoint ip,
     * and try to create it if none exists. Note that OVSDB does not indicate
     * that the ip field in the physical locator table must be unique, so, in
     * case multiple locators exist for the same ip, one of them is returned
     * randomly.
     */
    private PhysicalLocator acquirePhysicalLocator(IPv4Addr ip)
        throws Exception {
        Set<PhysicalLocator> plSet = getPhysicalLocators(ip);
        if (!plSet.isEmpty()) {
            return plSet.iterator().next();
        } else {
            PhysicalLocator loc = PhysicalLocator.apply(ip);
            PhysicalLocator pl = backend.createPhysicalLocator(loc);
            physicalLocators.put(pl.uuid(), pl);
            physicalLocatorsByIp.putIfAbsent(
                ip, new HashSet<PhysicalLocator>());
            physicalLocatorsByIp.get(ip).add(pl);
            return pl;
        }
    }

    /**
     * Get locator sets. This method is mainly for debugging and testing
     * purposes
     */
    public Expectation<Map<java.util.UUID, Set<java.util.UUID>>>
        getPhysicalLocatorSets() throws Exception {
        final Expectation<Map<java.util.UUID, Set<java.util.UUID>>> result =
            new Expectation<>();
        Runnable task = new Runnable() {
            @Override public void run() {
                try {
                    Map<UUID, Set<UUID>> internalSet =
                        backend.retrievePhysicalLocatorSet(null);
                    physicalLocatorSets.clear();
                    physicalLocatorSets.putAll(internalSet);
                    reversePhysicalLocatorSets.clear();

                    // Unefficient, but just for debugging purposes
                    Map<java.util.UUID, Set<java.util.UUID>> locatorSets =
                        new HashMap<>();
                    for (UUID k : internalSet.keySet()) {
                        Set<UUID> locators = new HashSet<>();
                        locators.addAll(internalSet.get(k));
                        locatorSets.put(k, locators);
                        // for reverse maps, we only care about singletons
                        if (internalSet.get(k).size() == 1) {
                            UUID s = internalSet.get(k).iterator().next();
                            reversePhysicalLocatorSets.putIfAbsent(
                                s, new HashSet<UUID>());
                            reversePhysicalLocatorSets.get(s).add(k);
                        }
                    }
                    result.success(locatorSets);
                } catch (Throwable exc) {
                    result.failure(exc);
                }
            }
        };
        executor.submit(task);
        return result;
    }

    /**
     * Get the locator ids associated to a particular locator set
     * The returned set may be empty.
     */
    private Set<UUID> getPhysicalLocatorSet(UUID id) throws Exception {
        Set<UUID> locatorIds = physicalLocatorSets.get(id);
        if (locatorIds == null) {
            Map<UUID, Set<UUID>> locatorSet =
                backend.retrievePhysicalLocatorSet(
                    backend.plSetTable.getUuidMatcher(id));
            locatorIds = locatorSet.get(id);
            if (locatorIds != null && !locatorIds.isEmpty()) {
                physicalLocatorSets.put(id, locatorIds);
                // for reverse maps, we only care about singletons
                if (locatorIds.size() == 1) {
                    UUID locId = locatorIds.iterator().next();
                    reversePhysicalLocatorSets.putIfAbsent(
                        locId, new HashSet<UUID>());
                    reversePhysicalLocatorSets.get(locId).add(id);
                }
            }
        }
        return (locatorIds == null)? new HashSet<UUID>(): locatorIds;
    }

    /**
     * Get the locator sets containing a particular locator
     * The returned set may be empty.
     */
    private Set<UUID> findPhysicalLocatorSet(UUID locatorId) throws Exception {
        Set<UUID> locatorSets = reversePhysicalLocatorSets.get(locatorId);
        if (locatorSets == null) {
            Map<UUID, Set<UUID>> locatorSetInfo =
                backend.retrievePhysicalLocatorSet(
                    backend.plSetTable.getLocatorMatcher(locatorId));
            physicalLocatorSets.putAll(locatorSetInfo);
            locatorSets = new HashSet<>();
            for (UUID k: locatorSetInfo.keySet()) {
                // for reverse maps, we only care about singletons
                Set<UUID> locatorIds = locatorSetInfo.get(k);
                if (locatorIds.size() == 1) {
                    locatorSets.add(k);
                    UUID locId = locatorIds.iterator().next();
                    reversePhysicalLocatorSets.putIfAbsent(
                        locId, new HashSet<UUID>());
                    reversePhysicalLocatorSets.get(locId).add(k);
                }
            }
        }
        return locatorSets;
    }

    /**
     * Acquire a physical locator set associated to a given tunnel endpoint ip,
     * and try to create it if none exists. Note that OVSDB does not guarantee
     * a unique locator set per ip, so, in case multiple locator sets exist for
     * the same ip, one of them is returned * randomly.
     */
    private UUID acquirePhysicalLocatorSet(UUID locatorId)
        throws Exception {
        Set<UUID> sets = findPhysicalLocatorSet(locatorId);
        if (sets.isEmpty()) {
            Map<UUID, Set<UUID>> locatorSetInfo =
                backend.createPhysicalLocatorSet(locatorId);
            physicalLocatorSets.putAll(locatorSetInfo);
            for (UUID k: locatorSetInfo.keySet()) {
                // for reverse maps, we only care about singletons
                Set<UUID> locatorIds = locatorSetInfo.get(k);
                if (locatorIds.size() == 1) {
                    sets.add(k);
                    UUID locId = locatorIds.iterator().next();
                    reversePhysicalLocatorSets.putIfAbsent(
                        locId, new HashSet<UUID>());
                    reversePhysicalLocatorSets.get(locId).add(k);
                }
            }
            if (sets.isEmpty()) {
                throw new VtepException(endPoint,
                    "cannot generate locator set for locator: " + locatorId);
            }
        }
        return sets.iterator().next();
    }

    /**
     * Apply mac location updates
     */
    public Subscription
    applyRemoteMacLocations(Observable<MacLocation> updates) {
        return updates.observeOn(scheduler).subscribe(
            new Subscriber<MacLocation>() {
            @Override public void onCompleted() {
                log.debug("completed MAC location updates");
                this.unsubscribe();
            }
            @Override public void onError(Throwable err) {
                log.warn("error on MAC location updates", err);
                this.unsubscribe();
            }
            @Override public void onNext(MacLocation macLocation) {
                log.debug("Received MAC location update: {}", macLocation);
                if (macLocation.vxlanTunnelEndpoint() != null) {
                    applyRemoteMacAddition(macLocation);
                } else {
                    applyRemoteMacDeletion(macLocation);
                }
            }
        });
    }

    private void applyRemoteMacAddition(MacLocation macLocation) {
        MacEntry entry = macLocationToMacEntry(macLocation);
        if (entry == null)
            return;
        try {
            if (entry.isUcast()) {
                backend.storeMacEntry(backend.uMacsRemoteTable, entry);
            } else {
                backend.storeMacEntry(backend.mMacsRemoteTable, entry);
            }
        } catch (Throwable exc) {
            log.error("failed to store mac table entry: {}", entry, exc);
        }
    }

    private void applyRemoteMacDeletion(MacLocation macLocation) {
        MacEntry entry = macLocationToMacEntry(macLocation);
        if (entry == null)
            return;
        try {
            if (entry.isUcast()) {
                backend.deleteMacEntry(backend.uMacsRemoteTable, entry);
            } else {
                backend.deleteMacEntry(backend.mMacsRemoteTable, entry);
            }
        } catch (Throwable exc) {
            log.error("failed to store mac table entry: {}", entry, exc);
        }
    }

    /**
     * Convert a mac entry to a mac location
     * (logs and returns null in case of error).
     */
    private MacLocation macEntryToMacLocation(MacEntry entry) {
        try {
            LogicalSwitch ls = getLogicalSwitch(entry.logicalSwitchId());
            if (ls == null) {
                log.warn("unknown logical switch in mac entry: {}",
                         entry.logicalSwitchId());
                return null;
            }

            PhysicalLocator loc = null;
            if (entry instanceof McastMac) {
                Set<UUID> locatorSet = getPhysicalLocatorSet(
                    ((McastMac)entry).locatorSet());
                if (locatorSet.isEmpty()) {
                    log.warn("no physical locators found for mac entry: {}",
                             entry);
                    return null;
                }
                if (locatorSet.size() > 1) {
                    log.warn("multiple locators for mac entry: {} - {}",
                             entry, locatorSet);
                }
                loc = getPhysicalLocator(locatorSet.iterator().next());
            } else if (entry instanceof UcastMac) {
                loc = getPhysicalLocator(((UcastMac)entry).locator());
            }
            if (loc == null) {
                log.warn("undetermined physical locator in mac entry: {}",
                         entry);
                return null;
            }

            return
                new MacLocation(entry.mac(), entry.ip(), ls.name(),
                                loc.dstIp());
        } catch (Throwable exc) {
            log.error("cannot translate mac entry to mac location: " + entry,
                      exc);
            return null;
        }
    }

    /**
     * Convert a mac location into a mac entry
     * (logs and returns null in case of error).
     */
    private MacEntry macLocationToMacEntry(MacLocation ml) {
        try {
            LogicalSwitch ls = getLogicalSwitch(ml.logicalSwitchName());
            if (ls == null) {
                log.warn("unknown logical switch in mac location: {}",
                         ml.logicalSwitchName());
                return null;
            }
            UUID locatorId = null;
            if (ml.vxlanTunnelEndpoint() != null) {
                PhysicalLocator loc =
                    acquirePhysicalLocator(ml.vxlanTunnelEndpoint());
                if (ml.mac().isMcast()) {
                    locatorId = acquirePhysicalLocatorSet(loc.uuid());
                } else {
                    locatorId = loc.uuid();
                }
            }
            if (ml.mac().isUcast())
                return new UcastMac(null, ls.uuid(), ml.mac(), ml.ipAddr(),
                                         locatorId);
            else
                return new McastMac(null, ls.uuid(), ml.mac(), ml.ipAddr(),
                                         locatorId);
        } catch (Throwable exc) {
            log.error("cannot translate mac location to mac entry", exc);
            return null;
        }
    }
}
