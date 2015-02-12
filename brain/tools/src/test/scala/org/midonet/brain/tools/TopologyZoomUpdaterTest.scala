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

package org.midonet.brain.tools

import org.junit.runner.RunWith
import org.midonet.cluster.data.storage.{Storage, InMemoryStorage, StorageWithOwnership}
import org.midonet.cluster.services.MidonetBackend
import org.scalatest.{BeforeAndAfter, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TopologyZoomUpdaterTest extends FeatureSpec
                                      with Matchers
                                      with BeforeAndAfter {

    class InMemoryMidonetBackendService extends MidonetBackend {
        private val zoom = new InMemoryStorage
        override def store: Storage = zoom
        override def ownershipStore: StorageWithOwnership = zoom
        override def isEnabled = true
        protected override def doStart(): Unit = {
            try {
                setupBindings()
                notifyStarted()
            } catch {
                case e: Exception => this.notifyFailed(e)
            }
        }
        protected override def doStop(): Unit = notifyStopped()
    }

    var backend: MidonetBackend = _
    var updater: TopologyZoomUpdater = _
    implicit var storage: StorageWithOwnership = _

    before {
        val cfg = new TopologyZoomUpdaterConfig {
            override def enableUpdates: Boolean = false
            override def periodMs: Long = 0
            override def initialTmpRouters: Int = 1
            override def initialTmpPortsPerNetwork: Int = 1
            override def initialTmpVteps: Int = 1
            override def initialTmpNetworksPerRouter: Int = 1
            override def initialTmpHosts: Int = 1
            override def numThreads: Int = 1
        }

        backend = new InMemoryMidonetBackendService
        backend.startAsync().awaitRunning()
        storage = backend.ownershipStore
        updater = new TopologyZoomUpdater(backend, cfg)
    }

    after {
        backend.stopAsync().awaitTerminated()
    }

    feature("Router") {
        scenario("crud") {
            val rt = Router("router").create()

            val obj1 = Router.get(rt.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldNot be (null)
            obj1.get.getId shouldBe rt.getId

            val state = rt.getAdminStateUp
            rt.setAdminStateUp(!state)
            rt.getAdminStateUp shouldBe !state

            val obj2 = Router.get(rt.getId)
            obj2.get.getAdminStateUp shouldBe !state

            rt.delete()
            Router.get(rt.getId) shouldBe None
        }
        scenario("attached port") {
            val rt = Router("router").create()
            val p = rt.createPort()
            p.getId shouldNot be (null)

            val obj1 = Port.get(p.getId)
            obj1 shouldNot be (None)
            obj1.get.getId shouldBe p.getId

            val dev = p.getDevice.get
            dev.isInstanceOf[Router] shouldBe true
            dev.asInstanceOf[Router].getId shouldBe rt.getId

            val ports = rt.getPorts
            ports.size shouldBe 1
            ports.iterator.next().getId shouldBe p.getId

            // port is removed from the list, but not from storage
            rt.removePort(p)
            rt.getPorts.size shouldBe 0

            val obj2 = Port.get(p.getId)
            obj2 shouldNot be (None)
            obj2.get.getDevice shouldBe None
        }
    }

}
