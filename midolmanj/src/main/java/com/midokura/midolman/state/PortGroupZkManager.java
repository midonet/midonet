/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs.Ids;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

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

    private List<Op> prepareDelete(UUID id, boolean updatePorts)
            throws StateAccessException {

        List<Op> ops = new ArrayList<Op>();

        // Delete all the rules that reference this port group
        String rulesPath = pathManager.getPortGroupRulesPath(id);
        Set<String> ruleIds = getChildren(rulesPath);
        for (String ruleId : ruleIds) {
            ops.addAll(ruleDao.prepareRuleDelete(UUID.fromString(ruleId)));
        }

        if (updatePorts) {
            // Update all the ports that reference this port group.
            // If this flag is set, the caller is responsible for updating
            // the port config data.
            String portsPath = pathManager.getPortGroupPortsPath(id);
            Set<String> portIds = getChildren(portsPath);
            for (String portId : portIds) {
                UUID portUuid = UUID.fromString(portId);
                PortConfig port = portDao.get(portUuid);
                if (port.portGroupIDs != null) { // Should never be null here.
                    port.portGroupIDs.remove(id);
                }
                ops.addAll(portDao.prepareUpdate(portUuid, port));
            }
        }

        // Delete the port references
        String portsPath = pathManager.getPortGroupPortsPath(id);
        Set<String> portIds = getChildren(portsPath);
        for (String portId : portIds) {
            ops.add(Op.delete(
                    pathManager.getPortGroupPortPath(id,
                            UUID.fromString(portId)), -1));
        }

        // Delete the port group nodes
        ops.add(Op.delete(rulesPath, -1));
        ops.add(Op.delete(portsPath, -1));
        ops.add(Op.delete(pathManager.getPortGroupPath(id), -1));

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
        return prepareDelete(id, true);
    }

    /**
     * Constructs a list of operations to perform in a port groups deletion.
     *
     * @param ids
     *            Set of IDs
     * @return List of Op objects.
     * @throws StateAccessException
     */
    public List<Op> prepareDelete(Set<UUID> ids) throws StateAccessException {
        log.debug("PortGroupZkManager.prepareDelete: entered");

        List<Op> ops = new ArrayList<Op>();

        Set<UUID> portIdSet = new HashSet<UUID>();

        // When removing multiple port groups, the port config cannot be
        // updated for each port group. Each port should update only once.
        for (UUID id : ids) {

            // Get all the ports
            String portsPath = pathManager.getPortGroupPortsPath(id);
            Set<String> portIds = getChildren(portsPath);
            for (String portId : portIds) {
                portIdSet.add(UUID.fromString(portId));
            }

            ops.addAll(prepareDelete(id, false));
        }

        // Update all the ports, removing the port groups
        for (UUID portId : portIdSet) {
            PortConfig port = portDao.get(portId);
            if (port.portGroupIDs != null) {
                for (UUID id : ids) {
                    port.portGroupIDs.remove(id);
                }
            }

            // update port
            ops.addAll(portDao.prepareUpdate(portId, port));
        }

        log.debug("PortGroupZkManager.prepareDelete: exiting");
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
}
