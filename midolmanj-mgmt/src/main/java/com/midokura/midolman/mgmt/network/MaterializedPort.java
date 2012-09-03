/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.network;

import java.util.UUID;

/**
 * Interface representing materialized port.
 */
public interface MaterializedPort {

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
