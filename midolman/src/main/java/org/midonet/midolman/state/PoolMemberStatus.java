/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.midolman.state;

/**
 * Pool member status as determined by the health monitor.
 */
public enum PoolMemberStatus {
    UP("UP"),
    DOWN("DOWN");

    private final String value;

    private PoolMemberStatus(String value) {
        this.value = value;
    }

    @Override
    public String toString() {
        return value;
    }
}
