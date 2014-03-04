/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.cluster;

public class ClusterClientException extends Exception {

    private static final long serialVersionUID = -2929352248590064787L;

    public ClusterClientException() {
        super();
    }

    public ClusterClientException(String message) {
        super(message);
    }

    public ClusterClientException(Throwable cause) {
        super(cause);
    }
}
