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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.services.discovery.FakeDiscovery
import org.midonet.cluster.services.endpoint.EndpointTestUtils
import org.midonet.cluster.services.endpoint.conf.ConfigGenerator
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.PortProvider

@RunWith(classOf[JUnitRunner])
class EndpointUserRegistrarTest extends FeatureSpec with Matchers
                                        with BeforeAndAfter
                                        with CuratorTestFramework {

    import EndpointUserRegistrarTest.TestHost

    private var registrar: EndpointUserRegistrar = _
    private var discovery: FakeDiscovery = _
    private var port: Int = _

    before {
        discovery = new FakeDiscovery
        port = PortProvider.getPort
        val conf = ConfigGenerator.Endpoint.fromString(
            EndpointTestUtils.genConfString(host = TestHost, port = port))
        registrar = new EndpointUserRegistrar(conf, discovery)
    }

    def createUser(path: String) =
        EndpointTestUtils.BasicTestEndpointUser(path = path)

    def basicRegisteredPathsFixture = new {
        val user1 = createUser("/path1")
        registrar.register(user1)

        val user2 = createUser("/path2")
        registrar.register(user2)

        val user3 = createUser("/path1/subpath1/subsubpath1")
        registrar.register(user3)

        val numUsers = 3
    }

    feature("Endpoint channel registrar") {
        scenario("registering users with valid paths") {
            noException should be thrownBy
                registrar.register(createUser("/path1"))

            noException should be thrownBy
                registrar.register(createUser("/path1/subpath1"))
            // This should be equivalent to '/path2'
            noException should be thrownBy
                registrar.register(createUser("/path1/../path2"))
            // This should be equivalent to '/path1/subpath2'
            noException should be thrownBy
                registrar.register(createUser("/path1/./subpath2"))
        }

        scenario("registering users with invalid paths") {
            a [PathNotAbsoluteException] should be thrownBy
                registrar.register(createUser("path1"))

            a [PathNotAbsoluteException] should be thrownBy
                registrar.register(createUser("path1/path2"))

            a [PathNormalizationException] should be thrownBy
                registrar.register(createUser("/../path1"))
        }

        scenario("registering duplicate paths") {
            noException should be thrownBy
                registrar.register(createUser("/path1"))

            a [PathAlreadyRegisteredException] should be thrownBy
                registrar.register(createUser("/path1"))
        }

        scenario("finding users by direct path") {
            val p = basicRegisteredPathsFixture

            registrar.findUserForPath(p.user1.endpointPath) shouldBe
                Some(p.user1.endpointPath, p.user1)

            registrar.findUserForPath(p.user2.endpointPath) shouldBe
                Some(p.user2.endpointPath, p.user2)

            registrar.findUserForPath(p.user3.endpointPath) shouldBe
                Some(p.user3.endpointPath, p.user3)
        }

        scenario("finding users by looking at parent paths") {
            val p = basicRegisteredPathsFixture

            val path1SubPath = p.user1.endpointPath + "/subpath1"
            registrar.findUserForPath(path1SubPath) shouldBe
                Some(p.user1.endpointPath, p.user1)

            val path1SubSubSubPath = p.user3.endpointPath + "/subsubsubpath1"
            registrar.findUserForPath(path1SubSubSubPath) shouldBe
                Some(p.user3.endpointPath, p.user3)

            val path2SubSubPath = p.user2.endpointPath + "/subpath1/subsubpath1"
            registrar.findUserForPath(path2SubSubPath) shouldBe
                Some(p.user2.endpointPath, p.user2)
        }

        scenario("finding non-existing channels") {
            basicRegisteredPathsFixture

            registrar.findUserForPath("/") shouldBe None
            registrar.findUserForPath("/path3") shouldBe None
            registrar.findUserForPath("/path3/path6") shouldBe None
        }

        scenario("test service discovery registration") {
            val userA = EndpointTestUtils.BasicTestEndpointUser(
                userName = Option("userA"), prot = "http", path = "/user/userA")
            val userB = EndpointTestUtils.BasicTestEndpointUser(
                userName = Option("userB"), prot = "wss", path = "/userB")
            val userC = EndpointTestUtils.BasicTestEndpointUser(
                userName = Option("userC"), prot = "ws", path = "/u/userC/wow")
            val userD = EndpointTestUtils.BasicTestEndpointUser(
                prot = "ws", path = "/u/userD")

            val users = Set(userA, userB, userC, userD)

            users.foreach(registrar.register)

            val expectedRegisteredServices = users
                .filter(_.name.isDefined)
                .map(u => (u.name.get, new URI(
                    s"${u.protocol(true)}://$TestHost:$port${u.endpointPath}")))

            discovery.registeredServices shouldBe expectedRegisteredServices
        }

        scenario("clearing") {
            val user = EndpointTestUtils.BasicTestEndpointUser(
                userName = Option("user"), prot = "http", path = "/user/user")

            registrar.register(user)

            registrar.findUserForPath(user.endpointPath) shouldBe
                Some(user.endpointPath, user)

            discovery.registeredServices.size shouldBe 1

            registrar.clear()

            registrar.findUserForPath(user.endpointPath) shouldBe
                None

            discovery.registeredServices.size shouldBe 0
        }
    }

}

object EndpointUserRegistrarTest {
    private final val TestHost = "test"
}
