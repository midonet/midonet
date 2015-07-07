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

import java.util.concurrent.{ExecutorService, Executors}

import scala.collection.JavaConverters.collectionAsScalaIterableConverter
import scala.reflect.ClassTag
import scala.util.Random

import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.framework.state.ConnectionState
import org.apache.curator.utils.CloseableUtils
import org.apache.curator.x.discovery.{ServiceInstance, ServiceType, ServiceDiscovery, ServiceDiscoveryBuilder}
import org.apache.curator.x.discovery.details.{ServiceCacheListener, JsonInstanceSerializer}
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.BehaviorSubject

import org.midonet.util.concurrent.NamedThreadFactory
import org.midonet.util.functors.makeRunnable

/**
  * A basic service discovery mechanism
  *
  * In order to use the service discovery mechanism, both for servers and
  * clients, a MidonetDiscovery should be instantiated, preferably as a
  * singleton.
  *
  * This instance generates both provider (server) and client handles.
  * The server information is contained in the 'Payload', which must be
  * a json-serializable class (annotating it with @JsonRootName("details")
  * does the trick for java classes; scala classes don't need it, but they
  * must be static - top level or inside an object).
  *
  * The payload class for client and server for a given service name must
  * be the same.
  *
  * Service registration example:
  * ...
  * val discoveryService = new MidonetDiscovery(curator)
  * val providerInfo = HostPortInfo("host_address", host_port)
  * val provider = discoveryService.newProvider("service_name", providerInfo)
  * provider.register()
  * ...
  *
  * Service client example:
  * ...
  * val discoveryService = new MidonetDiscovery(curator)
  * val client = discovery.newClient("service_name", classOf[HostPortInfo])
  * val allProviders: Array[HostPortInfo] = client.getProviders
  * val randomProvider: HostPortInfo = client.getRandomProvider
  * ...
  *
  * Service observable client example:
  * ...
  * val client = discovery.newObservableClient("service_name", classOf[HostPortInfo])
  * val serviceObserver: Observer[Array[HostPortInfo] ] = ...
  * client.observable.subscribe(serviceObserver)
  * ...
  *
  */
class MidonetDiscovery @Inject()(curator: CuratorFramework) {
    import MidonetDiscovery.DiscoveryPath

    private val executor = Executors.newSingleThreadExecutor(
        new NamedThreadFactory("midonet-discovery", isDaemon = true))

    /** Returns a client handle for the service discovery.
      * @param serviceName is the name of the desired service
      * @param clazz is the class of the service information, containing
      *              details on how to contact the server providing the
      *              service
      */
    def newClient[Payload: ClassTag](serviceName: String, clazz: Class[Payload])
    : MidonetServiceClient[Payload] = {
        val serviceDiscovery = ServiceDiscoveryBuilder.builder(clazz)
            .client(curator)
            .basePath(DiscoveryPath)
            .serializer(new JsonInstanceSerializer(clazz))
            .build()
        new MidonetServiceClient[Payload](serviceName, serviceDiscovery)
    }

    /** Returns an observable that publishes changes in the set of
      * service providers. On subscription, it provides the current set
      * of providers.
      * @param serviceName is the name of the desired service
      * @param clazz is the class of the service information, containing
      *              details on how to contact the server providing the
      *              service
      */
    def newObservableClient[Payload: ClassTag](serviceName: String,
                                               clazz: Class[Payload])
    : MidonetServiceObservableClient[Payload] = {
        val serviceDiscovery = ServiceDiscoveryBuilder.builder(clazz)
            .client(curator)
            .basePath(DiscoveryPath)
            .serializer(new JsonInstanceSerializer(clazz))
            .build()
        new MidonetServiceObservableClient[Payload](
            serviceName, serviceDiscovery, executor)
    }

    /** Returns a provider control instance that represents the current
      * server. Note that the returned instance must be registered/unregistered
      * explicitly */
    def newProvider[Payload: ClassTag](serviceName: String, info: Payload)
    : MidonetServiceProvider[Payload] = {
        val instance = ServiceInstance.builder[Payload]()
            .serviceType(ServiceType.DYNAMIC)
            .name(serviceName)
            .payload(info)
            .build()
        new MidonetServiceProvider(
            serviceName,
            ServiceDiscoveryBuilder
                .builder[Payload](info.getClass.asInstanceOf[Class[Payload]])
                    .client(curator)
                    .basePath(DiscoveryPath)
                    .serializer(new JsonInstanceSerializer(
                        info.getClass.asInstanceOf[Class[Payload]]))
                    .thisInstance(instance)
                    .build()
        )
    }
}

