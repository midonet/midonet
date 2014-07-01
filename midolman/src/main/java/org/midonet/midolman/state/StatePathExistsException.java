/*
 * Copyright (c) 2011 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import java.util.UUID;

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
     */
    public StatePathExistsException(String message, UUID tzId) {
        super(message);
        this.nodeInfo = new NodeInfo(NodeType.TUNNEL_ZONE, tzId);
    }
}
