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

import java.util.UUID
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}
import rx.Observable

import org.midonet.cluster.data.storage.MergedMap.Update
import org.midonet.cluster.data.storage.state_table.MacTableMergedMap
import org.midonet.cluster.data.storage.state_table.MacTableMergedMap.{MacTableUpdate, PortTS}
import org.midonet.packets.MAC
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class MacTableMergedMapTest extends FeatureSpec with BeforeAndAfter
                                                with Matchers {

    var map: MergedMap[MAC, PortTS] = _
    var macTable: MacTableMergedMap = _

    before {
        map = mock(classOf[MergedMap[MAC, PortTS]])
        macTable = new MacTableMergedMap(bridgeId = UUID.randomUUID(),
                                         vlanId = 1, map)
    }

    feature("StateTable trait methods") {
        scenario("add") {
            val mac = MAC.random()
            val port = UUID.randomUUID()

            val arg1 = ArgumentCaptor.forClass(classOf[MAC])
            val arg2 = ArgumentCaptor.forClass(classOf[PortTS])
            macTable.add(mac, port)
            verify(map).putOpinion(arg1.capture(), arg2.capture())
            arg1.getValue shouldBe mac
            arg2.getValue.id shouldBe port

            macTable.addPersistent(mac, port)
            verify(map, times(2)).putOpinion(arg1.capture(), arg2.capture())
            arg1.getValue shouldBe mac
            arg2.getValue.id shouldBe port
            arg2.getValue.ts shouldBe Long.MaxValue
        }

        scenario("get") {
            val mac = MAC.random()
            macTable.get(mac) shouldBe null

            val portTS = PortTS(UUID.randomUUID(), 0L)
            when(map.get(mac)).thenReturn(portTS)
            macTable.get(mac) shouldBe portTS.id
        }

        scenario("remove") {
            val mac = MAC.random()
            val port = UUID.randomUUID()

            macTable.remove(mac)
            verify(map).removeOpinion(mac)

            macTable.remove(mac, port)
            verify(map, times(2)).removeOpinion(mac)
        }

        scenario("contains") {
            val mac = MAC.random()
            val port = UUID.randomUUID()

            macTable.contains(mac)
            verify(map).containsKey(mac)

            macTable.contains(mac, port) shouldBe false
            verify(map).get(mac)

            when(map.get(mac)).thenReturn(PortTS(port, 0l))
            macTable.contains(mac, port) shouldBe true
            verify(map, times(2)).get(mac)

            when(map.get(mac)).thenReturn(PortTS(port, Long.MaxValue))
            macTable.containsPersistent(mac, port) shouldBe true
            verify(map, times(3)).get(mac)

            when(map.get(mac)).thenReturn(PortTS(port, 0l))
            macTable.containsPersistent(mac, port) shouldBe false
            verify(map, times(4)).get(mac)
        }

        scenario("getByValue") {
            val mac = MAC.random()
            val port = UUID.randomUUID()
            when(map.snapshot).thenReturn(Map(mac -> PortTS(port, 0l)))
            macTable.getByValue(port) shouldBe Set(mac)
            verify(map).snapshot
        }

        scenario("snapshot") {
            val mac = MAC.random()
            val portTS = PortTS(UUID.randomUUID(), 0l)
            when(map.snapshot).thenReturn(Map(mac -> portTS))
            macTable.snapshot shouldBe Map(mac -> portTS.id)
            verify(map).snapshot
        }

        scenario("observable") {
            val mac = MAC.random()
            val portTS = PortTS(UUID.randomUUID(), 0l)
            val update = new Update[MAC, PortTS](mac, null, portTS)

            when(map.observable)
                .thenReturn(Observable.just[Update[MAC, PortTS]](update))
            val observer = new TestAwaitableObserver[MacTableUpdate]()
            macTable.observable.subscribe(observer)

            val timeout = new FiniteDuration(1000, TimeUnit.MILLISECONDS)
            observer.awaitOnNext(1, timeout) shouldBe true

            observer.getOnNextEvents.get(0) shouldBe
                MacTableUpdate(vlanId = 1, mac, null, portTS.id)
            verify(map).observable
        }

        scenario("close") {
            macTable.close()
            verify(map).close()
        }
    }
}
