package com.midokura.midostore;/*
 * Copyright 2012 Midokura Europe SARL
 */

import java.util.UUID;

public interface DeviceBuilder {
    void setID(UUID id);
    void setInFilter(UUID filterID);
    void setOutFilter(UUID filterID);
    void build();
}
