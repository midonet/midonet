/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.client;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.midonet.midolman.rules.Rule;

public interface ChainBuilder {
    void setRules(List<Rule> rules);
    void setRules(List<UUID> ruleOrder, Map<UUID, Rule> rules);
}
