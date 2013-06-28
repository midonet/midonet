// Copyright 2013 Midokura Inc.

package org.midonet.midolman.state;

import java.util.Collection;
import java.util.HashSet;

import org.apache.zookeeper.CreateMode;

import org.midonet.midolman.rules.ChainPacketContext;
import org.midonet.midolman.rules.Condition;
import org.midonet.midolman.serialization.Serializer;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.sdn.flows.WildcardMatch;


public class ConditionSetImpl extends ReplicatedSet<Condition>
                              implements ConditionSet {
    public ConditionSetImpl(Directory d, CreateMode createMode,
                            Serializer serializer_) {
        super(d, createMode);
        serializer = serializer_;
        addWatcher(dirWatcher);
    }

    private class DirectoryWatcher implements Watcher<Condition> {
        public void process(Collection<Condition> added,
                            Collection<Condition> removed) {
            HashSet<Condition> newSet = new HashSet<Condition>(localSet);
            for (Condition item : removed) {
                newSet.remove(item);
            }
            for (Condition item : added) {
                newSet.add(item);
            }
            localSet = newSet;
        }
    }

    protected HashSet<Condition> localSet = new HashSet<Condition>();
    protected Serializer serializer;
    private DirectoryWatcher dirWatcher = new DirectoryWatcher();

    protected String encode(Condition item) throws SerializationException{
        return new String(serializer.serialize(item));
    }

    protected Condition decode(String str) throws SerializationException {
        return serializer.deserialize(str.getBytes(), Condition.class);
    }

    @Override
    public boolean matches(ChainPacketContext fwdInfo, WildcardMatch pktMatch,
                           boolean isPortFilter) {
        for (Condition condition : localSet) {
            if (condition.matches(fwdInfo, pktMatch, isPortFilter))
                return true;
        }
        return false;
    }
}
