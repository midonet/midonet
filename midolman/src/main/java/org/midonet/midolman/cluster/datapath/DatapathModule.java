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
package org.midonet.midolman.cluster.datapath;

import javax.inject.Singleton;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.datapath.DatapathChannel;
import org.midonet.midolman.datapath.DisruptorDatapathChannel;
import org.midonet.midolman.flows.FlowEjector;
import org.midonet.midolman.io.DatapathConnectionPool;
import org.midonet.midolman.io.OneToOneConnectionPool;
import org.midonet.midolman.io.OneToOneDpConnManager;
import org.midonet.midolman.io.OneToManyDpConnManager;
import org.midonet.midolman.io.UpcallDatapathConnectionManager;
import org.midonet.midolman.io.TokenBucketPolicy;
import org.midonet.midolman.services.DatapathConnectionService;
import org.midonet.netlink.NetlinkChannel;
import org.midonet.netlink.NetlinkChannelFactory;
import org.midonet.odp.OvsNetlinkFamilies;
import org.midonet.util.concurrent.NanoClock$;

public class DatapathModule extends PrivateModule {

    private static final Logger log =
        LoggerFactory.getLogger(DatapathModule.class);

    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        requireBinding(MidolmanConfig.class);

        bindNetlinkConnectionFactory();
        bindFlowEjector();
        bindOvsNetlinkFamilies();
        bindDatapathChannel();
        bindDatapathConnectionPool();
        bindUpcallDatapathConnectionManager();

        expose(NetlinkChannelFactory.class);
        expose(FlowEjector.class);
        expose(OvsNetlinkFamilies.class);
        expose(DatapathChannel.class);
        expose(DatapathConnectionPool.class);
        expose(UpcallDatapathConnectionManager.class);

        bind(DatapathConnectionService.class)
            .asEagerSingleton();
        expose(DatapathConnectionService.class);
    }

    protected void bindDatapathChannel() {
        bind(DatapathChannel.class)
            .toProvider(new Provider<DatapathChannel>() {
                @Inject
                MidolmanConfig config;

                @Inject
                Injector injector;

                @Override
                public DatapathChannel get() {
                    return new DisruptorDatapathChannel(
                        config.datapath().globalIncomingBurstCapacity() * 2,
                        config.outputChannels(),
                        injector.getInstance(FlowEjector.class),
                        injector.getInstance(NetlinkChannelFactory.class),
                        injector.getInstance(OvsNetlinkFamilies.class),
                        NanoClock$.MODULE$.DEFAULT()
                    );
                }
            })
            .in(Singleton.class);
    }

    protected void bindOvsNetlinkFamilies() {
        bind(OvsNetlinkFamilies.class)
            .toProvider(new Provider<OvsNetlinkFamilies>() {
                @Inject
                NetlinkChannelFactory factory;

                @Override
                public OvsNetlinkFamilies get() {
                    NetlinkChannel channel = factory.create(true);
                    try {
                        try {
                            OvsNetlinkFamilies families = OvsNetlinkFamilies.discover(channel);
                            log.debug(families.toString());
                            return families;
                        } finally {
                            channel.close();
                        }
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                }
            })
            .in(Singleton.class);
    }

    protected void bindFlowEjector() {
       bind(FlowEjector.class)
            .toProvider(new Provider<FlowEjector>() {
                @Inject
                MidolmanConfig config;

                @Override
                public FlowEjector get() {
                    return new FlowEjector(
                            config.datapath().globalIncomingBurstCapacity() * 2);
                }
            })
            .in(Singleton.class);
    }

    protected void bindNetlinkConnectionFactory() {
        bind(NetlinkChannelFactory.class)
            .in(Singleton.class);
    }

    protected void bindDatapathConnectionPool() {
        bind(DatapathConnectionPool.class)
            .toProvider(DatapathConnectionPoolProvider.class)
            .in(Singleton.class);
    }

    protected void bindUpcallDatapathConnectionManager() {
        bind(UpcallDatapathConnectionManager.class)
            .toProvider(UpcallDatapathConnectionManagerProvider.class)
            .in(Singleton.class);
    }

    public static class UpcallDatapathConnectionManagerProvider
            implements Provider<UpcallDatapathConnectionManager> {

        @Inject
        MidolmanConfig config;

        @Inject
        TokenBucketPolicy tbPolicy;

        @Override
        public UpcallDatapathConnectionManager get() {
            String val = config.inputChannelThreading();
            switch (val) {
                case "one_to_many":
                    return new OneToManyDpConnManager(config, tbPolicy);
                case "one_to_one":
                    return new OneToOneDpConnManager(config, tbPolicy);
                default:
                    throw new IllegalArgumentException(
                        "Unknown value for input_channel_threading: " + val);
            }
        }
    }

    public static class DatapathConnectionPoolProvider
            implements Provider<DatapathConnectionPool> {

        @Inject
        MidolmanConfig config;

        @Override
        public DatapathConnectionPool get() {
            return new OneToOneConnectionPool("netlink.requests",
                                              config.outputChannels(),
                                              config);
        }
    }
}
