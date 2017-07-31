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

package org.midonet.cluster.conf

import java.net.InetAddress
import java.util.Properties

import scala.util.Try

import com.typesafe.config.Config

import org.midonet.cluster.conf.EndpointConfig.SSLSource.SSLSource

/**
  * Configuration for Endpoint service.
  */
class EndpointConfig(val conf: Config,
                     systemProperties: Properties = System.getProperties) {
    import EndpointConfig.{DefaultKeystoreLocation, GlobalPrefix, SSLSource}

    /** Is the endpoint service enabled? */
    def enabled: Boolean = conf.getBoolean(s"$GlobalPrefix.enabled")

    /** Advertised host for the service */
    def serviceHost: Option[String] =
        optionString(conf, s"$GlobalPrefix.service.host").orElse {
            Try(InetAddress.getLocalHost.getHostAddress).toOption
        }

    /** Advertised port for the service */
    def servicePort: Int = conf.getInt(s"$GlobalPrefix.service.port")

    /** Interface to which we should bind the endpoint service */
    def serviceInterface: Option[String] =
        optionString(conf, s"$GlobalPrefix.service.interface")

    /** Whether SSL is enabled or not */
    def authSslEnabled: Boolean =
        conf.getBoolean(s"$GlobalPrefix.auth.ssl.enabled")

    /** Source for SSL context. Defaults to self-signed certificate */
    def sslSource: SSLSource =
        SSLSource(optionString(conf, s"$GlobalPrefix.auth.ssl.source"))

    /** Location of keystore for SSL auth using keystore */
    def keystoreLocation: Option[String] =
        optionString(conf, s"$GlobalPrefix.auth.ssl.keystore_path",
            systemProperties.getProperty("midonet.keystore_path",
                                         DefaultKeystoreLocation))

    /** Keystore password for SSL auth using keystore */
    def keystorePassword: Option[String] =
        optionString(conf, s"$GlobalPrefix.auth.ssl.keystore_password",
            systemProperties.getProperty("midonet.keystore_password"))

    /** Location of certificate for SSL auth using certificate */
    def certificateLocation: Option[String] =
        optionString(conf, s"$GlobalPrefix.auth.ssl.certificate_path",
            systemProperties.getProperty("midonet.certificate_path"))

    /** Location of private key for SSL auth using certificate */
    def privateKeyLocation: Option[String] =
        optionString(conf, s"$GlobalPrefix.auth.ssl.privatekey_path",
            systemProperties.getProperty("midonet.privatekey_path"))

    /** Password of the private key for SSL auth using certificate */
    def privateKeyPassword: Option[String] =
        optionString(conf, s"$GlobalPrefix.auth.ssl.privatekey_password",
            systemProperties.getProperty("midonet.privatekey_password"))
}

object EndpointConfig {
    final val LocalPrefix = "endpoint"
    final val GlobalPrefix = createPrefixFromParent("cluster",
                                                    LocalPrefix)

    final val DefaultKeystoreLocation = "/etc/midonet-cluster/ssl/midonet.jks"

    object SSLSource extends Enumeration {
        type SSLSource = Value
        val AutoSigned = Value("autosigned")
        val Keystore = Value("keystore")
        val Certificate = Value("certificate")

        def apply(str: Option[String]) = {
            SSLSource.withName(str.getOrElse(AutoSigned.toString))
        }
    }
}
