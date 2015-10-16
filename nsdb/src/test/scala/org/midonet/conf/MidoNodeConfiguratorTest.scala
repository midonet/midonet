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

package org.midonet.conf

import org.apache.commons.lang.RandomStringUtils
import org.scalatest.{FeatureSpecLike, Matchers}

import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.conf.MidoNodeConfigurator._

class MidoNodeConfiguratorTest extends FeatureSpecLike
                               with Matchers
                               with CuratorTestFramework {

    feature("MidonetBackendService should set the root path correctly") {
        scenario("/midonet/v1 root key") {
            val overrides =
                s"""
                  |zookeeper.root_key = /midonet/v1
                """.stripMargin
            val conf = MidoTestConfigurator.forAgents(overrides)
            MidoNodeConfigurator.zkRootKey(conf) shouldBe defaultZkRootKey
        }

        scenario("any other root key") {
            val rndPath = RandomStringUtils.random(10 /* string length */)
            val overrides =
                s"""
                  |zookeeper.root_key = ${rndPath}
                """.stripMargin
            val conf = MidoTestConfigurator.forAgents(overrides)
            MidoNodeConfigurator.zkRootKey(conf) shouldBe rndPath
        }

        scenario("No zookeeper root key") {
            val conf = MidoTestConfigurator.forClusters()
            MidoNodeConfigurator.zkRootKey(conf)
            MidoNodeConfigurator.zkRootKey(conf) shouldBe defaultZkRootKey
        }
    }
}
