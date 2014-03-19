/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;

/*
 * This class implements the leader election algorithm in Zookeeper.
 * What it achieves: picks a single node out of all registered nodes to
 * perform some task. If the designated node dies, a new one is automatically
 * chosen
 *
 * How to use:
 *  - pick a path where all your nodes will sync up. Then,
 *  - call "registerLeaderNode" with that path and a zk manager. the callback
 *     is executed on becoming the designated node.
 *  - you do NOT have to worry about re-registering when nodes go down. the
 *    process call handles all that for you.
 */
public class ZkLeaderElectionWatcher implements Watcher {
    public Integer mySeqNum;
    public ExecuteOnBecomingLeader executeOnBecomingLeader;
    public String leaderPath;
    public ZkManager zk;

    public interface ExecuteOnBecomingLeader {
        public void call();
    }

    protected final static Logger log =
            LoggerFactory.getLogger(ZkLeaderElectionWatcher.class);

    public ZkLeaderElectionWatcher(Integer mySeqNum,
            ExecuteOnBecomingLeader executeOnBecomingLeader,
            String leaderPath, ZkManager zk) {
        this.mySeqNum = mySeqNum;
        this.executeOnBecomingLeader = executeOnBecomingLeader;
        this.leaderPath = leaderPath;
        this.zk = zk;
    }

    public void process(WatchedEvent event) {
        if (event.getType().equals(Event.EventType.NodeDeleted)) {
            try {
                String nextNodePath = watchNextNode(mySeqNum,
                        executeOnBecomingLeader,
                        leaderPath, zk);
                if (nextNodePath == null) {
                    executeOnBecomingLeader.call();
                }
            } catch (StateAccessException e) {
                log.error("error trying to install watcher on " +
                          leaderPath + ": " + e.getMessage());
            }
        }
    }

    public static String watchNextNode(Integer myNode,
            ExecuteOnBecomingLeader becomeLeaderCallback, String leaderPath,
            ZkManager zk)
            throws StateAccessException {
        String path;
        ZkLeaderElectionWatcher cb;
        int tryCount = 0;
        do {
            Set<String> set = zk.getChildren(leaderPath);
            String nextNode
                    = ZkUtil.getNextLowerSequenceNumberPath(set, myNode);
            if (nextNode == null) {
                return null;
            }
            path = leaderPath + "/" + nextNode;
            cb = new ZkLeaderElectionWatcher(myNode, becomeLeaderCallback,
                    leaderPath, zk);
            tryCount++;
            // This should almost always succeed the first time. If it goes more
            // than 10 times, something weird is going on.
        } while (!zk.exists(path, cb) && tryCount < 10);
        if (tryCount >= 10) {
            throw new StateAccessException("Unable to obtain health " +
                    "monitor leader node to watch");
        }

        return path;
    }

    public static Integer registerLeaderNode(ExecuteOnBecomingLeader cb,
                                             String leaderPath, ZkManager zk)
            throws StateAccessException {
        String seqNumPath = zk.addEphemeralSequential(leaderPath, null);
        Integer myNode
                = ZkUtil.getSequenceNumberFromPath(seqNumPath);
        String nextNodePath = watchNextNode(myNode, cb, leaderPath, zk);
        if (nextNodePath == null) {
            cb.call();
        }
        return myNode;
    }
}
