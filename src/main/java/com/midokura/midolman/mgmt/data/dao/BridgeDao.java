package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Bridge;
import com.midokura.midolman.state.StateAccessException;

public interface BridgeDao extends OwnerQueryable {

    UUID create(Bridge bridge) throws StateAccessException;

    Bridge get(UUID id) throws StateAccessException;

    List<Bridge> list(String tenantId) throws StateAccessException;

    void update(Bridge bridge) throws StateAccessException;

    void delete(UUID id) throws StateAccessException;

}
