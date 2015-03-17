/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.midonet.midolman.cluster;

import scala.runtime.AbstractFunction1;

import com.google.inject.PrivateModule;
import com.google.inject.Provides;
import com.google.inject.Singleton;

import org.midonet.midolman.config.MidolmanConfig;
import org.midonet.midolman.io.TokenBucketPolicy;
import org.midonet.util.Bucket;
import org.midonet.util.StatisticalCounter;
import org.midonet.util.TokenBucket;
import org.midonet.util.TokenBucketSystemRate;

public class ResourceProtectionModule extends PrivateModule {

    public static final int MULTIPLIER = 8;

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
        // We add an extra slot so that channels can return tokens
        // they obtained due to the multiplier effect but didn't use.
        return new StatisticalCounter(conf.simulationThreads() + 1);
    }

    @Provides
    @Singleton
    TokenBucketPolicy provideTokenBucketPolicy(final MidolmanConfig conf,
                                               final StatisticalCounter counter) {
        // Here we check whether increments to our slot in the StatisticalCounter
        // should be atomic or not, depending on whether multiple threads will
        // be accessing it (true in the one_to_one" configuration setting).
        final boolean atomic;
        String val = conf.inputChannelThreading();
        switch (val) {
            case "one_to_many":
                atomic = false;
                break;
            case "one_to_one":
                atomic = true;
                break;
            default:
                throw new IllegalArgumentException(
                        "Unknown value for input_channel_threading: " + val);
        }

        return new TokenBucketPolicy(
                conf,
                new TokenBucketSystemRate(counter, MULTIPLIER),
                MULTIPLIER,
                new AbstractFunction1<TokenBucket, Bucket>() {
                    @Override
                    public Bucket apply(TokenBucket tb) {
                        return new Bucket(tb, MULTIPLIER, counter,
                                          conf.simulationThreads(), atomic);
                    }
                });
    }
}
