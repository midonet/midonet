package org.midonet.cluster.client;/*
 * Copyright 2012 Midokura Europe SARL
 */

import java.util.Collection;

import org.midonet.midolman.rules.Rule;

public interface ChainBuilder {
    void setRules(Collection<Rule> rules);
}
