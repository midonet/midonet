/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */

package org.midonet.midolman.state.zkManagers;

import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

public interface ConfigGetter<K, CFG> {
    public CFG get(K key)
            throws StateAccessException, SerializationException;
}
