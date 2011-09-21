/*
 * @(#)ZkBasePathManager        1.6 11/09/21
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.state;

/**
 * This class was created to manage ZooKeeper base paths.
 * 
 * @version 1.6 21 Sept 2011
 * @author Ryu Ishimoto
 */
public class ZkBasePathManager {

    protected String basePath = null;

    /**
     * Constructor.
     * 
     * @param basePath
     *            Base path of Zk.
     */
    public ZkBasePathManager(String basePath) {
        this.basePath = basePath;
    }

    /**
     * @return the basePath
     */
    public String getBasePath() {
        return basePath;
    }

    /**
     * @param basePath
     *            the basePath to set
     */
    public void setBasePath(String basePath) {
        this.basePath = basePath;
    }
}
