/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice.zookeeper;

import javax.inject.Inject;

import com.google.inject.Provider;

import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.ZkConnection;

/**
 * Provides an {@link Directory} implementation which is backed up by a
 * zookeeper connection.
 */
public class DirectoryProvider implements Provider<Directory> {

    @Inject
    ZkConnection connection;

    @Override
    public Directory get() {
        return connection.getRootDirectory();
    }
}
