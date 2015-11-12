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

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FunSuite, ShouldMatchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.InMemoryStorage
import org.midonet.cluster.models.Topology.Router
import org.midonet.cluster.topology.TopologyBuilder

@RunWith(classOf[JUnitRunner])
class DeviceWatcherTest extends FunSuite with BeforeAndAfter with
                                TopologyBuilder with ShouldMatchers {

    val log = LoggerFactory.getLogger(getClass)
    var store: InMemoryStorage  = _

    before {
        store = new InMemoryStorage
        MidonetBackend.setupBindings(store, store)
    }

    test("It receives and ignores issues according to subscriptions status") {
        var counter = 0
        var deleted: Object = null
        val updateCounter = (t: Router) => { counter = counter + 1 }
        val deleteCounter = (id: Object) => { deleted = id }
        val watcher = new DeviceWatcher[Router](store, log, updateCounter,
                                                deleteCounter)

        val r1 = createRouter()
        store.create(r1)

        watcher.startWatching()

        val r2 = createRouter()
        val r3 = createRouter()

        store.create(r2)

        deleted shouldBe null

        store.delete(classOf[Router], r1.getId)
        store.update(r2.toBuilder.setName("some-name").build())
        store.create(r3)

        counter shouldBe 4
        deleted shouldBe r1.getId

        watcher.stopWatching()

        store.create(createRouter()) // InMemoryStorage runs the watchers on
                                     // the same thread, check can be immediate

        counter shouldBe 4
        deleted shouldBe r1.getId
    }

}
