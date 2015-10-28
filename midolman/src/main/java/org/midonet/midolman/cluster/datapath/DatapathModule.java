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

import java.nio.channels.spi.SelectorProvider;
import java.util.Arrays;

import javax.inject.Singleton;
import scala.collection.JavaConversions;
import scala.collection.Seq$;

import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.PrivateModule;
import com.google.inject.Provider;

import com.lmax.disruptor.BatchEventProcessor;
import com.lmax.disruptor.EventPoller;
import com.lmax.disruptor.EventProcessor;
import com.lmax.disruptor.RingBuffer;
import com.lmax.disruptor.SequenceBarrier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.Util;
import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.datapath.DatapathChannel;
import org.midonet.midolman.datapath.DisruptorDatapathChannel;
import org.midonet.midolman.datapath.DisruptorDatapathChannel$;
import org.midonet.midolman.datapath.FlowProcessor;
import org.midonet.midolman.datapath.PacketExecutor;
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
import org.midonet.util.concurrent.AggregateEventPollerHandler;
import org.midonet.util.concurrent.BackchannelEventProcessor;
import org.midonet.util.concurrent.EventPollerHandlerAdapter;
import org.midonet.util.concurrent.NanoClock$;

import static org.midonet.midolman.datapath.DisruptorDatapathChannel.*;

public class DatapathModule extends PrivateModule {

    private static final Logger log =
        LoggerFactory.getLogger(DatapathModule.class);

    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        requireBinding(MidolmanConfig.class);

        bindNetlinkConnectionFactory();
        bindOvsNetlinkFamilies();
        bindFlowProcessor();
        bindDatapathChannel();
        bindDatapathConnectionPool();
        bindUpcallDatapathConnectionManager();

        expose(NetlinkChannelFactory.class);
        expose(OvsNetlinkFamilies.class);
        expose(FlowProcessor.class);
        expose(DatapathChannel.class);
        expose(DatapathConnectionPool.class);
        expose(UpcallDatapathConnectionManager.class);

        bind(DatapathConnectionService.class)
            .asEagerSingleton();
        expose(DatapathConnectionService.class);
    }

    private EventProcessor[] createProcessors(int threads, RingBuffer ringBuffer,
                                              SequenceBarrier barrier,
                                              FlowProcessor flowProcessor,
                                              NetlinkChannelFactory channelFactory) {
        threads = Math.max(threads, 1);
        EventProcessor[] processors = new EventProcessor[threads];
        if (threads == 1) {
            EventPoller.Handler handler = new AggregateEventPollerHandler(
                JavaConversions.asScalaBuffer(Arrays.asList(
                    flowProcessor,
                    new EventPollerHandlerAdapter(new PacketExecutor(1, 0, channelFactory)))));
            processors[0] = new BackchannelEventProcessor(
                ringBuffer, handler, flowProcessor, Seq$.MODULE$.empty());
        } else {
            int numPacketHandlers = threads - 1;
            for (int i = 0; i < numPacketHandlers; ++i) {
                PacketExecutor pexec = new PacketExecutor(numPacketHandlers, i,
                                                          channelFactory);
                processors[i] = new BatchEventProcessor(ringBuffer, barrier, pexec);
            }
            processors[numPacketHandlers] = new BackchannelEventProcessor(
                ringBuffer, flowProcessor, flowProcessor,  Seq$.MODULE$.empty());
        }
        return processors;
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
                    int capacity = Util.findNextPositivePowerOfTwo(
                        config.datapath().globalIncomingBurstCapacity() * 2);
                    RingBuffer<DatapathEvent> ringBuffer = RingBuffer.createMultiProducer(
                        DisruptorDatapathChannel$.MODULE$.eventFactory(config),
                        capacity);
                    SequenceBarrier barrier = ringBuffer.newBarrier();
                    EventProcessor processors[] = createProcessors(
                        config.outputChannels(),
                        ringBuffer, barrier,
                        injector.getInstance(FlowProcessor.class),
                        injector.getInstance(NetlinkChannelFactory.class));
                    return new DisruptorDatapathChannel(
                        injector.getInstance(OvsNetlinkFamilies.class),
                        ringBuffer, processors);
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

    protected void bindFlowProcessor() {
       bind(FlowProcessor.class)
            .toProvider(new Provider<FlowProcessor>() {
                @Inject
                MidolmanConfig config;

                @Inject
                Injector injector;

                @Override
                public FlowProcessor get() {
                    return new FlowProcessor(
                        injector.getInstance(OvsNetlinkFamilies.class),
                        config.datapath().globalIncomingBurstCapacity() * 2,
                        512, // Flow request size
                        injector.getInstance(NetlinkChannelFactory.class),
                        SelectorProvider.provider(),
                        NanoClock$.MODULE$.DEFAULT());
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
