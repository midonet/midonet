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

import java.util.Collection;
import java.util.List;
import java.util.Set;

import com.google.common.base.Preconditions;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.ZooDefs;

/*
 * Collection of functions useful in dealing with Zk paths, etc. If we make
 * any new functions as a part of some ZK recipe implementation, they should
 * be considered for placement in this file.
 */
public class ZkUtil {

    private ZkUtil() {}

    /**
     * Returns the integer representing the sequence number at the end of a
     * zk path.
     *
     * @param nodeName name of the node to extract the sequence number from
     * @return the integer representation of the sequence number.
     * @throws IllegalArgumentException
     */
    public static int getSequenceNumberFromPath(String nodeName) {
        if (nodeName.length() < ZkManager.ZK_SEQ_NUM_LEN) {
            throw new IllegalArgumentException(
                    "Invalid input, cannot parse " + nodeName);
        }
        String seqNum = nodeName.substring(
                nodeName.length() - ZkManager.ZK_SEQ_NUM_LEN);
        try {
            return Integer.parseInt(seqNum);
        } catch(NumberFormatException nfe) {
            throw new IllegalArgumentException(
                    "Could not parse a sequence number from node : " +
                            nodeName);
        }
    }

    /**
     * Returns the path from sequenceNumberPaths which has the greatest
     * sequence number which is less than seqNum, or null if
     * sequenceNumberPaths contains no such path.
     *
     * @param sequenceNumberPaths set of paths to look over.
     * @param seqNum the number to compare against.
     * @return the path of the next lower sequence number.
     */
    public static String getNextLowerSequenceNumberPath(
            Set<String> sequenceNumberPaths, int seqNum) {
        int nextLowest = Integer.MIN_VALUE;
        String nextLowestPath = null;
        for (String seqNumPath : sequenceNumberPaths) {
            Integer pathSeqNum = getSequenceNumberFromPath(seqNumPath);
            if (pathSeqNum < seqNum && pathSeqNum > nextLowest) {
                nextLowest = pathSeqNum;
                nextLowestPath = seqNumPath;
            }
        }

        if (nextLowest == Integer.MIN_VALUE) {
            return null;
        } else {
            return nextLowestPath;
        }
    }

    /**
     * Checks whether OpResult is an error result
     *
     * @param result OpResult object to check
     * @return True if it's error
     */
    public static boolean isError(OpResult result) {
        Preconditions.checkNotNull(result);
        return result instanceof OpResult.ErrorResult &&
               ((OpResult.ErrorResult) result).getErr() != 0;
    }

    /**
     * Checks whether Op object is deletion
     *
     * @param op Op object to check
     * @return True if it's error
     */
    public static boolean isDelete(Op op) {
        return op != null && op.getType() == ZooDefs.OpCode.delete;
    }

    /**
     * Retrieve the Op object corresponding to the erroneous OpResult.
     * results and ops arguments must have the same size.
     *
     * @param results OpResult list from multi
     * @param ops Op list submitted to multi
     * @return Op that failed
     */
    public static Op getErrorOp(List<OpResult> results, List<Op> ops) {
        Preconditions.checkNotNull(results);
        Preconditions.checkNotNull(ops);
        Preconditions.checkArgument(results.size() == ops.size());
        for (int i = 0; i < results.size(); i++) {
            OpResult result = results.get(i);
            if (isError(result)) {
                return ops.get(i);
            }
        }

        return null;
    }

    /**
     * Retrieve the Op object corresponding to the erroneous OpResult.
     * Error results and ops arguments must have the same size.
     *
     * @param ex KeeperException to get the OpResult list from
     * @param ops Op list submitted to mutli
     * @return Op that failed
     */
    public static Op getErrorOp(KeeperException ex, List<Op> ops) {
        Preconditions.checkNotNull(ex);
        Preconditions.checkNotNull(ops);
        return getErrorOp(ex.getResults(), ops);
    }
}
