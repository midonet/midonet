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

import scala.collection.JavaConversions._

import com.sun.jersey.api.client.WebResource
import com.sun.jersey.test.framework.JerseyTest
import org.junit.{Before, Test}
import org.scalatest.ShouldMatchers

import org.midonet.client.dto.{DtoApplication, DtoError}
import org.midonet.cluster.HttpRequestChecks
import org.midonet.cluster.rest_api.ResourceUris._
import org.midonet.cluster.rest_api.models.{Router, ServiceContainer, ServiceContainerGroup}
import org.midonet.cluster.rest_api.rest_api.{DtoWebResource, FuncTest, Topology}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

class TestServiceContainerResource extends JerseyTest(FuncTest.getBuilder.build())
                                           with HttpRequestChecks
                                           with ShouldMatchers {

    private var topology: Topology = null
    private var app: DtoApplication = null
    private var scBase: WebResource = null
    private var scgBase: WebResource = null

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
        scg.serviceType = ServiceContainerGroup.Service.IPSEC
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

    @Test
    def testCreateWithNonExistentServiceGroup(): Unit = {
        val router = makeRouter()
        val sc = new ServiceContainer
        sc.id = UUID.randomUUID()
        sc.routerId = router.id
        sc.serviceGroupId = UUID.randomUUID()
        sc.setBaseUri(app.getUri)
        postAndAssertStatus(sc, router.getServiceContainers,
                            APPLICATION_SERVICE_CONTAINER_JSON,
                            NOT_FOUND).getEntity(classOf[DtoError])

    }

    @Test
    def testCreateWithoutServiceGroupId(): Unit = {
        val router = makeRouter()
        val sc = new ServiceContainer
        sc.id = UUID.randomUUID()
        sc.routerId = router.id
        sc.setBaseUri(app.getUri)
        val err = postAndAssertStatus(sc, router.getServiceContainers,
                                      APPLICATION_SERVICE_CONTAINER_JSON,
                                      BAD_REQUEST).getEntity(classOf[DtoError])
        err.getViolations should have size 1
        err.getViolations.head.get("property") shouldBe "serviceGroupId"

    }

    @Test
    def testCRUD(): Unit = {
        val router = makeRouter()
        val scg = makeServiceContainerGroup()
        val sc = new ServiceContainer
        sc.id = UUID.randomUUID()
        sc.routerId = router.id
        sc.serviceGroupId = scg.id
        sc.setBaseUri(app.getUri)
        val scUri = postAndAssertOk(sc, router.getServiceContainers,
                                    APPLICATION_SERVICE_CONTAINER_JSON)
        var gotSc = getAndAssertOk[ServiceContainer](scUri,
                                    APPLICATION_SERVICE_CONTAINER_JSON)
        val device = getAndAssertOk[Router](gotSc.getDevice,
                                    APPLICATION_ROUTER_JSON_V3)
        device.id shouldBe router.id

        // Should also be accessible as a root resource


        gotSc = get[ServiceContainer](scBase.getUriBuilder
                                            .path(sc.id.toString)
                                            .build(),
                                    APPLICATION_SERVICE_CONTAINER_JSON)
        gotSc.id shouldBe sc.id
    }

}