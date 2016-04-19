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
package org.midonet.cluster.services.rest_api.resources

import java.util.UUID
import javax.ws.rs.core.Response.Status._

import com.sun.jersey.test.framework.JerseyTest
import org.junit.{Before, Test}
import org.midonet.client.dto.{DtoApplication, DtoError}
import org.midonet.cluster.HttpRequestChecks
import org.midonet.cluster.rest_api.models.Host
import org.midonet.cluster.rest_api.rest_api.{DtoWebResource, FuncTest, Topology}
import org.midonet.cluster.services.rest_api.MidonetMediaTypes._
import org.scalatest._

class TestHostApi extends JerseyTest(FuncTest.getBuilder.build())
    with HttpRequestChecks
    with ShouldMatchers {

    private var app: DtoApplication = _

    def _makeHost(id: UUID = UUID.randomUUID(),
                  name: String = "test_host") : Host = {
        val h = new Host()
        h.id = id
        h.name = name
        h.setBaseUri(app.getUri)
        h
    }

    def _createHost(id: UUID = UUID.randomUUID(),
                    name: String = "test_host") : Host = {
        val h = _makeHost(id = id, name = name)
        val uri = postAndAssertOk(h, app.getHosts,
                                  APPLICATION_HOST_JSON_V3)
        getAndAssertOk[Host](uri, APPLICATION_HOST_JSON_V3)
        h
    }

    @Before
    override def setUp(): Unit = {
        val dtoWebResource = new DtoWebResource(resource())
        val topology = new Topology.Builder(dtoWebResource).build()
        app = topology.getApplication
    }

    @Test
    def testCreateWithDuplicatedId(): Unit = {
        val h1 = _createHost()
        val h2 = _makeHost(id = h1.id)

        // This should return HttpConflict
        postAndAssertStatus(h2, app.getHosts,
                            APPLICATION_HOST_JSON_V3,
                            CONFLICT).getEntity(classOf[DtoError])
    }
}
