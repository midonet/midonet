/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.midonet.cluster.client;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public interface Builder<ConcreteBuilder> {
    // TODO(rossella, pino): implement deleted() which informs the builder that
    // TODO:                 the entity has been completely removed from storage
    // void deleted();
    void build();
}
