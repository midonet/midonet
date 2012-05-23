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
 * @author Yoshi Tamura
 */
public interface VpnDao {

    /**
     * Create a VPN.
     *
     * @param VPN
     *            VPN to create.
     * @return VPN ID.
     * @throws StateAccessException
     *             Data Access error.
     */
    UUID create(Vpn vpn) throws StateAccessException;

    /**
     * Delete a VPN.
     *
     * @param id
     *            ID of the VPN to delete.
     * @throws StateAccessException
     *             Data Access error.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * Get a VPN.
     *
     * @param id
     *            ID of the VPN to get.
     * @return Route object.
     * @throws StateAccessException
     *             Data Access error.
     */
    Vpn get(UUID id) throws StateAccessException;

    /**
     * List VPNs.
     *
     * @return A list of VPN objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Vpn> list(UUID portId) throws StateAccessException;

}
