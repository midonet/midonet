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

package org.midonet.cluster.services.endpoint

import java.io.Closeable

import com.google.common.net.HostAndPort

import rx.Observable

import org.midonet.cluster.services.discovery.MidonetDiscovery
import org.midonet.cluster.services.endpoint.comm.{CustomEndpointSelector, DiscoveryEndpointSelector}

/**
  * A trait for objects that need access to an always up-to-date list of
  * discoverable service endpoints.
  */
trait EndpointSelector extends Closeable {
    /**
      * Gets a random valid endpoint.
      * @return An option representing a random valid endpoint or None if no
      *         valid endpoints exist.
      */
    def getEndpoint: Option[HostAndPort]

    /**
      * Gets the set of valid endpoints
      * @return An observable returning valid endpoint (or an empty set)
      *         A new set is emitted every time
      */
    def observable: Observable[Set[HostAndPort]]
}

object EndpointSelector {
    def apply(serviceName: Option[String],
              descriptor: Option[EndpointDescriptor],
              discovery: => MidonetDiscovery): EndpointSelector =
        descriptor match {
            case Some(FixedEndpointDescriptor(hp)) =>
                CustomEndpointSelector.apply(hp)
            case somethingElse =>
                if (serviceName.isEmpty) {
                    throw new IllegalArgumentException(
                        "Service name wasn't provided but no fixed endpoint" +
                            "given as an alternative")
                }
                val addressFilter = somethingElse.map(_.createFilter())
                DiscoveryEndpointSelector(serviceName.get, discovery,
                                          addressFilter)
        }
}
