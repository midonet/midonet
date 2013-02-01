/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.cluster.client;

import java.util.UUID;

import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback1;
import org.midonet.util.functors.Callback3;

/*
 * Non-blocking.
 */
public interface MacLearningTable {
    void get(MAC mac, Callback1<UUID> cb, Long expirationTime);

    void add(MAC mac, UUID portID);

    void remove(MAC mac, UUID portID);

    void notify(Callback3<MAC, UUID, UUID> cb);
}
