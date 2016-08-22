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

import scala.collection.immutable.SortedSet

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

    val ordering = Ordering.fromLessThan[(Int, T)](
        (a, b) => a._1 < b._1 || a._1 == b._1 && a._2.hashCode < b._2.hashCode)
    var available = SortedSet.empty[(Int, T)](ordering)
    var count = Map.empty[T, Int]

    for (resource <- resources) {
        count += resource -> 0
        available += 0 -> resource
    }

    /** Returns the resource with least utilization
      */
    def get: T = {
        val head = available.head
        val updatedCount = head._1 + 1
        available = available.tail + (updatedCount -> head._2)
        count += head._2 -> updatedCount
        head._2
    }

    /**
      */
    def release(resource: T): Unit = {
        val currentCount = count(resource)
        if (currentCount > 0) {
            val updatedCount = currentCount - 1
            available = available -
                        (currentCount -> resource) +
                        (updatedCount -> resource)
            count += resource -> updatedCount
        }
    }
}
