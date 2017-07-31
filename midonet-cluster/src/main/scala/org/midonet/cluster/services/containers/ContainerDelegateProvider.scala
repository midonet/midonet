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

package org.midonet.cluster.services.containers

import scala.reflect.classTag

import com.google.inject.{AbstractModule, Guice}

import org.reflections.Reflections

import org.midonet.cluster.conf.ClusterConfig
import org.midonet.cluster.services.MidonetBackend
import org.midonet.containers.{ContainerDelegate, ContainerProvider}
import org.midonet.util.logging.Logger

/**
  * Scans the current classpath for service container delegates.
  */
class ContainerDelegateProvider(backend: MidonetBackend,
                                reflections: Reflections,
                                config: ClusterConfig,
                                log: Logger)
    extends ContainerProvider[ContainerDelegate](reflections, log)(classTag[ContainerDelegate]) {

    protected override val injector = Guice.createInjector(new AbstractModule() {
        override def configure(): Unit = {
            bind(classOf[MidonetBackend]).toInstance(backend)
            bind(classOf[ClusterConfig]).toInstance(config)
        }
    })

}
