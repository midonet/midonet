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
package org.midonet.midolman.guice.datapath;

import javax.inject.Singleton;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.midonet.midolman.io.DatapathConnectionPool;
import org.midonet.midolman.io.MockDatapathConnectionPool;
import org.midonet.midolman.io.UpcallDatapathConnectionManager;
import org.midonet.midolman.util.mock.MockUpcallDatapathConnectionManager;


public class MockDatapathModule extends DatapathModule {
    @Override
    protected void bindUpcallDatapathConnectionManager() {
        bind(UpcallDatapathConnectionManager.class)
                .toProvider(MockUpcallDatapathConnectionManagerProvider.class)
                .in(Singleton.class);
    }

    @Override
    protected void bindDatapathConnectionPool() {
        bind(DatapathConnectionPool.class).
            toInstance(new MockDatapathConnectionPool());
    }

    public static class MockUpcallDatapathConnectionManagerProvider
            implements Provider<UpcallDatapathConnectionManager> {

        @Inject
        org.midonet.midolman.config.MidolmanConfig config;

        @Override
        public UpcallDatapathConnectionManager get() {
            return new MockUpcallDatapathConnectionManager(config);
        }
    }
}
