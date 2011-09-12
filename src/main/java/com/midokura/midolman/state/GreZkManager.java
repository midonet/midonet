/*
 * @(#)GreZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.ZooKeeper;

/**
 * ZooKeeper manager class for GRE keys.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public class GreZkManager extends ZkManager {

    public GreZkManager(ZooKeeper zk, String basePath) {
        super(zk, basePath);
    }

    public static class GreKey implements Serializable {

        private static final long serialVersionUID = 1L;

        public GreKey() {
        }

        public GreKey(UUID bridgeId) {
            super();
            this.bridgeId = bridgeId;
        }

        public UUID bridgeId;
    }

    private int extractGreKeyFromPath(String path) {
        int idx = path.lastIndexOf('/');
        return Integer.parseInt(path.substring(idx + 1));
    }

    public ZkNodeEntry<Integer, GreKey> createGreKey() throws KeeperException,
            InterruptedException {
        String path = zk.create(pathManager.getGrePath() + "/", null, null,
                CreateMode.PERSISTENT_SEQUENTIAL);
        int key = extractGreKeyFromPath(path);
        return new ZkNodeEntry<Integer, GreKey>(key, new GreKey());
    }

    public List<Op> prepareGreUpdate(ZkNodeEntry<Integer, GreKey> gre)
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.setData(pathManager.getGreKeyPath(gre.key),
                    serialize(gre.value), -1));

        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize GRE",
                    e, GreKey.class);
        }
        return ops;
    }

}
