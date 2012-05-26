/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.config.PortMgmtConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.PortSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.PortConfig;
import com.midokura.midolman.state.PortZkManager;
import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkNodeEntry;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Class to build Op for the port paths.
 */
public class PortOpBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(PortOpBuilder.class);
    private final PathBuilder pathBuilder;
    private final PortSerializer serializer;
    private final PortZkManager zkDao;

    /**
     * Constructor
     * 
     * @param zkDao
     *            ZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            PortSerializer object.
     */
    public PortOpBuilder(PortZkManager zkDao, PathBuilder pathBuilder,
            PortSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Get the port update Op object.
     * 
     * @param id
     *            ID of the port.
     * @param config
     *            PortMgmtConfig object to set.
     * @return Op for router update.
     */
    public Op getPortSetDataOp(UUID id, PortMgmtConfig config)
            throws ZkStateSerializationException {
        log.debug("PortOpBuilder.getPortSetDataOp entered: id=" + id
                + " config=" + config);

        String path = pathBuilder.getPortPath(id);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getSetDataOp(path, data);

        log.debug("PortOpBuilder.getPortSetDataOp exiting.");
        return op;
    }

    /**
     * Get the port update Op object.
     * 
     * @param id
     *            ID of the port.
     * @param config
     *            PortConfig to set
     * @return Op for router update.
     * @throws StateAccessException
     */
    public List<Op> getPortSetDataOps(UUID id, PortConfig config)
            throws StateAccessException {
        log.debug("PortOpBuilder.getPortSetDataOps entered: id=" + id
                + ", config=" + config);

        List<Op> ops = new ArrayList<Op>();

        // If config is passed in, update this also
        ZkNodeEntry<UUID, PortConfig> entry = new ZkNodeEntry<UUID, PortConfig>(
                id, config);
        ops.addAll(zkDao.preparePortUpdate(entry));

        log.debug("PortOpBuilder.getPortSetDataOps exiting.");
        return ops;
    }

    /**
     * Get the port create Op object.
     * 
     * @param id
     *            ID of the port.
     * @param config
     *            PortMgmtConfig object to create.
     * @return Op for port create.
     */
    public Op getPortCreateOp(UUID id, PortMgmtConfig config)
            throws ZkStateSerializationException {
        log.debug("PortOpBuilder.getPortCreateOp entered: id=" + id
                + " config=" + config);

        String path = pathBuilder.getPortPath(id);
        byte[] data = null;
        if (config != null) {
            data = serializer.serialize(config);
        }
        Op op = zkDao.getPersistentCreateOp(path, data);

        log.debug("PortOpBuilder.getPortCreateOp exiting.");
        return op;
    }

    /**
     * Gets a list of Op objects to create a port in Midolman side.
     * 
     * @param id
     *            ID of the port
     * @param config
     *            PortConfig object to create.
     * @return List of Op objects.
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> getPortCreateOps(UUID id, PortConfig config)
            throws StateAccessException {
        log.debug("PortOpBuilder.getPortCreateOps entered: id=" + id);

        List<Op> ops = zkDao.preparePortCreate(id, config);

        log.debug("PortOpBuilder.getPortCreateOps exiting: ops count="
                + ops.size());
        return ops;
    }

    /**
     * Get the port delete Op object.
     * 
     * @param id
     *            ID of the port.
     * @return Op for port delete.
     */
    public Op getPortDeleteOp(UUID id) {
        log.debug("PortOpBuilder.getPortDeleteOp entered: id={}", id);

        String path = pathBuilder.getPortPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("PortOpBuilder.getPortDeleteOp exiting.");
        return op;
    }

    /**
     * Gets a list of Op objects to delete a Port in Midolman side.
     * 
     * @param id
     *            ID of the port
     * @return List of Op objects.
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> getPortDeleteOps(UUID id) throws StateAccessException {
        log.debug("PortOpBuilder.getPortDeleteOps entered: id={}", id);

        List<Op> ops = zkDao.preparePortDelete(id);

        log.debug("PortOpBuilder.getPortDeleteOps exiting: ops count={}",
                ops.size());
        return ops;
    }
}
