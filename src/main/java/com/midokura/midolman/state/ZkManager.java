/*
 * @(#)ZkManager        1.6 11/09/08
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

import java.io.IOException;

import org.apache.zookeeper.ZooKeeper;

import com.midokura.midolman.util.JSONSerializer;
import com.midokura.midolman.util.Serializer;

/**
 * Abstract base class for ZkManagers.
 * @version        1.6 11 Sept 2011
 * @author         Ryu Ishimoto
 */
public abstract class ZkManager {

    protected ZkPathManager pathManager = null;
    protected ZooKeeper zk = null;
    /**
     * Default constructor.
     * 
     * @param zk Zookeeper object.
     * @param basePath  Directory to set as the base.
     */
    public ZkManager(ZooKeeper zk, String basePath) {
        this.pathManager = new ZkPathManager(basePath);
        this.zk = zk;
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
