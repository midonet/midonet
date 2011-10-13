package com.midokura.midolman.mgmt.data;

import java.util.UUID;

import com.midokura.midolman.state.StateAccessException;
import com.midokura.midolman.state.ZkStateSerializationException;

public interface OwnerQueryable {

    public String getOwner(UUID id) throws StateAccessException,
            ZkStateSerializationException;
}
