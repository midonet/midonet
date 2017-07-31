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

package org.midonet.cluster.services.endpoint.conf

import java.util.Properties

import com.typesafe.config.{Config, ConfigFactory}

import org.midonet.cluster.EndpointConfig
import org.midonet.conf.MidoTestConfigurator

/**
  * Utilities for generating configurations
  */
object ConfigGenerator {
    private lazy val systemProperties = System.getProperties

    abstract class ConfigBase[C] {
        def fromString(confStr: String): C =
            fromConfig(createTypesafeConfig(confStr))

        def fromConfig(conf: Config): C

        protected def createTypesafeConfig(str: String): Config =
            ConfigFactory.parseString(str)
                .withFallback(MidoTestConfigurator.forClusters())
    }

    abstract class ConfigBaseWithProperties[C] extends ConfigBase[C] {
        def fromString(confStr: String,
                       props: Properties = systemProperties): C =
            fromConfig(createTypesafeConfig(confStr), props)

        def fromConfig(conf: Config, props: Properties = systemProperties): C
    }

    object Endpoint extends ConfigBaseWithProperties[EndpointConfig] {
        import EndpointConfig.GlobalPrefix
        def genConfString(enabled: Boolean = true,
                          serviceHost: String = "localhost",
                          servicePort: Int = 40001,
                          serviceInterface: String = "",
                          authSslEnabled: Boolean = true,
                          sslSource: String = "autosigned",
                          keystoreLocation: String = "/keystore",
                          keystorePassword: String = "password",
                          certificateLocation: String = "/certificate",
                          privateKeyLocation: String = "/privateKey",
                          privateKeyPassword: String = "password") =
            s"""
               |$GlobalPrefix.enabled=$enabled
               |$GlobalPrefix.service.host="$serviceHost"
               |$GlobalPrefix.service.port=$servicePort
               |$GlobalPrefix.service.interface="$serviceInterface"
               |$GlobalPrefix.auth.ssl.enabled=$authSslEnabled
               |$GlobalPrefix.auth.ssl.source="$sslSource"
               |$GlobalPrefix.auth.ssl.keystore_path="$keystoreLocation"
               |$GlobalPrefix.auth.ssl.keystore_password="$keystorePassword"
               |$GlobalPrefix.auth.ssl.certificate_path="$certificateLocation"
               |$GlobalPrefix.auth.ssl.privatekey_path="$privateKeyLocation"
               |$GlobalPrefix.auth.ssl.privatekey_password="$privateKeyPassword"
             """.stripMargin

        override def fromConfig(conf: Config): EndpointConfig =
            new EndpointConfig(conf)

        override def fromConfig(conf: Config,
                                props: Properties): EndpointConfig =
            new EndpointConfig(conf, props)
    }
}
