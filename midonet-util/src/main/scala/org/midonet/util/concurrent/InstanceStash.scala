/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.util.concurrent

import java.util.ArrayList

/**
* An object instance pool with special semantics:
*
*   - It's thread local.
*   - Returned objects have a limited lease period that ends whenever the
*     thread starts processing the next request (whatever 'request' means).
*
* In other words, object leases are request-scoped. It's illegal to leak them,
* store them or use them outside of the ongoing request at least-time. It also
* illegal to share them with other threads.
*
* Borrowers need not return objects to the pool, as the pool will collect them
* automatically.
*
* Threads using pools of this type need to invoke reUp() in between requests,
* to let the pool know when to collect leased objects.
*/
sealed abstract class InstanceStash[T](factory: () => T) {
    private val poolStorage = new ThreadLocal[ArrayList[T]] {
        override def initialValue = new ArrayList[T]()
    }

    private val nextStorage = new ThreadLocal[Int] {
        override def initialValue = 0
    }

    final def size = poolStorage.get.size - leasedInstances

    final def leasedInstances = nextStorage.get

    final def get(): T = {
        val next = nextStorage.get()
        val pool = poolStorage.get()
        if (pool.size() <= next)
            pool.add(next, factory())
        nextStorage.set(next+1)
        pool.get(next)
    }

    final def reUp(): Unit = {
        nextStorage.set(0)
    }
}

class InstanceStash0[T](factory: () => T) extends InstanceStash[T](factory) {

    def apply(): T = get()
}

class InstanceStash1[T, A](factory: () => T, fill: (T, A) => Unit) extends
       InstanceStash[T](factory) {

   def apply(arg: A): T = {
       val obj = get()
       fill(obj, arg)
       obj
   }
}

class InstanceStash2[T, A, B](factory: () => T, fill: (T, A, B) => Unit) extends
       InstanceStash[T](factory) {

   def apply(arg1: A, arg2: B): T = {
       val obj = get()
       fill(obj, arg1, arg2)
       obj
   }
}
