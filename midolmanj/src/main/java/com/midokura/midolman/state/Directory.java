/*
 * Copyright 2011 Midokura KK
 */

package com.midokura.midolman.state;

import java.util.List;
import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Op;
import org.apache.zookeeper.OpResult;

public interface Directory {

    String add(String relativePath, byte[] data, CreateMode mode)
            throws KeeperException, InterruptedException;

    void update(String relativePath, byte[] data) throws KeeperException,
            InterruptedException;

    byte[] get(String relativePath, Runnable watcher) throws KeeperException,
            InterruptedException;

    Set<String> getChildren(String relativePath, Runnable watcher)
            throws KeeperException, InterruptedException;

    boolean has(String relativePath) throws KeeperException,
            InterruptedException;

    void delete(String relativePath) throws KeeperException,
            InterruptedException;

    Directory getSubDirectory(String relativePath) throws KeeperException;

    List<OpResult> multi(List<Op> ops) throws InterruptedException,
            KeeperException;

    long getSessionId();

    public interface TypedWatcher extends Runnable {

        void pathDeleted(String path);

        void pathCreated(String path);

        void pathChildrenUpdated(String path);

        void pathDataChanged(String path);

        void pathNoChange(String path);

    }

    public static class DefaultTypedWatcher implements TypedWatcher {
        @Override
        public void pathDeleted(String path) {
            run();
        }

        @Override
        public void pathCreated(String path) {
            run();
        }

        @Override
        public void pathChildrenUpdated(String path) {
            run();
        }

        @Override
        public void pathDataChanged(String path) {
            run();
        }

        @Override
        public void pathNoChange(String path) {
            run();
        }

        @Override
        public void run() {
        }
    }
}
