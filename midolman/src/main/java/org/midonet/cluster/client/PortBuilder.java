/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package org.midonet.cluster.client;

public interface PortBuilder extends Builder<PortBuilder> {
    void setPort(Port<?> p);
}
