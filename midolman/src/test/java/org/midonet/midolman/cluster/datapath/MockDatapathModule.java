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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.inject.Singleton;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.datapath.DatapathChannel;
import org.midonet.midolman.datapath.FlowProcessor;
import org.midonet.midolman.io.DatapathConnectionPool;
import org.midonet.midolman.io.MockDatapathConnectionPool;
import org.midonet.midolman.io.UpcallDatapathConnectionManager;
import org.midonet.midolman.util.mock.MockDatapathChannel;
import org.midonet.midolman.util.mock.MockFlowProcessor;
import org.midonet.midolman.util.mock.MockUpcallDatapathConnectionManager;
import org.midonet.netlink.MockNetlinkChannelFactory;
import org.midonet.netlink.NetlinkChannelFactory;
import org.midonet.odp.Flow;
import org.midonet.odp.FlowMatch;

public class MockDatapathModule extends DatapathModule {

    public final Map<FlowMatch, Flow> flowsTable = new ConcurrentHashMap<>();

    @Override
    protected void bindUpcallDatapathConnectionManager() {
        bind(UpcallDatapathConnectionManager.class)
                .toProvider(new Provider<UpcallDatapathConnectionManager>() {
                    @Inject
                    MidolmanConfig config;

                    @Override
                    public UpcallDatapathConnectionManager get() {
                        return new MockUpcallDatapathConnectionManager(config);
                    }
                })
                .in(Singleton.class);
    }

    @Override
    protected void bindDatapathConnectionPool() {
        bind(DatapathConnectionPool.class).
            toInstance(new MockDatapathConnectionPool());
    }

    @Override
    protected void bindDatapathChannel() {
        bind(DatapathChannel.class).toInstance(new MockDatapathChannel(flowsTable));
    }

    @Override
    protected void bindNetlinkConnectionFactory() {
        bind(NetlinkChannelFactory.class).toInstance(new MockNetlinkChannelFactory());
    }

    @Override
    protected void bindFlowProcessor() {
        bind(FlowProcessor.class).toInstance(new MockFlowProcessor(flowsTable));
    }
}
