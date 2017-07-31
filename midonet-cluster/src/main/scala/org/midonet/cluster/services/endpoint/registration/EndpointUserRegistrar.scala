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

package org.midonet.cluster.services.endpoint.registration

import java.net.URI
import java.util.concurrent.ConcurrentHashMap

import scala.collection.JavaConverters.collectionAsScalaIterableConverter
import scala.util.Try
import scala.util.control.NonFatal

import org.apache.commons.io.FilenameUtils
import org.slf4j.LoggerFactory

import org.midonet.cluster.EndpointConfig
import org.midonet.cluster.services.discovery.{MidonetDiscovery, MidonetServiceHandler}
import org.midonet.cluster.services.endpoint.users.EndpointUser

/**
  * Class responsible for receiving, managing and retrieving endpoint user
  * registration requests.
  */
class EndpointUserRegistrar(endpointConfig: EndpointConfig,
                            discovery: MidonetDiscovery) {
    private val log = LoggerFactory.getLogger(classOf[EndpointUserRegistrar])

    private val pathToUserMap = new ConcurrentHashMap[String, EndpointUser]()
    private val userToDiscoveryHandler =
        new ConcurrentHashMap[EndpointUser, MidonetServiceHandler]()

    /**
      * Registers a new endpoint service user.
      *
      * @param user Endpoint service user
      */
    def register(user: EndpointUser) = {
        val path = user.endpointPath
        val normalizedPath = FilenameUtils.normalizeNoEndSeparator(path, true)

        if (normalizedPath == null) {
            throw new PathNormalizationException(path)
        }

        if (FilenameUtils.getPrefix(normalizedPath).isEmpty) {
            throw new PathNotAbsoluteException(normalizedPath)
        }

        // Put in the map
        if (pathToUserMap.putIfAbsent(normalizedPath, user) != null) {
            // If it was already there, throw exception.
            throw new PathAlreadyRegisteredException(normalizedPath)
        }

        registerInDiscovery(user)
    }

    /**
      * Searches the `pathToCustomizer` mapping for some endpoint user that
      * matches the provided `path` or any of its parents.
      *
      * @param path Path to match.
      * @return Optional Tuple (path, user) containing the matched path and
      *         the respective EndpointUser.
      */
    def findUserForPath(path: String): Option[(String, EndpointUser)] = {
        // Normalize the input path
        var nPath = FilenameUtils.normalizeNoEndSeparator(path, true)

        // Look into parent paths until we find a user or we reach the root
        while (Option(pathToUserMap.get(nPath)).isEmpty &&
               !FilenameUtils.getPath(nPath).isEmpty) {
            nPath = FilenameUtils.getFullPathNoEndSeparator(nPath)
        }

        // Return the user and the matched path if we found it
        Option(pathToUserMap.get(nPath)) match {
            case Some(user) => Some(nPath, user)
            case _ => None
        }
    }

    /**
      * Clears all data managed by this registrar.
      */
    def clear(): Unit = {
        pathToUserMap.clear()
        userToDiscoveryHandler.values.asScala.foreach{ n=>
            Try(n.unregister())
        }
        userToDiscoveryHandler.clear()
    }

    /**
      * Create a URI for an endpoint user
      * @param user The user for which to create the URI
      * @return The created URI
      */
    private def createUri(user: EndpointUser): Option[URI] = {
        endpointConfig.serviceHost.map { serviceHost =>
            new URI(
                user.protocol(endpointConfig.authSslEnabled),
                null,
                serviceHost,
                endpointConfig.servicePort,
                user.endpointPath,
                null, null
            )
        }
    }

    /**
      * Registers this user in service discovery.
      *
      * @param user Endpoint service user
      */
    private def registerInDiscovery(user: EndpointUser) = {
        try {
            user.name.foreach { name =>
                createUri(user).foreach { uri =>
                    userToDiscoveryHandler.put(
                        user,
                        discovery.registerServiceInstance(name, uri)
                    )
                }
            }
        } catch {
            case NonFatal(e) => log.warn(
                s"Unable to register ${user.name} to service discovery", e)
        }
    }

}

class PathNormalizationException(path: String) extends Exception(
    "Error normalizing the provided path: " + path)

class PathNotAbsoluteException(path: String) extends Exception(
    "The provided path is not absolute: " + path)

class PathAlreadyRegisteredException(path: String) extends Exception(
    "The provided path was already registered: " + path)
