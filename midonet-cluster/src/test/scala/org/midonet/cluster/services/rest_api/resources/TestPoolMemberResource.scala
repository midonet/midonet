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

package org.midonet.cluster.services.rest_api.resources

import java.net.URI
import java.util.UUID

import javax.ws.rs.core.UriInfo

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext

import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.mockito.Mockito
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import org.midonet.cluster.ClusterConfig
import org.midonet.cluster.data.storage.{KeyType, StateStorage, Storage}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.{LBStatus => PLBStatus}
import org.midonet.cluster.models.Topology.{PoolMember => PPoolMember}
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.StatusKey
import org.midonet.cluster.services.rest_api.MidonetMediaTypes.{APPLICATION_POOL_MEMBER_COLLECTION_JSON, APPLICATION_POOL_MEMBER_JSON}
import org.midonet.cluster.services.rest_api.resources.MidonetResource.ResourceContext
import org.midonet.cluster.storage.MidonetTestBackend
import org.midonet.cluster.util.IPAddressUtil.richIPAddress
import org.midonet.cluster.util.UUIDUtil.{RichJavaUuid, asRichProtoUuid}
import org.midonet.midolman.state.l4lb.LBStatus
import org.midonet.packets.IPv4Addr


@RunWith(classOf[JUnitRunner])
class TestPoolMemberResource extends FlatSpec
                                     with BeforeAndAfter
                                     with Matchers {

    private var backend: MidonetBackend = _
    private var resource: PoolMemberResource = _
    private var config: ClusterConfig = _

    private val executionCtx = ExecutionContext.global

    private def store: Storage = backend.store
    private def stateStore: StateStorage = backend.stateStore

    before {
        config = ClusterConfig.forTests(ConfigFactory.empty)

        backend = new MidonetTestBackend
        backend.store.registerClass(classOf[PPoolMember])
        backend.stateStore.registerKey(classOf[PPoolMember], StatusKey,
                                       KeyType.FailFast)
        backend.store.build()

        val mockUriInfo = Mockito.mock(classOf[UriInfo])
        Mockito.when(mockUriInfo.getBaseUri).thenReturn(new URI("http://test"))

        val resCtx = ResourceContext(config.restApi, backend, executionCtx,
                                     mockUriInfo, null, null)
        resource = new PoolMemberResource(resCtx)
    }

    "PoolMemberResource" should "leave PoolMember status unchanged if set to " +
                                "ACTIVE or INACTIVE" in {
        val pm1 = createPoolMember(status = PLBStatus.ACTIVE)
        checkPoolMemberStatus(pm1.getId, LBStatus.ACTIVE)

        val pm2 = createPoolMember(status = PLBStatus.INACTIVE)
        checkPoolMemberStatus(pm2.getId, LBStatus.INACTIVE)
    }

    it should "set PoolMember status to NO_MONITOR if it's set to MONITORED " +
              "in the protobuf model but not set in state storage" in {
        val pm = createPoolMember()
        checkPoolMemberStatus(pm.getId, LBStatus.NO_MONITOR)
    }

    it should "set PoolMember status to INACTIVE if it's set to MONITORED " +
              "in the protobuf model and adminStateUp is false" in {
        val pm = createPoolMember(adminStateUp = false)
        checkPoolMemberStatus(pm.getId, LBStatus.INACTIVE)
    }

    it should "list multiple pool members with status set correctly" in {
        val pm1 = createPoolMember(status = PLBStatus.ACTIVE)
        val pm2 = createPoolMember(status = PLBStatus.INACTIVE)
        val pm3 = createPoolMember(adminStateUp = false)
        val pm4 = createPoolMember()
        val pm5 = createPoolMember()
        val pm6 = createPoolMember()

        setStatus(pm5.getId, "ACTIVE")
        setStatus(pm6.getId, "INACTIVE")

        val pmMap = resource.list(APPLICATION_POOL_MEMBER_COLLECTION_JSON)
            .asScala.map(pm => (pm.id, pm.status)).toMap
        pmMap(pm1.getId.asJava) shouldBe LBStatus.ACTIVE
        pmMap(pm2.getId.asJava) shouldBe LBStatus.INACTIVE
        pmMap(pm3.getId.asJava) shouldBe LBStatus.INACTIVE
        pmMap(pm4.getId.asJava) shouldBe LBStatus.NO_MONITOR
        pmMap(pm5.getId.asJava) shouldBe LBStatus.ACTIVE
        pmMap(pm6.getId.asJava) shouldBe LBStatus.INACTIVE
    }

    it should "set PoolMember status to ACTIVE or INACTIVE according to the " +
              "value in state storage" in {
        val pm = createPoolMember()
        setStatus(pm.getId, "ACTIVE")
        checkPoolMemberStatus(pm.getId, LBStatus.ACTIVE)

        setStatus(pm.getId, "INACTIVE")
        checkPoolMemberStatus(pm.getId, LBStatus.INACTIVE)
    }

    private def createPoolMember(adminStateUp: Boolean = true,
                                 status: PLBStatus = PLBStatus.MONITORED)
    : PPoolMember = {
        val bldr = PPoolMember.newBuilder
        bldr.setAddress(IPv4Addr.random.asProto)
        bldr.setAdminStateUp(adminStateUp)
        bldr.setId(UUID.randomUUID().asProto)
        bldr.setStatus(status)

        val pm = bldr.build()
        store.create(pm)
        pm
    }

    private def checkPoolMemberStatus(id: Commons.UUID,
                                      expectedStatus: LBStatus): Unit = {
        val pm = resource.get(id.asJava.toString, APPLICATION_POOL_MEMBER_JSON)
        pm.status shouldBe expectedStatus
    }

    private def setStatus(id: Commons.UUID, status: String): Unit = {
        stateStore.addValue(classOf[PPoolMember], id, StatusKey, status)
            .toBlocking.first()
    }
}
