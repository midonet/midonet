/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.state;

import org.apache.zookeeper.KeeperException.NodeExistsException;

public class StatePathExistsException extends StateAccessException {
    private static final long serialVersionUID = 1L;

    // Path to node whose existence caused the exception.
    private String path;

    /**
     * Default constructor
     */
    public StatePathExistsException(String message, String path) {
        super(message);
        this.path = path;
    }

    public StatePathExistsException(String message, NodeExistsException cause) {
        super(message, cause);
        path = cause.getPath();
    }

    /**
     * Returns path to node whose existence caused the exception.
     */
    public String getPath() {
        return path;
    }
}
