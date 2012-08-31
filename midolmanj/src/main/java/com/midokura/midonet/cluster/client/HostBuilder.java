/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.client;

import java.util.Map;
import java.util.UUID;

import com.midokura.midonet.cluster.data.AvailabilityZone;

public interface HostBuilder extends Builder<HostBuilder> {

    HostBuilder setDatapathName(String datapathName);

    HostBuilder addMaterializedPortMapping(UUID portId, String interfaceName);

    HostBuilder delMaterializedPortMapping(UUID portId, String interfaceName);

    HostBuilder setAvailabilityZones(Map<UUID, AvailabilityZone.HostConfig<?,?>> zoneConfigsForHost);
}
