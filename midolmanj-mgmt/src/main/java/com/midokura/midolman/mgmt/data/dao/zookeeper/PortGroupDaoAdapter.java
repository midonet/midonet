/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.PortGroupDao;
import com.midokura.midolman.mgmt.data.dto.PortGroup;
import com.midokura.midolman.mgmt.data.dto.config.PortGroupMgmtConfig;
import com.midokura.midolman.mgmt.data.dto.config.PortGroupNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.mgmt.jaxrs.JsonJaxbSerializer;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;
import com.midokura.midolman.state.ZkManager;

/**
 * PortGroup ZK DAO adapter.
 */
public class PortGroupDaoAdapter implements PortGroupDao {

    private final static Logger log = LoggerFactory
            .getLogger(PortGroupDaoAdapter.class);
    private final ZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final ZkConfigSerializer serializer;

    /**
     * Constructor
     *
     * @param zkDao
     *            ZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     */
    public PortGroupDaoAdapter(ZkManager zkDao, PathBuilder pathBuilder) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = new ZkConfigSerializer(new JsonJaxbSerializer());
    }

    @Override
    public UUID create(PortGroup group) throws StateAccessException {
        log.debug("PortGroupDaoAdapter.create entered: group={}", group);

        if (null == group.getId()) {
            group.setId(UUID.randomUUID());
        }

        List<Op> ops = new ArrayList<Op>();
        String path = pathBuilder.getPortGroupPath(group.getId());
        byte[] data = serializer.serialize(group.toMgmtConfig());
        ops.add(zkDao.getPersistentCreateOp(path, data));

        path = pathBuilder.getTenantPortGroupNamePath(group.getTenantId(),
                group.getName());
        data = serializer.serialize(group.toNameMgmtConfig());
        ops.add(zkDao.getPersistentCreateOp(path, data));

        zkDao.multi(ops);

        log.debug("PortGroupDaoAdapter.create exiting: group={}", group);
        return group.getId();
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("PortGroupDaoAdapter.delete entered: id={}", id);

        List<Op> ops = new ArrayList<Op>();
        PortGroup group = get(id);

        String path = pathBuilder.getTenantPortGroupNamePath(
                group.getTenantId(), group.getName());
        ops.add(zkDao.getDeleteOp(path));
        path = pathBuilder.getPortGroupPath(id);
        ops.add(zkDao.getDeleteOp(path));
        zkDao.multi(ops);

        log.debug("PortGroupDaoAdapter.delete exiting.");
    }

    @Override
    public PortGroup get(UUID id) throws StateAccessException {
        log.debug("PortGroupDaoAdapter.get entered: id={}", id);

        PortGroup group = null;
        if (exists(id)) {
            PortGroupMgmtConfig mgmtConfig = getMgmtData(id);
            group = new PortGroup(id, mgmtConfig.tenantId, mgmtConfig.name);
        }

        log.debug("PortGroupDaoAdapter.get existing: group={}", group);
        return group;
    }

    @Override
    public PortGroup get(String tenantId, String name)
            throws StateAccessException {
        log.debug("PortGroupDaoAdapter.get entered: tenantId=" + tenantId
                + ", name=" + name);

        PortGroupNameMgmtConfig nameConfig = getNameData(tenantId, name);
        PortGroup group = get(nameConfig.id);

        log.debug("PortGroupDaoAdapter.get existing: group={}", group);
        return group;
    }

    @Override
    public List<PortGroup> list(String tenantId) throws StateAccessException {
        log.debug("PortGroupDaoAdapter.list entered: tenantId={}", tenantId);

        String path = pathBuilder.getTenantPortGroupNamesPath(tenantId);
        Set<String> names = zkDao.getChildren(path, null);
        List<PortGroup> groups = new ArrayList<PortGroup>();
        for (String name : names) {
            groups.add(get(tenantId, name));
        }
        return groups;
    }

    public List<Op> buildTenantPortGroupsDelete(String tenantID)
            throws StateAccessException {
        String path = pathBuilder.getTenantPortGroupNamesPath(tenantID);
        Set<String> names = zkDao.getChildren(path, null);
        List<Op> ops = new ArrayList<Op>();
        for (String name : names) {
            PortGroupNameMgmtConfig nameConfig = getNameData(tenantID, name);
            path = pathBuilder.getTenantPortGroupNamePath(tenantID, name);
            ops.add(zkDao.getDeleteOp(path));
            path = pathBuilder.getPortGroupPath(nameConfig.id);
            ops.add(zkDao.getDeleteOp(path));
        }
        return ops;
    }

    /**
     * Checks whether a PortGroup exists with the given ID.
     *
     * @param id
     *            PortGroup ID
     * @return True if PortGroup exists.
     * @throws StateAccessException
     *             Data access error.
     */
    private boolean exists(UUID id) throws StateAccessException {
        String path = pathBuilder.getPortGroupPath(id);
        return zkDao.exists(path);
    }

    /**
     * Get the data for the given PortGroup.
     *
     * @param id
     *            ID of the PortGroup.
     * @return PortGroupMgmtConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    private PortGroupMgmtConfig getMgmtData(UUID id)
            throws StateAccessException {
        if (id == null) {
            throw new IllegalArgumentException("ID cannot be null");
        }
        String path = pathBuilder.getPortGroupPath(id);
        byte[] data = zkDao.get(path);
        return serializer.deserialize(data, PortGroupMgmtConfig.class);
    }

    /**
     * Get the data for the given PortGroup by name.
     *
     * @param tenantId
     *            ID of the Tenant that owns the PortGroup.
     * @param name
     *            Name of the PortGroup.
     * @return PortGroupNameMgmtConfig stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public PortGroupNameMgmtConfig getNameData(String tenantId, String name)
            throws StateAccessException {
        if (tenantId == null || name == null) {
            throw new IllegalArgumentException(
                    "tenantId, PortGroup name cannot be null");
        }

        String path = pathBuilder.getTenantPortGroupNamePath(tenantId, name);
        byte[] data = zkDao.get(path);
        PortGroupNameMgmtConfig config = serializer.deserialize(data,
                PortGroupNameMgmtConfig.class);

        log.debug("PortGroupZkDao.getNameData exiting: path=" + path);
        return config;
    }
}
