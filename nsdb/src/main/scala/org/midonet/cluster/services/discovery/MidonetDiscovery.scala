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

import com.google.inject.Inject
import org.apache.curator.framework.CuratorFramework
import org.apache.curator.utils.CloseableUtils
import org.apache.curator.x.discovery._
import org.apache.curator.x.discovery.details.JsonInstanceSerializer
import org.apache.curator.x.discovery.strategies.RandomStrategy

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

    /** Returns a client handle for the service discovery */
    def newClient[Payload](serviceName: String, clazz: Class[Payload])
    : MidonetServiceClient[Payload] =
        new MidonetServiceClient[Payload](
            serviceName,
            ServiceDiscoveryBuilder.builder(clazz)
                .client(curator)
                .basePath(DiscoveryPath)
                .serializer(new JsonInstanceSerializer(clazz))
                .build()
        )

    /** Returns a provider control instance that represents the current
      * server */
    def newProvider[Payload](serviceName: String, info: Payload)
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
final class MidonetServiceClient[Payload] protected[discovery](
    val serviceName: String, serviceDiscovery: ServiceDiscovery[Payload]) {
    private val provider = serviceDiscovery.serviceProviderBuilder()
        .serviceName(serviceName)
        .providerStrategy(new RandomStrategy[Payload])
        .build()
    def start(): Unit = provider.start()
    def stop(): Unit = CloseableUtils.closeQuietly(provider)
    def getServerInfo: Option[Payload] =
        if (provider.getInstance() == null) None
        else Option(provider.getInstance().getPayload)
}

/**
 * A class representing a server providing a service, and which can be
 * registered or unregistered from the service discovery framework
 */
final class MidonetServiceProvider[Payload] protected[discovery](
    val serviceName: String, serviceDiscovery: ServiceDiscovery[Payload]) {
    def start(): Unit = serviceDiscovery.start()
    def stop(): Unit = CloseableUtils.closeQuietly(serviceDiscovery)
}

/**
 * Basic host/port server details
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

