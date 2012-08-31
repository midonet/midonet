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
public class GreAvailabilityZone extends AvailabilityZone<GreAvailabilityZone, GreAvailabilityZone.Data> {

    public GreAvailabilityZone() {
        super(null, new Data());
    }

    public GreAvailabilityZone(UUID uuid) {
        super(uuid, new Data());
    }

    @Override
    public Type getType() {
        return Type.Gre;
    }

    public GreAvailabilityZone(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected GreAvailabilityZone self() {
        return this;
    }

    public static class Data extends AvailabilityZone.Data {

    }
}
