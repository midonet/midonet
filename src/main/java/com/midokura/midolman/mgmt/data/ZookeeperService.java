/*
 * @(#)ZookeeperService        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.state.ZkConnection;

/**
 * Class implementing a singleton Zookeeper connection.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
public class ZookeeperService {
    /*
     * Singleton implementation for Zookeeper connection.
     */
	
	private static ZkConnection conn = null;
	
	private ZookeeperService() {
	}

    /**
     * Get the Zookeeper connection.
     *
     * @param   connStr  Connection string.
     * @return  ZkConnection object.
     * @throws  Exception  Any exception thrown from connecting to Zookeeper.
     */
	public static synchronized ZkConnection getConnection(String connStr)
	        throws Exception {    
	    if (null == conn) {
	        conn = new ZkConnection(connStr, null);
	        conn.open();
	    }
	    return conn;
	}
}