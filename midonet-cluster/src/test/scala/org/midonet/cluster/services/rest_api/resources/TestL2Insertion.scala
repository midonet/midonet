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

import scala.collection.JavaConverters._
import scala.concurrent.Await
import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import com.sun.jersey.api.client.{ClientResponse, WebResource}
import com.sun.jersey.api.client.ClientResponse.Status
import com.sun.jersey.test.framework.JerseyTest

import org.midonet.client.dto.{DtoBridge, DtoBridgePort}
import org.midonet.cluster.data.storage.{NotFoundException, Storage}
import org.midonet.cluster.rest_api.rest_api.{DtoWebResource, FuncTest, Topology}
import org.midonet.cluster.models.{Topology => PbTopo, Commons => PbCommons}
import org.midonet.cluster.rest_api.ResourceUris
import org.midonet.cluster.rest_api.models.{L2Insertion, Port}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class TestL2Insertion extends FeatureSpec
        with Matchers
        with BeforeAndAfter {

    var topology: Topology = _
    var l2Resource: WebResource = _

    val jerseyTest: FuncJerseyTest = new FuncJerseyTest
    var store: Storage = _

    val Bridge0 = "BRIDGE0"
    val Port0 = "PORT0"
    val Port1 = "PORT1"
    val ServicePort0 = "SERVICEPORT0"
    val ServicePort1 = "SERVICEPORT1"

    before {
        val dtoWebResource = new DtoWebResource(jerseyTest.resource())

        val builder = new Topology.Builder(dtoWebResource)

        val bridge = new DtoBridge()
        bridge.setName(Bridge0)
        bridge.setTenantId("dummyTenant")
        builder.create(Bridge0, bridge)

        val protectedPort0 = new DtoBridgePort()
        builder.create(Bridge0, Port0, protectedPort0)
        val protectedPort1 = new DtoBridgePort()
        builder.create(Bridge0, Port1, protectedPort1)

        val servicePort0 = new DtoBridgePort()
        builder.create(Bridge0, ServicePort0, servicePort0)
        val servicePort1 = new DtoBridgePort()
        builder.create(Bridge0, ServicePort1, servicePort1)

        topology = builder.build()

        l2Resource = jerseyTest.resource().path(ResourceUris.L2INSERTIONS)
        store = FuncTest._injector.getInstance(classOf[MidonetBackend]).store
    }

    def createInsertion(portId: UUID, srvPortId: UUID): L2Insertion = {
        val insertion = new L2Insertion()
        insertion.id = UUID.randomUUID
        insertion.portId = portId
        insertion.srvPortId = srvPortId
        insertion.vlan = 10
        insertion.position = 1
        insertion.failOpen = false
        insertion.mac = "02:00:00:00:ee:00"
        insertion.setBaseUri(jerseyTest.getBaseURI())
        insertion
    }

    def fetchDevice[T](clazz: Class[T], id: PbCommons.UUID): T = {
        Await.result(store.get(clazz, id), 5 seconds)
    }

    def ensureRuleCount(chainId: PbCommons.UUID, count: Int,
                        ruleType: PbTopo.Rule.Type = PbTopo.Rule.Type.L2TRANSFORM_RULE): Unit = {
        val chain = fetchDevice(classOf[PbTopo.Chain], chainId)
        chain.getRuleIdsList.asScala foreach {
            (id: PbCommons.UUID) => {
                val r = fetchDevice(classOf[PbTopo.Rule], id)
                r.getType shouldBe ruleType
            }
        }
        chain.getRuleIdsCount shouldBe count
    }

    def checkRulesFailOpen(chainId: PbCommons.UUID, target: PbCommons.UUID,
                           failOpen: Boolean): Unit = {
        val chain = fetchDevice(classOf[PbTopo.Chain], chainId)
        var foundRule = false
        chain.getRuleIdsList.asScala foreach {
            (id: PbCommons.UUID) => {
                val r = fetchDevice(classOf[PbTopo.Rule], id)
                if (r.getAction == PbTopo.Rule.Action.REDIRECT &&
                        r.getTransformRuleData.getTargetPortId == target) {
                    r.getTransformRuleData.getFailOpen shouldBe failOpen
                    foundRule = true
                }
            }
        }
        foundRule shouldBe true
    }

    feature("l2insertion") {
        scenario("Adding l2insertion to port creates chains") {
            val portId0 = topology.getBridgePort(Port0).getId
            val srvPortId0 = topology.getBridgePort(ServicePort0).getId
            var insertion = createInsertion(portId0, srvPortId0)

            var response = l2Resource.`type`(APPLICATION_L2INSERTION_JSON)
                .post(classOf[ClientResponse], insertion)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode

            var port0 = fetchDevice(classOf[PbTopo.Port], portId0)
            port0.hasL2InsertionInfilterId shouldBe true
            port0.hasL2InsertionOutfilterId shouldBe true
            ensureRuleCount(port0.getL2InsertionInfilterId, 3)
            ensureRuleCount(port0.getL2InsertionOutfilterId, 2)

            val srvPort0 = fetchDevice(classOf[PbTopo.Port], srvPortId0)
            srvPort0.hasL2InsertionInfilterId shouldBe true
            srvPort0.hasL2InsertionOutfilterId shouldBe true
            ensureRuleCount(srvPort0.getL2InsertionInfilterId, 2,
                            PbTopo.Rule.Type.JUMP_RULE)
            ensureRuleCount(srvPort0.getL2InsertionOutfilterId, 0)

            val srvPortId1 = topology.getBridgePort(ServicePort1).getId
            insertion = createInsertion(portId0, srvPortId1)

            response = l2Resource.`type`(APPLICATION_L2INSERTION_JSON)
                .post(classOf[ClientResponse], insertion)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode

            // adding another insertion should add 1 more rule on each chain
            // on the port
            port0 = fetchDevice(classOf[PbTopo.Port], portId0)
            port0.hasL2InsertionInfilterId shouldBe true
            port0.hasL2InsertionOutfilterId shouldBe true
            ensureRuleCount(port0.getL2InsertionInfilterId, 4)
            ensureRuleCount(port0.getL2InsertionOutfilterId, 3)

            // service ports should always be the same
            for (id <- Seq(srvPortId0, srvPortId1)) {
                val srvPort = fetchDevice(classOf[PbTopo.Port], id)
                srvPort.hasL2InsertionInfilterId shouldBe true
                srvPort.hasL2InsertionOutfilterId shouldBe true
                ensureRuleCount(srvPort.getL2InsertionInfilterId, 2,
                                PbTopo.Rule.Type.JUMP_RULE)
                ensureRuleCount(srvPort.getL2InsertionOutfilterId, 0)
            }
        }

        scenario("Update l2insertion reflected in rules") {
            val portId0 = topology.getBridgePort(Port0).getId
            val srvPortId0 = topology.getBridgePort(ServicePort0).getId
            val insertion = createInsertion(portId0, srvPortId0)

            var response = l2Resource.`type`(APPLICATION_L2INSERTION_JSON)
                .post(classOf[ClientResponse], insertion)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode

            var port0 = fetchDevice(classOf[PbTopo.Port], portId0)
            port0.hasL2InsertionInfilterId shouldBe true
            port0.hasL2InsertionOutfilterId shouldBe true
            checkRulesFailOpen(port0.getL2InsertionInfilterId, srvPortId0, false)
            checkRulesFailOpen(port0.getL2InsertionOutfilterId, srvPortId0, false)

            insertion.failOpen = true
            response = l2Resource
                .uri(response.getLocation)
                .`type`(APPLICATION_L2INSERTION_JSON)
                .put(classOf[ClientResponse], insertion)
            response.getStatusInfo
                .getStatusCode shouldBe Status.NO_CONTENT.getStatusCode
            port0 = fetchDevice(classOf[PbTopo.Port], portId0)
            port0.hasL2InsertionInfilterId shouldBe true
            port0.hasL2InsertionOutfilterId shouldBe true
            checkRulesFailOpen(port0.getL2InsertionInfilterId, srvPortId0, true)
            checkRulesFailOpen(port0.getL2InsertionOutfilterId, srvPortId0, true)
        }

        scenario("Deleting l2insertion clears chains") {
            val portId0 = topology.getBridgePort(Port0).getId
            val srvPortId0 = topology.getBridgePort(ServicePort0).getId
            var insertion = createInsertion(portId0, srvPortId0)

            var response = l2Resource.`type`(APPLICATION_L2INSERTION_JSON)
                .post(classOf[ClientResponse], insertion)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode

            var port0 = fetchDevice(classOf[PbTopo.Port], portId0)
            port0.hasL2InsertionInfilterId shouldBe true
            port0.hasL2InsertionOutfilterId shouldBe true
            ensureRuleCount(port0.getL2InsertionInfilterId, 3)
            ensureRuleCount(port0.getL2InsertionOutfilterId, 2)

            var srvPort0 = fetchDevice(classOf[PbTopo.Port], srvPortId0)
            srvPort0.hasL2InsertionInfilterId shouldBe true
            srvPort0.hasL2InsertionOutfilterId shouldBe true
            ensureRuleCount(srvPort0.getL2InsertionInfilterId, 2,
                            PbTopo.Rule.Type.JUMP_RULE)
            ensureRuleCount(srvPort0.getL2InsertionOutfilterId, 0)

            response = l2Resource.uri(response.getLocation)
                .delete(classOf[ClientResponse])
            response.getStatusInfo
                .getStatusCode shouldBe Status.NO_CONTENT.getStatusCode

            port0 = fetchDevice(classOf[PbTopo.Port], portId0)
            port0.hasL2InsertionInfilterId shouldBe true
            port0.hasL2InsertionOutfilterId shouldBe true
            ensureRuleCount(port0.getL2InsertionInfilterId, 0)
            ensureRuleCount(port0.getL2InsertionOutfilterId, 0)

            srvPort0 = fetchDevice(classOf[PbTopo.Port], srvPortId0)
            srvPort0.hasL2InsertionInfilterId shouldBe true
            srvPort0.hasL2InsertionOutfilterId shouldBe true
            ensureRuleCount(srvPort0.getL2InsertionInfilterId, 0)
            ensureRuleCount(srvPort0.getL2InsertionOutfilterId, 0)
        }

        scenario("Deleting port deletes insertion") {
            val portId0 = topology.getBridgePort(Port0).getId
            val srvPortId0 = topology.getBridgePort(ServicePort0).getId
            val insertion = createInsertion(portId0, srvPortId0)
            var response = l2Resource.`type`(APPLICATION_L2INSERTION_JSON)
                .post(classOf[ClientResponse], insertion)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode

            val insertionObj = fetchDevice(classOf[PbTopo.L2Insertion],
                                        insertion.id)


            var srvPort0 = fetchDevice(classOf[PbTopo.Port], srvPortId0)
            srvPort0.getSrvInsertionIdsCount shouldBe 1

            response = jerseyTest.resource().path(
                ResourceUris.PORTS + "/" + portId0)
                .delete(classOf[ClientResponse])
            response.getStatusInfo
                .getStatusCode shouldBe Status.NO_CONTENT.getStatusCode

            intercept[NotFoundException] {
                fetchDevice(classOf[PbTopo.L2Insertion],
                            insertion.id)
            }

            srvPort0 = fetchDevice(classOf[PbTopo.Port], srvPortId0)
            srvPort0.getSrvInsertionIdsCount shouldBe 0
        }

        scenario("Deleting service port deletes insertion") {
            val portId0 = topology.getBridgePort(Port0).getId
            val srvPortId0 = topology.getBridgePort(ServicePort0).getId
            val insertion = createInsertion(portId0, srvPortId0)
            var response = l2Resource.`type`(APPLICATION_L2INSERTION_JSON)
                .post(classOf[ClientResponse], insertion)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode

            var port0 = fetchDevice(classOf[PbTopo.Port], portId0)
            port0.getInsertionIdsCount shouldBe 1

            val insertionObj = fetchDevice(classOf[PbTopo.L2Insertion],
                                        insertion.id)
            response = jerseyTest.resource().path(
                ResourceUris.PORTS + "/" + srvPortId0)
                .delete(classOf[ClientResponse])
            response.getStatusInfo
                .getStatusCode shouldBe Status.NO_CONTENT.getStatusCode

            intercept[NotFoundException] {
                fetchDevice(classOf[PbTopo.L2Insertion],
                            insertion.id)
            }

            port0 = fetchDevice(classOf[PbTopo.Port], portId0)
            port0.getInsertionIdsCount shouldBe 0
        }

        scenario("Updating port doesn't clear l2insertion fields") {
            val portId0 = topology.getBridgePort(Port0).getId
            val srvPortId0 = topology.getBridgePort(ServicePort0).getId
            val insertion = createInsertion(portId0, srvPortId0)
            val response = l2Resource.`type`(APPLICATION_L2INSERTION_JSON)
                .post(classOf[ClientResponse], insertion)
            response.getStatusInfo
                .getStatusCode shouldBe Status.CREATED.getStatusCode

            var port0dev = fetchDevice(classOf[PbTopo.Port], portId0)
            port0dev.hasL2InsertionInfilterId shouldBe true
            port0dev.hasL2InsertionOutfilterId shouldBe true
            port0dev.getInsertionIdsCount shouldBe 1

            val portResource = jerseyTest.resource().path(
                ResourceUris.PORTS + "/" + portId0)
            val port0 = portResource.accept(APPLICATION_PORT_V3_JSON)
                .get(classOf[Port])
            port0.setBaseUri(jerseyTest.resource()
                                 .path(ResourceUris.PORTS).getURI)
            port0.interfaceName = "foobar"
            portResource.`type`(APPLICATION_PORT_V3_JSON)
                .put(port0)

            port0dev = fetchDevice(classOf[PbTopo.Port], portId0)
            port0dev.hasL2InsertionInfilterId shouldBe true
            port0dev.hasL2InsertionOutfilterId shouldBe true
            port0dev.getInsertionIdsCount shouldBe 1

            var srvPort0dev = fetchDevice(classOf[PbTopo.Port], srvPortId0)
            srvPort0dev.hasL2InsertionInfilterId shouldBe true
            srvPort0dev.hasL2InsertionOutfilterId shouldBe true
            srvPort0dev.getSrvInsertionIdsCount shouldBe 1

            val srvPortResource = jerseyTest.resource().path(
                ResourceUris.PORTS + "/" + srvPortId0)
            val srvPort0 = srvPortResource.accept(APPLICATION_PORT_V3_JSON)
                .get(classOf[Port])
            srvPort0.setBaseUri(jerseyTest.resource()
                                    .path(ResourceUris.PORTS).getURI)
            srvPort0.interfaceName = "srvfoobar"
            srvPortResource.`type`(APPLICATION_PORT_V3_JSON)
                .put(srvPort0)

            srvPort0dev = fetchDevice(classOf[PbTopo.Port], srvPortId0)
            srvPort0dev.hasL2InsertionInfilterId shouldBe true
            srvPort0dev.hasL2InsertionOutfilterId shouldBe true
            srvPort0dev.getSrvInsertionIdsCount shouldBe 1
        }
    }

}

class FuncJerseyTest extends JerseyTest(FuncTest.getBuilder.build()) {
    override def getBaseURI = super.getBaseURI
}
