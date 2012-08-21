/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midonet.cluster.client;

public interface ForwardingElementBuilder
    extends DeviceBuilder<ForwardingElementBuilder> {
    void setSourceNatResource(SourceNatResource resource);
}
