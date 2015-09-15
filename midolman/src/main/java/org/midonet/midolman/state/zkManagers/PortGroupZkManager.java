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

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.*;

import java.util.*;

/**
 * Class to manage the router ZooKeeper data.
 */
public class PortGroupZkManager
        extends AbstractZkManager<UUID, PortGroupZkManager.PortGroupConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(PortGroupZkManager.class);

    public static class PortGroupConfig extends ConfigWithProperties {

        public PortGroupConfig() {
            super();
        }

        public PortGroupConfig(String name) {
            this(name, false);
        }

        public PortGroupConfig(String name, boolean stateful) {
            super();
            this.name = name;
            this.stateful = stateful;
        }

        public String name;
        public boolean stateful;
    }

    private final PortZkManager portDao;
    private final RuleZkManager ruleDao;

    /**
     * Initializes a PortGroupZkManager object with a ZooKeeper client and the
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
    public PortGroupZkManager(ZkManager zk, PathBuilder paths,
                              Serializer serializer) {
        super(zk, paths, serializer);
        portDao = new PortZkManager(zk, paths, serializer);
        ruleDao = new RuleZkManager(zk, paths, serializer);
    }

    @Override
    protected String getConfigPath(UUID id) {
        return paths.getPortGroupPath(id);
    }

    @Override
    protected Class<PortGroupConfig> getConfigClass() {
        return PortGroupConfig.class;
    }

    /**
     * Constructs a list of ZooKeeper update operations to perform when adding a
     * new port group.
     *
     * @param id
     *            Port Group ID
     * @param config
     *            PortGroupConfig object.
     * @return A list of Op objects to represent the operations to perform.
     */
    public List<Op> prepareCreate(UUID id, PortGroupConfig config)
            throws StateAccessException, SerializationException {
        log.debug("PortGroupZkManager.prepareCreate: entered");

        List<Op> ops = new ArrayList<Op>();
        ops.add(simpleCreateOp(id, config));

        // Keep the references to ports and rules that reference it.
        ops.add(Op.create(paths.getPortGroupPortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(paths.getPortGroupRulesPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));

        log.debug("PortGroupZkManager.prepareCreate: exiting");
        return ops;
    }

    public List<Op> prepareUpdate(UUID id, PortGroupConfig config)
            throws StateAccessException, SerializationException {
        List<Op> ops = new ArrayList<Op>();
        PortGroupConfig oldConfig = get(id);
        boolean dataChanged = false;

        String newName = config.name;
        String oldName = oldConfig.name;
        if (newName == null ? oldName != null : !newName.equals(oldName))
            dataChanged = true;

        if (config.stateful != oldConfig.stateful)
            dataChanged = true;

        if (dataChanged) {
            config.properties.clear();
            config.properties.putAll(oldConfig.properties);
            ops.add(Op.setData(paths.getPortGroupPath(id),
                    serializer.serialize(config), -1));
        }

        return ops;
    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new port group.
     *
     * @return The UUID of the newly created object.
     * @throws StateAccessException
     */
    public UUID create(PortGroupConfig config) throws StateAccessException,
            SerializationException {
        UUID id = UUID.randomUUID();
        zk.multi(prepareCreate(id, config));
        return id;
    }

    public boolean portIsMember(UUID id, UUID portId)
        throws StateAccessException{
        String path = paths.getPortGroupPortPath(id, portId);
        return zk.exists(path);
    }

    public void addPortToPortGroup(UUID id, UUID portId)
            throws StateAccessException, SerializationException {

        // Make sure that port group ID is valid
        if (!exists(id)) {
            throw new IllegalArgumentException("Port Group does not exist: "
                    + id);
        }

        // Check to make sure it's not a member already
        if (portIsMember(id, portId)) {
            return;
        }

        PortConfig port = portDao.get(portId);

        // Set the port's port group
        if (port.portGroupIDs == null) {
            port.portGroupIDs = new HashSet<UUID>();
        }
        port.portGroupIDs.add(id);

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.setData(paths.getPortPath(portId),
                serializer.serialize(port), -1));
        ops.add(Op.create(paths.getPortGroupPortPath(id, portId), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        zk.multi(ops);
    }

    public void removePortFromPortGroup(UUID id, UUID portId)
            throws StateAccessException, SerializationException {

        // Check to make sure that it exists and it's a member
        if (!exists(id) || !portIsMember(id, portId)) {
            return;
        }

        PortConfig port = portDao.get(portId);

        // If for some reason the port does not contain this port group,
        // it's likely a bug but just leave gracefully.
        if (port.portGroupIDs == null || !port.portGroupIDs.contains(id)) {
            log.warn("PortGroup (" + id + ") - Port (" + portId +
                    ") relationship is inconsistent in ZK.");
            return;
        }

        port.portGroupIDs.remove(id);

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.setData(paths.getPortPath(portId),
                serializer.serialize(port), -1));
        ops.add(Op.delete(paths.getPortGroupPortPath(id, portId), -1));
        zk.multi(ops);

    }
}
