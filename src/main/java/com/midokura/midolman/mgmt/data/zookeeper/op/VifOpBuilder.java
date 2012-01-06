/*
 * @(#)VifOpBuilder        1.6 12/1/6
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

import com.midokura.midolman.mgmt.data.dto.config.VifConfig;
import com.midokura.midolman.state.StateAccessException;

/**
 * VIF Op builder.
 *
 * @version 1.6 6 Jan 2012
 * @author Ryu Ishimoto
 */
public class VifOpBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(VifOpBuilder.class);
    private final VifOpPathBuilder pathBuilder;
    private final PortOpBuilder portOpBuilder;

    /**
     * Constructor
     *
     * @param pathBuilder
     *            VifOpPathBuilder object
     * @param portOpBuilder
     *            PortOpBuilder object
     */
    public VifOpBuilder(VifOpPathBuilder pathBuilder,
            PortOpBuilder portOpBuilder) {
        this.pathBuilder = pathBuilder;
        this.portOpBuilder = portOpBuilder;
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
        log.debug("VifOpBuilder.buildCreate entered: id={}", id);

        List<Op> ops = new ArrayList<Op>();
        ops.add(pathBuilder.getVifCreateOp(id, config));

        // Plug!
        ops.addAll(portOpBuilder.buildPlug(config.portId, id));

        log.debug("VifOpBuilder.buildCreate exiting: ops count={}", ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to delete and unplugs a vif
     *
     * @param id
     *            　ID of the VIF
     * @param portId
     *            ID of the port
     * @return Op list
     * @throws StateAccessException
     *             Data error.
     */
    public List<Op> buildDelete(UUID id, UUID portId)
            throws StateAccessException {
        log.debug("VifOpBuilder.buildDelete entered: id={}", id);

        // Unplug!
        List<Op> ops = portOpBuilder.buildPlug(portId, null);
        ops.add(pathBuilder.getVifDeleteOp(id));

        log.debug("VifOpBuilder.buildDelete exiting: ops count={}", ops.size());
        return ops;
    }
}
