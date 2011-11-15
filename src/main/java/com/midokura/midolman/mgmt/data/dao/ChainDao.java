/*
 * @(#)ChainDao        1.6 11/11/15
 *
 * Copyright 2011 Midokura KK
 */
package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Chain;
import com.midokura.midolman.state.StateAccessException;

public interface ChainDao extends OwnerQueryable {

    UUID create(Chain chain) throws StateAccessException;

    List<Chain> listTableChains(UUID routerId, String table)
            throws StateAccessException;

    List<Chain> listNatChains(UUID routerId) throws StateAccessException;

    List<Chain> list(UUID routerId) throws StateAccessException;

    Chain get(UUID routerId, String table, String name)
            throws StateAccessException;

    Chain get(UUID id) throws StateAccessException;

    void delete(UUID id) throws StateAccessException;

}
