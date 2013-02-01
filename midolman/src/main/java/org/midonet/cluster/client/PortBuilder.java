/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.cluster.client;

public interface PortBuilder extends Builder {
    void setPort(Port<?> p);
}
