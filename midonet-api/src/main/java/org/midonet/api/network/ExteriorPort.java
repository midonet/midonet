/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import java.net.URI;
import java.util.UUID;

/**
 * Interface representing exterior port.
 */
public interface ExteriorPort {

    /**
     * @return VIF ID
     */
    UUID getVifId();

    /**
     * @param vifId
     *            VIF ID to set
     */
    void setVifId(UUID vifId);

    /**
     * Getter to be used to generate "host-interface-port" property's value.
     *
     * @return the URI of the host-interface-port binding
     */
    URI getHostInterfacePort();
}
