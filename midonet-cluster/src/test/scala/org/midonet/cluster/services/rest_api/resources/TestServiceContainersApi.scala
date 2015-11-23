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
import javax.ws.rs.core.Response.Status._
import javax.ws.rs.core.UriBuilder

import scala.collection.JavaConversions._

import com.sun.jersey.api.client.WebResource
import com.sun.jersey.test.framework.JerseyTest
import org.eclipse.jetty.http.HttpStatus.METHOD_NOT_ALLOWED_405
import org.junit.{Before, Test}
import org.scalatest.ShouldMatchers

import org.midonet.client.dto.{DtoApplication, DtoError, DtoRouter, DtoRouterPort}
import org.midonet.cluster.HttpRequestChecks
import org.midonet.cluster.rest_api.ResourceUris._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.rest_api.{DtoWebResource, FuncTest, Topology}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
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
        val scgUri = postAndAssertOk(scg, scgBase.getURI,
                        APPLICATION_SERVICE_CONTAINER_GROUP_JSON)
        getAndAssertOk[ServiceContainerGroup](scgUri,
                        APPLICATION_SERVICE_CONTAINER_GROUP_JSON)
    }

    def makeRouter(): Router = {
        val r = new Router()
        r.id = UUID.randomUUID()
        r.name = "test-" + r.id.toString
        r.setBaseUri(app.getUri)
        val rUri = postAndAssertOk(r, app.getRouters,
                                   APPLICATION_ROUTER_JSON_V3)
        getAndAssertOk[Router](rUri, APPLICATION_ROUTER_JSON_V3)
    }

    def makePort(onRouter: Router): Port = {
        val p = new RouterPort
        p.id = UUID.randomUUID()
        p.setBaseUri(app.getUri)
        p.portAddress = IPv4Addr.random.toString
        p.networkAddress = p.portAddress
        p.networkLength = 12
        val uri = postAndAssertOk(p, onRouter.getPorts,
                                  APPLICATION_PORT_V3_JSON)
        getAndAssertOk[RouterPort](uri, APPLICATION_PORT_V3_JSON)
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
        postAndAssertStatus(sc, scBase.getURI,
                            APPLICATION_SERVICE_CONTAINER_JSON,
                            NOT_FOUND).getEntity(classOf[DtoError])

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
        val err = postAndAssertStatus(sc, scBase.getURI,
                                      APPLICATION_SERVICE_CONTAINER_JSON,
                                      BAD_REQUEST).getEntity(classOf[DtoError])
        err.getViolations should have size 1
        err.getViolations.head.get("property") shouldBe "serviceGroupId"

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
        postAndAssertOk(sc, scBase.getURI, APPLICATION_SERVICE_CONTAINER_JSON)
        sc.configurationId = UUID.randomUUID()
        putAndAssertStatus(sc, APPLICATION_SERVICE_CONTAINER_JSON,
                           METHOD_NOT_ALLOWED_405)
        putAndAssertStatus(scg, APPLICATION_SERVICE_CONTAINER_JSON,
                           METHOD_NOT_ALLOWED_405)
    }

    @Test
    def testCRUD(): Unit = {
        val router = makeRouter()
        val port = makePort(router)
        val scg = makeServiceContainerGroup()

        listAndAssertOk[ServiceContainer](
            scg.getServiceContainers,
            APPLICATION_SERVICE_CONTAINER_COLLECTION_JSON) shouldBe empty

        val sc = new ServiceContainer
        sc.id = UUID.randomUUID()
        sc.portId = port.id
        sc.serviceGroupId = scg.id
        sc.serviceType = "IPSEC"
        sc.configurationId = UUID.randomUUID()
        sc.setBaseUri(app.getUri)

        val expectUri= scBase.getUriBuilder.path(sc.id.toString).build()

        // Make a service container
        val scUri = postAndAssertOk(sc, scBase.getURI,
                                    APPLICATION_SERVICE_CONTAINER_JSON)

        // Check that it's there
        val dtoSc = getAndAssertOk[ServiceContainer](scUri,
                                    APPLICATION_SERVICE_CONTAINER_JSON)
        dtoSc.getUri shouldBe scUri

        // And the linked router port is expected to have a backref
        val dtoPort = get[DtoRouterPort](dtoSc.getPort,
                                         APPLICATION_PORT_V3_JSON)
        dtoPort.getId shouldBe port.id
        dtoPort.getDeviceId shouldBe router.id
        dtoPort.getServiceContainer shouldBe scUri

        // The SC group should also link to the SC
        // Check that it's there
        val dtoScg = getAndAssertOk[ServiceContainerGroup](scg.getUri,
                                     APPLICATION_SERVICE_CONTAINER_GROUP_JSON)
        dtoScg.serviceContainerIds should have size 1
        dtoScg.serviceContainerIds.head shouldBe dtoSc.id

        // It's also returned when listing SCs in the SCG
        val nestedScs = listAndAssertOk[ServiceContainer](
            scg.getServiceContainers,
            APPLICATION_SERVICE_CONTAINER_COLLECTION_JSON)
        nestedScs should have size 1
        nestedScs.head shouldBe dtoSc

        // Finally, ensure that the SC is accessible at the root path
        get[ServiceContainer](expectUri,
            APPLICATION_SERVICE_CONTAINER_JSON) shouldBe sc
    }

}