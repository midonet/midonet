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

import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.AbstractService;

import org.apache.curator.framework.CuratorFramework;
import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;

import org.midonet.midolman.PacketWorkflow;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMetadata;
import org.midonet.sdn.flows.FlowTagger;

public class InsightsTest {

    public static class Listener
        extends AbstractService implements Insights.Listener {

        int flowsAdded = 0;
        int flowSimulation = 0;
        int flowDeleted = 0;
        public final MidolmanConfig config;
        public final CuratorFramework curator;
        public final MetricRegistry metrics;

        public Listener(MidolmanConfig config,
                        CuratorFramework curator,
                        MetricRegistry metrics) {
            this.config = config;
            this.curator = curator;
            this.metrics = metrics;
        }

        @Override
        public void flowAdded(FlowMatch flowMatch,
                              List<FlowTagger.FlowTag> flowTags,
                              long expiration) {
            flowsAdded++;
        }

        @Override
        public void flowSimulation(PacketContext context,
                                   PacketWorkflow.SimulationResult result) {
            flowSimulation++;
        }

        @Override
        public void flowDeleted(FlowMatch flowMatch, FlowMetadata metadata) {
            flowDeleted++;
        }

        @Override
        protected void doStart() {
            notifyStarted();
        }

        @Override
        protected void doStop() {
            notifyStopped();
        }
    }

    public static class InvalidConstructorListener
        extends AbstractService implements Insights.Listener {

        @Override
        public void flowAdded(FlowMatch flowMatch,
                              List<FlowTagger.FlowTag> flowTags,
                              long expiration) {
        }

        @Override
        public void flowSimulation(PacketContext context,
                                   PacketWorkflow.SimulationResult result) {
        }

        @Override
        public void flowDeleted(FlowMatch flowMatch, FlowMetadata metadata) {
        }

        @Override
        protected void doStart() {
            notifyStarted();
        }

        @Override
        protected void doStop() {
            notifyStopped();
        }
    }

    public static class InvalidClassListener {
    }

    @Test
    public void testDefaultListener() {
        // Given an empty config and a metric registry.
        MidolmanConfig config = MidolmanConfig.forTests(
            "agent.insights.listener_class = \"\"");
        MetricRegistry metrics = new MetricRegistry();

        // And a curator instance.
        CuratorFramework curator = Mockito.mock(CuratorFramework.class);

        // When creating an insights instance.
        Insights insights = new Insights(config, curator, metrics);

        // Then the current listener is not null.
        Assert.assertNotNull(insights.currentListener());

        // And all methods are implemented.
        insights.flowAdded(Mockito.mock(FlowMatch.class),
                           Collections.emptyList(),
                           0L);
        insights.flowSimulation(new PacketContext(),
                                PacketWorkflow.NoOp$.MODULE$);
        insights.flowDeleted(Mockito.mock(FlowMatch.class),
                             Mockito.mock(FlowMetadata.class));

        // When starting insights.
        insights.startAsync().awaitRunning();

        // Then insights is started.
        Assert.assertTrue(insights.currentListener().isRunning());

        // Then stopping insights.
        insights.stopAsync().awaitTerminated();

        // Then insights is stopped.
        Assert.assertFalse(insights.currentListener().isRunning());
    }

    @Test
    public void testInvalidListener() {
        // Given a non-existing listener and a metric registry.
        MidolmanConfig conf = MidolmanConfig.forTests(
            "agent.insights.listener_class = \"not.a.listener\"");
        MetricRegistry metrics = new MetricRegistry();

        // And a curator instance.
        CuratorFramework curator = Mockito.mock(CuratorFramework.class);

        // When creating an insights instance.
        Insights insights = new Insights(conf, curator, metrics);

        // Then the current listener is not null.
        Assert.assertNotNull(insights.currentListener());
    }

    @Test
    public void testInvalidConstructor() {
        // Given a non-existing listener and a metric registry.
        MidolmanConfig conf = MidolmanConfig.forTests(
            "agent.insights.listener_class = \"" +
            InvalidConstructorListener.class.getName() + "\"");
        MetricRegistry metrics = new MetricRegistry();

        // And a curator instance.
        CuratorFramework curator = Mockito.mock(CuratorFramework.class);

        // When creating an insights instance.
        Insights insights = new Insights(conf, curator, metrics);

        // Then the current listener is not null.
        Assert.assertNotNull(insights.currentListener());
    }

    @Test
    public void testInvalidClass() {
        // Given a non-existing listener and a metric registry.
        MidolmanConfig conf = MidolmanConfig.forTests(
            "agent.insights.listener_class = \"" +
            InvalidClassListener.class.getName() + "\"");
        MetricRegistry metrics = new MetricRegistry();

        // And a curator instance.
        CuratorFramework curator = Mockito.mock(CuratorFramework.class);

        // When creating an insights instance.
        Insights insights = new Insights(conf, curator, metrics);

        // Then the current listener is not null.
        Assert.assertNotNull(insights.currentListener());
    }

    @Test
    public void testSingleListener() throws ClassNotFoundException {
        // Given a valid listener and a metric registry.
        MidolmanConfig config = MidolmanConfig.forTests(
            "agent.insights.listener_class = \"" + Listener.class.getName() + "\"");
        MetricRegistry metrics = new MetricRegistry();

        // And a curator instance.
        CuratorFramework curator = Mockito.mock(CuratorFramework.class);

        // When creating an insights instance.
        Insights insights = new Insights(config, curator, metrics);

        // Then the current listener is not null.
        Assert.assertNotNull(insights.currentListener());

        // And the current listener is an instance of Listener.
        Assert.assertEquals(Listener.class,
                            insights.currentListener().getClass());

        // And the listener was passed the metrics.
        Listener listener = (Listener) insights.currentListener();
        Assert.assertEquals(listener.config, config);
        Assert.assertEquals(listener.curator, curator);
        Assert.assertEquals(listener.metrics, metrics);

        // When adding a flow.
        insights.flowAdded(Mockito.mock(FlowMatch.class),
                           Collections.emptyList(),
                           0L);

        // Then the call is passed to the listener.
        Assert.assertEquals(1, listener.flowsAdded);

        // When recording a simulation.
        insights.flowSimulation(new PacketContext(),
                                PacketWorkflow.NoOp$.MODULE$);

        // Then the call is passed to the listener.
        Assert.assertEquals(1, listener.flowSimulation);

        // When recording a flow.
        insights.flowDeleted(Mockito.mock(FlowMatch.class),
                             Mockito.mock(FlowMetadata.class));

        // Then the call is passed to the listener.
        Assert.assertEquals(1, listener.flowDeleted);
    }

    @Test
    public void testLifecycle() {
        // Given a valid listener and a metric registry.
        MidolmanConfig config = MidolmanConfig.forTests(
            "agent.insights.listener_class = \"" + Listener.class.getName() + "\"");
        MetricRegistry metrics = new MetricRegistry();

        // And a curator instance.
        CuratorFramework curator = Mockito.mock(CuratorFramework.class);

        // When creating an insights instance.
        Insights insights = new Insights(config, curator, metrics);

        // And starting insights.
        insights.startAsync().awaitRunning();

        // Then the listener is started.
        Assert.assertTrue(insights.currentListener().isRunning());

        // When stopping insights.
        insights.stopAsync().awaitTerminated();

        // Then the listener is stopped.
        Assert.assertFalse(insights.currentListener().isRunning());
    }

}
