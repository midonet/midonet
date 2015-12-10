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

import java.util.concurrent.ExecutorService

import scala.reflect.classTag

import com.google.inject.name.Names
import com.google.inject.{AbstractModule, Guice}
import com.typesafe.scalalogging.Logger

import org.midonet.containers.ContainerProvider
import org.midonet.midolman.topology.VirtualTopology

/**
  * Scans the current classpath of the package specified by the `prefix`
  * argument for service container handlers.
  */
class ContainerHandlerProvider(prefix: String,
                               vt: VirtualTopology,
                               executor: ExecutorService,
                               log: Logger)
    extends ContainerProvider[ContainerHandler](prefix, log)(classTag[ContainerHandler]) {

    log info s"Scanning classpath $prefix for service container handler"

    protected override val injector = Guice.createInjector(new AbstractModule() {
        override def configure(): Unit = {
            bind(classOf[VirtualTopology]).toInstance(vt)
            bind(classOf[ExecutorService]).annotatedWith(Names.named("container"))
                                          .toInstance(executor)
        }
    })

}
