/*
 * Copyright 2013 Midokura PTE LTD.
 */
package org.midonet.cluster.data.ports;

import java.util.UUID;

/**
 * Interface for materialized ports
 */
public interface ExteriorPort<T> {
    /**
     * Set the host UUID to this server-side DTO object.
     *
     * @param hostId an UUID of the host to be set
     * @return       this server-side DTO object
     */
    T setHostId(UUID hostId);

    /**
     * Get the UUID of the host set in this server-side DTO object.
     *
     * @return the UUID of the host associated with this materialized port
     */
    UUID getHostId();

    /**
     * Set the interface name to this server-side DTO object.
     *
     * @param interfaceName a name of the interface to be set
     * @return              this server-side DTO object
     */
    T setInterfaceName(String interfaceName);

    /**
     * Get the interface name set in this server-side DTO object.
     *
     * @return the string of interface name associated with this materialized
     *         port
     */
    String getInterfaceName();
}
