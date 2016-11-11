/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.midolman.haproxy

import scala.sys.process._

import org.junit.runner.RunWith
import org.scalatest.concurrent.Eventually
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, ShouldMatchers}
import org.slf4j.LoggerFactory


@RunWith(classOf[JUnitRunner])
class HaproxyHelperTest extends FeatureSpec
                        with Eventually
                        with ShouldMatchers {

    val log = LoggerFactory.getLogger(classOf[HaproxyHelperTest])

    def verifyIpNetns(name: String, iface: String): Unit = {
        eventually {
            val ns = "ip netns".!!
            (ns contains name) shouldBe true
            val links = s"ip netns exec $name ip link".!!
            (ns contains iface) shouldBe true
        }
    }

    def verifyNoIpNetns(name: String, iface: String): Unit = {
        eventually {
            val ns = "ip netns".!!
            (ns contains name) shouldBe true
            val links = s"ip link".!!
            (ns contains iface) shouldBe true
        }
    }

    val haproxyScript = "../midolman/src/lib/midolman/service_containers/haproxy/haproxy-helper"

    def makens(name: String, iface: String) =
        s"$haproxyScript makens $name $iface".!!

    def cleanns(name: String) =
        s"$haproxyScript cleanns $name".!!

    feature("creates namespace") {
        scenario("namespace is created") {
            val name = "TEST"
            val ifaceName = "iface"
            makens(name, ifaceName)
            verifyIpNetns(name, ifaceName)

            cleanns(name)
            verifyNoIpNetns(name, ifaceName)
        }
    }
}
