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

package org.midonet.cluster.services

import org.apache.commons.lang.RandomStringUtils
import org.scalatest.{FeatureSpecLike, Matchers}

import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.conf.MidoTestConfigurator

class MidonetBackendServiceTest extends FeatureSpecLike
                                with Matchers
                                with CuratorTestFramework {

    var backend: MidonetBackendService = _

    feature("MidonetBackendService should set the root path correctly") {
        scenario("Default root path with use_my_own_zk_root_path set to false") {
            val overrides =
                """
                  |zookeeper.use_my_own_zk_root_path = false
                """.stripMargin
            val conf = new MidonetBackendConfig(
                MidoTestConfigurator.forAgents(overrides))
            backend = new MidonetBackendService(conf, curator,
                                                metricRegistry = null)
            backend.zkRoot shouldBe "/midonet/v5"
        }

        scenario("Default root path with use_my_own_zk_root_path set to true") {
            val overrides =
                """
                  |zookeeper.use_my_own_zk_root_path = true
                """.stripMargin
            val conf = new MidonetBackendConfig(
                MidoTestConfigurator.forAgents(overrides))
            backend = new MidonetBackendService(conf, curator,
                                                metricRegistry = null)
            backend.zkRoot shouldBe "/midonet/v5"
        }

        scenario("v1 root path with use_my_own_zk_root_path set to false") {
            val overrides =
            """
              |zookeeper.use_my_own_zk_root_path = false
              |zookeeper.root_key = /midonet/v1
            """.stripMargin
            val conf = new MidonetBackendConfig(
                MidoTestConfigurator.forAgents(overrides))
            backend = new MidonetBackendService(conf, curator,
                                                metricRegistry = null)
            backend.zkRoot shouldBe "/midonet/v5"
        }

        scenario("v1 root path with use_my_own_zk_root_path set to true") {
            val overrides =
                """
                  |zookeeper.use_my_own_zk_root_path = true
                  |zookeeper.root_key = /midonet/v1
                """.stripMargin
            val conf = new MidonetBackendConfig(
                MidoTestConfigurator.forAgents(overrides))
            backend = new MidonetBackendService(conf, curator,
                                                metricRegistry = null)
            backend.zkRoot shouldBe "/midonet/v1"
        }

        scenario("v2 root path with use_my_own_zk_root_path set to false") {
            val overrides =
                """
                  |zookeeper.use_my_own_zk_root_path = false
                  |zookeeper.root_key = /midonet/v2
                """.stripMargin
            val conf = new MidonetBackendConfig(
                MidoTestConfigurator.forAgents(overrides))
            backend = new MidonetBackendService(conf, curator,
                                                metricRegistry = null)
            backend.zkRoot shouldBe "/midonet/v5"
        }

        scenario("v2 root path with use_my_own_zk_root_path set to true") {
            val overrides =
                """
                  |zookeeper.use_my_own_zk_root_path = true
                  |zookeeper.root_key = /midonet/v2
                """.stripMargin
            val conf = new MidonetBackendConfig(
                MidoTestConfigurator.forAgents(overrides))
            backend = new MidonetBackendService(conf, curator,
                                                metricRegistry = null)
            backend.zkRoot shouldBe "/midonet/v2"
        }

        scenario("random root path with use_my_own_zk_root_path set to false") {
            val rndPath = RandomStringUtils.random(10 /* string length */)
            val overrides =
                s"""
                  |zookeeper.use_my_own_zk_root_path = false
                  |zookeeper.root_key = ${rndPath}
                """.stripMargin
            val conf = new MidonetBackendConfig(
                MidoTestConfigurator.forAgents(overrides))
            backend = new MidonetBackendService(conf, curator,
                                                metricRegistry = null)
            backend.zkRoot shouldBe "/midonet/v5"
        }

        scenario("random root path with use_my_own_zk_root_path set to true") {
            val rndPath = RandomStringUtils.random(10 /* string length */)
            val overrides =
                s"""
                  |zookeeper.use_my_own_zk_root_path = true
                  |zookeeper.root_key = ${rndPath}
                """.stripMargin
            val conf = new MidonetBackendConfig(
                MidoTestConfigurator.forAgents(overrides))
            backend = new MidonetBackendService(conf, curator,
                                                metricRegistry = null)
            backend.zkRoot shouldBe rndPath
        }
    }
}
