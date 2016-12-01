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

import java.net.URI

import scala.reflect.runtime.universe.{TypeTag, typeOf}

import org.apache.curator.x.discovery.UriSpec

import rx.Observer
import rx.subjects.BehaviorSubject

/**
  * An implementation of MidonetDiscovery for use with Unit tests.
  *
  * This class keeps all discovery state in an observable set of (ServiceName,
  * URI) pairs, mimicking the behaviour of the standard Zookeeper-based
  * MidonetDiscovery, without all the networking hassle.
  */
class FakeDiscovery extends MidonetDiscovery {

    /**
      * Set of registered service instances as (ServiceName, URI) pairs.
      */
    var registeredServices = Set[(String, URI)]()

    private val discoveryUpdates = BehaviorSubject.create[Set[(String, URI)]]

    /**
      * A companion implementation of MidonetServiceHandler to unregister
      * services from the registeredServices set of FakeDiscovery.
      *
      * @param serviceName The name of the service associated with this handler.
      * @param uri The URI of the particular instance of the service associated
      *            with this handler.
      */
    private class MockedMidonetServiceHandler(serviceName: String,
                                              uri: URI)
        extends MidonetServiceHandler {
        override def unregister() = {
            registeredServices = registeredServices - ((serviceName, uri))
            discoveryUpdates.onNext(registeredServices)
        }
    }

    override def stop(): Unit = {
        // Do nothing
    }

    override def getClient[S](serviceName: String)
                             (implicit tag: TypeTag[S]) =
        new FakeDiscoveryClient[S](serviceName)

    override def registerServiceInstance(serviceName: String,
                                         address: String,
                                         port: Int)
        : MidonetServiceHandler =
        registerServiceInstance(serviceName,
                                new URI(null, null, address, port,
                                        null, null, null))

    override def registerServiceInstance(serviceName: String,
                                         uri: URI)
        : MidonetServiceHandler = {
        registeredServices = registeredServices + ((serviceName, uri))
        discoveryUpdates.onNext(registeredServices)
        new MockedMidonetServiceHandler(serviceName, uri)
    }

    /**
      * A companion implementation of MidonetDiscoveryClient that observes
      * for changes related to a particular service in the Set of pairs managed
      * by FakeDiscovery.
      *
      * @param serviceName The name of the service whose instances we want to
      *                    get notified about.
      * @tparam S One of MidonetServiceHostAndPort or MidonetServiceURI
      */
    class FakeDiscoveryClient[S](serviceName: String)(implicit val tag: TypeTag[S])
        extends MidonetDiscoveryClient[S] with Observer[Set[(String, URI)]] {
        private val updates = BehaviorSubject.create[Seq[S]]

        override val observable = updates.asObservable.distinctUntilChanged

        // Subscribe to the set managed by FakeDiscovery
        private val discoverySubscription = discoveryUpdates.subscribe(this)

        /**
          * @return A sequence of all service instances matching the service
          *         name provided in the constructor.
          */
        override def instances: Seq[S] =
            asMidonetServices(registeredServices)

        /**
          * Unsubscribe this client from the discovery service.
          */
        override def stop(): Unit = discoverySubscription.unsubscribe()

        /**
          * Convert a set of (ServiceName, URI) pairs to a sequence of
          * MidonetServiceInstances.
          *
          * @param services Set of (ServiceName, URI) pairs.
          * @return Sequence of MidonetServiceInstances.
          */
        private def asMidonetServices(services: Set[(String, URI)]): Seq[S] =
            services.filter {
                case (s, _) if s == serviceName => true
                case _ => false
            }.map(_._2).flatMap(asMidonetService).toSeq

        /**
          * Convert a URI to a subclass of MidonetServiceInstance according to
          * the generic type of this client.
          *
          * @param uri The uri we want to convert to a MidonetServiceInstance.
          * @return An option containing an instance of MidonetServiceURI or
          *         MidonetServiceHostAndPort or None if S doesn't match either
          *         of those types.
          */
        private def asMidonetService(uri: URI): Option[S] = {
            (uri.getHost, uri.getPort, new UriSpec(uri.toString)) match {
                case (address: String, port: Int, _)
                    if typeOf[S] =:= typeOf[MidonetServiceHostAndPort] =>
                    Option(MidonetServiceHostAndPort(address, port).asInstanceOf[S])
                case (_, _, uri: UriSpec)
                    if typeOf[S] =:= typeOf[MidonetServiceURI] =>
                    Option(MidonetServiceURI(new URI(uri.build)).asInstanceOf[S])
                case _ =>
                    // We filter those instances that do not comply with the
                    // type of the client doing the request
                    None
            }
        }

        /**
          * When an error comes from FakeDiscovery, propagate it to our own
          * observable.
          *
          * @param e Error coming from FakeDiscovery.
          */
        override def onError(e: Throwable) = updates.onError(e)

        /**
          * When a completion event comes from FakeDiscovery, propagate it to
          * our own observable.
          */
        override def onCompleted() = updates.onCompleted()

        /**
          * When an updated set of instances comes from FakeDiscovery, propagate
          * it to our own observable as a sequence of MidonetServiceInstances.
          *
          * @param t A set of (ServiceName, URI) pairs.
          */
        override def onNext(t: Set[(String, URI)]) =
            updates.onNext(asMidonetServices(t))
    }
}

