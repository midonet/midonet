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

package org.midonet.cluster.auth

import com.google.inject.AbstractModule
import com.typesafe.config.Config
import com.typesafe.scalalogging.Logger

import org.apache.commons.configuration.ConfigurationException

import org.midonet.cluster.AuthConfig

class AuthModule(config: AuthConfig, log: Logger)
    extends AbstractModule {

    override def configure(): Unit = {
        bind(classOf[AuthService]).toInstance(newAuthService)
    }

    private def newAuthService: AuthService = {
        val authProvider = config.provider
        log.info("Authentication provider: {}", authProvider)

        try {

            val clazz = Class.forName(authProvider)
            try {
                val constructor = clazz.getDeclaredConstructor(classOf[Config])
                constructor.setAccessible(true)
                constructor.newInstance(config.conf).asInstanceOf[AuthService]
            } catch {
                case _: NoSuchMethodException =>
                    val constructor = clazz.getDeclaredConstructor()
                    constructor.setAccessible(true)
                    constructor.newInstance().asInstanceOf[AuthService]
            }
        } catch {
            case e: ClassNotFoundException =>
                throw new ConfigurationException(
                    s"Cannot find authentication provider $authProvider in" +
                    s"current class path", e)
            case e @ (_: InstantiationException |
                      _: IllegalAccessException |
                      _: NoSuchMethodException) =>
                throw new ConfigurationException(
                    s"Authentication provider $authProvider must expose a " +
                    s"either a default constructor or a constructor taking " +
                    s"a single configuration argument", e)
            case e: SecurityException =>
                throw new ConfigurationException(
                    s"Failed to create an instance of the authentication " +
                    s"provider $authProvider", e)
            case e: ClassCastException =>
                throw new ConfigurationException(
                    s"Authentication provider $authProvider is not a valid", e)
        }
    }

}
