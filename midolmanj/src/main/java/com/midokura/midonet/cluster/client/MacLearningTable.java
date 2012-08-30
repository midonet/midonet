/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midonet.cluster.client;

import java.util.UUID;

import com.midokura.packets.MAC;
import com.midokura.util.functors.Callback1;
import com.midokura.util.functors.Callback3;

/*
 * Non-blocking.
 */
public interface MacLearningTable {
    void get(MAC mac, Callback1<UUID> cb, Long expirationTime);

    void add(MAC mac, UUID portID);

    void remove(MAC mac, UUID portID);

    void notify(Callback3<MAC, UUID, UUID> cb);
}
