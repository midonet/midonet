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

package org.midonet.cluster.services

import java.net.URI

import scala.reflect.runtime.universe._

import rx.Observable

import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetDiscoveryClient, MidonetServiceHandler}

class MockMidonetDiscovery() extends MidonetDiscovery {
    override def getClient[S](serviceName: String)(implicit tag: TypeTag[S])
        : MidonetDiscoveryClient[S] = {
            new MockMidonetServiceClient[S]
        }

        override def registerServiceInstance(serviceName: String,
                                             address: String,
                                             port: Int)
        : MidonetServiceHandler = { null }

        override def registerServiceInstance(serviceName: String,
                                             uri: URI)
        : MidonetServiceHandler = { null }

        override def stop(): Unit = {}
    }

    class MockMidonetServiceClient[S] extends MidonetDiscoveryClient[S] {

        override val observable: Observable[Seq[S]] = Observable.empty()

        /** Stop receiving service provider information. It closes resources
          * used by cache and completes the observable. */
        override def stop(): Unit = {}

        /** Get the current set of registered [[org.apache.curator.x.discovery.ServiceInstance]]. It's updated
          * upon notification from the storage layer (ZooKeeper) so it could be
          * outdated for a limited amount of time. Instances registered not
          * complying with the client type S are filtered. */
        override def instances: Seq[S] = Seq.empty
    }
