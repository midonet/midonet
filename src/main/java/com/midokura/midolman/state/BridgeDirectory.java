package com.midokura.midolman.state;

import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoChildrenForEphemeralsException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;

public class BridgeDirectory {

    private Directory dir;

    public BridgeDirectory(Directory dir) {
        this.dir = dir;
    }

    private static byte[] intToBytes(int value) {
        return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value };
    }

    private static int bytesToInt(byte[] b) {
        return (b[0] << 24) + ((b[1] & 0xFF) << 16) + ((b[2] & 0xFF) << 8)
                + (b[3] & 0xFF);
    }

    public void add(UUID bridgeId, int gre_key) throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException {
        dir.add("/" + bridgeId.toString(), intToBytes(gre_key),
                CreateMode.PERSISTENT);
    }

    public void setGreKey(UUID bridgeId, int gre_key) throws NoNodeException {
        dir.update("/" + bridgeId.toString(), intToBytes(gre_key));
    }

    public int getGreKey(UUID bridgeId) throws NoNodeException {
        byte[] data = dir.get("/" + bridgeId.toString(), null);
        return bytesToInt(data);
    }

    public void delete(UUID bridgeId) throws NoNodeException {
        dir.delete("/" + bridgeId.toString());
    }
}
