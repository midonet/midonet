/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.network;

import java.util.UUID;

/**
 * Interface representing exterior port.
 */
public interface ExteriorPort {

    /**
     * @return VIF ID
     */
    public UUID getVifId();

    /**
     * @param vifId
     *            VIF ID to set
     */
    public void setVifId(UUID vifId);
}
