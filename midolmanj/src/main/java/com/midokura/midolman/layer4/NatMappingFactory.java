// Copyright 2012 Midokura Inc.

package com.midokura.midolman.layer4;

import java.util.UUID;


public interface NatMappingFactory {
    NatMapping newNatMapping(UUID ownerID);
}
