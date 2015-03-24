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

package org.midonet.cluster.data.storage

import org.junit.Test
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, Suite}

import org.midonet.cluster.util.CuratorTestFramework

@RunWith(classOf[JUnitRunner])
class ZoomMapTests extends Suite with CuratorTestFramework with Matchers {
    private var zom: ZookeeperObjectMapper = _

    override protected val ZK_ROOT = "/maps"
    
    private val BridgeMap1Id = "/maps/bridge/1"

    override protected def setup(): Unit = {
        zom = new ZookeeperObjectMapper(ZK_ROOT, curator)
        zom.build()
    }

    @Test
    def testCreateMap(): Unit = {
        zom.multi(List(CreateMap(BridgeMap1Id)))
        zom.getMapKeys(BridgeMap1Id) should be(empty)
    }

    @Test
    def testAddEntries(): Unit = {
        zom.multi(List(CreateMap(BridgeMap1Id)))
        zom.multi(List(AddMapEntry(BridgeMap1Id, "key1", "val1"),
                       AddMapEntry(BridgeMap1Id, "key2", null)))
        val keys = zom.getMapKeys(BridgeMap1Id)
        keys should contain only("key1", "key2")
        zom.getMapValue(BridgeMap1Id, "key2") shouldBe null

    }

    @Test
    def testUpdateEntries(): Unit = {
        createMapAndEntries(BridgeMap1Id, ("key1", "val1"), ("key2", null))
        zom.multi(List(UpdateMapEntry(BridgeMap1Id, "key1", null),
                       UpdateMapEntry(BridgeMap1Id, "key2", "val2")))
        zom.getMapValue(BridgeMap1Id, "key1") shouldBe null
        zom.getMapValue(BridgeMap1Id, "key2") shouldBe "val2"
    }

    @Test
    def testDeleteEntries(): Unit = {
        createMapAndEntries(BridgeMap1Id, ("key1", "val1"), ("key2", null))
        zom.multi(List(DeleteMapEntry(BridgeMap1Id, "key1")))
        zom.getMapKeys(BridgeMap1Id) should contain only "key2"
    }

    @Test
    def testDeleteMap(): Unit = {
        createMapAndEntries(BridgeMap1Id, ("key1", "val1"), ("key2", null))
        zom.multi(List(DeleteMap(BridgeMap1Id)))
        curator.checkExists().forPath(BridgeMap1Id) shouldBe null
    }

    @Test
    def testCreateExistingMap(): Unit = {
        createMapAndEntries(BridgeMap1Id)
        val ex = the [MapExistsException] thrownBy
                 createMapAndEntries(BridgeMap1Id)
        ex.mapId shouldBe BridgeMap1Id
    }

    @Test
    def testAddEntryToNonExistingMap(): Unit = {
        val ex = the [MapNotFoundException] thrownBy
                 zom.multi(List(AddMapEntry(BridgeMap1Id, "key", "value")))
        ex.mapId shouldBe BridgeMap1Id
    }

    @Test
    def testUpdateNonExistingEntry(): Unit = {
        createMapAndEntries(BridgeMap1Id, ("key", "value"))
        val ex = the [MapEntryNotFoundException] thrownBy
                 zom.multi(List(UpdateMapEntry(BridgeMap1Id, "key2", "val2")))
        ex.mapId shouldBe BridgeMap1Id
        ex.key shouldBe "key2"
    }

    @Test
    def testDeleteNonExistingEntry(): Unit = {
        val ex = the [MapEntryNotFoundException] thrownBy
                 zom.multi(List(DeleteMapEntry(BridgeMap1Id, "key")))
        ex.mapId shouldBe BridgeMap1Id
        ex.key shouldBe "key"
    }

    @Test
    def testAddExistingEntry(): Unit = {
        createMapAndEntries(BridgeMap1Id, ("key", "value"))
        val ex = the [MapEntryExistsException] thrownBy
                 zom.multi(List(AddMapEntry(BridgeMap1Id, "key", "value2")))
        ex.mapId shouldBe BridgeMap1Id
        ex.key shouldBe "key"
    }

    @Test
    def testAddEntryTwiceInOneTxn(): Unit = {

    }

    private def createMapAndEntries(mapId: String,
                                    entries: (String, String)*) = {
        val adds = for ((key, value) <- entries)
            yield AddMapEntry(mapId, key, value)
        zom.multi(CreateMap(mapId) +: adds)
    }

}
