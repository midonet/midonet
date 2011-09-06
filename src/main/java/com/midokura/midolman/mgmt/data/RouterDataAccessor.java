/*
 * @(#)Router        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data;

import com.midokura.midolman.state.Directory;
import com.midokura.midolman.state.RouterDirectory;
import com.midokura.midolman.state.ZkConnection;

/**
 * Data access class for router.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
public class RouterDataAccessor {
    /*
     * Implements CRUD operations on Router.
     */

	private String zkConn = null;
		
	/**
	 * Default constructor
	 * 
	 * @param  zkConn  Zookeeper connection string.
	 */
	public RouterDataAccessor(String zkConn) {
		this.zkConn = zkConn;
	}
	
	private RouterDirectory getRouterDirectory() throws Exception {
	        ZkConnection zk = ZookeeperService.getConnection(zkConn);
	    Directory dir = zk.getRootDirectory().getSubDirectory(
	            "/midolman/routers");
	    return new RouterDirectory(dir);
    }	

	/**
	 * Add Router object to Zookeeper directories.
	 * 
	 * @param   router  Router object to add.
	 * @throws  Exception  Error adding data to Zookeeper.
	 */
	public void create(Router router) throws Exception {
		RouterDirectory routerDir = getRouterDirectory();
		routerDir.addRouter(router.getId());
	}

}
