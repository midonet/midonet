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
import java.util.concurrent.TimeUnit

import scala.concurrent.duration.FiniteDuration

import org.junit.runner.RunWith
import org.mockito.ArgumentCaptor
import org.mockito.Mockito._
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FeatureSpec, Matchers}
import rx.Observable

import org.midonet.cluster.data.storage.MergedMap
import org.midonet.cluster.data.storage.MergedMap.Update
import org.midonet.cluster.data.storage.state_table.BridgeArpTableMergedMap.{ArpTableUpdate, MacOrdering, MacTS}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class ArpTableMergedMapTest extends FeatureSpec with BeforeAndAfter
                            with Matchers {

    var map: MergedMap[IPv4Addr, MacTS] = _
    var arpTable: BridgeArpTableMergedMap = _

    before {
       map = mock(classOf[MergedMap[IPv4Addr, MacTS]])
       arpTable = new BridgeArpTableMergedMap(UUID.randomUUID(), map)
    }

    feature("StateTable trait methods") {
        scenario("add") {
            val ip = IPv4Addr.random
            val mac = MAC.random()

            val arg1 = ArgumentCaptor.forClass(classOf[IPv4Addr])
            val arg2 = ArgumentCaptor.forClass(classOf[MacTS])
            arpTable.add(ip, mac)
            verify(map).putOpinion(arg1.capture(), arg2.capture())
            arg1.getValue shouldBe ip
            arg2.getValue.mac shouldBe mac

            arpTable.addPersistent(ip, mac)
            verify(map, times(2)).putOpinion(arg1.capture(), arg2.capture())
            arg1.getValue shouldBe ip
            arg2.getValue.mac shouldBe mac
            arg2.getValue.ts shouldBe Long.MaxValue
        }

        scenario("get") {
            val ip = IPv4Addr.random
            arpTable.get(ip) shouldBe null

            val macTS = MacTS(MAC.random(), 0L)
            when(map.get(ip)).thenReturn(macTS)
            arpTable.get(ip) shouldBe macTS.mac
        }

        scenario("remove") {
            val ip = IPv4Addr.random
            val mac = MAC.random()

            arpTable.remove(ip)
            verify(map).removeOpinion(ip)

            arpTable.remove(ip, mac)
            verify(map, times(2)).removeOpinion(ip)
        }

        scenario("contains") {
            val ip = IPv4Addr.random
            val mac = MAC.random()

            arpTable.contains(ip)
            verify(map).containsKey(ip)

            arpTable.contains(ip, mac) shouldBe false
            verify(map).get(ip)

            when(map.get(ip)).thenReturn(MacTS(mac, 0l))
            arpTable.contains(ip, mac) shouldBe true
            verify(map, times(2)).get(ip)

            when(map.get(ip)).thenReturn(null)
            arpTable.contains(ip, mac) shouldBe false
            verify(map, times(3)).get(ip)

            when(map.get(ip)).thenReturn(MacTS(mac, Long.MaxValue))
            arpTable.containsPersistent(ip, mac) shouldBe true
            verify(map, times(4)).get(ip)

            when(map.get(ip)).thenReturn(MacTS(mac, 0l))
            arpTable.containsPersistent(ip, mac) shouldBe false
            verify(map, times(5)).get(ip)

            when(map.get(ip)).thenReturn(null)
            arpTable.containsPersistent(ip, mac) shouldBe false
            verify(map, times(6)).get(ip)
        }

        scenario("getByValue") {
            val ip = IPv4Addr.random
            val mac = MAC.random()
            when(map.snapshot).thenReturn(Map(ip -> MacTS(mac, 0l)))
            arpTable.getByValue(mac) shouldBe Set(ip)
            verify(map).snapshot
        }

        scenario("snapshot") {
            val ip = IPv4Addr.random
            val macTS = MacTS(MAC.random(), 0l)
            when(map.snapshot).thenReturn(Map(ip -> macTS))
            arpTable.snapshot shouldBe Map(ip -> macTS.mac)
            verify(map).snapshot
        }

        scenario("observable") {
            val ip = IPv4Addr.random
            val macTS1 = MacTS(MAC.random(), 0l)
            val macTS2 = MacTS(MAC.random(), 1l)
            val update1 = new Update[IPv4Addr, MacTS](ip, null, macTS1)
            val update2 = new Update[IPv4Addr, MacTS](ip, macTS1, null)
            val update3 = new Update[IPv4Addr, MacTS](ip, macTS1, macTS2)

            when(map.observable)
                .thenReturn(Observable.just[Update[IPv4Addr, MacTS]](update1,
                                                                     update2,
                                                                     update3))
            val observer = new TestAwaitableObserver[ArpTableUpdate]()
            arpTable.observable.subscribe(observer)

            val timeout = new FiniteDuration(1000, TimeUnit.MILLISECONDS)
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe
                ArpTableUpdate(ip, null, macTS1.mac)
            verify(map).observable

            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBe
                ArpTableUpdate(ip, macTS1.mac, null)

            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents.get(2) shouldBe
                ArpTableUpdate(ip, macTS1.mac, macTS2.mac)
        }

        scenario("close") {
            arpTable.close()
            verify(map).close()
        }
    }

    feature("ArpTableUpdate") {
        scenario("toString of an ArpTableUpdate") {
            val ip = IPv4Addr.random
            val oldMac = MAC.random()
            val newMac = MAC.random()

            val update = ArpTableUpdate(ip, oldMac, newMac)
            update.toString shouldBe s"[ip=$ip oldMAC=$oldMac " +
                                     s"newMac=$newMac]"

            val update2 = ArpTableUpdate(ip, null, newMac)
            update2.toString shouldBe s"[ip=$ip oldMAC=null " +
                                      s"newMac=$newMac]"

            val update3 = ArpTableUpdate(ip, oldMac, null)
            update3.toString shouldBe s"[ip=$ip oldMAC=$oldMac " +
                                      s"newMac=null]"
        }
    }

    feature("MacOrdering") {
        scenario("Comparing two MacTS") {
            val macOrdering = new MacOrdering()

            val macTS1 = MacTS(MAC.random(), 0l)
            val macTS2 = MacTS(MAC.random(), 1l)

            macOrdering.compare(macTS1, macTS2) shouldBe -1
            macOrdering.compare(macTS2, macTS1) shouldBe 1
            macOrdering.compare(macTS1, macTS1) shouldBe 0
        }
    }
}
