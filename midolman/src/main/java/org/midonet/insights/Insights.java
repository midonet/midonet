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

import javax.annotation.Nonnull;

import com.codahale.metrics.MetricRegistry;
import com.google.common.util.concurrent.AbstractService;
import com.google.common.util.concurrent.Service;

import org.apache.commons.lang3.StringUtils;
import org.apache.curator.framework.CuratorFramework;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.PacketWorkflow;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.simulation.PacketContext;
import org.midonet.odp.FlowMatch;
import org.midonet.odp.FlowMetadata;
import org.midonet.sdn.flows.FlowTagger;

/**
 * Provides a plug-able mechanism for collecting analytics data from this
 * MidoNet Agent.
 */
@SuppressWarnings("unused")
public final class Insights extends AbstractService {

    private static final Logger LOG = LoggerFactory.getLogger(Insights.class);

    interface Listener extends Service {

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
         * @param context The packet context.
         * @param result The simulation result.
         */
        void flowSimulation(PacketContext context,
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

    private static final class EmptyListener
        extends AbstractService
        implements Listener {
        @Override

        public void flowAdded(FlowMatch flowMatch,
                              List<FlowTagger.FlowTag> flowTags,
                              long expiration) { }

        @Override
        public void flowSimulation(PacketContext context,
                                   PacketWorkflow.SimulationResult result) { }

        @Override
        public void flowDeleted(FlowMatch flowMatch, FlowMetadata metadata) { }

        @Override
        protected void doStart() {
            notifyStarted();
        }

        @Override
        protected void doStop() {
            notifyStopped();
        }
    }

    private static final Listener EMPTY_LISTENER = new EmptyListener();

    public static final Insights NONE = new Insights();

    private final Listener listener;

    private Insights() {
        listener = EMPTY_LISTENER;
    }

    public Insights(MidolmanConfig config,
                    CuratorFramework curator,
                    MetricRegistry metrics) {
        if (!config.insights().enabled() ||
            StringUtils.isBlank(config.insights().listenerClass())) {
            LOG.info("Insights listener not enabled");
            listener = EMPTY_LISTENER;
        } else {
            Listener l = EMPTY_LISTENER;
            try {
                Class<? extends Listener> clazz = Class.forName(
                    config.insights().listenerClass().replaceAll("\\.\\.", "."))
                    .asSubclass(Listener.class);
                l = clazz.getConstructor(MidolmanConfig.class,
                                         CuratorFramework.class,
                                         MetricRegistry.class)
                    .newInstance(config, curator, metrics);
                LOG.info("Insights listener {} enabled", clazz.getName());
            } catch (ClassNotFoundException e) {
                LOG.warn(
                    "Installing Insights listener failed: class not found: {}",
                    config.insights().listenerClass());
            } catch (NoSuchMethodException | InstantiationException |
                     IllegalAccessException | InvocationTargetException e) {
                LOG.warn(
                    "Installing Insights listener failed: cannot instantiate: {}",
                    config.insights().listenerClass(), e);
            } catch (ClassCastException e) {
                LOG.warn(
                    "Installing Insights listener failed: invalid class: {}",
                    config.insights().listenerClass(), e);
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
     * @param context The packet context.
     * @param result The simulation result.
     */
    public void flowSimulation(@Nonnull PacketContext context,
                               @Nonnull PacketWorkflow.SimulationResult result) {
        listener.flowSimulation(context, result);
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

    @Override
    protected void doStart() {
        listener.startAsync().awaitRunning();
        notifyStarted();
    }

    @Override
    protected void doStop() {
        listener.stopAsync().awaitTerminated();
        notifyStopped();
    }

}
