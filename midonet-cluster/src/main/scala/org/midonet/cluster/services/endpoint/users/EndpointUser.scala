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

package org.midonet.cluster.services.endpoint.users

import io.netty.channel.Channel

import org.midonet.minion.Minion

/**
  * Trait for minions that need to register some endpoint with the
  * EndpointService
  */
trait EndpointUser extends Minion {

    /**
      * Name of the user for discovery registration. If left as None, this
      * service will not be registered in service discovery.
      *
      * @return The name of this endpoint user for discovery registration.
      */
    def name: Option[String] = None

    /**
      * Return the path to be assigned to this minion.
      *
      * @return The path assigned to this minion.
      */
    def endpointPath: String

    /**
      * Protocol string generator.
      * @param sslEnabled Whether the endpoint connection has ssl enabled.
      * @return The protocol string to use in this situation.
      */
    def protocol(sslEnabled: Boolean): String

    /**
      * Do some initialization of a netty channel (usually by adding handlers).
      *
      * NOTE: Handlers should be added after the HTTPPathBasedInitializerHandler
      *       otherwise they won't get executed for the first HTTP request (the
      *       one being processed when this is called).
      *
      * @param path Path part of the URL of the first HTTP request handled.
      * @param channel Channel to be initialized.
      */
    def initEndpointChannel(path: String, channel: Channel)
}

