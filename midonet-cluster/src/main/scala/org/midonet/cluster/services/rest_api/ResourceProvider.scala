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

package org.midonet.cluster.services.rest_api

import javax.ws.rs.Path

import scala.collection.JavaConverters._

import com.google.inject.servlet.RequestScoped
import com.typesafe.scalalogging.Logger

import org.reflections.Reflections

import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.rest_api.annotation.ApiResource

class ResourceProvider(log: Logger) {

    log info "Scanning classpath for pluggable API resources"
    private val reflections =
        new Reflections("org.midonet.cluster.services.rest_api",
                        "org.midonet.cluster.rest_api")
    private val annotated =
        reflections.getTypesAnnotatedWith(classOf[ApiResource]).asScala
    private val allResources =
        annotated filter { clazz =>
            val annotated =
                (clazz.getAnnotation(classOf[RequestScoped]) ne null) &&
                (clazz.getAnnotation(classOf[Path]) ne null)
            if (!annotated) {
                log warn s"API resource class ${clazz.getName} found but " +
                         s"missing either @RequestScoped or @Path annotation"
            }
            annotated
        } map { clazz =>
            val apiAnnotation = clazz.getAnnotation(classOf[ApiResource])
            val pathAnnotation = clazz.getAnnotation(classOf[Path])
            log info s"API endpoint found: /${pathAnnotation.value} version  " +
                     apiAnnotation.version
            (pathAnnotation.value, apiAnnotation, clazz)
        }
    private val currentResources =
        allResources.aggregate(Map[String, (Int, Class[_])]())((map, entry) => {
            map get entry._1 match {
                case Some((version, _)) if version < entry._2.version() =>
                    map + (entry._1 -> (entry._2.version(), entry._3))
                case None => map + (entry._1 -> (entry._2.version(), entry._3))
                case _ => map
            }
        }, _ ++ _)

    def get(name: String): Class[_] = {
        currentResources get name match {
            case Some((version, clazz)) =>
                log.debug("Request for {} handled by {} version {}",
                          name, clazz, Int.box(version))
                clazz
            case None =>
                throw new NotFoundHttpException(s"Root resource $name not found")
        }
    }

    def all = allResources.toSeq.sortBy(_._1)
                          .asInstanceOf[Seq[(String, ApiResource, Class[Object])]]
                          .asJavaCollection

}
