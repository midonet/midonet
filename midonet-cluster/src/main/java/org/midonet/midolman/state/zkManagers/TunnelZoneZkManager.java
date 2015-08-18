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

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;

import com.google.common.util.concurrent.SettableFuture;
import com.google.inject.Inject;

import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.WatchableZkManager;
import org.midonet.cluster.data.TunnelZone;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.util.functors.CollectionFunctors;
import org.midonet.util.functors.Functor;

public class TunnelZoneZkManager
        extends AbstractZkManager<UUID, TunnelZone.Data>
        implements WatchableZkManager<UUID, TunnelZone.Data> {

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
    @Inject
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

    public Set<TunnelZone.HostConfig> getZoneMembershipsForHosts(
            UUID zoneId, Set<UUID> hosts)
            throws StateAccessException, SerializationException {
        Map<UUID, Future<byte[]>> futures =
            new HashMap<UUID, Future<byte[]>>();
        for (UUID host : hosts) {
            String zoneMembershipPath =
                paths.getTunnelZoneMembershipPath(zoneId, host);
            final SettableFuture<byte[]> f = SettableFuture.create();
            zk.asyncGet(zoneMembershipPath,
                        new DirectoryCallback<byte[]>(){
                            @Override
                            public void onTimeout(){
                                f.setException(new TimeoutException());
                            }

                            @Override
                            public void onError(KeeperException e) {
                                f.setException(e);
                            }

                            @Override
                            public void onSuccess(byte[] data) {
                                f.set(data);
                            }
                        }, null);
            futures.put(host, f);
        }

        Set<TunnelZone.HostConfig> configs = new HashSet<TunnelZone.HostConfig>();
        for (Map.Entry<UUID, Future<byte[]>> entry : futures.entrySet()) {
            try {
                TunnelZone.HostConfig.Data config =
                    serializer.deserialize(entry.getValue().get(),
                                           TunnelZone.HostConfig.Data.class);
                configs.add(new TunnelZone.HostConfig(entry.getKey(), config));
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw new StateAccessException(ie);
            } catch (ExecutionException ee) {
                if (ee.getCause() instanceof KeeperException.NoNodeException) {
                    // ignore, membership simply didn't exist
                } else {
                    throw new StateAccessException(ee.getCause());
                }
            }
        }
        return configs;
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

}
