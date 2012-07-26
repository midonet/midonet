package com.midokura.midostore;/*
 * Copyright 2012 Midokura Europe SARL
 */

import java.util.UUID;

public interface DeviceBuilder<
        ConcreteDeviceBulder extends DeviceBuilder<ConcreteDeviceBulder>
    >
    extends Builder<ConcreteDeviceBulder> {

    ConcreteDeviceBulder setID(UUID id);

    ConcreteDeviceBulder setInFilter(UUID filterID);

    ConcreteDeviceBulder setOutFilter(UUID filterID);

    void build();
}
