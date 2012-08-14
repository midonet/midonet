package com.midokura.midonet.cluster.client;/*
 * Copyright 2012 Midokura Europe SARL
 */

import java.util.NavigableSet;
import java.util.UUID;

import com.midokura.midolman.util.Callback1;

public interface SourceNatResource {
    void getSnatBlocks(int ip, Callback1<NavigableSet<Integer>> cb);
    void addSnatReservation(int ip, int startPort, Callback1<Boolean> cb);
}
