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

package org.midonet.cluster.services.containers.schedulers

import java.util.UUID

import scala.collection.JavaConverters._
import scala.util.Random

import com.typesafe.scalalogging.Logger

import org.scalatest.{Suite, BeforeAndAfter}
import org.slf4j.LoggerFactory

import rx.schedulers.Schedulers

import org.midonet.cluster.data.storage.InMemoryStorage
import org.midonet.cluster.models.State.ContainerServiceStatus
import org.midonet.cluster.models.Topology.{PortGroup, Port, HostGroup, Host}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend._
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.reactivex._

trait SchedulersTest extends Suite with BeforeAndAfter {

    protected var store: InMemoryStorage = _
    protected var context: Context = _
    protected val random = new Random()

    before {
        val executor = new SameThreadButAfterExecutorService
        val log = Logger(LoggerFactory.getLogger("containers"))
        store = new InMemoryStorage
        MidonetBackend.setupBindings(store, store)
        context = Context(store, store, executor, Schedulers.from(executor), log)
    }

    protected def createHost(): Host = {
        val host = Host.newBuilder()
                       .setId(randomUuidProto)
                       .build()
        store create host
        host
    }

    protected def createHostStatus(hostId: UUID): ContainerServiceStatus = {
        val status = ContainerServiceStatus.newBuilder()
            .setWeight(random.nextInt())
            .build()
        store.addValueAs(hostId.toString, classOf[Host], hostId,
                         ContainerKey, status.toString).await()
        status
    }

    protected def deleteHostStatus(hostId: UUID): Unit = {
        store.removeValueAs(hostId.toString, classOf[Host], hostId,
                            ContainerKey, null).await()
    }

    protected def createPort(host: Host = null): Port = {
        val builder = Port.newBuilder()
                          .setId(randomUuidProto)
                          .setInterfaceName(random.nextString(4))
        if (host ne null)
            builder.setHostId(host.getId)
        val port = builder.build()
        store.create(port)
        port
    }

    protected def activatePort(port: Port): Unit = {
        store.addValueAs(port.getHostId.asJava.toString, classOf[Port],
                         port.getId, ActiveKey, ActiveKey).await()
    }

    protected def deactivatePort(port: Port): Unit = {
        store.removeValueAs(port.getHostId.asJava.toString, classOf[Port],
                            port.getId, ActiveKey, null).await()
    }

    protected def createHostGroup(hostIds: UUID*): HostGroup = {
        val hostGroup = HostGroup.newBuilder()
                                 .setId(randomUuidProto)
                                 .addAllHostIds(hostIds.map(_.asProto).asJava)
                                 .build()
        store create hostGroup
        hostGroup
    }

    protected def createPortGroup(portIds: UUID*): PortGroup = {
        val portGroup = PortGroup.newBuilder()
                                 .setId(randomUuidProto)
                                 .addAllPortIds(portIds.map(_.asProto).asJava)
                                 .build()
        store create portGroup
        portGroup
    }
}
