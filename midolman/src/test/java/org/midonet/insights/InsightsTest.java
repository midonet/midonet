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

import com.codahale.metrics.MetricRegistry;

import org.junit.Assert;
import org.junit.Test;
import org.reflections.Reflections;

import org.midonet.midolman.PacketWorkflow;
import org.midonet.midolman.flows.ManagedFlow;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.odp.FlowMetadata;

public class InsightsTest {

    private static class Listener implements Insights.Listener {

        public int simulationCalls = 0;
        public int flowCalls = 0;
        public final MetricRegistry metrics;

        public Listener(MetricRegistry metrics) {
            this.metrics = metrics;
        }

        @Override
        public void recordSimulation(PacketContext context,
                                     PacketWorkflow.SimulationResult result) {
            simulationCalls++;
        }

        @Override
        public void recordFlow(ManagedFlow flow, FlowMetadata metadata) {
            flowCalls++;
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

        // When recording a simulation.
        insights.recordSimulation(null, null);

        // Then the call is passed to the listener.
        Assert.assertEquals(listener.simulationCalls, 1);

        // When recording a flow.
        insights.recordFlow(null, null);

        // Then the call is passed to the listener.
        Assert.assertEquals(listener.flowCalls, 1);
    }

}
