/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.midonet.cluster.client;

import java.util.UUID;

import com.midokura.util.functors.Callback1;
import com.midokura.packets.MAC;

/*
 * Non-blocking.
 */
public interface MacLearningTable {
    void get(MAC mac, Callback1<UUID> cb);
    void add(MAC mac, UUID portID);
    void remove(MAC mac, UUID portID);
}
