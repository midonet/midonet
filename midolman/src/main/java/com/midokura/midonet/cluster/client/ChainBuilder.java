package com.midokura.midonet.cluster.client;/*
 * Copyright 2012 Midokura Europe SARL
 */

import java.util.Collection;

import com.midokura.midolman.rules.Rule;

public interface ChainBuilder {
    void setRules(Collection<Rule> rules);
}
