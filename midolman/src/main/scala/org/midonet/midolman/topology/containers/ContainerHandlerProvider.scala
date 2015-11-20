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

package org.midonet.midolman.topology.containers

import scala.reflect.classTag

import com.google.inject.{AbstractModule, Guice}
import com.typesafe.scalalogging.Logger

import org.midonet.containers.ContainerProvider
import org.midonet.midolman.topology.VirtualTopology
import org.midonet.midolman.topology.containers.ContainerHandlerProvider.Prefix

object ContainerHandlerProvider {
    val Prefix = "org.midonet.midolman.topology.containers"
}

/**
  * Scans the current classpath of the package
  * `org.midonet.midolman.topology.containers` for service container handlers.
  */
class ContainerHandlerProvider(vt: VirtualTopology, log: Logger)
    extends ContainerProvider[ContainerHandler](Prefix, log)(classTag[ContainerHandler]) {

    log info "Scanning classpath for service container handler"

    protected override val injector = Guice.createInjector(new AbstractModule() {
        override def configure(): Unit = {
            bind(classOf[VirtualTopology]).toInstance(vt)
        }
    })

}
