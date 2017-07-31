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

import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicReference

import scala.collection.JavaConverters.{asJavaCollectionConverter, asScalaSetConverter}
import scala.collection.mutable

import com.google.common.net.HostAndPort

import rx.subjects.BehaviorSubject

import org.midonet.cluster.services.endpoint.EndpointSelector
import org.midonet.util.random._

/**
  * An object that serves as an observable set of custom service end points.
 *
  * @param epSet is the initial set of hosts and ports of the target service.
  */
class CustomEndpointSelector(epSet: Set[HostAndPort] = Set.empty)
    extends EndpointSelector {

    private val endpoints = new AtomicReference[mutable.Set[HostAndPort]](null)
    private val subject = BehaviorSubject.create[Set[HostAndPort]]()
    setEndpoints(epSet)

    /**
      * Gets a random valid endpoint.
      * @return An option representing a random valid endpoint or None if no
      *         valid endpoints exist.
      */
    override def getEndpoint: Option[HostAndPort] = rndElement(endpoints.get)

    /**
      * Adds a new endpoint to the custom endpoint set.
      * @param endpoint Host and port of the new endpoint.
      */
    def addEndpoint(endpoint: HostAndPort) = {
        endpoints.get().add(endpoint)
        notifyChange()
    }

    /**
      * Removes an endpoint from the custom endpoint set.
      * @param endpoint Host and port of the endpoint to remove.
      */
    def removeEndpoint(endpoint: HostAndPort) = {
        endpoints.get().remove(endpoint)
        notifyChange()
    }

    /**
      * Sets the new custom endpoint set.
      * @param epSet The new custom endpoint set.
      */
    def setEndpoints(epSet: Iterable[HostAndPort]) = {
        val concurrentSet = Collections.newSetFromMap(
            new ConcurrentHashMap[HostAndPort, java.lang.Boolean])
        concurrentSet.addAll(epSet.asJavaCollection)
        endpoints.lazySet(concurrentSet.asScala)
        notifyChange()
    }

    /**
      * Clears the custom endpoint set.
      */
    def clear() = {
        endpoints.get().clear()
        notifyChange()
    }

    /**
      * Gets the set of valid endpoints
      * @return An observable returning valid endpoint (or an empty set)
      *         A new set is emitted every time
      */
    override def observable = subject.asObservable.distinctUntilChanged

    override def close(): Unit = {
        // Do nothing
    }

    private def notifyChange(): Unit =
        subject.onNext(endpoints.get.toSet)
}

object CustomEndpointSelector {
    def apply(epSet: Set[HostAndPort]): CustomEndpointSelector =
        new CustomEndpointSelector(epSet)

    def apply(hp: Option[HostAndPort] = None): CustomEndpointSelector =
        apply(hp.toSet)

    def apply(hp: HostAndPort): CustomEndpointSelector =
        apply(Set(hp))
}
