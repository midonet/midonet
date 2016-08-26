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

package org.midonet.midolman.containers

import java.util.UUID
import java.util.concurrent.{ExecutorService, ScheduledExecutorService}

import scala.reflect.classTag

import com.google.inject.name.Names
import com.google.inject.{AbstractModule, Guice}

import org.reflections.Reflections

import org.midonet.cluster.services.MidonetBackend
import org.midonet.containers.{ContainerHandler, ContainerProvider}
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.util.logging.Logger

/**
  * Scans the current classpath for service container handlers.
  */
class ContainerHandlerProvider(reflections: Reflections,
                               vt: VirtualTopology,
                               ioExecutor: ScheduledExecutorService,
                               log: Logger)
    extends ContainerProvider[ContainerHandler](reflections, log)(classTag[ContainerHandler]) {

    protected override val injector = Guice.createInjector(new AbstractModule() {
        override def configure(): Unit = {
            bind(classOf[VirtualTopology]).toInstance(vt)
            bind(classOf[MidonetBackend]).toInstance(vt.backend)
            bind(classOf[ScheduledExecutorService]).annotatedWith(Names.named("io"))
                                                   .toInstance(ioExecutor)
        }
    })

    /**
      * Gets a new container instance from the current container provider,
      * where the container receives the specified identifier.
      */
    @throws[Exception]
    def getInstance(name: String, id: UUID, containerExecutor: ExecutorService)
    : ContainerHandler = {
        current get name match {
            case Some((_, clazz)) =>
                injector.createChildInjector(new AbstractModule {
                    override def configure(): Unit = {
                        bind(classOf[UUID])
                            .annotatedWith(Names.named("id"))
                            .toInstance(id)
                        bind(classOf[ExecutorService])
                            .annotatedWith(Names.named("container"))
                            .toInstance(containerExecutor)
                    }
                }).getInstance(clazz).asInstanceOf[ContainerHandler]
            case None => throw new NoSuchElementException(name)
        }
    }

}
