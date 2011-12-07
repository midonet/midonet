// Copyright 2011 Midokura Inc.

package com.midokura.midolman.layer3;

import java.util.UUID;


// MBean which exposes the Routers' ARP caches.

public interface RouterMBean {
    // Really an array of UUID
    public Object[] getPortSet();

    // Really an array of Integer
    public Object[] getArpCacheKeys(UUID portUuid);

    public String getArpCacheEntry(UUID portUuid, int ipAddress);
    // TODO: Convert the int ipAddresses used by Router to IntIPv4
}
