package org.midonet.cluster.client;/*
 * Copyright 2012 Midokura Europe SARL
 */

import java.util.UUID;

public interface DeviceBuilder<ConcreteDeviceBulder>
    extends Builder<ConcreteDeviceBulder> {

    ConcreteDeviceBulder setAdminStateUp(boolean adminStateUp);

    ConcreteDeviceBulder setInFilter(UUID filterID);

    ConcreteDeviceBulder setOutFilter(UUID filterID);

    void build();
}
