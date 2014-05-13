/*
 * Copyright 2014 Midokura PTE LTD.
 */

package org.midonet.midolman.state;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This is an in-memory, naive implementation of the Directory interface.
 * It is only meant to be used in tests. However, it is packaged here
 * so that it can be used by external projects (e.g. functional tests).
 */
public class CheckpointedMockDirectory extends MockDirectory
        implements CheckpointedDirectory {

    private final static Logger log = LoggerFactory.getLogger(
        MockDirectory.class);

    private class Checkpoint {
        private Node root;
        private String path;

        public Checkpoint(Node node, String path) {
            this.root = node.clone();
            this.path = path;
        }
    }

    private Map<Integer, Checkpoint> checkpoints = new HashMap<>();
    private int currentCheckpoint = 0;

    @Override
    synchronized public int createCheckPoint(String path) {
        Checkpoint cp;
        try {
            cp = new Checkpoint(getNode(path).clone(), path);
        } catch (NoNodeException nne) {
            cp = new Checkpoint(null, path);
        }
        checkpoints.put(++currentCheckpoint, cp);
        return currentCheckpoint;
    }

    @Override
    public int createCheckPoint() {
        return createCheckPoint("");
    }

    @Override
    public boolean checkpointsAreEquivalent(int cpIndex1, int cpIndex2) {
        Checkpoint cp1 = checkpoints.get(cpIndex1);
        Checkpoint cp2 = checkpoints.get(cpIndex2);
        if (cp1 == null)
            return cp2 == null;
        return cp2 != null && cp1.root.isSameNode(cp2.root);
    }

    @Override
    public Map<String, String> getAddedPaths(int cpIndex1, int cpIndex2) {
        Map<String, String> addedNodes = new HashMap<String, String>();
        Checkpoint cp1 = checkpoints.get(cpIndex1);
        Checkpoint cp2 = checkpoints.get(cpIndex2);
        if (cp2 == null || cp2.root == null)
            return addedNodes;
        if (cp1 == null || cp1.root == null)
            return cp2.root.toStringMap();

        Map<String, String> cp1Map = cp1.root.toStringMap();
        Map<String, String> cp2Map = cp2.root.toStringMap();

        for (String k : cp2Map.keySet()) {
            if (!cp1Map.containsKey(k)) {
                addedNodes.put(k, cp2Map.get(k));
            }
        }

        return addedNodes;
    }

    @Override
    public Map<String, String> getRemovedPaths(int cpIndex1, int cpIndex2) {
        Map<String, String> removedNodes = new HashMap<String, String>();
        Checkpoint cp1 = checkpoints.get(cpIndex1);
        Checkpoint cp2 = checkpoints.get(cpIndex2);
        if (cp1 == null || cp1.root == null)
            return removedNodes;
        if (cp2 == null || cp2.root == null)
            return cp1.root.toStringMap();

        Map<String, String> cp1Map = cp1.root.toStringMap();
        Map<String, String> cp2Map = cp2.root.toStringMap();

        for (String k : cp1Map.keySet()) {
            if (!cp2Map.containsKey(k)) {
                removedNodes.put(k, cp1Map.get(k));
            }
        }

        return removedNodes;
    }

    @Override
    public Map<String, String> getModifiedPaths(int cpIndex1, int cpIndex2) {
        Map<String, String> modifiedNodes = new HashMap<>();
        Checkpoint cp1 = checkpoints.get(cpIndex1);
        Checkpoint cp2 = checkpoints.get(cpIndex2);
        if ((cp1 == null || cp1.root == null) &&
                (cp2 == null || cp2.root == null))
            return modifiedNodes;

        Map<String, String> cp1Map = cp1.root.toStringMap();
        Map<String, String> cp2Map = cp2.root.toStringMap();

        for (String k : cp1Map.keySet()) {
            if (cp2Map.containsKey(k)) {
                String cp1Val = cp1Map.get(k);
                String cp2Val = cp2Map.get(k);
                if (!Objects.equals(cp1Val, cp2Val))
                    modifiedNodes.put(k, cp2Map.get(k));
            }
        }

        return modifiedNodes;
    }
}
