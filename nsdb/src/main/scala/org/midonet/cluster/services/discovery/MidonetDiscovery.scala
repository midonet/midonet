/*
 * Copyright 2015 Midokura SARL
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
import org.apache.curator.x.discovery._
import org.apache.curator.x.discovery.details.{ServiceCacheListener, JsonInstanceSerializer}
import org.slf4j.LoggerFactory
import rx.Observable
import rx.subjects.BehaviorSubject

import org.midonet.util.concurrent.NamedThreadFactory

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
 */
class MidonetDiscovery @Inject()(curator: CuratorFramework) {
    import MidonetDiscovery._

    private val executor = Executors.newSingleThreadExecutor(
        new NamedThreadFactory("midonet-discovery"))

    /** Returns a client handle for the service discovery.
      * @param serviceName is the name of the desired service
      * @param clazz is the class of the service information
      * @param updates indicates if notifications of changes should be
      *                provided via observable */
    def newClient[Payload: ClassTag](serviceName: String, clazz: Class[Payload],
                                     updates: Boolean = false)
    : MidonetServiceClient[Payload] = {
        val serviceDiscovery = ServiceDiscoveryBuilder.builder(clazz)
            .client(curator)
            .basePath(DiscoveryPath)
            .serializer(new JsonInstanceSerializer(clazz))
            .build()
        if (updates)
            new MidonetCachedServiceClient[Payload](
                serviceName, serviceDiscovery, executor)
        else
            new MidonetDirectServiceClient[Payload](
                serviceName, serviceDiscovery)
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
            ServiceDiscoveryBuilder.builder[Payload](
                info.getClass.asInstanceOf[Class[Payload]])
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
 * A class representing a service discovery client
 */
abstract class MidonetServiceClient[Payload: ClassTag](val serviceName: String) {
    private val random = new Random()

    /** Get the list of available service providers */
    def getProviders: Array[Payload]

    /** Get a random service provider instance */
    def getRandomProvider: Option[Payload] = {
        val instances = getProviders
        instances.length match {
            case 0 => None
            case n: Int => Some(instances(random.nextInt(n)))
        }
    }

    /** Get an observable with service provider updates (if available) */
    val observable: Observable[Array[Payload]]

    /** Stop receiving service provider information */
    def stop(): Unit
}

final class MidonetCachedServiceClient[Payload: ClassTag] protected[discovery](
    name: String, serviceDiscovery: ServiceDiscovery[Payload],
    executor: ExecutorService) extends MidonetServiceClient[Payload](name) {

    private val log =
        LoggerFactory.getLogger(classOf[MidonetServiceClient[Payload]])

    @volatile private var providers: Array[Payload] = new Array[Payload](0)
    private val updates = BehaviorSubject.create[Array[Payload]](providers)

    private val cache = serviceDiscovery.serviceCacheBuilder()
        .name(serviceName).build()

    private def updateProviders(): Unit = {
        val payloads = cache.getInstances.asScala
            .flatMap(s => Option(s.getPayload)).toArray
        providers = payloads
        updates.onNext(payloads)
    }

    cache.addListener(new ServiceCacheListener {
        override def cacheChanged(): Unit = updateProviders()
        override def stateChanged(client: CuratorFramework,
                                  newState: ConnectionState): Unit =
            log.info("changed state for {} service discovery: " + newState,
                     serviceName)
    }, executor)
    cache.start()
    updateProviders()

    override def getProviders: Array[Payload] = providers
    override def stop(): Unit = {
        CloseableUtils.closeQuietly(cache)
        updates.onCompleted()
    }
    override val observable: Observable[Array[Payload]] =
        updates.asObservable().distinctUntilChanged().serialize()
}

final class MidonetDirectServiceClient[Payload: ClassTag] protected[discovery](
    name: String, serviceDiscovery: ServiceDiscovery[Payload])
    extends MidonetServiceClient[Payload](name) {

    private val provider = serviceDiscovery.serviceProviderBuilder()
        .serviceName(serviceName)
        .build()
    provider.start()

    override def getProviders: Array[Payload] = provider.getAllInstances.asScala
        .flatMap(s => Option(s.getPayload)).toArray

    override def stop(): Unit = {
        CloseableUtils.closeQuietly(provider)
    }
    override val observable: Observable[Array[Payload]] = Observable.empty()
}

/**
 * A class representing a server providing a service, and which can be
 * registered or unregistered from the service discovery framework
 */
final class MidonetServiceProvider[Payload: ClassTag] protected[discovery](
    val serviceName: String, serviceDiscovery: ServiceDiscovery[Payload]) {
    /** Indicate that the service is being provided by this instance */
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

