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

package org.midonet.cluster.rest_api.neutron

import java.net.URI
import java.util.UUID

import scala.reflect.ClassTag

import com.sun.jersey.api.client.{ClientResponse, UniformInterfaceException}
import com.sun.jersey.api.client.ClientResponse.Status._

import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}

import org.midonet.cluster.rest_api.neutron.models._
import org.midonet.cluster.rest_api.rest_api.FuncJerseyTest
import org.midonet.cluster.services.rest_api.MidonetMediaTypes
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._

class NeutronApiTest extends FeatureSpec
                             with Matchers
                             with BeforeAndAfter {

    protected var jerseyTest: FuncJerseyTest = _

    protected def setup(): Unit = { }
    protected def tearDown(): Unit = { }

    before {
        jerseyTest = new FuncJerseyTest
        jerseyTest.setUp()
        setup()
    }

    after {
        jerseyTest.tearDown()
        tearDown()
    }

    protected def post(dto: AnyRef): ClientResponse = {
        val clazz = dto.getClass
        jerseyTest.resource()
            .path("/neutron" + lastPathSteps(clazz))
            .`type`(mediaTypes(clazz))
            .post(classOf[ClientResponse], dto)
    }

    protected def postAndVerifySuccess(dto: AnyRef): URI = {
        val resp = post(dto)
        resp.getStatus shouldBe CREATED.getStatusCode
        resp.getLocation
    }

    protected def put(dto: AnyRef, id: UUID): Unit = {
        val clazz = dto.getClass
        jerseyTest.resource()
            .path("/neutron" + lastPathSteps(clazz) + '/' + id)
            .`type`(mediaTypes(clazz))
            .put(dto)
    }

    protected def get[T](uri: URI)(implicit tag: ClassTag[T]): T = {
        val clazz = tag.runtimeClass.asInstanceOf[Class[T]]
        jerseyTest.resource().uri(uri)
            .accept(mediaTypes(clazz))
            .get(clazz)
    }

    protected def getNeutron: Neutron = {
        jerseyTest.resource().path("/neutron")
            .accept(NEUTRON_JSON_V3)
            .get(classOf[Neutron])
    }

    protected def delete(uri: URI): ClientResponse = {
        jerseyTest.resource().uri(uri).delete(classOf[ClientResponse])
    }

    protected def deleteAndVerifyNoContent(uri: URI): Unit = {
        delete(uri).getStatusInfo.getStatusCode shouldBe
            NO_CONTENT.getStatusCode
    }

    protected def methodGetNotAllowed[T](uri: URI)(implicit tag: ClassTag[T])
    : Unit = {
        val ex = intercept[UniformInterfaceException](get(uri))
        ex.getResponse.getStatus shouldBe METHOD_NOT_ALLOWED.getStatusCode
    }

    private val lastPathSteps = Map[Class[_], String](
        classOf[GatewayDevice] -> "/gateway_devices",
        classOf[L2GatewayConnection] -> "/l2_gateway_connections",
        classOf[Network] -> "/networks",
        classOf[RemoteMacEntry] -> "/remote_mac_entries",
        classOf[Router] -> "/routers",
        classOf[BgpPeer] -> "/bgp_peers",
        classOf[BgpSpeaker] -> "/bgp_speakers"
    )

    protected val mediaTypes = Map[Class[_], String](
        classOf[GatewayDevice] -> MidonetMediaTypes.NEUTRON_GATEWAY_DEVICE_JSON_V1,
        classOf[L2GatewayConnection] -> MidonetMediaTypes.NEUTRON_L2_GATEWAY_CONNECTION_JSON_V1,
        classOf[Network] -> NeutronMediaType.NETWORK_JSON_V1,
        classOf[RemoteMacEntry] -> MidonetMediaTypes.NEUTRON_REMOTE_MAC_ENTRY_JSON_V1,
        classOf[Router] -> NeutronMediaType.ROUTER_JSON_V1,
        classOf[BgpPeer] -> MidonetMediaTypes.NEUTRON_BGP_PEER_JSON_V1,
        classOf[BgpSpeaker] -> MidonetMediaTypes.NEUTRON_BGP_SPEAKER_JSON_V1
    )
}
