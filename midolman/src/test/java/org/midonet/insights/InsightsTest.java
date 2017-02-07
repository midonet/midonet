/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.insights;

import java.util.Collections;
import java.util.List;
import java.util.UUID;

import com.codahale.metrics.MetricRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.reflections.Reflections;

import org.midonet.midolman.PacketWorkflow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMetadata;
import org.midonet.odp.Packet;
import org.midonet.sdn.flows.FlowTagger;

public class InsightsTest {

    private static class Listener implements Insights.Listener {

        int flowsAdded = 0;
        int flowSimulation = 0;
        int flowDeleted = 0;
        public final MetricRegistry metrics;

        public Listener(MetricRegistry metrics) {
            this.metrics = metrics;
        }

        @Override
        public void flowAdded(FlowMatch flowMatch,
                              List<FlowTagger.FlowTag> flowTags,
                              long expiration) {
            flowsAdded++;
        }

        @Override
        public void flowSimulation(long cookie,
                                   Packet packet,
                                   UUID inputPort,
                                   FlowMatch flowMatch,
                                   List<FlowTagger.FlowTag> flowTags,
                                   PacketWorkflow.SimulationResult result) {
            flowSimulation++;
        }

        @Override
        public void flowDeleted(FlowMatch flowMatch, FlowMetadata metadata) {
            flowDeleted++;
        }
    }

    @Test
    public void testDefaultListener() {
        // Given a reflection instance and a metric registry.
        Reflections reflections = new Reflections(Void.class);
        MetricRegistry metrics = new MetricRegistry();

        // When creating an insights instance.
        Insights insights = new Insights(reflections, metrics);

        // Then the current listener is not null.
        Assert.assertNotNull(insights.currentListener());
    }

    @Test
    public void testSingleListener() {
        // Given a reflection instance and a metric registry.
        Reflections reflections = new Reflections(Listener.class);
        MetricRegistry metrics = new MetricRegistry();

        // When creating an insights instance.
        Insights insights = new Insights(reflections, metrics);

        // Then the current listener is not null.
        Assert.assertNotNull(insights.currentListener());

        // And the current listener is an instance of Listener.
        Assert.assertEquals(insights.currentListener().getClass(),
                            Listener.class);

        // And the listener was passed the metrics.
        Listener listener = (Listener) insights.currentListener();
        Assert.assertEquals(listener.metrics, metrics);

        // When adding a flow.
        insights.flowAdded(Mockito.mock(FlowMatch.class),
                           Collections.emptyList(),
                           0L);

        // Then the call is passed to the listener.
        Assert.assertEquals(1, listener.flowsAdded);

        // When recording a simulation.
        insights.flowSimulation(0L,
                                Mockito.mock(Packet.class),
                                UUID.randomUUID(),
                                Mockito.mock(FlowMatch.class),
                                Collections.emptyList(),
                                PacketWorkflow.NoOp$.MODULE$);

        // Then the call is passed to the listener.
        Assert.assertEquals(1, listener.flowSimulation);

        // When recording a flow.
        insights.flowDeleted(Mockito.mock(FlowMatch.class),
                             Mockito.mock(FlowMetadata.class));

        // Then the call is passed to the listener.
        Assert.assertEquals(1, listener.flowDeleted);
    }

}
