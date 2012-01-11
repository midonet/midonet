/*
 * @(#)VifDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Vif;
import com.midokura.midolman.state.StateAccessException;

/**
 * Data access class for VIF.
 *
 * @version 1.6 29 Nov 2011
 * @author Ryu Ishimoto
 */
public interface VifDao {

    /**
     * Create a VIF.
     *
     * @param VIF
     *            VIF to create.
     * @return VIF ID.
     * @throws StateAccessException
     *             Data Access error.
     */
    UUID create(Vif vif) throws StateAccessException;

    /**
     * Delete a VIF.
     *
     * @param id
     *            ID of the VIF to delete.
     * @throws StateAccessException
     *             Data Access error.
     */
    void delete(UUID id) throws StateAccessException;

    /**
     * Get a VIF.
     *
     * @param id
     *            ID of the VIF to get.
     * @return Route object.
     * @throws StateAccessException
     *             Data Access error.
     */
    Vif get(UUID id) throws StateAccessException;

    /**
     * List VIFs.
     *
     * @return A list of Vif objects.
     * @throws StateAccessException
     *             Data Access error.
     */
    List<Vif> list() throws StateAccessException;
}
