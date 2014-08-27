/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

import com.google.common.base.Preconditions;

import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooDefs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Class to manage list of Op Zookeeper objects.
 *
 * Use this class with caution since it is very opinionated.
 *
 * It assumes the following:
 *
 * - Delete, Create, Update should happen in that order
 * - There can be multiple updates with the same path but not for creates
 *   and deletes
 */
public class ZkOpList {

    private static final Logger logger =
        LoggerFactory.getLogger(ZkOpList.class);

    private final SortedMap<String, Op> deleteOps = new TreeMap<>(
        Collections.reverseOrder());
    private final SortedMap<String, Op> createOps = new TreeMap<>();
    private final List<Op> updateOps = new ArrayList<>();

    private final ZkManager zkManager;

    public ZkOpList(ZkManager zkManager) {
        Preconditions.checkNotNull(zkManager);
        this.zkManager = zkManager;
    }

    private static boolean validOpType(int type) {
        return (type == ZooDefs.OpCode.delete || type == ZooDefs.OpCode.create
                || type == ZooDefs.OpCode.setData);
    }

    private static String getOpDesc(Op op) {
        return ZooDefs.opNames[op.getType()] + " " + op.getPath();
    }

    private static int remove(List<Op> ops, String path) {

        int cnt = 0;
        for (Iterator<Op> it = ops.iterator(); it.hasNext(); ) {
            Op op = it.next();
            if (op.getPath().equals(path)) {
                logger.warn("Removing path Op: {}.", getOpDesc(op));
                it.remove();
                cnt++;
            }
        }
        return cnt;
    }

    private void dump() {
        if (!logger.isDebugEnabled()) {
            return;
        }

        logger.debug("******** BEGIN PRINTING ZK OPs *********");

        for (Map.Entry<String, Op> entry : this.deleteOps.entrySet()) {
            logger.debug(getOpDesc(entry.getValue()));
        }

        for (Map.Entry<String, Op> entry : this.createOps.entrySet()) {
            logger.debug(getOpDesc(entry.getValue()));
        }

        for (Op op : this.updateOps) {
            logger.debug(getOpDesc(op));
        }

        logger.debug("******** END PRINTING ZK OPs *********");
    }

    private int size() {
        return this.deleteOps.size() + this.createOps.size() +
               this.updateOps.size();
    }

    private List<Op> combine() {
        // Supports:
        //    - delete & re-add
        //    - create & update
        List<Op> ops = new ArrayList<>();

        ops.addAll(this.deleteOps.values());
        ops.addAll(this.createOps.values());
        ops.addAll(this.updateOps);

        return ops;
    }

    private void clear() {
        this.deleteOps.clear();
        this.createOps.clear();
        this.updateOps.clear();
    }

    /**
     * Add an Op object.
     *
     * For delete Op, all the previously added updated and delete Ops are
     * replaced by this one.  If there was a create Op prior to it, delete
     * Op is not added, and the create Op is removed.
     *
     * For create Op, if another create Op already exists, this replaces it.
     *
     * @param op Op object to add
     */
    public void add(Op op) {
        Preconditions.checkNotNull(op);
        Preconditions.checkArgument(validOpType(op.getType()));

        int type = op.getType();
        if (type == ZooDefs.OpCode.delete) {

            // Remove any updates previously added
            remove(this.updateOps, op.getPath());

            // Remove any create added but if there was a create, there is no
            // need to add the delete Op
            if (this.createOps.containsKey(op.getPath())) {
                this.createOps.remove(op.getPath());
                return;
            }

            // Replace any delete previously added
            this.deleteOps.put(op.getPath(), op);

        } else if (type == ZooDefs.OpCode.create) {

            // Replace the previously added create
            this.createOps.put(op.getPath(), op);

        } else if (type == ZooDefs.OpCode.setData) {

            // For updates, just add to the list
            this.updateOps.add(op);
        }
    }

    /**
     * Add a list of Ops
     * @param ops Op objects to add
     */
    public void addAll(List<Op> ops) {
        Preconditions.checkNotNull(ops);
        for (Op op : ops) {
            add(op);
        }
    }

    /**
     * Commit the Ops with duplicate paths removed.
     *
     * @throws StateAccessException
     */
    public void commit() throws StateAccessException {

        if (size() > 0) {
            dump();
            this.zkManager.multi(combine());
            clear();
        } else {
            logger.warn("No Op to commit");
        }
    }
}
