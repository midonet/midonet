/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package com.midokura.midolman.monitoring;

import java.util.UUID;

/**
 * Date: 6/1/12
 */
public interface HostIdProvider {

    public UUID getHostId();
}
