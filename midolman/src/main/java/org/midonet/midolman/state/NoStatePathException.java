/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.state;

import org.apache.zookeeper.KeeperException.NoNodeException;

public class NoStatePathException extends StateAccessException {

    private static final long serialVersionUID = 1L;

    // Path to node whose nonexistence caused the exception.
    private String path;

    public NoStatePathException(String message, String path) {
        super(message);
        this.path = path;
    }

    public NoStatePathException(String message, NoNodeException cause) {
        super(message, cause);
        path = cause.getPath();
    }

    /**
     * Returns the path whose nonexistence caused the exception.
     */
    public String getPath() {
        return path;
    }

}