object MidonetDiscovery {
    // Path inside zookeeper
    final val DiscoveryPath = "/midonet/service-discovery"
}

/**
  * A class representing a service discovery client.
  * The client instance should be explicitly 'stopped' if no one is going
  * to use it any more.
  * @tparam I is the the Service Information class (payload)
  */
final class MidonetServiceClient[I: ClassTag](
    val serviceName: String, serviceDiscovery: ServiceDiscovery[I]) {

    private val random = new Random()

    private val provider = serviceDiscovery.serviceProviderBuilder()
        .serviceName(serviceName)
        .build()
    provider.start()

    /** Get the list of available service providers */
    def getProviders: Array[I] = provider.getAllInstances.asScala
        .flatMap(s => Option(s.getPayload)).toArray

    /** Stop receiving service provider information */
    def stop(): Unit = {
        CloseableUtils.closeQuietly(provider)
    }

    /** Get a random service provider instance */
    def getRandomProvider: Option[I] = {
        val instances = getProviders
        instances.length match {
            case 0 => None
            case n: Int => Some(instances(random.nextInt(n)))
        }
    }
}

/**
  * A class implementing a service discovery client providing an observable
  * with the service provider changes
  * The client instance should be explicitly 'stopped' if no one is going
  * to use it any more.
  * @tparam I is the the Service Information class (payload)
  */
final class MidonetServiceObservableClient[I: ClassTag] protected[discovery](
    val serviceName: String, serviceDiscovery: ServiceDiscovery[I],
    executor: ExecutorService) {

    private val log =
        LoggerFactory.getLogger(classOf[MidonetServiceClient[I]])

    private val updates =
        BehaviorSubject.create[Array[I]](Array[I]())

    private val cache = serviceDiscovery.serviceCacheBuilder()
        .name(serviceName).build()

    private def updateProviders(): Unit =
        updates.onNext(cache.getInstances.asScala
            .flatMap(s => Option(s.getPayload)).toArray)

    cache.addListener(new ServiceCacheListener {
        override def cacheChanged(): Unit = updateProviders()
        override def stateChanged(client: CuratorFramework,
                                  newState: ConnectionState): Unit =
            log.info("changed state for {} service discovery: " + newState,
                     serviceName)
    }, executor)
    cache.start()
    executor.submit(makeRunnable {updateProviders()})

    /** Stop receiving service provider information */
    def stop(): Unit = {
        CloseableUtils.closeQuietly(cache)
        updates.onCompleted()
    }

    /** Provide an observable for service provider changes */
    val observable: Observable[Array[I]] =
        updates.asObservable().distinctUntilChanged().serialize()
}

/**
  * A class representing a server providing a service, and which can be
  * registered or unregistered from the service discovery framework.
  * @tparam I is the the Service Information class (payload)
  */
final class MidonetServiceProvider[I: ClassTag] protected[discovery](
    val serviceName: String, serviceDiscovery: ServiceDiscovery[I]) {

    /** Register this instance as a provider of the specified service name */
    def register(): Unit = serviceDiscovery.start()
    /** Indicate that the service is no longer provided by this instance */
    def unregister(): Unit = CloseableUtils.closeQuietly(serviceDiscovery)
}

/**
 * Basic payload with host/port server details.
 * Note that this class must be json serializable (i.e. provide
 * setters and getters, and define equals and hashCode. Java implementations
 * require annotating it with '@JsonRootName("details")'.
 */
final class HostPortInfo(private var host: String, private var port: Int) {
    def this() = this("localhost", 0)
    def setHost(h: String): Unit = host = h
    def getHost: String = host
    def setPort(p: Int): Unit = port = p
    def getPort: Int = port
    override def equals(o: Any): Boolean = o match {
        case that: HostPortInfo => host == that.host && port == that.port
        case _ => false
    }
    override def hashCode: Int = 41 * host.hashCode + port.hashCode
}

