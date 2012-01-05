/*
 * @(#)VifOpPathBuilder        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.mgmt.data.zookeeper.io.VifSerializer;
import com.midokura.midolman.mgmt.data.zookeeper.path.PathBuilder;
import com.midokura.midolman.state.ZkManager;
import com.midokura.midolman.state.ZkStateSerializationException;

/**
 * Class to build Op for the VIF paths.
 *
 * @version 1.6 6 Jan 2011
 * @author Ryu Ishimoto
 */
public class VifOpPathBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(VifOpPathBuilder.class);
    private final ZkManager zkDao;
    private final VifSerializer serializer;
    private final PathBuilder pathBuilder;

    /**
     * Constructor
     *
     * @param zkDao
     *            ZkManager object to access ZK data.
     * @param pathBuilder
     *            PathBuilder object to get path data.
     * @param serializer
     *            VifSerializer object to serialize the data.
     */
    public VifOpPathBuilder(ZkManager zkDao, PathBuilder pathBuilder,
            VifSerializer serializer) {
        this.zkDao = zkDao;
        this.pathBuilder = pathBuilder;
        this.serializer = serializer;
    }

    /**
     * Get the Vif bridge create Op object.
     *
     * @param id
     *            ID of the Vif
     * @return Op for Vif bridge create.
     * @throws ZkStateSerializationException
     */
    public Op getVifCreateOp(UUID id, VifConfig config)
            throws ZkStateSerializationException {
        log.debug("VifOpPathBuilder.getVifCreateOp entered: id={}", id);

        String path = pathBuilder.getVifPath(id);
        byte[] data = serializer.serialize(config);
        Op op = zkDao.getPersistentCreateOp(path, data);

        log.debug("VifOpPathBuilder.getVifCreateOp exiting.");
        return op;
    }

    /**
     * Get the Vif delete Op object.
     *
     * @param id
     *            ID of the Vif
     * @return Op for Vif delete.
     */
    public Op getVifDeleteOp(UUID id) {
        log.debug("VifOpPathBuilder.getVifDeleteOp entered: id={}", id);

        String path = pathBuilder.getVifPath(id);
        Op op = zkDao.getDeleteOp(path);

        log.debug("VifOpPathBuilder.getVifDeleteOp exiting.");
        return op;
    }
}
