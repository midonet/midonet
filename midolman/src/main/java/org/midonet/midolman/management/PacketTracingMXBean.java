/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.management;

public interface PacketTracingMXBean {
    static String NAME = "org.midonet.midolman:type=PacketTracing";

    PacketTracer[] getTracers();
    void addTracer(PacketTracer tracer);
    int removeTracer(PacketTracer tracer);
    int flush();
}
