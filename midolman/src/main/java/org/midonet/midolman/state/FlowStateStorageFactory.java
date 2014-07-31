/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state;

public interface FlowStateStorageFactory {
    FlowStateStorage create();
}
