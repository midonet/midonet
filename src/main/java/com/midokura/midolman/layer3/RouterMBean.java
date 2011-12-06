// Copyright 2011 Midokura Inc.

package com.midokura.midolman.layer3;

import java.util.UUID;


// MBean which exposes the Routers' ARP caches.

public interface RouterMBean {
    public String getArpCacheEntry(UUID portUuid, int ipAddress);
    // TODO: Convert the int ipAddresses used by Router to IntIPv4
}
