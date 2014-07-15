/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.cluster.client;

import java.util.Set;
import java.util.UUID;

import org.midonet.cluster.data.PortGroup;

public interface PortGroupBuilder {
    void setConfig(PortGroup portGroup);
    void setMembers(Set<UUID> members);
}
