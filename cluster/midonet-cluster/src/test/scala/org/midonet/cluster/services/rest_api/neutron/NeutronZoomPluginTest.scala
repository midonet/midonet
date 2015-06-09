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

package org.midonet.cluster.services.rest_api.neutron

import java.util.UUID

import com.typesafe.config.ConfigFactory
import org.scalatest._
import org.midonet.cluster.data.storage.NotFoundException
import org.midonet.cluster.rest_api.neutron.models.Network
import org.midonet.cluster.services.rest_api.neutron.plugin.NeutronZoomPlugin
import org.midonet.util.concurrent.toFutureOps
import org.midonet.cluster.models.Topology
import org.midonet.cluster.rest_api.NotFoundHttpException
import org.midonet.cluster.services.{MidonetBackend, MidonetBackendService}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.MidonetEventually
import scala.concurrent.duration._

class NeutronZoomPluginTest extends FeatureSpec
                                   with BeforeAndAfter
                                   with ShouldMatchers
                                   with GivenWhenThen
                                   with CuratorTestFramework
                                   with MidonetEventually {

    var backend: MidonetBackend = _
    var plugin: NeutronZoomPlugin = _
    var timeout = 5.seconds

    override def setup() {
        val cfg = new MidonetBackendConfig(ConfigFactory.parseString(s"""
           |zookeeper.zookeeper_hosts : "${zk.getConnectString}"
           |zookeeper.root_key : "$ZK_ROOT"
        """.stripMargin)
        )
        backend = new MidonetBackendService(cfg, curator)
        backend.setupBindings()
        plugin = new NeutronZoomPlugin(backend, cfg)
    }

    feature("The Plugin should be able to CRUD") {
        scenario("The plugin handles a Network") {
            val n = new Network
            n.id = UUID.randomUUID()
            n.name = UUID.randomUUID().toString
            n.external = false
            val n1 = plugin.createNetwork(n)
            n1 shouldBe n
            n1 shouldBe plugin.getNetwork(n.id)

            backend.store.get(classOf[Topology.Network], n.id)
                         .await(timeout).getName shouldBe n.name

            n.name = UUID.randomUUID().toString
            val n2 = plugin.updateNetwork(n.id, n)
            n2 shouldBe n
            n2 shouldNot be (n1)

            backend.store.get(classOf[Topology.Network], n.id)
                   .await(timeout).getName shouldBe n.name

            plugin.deleteNetwork(n.id)

            intercept[NotFoundHttpException] {
                plugin.getNetwork(n.id)
            }

            backend.store.exists(classOf[Topology.Network], n.id)
                   .await(timeout) shouldBe false
        }
    }

}
