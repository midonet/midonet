/*
 * Copyright 2016 Midokura SARL
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
package org.midonet.util.collection

/**
  * This class allows non-exclusive distribution of a fixed set of resources
  * between a variable number of consumers, granting that it will always
  * return the resource that's being used by the least number of consumers.
  *
  * Useful for example to balance a pool of executors
  *
  * Warning: not concurrent
  */
class SharedResourceBalancer[T](resources: Seq[_ <: T]) {

    require(resources.nonEmpty)

    private val usage = resources.map( _ => 0 ).toArray

    /** Returns the resource with least utilization
      */
    def get: T = {
        val idx = usage.zipWithIndex.min._2
        usage(idx) += 1
        resources(idx)
    }

    /** Release a resource when it's not used anymore
      */
    def release(resource: T): Unit = {
        val idx = resources.indexOf(resource)
        if (idx>=0 && usage(idx)>0) {
            usage(idx) -= 1
        }
    }
}
