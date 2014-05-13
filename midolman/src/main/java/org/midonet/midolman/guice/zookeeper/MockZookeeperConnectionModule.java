/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.zookeeper;

import com.google.inject.name.Names;
import org.midonet.midolman.state.CheckpointedDirectory;
import org.midonet.midolman.state.CheckpointedMockDirectory;
import org.midonet.midolman.state.Directory;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.CallingThreadReactor;

public class MockZookeeperConnectionModule  extends ZookeeperConnectionModule {

    @Override
    protected void bindZookeeperConnection() {
        // no binding since we are mocking
    }

    @Override
    protected void bindDirectory() {
        CheckpointedDirectory dir = new CheckpointedMockDirectory();
        bind(Directory.class).toInstance(dir);
        bind(CheckpointedDirectory.class).toInstance(dir);
        expose(CheckpointedDirectory.class);
    }

    @Override
    protected void bindReactor() {
        bind(Reactor.class).annotatedWith(
                Names.named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG))
                .to(CallingThreadReactor.class)
                .asEagerSingleton();
    }
}
