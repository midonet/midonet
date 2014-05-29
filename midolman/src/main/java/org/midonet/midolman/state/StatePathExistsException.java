/*
 * Copyright (c) 2011 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import org.apache.zookeeper.KeeperException.NodeExistsException;

public class StatePathExistsException extends StatePathExceptionBase {
    private static final long serialVersionUID = 1L;

    public StatePathExistsException(String message, String basePath,
                                    NodeExistsException cause) {
        super(message, cause.getPath(), basePath, cause);
    }

    /**
     * Provided for TunnelZoneZkManager(), which generates a
     * StatePathExistsException without an underlying KeeperException.
     * If you use this constructor, getNodeInfo() will blow up.
     */
    @Deprecated
    public StatePathExistsException(String message) {
        super(message);
    }
}
