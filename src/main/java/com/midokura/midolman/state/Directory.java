/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.state;

import java.util.Set;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException.NoChildrenForEphemeralsException;
import org.apache.zookeeper.KeeperException.NoNodeException;
import org.apache.zookeeper.KeeperException.NodeExistsException;

public interface Directory {

    String add(String relativePath, byte[] data, CreateMode mode)
            throws NoNodeException, NodeExistsException, 
            NoChildrenForEphemeralsException;
    void update(String relativePath, byte[] data) throws NoNodeException;
    byte[] get(String relativePath, Runnable watcher) throws NoNodeException;
    Set<String> getChildren(String relativePath, Runnable watcher) 
            throws NoNodeException;
    boolean has(String relativePath);
    void delete(String relativePath) throws NoNodeException;
    Directory getSubDirectory(String relativePath) throws NoNodeException;
}
