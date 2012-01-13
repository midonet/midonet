/*
 * @(#)VpnDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for VPN.
 *
 * @version 1.6 29 Nov 2011
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
