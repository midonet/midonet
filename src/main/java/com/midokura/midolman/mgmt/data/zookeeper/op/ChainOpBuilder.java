/*
 * @(#)ChainOpBuilder        1.6 11/12/26
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.zookeeper.op;

import java.util.ArrayList;
import java.util.List;

import org.apache.zookeeper.Op;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.mgmt.data.zookeeper.op.ChainOpPathBuilder;
import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.state.StateAccessException;

/**
 * Chain Op builder.
 *
 * @version 1.6 26 Dec 2011
 * @author Ryu Ishimoto
 */
public class ChainOpBuilder {

    private final static Logger log = LoggerFactory
            .getLogger(ChainOpBuilder.class);
    private final ChainOpPathBuilder pathBuilder;

    /**
     * Constructor
     *
     * @param pathBuilder
     *            Chain DAO pathBuilder.
     */
    public ChainOpBuilder(ChainOpPathBuilder pathBuilder) {
        this.pathBuilder = pathBuilder;
    }

    /**
     * Build list of Op objects to create a chain
     *
     * @param chains
     *            Chains to create Op from
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildCreate(List<Chain> chains) throws StateAccessException {
        if (chains == null) {
            throw new IllegalArgumentException("chains cannot be null");
        }
        log.debug("ChainOpBuilder.buildCreate entered: chains={}",
                chains.size());

        List<Op> ops = new ArrayList<Op>();
        for (Chain chain : chains) {
            ops.addAll(buildCreate(chain));
        }

        log.debug("ChainOpBuilder.buildCreate exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to create a chain
     *
     * @param chain
     *            Chain to create Op from
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildCreate(Chain chain) throws StateAccessException {
        if (chain == null) {
            throw new IllegalArgumentException("chain cannot be null");
        }
        log.debug("ChainOpBuilder.buildCreate entered: chain={}", chain);
        List<Op> ops = new ArrayList<Op>();

        // Root
        ops.add(pathBuilder.getChainCreateOp(chain.getId(),
                chain.toMgmtConfig()));

        // Router/Chain ID
        ops.add(pathBuilder.getRouterTableChainCreateOp(chain.getRouterId(),
                chain.getTable(), chain.getId()));

        // Router/Chain name
        ops.add(pathBuilder.getRouterTableChainNameCreateOp(
                chain.getRouterId(), chain.getTable(), chain.getName(),
                chain.toNameMgmtConfig()));

        // Cascade
        ops.addAll(pathBuilder.getChainCreateOps(chain.getId(),
                chain.toConfig()));

        log.debug("ChainOpBuilder.buildCreate exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Get delete Op list.
     *
     * @param chains
     *            Objects to delete
     * @param cascade
     *            Delete midolman data if true.
     * @return List of Op
     * @throws StateAccessException
     *             Data access error.
     */
    public List<Op> buildDelete(List<Chain> chains, boolean cascade)
            throws StateAccessException {
        if (chains == null) {
            throw new IllegalArgumentException("chains cannot be null");
        }
        log.debug("ChainOpBuilder.buildDelete entered: chains count="
                + chains.size() + ", cascade=" + cascade);

        List<Op> ops = new ArrayList<Op>();
        for (Chain chain : chains) {
            ops.addAll(buildDelete(chain, cascade));
        }

        log.debug("ChainOpBuilder.buildDelete exiting: ops count={}",
                ops.size());
        return ops;
    }

    /**
     * Build list of Op objects to delete a chain
     *
     * @param chain
     *            Chain to build Op from
     * @param cascade
     *            Delete midolman data if set to true.
     * @return List of Op objects
     * @throws StateAccessException
     *             Data access error
     */
    public List<Op> buildDelete(Chain chain, boolean cascade)
            throws StateAccessException {
        if (chain == null) {
            throw new IllegalArgumentException("chain cannot be null");
        }
        log.debug("ChainOpBuilder.buildDelete entered: chain=" + chain
                + ", cascade=" + cascade);

        List<Op> ops = new ArrayList<Op>();

        // Cascade
        if (cascade) {
            ops.addAll(pathBuilder.getChainDeleteOps(chain.getId()));
        }

        // Router/Chain name
        ops.add(pathBuilder.getRouterTableChainNameDeleteOp(
                chain.getRouterId(), chain.getTable(), chain.getName()));

        // Router/Chain ID
        ops.add(pathBuilder.getRouterTableChainDeleteOp(chain.getRouterId(),
                chain.getTable(), chain.getId()));

        // Root
        ops.add(pathBuilder.getChainDeleteOp(chain.getId()));

        log.debug("ChainOpBuilder.buildDelete exiting: ops count={}",
                ops.size());
        return ops;
    }
}
