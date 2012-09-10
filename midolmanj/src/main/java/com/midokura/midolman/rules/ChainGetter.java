// Copyright 2012 Midokura Inc.

package com.midokura.midolman.rules;

import java.util.UUID;

import org.openflow.protocol.OFMatch;

import com.midokura.midolman.layer4.NatMapping;
import com.midokura.midolman.state.StateAccessException;


public interface ChainGetter {
    Chain getOrCreateChain(UUID id) throws StateAccessException;
    NatMapping getNatMapping(UUID ownerId);
    void freeFlowResources(OFMatch match, UUID ownerID);
}
