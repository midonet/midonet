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

import scala.collection.JavaConverters._

import com.google.inject.{AbstractModule, Guice}
import com.typesafe.scalalogging.Logger

import org.reflections.Reflections

import org.midonet.containers.Container
import org.midonet.midolman.config.MidolmanConfig
import org.midonet.midolman.topology.VirtualTopology

/**
  * Scans the current classpath of the package
  * `org.midonet.midolman.topology.containers` for service container handlers.
  */
class ContainerHandlerProvider(log: Logger,
                               vt: VirtualTopology) {

    log info "Scanning classpath for service container handler"
    private val reflections =
        new Reflections("org.midonet.midolman.topology.containers")
    private val annotated =
        reflections.getTypesAnnotatedWith(classOf[Container]).asScala
    private val injector = Guice.createInjector(new AbstractModule() {
        override def configure(): Unit = {
            bind(classOf[VirtualTopology]).toInstance(vt)
        }
    })

    private val allContainers =
        annotated filter {
            classOf[ContainerHandler].isAssignableFrom
        } map { clazz =>
            val annotation = clazz.getAnnotation(classOf[Container])
            log info s"Service container handler: ${annotation.name()} " +
                     s"version ${annotation.version()}"
            (annotation.name(), annotation, clazz)
        }
    private val currentContainers =
        allContainers.aggregate(Map[String, (Int, Class[_])]())((map, entry) => {
            map get entry._1 match {
                case Some((version, _)) if version < entry._2.version() =>
                    map + (entry._1 -> (entry._2.version(), entry._3))
                case None => map + (entry._1 -> (entry._2.version(), entry._3))
                case _ => map
            }
        }, _ ++ _)

    private val currentInstances =
        currentContainers map { entry =>
            val name = entry._1
            val instance = injector.getInstance(entry._2._2)
                                   .asInstanceOf[ContainerHandler]
            (name, (entry._2._1, instance))
        }

    @throws[NoSuchElementException]
    def get(name: String): ContainerHandler = {
        currentInstances get name match {
            case Some((version, instance)) =>
                log debug s"Container $name handled by ${instance.getClass} " +
                          s"version $version"
                instance
            case None => throw new NoSuchElementException(name)
        }
    }

    def all = allContainers

    def current = currentInstances

}
