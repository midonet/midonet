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
import org.apache.zookeeper.OpResult;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.OpResult.CreateResult;
import org.apache.zookeeper.ZooDefs.Ids;

/**
 * ZooKeeper manager class for GRE keys.
 * @version        1.6 11 Sept 2011
 * @author         Ryu Ishimoto
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
    
    public class InvalidGreZkPathException extends Exception {
        private static final long serialVersionUID = 1L;
        private String path;
        
        public InvalidGreZkPathException(String msg, String path) {
            super(msg);
            this.path = path;
        }
        
        public InvalidGreZkPathException(String msg, Throwable e, 
                String path) {
            super(msg, e);
            this.path = path;
        }
        
        @Override
        public String getMessage() {
          return "Bad path: " + this.path;
        }
    }

    private int extractGreKeyFromPath(String path) 
            throws InvalidGreZkPathException {
        int idx = path.lastIndexOf('/');
        if (idx < 0) {
            throw new InvalidGreZkPathException(
                    "Invalid path: No '/'.", path);
        } else if (idx == path.length()-1) {
            throw new InvalidGreZkPathException("Path ends with '/'.", path);
        }
        
        try {
            return Integer.parseInt(path.substring(idx+1));
        } catch (NumberFormatException e) {
            throw new InvalidGreZkPathException(
                    "Non-integer sequence number found.", e, path);
        }
    }

    public List<Op> getCreateBridgeKeyOps(UUID bridgeId) 
            throws ZkStateSerializationException {
        return getCreateKeyOps(new GreKey(bridgeId));
    }
        
    public List<Op> getCreateKeyOps(GreKey key) 
            throws ZkStateSerializationException {
        List<Op> ops = new ArrayList<Op>();
        try {
            ops.add(Op.create(pathManager.getGrePath(), serialize(key), 
                    Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT_SEQUENTIAL));
        } catch (IOException e) {
            throw new ZkStateSerializationException("Could not serialize GRE",
                    e, GreKey.class);
        }
        return ops;
    }
 
    public int extractGreKeyFromOpResults(List<OpResult> results) 
            throws InvalidGreZkPathException {
        int greKeyId = -1;
        for (OpResult result : results) {
            if (result instanceof CreateResult) {
                // Should be the only create.
                String path = ((CreateResult)result).getPath();
                greKeyId = extractGreKeyFromPath(path);
            } 
        }
        return greKeyId;        
    }
    
    public int createBridgeKey(UUID bridgeId) 
            throws InterruptedException, KeeperException, 
                ZkStateSerializationException, InvalidGreZkPathException {
        List<OpResult> results = zk.multi(getCreateBridgeKeyOps(bridgeId));
        return extractGreKeyFromOpResults(results);

    }
}
