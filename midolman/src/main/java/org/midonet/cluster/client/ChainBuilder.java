package org.midonet.cluster.client;/*
 * Copyright 2012 Midokura Europe SARL
 */

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.midonet.midolman.rules.Rule;

public interface ChainBuilder {
    void setRules(List<Rule> rules);
    void setRules(List<UUID> ruleOrder, Map<UUID, Rule> rules);
}
