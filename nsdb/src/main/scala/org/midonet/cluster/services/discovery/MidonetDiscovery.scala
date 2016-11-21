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
import java.util.concurrent.ExecutorService

import scala.collection.JavaConverters._
import scala.reflect.runtime.universe._
import scala.util.control.NonFatal

import com.google.common.annotations.VisibleForTesting
import com.google.common.net.HostAndPort
import com.google.inject.Inject
import com.typesafe.scalalogging.Logger

import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.x.discovery._
import org.apache.curator.x.discovery.details.ServiceCacheListener
import org.slf4j.LoggerFactory

import rx.Observable
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
  * server and a port or a URI.
  *
  * The executor is necessary to perform background operations to update the
  * list of candidate services every time there's a new update (a service
  * is registered or unregistered).
  *
  * Service registration example for a service on URI "udp://10.0.0.1:2674"
  * ...
  * val discoveryService = new MidonetDiscovery(curator, executor, config)
  * val service = discoveryService.registerServiceInstance(
  *     "service_name", new URI("udp://10.0.0.1:2674"))
  * ...
  * // service no longer available
  * service.unregister()
  * // upon shutdown
  * discoveryService.stop()
  *
  * Service client example:
  * ...
  * val discoveryService = new MidonetDiscovery(curator, executor, config)
  * val client = discovery.getClient[MidonetServiceURI]("service_name")
  * val instances: MidonetServiceURI = client.instances
  * val address: String = instances.head.uri.getHost
  * val port: Int = instances.head.uri.getPort()
  * val protocol: String = instances.head.uri.getScheme()
  * ...
  *
  * Service observable client example:
  * ...
  * val serviceObserver: Observer[List[MidonetServiceURI]] = ...
  * client.observable.subscribe(serviceObserver)
  * ...
  *
  */
object MidonetDiscovery {
    // Path inside zookeeper
    final val DiscoveryPath = "/service-discovery"

    final val ServiceDiscoveryLog = "org.midonet.cluster.services.discovery"

}

trait MidonetDiscovery {

    /** Stops the discovery service so all resources are closed. It does not
      * unregisters currently registered service instances.
      */
    def stop(): Unit
    /** Returns a [[MidonetDiscoveryClient]] handle for the service discovery.
      * This client has an observable field that publishes changes in the set
      * of a given service provider name as well as methods to retrieve current
      * service instance candidates. On subscription to the observable, it
      * provides the current set of providers.
      *
      * @param serviceName is the name of the desired service
      */

    def getClient[S](serviceName: String)(implicit tag: TypeTag[S])
    : MidonetDiscoveryClient[S]

     /** Returns a [[MidonetServiceHandler]] handle necessary to register the
      * service instance in the discovery service. The returned service instance
      * must be explicitly unregistered. This method accepts an address and port
      * specifying the discovery information. If the registered node crashes,
      * it will be unregistered upon session timeout.
      *
      * @param serviceName name of the service to register
      * @param address String with the ip address
      * @param port Int port where the service is bound to
      * @return
      */
    def registerServiceInstance(serviceName: String, address: String, port: Int)
    : MidonetServiceHandler

    /** Returns a [[MidonetServiceHandler]] handle necessary to register the
      * service instance in the discovery service. The returned service instance
      * must be explicitly unregistered. This method accept an uri specifying
      * discovery information. If the registered node crashes, it will be
      * unregistered upon session timeout.
      *
      * @param serviceName name of the service to register
      * @param uri URI object specifying the discovery information
      * @return
      */
    def registerServiceInstance(serviceName: String, uri: URI)
    : MidonetServiceHandler

    /** Returns a [[MidonetServiceHandler]] handle necessary to register the
      * service instance in the discovery service. The returned service instance
      * must be explicitly unregistered. This method accepts a [[HostAndPort]]
      * specifying the discovery information. If the registered node crashes,
      * it will be unregistered upon session timeout.
      *
      * @param serviceName name of the service to register
      * @param hostAndPort [[HostAndPort]] describing the instance location
      * @return
      */
    def registerServiceInstance(serviceName: String,
                                hostAndPort: HostAndPort)
    : MidonetServiceHandler =
        registerServiceInstance(serviceName, hostAndPort.getHostText,
                                hostAndPort.getPort)

    /** Returns a [[MidonetServiceHandler]] handle necessary to register the
      * service instance in the discovery service. The returned service instance
      * must be explicitly unregistered. This method accepts a "host:port"
      * string specifying the discovery information. If the registered node
      * crashes, it will be unregistered upon session timeout.
      *
      * @param serviceName name of the service to register
      * @param hostAndPortStr "host:port" string with the instance location
      * @return
      */
    def registerServiceInstance(serviceName: String,
                                hostAndPortStr: String)
    : MidonetServiceHandler =
        registerServiceInstance(serviceName,
                                HostAndPort.fromString(hostAndPortStr))
}

