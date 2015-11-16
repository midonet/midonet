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
package org.midonet.midolman.host.state;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;

import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.WatchableZkManager;
import org.midonet.cluster.data.storage.Directory;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

/**
 * Wrapper class over a Directory that handled setting and reading data related
 * to hosts and interfaces associated with the hosts.
 */
public class HostZkManager
        extends AbstractZkManager<UUID, HostDirectory.Metadata>
        implements WatchableZkManager<UUID, HostDirectory.Metadata> {

    private final static Logger log =
        LoggerFactory.getLogger(HostZkManager.class);

    /**
     * Initializes a HostZkManager object with a ZooKeeper client
     * and the root path of the ZooKeeper directory.
     */
    @Inject
    public HostZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getHostPath(id);
    }

    @Override
    protected Class<HostDirectory.Metadata> getConfigClass() {
        return HostDirectory.Metadata.class;
    }

    public boolean isAlive(UUID id) throws StateAccessException {
        return zk.exists(paths.getHostPath(id) + "/alive");
    }

    public boolean isAlive(UUID id, Runnable watcher) throws StateAccessException {
        return zk.exists(paths.getHostPath(id) + "/alive", watcher);
    }

    public boolean isAlive(UUID id, Watcher watcher) throws StateAccessException {
        return zk.exists(paths.getHostPath(id) + "/alive", watcher);
    }

    /**
     * Get the flooding proxy weight value for the host.
     *
     * The returned value is null if the value was not present/initialized in
     * zk; it is the responsibility of the caller to convert that into the
     * required default value (or take any other necessary actions).
     * @param hostId is the host id
     * @return the proxy weight value or null if it was not initialized.
     */
    public Integer getFloodingProxyWeight(UUID hostId)
        throws StateAccessException, SerializationException {
        String path = paths.getHostFloodingProxyWeightPath(hostId);
        String hostPath = paths.getHostPath(hostId);
        if (zk.exists(hostPath) && !zk.exists(path)) {
            return null;
        } else {
            byte[] data = zk.get(path);
            return serializer.deserialize(data, int.class);
        }
    }

    public List<UUID> getHostIds() throws StateAccessException {
        return getHostIds(null);
    }

    public List<UUID> getHostIds(Directory.TypedWatcher watcher)
            throws StateAccessException {
        return getUuidList(paths.getHostsPath(), watcher);
    }

    public boolean existsInterface(UUID hostId, String interfaceName)
            throws StateAccessException {
        return zk.exists(paths.getHostInterfacePath(hostId,
                                                       interfaceName));
    }

    public HostDirectory.Interface getInterfaceData(UUID hostId,
                                                    String name)
            throws StateAccessException, SerializationException {

        try {
            byte[] data = zk.get(
                paths.getHostInterfacePath(hostId, name));

            return serializer.deserialize(data, HostDirectory.Interface.class);
        } catch (SerializationException e) {
            throw new SerializationException(
                "Could not deserialize host interface metadata for id: " +
                    hostId + " / " + name,
                e, HostDirectory.Metadata.class);
        }
    }

    /**
     * Will return the collection of names for all the interfaces presently
     * described under the provided host entry.
     *
     * @param hostId the host id for which we want the interfaces
     * @return the collection of interface names
     * @throws StateAccessException if we weren't able to properly communicate
     *                              with the datastore.
     */
    public Set<String> getInterfaces(UUID hostId) throws StateAccessException {

        String path = paths.getHostInterfacesPath(hostId);
        if (!zk.exists(path)) {
            return Collections.emptySet();
        }

        return zk.getChildren(path);
    }

}
