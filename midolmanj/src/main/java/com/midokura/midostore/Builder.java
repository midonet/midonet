/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midostore;

/**
* // TODO: mtoader ! Please explain yourself.
*/
public interface Builder<ConcreteBuilder extends Builder<ConcreteBuilder>> {
    ConcreteBuilder start();
    void build();
}
