/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.zones;

import java.util.UUID;
import javax.annotation.Nonnull;

import com.midokura.midonet.cluster.data.AvailabilityZone;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public class CapwapAvailabilityZoneHost
    extends
    AvailabilityZone.HostConfig<CapwapAvailabilityZoneHost, CapwapAvailabilityZoneHost.Data> {

    public CapwapAvailabilityZoneHost() {
        this(null, new Data());
    }

    public CapwapAvailabilityZoneHost(UUID uuid) {
        this(uuid, new Data());
    }

    public CapwapAvailabilityZoneHost(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected CapwapAvailabilityZoneHost self() {
        return this;
    }

    public static class Data extends AvailabilityZone.HostConfig.Data {
    }
}
