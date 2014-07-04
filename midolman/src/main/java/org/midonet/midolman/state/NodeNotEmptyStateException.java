/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

import org.apache.zookeeper.KeeperException.NotEmptyException;

/**
 * Thrown when attempting to delete a node which still has children.
 */
public class NodeNotEmptyStateException extends StatePathExceptionBase {

    private static final long serialVersionUID = 1L;

    public NodeNotEmptyStateException(String message, String basePath,
                                      NotEmptyException cause) {
        super(message, cause.getPath(), basePath, cause);
    }
}
