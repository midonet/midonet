/*
 * @(#)ZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;
import java.util.List;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;

import com.midokura.midolman.util.JSONSerializer;
import com.midokura.midolman.util.Serializer;

/**
 * Abstract base class for ZkManagers.
 * 
 * @version 1.6 11 Sept 2011
 * @author Ryu Ishimoto
 */
public abstract class ZkManager {

    protected ZkPathManager pathManager = null;
    protected ZooKeeper zk = null;
    protected String basePath = null;

    protected class MyWatcher implements Watcher {
        Runnable watcher;

        MyWatcher(Runnable watch) {
            watcher = watch;
        }

        @Override
        public void process(WatchedEvent arg0) {
            watcher.run();
        }
    }

    /**
     * Default constructor.
     * 
     * @param zk
     *            Zookeeper object.
     * @param basePath
     *            Directory to set as the base.
     */
    public ZkManager(ZooKeeper zk, String basePath) {
        this.basePath = basePath;
        this.pathManager = new ZkPathManager(basePath);
        this.zk = zk;
    }

    public byte[] getData(String path, Runnable watcher) throws KeeperException,
            InterruptedException {
        return zk.getData(path, (null == watcher) ? null : new MyWatcher(
                watcher), null);
    }

    public List<String> getChildren(String path, Runnable watcher)
            throws KeeperException, InterruptedException {
        return zk.getChildren(path, (null == watcher) ? null : new MyWatcher(
                watcher));
    }

    protected static <T> byte[] serialize(T obj) throws IOException {
        Serializer<T> s = new JSONSerializer<T>();
        return s.objToBytes(obj);
    }

    protected static <T> T deserialize(byte[] obj, Class<T> clazz)
            throws IOException {
        Serializer<T> s = new JSONSerializer<T>();
        return s.bytesToObj(obj, clazz);
    }
}
