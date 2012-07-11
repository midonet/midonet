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

import com.midokura.midolman.mgmt.data.dto.PortGroup;
import com.midokura.midolman.mgmt.data.dto.config.PortGroupNameMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.mgmt.jaxrs.JsonJaxbSerializer;
import com.midokura.midolman.state.PortGroupZkManager;
import com.midokura.midolman.state.PortGroupZkManager.PortGroupConfig;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkConfigSerializer;

/**
 * PortGroup ZK DAO implementation
 */
public class PortGroupZkDaoImpl implements PortGroupZkDao {

    private final static Logger log = LoggerFactory
            .getLogger(PortGroupZkDaoImpl.class);
    private final PortGroupZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final ZkConfigSerializer serializer;

    /**
     * Constructor
     *
     * @param zkDao
     *            PortGroupZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     */
    public PortGroupZkDaoImpl(PortGroupZkManager zkDao, PathBuilder pathBuilder) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = new ZkConfigSerializer(new JsonJaxbSerializer());
    }

    @Override
    public UUID create(PortGroup group) throws StateAccessException {
        log.debug("PortGroupZkDaoImpl.create entered: group={}", group);

        if (null == group.getId()) {
            group.setId(UUID.randomUUID());
        }

        List<Op> ops = zkDao.prepareCreate(group.getId(), group.toConfig());
        byte[] data = serializer.serialize(group.toNameMgmtConfig());
        ops.add(zkDao.getPersistentCreateOp(pathBuilder
                .getTenantPortGroupNamePath(group.getTenantId(),
                        group.getName()), data));
        zkDao.multi(ops);

        log.debug("PortGroupZkDaoImpl.create exiting: group={}", group);
        return group.getId();
    }

    @Override
    public void delete(UUID id) throws StateAccessException {
        log.debug("PortGroupZkDaoImpl.delete entered: id={}", id);

        List<Op> ops = prepareDelete(id);
        zkDao.multi(ops);

        log.debug("PortGroupZkDaoImpl.delete exiting.");
    }

    @Override
    public PortGroup get(UUID id) throws StateAccessException {
        log.debug("PortGroupZkDaoImpl.get entered: id={}", id);

        PortGroup group = null;
        if (zkDao.exists(id)) {
            PortGroupConfig config = zkDao.get(id);
            group = new PortGroup(id, config);
        }

        log.debug("PortGroupZkDaoImpl.get existing: group={}", group);
        return group;
    }

    @Override
    public PortGroup get(String tenantId, String name)
            throws StateAccessException {
        log.debug("PortGroupZkDaoImpl.get entered: tenantId=" + tenantId
                + ", name=" + name);

        PortGroupNameMgmtConfig nameConfig = getNameData(tenantId, name);
        PortGroup group = get(nameConfig.id);

        log.debug("PortGroupZkDaoImpl.get existing: group={}", group);
        return group;
    }

    @Override
    public List<PortGroup> list(String tenantId) throws StateAccessException {
        log.debug("PortGroupZkDaoImpl.list entered: tenantId={}", tenantId);

        String path = pathBuilder.getTenantPortGroupNamesPath(tenantId);
        Set<String> names = zkDao.getChildren(path, null);
        List<PortGroup> groups = new ArrayList<PortGroup>();
        for (String name : names) {
            groups.add(get(tenantId, name));
        }
        return groups;
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
    private PortGroupNameMgmtConfig getNameData(String tenantId, String name)
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

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.zookeeper.PortGroup#prepareDelete
     * (java.util.UUID)
     */
    @Override
    public List<Op> prepareDelete(UUID id) throws StateAccessException {
        return prepareDelete(get(id));
    }

    /*
     * (non-Javadoc)
     *
     * @see
     * com.midokura.midolman.mgmt.data.dao.zookeeper.PortGroupZkDao#prepareDelete
     * (com.midokura.midolman.mgmt.data.dto.PortGroup)
     */
    @Override
    public List<Op> prepareDelete(PortGroup group) throws StateAccessException {

        List<Op> ops = zkDao.prepareDelete(group.getId());
        String path = pathBuilder.getTenantPortGroupNamePath(
                group.getTenantId(), group.getName());
        ops.add(zkDao.getDeleteOp(path));
        return ops;
    }
}
