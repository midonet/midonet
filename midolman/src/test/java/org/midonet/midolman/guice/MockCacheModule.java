/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.midolman.guice;

import com.google.inject.Singleton;

import org.midonet.cache.Cache;
import org.midonet.midolman.util.MockCache;


public class MockCacheModule extends CacheModule {

    @Override
    protected void bindCache() {
        // no binding since we are mocking
        bind(Cache.class).to(MockCache.class).in(Singleton.class);
        expose(Cache.class);
    }
}
