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
package org.midonet.midolman.cluster.state;

import com.google.inject.*;

import org.midonet.midolman.state.FlowState;
import org.midonet.midolman.state.FlowStateStorage;
import org.midonet.midolman.state.FlowStateStorageFactory;
import org.midonet.midolman.state.MockStateStorage;
import org.midonet.odp.Flow;

import scala.concurrent.Future;
import scala.concurrent.Future$;

public class MockFlowStateStorageModule extends PrivateModule {
    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        bind(FlowStateStorageFactory.class).toProvider(FlowStateStorageFactoryProvider.class)
                .asEagerSingleton();
        expose(FlowStateStorageFactory.class);
    }

    private static class FlowStateStorageFactoryProvider implements Provider<FlowStateStorageFactory> {
        @Override
        public FlowStateStorageFactory get() {
            return new FlowStateStorageFactory() {
                @Override
                public Future<FlowStateStorage> create() {
                    return Future$.MODULE$.successful(
                        (FlowStateStorage)new MockStateStorage());
                }
            };
        }
    }
}
