/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import java.util.Collection;
import java.util.Set;

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
}
