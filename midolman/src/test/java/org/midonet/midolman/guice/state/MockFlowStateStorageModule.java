/*
* Copyright 2014 Midokura Europe SARL
*/
package org.midonet.midolman.guice.state;

import com.google.inject.*;

import org.midonet.midolman.state.FlowStateStorage;
import org.midonet.midolman.state.FlowStateStorageFactory;
import org.midonet.midolman.state.MockStateStorage;

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
                public FlowStateStorage create() {
                    return new MockStateStorage();
                }
            };
        }
    }
}
