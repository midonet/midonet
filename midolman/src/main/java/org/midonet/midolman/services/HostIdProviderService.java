/*
 * Copyright (c) 2012 Midokura Pte.Ltd.
 */

package org.midonet.midolman.services;

import java.util.UUID;

/**
 * Date: 6/1/12
 */
public interface HostIdProviderService {
    public UUID getHostId();
}
