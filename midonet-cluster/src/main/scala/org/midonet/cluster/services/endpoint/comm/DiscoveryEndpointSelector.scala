/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster.services.endpoint.comm

import java.util.concurrent.atomic.AtomicReference

import com.google.common.net.HostAndPort

import org.slf4j.LoggerFactory

import rx.subjects.BehaviorSubject
import rx.{Observable, Observer}

import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetServiceHostAndPort}
import org.midonet.cluster.services.endpoint.EndpointSelector
import org.midonet.util.random._

/**
  * A trait for objects that need access to an always up-to-date list of
  * discoverable service endpoints.
 *
  * @param name of the service whose instances we want to use as endpoints.
  * @param discovery is the instance of the midonet discovery service.
  */
class DiscoveryEndpointSelector(name: String, discovery: MidonetDiscovery,
                                addressFilter: AddressFilter = NoFilter)
    extends EndpointSelector {

    private val log = LoggerFactory.getLogger(classOf[DiscoveryEndpointSelector])

    /**
      * Set of currently active endpoints.
      */
    private val endpoints = new AtomicReference(Set.empty[HostAndPort])

    private val subject = BehaviorSubject.create(endpoints.get)

    private val client = discovery.getClient[MidonetServiceHostAndPort](name)

    client.observable.subscribe(
        new Observer[Seq[MidonetServiceHostAndPort]] {
            override def onCompleted(): Unit = {
                log.debug("Service discovery completed for {}", name)
                subject.onCompleted()
                endpoints.lazySet(Set.empty)
            }

            override def onError(e: Throwable): Unit = {
                log.error("Service discovery failed for " + name, e)
                subject.onError(e)
                endpoints.lazySet(Set.empty)
            }

            override def onNext(t: Seq[MidonetServiceHostAndPort]): Unit = {
                endpoints.lazySet(addressFilter.filter(
                    t.map {
                        mhp => HostAndPort.fromParts(mhp.address, mhp.port)
                    }.toSet))
                subject.onNext(endpoints.get())
                log.debug("New endpoints for {}: " + endpoints, name)
            }
        }
    )

    /**
      * Gets a random valid endpoint.
      * @return An option representing a random valid endpoint or None if no
      *         valid endpoints exist.
      */
    override def getEndpoint: Option[HostAndPort] = rndElement(endpoints.get())

    /**
      * Gets the set of valid endpoints
      * @return An observable returning valid endpoint (or an empty set)
      *         A new set is emitted every time
      */
    override def observable: Observable[Set[HostAndPort]] =
        subject.asObservable().distinctUntilChanged()

    /**
      * Stops the endpoint discovery mechanism.
      */
    override def close(): Unit = client.stop()
}

object DiscoveryEndpointSelector {
    def apply(serviceName: String, discovery: MidonetDiscovery,
              addressFilter: AddressFilter = NoFilter)
        : DiscoveryEndpointSelector =
        new DiscoveryEndpointSelector(serviceName, discovery, addressFilter)

    def apply(serviceName: String, discovery: MidonetDiscovery,
              addressFilter: Option[AddressFilter])
        : DiscoveryEndpointSelector =
        addressFilter match {
            case Some(filter) =>
                apply(serviceName, discovery, filter)
            case None =>
                apply(serviceName, discovery)
        }

}
