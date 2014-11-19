/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
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

import org.apache.commons.lang.time.StopWatch;
import org.apache.zookeeper.KeeperException;
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
 * - Delete, Create, Update should happen in that order - There can be multiple
 * updates with the same path but not for creates and deletes
 */
public class ZkOpList {

    private static final Logger logger =
        LoggerFactory.getLogger(ZkOpList.class);
    public static final int DEL_RETRIES = 2;

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

    private static void removeStartsWith(Map<String, Op> ops, String path) {

        Iterator<Map.Entry<String, Op>> it = ops.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Op> entry = it.next();
            if (entry.getKey().startsWith(path)) {
                logger.warn("Removing path starting with Op: {}.",
                            getOpDesc(entry.getValue()));
                it.remove();
            }
        }
    }

    private static void removeStartsWith(List<Op> ops, String path) {

        Iterator<Op> it = ops.iterator();
        while (it.hasNext()) {
            Op entry = it.next();
            if (entry.getPath().startsWith(path)) {
                logger.warn("Removing path starting with Op: {}.",
                            getOpDesc(entry));
                it.remove();
            }
        }
    }

    private static Op getErrorDelOpOrThrow(StateAccessException ex,
                                           List<Op> ops)
        throws StateAccessException {

        Op op = ZkUtil.getErrorOp((KeeperException) ex.getCause(), ops);
        if (ZkUtil.isDelete(op)) {
            return op;
        } else {
            throw ex;
        }
    }

    private static int decrementOrThrow(int num, StateAccessException ex)
        throws StateAccessException {

        num--;
        if (num < 0) {
            throw ex;
        }
        return num;
    }

    private void addChildrenDelOps(String rootPath)
        throws StateAccessException {

        List<Op> ops = this.zkManager.getRecursiveDeleteOps(rootPath);
        for (Op op : ops) {
            String path = op.getPath();
            if (!this.deleteOps.containsKey(path)) {
                this.deleteOps.put(path, op);
            }
        }
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

    private void tryCommit(int delRetries) throws StateAccessException {
        logger.debug("Trying commit with delete retries: {}", delRetries);
        dump();
        List<Op> ops = combine();

        try {
            this.zkManager.multi(ops);
        } catch (NoStatePathException ex) {

            // For deletion, if a node was deleted, just skip it and retry.
            Op errorOp = getErrorDelOpOrThrow(ex, ops);
            removeStartsWith(this.deleteOps, errorOp.getPath());
            tryCommit(delRetries);

        } catch (NodeNotEmptyStateException ex) {

            // For deletion, if a child node was added, try to re-fetch all
            // children from the parent node and try again, but with a limit on
            // the number of retries.
            Op errorOp = getErrorDelOpOrThrow(ex, ops);
            delRetries = decrementOrThrow(delRetries, ex);
            addChildrenDelOps(errorOp.getPath());
            tryCommit(delRetries);
        }
    }

    /**
     * Add an Op object.
     *
     * For delete Op, all the previously added updated and delete Ops are
     * replaced by this one.  If there was a create Op prior to it, delete Op is
     * not added, and the create Op is removed.
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

            // Remove any updates previously added containing this sub-path
            removeStartsWith(this.updateOps, op.getPath());

            boolean hasCreatePath = this.createOps.containsKey(op.getPath());

            // Remove any creates previous added containing this sub-path
            removeStartsWith(this.createOps, op.getPath());

            // Add this Delete Op if this path wasn't something created in same
            // multi
            if (!hasCreatePath) {
                // Replace any delete previously added
                this.deleteOps.put(op.getPath(), op);
            }

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
     *
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
     */
    public void commit() throws StateAccessException {
        StopWatch stopWatch = new StopWatch();
        stopWatch.start();
        if (size() > 0) {
            tryCommit(DEL_RETRIES);
            clear();
        } else {
            logger.warn("No Op to commit");
        }
        stopWatch.stop();
        logger.debug("Commit operation took " + stopWatch.getTime() +
                     " milliseconds.");
    }
}
