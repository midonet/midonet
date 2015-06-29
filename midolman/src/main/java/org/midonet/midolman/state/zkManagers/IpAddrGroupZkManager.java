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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.AbstractZkManager;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.DirectoryCallback;
import org.midonet.midolman.state.DirectoryCallbackFactory;
import org.midonet.midolman.state.NoStatePathException;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.StatePathExistsException;
import org.midonet.midolman.state.ZkManager;
import org.midonet.packets.IPAddr$;
import org.midonet.util.functors.Functors;

/**
 * Class to manage the router ZooKeeper data.
 */
public class IpAddrGroupZkManager extends
        AbstractZkManager<UUID, IpAddrGroupZkManager.IpAddrGroupConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(IpAddrGroupZkManager.class);

    public static class IpAddrGroupConfig extends ConfigWithProperties {

        public IpAddrGroupConfig() {
        }

        public IpAddrGroupConfig(String name) {
            this(null, name);
        }

        public IpAddrGroupConfig(UUID id, String name) {
            this.id = id;
            this.name = name;
        }

        public UUID id;
        public String name;
    }

    private final RuleZkManager ruleDao;

    /**
     * Initializes a IpAddrGroupZkManager object with a ZooKeeper client and the
     * root path of the ZooKeeper directory.
     *
     * @param zk
     *         Zk data access class
     * @param paths
     *         PathBuilder class to construct ZK paths
     * @param serializer
     *         ZK data serialization class
     * @versionProvider
     *         Provides versioning information
     */
    public IpAddrGroupZkManager(ZkManager zk, PathBuilder paths,
                            Serializer serializer) {
        super(zk, paths, serializer);
        ruleDao = new RuleZkManager(zk, paths, serializer);
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
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new IP addr group.
     *
     * @param id
     *            IP addr group ID
     * @param config
     *            IpAddrGroupConfig object.
     * @return A list of Op objects to represent the operations to perform.
     */
    public List<Op> prepareCreate(UUID id, IpAddrGroupConfig config)
            throws StateAccessException, SerializationException {
        log.debug("IpAddrGroupZkManager.prepareCreate: entered");

        List<Op> ops = new ArrayList<>();
        ops.add(simpleCreateOp(id, config));

        // Directory for member addresses.
        ops.add(Op.create(paths.getIpAddrGroupAddrsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        // Keep track of rules that reference this group.
        ops.add(Op.create(paths.getIpAddrGroupRulesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        log.debug("IpAddrGroupZkManager.prepareCreate: exiting");
        return ops;
    }

    /**
     * Constructs a list of operations to perform in a IP addr group deletion.
     *
     * @param id
     *            The ID of a IP addr group to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public List<Op> prepareDelete(UUID id) throws StateAccessException,
            SerializationException {

        List<Op> ops = new ArrayList<>();

        // Delete all the rules that reference this IP addr group
        String rulesPath = paths.getIpAddrGroupRulesPath(id);
        List<UUID> ruleIds = getUuidList(rulesPath);
        for (UUID ruleId : ruleIds) {
            ops.addAll(ruleDao.prepareDelete(ruleId));
        }

        // Delete addresses.
        Set<String> addrs = zk.getChildren(paths.getIpAddrGroupAddrsPath(id));
        for (String addr : addrs) {
            ops.add(Op.delete(paths.getIpAddrGroupAddrPath(id, addr), -1));
        }

        ops.add(Op.delete(rulesPath, -1));
        ops.add(Op.delete(paths.getIpAddrGroupAddrsPath(id), -1));
        ops.add(Op.delete(paths.getIpAddrGroupPath(id), -1));

        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new IP addr group.
     *
     * @return The UUID of the newly created object.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public UUID create(IpAddrGroupConfig config) throws StateAccessException,
            SerializationException {
        UUID id = config.id != null ? config.id : UUID.randomUUID();
        zk.multi(prepareCreate(id, config));
        return id;
    }

    /***
     * Deletes a IP addr group and its related data from the directories
     * atomically.
     *
     * @param id
     *            ID of the IP addr group to delete.
     * @throws org.midonet.midolman.state.StateAccessException
     */
    public void delete(UUID id) throws StateAccessException,
            SerializationException {
        zk.multi(prepareDelete(id));
    }

    /**
     * Checks whether a IP addr group with the given ID exists.
     *
     * @param id
     *            IP addr group ID to check
     * @return True if exists
     * @throws org.midonet.midolman.state.StateAccessException
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
     * @throws org.midonet.midolman.state.StateAccessException
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

    public boolean isMember(UUID groupId, String addr)
            throws StateAccessException {
        addr = IPAddr$.MODULE$.canonicalize(addr);
        return zk.exists(paths.getIpAddrGroupAddrPath(groupId, addr));
    }

    public boolean isOnlyMember(UUID groupId, String addr, UUID portId)
            throws StateAccessException {

        if (!zk.exists(paths.getIpAddrGroupAddrPath(groupId, addr)))
            return false;

        Set<String> ports = zk.getChildren(
                paths.getIpAddrGroupAddrPortsPath(groupId, addr));
        return ports.size() == 1 && ports.contains(portId.toString());
    }

    public void addAddr(UUID groupId, String addr)
            throws StateAccessException, SerializationException {
        addr = IPAddr$.MODULE$.canonicalize(addr);
        try {
            zk.addPersistent(paths.getIpAddrGroupAddrPath(groupId, addr), null);
        } catch (StatePathExistsException ex) {
            // This group already has this address. Do nothing.
        }
    }

    public Set<String> getAddrs(UUID id) throws StateAccessException {
        return zk.getChildren(paths.getIpAddrGroupAddrsPath(id));
    }

    public void getAddrsAsync(UUID ipAddrGroupId,
                              DirectoryCallback<Set<String>> addrsCallback,
                              Directory.TypedWatcher watcher) {
        zk.asyncGetChildren(
                paths.getIpAddrGroupAddrsPath(ipAddrGroupId),
                DirectoryCallbackFactory.transform(
                        addrsCallback, Functors.<Set<String>>identity()),
                watcher);
    }

    public void removeAddr(UUID groupId, String addr)
            throws StateAccessException, SerializationException {
        addr = IPAddr$.MODULE$.canonicalize(addr);
        try {
            zk.delete(paths.getIpAddrGroupAddrPath(groupId, addr));
        } catch (NoStatePathException ex) {
            // Either the group doesn't exist, or the address doesn't.
            // Either way, the desired postcondition is met.
        }
    }

    public void prepareAddAdr(List<Op> ops, UUID groupId, String addr,
                              UUID portId, boolean isRebuild)
            throws StateAccessException, SerializationException {
        addr = IPAddr$.MODULE$.canonicalize(addr);
        if (!isMember(groupId, addr) ||
                (isRebuild && isOnlyMember(groupId, addr, portId))) {
            ops.add(zk.getPersistentCreateOp(
                    paths.getIpAddrGroupAddrPath(groupId, addr), null));
            ops.add(zk.getPersistentCreateOp(
                    paths.getIpAddrGroupAddrPortsPath(groupId, addr), null));
        }
        ops.add(zk.getPersistentCreateOp(
                paths.getIpAddrGroupAddrPortPath(groupId, addr, portId), null));
    }

    public void prepareRemoveAddr(List<Op> ops, UUID groupId, String addr,
                                  UUID portId)
            throws StateAccessException, SerializationException {
        addr = IPAddr$.MODULE$.canonicalize(addr);

        ops.add(zk.getDeleteOp(
                paths.getIpAddrGroupAddrPortPath(groupId, addr, portId)));

        if (isOnlyMember(groupId, addr, portId)) {
            // This is the last port, so we can delete the addr entry
            ops.add(zk.getDeleteOp(
                    paths.getIpAddrGroupAddrPortsPath(groupId, addr)));
            ops.add(zk.getDeleteOp(
                    paths.getIpAddrGroupAddrPath(groupId, addr)));
        }
    }
}
