/*
* Copyright 2013 Midokura Europe SARL
*/
package org.midonet.util.collections;

import java.lang.ref.WeakReference;

import org.jboss.netty.util.internal.ConcurrentWeakKeyHashMap;

/** A pool of shared objects that will be garbage collected when not reference
  * from outside of the pool.
  *
  * Mean to be used as a cache for objects of which a lot of identical instances
  * are expected to be created and maintained in memory.
  */
public class WeakObjectPool<T> {

    private ConcurrentWeakKeyHashMap<T, WeakReference<T>> pool = new ConcurrentWeakKeyHashMap<>();

    public T sharedRef(T instance) {
        WeakReference<T> ref = pool.get(instance);
        if (ref != null) {
            T obj = ref.get();
            if (obj != null)
                return ref.get();
        }
        pool.put(instance, new WeakReference<T>(instance));
        return instance;
    }
}
