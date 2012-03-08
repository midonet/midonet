/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.openvswitch;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;

public class MockOpenvSwitchBridges implements Iterable<MockOpenvSwitchBridge> {
    Map<String, MockOpenvSwitchBridge> bridgesByName = new HashMap<String, MockOpenvSwitchBridge>();
    Map<Long, MockOpenvSwitchBridge> bridgesById = new HashMap<Long, MockOpenvSwitchBridge>();

    public MockOpenvSwitchBridge get(String key) {
        return bridgesByName.get(key);
    }

    public void put(String name, MockOpenvSwitchBridge mockOpenvSwitchBridge) {
        bridgesByName.put(name, mockOpenvSwitchBridge);
        // TODO check if it's in the other map
    }

    public MockOpenvSwitchBridge get(long bridgeId) {
        return bridgesById.get(bridgeId);
    }

    public void put (Long bridgeId, MockOpenvSwitchBridge mockOpenvSwitchBridge) {
        bridgesById.put(bridgeId, mockOpenvSwitchBridge);
        // TODO check if it's on the other map
    }

    public boolean has(String bridgeName) {
        return bridgesByName.containsKey(bridgeName);
    }

    public boolean has(long bridgeId) {
        return bridgesById.containsKey(bridgeId);
    }

    @Override
    public Iterator<MockOpenvSwitchBridge> iterator() {
        return bridgesById.values().iterator();
    }
}
