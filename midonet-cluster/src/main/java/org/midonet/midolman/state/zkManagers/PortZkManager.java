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

import com.google.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.PortConfig;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.ZkManager;

/**
 * Class to manage the port ZooKeeper data.
 */
public class PortZkManager extends AbstractZkManager<UUID, PortConfig> {

    private final static Logger log =
            LoggerFactory.getLogger(PortZkManager.class);

    /**
     * Initializes a PortZkManager object with a ZooKeeper client and the root
     * path of the ZooKeeper directory.
     */
    @Inject
    public PortZkManager(ZkManager zk, PathBuilder paths,
                         Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getPortPath(id);
    }

    @Override
    protected Class<PortConfig> getConfigClass() {
        return PortConfig.class;
    }

    public <T extends PortConfig> T get(UUID id, Class<T> clazz)
            throws StateAccessException, SerializationException {
        return get(id, clazz, null);
    }

    public <T extends PortConfig> T get(UUID id, Class<T> clazz,
            Runnable watcher) throws StateAccessException,
            SerializationException {
        log.debug("Get PortConfig: " + id);
        byte[] data = zk.get(paths.getPortPath(id), watcher);
        return serializer.deserialize(data, clazz);
    }

    public boolean isActivePort(UUID portId) throws StateAccessException {
        String path = paths.getPortActivePath(portId);
        log.debug("checking port liveness");
        return zk.exists(path) && (zk.getChildren(path).size() > 0);
    }

    /**
     * Gets a list of router port IDs for a given router
     *
     * @return A list of router port IDs.
     * @throws StateAccessException
     */
    public List<UUID> getRouterPortIDs(UUID routerId, Runnable watcher)
            throws StateAccessException {
        return listPortIDs(paths.getRouterPortsPath(routerId), watcher);
    }

    public List<UUID> getRouterPortIDs(UUID routerId)
            throws StateAccessException {
        return getRouterPortIDs(routerId, null);
    }

    public List<UUID> listPortIDs(String path, Runnable watcher)
            throws StateAccessException {
        return getUuidList(path, watcher);
    }

}
