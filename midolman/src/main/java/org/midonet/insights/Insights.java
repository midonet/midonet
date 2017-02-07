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
import java.util.List;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;

import com.codahale.metrics.MetricRegistry;

import org.reflections.Reflections;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.PacketWorkflow;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMetadata;
import org.midonet.odp.Packet;
import org.midonet.sdn.flows.FlowTagger;

/**
 * Provides a pluggable mechanism for collecting analytics data from this
 * MidoNet Agent.
 */
@SuppressWarnings("unused")
public final class Insights {

    private static final Logger LOG = LoggerFactory.getLogger(Insights.class);

    interface Listener {

        /**
         * Records adding a new flow. Implementations this method must be
         * lock-free, do not allocate heap memory and be thread-safe.
         *
         * @param flowMatch The flow match.
         * @param flowTags The flow tags.
         * @param expiration The flow expiration.
         */
        void flowAdded(FlowMatch flowMatch,
                       List<FlowTagger.FlowTag> flowTags,
                       long expiration);

        /**
         * Records the result of a packet simulation into a flow record.
         * Implementations of this method must be lock-free, do not allocate
         * heap memory and be thread-safe.
         *
         * @param cookie The simulation cookie.
         * @param packet The simulated packet.
         * @param inputPort The input port.
         * @param flowMatch The flow match.
         * @param flowTags The flow tags.
         * @param result The simulation result.
         */
        void flowSimulation(long cookie,
                            Packet packet,
                            UUID inputPort,
                            FlowMatch flowMatch,
                            List<FlowTagger.FlowTag> flowTags,
                            PacketWorkflow.SimulationResult result);

        /**
         * Records an installed flow when the flow has been deleted, which
         * includes any flow metadata that the flow may generate.
         * Implementations of this method must be lock-free, do not allocate
         * heap memory and be thread-safe.
         *
         * @param flowMatch The flow match.
         * @param metadata The flow metadata.
         */
        void flowDeleted(FlowMatch flowMatch, FlowMetadata metadata);
    }

    private static final Listener EMPTY_LISTENER = new Listener() {
        @Override
        public void flowAdded(FlowMatch flowMatch,
                              List<FlowTagger.FlowTag> flowTags,
                              long expiration) { }

        @Override
        public void flowSimulation(long cookie,
                                   Packet packet,
                                   UUID inputPort,
                                   FlowMatch flowMatch,
                                   List<FlowTagger.FlowTag> flowTags,
                                   PacketWorkflow.SimulationResult result) { }

        @Override
        public void flowDeleted(FlowMatch flowMatch, FlowMetadata metadata) { }
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
            LOG.warn("Multiple insights listeners installed: insights will "
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
     * Records adding a new flow. Call to this method is lock-free, does not
     * allocate heap memory and is thread-safe.
     *
     * @param flowMatch The flow match.
     * @param flowTags The flow tags.
     * @param expiration The flow expiration.
     */
    public void flowAdded(@Nonnull FlowMatch flowMatch,
                          @Nonnull List<FlowTagger.FlowTag> flowTags,
                          long expiration) {
        listener.flowAdded(flowMatch, flowTags, expiration);
    }

    /**
     * Records the result of a packet simulation into a flow record. Call to
     * this method is lock-free, does not allocate heap memory and
     * is thread-safe.
     *
     * @param cookie The simulation cookie.
     * @param packet The simulated packet.
     * @param inputPort The input port.
     * @param flowMatch The flow match.
     * @param flowTags The flow tags.
     * @param result The simulation result.
     */
    public void flowSimulation(long cookie,
                               @Nonnull Packet packet,
                               @Nonnull UUID inputPort,
                               @Nonnull FlowMatch flowMatch,
                               @Nonnull List<FlowTagger.FlowTag> flowTags,
                               @Nonnull PacketWorkflow.SimulationResult result) {
        listener.flowSimulation(cookie, packet, inputPort, flowMatch, flowTags,
                                result);
    }

    /**
     * Records an installed flow when the flow has been deleted, which includes
     * any flow metadata that the flow may generate. Call to this method is
     * lock-free, does not allocate heap memory and is thread-safe.
     *
     * @param flowMatch The flow match.
     * @param metadata The flow metadata.
     */
    public void flowDeleted(@Nonnull FlowMatch flowMatch,
                            @Nonnull FlowMetadata metadata) {
        listener.flowDeleted(flowMatch, metadata);
    }

    /**
     * @return The current listener.
     */
    Listener currentListener() {
        return listener;
    }

}
