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

package org.midonet.brain.services.vladimir

import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, _}

import org.midonet.brain.{BrainConfig, ClusterNode}
import org.midonet.brain.services.conf.ZookeeperTestSuite
import org.midonet.cluster.services.{MidonetBackendService, MidonetBackend}
import org.midonet.conf.{HostIdGenerator, MidoNodeConfigurator}

@RunWith(classOf[JUnitRunner])
class VladimirTest extends FeatureSpecLike
                           with Matchers
                           with BeforeAndAfterAll
                           with BeforeAndAfter
                           with GivenWhenThen
                           with ZookeeperTestSuite {

    HostIdGenerator.useTemporaryHostId()
    var vlad: Vladimir = _

    val HTTP_PORT: Int = 8080

    private val confStr =
        s"""
           |brain.rest_api.enabled : true
           |brain.rest_api.http_port : $HTTP_PORT
        """.stripMargin

    override def config = ConfigFactory.parseString(confStr).withFallback(super.config)
    var confMinion: Vladimir = _
    var backend: MidonetBackend = _

    before {
        clearZookeeper()
        MidoNodeConfigurator(config).deployBundledConfig()
    }

    override def beforeAll(): Unit = {
        super.beforeAll()
        val brainConf = new BrainConfig(config)
        val context = ClusterNode.Context(HostIdGenerator.getHostId, true)
        backend = new MidonetBackendService(brainConf.backend, zkClient)
        backend.setupBindings()
        confMinion = new Vladimir(context, backend, brainConf)
        confMinion.startAsync().awaitRunning()

    }

    override def afterAll(): Unit = {
        super.afterAll()
        confMinion.stopAsync().awaitTerminated()
    }
}
