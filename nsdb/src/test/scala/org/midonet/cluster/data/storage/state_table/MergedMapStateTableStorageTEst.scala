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

package org.midonet.cluster.data.storage.state_table

import java.util.UUID

import scala.util.Random

import com.typesafe.config.ConfigFactory
import org.junit.runner.RunWith
import org.scalatest.{Matchers, BeforeAndAfter, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.storage.InMemoryMergedMapBusBuilder
import org.midonet.cluster.storage.KafkaConfig

@RunWith(classOf[JUnitRunner])
class MergedMapStateTableStorageTest extends FeatureSpec with Matchers {

    private val stateStorage =
        new MergedMapStateTableStorage(new KafkaConfig(ConfigFactory.empty),
                                       new InMemoryMergedMapBusBuilder())

    feature("Obtaining state tables") {
        scenario("Mac table") {
            val macTable = stateStorage.macTable(deviceId = UUID.randomUUID(),
                               vlanId = Random.nextInt(32768).toShort,
                               ownerId = "owner")
            macTable shouldNot be(null)
        }

        scenario("Arp table") {
            val arpTable = stateStorage.bridgeArpTable(bridgeId = UUID.randomUUID())
            arpTable shouldNot be(null)
        }

        scenario("Arp cache") {
            val arpCache = stateStorage.routerArpCache(routerId = UUID.randomUUID())
            arpCache shouldNot be(null)
        }
    }
}
