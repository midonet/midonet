/*
 * @(#)VifOpService        1.6 12/1/6
 *
 * Copyright 2012 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.dao.zookeeper.VifZkDao;
import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * VIF Op builder.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class VifOpService {

    private final static Logger log = LoggerFactory
            .getLogger(VifOpService.class);
    private final VifZkDao zkDao;
    private final VifOpBuilder opBuilder;
    private final PortOpService portOpService;

    /**
     * Constructor
     *
     * @param opBuilder
     *            VifOpBuilder object
     * @param portOpService
     *            PortOpService object
     * @param zkDao
     *            VifZkDao object
     */
    public VifOpService(VifOpBuilder opBuilder,
            PortOpService portOpService, VifZkDao zkDao) {
        this.opBuilder = opBuilder;
        this.portOpService = portOpService;
        this.zkDao = zkDao;
    }

    /**
     * Build list of Op objects to create and plug a vif
     *
     * @param id
     *            ID of VIF
     * @param config
     *            VifConfig object
     * @return Op list
     * @throws StateAccessException
     *             Data error.
     */
    public List<Op> buildCreate(UUID id, VifConfig config)
            throws StateAccessException {
        log.debug("VifOpService.buildCreate entered: id={}", id);

        List<Op> ops = new ArrayList<Op>();
        ops.add(opBuilder.getVifCreateOp(id, config));

        // Plug!
        ops.addAll(portOpService.buildPlug(config.portId, id));

        log.debug("VifOpService.buildCreate exiting: ops count={}", ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to delete and unplugs a vif
     *
     * @param id
     *            　ID of the VIF
     * @return Op list
     * @throws StateAccessException
     *             Data error.
     */
    public List<Op> buildDelete(UUID id) throws StateAccessException {
        log.debug("VifOpService.buildDelete entered: id={}", id);

        VifConfig config = zkDao.getData(id);

        // Unplug!
        List<Op> ops = portOpService.buildPlug(config.portId, null);
        ops.add(opBuilder.getVifDeleteOp(id));

        log.debug("VifOpService.buildDelete exiting: ops count={}", ops.size());
        return ops;
    }
}
