/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midolman.guice;

import javax.inject.Singleton;

import com.midokura.cache.Cache;
import com.midokura.midolman.util.MockCache;

public class MockCacheModule extends CacheModule {

    public MockCacheModule() {

    }

    @Override
    protected void bindCache() {
        // no binding since we are mocking
        bind(Cache.class).to(MockCache.class).in(Singleton.class);
        expose(Cache.class);
    }
}
