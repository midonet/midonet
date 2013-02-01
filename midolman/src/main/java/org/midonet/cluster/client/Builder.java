/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.cluster.client;

/**
 * // TODO: mtoader ! Please explain yourself.
 */
public interface Builder<ConcreteBuilder> {
    // TODO(rossella, pino): implement deleted() which informs the builder that
    // TODO:                 the entity has been completely removed from storage
    // TODO: void deleted();
    void build();
}
