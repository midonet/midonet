/*
 * Copyright 2012 Midokura Europe SARL
 */

package com.midokura.sdn.flows;

import java.util.ArrayList;
import java.util.List;

import org.apache.commons.lang.builder.HashCodeBuilder;

import com.midokura.sdn.dp.flows.FlowAction;

public class WildcardFlow {
    short priority = 0; // used to choose among many matching flows
    WildcardMatch match;
    List<FlowAction<?>> actions;

    long creationTimeMillis;
    long lastUsedTimeMillis;

    long hardExpirationMillis = 0; // default: never expire
    long idleExpirationMillis = 0; // default: never expire

    public WildcardFlow() {
        this.match = new WildcardMatch();
        this.actions = new ArrayList<FlowAction<?>>();
    }

    public short getPriority() {
        return priority;
    }

    public WildcardFlow setPriority(short priority) {
        this.priority = priority;
        return this;
    }

    public WildcardMatch getMatch() {
        return match;
    }

    public WildcardFlow setMatch(WildcardMatch match) {
        this.match = match;
        return this;

    }

    public boolean equals(Object o){
        if(o == this)
            return true;
        if(o == null || o.getClass() != this.getClass())
            return false;
        WildcardFlow that = (WildcardFlow)o;
        if (that.getHardExpirationMillis() != this.getCreationTimeMillis() ||
            that.getIdleExpirationMillis() != this.getIdleExpirationMillis() ||
            that.getPriority() != this.getPriority())
            return false;
        if(actions != null ? !actions.equals(that.actions) : that.actions != null){
            return false;
        }
        if(match != null ? !match.equals(that.match) : that.match != null){
            return false;
        }
        return true;
    }

    public int hashCode(){
        return new HashCodeBuilder(17, 37).
            append(hardExpirationMillis).
            append(idleExpirationMillis).
            append(priority).
            append(actions).
            append(match).
            toHashCode();
    }

    public List<FlowAction<?>> getActions() {
        return actions;
    }

    public WildcardFlow setActions(List<FlowAction<?>> actions) {
        this.actions = actions;
        return this;
    }

    public WildcardFlow addAction(FlowAction<?> action) {
        if (actions == null)
            actions = new ArrayList<FlowAction<?>>();
        actions.add(action);
        return this;
    }

    public long getLastUsedTimeMillis() {
        return lastUsedTimeMillis;
    }

    public WildcardFlow setLastUsedTimeMillis(long lastUsedTimeMillis) {
        this.lastUsedTimeMillis = lastUsedTimeMillis;
        return this;
    }

    public long getHardExpirationMillis() {
        return hardExpirationMillis;
    }

    public WildcardFlow setHardExpirationMillis(long hardExpirationMillis) {
        this.hardExpirationMillis = hardExpirationMillis;
        return this;
    }

    public long getIdleExpirationMillis() {
        return idleExpirationMillis;
    }

    public WildcardFlow setIdleExpirationMillis(long idleExpirationMillis) {
        this.idleExpirationMillis = idleExpirationMillis;
        return this;
    }

    public long getCreationTimeMillis() {
        return creationTimeMillis;
    }

    public WildcardFlow setCreationTimeMillis(long creationTimeMillis) {
        this.creationTimeMillis = creationTimeMillis;
        return this;
    }

    @Override
    public String toString() {
        return "WildcardFlow{" +
            "actions=" + actions +
            ", priority=" + priority +
            ", match=" + match +
            ", creationTimeMillis=" + creationTimeMillis +
            ", lastUsedTimeMillis=" + lastUsedTimeMillis +
            ", hardExpirationMillis=" + hardExpirationMillis +
            ", idleExpirationMillis=" + idleExpirationMillis +
            '}';
    }
}
