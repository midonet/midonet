/*
 * Copyright (c) 2012 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.guice;

import com.google.inject.Singleton;

import org.midonet.cache.Cache;
import org.midonet.cache.MockCache;
import org.midonet.util.eventloop.CallingThreadReactor;
import org.midonet.util.eventloop.Reactor;

public class MockCacheModule extends CacheModule {

    @Override
    protected void bindCache() {
        // no binding since we are mocking
        bind(Cache.class)
            .annotatedWith(NAT_CACHE.class)
            .to(MockCache.class)
            .in(Singleton.class);
        bind(Cache.class)
            .annotatedWith(TRACE_MESSAGES.class)
            .to(MockCache.class)
            .in(Singleton.class);
        bind(Cache.class)
            .annotatedWith(TRACE_INDEX.class)
            .to(MockCache.class)
            .in(Singleton.class);
    }

    @Override
    protected void bindReactor() {
        bind(Reactor.class).
                annotatedWith(CACHE_REACTOR.class).
                toInstance(new CallingThreadReactor());
    }
}
