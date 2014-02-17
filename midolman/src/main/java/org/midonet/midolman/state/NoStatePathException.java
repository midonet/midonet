/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.midolman.state;

import org.apache.zookeeper.KeeperException.NoNodeException;

public class NoStatePathException extends StatePathExceptionBase {
    private static final long serialVersionUID = 1L;

    public NoStatePathException(String message, String basePath,
                                NoNodeException cause) {
        super(message, cause.getPath(), basePath, cause);
    }
}
