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

package org.midonet.cluster.services.rest_api

import java.util.UUID

import javax.ws.rs.core.Response.Status._

import scala.collection.JavaConversions._

import com.sun.jersey.api.client.WebResource

import org.junit.Test
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, Suite}

import org.midonet.client.dto.{DtoError, DtoRouterPort}
import org.midonet.cluster.HttpRequestChecks
import org.midonet.cluster.models.State.ContainerStatus.Code
import org.midonet.cluster.rest_api.ResourceUris._
import org.midonet.cluster.rest_api.models._
import org.midonet.cluster.rest_api.rest_api._
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class ServiceContainerResourceTest extends FuncJerseyTest
                                           with Suite
                                           with BeforeAndAfter
                                           with HttpRequestChecks {

    private val backdoor = FuncTest._injector.getInstance(classOf[TopologyBackdoor])
    private var containerBase: WebResource = _
    private var groupBase: WebResource = _

    before {
        super.setUp()
        val dtoWebResource = new DtoWebResource(resource())
        topology = new Topology.Builder(dtoWebResource).build()
        app = topology.getApplication
        containerBase = resource().path(SERVICE_CONTAINERS)
        groupBase = resource().path(SERVICE_CONTAINER_GROUPS)
    }

    private def createServiceContainerGroup(): ServiceContainerGroup = {
        val group = new ServiceContainerGroup
        group.id = UUID.randomUUID()
        group.setBaseUri(app.getUri)
        val scgUri = postAndAssertOk(group, groupBase.getURI,
                        APPLICATION_SERVICE_CONTAINER_GROUP_JSON)
        getAndAssertOk[ServiceContainerGroup](scgUri,
                        APPLICATION_SERVICE_CONTAINER_GROUP_JSON)
    }

    private def createHost(): UUID = {
        val hostId = UUID.randomUUID()
        backdoor.createHost(hostId, "", Array())
        hostId
    }

    private def createRouter(): Router = {
        val router = new Router()
        router.id = UUID.randomUUID()
        router.name = "test-" + router.id.toString
        router.setBaseUri(app.getUri)
        val rUri = postAndAssertOk(router, app.getRouters,
                                   APPLICATION_ROUTER_JSON_V3)
        getAndAssertOk[Router](rUri, APPLICATION_ROUTER_JSON_V3)
    }

    private def createPort(router: Router): Port = {
        val port = new RouterPort
        port.id = UUID.randomUUID()
        port.routerId = router.id
        port.setBaseUri(app.getUri)
        port.portAddress = IPv4Addr.random.toString
        port.networkAddress = port.portAddress
        port.networkLength = 12
        val uri = postAndAssertOk(port, router.getPorts,
                                  APPLICATION_PORT_V3_JSON)
        getAndAssertOk[RouterPort](uri, APPLICATION_PORT_V3_JSON)
    }

    @Test
    def testCreateWithNonExistentGroup(): Unit = {
        // Given router port.
        val router = createRouter()
        val port = createPort(router)

        // And a container referencing a non existent group.
        val container = new ServiceContainer
        container.id = UUID.randomUUID()
        container.portId = port.id
        container.serviceType = "TYPE"
        container.serviceGroupId = UUID.randomUUID()
        container.setBaseUri(app.getUri)

        // Then creating the container should fail.
        postAndAssertStatus(container, containerBase.getURI,
                            APPLICATION_SERVICE_CONTAINER_JSON,
                            NOT_FOUND).getEntity(classOf[DtoError])
    }

    @Test
    def testCreateWithoutGroupId(): Unit = {
        // Given a router port.
        val router = createRouter()
        val port = createPort(router)

        // And a container with the group not set.
        val sc = new ServiceContainer
        sc.id = UUID.randomUUID()
        sc.portId = port.id
        sc.serviceType = "TYPE"
        sc.setBaseUri(app.getUri)

        // Then creating the container should succeed.
        postAndAssertOk(sc, containerBase.getURI,
                        APPLICATION_SERVICE_CONTAINER_JSON)
    }

    @Test
    def testUpdateDoesNotModifyContainer(): Unit = {
        // Given a router port and a container group.
        val router = createRouter()
        val port = createPort(router)
        val group = createServiceContainerGroup()

        // And a container.
        val container = new ServiceContainer
        val configurationId = UUID.randomUUID()
        container.id = UUID.randomUUID()
        container.configurationId = configurationId
        container.portId = port.id
        container.serviceType = "TYPE"
        container.serviceGroupId = group.id
        container.setBaseUri(app.getUri)

        // Then creating the container should succeed.
        postAndAssertOk(container, containerBase.getURI,
                        APPLICATION_SERVICE_CONTAINER_JSON)

        // And updating the container should succeed.
        container.configurationId = UUID.randomUUID()
        putAndAssertStatus(container, APPLICATION_SERVICE_CONTAINER_JSON,
                           NO_CONTENT.getStatusCode)

        // And the container configuration remains unchanged.
        getAndAssertOk[ServiceContainer](container.getUri,
                                         APPLICATION_SERVICE_CONTAINER_JSON)
                .configurationId shouldBe configurationId
    }

    @Test
    def testCrud(): Unit = {
        // Given a router port and a container group.
        val router = createRouter()
        val port = createPort(router)
        val group = createServiceContainerGroup()

        // Then the initial list of containers should be empty.
        listAndAssertOk[ServiceContainer](
            group.getServiceContainers,
            APPLICATION_SERVICE_CONTAINER_COLLECTION_JSON) shouldBe empty

        // Given a container.
        val container = new ServiceContainer
        container.id = UUID.randomUUID()
        container.portId = port.id
        container.serviceGroupId = group.id
        container.serviceType = "TYPE"
        container.configurationId = UUID.randomUUID()
        container.statusCode = Code.STOPPED
        container.setBaseUri(app.getUri)

        val uri1 = containerBase.getUriBuilder.path(container.id.toString).build()

        // Then creating the container should succeed.
        val uri2 = postAndAssertOk(container, group.getServiceContainers,
                                   APPLICATION_SERVICE_CONTAINER_JSON)

        // And getting the container at the returned URI should succeed.
        get[ServiceContainer](
            uri1, APPLICATION_SERVICE_CONTAINER_JSON) shouldBe container


        // And getting the container should return the container.
        val dtoContainer = getAndAssertOk[ServiceContainer](
            uri2, APPLICATION_SERVICE_CONTAINER_JSON)
        dtoContainer.getUri shouldBe uri2

        // And the port should reference the container.
        val dtoPort = get[DtoRouterPort](dtoContainer.getPort,
                                         APPLICATION_PORT_V3_JSON)
        dtoPort.getId shouldBe port.id
        dtoPort.getDeviceId shouldBe router.id
        dtoPort.getServiceContainer shouldBe uri2

        // And the group should reference the container.
        val dtoGroup = getAndAssertOk[ServiceContainerGroup](
            group.getUri, APPLICATION_SERVICE_CONTAINER_GROUP_JSON)
        dtoGroup.serviceContainerIds should have size 1
        dtoGroup.serviceContainerIds.head shouldBe dtoContainer.id

        // And the list of containers should have the container.
        val containers = listAndAssertOk[ServiceContainer](
            group.getServiceContainers,
            APPLICATION_SERVICE_CONTAINER_COLLECTION_JSON)
        containers should have size 1
        containers.head shouldBe dtoContainer
    }

    @Test
    def testScheduleContainerWithoutPort(): Unit = {
        // Given a container group.
        val group = createServiceContainerGroup()

        // And a container.
        val container = new ServiceContainer
        container.id = UUID.randomUUID()
        container.configurationId = UUID.randomUUID()
        container.serviceType = "TYPE"
        container.serviceGroupId = group.id
        container.setBaseUri(app.getUri)

        // Then creating the container should succeed.
        postAndAssertOk(container, containerBase.getURI,
                        APPLICATION_SERVICE_CONTAINER_JSON)

        // Schedule the container at a random host.
        container.hostId = UUID.randomUUID()

        // Then scheduling the container should fail.
        putAndAssertStatus(container, APPLICATION_SERVICE_CONTAINER_JSON,
                           NOT_ACCEPTABLE.getStatusCode)
    }

    @Test
    def testScheduleContainerNonExistingHost(): Unit = {
        // Given a container group.
        val router = createRouter()
        val port = createPort(router)
        val group = createServiceContainerGroup()

        // And a container.
        val container = new ServiceContainer
        container.id = UUID.randomUUID()
        container.configurationId = UUID.randomUUID()
        container.serviceType = "TYPE"
        container.serviceGroupId = group.id
        container.portId = port.id
        container.setBaseUri(app.getUri)

        // Then creating the container should succeed.
        postAndAssertOk(container, containerBase.getURI,
                        APPLICATION_SERVICE_CONTAINER_JSON)

        // Schedule the container at a random host.
        container.hostId = UUID.randomUUID()

        // Then scheduling the container should fail.
        putAndAssertStatus(container, APPLICATION_SERVICE_CONTAINER_JSON,
                           NOT_FOUND.getStatusCode)
    }

    @Test
    def testScheduleAndUnscheduleContainer(): Unit = {
        // Given a container group.
        val hostId = createHost()
        val router = createRouter()
        val port = createPort(router)
        val group = createServiceContainerGroup()

        // And a container.
        val container = new ServiceContainer
        container.id = UUID.randomUUID()
        container.configurationId = UUID.randomUUID()
        container.serviceType = "TYPE"
        container.serviceGroupId = group.id
        container.portId = port.id
        container.setBaseUri(app.getUri)

        // Then creating the container should succeed.
        postAndAssertOk(container, containerBase.getURI,
                        APPLICATION_SERVICE_CONTAINER_JSON)

        // Schedule the container at the host.
        container.hostId = hostId

        // Then scheduling the container should succeed.
        putAndAssertOk(container, APPLICATION_SERVICE_CONTAINER_JSON)

        // And the port should be bound at that host.
        getAndAssertOk[Port](port.getUri, APPLICATION_PORT_V3_JSON)
            .hostId shouldBe hostId

        // And unscheduling the container should succeed.
        container.hostId = null

        // Then scheduling the container should succeed.
        putAndAssertOk(container, APPLICATION_SERVICE_CONTAINER_JSON)

        // And the port should be bound at that host.
        getAndAssertOk[Port](port.getUri, APPLICATION_PORT_V3_JSON)
            .hostId shouldBe null
    }

}
