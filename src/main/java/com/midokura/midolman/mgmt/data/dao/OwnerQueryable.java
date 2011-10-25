package com.midokura.midolman.mgmt.data.dao;

import java.util.UUID;

import com.midokura.midolman.state.StateAccessException;

public interface OwnerQueryable {

    String getOwner(UUID id) throws StateAccessException;
}
