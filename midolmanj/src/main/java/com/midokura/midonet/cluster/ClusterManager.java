/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midonet.cluster;

import java.util.UUID;

import com.midokura.util.collections.TypedHashMap;
import com.midokura.util.collections.TypedMap;

abstract class ClusterManager<T> {

    private TypedMap<UUID, T> builderMap = new TypedHashMap<UUID, T>();

    public void registerNewBuilder(UUID id, T builder)
        throws ClusterClientException {
        if (builderMap.containsKey(id)){
            throw new ClusterClientException("Builder for device "
                                                 + id.toString() + " already registered");
        }
        builderMap.put(id, builder);
    }
    
    protected T getBuilder(UUID id){
        return builderMap.get(id);
    }
    
    abstract public Runnable getConfig(UUID id);
}
