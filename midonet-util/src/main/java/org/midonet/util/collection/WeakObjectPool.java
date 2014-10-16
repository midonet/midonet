/*
 * Copyright 2014 Midokura SARL
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.midonet.util.collection;

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
