// Copyright 2012 Midokura Inc.

package org.midonet.midolman.state;

import java.util.UUID;


public interface LogicalPortConfig {
    UUID peerId();
    void setPeerId(UUID id);
}
