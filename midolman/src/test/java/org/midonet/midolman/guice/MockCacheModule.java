/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice;

import com.google.inject.Key;
import com.google.inject.Singleton;

import org.midonet.cache.Cache;
import org.midonet.midolman.util.MockCache;


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
        expose(Key.get(Cache.class, NAT_CACHE.class));
        expose(Key.get(Cache.class, TRACE_MESSAGES.class));
        expose(Key.get(Cache.class, TRACE_INDEX.class));
    }
}
