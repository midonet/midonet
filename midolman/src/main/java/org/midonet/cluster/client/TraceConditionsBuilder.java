// Copyright 2013 Midokura Inc.

package org.midonet.cluster.client;

import java.util.Set;

import org.midonet.midolman.rules.Condition;


public interface TraceConditionsBuilder extends Builder<TraceConditionsBuilder> {
    void setConditions(Set<Condition> conditions);
}
