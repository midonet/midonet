/*
 * Copyright (c) 2011-2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state.zkManagers;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Class to manage the router ZooKeeper data.
 */
public class PortGroupZkManager
        extends AbstractZkManager<UUID, PortGroupZkManager.PortGroupConfig> {

    private final static Logger log = LoggerFactory
            .getLogger(PortGroupZkManager.class);

    public static class PortGroupConfig extends BaseConfig {

        public PortGroupConfig() {
            super();
        }

        public PortGroupConfig(String name) {
            super();
            this.name = name;
        }

        public String name;
        public Map<String, String> properties = new HashMap<String, String>();
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

    /**
     * Constructs a list of operations to perform in a port group deletion.
     *
     * @param id
     *            The ID of a port group to delete.
     * @return A list of Op objects representing the operations to perform.
     * @throws StateAccessException
     */
    public List<Op> prepareDelete(UUID id) throws StateAccessException,
            SerializationException {

        List<Op> ops = new ArrayList<Op>();

        // Delete all the rules that reference this port group
        String rulesPath = paths.getPortGroupRulesPath(id);
        Set<String> ruleIds = zk.getChildren(rulesPath);
        for (String ruleId : ruleIds) {
            ops.addAll(ruleDao.prepareDelete(UUID.fromString(ruleId)));
        }

        // Update all the ports that reference this port group.
        String portsPath = paths.getPortGroupPortsPath(id);
        Set<String> portIds = zk.getChildren(portsPath);
        for (String portId : portIds) {
            UUID portUuid = UUID.fromString(portId);
            PortConfig port = portDao.get(portUuid);
            if (port.portGroupIDs != null) { // Should never be null here.
                port.portGroupIDs.remove(id);
            }
            ops.add(Op.setData(paths.getPortPath(portUuid),
                    serializer.serialize(port), -1));
            ops.add(Op.delete(
                    paths.getPortGroupPortPath(id, portUuid), -1));
        }

        // Delete the port group nodes
        ops.add(Op.delete(rulesPath, -1));
        ops.add(Op.delete(portsPath, -1));
        ops.add(Op.delete(paths.getPortGroupPath(id), -1));

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

    /***
     * Deletes a port group and its related data from the directories
     * atomically.
     *
     * @param id
     *            ID of the port group to delete.
     * @throws StateAccessException
     */
    public void delete(UUID id) throws StateAccessException,
            SerializationException {
        zk.multi(prepareDelete(id));
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
