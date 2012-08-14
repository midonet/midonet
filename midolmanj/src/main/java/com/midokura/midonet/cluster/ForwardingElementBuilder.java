package com.midokura.midonet.cluster;/*
 * Copyright 2012 Midokura Europe SARL
 */

public interface ForwardingElementBuilder extends DeviceBuilder {
    void setSourceNatResource(SourceNatResource resource);
}
