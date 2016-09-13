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

package org.midonet.containers

import java.util.UUID
import java.util.concurrent.{ExecutorService, ScheduledExecutorService}

import javax.inject.Named

import scala.concurrent.{ExecutionContext, Future}

import com.google.inject.Inject

import rx.subjects.PublishSubject
import rx.Observable

import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.midolman.routingprotocols.RoutingManagerActor
import org.midonet.midolman.routingprotocols.RoutingManagerActor.BgpContainerReady
import org.midonet.midolman.topology.VirtualTopology


/**
  * Implements a [[ContainerHandler]] for a BGP service.
  */
@Container(name = Containers.QUAGGA_CONTAINER, version = 1)
class QuaggaContainer @Inject()(@Named("id") id: UUID,
                               vt: VirtualTopology,
                               @Named("container") containerExecutor: ExecutorService,
                               @Named("io") ioExecutor: ScheduledExecutorService)
    extends ContainerHandler with ContainerCommons {

    override def logSource = "org.midonet.containers.bgp"
    override def logMark = s"bgp:$id"

    private val statusSubject = PublishSubject.create[ContainerStatus]

    private implicit val ec = ExecutionContext.fromExecutor(containerExecutor)

    /**
      * @see [[ContainerHandler.create]]
      */
    override def create(port: ContainerPort): Future[Option[String]] = {
        RoutingManagerActor.selfRefFuture.map { actorRef =>
            actorRef ! BgpContainerReady(port.portId)
            statusSubject onNext ContainerHealth(Code.RUNNING, "BGP", "None")
            None
        }
    }

    /**
      * @see [[ContainerHandler.updated]]
      */
    override def updated(port: ContainerPort): Future[Option[String]] =
        Future.successful(None)

    /**
      * @see [[ContainerHandler.delete]]
      */
    override def delete(): Future[Unit] = Future.successful(())

    /**
      * @see [[ContainerHandler.cleanup]]
      */
    override def cleanup(name: String): Future[Unit] = Future.successful(())

    /**
      * @see [[ContainerHandler.status]]
      */
    override def status: Observable[ContainerStatus] = {
        statusSubject.asObservable()
    }
}
