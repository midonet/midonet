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

package org.midonet.cluster.services.state.client

import scala.reflect.runtime.universe._

import org.apache.zookeeper.KeeperException.UnimplementedException

import rx.Observable

import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetDiscoveryClient, MidonetServiceHandler, MidonetServiceHostAndPort}
import org.midonet.cluster.services.state.StateProxyService

class DiscoveryMock(host: String, port: Int)
    extends DiscoveryMockList(List( MidonetServiceHostAndPort(host,port) ))

class DiscoveryMockList(servers: Seq[MidonetServiceHostAndPort])
    extends MidonetDiscovery {

    override def stop(): Unit = {}

    override def getClient[S](serviceName: String)(implicit tag: TypeTag[S])
        : MidonetDiscoveryClient[S] = {
        if (serviceName.equals(StateProxyService.Name)) {
            new MidonetDiscoveryClient[MidonetServiceHostAndPort] {
                override def stop(): Unit = {}

                override val instances: Seq[MidonetServiceHostAndPort] =
                    servers

                val observable: Observable[Seq[MidonetServiceHostAndPort]] = null

            }.asInstanceOf[MidonetDiscoveryClient[S]]
        } else {
            throw new Exception("Wrong service name")
        }
    }

    override def registerServiceInstance(serviceName: String, address: String, port: Int)
        : MidonetServiceHandler = throw new Exception("Method unimplemented in mock")

    override def registerServiceInstance(serviceName: String, url: java.net.URI)
        : MidonetServiceHandler = throw new Exception("Method unimplemented in mock")
}
