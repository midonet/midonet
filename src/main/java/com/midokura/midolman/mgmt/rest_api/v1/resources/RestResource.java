/*
 * @(#)RestResource.java        1.6 11/09/05
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.rest_api.v1.resources;

import javax.servlet.ServletContext;
import javax.ws.rs.core.Context;

/**
 * Base abstract class for all the resources.
 *
 * @version        1.6 05 Sept 2011
 * @author         Ryu Ishimoto
 */
public abstract class RestResource {
    /*
     * Provide resources that can be shared for all the subclassed resources.
     */
	
    /** Zookeeper connection string **/
    protected String zookeeperConn = null;
	
    /**
     * Set zookeeper connection from config at the application initialization.
     * 
     * @param  context  ServletContext object to which it gets data from.
     */
    @Context
    public void setZookeeperConn(ServletContext context) {
        zookeeperConn = context.getInitParameter("zookeeper-connection");
    }
}
