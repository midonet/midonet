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
public class IpsecAvailabilityZoneHost
    extends AvailabilityZone.HostConfig<IpsecAvailabilityZoneHost, IpsecAvailabilityZoneHost.Data> {

    public IpsecAvailabilityZoneHost() {
        this(null, new Data());
    }

    public IpsecAvailabilityZoneHost(UUID uuid) {
        this(uuid, new Data());
    }

    public IpsecAvailabilityZoneHost(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected IpsecAvailabilityZoneHost self() {
        return this;
    }

    public static class Data extends AvailabilityZone.HostConfig.Data {
    }
}
