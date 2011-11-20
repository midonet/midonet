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

public interface VifDao extends OwnerQueryable {

    UUID create(Vif vif) throws StateAccessException;

    void delete(UUID id) throws StateAccessException;

    Vif get(UUID id) throws StateAccessException;

    List<Vif> list() throws StateAccessException;
}
