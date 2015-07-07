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
 */
class MidonetDiscovery @Inject()(curator: CuratorFramework) {
    import MidonetDiscovery._

    /** Returns a client handle for the service discovery */
    def newClient[Payload](serviceName: String): MidonetServiceClient[Payload] =
        new MidonetServiceClient[Payload](
            serviceName,
            ServiceDiscoveryBuilder.builder(classOf[Payload])
                .client(curator)
                .basePath(DiscoveryPath)
                .serializer(new JsonInstanceSerializer(classOf[Payload]))
                .build()
        )
    
    /** Returns a provider control instance that represents the current
      * server */
    def newProvider[Payload](srv: MidonetService[Payload])
    : MidonetServiceProvider[Payload] =
        new MidonetServiceProvider(
            ServiceDiscoveryBuilder.builder(classOf[Payload])
                .client(curator)
                .basePath(DiscoveryPath)
                .serializer(new JsonInstanceSerializer(classOf[Payload]))
                .thisInstance(srv.instance)
                .build()
        )
}
object MidonetDiscovery {
    final val DiscoveryPath = "/midonet/discovery"
}

/**
 * A class representing a service discovery client
 */
class MidonetServiceClient[Payload] protected[discovery](
    val name: String, private val serviceDiscovery: ServiceDiscovery[Payload]) {
    private val provider = serviceDiscovery.serviceProviderBuilder()
        .serviceName(name)
        .providerStrategy(new RandomStrategy[Payload])
        .build()
    def start(): Unit = provider.start()
    def stop(): Unit = CloseableUtils.closeQuietly(provider)
    def getServerInfo: Payload = provider.getInstance().getPayload
}

/**
 * A class representing a server providing a service, and which can be
 * registered or unregistered from the service discovery framework
 */
class MidonetServiceProvider[Payload] protected[discovery](
    private val serviceDiscovery: ServiceDiscovery[Payload]) {
    def start(): Unit = serviceDiscovery.start()
    def stop(): Unit = CloseableUtils.closeQuietly(serviceDiscovery)
}

/**
 * Service description
 */
class MidonetService[Payload](name: String, payload: Payload) {
    protected[discovery] val instance = ServiceInstance.builder[Payload]()
        .serviceType(ServiceType.DYNAMIC)
        .name(name)
        .payload(payload)
        .build()
}

