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

import java.util.concurrent.ExecutorService

import scala.collection.JavaConverters.collectionAsScalaIterableConverter
import scala.reflect.ClassTag
import scala.util.Random
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.x.discovery._
import org.apache.curator.x.discovery.details.ServiceCacheListener
import org.slf4j.LoggerFactory

import rx.subjects.BehaviorSubject

import org.midonet.cluster.services.discovery.MidonetDiscovery._
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.util.functors.makeRunnable

/**
  * A basic service discovery mechanism
  *
  * In order to use the service discovery mechanism, both for servers and
  * clients, a MidonetDiscovery should be instantiated.
  *
  * This instance generates both service and client handles. The service
  * is identified by its connection information, which is usually a
  * server and a port. This information should be provided in a user defined
  * object type (e.g. URI). This payload must be a json-serializable class
  * by jackson library, the underlying serializer of the curator discovery
  * framework. Annotating it with @JsonRootName("details") does the trick for
  * java classes; scala classes don't need it, but they must be static - top
  * level or inside an object).
  *
  * The executor is necessary to perform background operations to update the
  * list of candidate services every time there's a new update (a service
  * is registered or unregistered).
  *
  * The payload class for client and server for a given service name must
  * be the same. It's the user's responsability to check for the payload
  * correctness.
  *
  * Service registration example for a service on bound_ip:2674
  * ...
  * val discoveryService = new MidonetDiscovery[URI](curator, executor)
  * val service = discoveryService.registerServiceInstance("service_name", new URI("10.0.0.1:2674"))
  * ...
  * // service no longer available
  * service.unregister()
  * // upon shutdown
  * discoveryService.stop()
  *
  * Service client example:
  * ...
  * val discoveryService = new MidonetDiscovery[URI](curator, executor)
  * val client = discovery.getClient("service_name")
  * val allInstances: ServiceInstance[URI] = client.instances
  * val address: String = allInstances.get(0).getHost
  * val port: Int = allInstances.get(0).getPort()
  * ...
  *
  * Service observable client example:
  * ...
  * val serviceObserver: Observer[List[ServiceInstance[URI]]] = ...
  * client.observable.subscribe(serviceObserver)
  * ...
  *
  */
object MidonetDiscovery {
    // Path inside zookeeper
    final val discoveryPath = "/service-discovery"

    final val serviceDiscoveryLog = "org.midonet.cluster.services.discovery"

}

class MidonetDiscovery[P] @Inject()(curator: CuratorFramework,
                                    executor: ExecutorService,
                                    config: MidonetBackendConfig)
                                   (implicit ct: ClassTag[P]) {

    private val log = Logger(LoggerFactory.getLogger(serviceDiscoveryLog))

    log.debug("Midonet Discovery root path: " +
              config.rootKey + discoveryPath)

    private val discoveryService = ServiceDiscoveryBuilder
        .builder[P](ct.runtimeClass.asInstanceOf[Class[P]])
        .client(curator)
        .basePath(config.rootKey + discoveryPath)
        .build

    discoveryService.start()

    /** Stops the discovery service so all resources are closed. It does not
      * unregisters currently registered service instances.
      */
    def stop(): Unit = discoveryService.close()

    /** Returns a [[MidonetDiscoveryClient]] handle for the service discovery.
      * This client has an observable field that publishes changes in the set
      * of a given service provider name as well as methods to retrieve current
      * service instance candidates. On subscription to the observable, it
      * provides the current set of providers.
      *
      * @param serviceName is the name of the desired service
      */
    def getClient(serviceName: String): MidonetDiscoveryClient[P] = {
        new MidonetDiscoveryClient(
            serviceName, discoveryService, executor)
    }

    /** Returns a [[MidonetServiceInstance]] handle necessary to registers the
      * service instance in the discovery service. If server and port are not
      * specified, it is assumed that information to contact the service is
      * contained within the info parameter. The returned service instance
      * must be explicitly unregistered.
      *
      * @param serviceName name of the service to register
      * @param info discovery information necessary for the clients
      * @return
      */
    @throws[IllegalArgumentException]
    def registerServiceInstance(serviceName: String,
                                info: P)
    : MidonetServiceInstance[P] = {
        if (info == null) {
            throw new IllegalArgumentException(
                "Service discovery information should be provided on the" +
                "payload, so it cannot be null.")
        }
        new MidonetServiceInstance[P](serviceName, info, discoveryService)
    }
}


/**
  * A class implementing a service discovery client providing an observable
  * with the service provider changes.
  * The client instance should be explicitly 'stopped' if no one is going
  * to use it any more.
  *
  * @tparam P is the the Service Information class (payload)
  */
final class MidonetDiscoveryClient[P: ClassTag] (
    serviceName: String, serviceDiscovery: ServiceDiscovery[P],
    executor: ExecutorService) {

    private val log = Logger(LoggerFactory.getLogger(serviceDiscoveryLog))

    private val random = new Random()

    private val updates =
        BehaviorSubject.create[List[ServiceInstance[P]]]

    private val cache =
        serviceDiscovery.serviceCacheBuilder.name(serviceName).build

    private def updateServiceInstances(): Unit = {
        updates.onNext(instances)
    }

    cache.addListener(new ServiceCacheListener {
        override def cacheChanged(): Unit = updateServiceInstances()
        override def stateChanged(client: CuratorFramework,
                                  newState: ConnectionState): Unit =
            log.info(
                s"Changed state for $serviceName service: $newState")
    }, executor)
    cache.start()
    executor.submit(makeRunnable { updateServiceInstances() })

    /** Provide an observable for service provider changes */
    val observable = updates.asObservable.distinctUntilChanged

    /** Stop receiving service provider information. It closes resources
      * used by cache and completes the observable. */
    def stop(): Unit = {
        try {
            // The discovery service may have been closed previously
            cache.close()
        } catch {
            case NonFatal(e) => log.info("Service discovery already closed.")
        } finally {
            updates.onCompleted()
        }
    }

    /** Get the current set of registered [[ServiceInstance]]. It's updated
      * upon notification from the storage layer (ZooKeeper) so it could be
      * outdated for a limited amount of time. */
    def instances: List[ServiceInstance[P]] = {
        cache.getInstances.asScala.toList
    }

}

/**
  * A class representing a server providing a service, and which can be
  * registered or unregistered from the service discovery framework.
  */
final class MidonetServiceInstance[P: ClassTag] (
    serviceName: String, info: P, serviceDiscovery: ServiceDiscovery[P]) {


    /** Register the service with the specified discovery information into the
      * discovery service. It must be registered explicitly before being
      * available for discovery.
      */
    @VisibleForTesting
    private[discovery] val serviceInstance = ServiceInstance.builder[P]()
        .serviceType(ServiceType.DYNAMIC)
        .name(serviceName)
        .payload(info)
        .build

    serviceDiscovery.registerService(serviceInstance)

    /** Indicate that the service is no longer provided by this instance.
      * This method must be explicitly called to unregister a service. Shutting
      * down the discovery service does not modify the registration state to
      * prevent service unregistration upon crash.*/
    def unregister(): Unit = serviceDiscovery.unregisterService(serviceInstance)
}
