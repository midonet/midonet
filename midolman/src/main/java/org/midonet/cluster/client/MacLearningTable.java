/*
 * Copyright 2012 Midokura Europe SARL
 */
package org.midonet.cluster.client;

import java.util.UUID;

import org.midonet.packets.MAC;
import org.midonet.util.functors.Callback3;

public interface MacLearningTable {
    UUID get(MAC mac);

    void add(MAC mac, UUID portID);

    void remove(MAC mac, UUID portID);

    void notify(Callback3<MAC, UUID, UUID> cb);
}
