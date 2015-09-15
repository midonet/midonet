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
package org.midonet.midolman.state.zkManagers;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.WatchableZkManager;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;

public class TunnelZoneZkManager
        extends AbstractZkManager<UUID, TunnelZone.Data>
        implements WatchableZkManager<UUID, TunnelZone.Data> {

    private final static Logger log =
        LoggerFactory.getLogger(TunnelZoneZkManager.class);

    /**
     * Initializes a TunnelZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     */
    public TunnelZoneZkManager(ZkManager zk, PathBuilder paths,
                               Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getTunnelZonePath(id);
    }

    @Override
    protected Class<TunnelZone.Data> getConfigClass() {
        return TunnelZone.Data.class;
    }

    public List<UUID> getZoneIds() throws StateAccessException {
        return getUuidList(paths.getTunnelZonesPath());
    }

    public TunnelZone getZone(UUID zoneId, Directory.TypedWatcher watcher)
            throws StateAccessException, SerializationException {

        if (!exists(zoneId)) {
            return null;
        }

        return new TunnelZone(zoneId, super.get(zoneId, watcher));
    }

    public boolean membershipExists(UUID zoneId, UUID hostId)
            throws StateAccessException {
        return zk.exists(paths.getTunnelZoneMembershipPath(zoneId, hostId));
    }


    public TunnelZone.HostConfig getZoneMembership(UUID zoneId, UUID hostId, Directory.TypedWatcher watcher)
            throws StateAccessException, SerializationException {

        String zoneMembershipPath =
                paths.getTunnelZoneMembershipPath(zoneId, hostId);
        if (!zk.exists(zoneMembershipPath)) {
            return null;
        }

        byte[] bytes = zk.get(zoneMembershipPath, watcher);

        TunnelZone.HostConfig.Data data =
                serializer.deserialize(bytes,
                                       TunnelZone.HostConfig.Data.class);

        return new TunnelZone.HostConfig(hostId, data);
    }

    public Set<UUID> getZoneMemberships(UUID zoneId, Directory.TypedWatcher watcher)
        throws StateAccessException {

        String zoneMembershipsPath =
            paths.getTunnelZoneMembershipsPath(zoneId);

        if (!zk.exists(zoneMembershipsPath))
            return Collections.emptySet();

        return CollectionFunctors.map(
                zk.getChildren(zoneMembershipsPath, watcher),
            new Functor<String, UUID>() {
                @Override
                public UUID apply(String arg0) {
                    return UUID.fromString(arg0);
                }
            }, new HashSet<UUID>()
        );
    }

    /**
     * Creates a new tunnel zone, but validates that there is not one already
     * with the same name. This same check is done in the API but added here
     * for extra safety.
     */
    public UUID createZone(TunnelZone zone, Directory.TypedWatcher watcher)
            throws StateAccessException, SerializationException {

        log.debug("Creating availability zone {}", zone);
        List<Op> createMulti = new ArrayList<Op>();

        UUID zoneId = zone.getId();

        if (zoneId == null) {
            zoneId = UUID.randomUUID();
        }

        for (UUID tzId : this.getZoneIds()) {
            TunnelZone tz = this.getZone(tzId, null);
            if (tz.getType().equals(zone.getType()) &&
                tz.getName().equalsIgnoreCase(zone.getName())) {
                throw new StatePathExistsException(
                    "There is already a tunnel zone with the same type and" +
                    "name", tz.getId());
            }
        }

        createMulti.add(simpleCreateOp(zoneId, zone.getData()));

        createMulti.add(
                zk.getPersistentCreateOp(
                paths.getTunnelZoneMembershipsPath(zoneId),
                null
            )
        );

        zk.multi(createMulti);
        zone.setId(zoneId);
        return zoneId;
    }

}
