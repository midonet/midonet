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

package org.midonet.containers

import java.util.UUID

import scala.collection.JavaConverters._
import scala.reflect.ClassTag

import com.google.inject.name.Names
import com.google.inject.{AbstractModule, Injector}
import com.typesafe.scalalogging.Logger

import org.reflections.Reflections

/**
  * A container provider scans all classes of type T on the current classpath
  * with specified reflections instance, and annotated with a [[Container]]
  * annotation. The [[Container]] specifies a name and version for each
  * container class, and the provider ensures that the last version of each
  * container type is available for instantiation.
  *
  * A caller can create an instance of the given container type using the
  * `getInstance` method and the container name as specified in the [[Container]]
  * annotation. Instantiation uses Guice dependency injection, and towards this
  * end derived classes of the [[ContainerProvider]] need to provide an
  * implementation of the `injector` method which provides the appropriate
  * dependencies.
  */
abstract class ContainerProvider[T](reflections: Reflections, log: Logger)
                                   (tag: ClassTag[T]) {

    log info s"Scanning classpath for service containers"

    private val annotated =
        reflections.getTypesAnnotatedWith(classOf[Container]).asScala

    protected def injector: Injector

    private val allContainers =
        annotated filter {
            tag.runtimeClass.isAssignableFrom
        } map { clazz =>
            val annotation = clazz.getAnnotation(classOf[Container])
            log info s"Service container: ${annotation.name()} " +
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

    /**
      * Gets a new container instance from the current container provider.
      */
    @throws[Exception]
    def getInstance(name: String): T = {
        currentContainers get name match {
            case Some((_, clazz)) =>
                injector.getInstance(clazz).asInstanceOf[T]
            case None => throw new NoSuchElementException(name)
        }
    }

    /**
      * Gets a new container instance from the current container provider,
      * where the container receives the specified identifier.
      */
    @throws[Exception]
    def getInstance(name: String, id: UUID): T = {
        currentContainers get name match {
            case Some((_, clazz)) =>
                injector.createChildInjector(new AbstractModule {
                    override def configure(): Unit = {
                        bind(classOf[UUID]).annotatedWith(Names.named("id"))
                                           .toInstance(id)
                    }
                }).getInstance(clazz).asInstanceOf[T]
            case None => throw new NoSuchElementException(name)
        }
    }

    /** Returns all container classes that have been loaded by the provider,
      * including those of which that have multiple versions for the same
      * container name.
      */
    def all = allContainers

    /** Returns the current set of container classes. If for the same container
      * name there are multiple classes with different versions, this set
      * includes only the class corresponding to the latest container version.
      */
    def current = currentContainers

}
