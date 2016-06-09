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

package org.midonet.cluster.services.rest_api.resources

import java.util.UUID

import javax.ws.rs.core.Response.Status
import javax.ws.rs.core.Response.Status._

import scala.collection.JavaConversions._

import com.sun.jersey.api.client.WebResource
import com.sun.jersey.test.framework.JerseyTest

import org.junit.{Before, Test}
import org.scalatest.ShouldMatchers

import org.midonet.client.dto.{DtoApplication, DtoError, DtoRouterPort}
import org.midonet.cluster.HttpRequestChecks
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.rest_api.ResourceUris._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.rest_api.{DtoWebResource, FuncTest, Topology}
import org.midonet.packets.IPv4Addr

class TestServiceContainersApi extends JerseyTest(FuncTest.getBuilder.build())
                                       with HttpRequestChecks
                                       with ShouldMatchers {

    private var topology: Topology = _
    private var app: DtoApplication = _
    private var scBase: WebResource = _
    private var scgBase: WebResource = _

    @Before
    override def setUp(): Unit = {
        val dtoWebResource = new DtoWebResource(resource())
        topology = new Topology.Builder(dtoWebResource).build()
        app = topology.getApplication
        scBase = resource().path(SERVICE_CONTAINERS)
        scgBase = resource().path(SERVICE_CONTAINER_GROUPS)
    }

    def makeServiceContainerGroup(): ServiceContainerGroup = {
        val scg = new ServiceContainerGroup
        scg.id = UUID.randomUUID()
        scg.setBaseUri(app.getUri)
        val scgUri = postAndAssertOk(scg, scgBase.getURI)
        getAndAssertOk[ServiceContainerGroup](scgUri)
    }

    def makeRouter(): Router = {
        val r = new Router()
        r.id = UUID.randomUUID()
        r.name = "test-" + r.id.toString
        r.setBaseUri(app.getUri)
        val rUri = postAndAssertOk(r, app.getRouters)
        getAndAssertOk[Router](rUri)
    }

    def makePort(onRouter: Router): Port = {
        val p = new RouterPort
        p.id = UUID.randomUUID()
        p.routerId = onRouter.id
        p.setBaseUri(app.getUri)
        p.portAddress = IPv4Addr.random.toString
        p.networkAddress = p.portAddress
        p.networkLength = 12
        val uri = postAndAssertOk(p, onRouter.getPorts)
        getAndAssertOk[RouterPort](uri)
    }

    @Test
    def testCreateWithNonExistentServiceGroup(): Unit = {
        val router = makeRouter()
        val port = makePort(router)
        val sc = new ServiceContainer
        sc.id = UUID.randomUUID()
        sc.portId = port.id
        sc.serviceType = "MEH"
        sc.serviceGroupId = UUID.randomUUID()
        sc.setBaseUri(app.getUri)
        postAndAssertStatus(sc, scBase.getURI, NOT_FOUND)
            .getEntity(classOf[DtoError])

    }

    @Test
    def testCreateWithoutServiceGroupId(): Unit = {
        val router = makeRouter()
        val port = makePort(router)
        val sc = new ServiceContainer
        sc.id = UUID.randomUUID()
        sc.portId = port.id
        sc.serviceType = "MOH"
        sc.setBaseUri(app.getUri)
        postAndAssertOk(sc, scBase.getURI)
    }

    @Test
    def testUpdateNotAllowed(): Unit = {
        val sc = new ServiceContainer
        val router = makeRouter()
        val port = makePort(router)
        val scg = makeServiceContainerGroup()
        sc.id = UUID.randomUUID()
        sc.configurationId = UUID.randomUUID()
        sc.portId = port.id
        sc.serviceType = "IPSEC"
        sc.serviceGroupId = scg.id
        sc.setBaseUri(app.getUri)
        postAndAssertOk(sc, scBase.getURI)
        sc.configurationId = UUID.randomUUID()
        putAndAssertStatus(sc, 405)
        putAndAssertStatus(scg, 405)
    }

    @Test
    def testCRUD(): Unit = {
        val router = makeRouter()
        val port = makePort(router)
        val scg = makeServiceContainerGroup()

        listAndAssertOk[ServiceContainer](
            scg.getServiceContainers) shouldBe empty

        val sc = new ServiceContainer
        sc.id = UUID.randomUUID()
        sc.portId = port.id
        sc.serviceGroupId = scg.id
        sc.serviceType = "IPSEC"
        sc.configurationId = UUID.randomUUID()
        sc.statusCode = Code.STOPPED
        sc.setBaseUri(app.getUri)

        val expectUri= scBase.getUriBuilder.path(sc.id.toString).build()

        /* Make a service container into a group */
        val scUri = postAndAssertOk(sc, scg.getServiceContainers)

        // Check that it's there
        val dtoSc = getAndAssertOk[ServiceContainer](scUri)
        dtoSc.getUri shouldBe scUri

        // And the linked router port is expected to have a backref
        val dtoPort = getAndAssertOk[RouterPort](dtoSc.getPort)
        dtoPort.id shouldBe port.id
        dtoPort.getDeviceId shouldBe router.id
        dtoPort.getServiceContainer shouldBe scUri

        // The SC group should also link to the SC
        // Check that it's there
        val dtoScg = getAndAssertOk[ServiceContainerGroup](scg.getUri)
        dtoScg.serviceContainerIds should have size 1
        dtoScg.serviceContainerIds.head shouldBe dtoSc.id

        // It's also returned when listing SCs in the SCG
        val nestedScs = listAndAssertOk[ServiceContainer](
            scg.getServiceContainers)
        nestedScs should have size 1
        nestedScs.head shouldBe dtoSc

        // Finally, ensure that the SC is accessible at the root path
        get[ServiceContainer](expectUri) shouldBe sc
    }

}
