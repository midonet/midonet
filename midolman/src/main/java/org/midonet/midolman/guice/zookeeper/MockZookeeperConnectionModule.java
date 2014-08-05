/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.zookeeper;

import javax.inject.Singleton;

import com.google.inject.name.Names;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.MockDirectory;
import org.midonet.util.eventloop.Reactor;
import org.midonet.util.eventloop.CallingThreadReactor;

public class MockZookeeperConnectionModule  extends ZookeeperConnectionModule {

    Directory directory;

    public MockZookeeperConnectionModule() {
        this(null);
    }

    public MockZookeeperConnectionModule(Directory directory) {
        this.directory = directory;
    }

    @Override
    protected void bindZookeeperConnection() {
        // no binding since we are mocking
    }

    @Override
    protected void bindDirectory() {
        if (directory == null) {
            bind(Directory.class)
                .to(MockDirectory.class)
                .in(Singleton.class);
        } else {
            bind(Directory.class)
                .toInstance(directory);
        }
    }

    @Override
    protected void bindReactor() {
        bind(Reactor.class).annotatedWith(
                Names.named(ZKConnectionProvider.DIRECTORY_REACTOR_TAG))
                .to(CallingThreadReactor.class)
                .asEagerSingleton();
    }
}
