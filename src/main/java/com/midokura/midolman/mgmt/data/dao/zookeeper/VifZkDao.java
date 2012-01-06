/*
 * @(#)VifZkDao        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao.zookeeper;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.VifSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkManager;

/**
 * Proxy class to access ZooKeeper for VIF data.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class VifZkDao {

    private final static Logger log = LoggerFactory.getLogger(VifZkDao.class);
    private final ZkManager zkDao;
    private final PathBuilder pathBuilder;
    private final VifSerializer serializer;

    /**
     * Constructor
     *
     * @param zkDao
     *            PortZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            VifSerializer object.
     */
    public VifZkDao(ZkManager zkDao, PathBuilder pathBuilder,
            VifSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Get the data for the given VIF ID.
     *
     * @param id
     *            ID of the VIF.
     * @return Byte array data stored in ZK.
     * @throws StateAccessException
     *             Data access error.
     */
    public VifConfig getData(UUID id) throws StateAccessException {
        log.debug("VifZkDao.getData entered: id={}", id);

        String path = pathBuilder.getVifPath(id);
        byte[] data = zkDao.get(path);
        VifConfig config = serializer.deserialize(data);

        log.debug("VifZkDao.getData exiting: path={}", path);
        return config;
    }

    /**
     * Get a set of Vif IDs.
     *
     * @return Set of Vif IDs.
     * @throws StateAccessException
     *             Data access error.
     */
    public Set<String> getIds() throws StateAccessException {
        log.debug("VifZkDao.getIds entered.");

        String path = pathBuilder.getVifsPath();
        Set<String> ids = zkDao.getChildren(path, null);

        log.debug("VifZkDao.getIds exiting: path=" + path + " ids count="
                + ids.size());
        return ids;
    }

    /**
     * Wrapper for multi ZK API. The paths in the Op list are expected already
     * to be created.
     *
     * @param ops
     * @throws StateAccessException
     */
    public void multi(List<Op> ops) throws StateAccessException {
        log.debug("VifZkDao.multi entered: ops count={}", ops.size());
        zkDao.multi(ops);
        log.debug("VifZkDao.multi exiting.");
    }
}
