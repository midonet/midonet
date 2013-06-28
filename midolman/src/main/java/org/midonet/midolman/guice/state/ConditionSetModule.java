/*
* Copyright 2013 Midokura Inc.
*/
package org.midonet.midolman.guice.state;

import com.google.inject.PrivateModule;
import com.google.inject.Singleton;

import org.midonet.midolman.state.ConditionSet;


public class ConditionSetModule extends PrivateModule {

    @Override
    protected void configure() {
        bind(ConditionSet.class).toProvider(ConditionSetProvider.class)
                                .in(Singleton.class);
        expose(ConditionSet.class);
    }

}
