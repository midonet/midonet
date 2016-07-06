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

package org.midonet.cluster.services.discovery

/**
  * A trait that encapsulates selection of a service instance from the list
  * of instances returned by a [[MidonetDiscoveryClient]]
  *
  * @tparam T the type of instance returned by this [[MidonetDiscoveryClient]]
  *           ( see[[MidonetServiceInstance]] )
  */
trait MidonetDiscoverySelector[T] {

    def getInstance: Option[T]
}

/**
  * factory methods for different selection behaviors
  */
object MidonetDiscoverySelector {

    /**
      * a [[MidonetDiscoverySelector]] that returns instances in a
      * round-robin fashion.
      */
    def roundRobin[T]: MidonetDiscoveryClient[T] => MidonetDiscoverySelector[T]
    = new SelectorImpl(new RoundRobinPolicy[T])(_)

    /**
      * a [[MidonetDiscoverySelector]] that always returns the first instance
      * returned by the [[MidonetDiscoveryClient]]
      */
    def first[T]: MidonetDiscoveryClient[T] => MidonetDiscoverySelector[T]
    = new SelectorImpl(new FirstPolicy[T])(_)
}

private sealed trait MidonetDiscoveryPolicy[T] {

    def select(instances: Seq[T]): T
}

private final class SelectorImpl[T](policy: MidonetDiscoveryPolicy[T])
                                   (client: MidonetDiscoveryClient[T])
    extends MidonetDiscoverySelector[T] {

    def getInstance: Option[T] = {
        val instances = client.instances
        if (instances.nonEmpty) {
            Some(policy.select(instances))
        } else {
            None
        }
    }
}

private final class RoundRobinPolicy[T] extends MidonetDiscoveryPolicy[T] {

    private var counter = 0

    override def select(instances: Seq[T]): T = {
        val idx = counter % instances.size
        counter += 1
        instances(idx)
    }
}

private final class FirstPolicy[T] extends MidonetDiscoveryPolicy[T] {

    override def select(instances: Seq[T]): T = instances.head
}
