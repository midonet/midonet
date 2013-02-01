// Copyright 2012 Midokura Inc.

package org.midonet.midolman.layer4;

import java.util.UUID;


public interface NatMappingFactory {
    NatMapping get(UUID ownerID);
}
