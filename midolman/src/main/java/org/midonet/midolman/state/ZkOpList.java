/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

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

    private final Map<Integer, List<Op>> opMap;
    private final ZkManager zkManager;

    public ZkOpList(ZkManager zkManager) {
        Preconditions.checkNotNull(zkManager);
        this.zkManager = zkManager;

        this.opMap = new HashMap<>(3);
        this.opMap.put(ZooDefs.OpCode.create, new ArrayList<Op>());
        this.opMap.put(ZooDefs.OpCode.delete, new ArrayList<Op>());
        this.opMap.put(ZooDefs.OpCode.setData, new ArrayList<Op>());
    }

    private List<Op> getOps(int type) {
        return this.opMap.get(type);
    }

    private void addOp(int type, Op op) {
        this.opMap.get(type).add(op);
    }

    private List<Op> createOps() {
        return getOps(ZooDefs.OpCode.create);
    }

    private List<Op> deleteOps() {
        return getOps(ZooDefs.OpCode.delete);
    }

    private List<Op> setDataOps() {
        return getOps(ZooDefs.OpCode.setData);
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

    private static boolean contains(List<Op> ops, String path) {

        for (Op op : ops) {
            if (op.getPath().equals(path)) {
                return true;
            }
        }
        return false;
    }

    private void dump() {
        if (!logger.isDebugEnabled()) {
            return;
        }

        logger.debug("******** BEGIN PRINTING ZK OPs *********");

        for (Map.Entry<Integer, List<Op>> entry : this.opMap.entrySet()) {
            for (Op op : entry.getValue()) {
                logger.debug(getOpDesc(op));
            }
        }

        logger.debug("******** END PRINTING ZK OPs *********");
    }

    private int size() {
        int size = 0;
        for (Map.Entry<Integer, List<Op>> entry : this.opMap.entrySet()) {
            size += entry.getValue().size();
        }
        return size;
    }

    private List<Op> combine() {
        // Supports:
        //    - delete & re-add
        //    - create & update
        List<Op> ops = new ArrayList<>();
        ops.addAll(deleteOps());
        ops.addAll(createOps());
        ops.addAll(setDataOps());
        return ops;
    }

    private void clear() {
        for (Map.Entry<Integer, List<Op>> entry : this.opMap.entrySet()) {
            entry.getValue().clear();
        }
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
            remove(setDataOps(), op.getPath());

            // Remove any deletes previously added
            remove(deleteOps(), op.getPath());

            // Remove any create added but if there was a create, there is no
            // need to add the delete Op
            if (remove(createOps(), op.getPath()) > 0) {
                return;
            }

        } else if (type == ZooDefs.OpCode.create) {

            // Remove the previously added create
            remove(createOps(), op.getPath());
        }

        addOp(type, op);
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
     * Add an Op object.
     *
     * For delete and create Ops, add only if there has been no other Op with
     * the same path has been added.
     *
     * @param op Op object to add
     */
    public void addIfFirst(Op op) {
        Preconditions.checkNotNull(op);
        Preconditions.checkArgument(validOpType(op.getType()));

        int type = op.getType();
        if (type == ZooDefs.OpCode.create) {
            if (contains(createOps(), op.getPath())) {
                logger.warn("Ignoring Op {} because it already exists",
                            getOpDesc(op));
                return;
            }
        } else if (type == ZooDefs.OpCode.delete) {
            if (contains(deleteOps(), op.getPath())) {
                return;
            }
        }

        addOp(type, op);
    }

    /**
     * Add a list of Ops.  For create and delete, only add the paths do not
     * already exist.
     * @param ops Op objects to add
     */
    public void addAllIfFirst(List<Op> ops) {
        Preconditions.checkNotNull(ops);
        for (Op op : ops) {
            addIfFirst(op);
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
