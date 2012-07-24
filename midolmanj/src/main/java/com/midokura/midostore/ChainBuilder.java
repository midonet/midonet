package com.midokura.midostore;/*
 * Copyright 2012 Midokura Europe SARL
 */

import com.midokura.midolman.rules.Rule;

public interface ChainBuilder {
    void addRule(Rule rule);
    void removeRule(Rule rule);
    void build();
}
