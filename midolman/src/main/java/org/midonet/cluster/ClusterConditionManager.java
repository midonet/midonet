// Copyright 2013 Midokura Inc.

package org.midonet.cluster;

import java.util.Collections;
import java.util.List;
import java.util.UUID;
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

    List<Condition> conditionList = null;

    @Override
    protected void getConfig(UUID id) {
        log.debug("getConfig({}) - start", id);
        TraceConditionsBuilder builder = getBuilder(id);
        try {
            conditionList = conditionMgr.getConditions(watchConditionList(id));
        } catch (StateAccessException e) {
            log.error("Unable to get condition set: ", e);
            conditionList = Collections.emptyList();
        } catch (SerializationException e) {
            log.error("Unable to get condition set: ", e);
            conditionList = Collections.emptyList();
        }
        log.debug("Got the condition set: {}", conditionList);
        builder.setConditions(conditionList);
        builder.build();
    }

    Runnable watchConditionList(final UUID id) {
        return new Runnable() {
            @Override
            public void run() {
                getConfig(id);
            }
        };
    }
}
