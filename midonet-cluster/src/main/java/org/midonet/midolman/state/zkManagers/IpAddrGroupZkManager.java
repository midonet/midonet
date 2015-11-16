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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.google.inject.Inject;

import org.midonet.cluster.backend.zookeeper.StateAccessException;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.ZkManager;
import org.midonet.nsdb.ConfigWithProperties;

/**
 * Class to manage the router ZooKeeper data.
 */
public class IpAddrGroupZkManager extends
        AbstractZkManager<UUID, IpAddrGroupZkManager.IpAddrGroupConfig> {

    public static class IpAddrGroupConfig extends ConfigWithProperties {

        public IpAddrGroupConfig() {
        }

        public UUID id;
        public String name;
    }

    /**
     * Initializes a IpAddrGroupZkManager object with a ZooKeeper client and the
     * root path of the ZooKeeper directory.
     */
    @Inject
    public IpAddrGroupZkManager(ZkManager zk, PathBuilder paths,
                            Serializer serializer) {
        super(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getIpAddrGroupPath(id);
    }

    @Override
    protected Class<IpAddrGroupConfig> getConfigClass() {
        return IpAddrGroupConfig.class;
    }

    /**
     * Checks whether a IP addr group with the given ID exists.
     *
     * @param id
     *            IP addr group ID to check
     * @return True if exists
     * @throws StateAccessException
     */
    public boolean exists(UUID id) throws StateAccessException {
        return zk.exists(paths.getIpAddrGroupPath(id));
    }

    /**
     * Gets a IpAddrGroupConfig object with the given ID.
     *
     * @param id
     *            The ID of the ip addr group.
     * @return IpAddrGroupConfig object
     * @throws StateAccessException
     */
    public IpAddrGroupConfig get(UUID id) throws StateAccessException,
            SerializationException {
        byte[] data = zk.get(paths.getIpAddrGroupPath(id));
        return serializer.deserialize(data, IpAddrGroupConfig.class);
    }

    public Set<UUID> getAllIds() throws StateAccessException {
        String path = paths.getIpAddrGroupsPath();
        Set<String> groups = zk.getChildren(path);
        Set<UUID> ids = new HashSet<>();
        for (String group : groups) {
            ids.add(UUID.fromString(group));
        }
        return ids;
    }

    public List<IpAddrGroupConfig> list()
            throws StateAccessException, SerializationException {
        String path = paths.getIpAddrGroupsPath();
        Set<String> groups = zk.getChildren(path);
        List<IpAddrGroupConfig> configs = new ArrayList<>();
        for (String group : groups) {
            configs.add(get(UUID.fromString(group)));
        }
        return configs;
    }

    public Set<String> getAddrs(UUID id) throws StateAccessException {
        return zk.getChildren(paths.getIpAddrGroupAddrsPath(id));
    }

}
