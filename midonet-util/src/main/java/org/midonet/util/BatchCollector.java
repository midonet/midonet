/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.util;

public interface BatchCollector<T> {
    void submit(T item);

    void endBatch();
}
