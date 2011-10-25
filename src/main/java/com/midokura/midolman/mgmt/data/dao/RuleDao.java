package com.midokura.midolman.mgmt.data.dao;

import java.util.List;
import java.util.UUID;

import com.midokura.midolman.mgmt.data.dto.Rule;
import com.midokura.midolman.state.RuleIndexOutOfBoundsException;
import com.midokura.midolman.state.StateAccessException;

public interface RuleDao extends OwnerQueryable {

    UUID create(Rule rule) throws RuleIndexOutOfBoundsException,
            StateAccessException;

    void delete(UUID id) throws StateAccessException;

    Rule get(UUID id) throws StateAccessException;

    List<Rule> list(UUID chainId) throws StateAccessException;
}
