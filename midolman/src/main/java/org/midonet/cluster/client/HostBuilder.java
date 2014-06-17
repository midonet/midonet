/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.client;

import java.util.Map;
import java.util.UUID;

import org.midonet.cluster.data.TunnelZone;

public interface HostBuilder extends Builder<HostBuilder> {

    HostBuilder setDatapathName(String datapathName);

    HostBuilder addMaterializedPortMapping(UUID portId, String interfaceName);

    HostBuilder delMaterializedPortMapping(UUID portId, String interfaceName);

    HostBuilder setTunnelZones(Map<UUID, TunnelZone.HostConfig> zoneConfigsForHost);
}
