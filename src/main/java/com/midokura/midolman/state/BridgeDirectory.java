package com.midokura.midolman.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
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

    /* TODO(pino): get rid of these when we're happy with the serialization.
    private static byte[] intToBytes(int value) {
        return new byte[] { (byte) (value >>> 24), (byte) (value >>> 16),
                (byte) (value >>> 8), (byte) value };
    }

    private static int bytesToInt(byte[] b) {
        return (b[0] << 24) + ((b[1] & 0xFF) << 16) + ((b[2] & 0xFF) << 8)
                + (b[3] & 0xFF);
    }
    */

    public void add(UUID bridgeId, int gre_key) throws NoNodeException,
            NodeExistsException, NoChildrenForEphemeralsException, IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeInt(gre_key);
        out.close();
        dir.add("/" + bridgeId.toString(), bos.toByteArray(),
                CreateMode.PERSISTENT);
    }

    public void setGreKey(UUID bridgeId, int gre_key) throws NoNodeException,
            IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeInt(gre_key);
        out.close();
        dir.update("/" + bridgeId.toString(), bos.toByteArray());
    }

    public int getGreKey(UUID bridgeId) throws NoNodeException, IOException {
        byte[] data = dir.get("/" + bridgeId.toString(), null);
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream in = new ObjectInputStream(bis);
        int greKey = in.readInt();
        in.close();
        return greKey;
    }

    public void delete(UUID bridgeId) throws NoNodeException {
        dir.delete("/" + bridgeId.toString());
    }
}
