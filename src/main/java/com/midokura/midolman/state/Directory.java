/*
 * Copyright 2011 Midokura KK 
 */

package com.midokura.midolman.state;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;

public interface Directory {

    void add(String relativePath, byte[] data, CreateMode createMode) throws KeeperException, InterruptedException;
    void update(String relativePath, byte[] data);
    byte[] get(String relativePath);
    
    Directory getSubDirectory(String path);
}
