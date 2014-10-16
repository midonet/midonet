/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

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
import org.midonet.midolman.state.NoStatePathException;
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
        } catch (NoStatePathException e) {
            log.debug("Condition set {} has been deleted", id);
        } catch (StateAccessException | SerializationException e) {
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
