package com.midokura.midonet.cluster.client;/*
 * Copyright 2012 Midokura Europe SARL
 */

public interface ForwardingElementBuilder extends DeviceBuilder {
    void setSourceNatResource(SourceNatResource resource);
}
