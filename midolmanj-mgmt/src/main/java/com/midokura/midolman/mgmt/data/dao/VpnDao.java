/*
 * Copyright 2011 Midokura KK
 * Copyright 2012 Midokura PTE LTD.
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for VPN.
 */
public interface VpnDao extends GenericDao <Vpn, UUID> {

    /**
     * List VPNs.
     *
     * @return A list of VPN objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Vpn> findByPort(UUID portId) throws StateAccessException;

}
