/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman.host.scanner;

import java.util.Set;

import org.midonet.Subscription;
import org.midonet.midolman.host.interfaces.InterfaceDescription;
import org.midonet.netlink.Callback;

/**
 * Interface data scanning API. It's job is scan and find out the
 * current list of interface data from the local system and notify
 * observers whenever there are changes.
 */
public interface InterfaceScanner {
    Subscription register(final Callback<Set<InterfaceDescription>> callback);
    void start();
    void shutdown();
}
