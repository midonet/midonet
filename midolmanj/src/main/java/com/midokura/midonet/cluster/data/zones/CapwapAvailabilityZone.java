/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.data.zones;

import java.util.UUID;

import javax.annotation.Nonnull;

import com.midokura.midonet.cluster.data.AvailabilityZone;

/**
 *
 */
public class CapwapAvailabilityZone
    extends AvailabilityZone<CapwapAvailabilityZone, CapwapAvailabilityZone.Data> {

    public CapwapAvailabilityZone() {
        this(null, new Data());
    }

    public CapwapAvailabilityZone(UUID uuid) {
        this(uuid, new Data());
    }

    @Override
    public Type getType() {
        return Type.Capwap;
    }

    public CapwapAvailabilityZone(UUID zoneId, @Nonnull Data data) {
        super(zoneId, data);
    }

    @Override
    protected CapwapAvailabilityZone self() {
        return this;
    }

    public static class Data extends AvailabilityZone.Data {

    }
}
