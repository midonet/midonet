/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.guice;

import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.io.TokenBucketPolicy;
import org.midonet.util.StatisticalCounter;
import org.midonet.util.TokenBucketSystemRate;

public class ResourceProtectionModule extends PrivateModule {

    @Override
    protected void configure() {
        binder().requireExplicitBindings();
        requireBinding(MidolmanConfig.class);
        expose(StatisticalCounter.class);
        expose(TokenBucketPolicy.class);
    }

    @Provides
    @Singleton
    StatisticalCounter provideStatisticalCounter(MidolmanConfig conf) {
        return new StatisticalCounter(conf.getSimulationThreads());
    }

    @Provides
    @Singleton
    TokenBucketPolicy provideTokenBucketPolicy(MidolmanConfig conf,
                                               StatisticalCounter counter) {
        return new TokenBucketPolicy(conf, new TokenBucketSystemRate(counter));
    }
}
