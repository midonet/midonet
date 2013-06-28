// Copyright 2013 Midokura Inc.

package org.midonet.midolman.guice.state;

import com.google.inject.Inject;
import com.google.inject.Provider;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.midonet.midolman.config.ZookeeperConfig;
import org.midonet.midolman.rules.ChainPacketContext;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.state.ConditionSet;
import org.midonet.midolman.state.ConditionSetImpl;
import org.midonet.midolman.state.DummyConditionSet;
import org.midonet.midolman.state.Directory;
import org.midonet.midolman.state.PathBuilder;
import org.midonet.sdn.flows.WildcardMatch;


public class ConditionSetProvider implements Provider<ConditionSet> {
    @Inject Directory directory;
    @Inject ZookeeperConfig config;
    @Inject Serializer serializer;
    @Inject PathBuilder pathBuilder;

    static final Logger log =
            LoggerFactory.getLogger(ConditionSetProvider.class);

    public ConditionSet get() {
        try {
            return new ConditionSetImpl(
                    directory.getSubDirectory(
                        pathBuilder.getTracedConditionsPath()),
                    CreateMode.PERSISTENT, serializer);
        } catch (KeeperException e) {
            log.error("Unable to make ConditionSet; packet traces disabled: ",
                      e);
            return new DummyConditionSet(false);
        }
    }

}
