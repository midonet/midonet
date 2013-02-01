/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.client;

import java.util.Set;
import java.util.UUID;

public interface PortSetBuilder extends Builder<PortSetBuilder> {

    PortSetBuilder setHosts(Set<UUID> hosts);

    PortSetBuilder addHost(UUID host);

    PortSetBuilder delHost(UUID host);
}
