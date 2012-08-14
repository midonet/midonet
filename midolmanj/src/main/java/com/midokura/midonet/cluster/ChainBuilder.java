package com.midokura.midonet.cluster;/*
 * Copyright 2012 Midokura Europe SARL
 */

import com.midokura.midolman.rules.Rule;

public interface ChainBuilder {
    void addRule(Rule rule);
    void removeRule(Rule rule);
    void build();
}
