package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Vpn;
import com.midokura.midolman.state.StateAccessException;

public interface VpnDao extends OwnerQueryable {

    UUID create(Vpn vpn) throws StateAccessException;

    Vpn get(UUID id) throws StateAccessException;

    List<Vpn> list(UUID portId) throws StateAccessException;

    void delete(UUID id) throws StateAccessException;

}
