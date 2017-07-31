/*
 * Copyright 2017 Midokura SARL
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

package org.midonet.cluster

import java.io.File

import scala.collection.JavaConverters.iterableAsScalaIterableConverter

import com.google.common.net.HostAndPort
import com.typesafe.config.{Config, ConfigException}

import org.midonet.cluster.services.endpoint.EndpointDescriptor

package object conf {

    /** Create a new prefix based on a parent prefix and the current one */
    def createPrefixFromParent(parentPrefix: String, prefix: String): String =
        (if (parentPrefix == null) "" else parentPrefix + ".") + prefix

    /** Get a string if the configuration entry is not empty, or from
      * the specified alternative source otherwise (default to null and thus
      * None as a result) */
    def optionString(conf: Config, path: String,
                     alternativeSrc: => String = null): Option[String] = {
        lazy val alternativeOption = Option(alternativeSrc).filter(_.nonEmpty)

        if (conf.hasPath(path))
            Some(conf.getString(path)).filter(_.nonEmpty)
                .orElse(alternativeOption)
        else
            alternativeOption
    }

    /** Get a host and port spec, if not empty */
    def optionHostAndPort(conf: Config, path: String): Option[HostAndPort] =
        if (conf.hasPath(path)) conf.getString(path) match {
            case empty: String if empty.isEmpty => None
            case hostPort => Some(HostAndPort.fromString(hostPort))
        } else None

    /** Get a file if the configuration entry points to an existing directory.
      * If no directory is provided in the configuration but alternative
      * directories are provided, return the first one of those that exists.
      * If no directories exist, return None.
      *
      * NOTE: If a directory is provided by the configuration, the default
      *       directories WILL NOT be tried, even if the provided directory
      *       does not exist.
      *
      * @param conf Configuration to read from.
      * @param path Path to the configuration entry whose value we want to read.
      * @param alternativeDirs Alternative directories to check if one is not
      *                        provided.
      * @return An option containing the first existing directory found or None
      *         if no directory exists.
      */
    def optionExistingDir(conf: Config, path: String,
                          alternativeDirs: List[String] = Nil)
    : Option[File] = {
        optionString(conf, path).fold(alternativeDirs.toStream)(p => Stream(p))
            .map(new File(_)).find(_.isDirectory)
    }

    /** Get a list of host and port specs, or an empty list */
    def listHostAndPort(conf: Config, path: String): List[HostAndPort] =
        if (conf.hasPath(path)) try {
            conf.getStringList(path).asScala.filterNot(_.isEmpty)
                .map(HostAndPort.fromString).toList
        } catch {
            case e: ConfigException.WrongType if conf.getString(path).isEmpty =>
                List.empty
        } else
            List.empty

    /** Get an endpoint descriptor from an endpoint description string */
    def endpointDescriptor(conf: Config, path: String)
    : Option[EndpointDescriptor] =
        optionString(conf, path) match {
            case None => None
            case Some(empty) if empty.isEmpty => None
            case nonEmpty => EndpointDescriptor(nonEmpty)
        }
}

