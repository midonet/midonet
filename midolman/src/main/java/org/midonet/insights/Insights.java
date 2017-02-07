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

import java.lang.reflect.InvocationTargetException;
import java.util.Set;

import com.codahale.metrics.MetricRegistry;

import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.PacketWorkflow;
import org.midonet.midolman.flows.ManagedFlow;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.odp.FlowMetadata;

/**
 * Provides a pluggable mechanism for collecting analytics data from this
 * MidoNet Agent.
 */
@SuppressWarnings("unused")
public final class Insights {

    private static final Logger LOG = LoggerFactory.getLogger(Insights.class);

    interface Listener {
        void recordSimulation(PacketContext context,
                              PacketWorkflow.SimulationResult result);

        void recordFlow(ManagedFlow flow, FlowMetadata metadata);
    }

    private static final Listener EMPTY_LISTENER = new Listener() {
        @Override
        public void recordSimulation(PacketContext context,
                                     PacketWorkflow.SimulationResult result) { }

        @Override
        public void recordFlow(ManagedFlow flow, FlowMetadata metadata) { }
    };

    public static final Insights NONE = new Insights();

    private final Listener listener;

    private Insights() {
        listener = EMPTY_LISTENER;
    }

    public Insights(Reflections reflections, MetricRegistry metrics) {
        Set<Class<? extends Listener>> listeners =
            reflections.getSubTypesOf(Listener.class);
        if (listeners.size() == 0) {
            LOG.info("No insights listener installed");
            listener = EMPTY_LISTENER;
        } else if (listeners.size() > 1) {
            LOG.info("Multiple insights listeners installed: insights will "
                     + "be disabled");
            listener = EMPTY_LISTENER;
        } else {
            Listener l = EMPTY_LISTENER;
            try {
                Class<? extends Listener> clazz = listeners.iterator().next();
                l = clazz.getConstructor(MetricRegistry.class)
                         .newInstance(metrics);
                LOG.info("Insights listener {} enabled", clazz.getName());
            } catch (NoSuchMethodException | InstantiationException |
                     IllegalAccessException | InvocationTargetException e) {
                LOG.warn("Installing insights listener failed: insights will "
                         + "be disabled", e);
            }
            listener = l;
        }
    }

    /**
     * Records the result of a packet simulation into a flow record. Call to
     * this method is lock-free, does not allocate heap memory and
     * is thread-safe.
     *
     * @param context The packet context.
     * @param result The simulation result.
     */
    public void recordSimulation(PacketContext context,
                                 PacketWorkflow.SimulationResult result) {
        listener.recordSimulation(context, result);
    }

    /**
     * Records an installed flow when the flow has been deleted, which includes
     * any flow metadata that the flow may generate. Call to this method is
     * lock-free, does not allocate heap memory and is thread-safe.
     *
     * @param flow The managed flow.
     * @param metadata The flow metadata.
     */
    public void recordFlow(ManagedFlow flow, FlowMetadata metadata) {
        listener.recordFlow(flow, metadata);
    }

    /**
     * @return The current listener.
     */
    Listener currentListener() {
        return listener;
    }

}
