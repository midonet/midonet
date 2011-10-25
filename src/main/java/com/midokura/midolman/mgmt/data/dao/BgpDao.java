package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Bgp;
import com.midokura.midolman.state.StateAccessException;

public interface BgpDao extends OwnerQueryable {

    UUID create(Bgp bgp) throws StateAccessException;

    Bgp get(UUID id) throws StateAccessException;

    List<Bgp> list(UUID portId) throws StateAccessException;

    void delete(UUID id) throws StateAccessException;

}