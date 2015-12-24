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

import scala.reflect.ClassTag

import com.sun.jersey.api.client.ClientResponse
import com.sun.jersey.api.client.ClientResponse.Status

import org.scalatest.{BeforeAndAfter, Matchers, FeatureSpec}

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

    protected def postAndVerifySuccess(dto: AnyRef): URI = {
        val clazz = dto.getClass
        val resp = jerseyTest.resource()
            .path("/neutron" + lastPathSteps(clazz))
            .`type`(mediaTypes(clazz))
            .post(classOf[ClientResponse], dto)
        resp.getStatus shouldBe Status.CREATED.getStatusCode
        resp.getLocation
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

    protected def deleteAndVerifyNoContent(uri: URI): Unit = {
        val resp = jerseyTest.resource().uri(uri)
            .delete(classOf[ClientResponse])
        resp.getStatusInfo.getStatusCode shouldBe
            Status.NO_CONTENT.getStatusCode
    }

    private val lastPathSteps = Map[Class[_], String](
        classOf[GatewayDevice] -> "/gateway_devices",
        classOf[L2GatewayConnection] -> "/l2_gateway_connections",
        classOf[Network] -> "/networks",
        classOf[RemoteMacEntry] -> "/remote_mac_entries",
        classOf[Router] -> "/routers"
    )

    protected val mediaTypes = Map[Class[_], String](
        classOf[GatewayDevice] -> MidonetMediaTypes.NEUTRON_GATEWAY_DEVICE_JSON_V1,
        classOf[L2GatewayConnection] -> MidonetMediaTypes.NEUTRON_L2_GATEWAY_CONNECTION_JSON_V1,
        classOf[Network] -> NeutronMediaType.NETWORK_JSON_V1,
        classOf[RemoteMacEntry] -> MidonetMediaTypes.NEUTRON_REMOTE_MAC_ENTRY_JSON_V1,
        classOf[Router] -> NeutronMediaType.ROUTER_JSON_V1
    )
}
