/*
* Copyright 2013 Midokura Inc.
*/
package org.midonet.midolman.guice;

import com.google.inject.PrivateModule;
import com.google.inject.Singleton;

import org.midonet.midolman.state.ConditionSet;
import org.midonet.midolman.state.DummyConditionSet;


public class DummyConditionSetModule extends PrivateModule {

    private boolean flag;

    public DummyConditionSetModule(boolean flag_) {
        flag = flag_;
    }

    @Override
    protected void configure() {
        bind(ConditionSet.class).toInstance(new DummyConditionSet(flag));
        expose(ConditionSet.class);
    }

}
