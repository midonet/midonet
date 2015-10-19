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

import java.util.List;
import java.util.UUID;

import org.midonet.cluster.WatchableZkManager;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

public class TunnelZoneZkManager
        extends AbstractZkManager<UUID, TunnelZone.Data>
        implements WatchableZkManager<UUID, TunnelZone.Data> {

    /**
     * Initializes a TunnelZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
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

}
