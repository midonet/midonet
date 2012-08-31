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
public class IpsecAvailabilityZone
    extends AvailabilityZone<IpsecAvailabilityZone, IpsecAvailabilityZone.Data> {

    public IpsecAvailabilityZone() {
        this(null, new Data());
    }

    public IpsecAvailabilityZone(UUID uuid) {
        this(uuid, new Data());
    }

    @Override
    public Type getType() {
        return Type.Ipsec;
    }

    public IpsecAvailabilityZone(UUID uuid, @Nonnull Data data) {
        super(uuid, data);
    }

    @Override
    protected IpsecAvailabilityZone self() {
        return this;
    }

    public static class Data extends AvailabilityZone.Data {

    }
}