class MidonetDiscoveryImpl @Inject()(curator: CuratorFramework,
                                 executor: ExecutorService,
                                 config: MidonetBackendConfig)
    extends MidonetDiscovery {

    private val log = Logger(LoggerFactory.getLogger(ServiceDiscoveryLog))

    log.debug("Midonet Discovery root path: " +
              config.rootKey + DiscoveryPath)

    private val discoveryService = ServiceDiscoveryBuilder
        .builder[Void](classOf[Void])
        .client(curator)
        .basePath(config.rootKey + DiscoveryPath)
        .build

    discoveryService.start()

    def stop(): Unit = discoveryService.close()

    def getClient[S](serviceName: String)(implicit tag: TypeTag[S])
    : MidonetDiscoveryClient[S] = {
        new MidonetDiscoveryClientImpl[S](serviceName, discoveryService, executor)
    }

    def registerServiceInstance(serviceName: String, address: String, port: Int)
    : MidonetServiceHandler = {
        new MidonetServiceHandlerImpl(serviceName, Option(address), Option(port),
                                      Option.empty, discoveryService)
    }

    def registerServiceInstance(serviceName: String, uri: URI)
    : MidonetServiceHandler = {
        new MidonetServiceHandlerImpl(serviceName, Option.empty, Option.empty,
                                      Option(uri), discoveryService)
    }
}

/**
  * Case classes supported by the MidonetDiscoveryService. When obtaining a
  * client, one of these cases classes should be suplied and the registered
  * information will be presented according to this interface.
  */
trait MidonetServiceInstance
case class MidonetServiceHostAndPort(address: String, port: Int)
    extends MidonetServiceInstance {
    override def toString = s"$address:$port"
}
case class MidonetServiceURI(uri: URI) extends MidonetServiceInstance {
    override def toString = uri.toString
}

/**
  * This trait provides a service discovery client exposing an observable
  * with the service provider changes. Current registered providers can also be
  * retrieved at any point in time.
  * The client instance should be explicitly 'stopped' if no one is going
  * to use it any more.
  */
trait MidonetDiscoveryClient[S] {

    /** Provide an observable for service provider changes. */
    val observable: Observable[Seq[S]]

    /** Get the current set of registered [[ServiceInstance]]. It's updated
      * upon notification from the storage layer (ZooKeeper) so it could be
      * outdated for a limited amount of time. Instances registered not
      * complying with the client type S are filtered.*/
    def instances: Seq[S]

    /** Stop receiving service provider information. It closes resources
      * used by cache and completes the observable. */
    def stop(): Unit
}

private[discovery] final class MidonetDiscoveryClientImpl[S](
        serviceName: String, serviceDiscovery: ServiceDiscovery[Void],
        executor: ExecutorService)(implicit val tag: TypeTag[S])
    extends MidonetDiscoveryClient[S]{

    private val log = Logger(LoggerFactory.getLogger(ServiceDiscoveryLog))

    private val updates = BehaviorSubject.create[Seq[S]]

    private val cache =
        serviceDiscovery.serviceCacheBuilder.name(serviceName).build

    private def updateServiceInstances(): Unit = {
        updates.onNext(instances)
    }

    cache.addListener(new ServiceCacheListener {
        override def cacheChanged(): Unit = updateServiceInstances()
        override def stateChanged(client: CuratorFramework,
                                  newState: ConnectionState): Unit =
            log.info(s"Changed state for $serviceName service: $newState")
    }, executor)

    cache.start()

    executor.submit(makeRunnable { updateServiceInstances() })

    override val observable = updates.asObservable.distinctUntilChanged

    override def stop(): Unit = {
        try {
            // The discovery service may have been closed previously
            cache.close()
        } catch {
            case NonFatal(e) => log.info("Service discovery already closed.")
        } finally {
            updates.onCompleted()
        }
    }

    override def instances: Seq[S] = {
        cache.getInstances.asScala.flatMap(asMidonetService)
    }

    private def asMidonetService(instance: ServiceInstance[Void]): Option[S] = {
        (instance.getAddress, instance.getPort, instance.getUriSpec) match {
            case (address: String, port: Integer, _)
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

}

/**
  * Handler for a registered midonet service. Once a service is registered,
  * it needs to be registered through its handler explicitly. If the service
  * crashes, the service will be automatically registered upon session
  * expiration.
  */
trait MidonetServiceHandler {

    /** Indicate that the service is no longer provided by this instance.
      * This method must be explicitly called if the user of this trait wants the
      * service to be immediately removed from the list of available instances.
      * If a service instance is not explicitly unregistered, the service
      * will only be unregistered (automatically) after the session with the
      * backend time outs -- because its associated discovery service
      * instance was closed or crashed).
      */
    def unregister(): Unit

}

private[discovery] final class MidonetServiceHandlerImpl(name: String,
       address: Option[String], port: Option[Int], uri: Option[URI],
       discovery: ServiceDiscovery[Void]) extends MidonetServiceHandler {

    /** Register the service with the specified discovery information into the
      * discovery service. It must be registered explicitly before being
      * available for discovery. */

    @VisibleForTesting
    private[discovery] val instanceBldr = ServiceInstance.builder[Void]()
        .serviceType(ServiceType.DYNAMIC)
        .name(name)
    if (address.isDefined) instanceBldr.address(address.get)
    if (port.isDefined) instanceBldr.port(port.get)
    if (uri.isDefined) instanceBldr.uriSpec(new UriSpec(uri.get.toString))

    @VisibleForTesting
    private[discovery] val instance = instanceBldr.build

    discovery.registerService(instance)

    override def unregister(): Unit = discovery.unregisterService(instance)

}
