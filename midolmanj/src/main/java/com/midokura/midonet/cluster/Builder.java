/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public interface Builder<ConcreteBuilder extends Builder<ConcreteBuilder>> {
    ConcreteBuilder start();
    void build();
}
