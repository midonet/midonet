// Copyright 2013 Midokura Inc.

package org.midonet.cluster;

import java.util.Collections;
import java.util.UUID;
import java.util.Set;
import javax.inject.Inject;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.cluster.client.TraceConditionsBuilder;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;
import org.midonet.midolman.state.zkManagers.TraceConditionZkManager;


public class ClusterConditionManager extends ClusterManager<TraceConditionsBuilder> {
    private static final Logger log =
        LoggerFactory.getLogger(ClusterConditionManager.class);

    @Inject
    TraceConditionZkManager conditionMgr;

    Set<Condition> conditionSet = null;

    @Override
    protected void getConfig(UUID id) {
        TraceConditionsBuilder builder = getBuilder(id);
        try {
            conditionSet = conditionMgr.getConditions(watchConditionSet(id));
        } catch (StateAccessException e) {
            log.error("Unable to get condition set: ", e);
            conditionSet = Collections.emptySet();
        } catch (SerializationException e) {
            log.error("Unable to get condition set: ", e);
            conditionSet = Collections.emptySet();
        }
        builder.setConditions(conditionSet);
        builder.build();
    }

    Runnable watchConditionSet(final UUID id) {
        return new Runnable() {
            @Override
            public void run() {
                getConfig(id);
            }
        };
    }
}
