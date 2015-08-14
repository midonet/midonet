/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.midolman.cluster;

import com.codahale.metrics.MetricRegistry;
import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.monitoring.metrics.PacketPipelineMetrics;

public class MetricsModule extends PrivateModule {
    @Override
    protected void configure() {
        bind(MetricRegistry.class).toInstance(new MetricRegistry());
        expose(MetricRegistry.class);

        expose(PacketPipelineMetrics.class);
    }

    @Provides
    @Singleton
    PacketPipelineMetrics providePacketPipelineMetrics(
        final MidolmanConfig conf, final MetricRegistry registry) {
        return new PacketPipelineMetrics(registry, conf.simulationThreads());
    }
}
