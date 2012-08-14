package com.midokura.midonet.cluster.client;/*
 * Copyright 2012 Midokura Europe SARL
 */

import java.util.UUID;

import com.midokura.midolman.util.Callback1;
import com.midokura.packets.IPv4;
import com.midokura.packets.MAC;

/**
 * Non-blocking.
 */
public interface ArpCache {
    void get(IPv4 ipAddr, Callback1<MAC> cb);
    void add(IPv4 ipAddr, MAC mac);
    void remove(IPv4 ipAddr, MAC mac);
}
