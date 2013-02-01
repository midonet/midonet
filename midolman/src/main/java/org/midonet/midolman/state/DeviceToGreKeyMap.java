/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.midolman.state;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.UUID;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

public class DeviceToGreKeyMap {

    private Directory dir;

    public DeviceToGreKeyMap(Directory dir) {
        this.dir = dir;
    }

    public void add(UUID deviceId, int gre_key) throws IOException,
            KeeperException, InterruptedException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeInt(gre_key);
        out.close();
        dir.add("/" + deviceId.toString(), bos.toByteArray(),
                CreateMode.PERSISTENT);
    }

    public boolean exists(UUID deviceId) throws KeeperException, InterruptedException {
        return dir.has("/" + deviceId.toString());
    }

    public void setGreKey(UUID deviceId, int gre_key) throws IOException,
            KeeperException, InterruptedException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ObjectOutputStream out = new ObjectOutputStream(bos);
        out.writeInt(gre_key);
        out.close();
        dir.update("/" + deviceId.toString(), bos.toByteArray());
    }

    public int getGreKey(UUID deviceId) throws IOException, KeeperException,
            InterruptedException {
        byte[] data = dir.get("/" + deviceId.toString(), null);
        ByteArrayInputStream bis = new ByteArrayInputStream(data);
        ObjectInputStream in = new ObjectInputStream(bis);
        int greKey = in.readInt();
        in.close();
        return greKey;
    }

    public void delete(UUID deviceId) throws KeeperException,
            InterruptedException {
        dir.delete("/" + deviceId.toString());
    }
}
