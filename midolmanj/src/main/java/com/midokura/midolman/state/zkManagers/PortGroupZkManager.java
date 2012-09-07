/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state.zkManagers;

import com.midokura.midolman.state.*;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * Class to manage the router ZooKeeper data.
 */
public class PortGroupZkManager extends ZkManager {

    private final static Logger log = LoggerFactory
            .getLogger(PortGroupZkManager.class);

    public static class PortGroupConfig {

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
     *            Directory object.
     * @param basePath
     *            The root path.
     */
    public PortGroupZkManager(Directory zk, String basePath) {
        super(zk, basePath);
        portDao = new PortZkManager(zk, basePath);
        ruleDao = new RuleZkManager(zk, basePath);
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
            throws ZkStateSerializationException {
        log.debug("PortGroupZkManager.prepareCreate: entered");

        List<Op> ops = new ArrayList<Op>();
        ops.add(Op.create(pathManager.getPortGroupPath(id),
                serializer.serialize(config), Ids.OPEN_ACL_UNSAFE,
                CreateMode.PERSISTENT));

        // Keep the references to ports and rules that reference it.
        ops.add(Op.create(pathManager.getPortGroupPortsPath(id), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        ops.add(Op.create(pathManager.getPortGroupRulesPath(id), null,
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
    public List<Op> prepareDelete(UUID id) throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();

        // Delete all the rules that reference this port group
        String rulesPath = pathManager.getPortGroupRulesPath(id);
        Set<String> ruleIds = getChildren(rulesPath);
        for (String ruleId : ruleIds) {
            ops.addAll(ruleDao.prepareRuleDelete(UUID.fromString(ruleId)));
        }

        // Update all the ports that reference this port group.
        String portsPath = pathManager.getPortGroupPortsPath(id);
        Set<String> portIds = getChildren(portsPath);
        for (String portId : portIds) {
            UUID portUuid = UUID.fromString(portId);
            PortConfig port = portDao.get(portUuid);
            if (port.portGroupIDs != null) { // Should never be null here.
                port.portGroupIDs.remove(id);
            }
            ops.add(Op.setData(pathManager.getPortPath(portUuid),
                    serializer.serialize(port), -1));
            ops.add(Op.delete(
                    pathManager.getPortGroupPortPath(id, portUuid), -1));
        }

        // Delete the port group nodes
        ops.add(Op.delete(rulesPath, -1));
        ops.add(Op.delete(portsPath, -1));
        ops.add(Op.delete(pathManager.getPortGroupPath(id), -1));

        return ops;

    }

    /**
     * Performs an atomic update on the ZooKeeper to add a new port group.
     *
     * @return The UUID of the newly created object.
     * @throws StateAccessException
     */
    public UUID create(PortGroupConfig config) throws StateAccessException {
        UUID id = UUID.randomUUID();
        multi(prepareCreate(id, config));
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
    public void delete(UUID id) throws StateAccessException {
        multi(prepareDelete(id));
    }

    /**
     * Checks whether a port group with the given ID exists.
     *
     * @param id
     *            Port group ID to check
     * @return True if exists
     * @throws StateAccessException
     */
    public boolean exists(UUID id) throws StateAccessException {
        return exists(pathManager.getPortGroupPath(id));
    }

    /**
     * Gets a PortGroupConfig object with the given ID.
     *
     * @param id
     *            The ID of the port group.
     * @return PortGroupConfig object
     * @throws StateAccessException
     */
    public PortGroupConfig get(UUID id) throws StateAccessException {
        byte[] data = get(pathManager.getPortGroupPath(id));
        return serializer.deserialize(data, PortGroupConfig.class);
    }

    public boolean portIsMember(UUID id, UUID portId)
        throws StateAccessException{
        String path = pathManager.getPortGroupPortPath(id, portId);
        return exists(path);
    }

    public void addPortToPortGroup(UUID id, UUID portId)
        throws StateAccessException {

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
        ops.add(Op.setData(pathManager.getPortPath(portId),
                serializer.serialize(port), -1));
        ops.add(Op.create(pathManager.getPortGroupPortPath(id, portId), null,
                Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT));
        multi(ops);
    }

    public void removePortFromPortGroup(UUID id, UUID portId)
        throws StateAccessException {

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
        ops.add(Op.setData(pathManager.getPortPath(portId),
                serializer.serialize(port), -1));
        ops.add(Op.delete(pathManager.getPortGroupPortPath(id, portId), -1));
        multi(ops);

    }
}
