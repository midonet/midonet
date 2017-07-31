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

import java.net.InetAddress
import java.util.Properties
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.Duration
import scala.util.Random

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.EndpointConfig.SSLSource
import org.midonet.cluster.EndpointConfig.DefaultKeystoreLocation
import org.midonet.cluster.services.endpoint.ClusterTestUtils

@RunWith(classOf[JUnitRunner])
class EndpointConfigTest extends FeatureSpec with Matchers {

    private val random = new Random()
    private val timeout = Duration(5, TimeUnit.SECONDS)

    private def rndPort = 40000 + random.nextInt(10000)

    feature("base configuration") {
        scenario("setting enabled to false") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    enabled = false)
            )
            conf.enabled shouldBe false
        }
        scenario("enabled service") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    enabled = true)
            )
            conf.enabled shouldBe true
        }
    }
    feature("service configuration") {
        scenario("endpoint config") {
            val port = rndPort
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    servicePort = port)
            )
            conf.servicePort shouldBe port
        }
        scenario("ssl disabled") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    authSslEnabled = false)
            )
            conf.authSslEnabled shouldBe false
        }
        scenario("ssl enabled and default ssl source") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    authSslEnabled = true,
                    sslSource = "")
            )
            conf.authSslEnabled shouldBe true
            conf.sslSource shouldBe SSLSource.AutoSigned
        }
        scenario("ssl source - auto signed") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    sslSource = "autosigned")
            )
            conf.sslSource shouldBe SSLSource.AutoSigned
        }
        scenario("ssl source - keystore") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    sslSource = "keystore")
            )
            conf.sslSource shouldBe SSLSource.Keystore
        }
        scenario("ssl source - certificate") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    sslSource = "certificate")
            )
            conf.sslSource shouldBe SSLSource.Certificate
        }
        scenario("ssl source - unknown") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    sslSource = "someOtherThing")
            )

            a [java.util.NoSuchElementException] should be thrownBy
                conf.sslSource
        }
        scenario("ssl keystore location from config") {
            val pathToKeyStore = "/some/path/to/keystore"

            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    keystoreLocation = pathToKeyStore)
            )

            conf.keystoreLocation shouldBe Some(pathToKeyStore)
        }
        scenario("ssl keystore location from system properties") {
            val pathToKeyStore = "/some/path/to/keystore"
            val systemProperties = new Properties
            systemProperties.put("midonet.keystore_path",
                                 pathToKeyStore)
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    keystoreLocation = ""),
                systemProperties
            )

            conf.keystoreLocation shouldBe Some(pathToKeyStore)
        }
        scenario("ssl keystore location not specified") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    keystoreLocation = "")
            )
            conf.keystoreLocation shouldBe Some(DefaultKeystoreLocation)
        }
        scenario("ssl keystore password from config") {
            val password = "BaconIsAwesome"

            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    keystorePassword = password)
            )

            conf.keystorePassword shouldBe Some(password)
        }
        scenario("ssl keystore password from system properties") {
            val password = "BaconIsAwesome"
            val systemProperties = new Properties
            systemProperties.put("midonet.keystore_password",
                                 password)
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    keystorePassword = ""),
                systemProperties
            )

            conf.keystorePassword shouldBe Some(password)
        }
        scenario("ssl keystore password not specified") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    keystorePassword = "")
            )

            conf.keystorePassword shouldBe None
        }
        scenario("ssl certificate from config") {
            val certificatePath = "/some/path/to/certificate"
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    certificateLocation = certificatePath)
            )

            conf.certificateLocation shouldBe Some(certificatePath)
        }
        scenario("ssl certificate from system properties") {
            val certificatePath = "/some/path/to/certificate"
            val systemProperties = new Properties
            systemProperties.put("midonet.certificate_path",
                                 certificatePath)
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    certificateLocation = ""),
                systemProperties
            )

            conf.certificateLocation shouldBe Some(certificatePath)
        }
        scenario("ssl certificate not specified") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    certificateLocation = "")
            )

            conf.certificateLocation shouldBe None
        }
        scenario("ssl private key from config") {
            val privateKeyPath = "/some/path/to/privateKey"

            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    privateKeyLocation = privateKeyPath)
            )

            conf.privateKeyLocation shouldBe Some(privateKeyPath)
        }
        scenario("ssl private key from system properties") {
            val privateKeyPath = "/some/path/to/privateKey"
            val systemProperties = new Properties
            systemProperties.put("midonet.privatekey_path",
                                 privateKeyPath)

            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    privateKeyLocation = ""),
                systemProperties
            )

            conf.privateKeyLocation shouldBe Some(privateKeyPath)
        }
        scenario("ssl private key not specified") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    privateKeyLocation = "")
            )

            conf.privateKeyLocation shouldBe None
        }
        scenario("ssl private key password from config") {
            val privateKeyPassword = "BaconIsAwesome"

            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    privateKeyPassword = privateKeyPassword)
            )

            conf.privateKeyPassword shouldBe Some(privateKeyPassword)
        }
        scenario("ssl private key password from system properties") {
            val privateKeyPassword = "BaconIsAwesome"
            val systemProperties = new Properties
            systemProperties.put("midonet.privatekey_password",
                                 privateKeyPassword)
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    privateKeyPassword = ""),
                systemProperties
            )

            conf.privateKeyPassword shouldBe Some(privateKeyPassword)
        }
        scenario("ssl private key password not specified") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    privateKeyPassword = "")
            )

            conf.privateKeyPassword shouldBe None
        }
    }
    feature("service discovery") {
        scenario("explicit values") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    serviceHost = "1.2.3.4")
            )

            conf.serviceHost shouldBe Some("1.2.3.4")
        }
        scenario("disabled discovery") {
            val conf = ConfigGenerator.Endpoint.fromString(
                ConfigGenerator.Endpoint.genConfString(
                    serviceHost = "1.2.3.4")
            )
            conf.serviceHost shouldBe Some("1.2.3.4")
        }
        scenario("host auto-detection") {
            ClusterTestUtils.hostSelfDetectionTest {
                val conf = ConfigGenerator.Endpoint.fromString(
                    ConfigGenerator.Endpoint.genConfString(
                        serviceHost = "")
                )
                val myAddr = conf.serviceHost
                myAddr.isDefined shouldBe true
                myAddr.get.nonEmpty shouldBe true
                InetAddress.getByName(myAddr.get)
                    .isReachable(timeout.toMillis.toInt) shouldBe true
            }
        }
    }
}
