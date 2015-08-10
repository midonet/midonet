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

import org.midonet.cluster.data.storage.MergedMap.Update
import org.midonet.cluster.data.storage.state_table.RouterArpCacheMergedMap.{ArpCacheOrdering, ArpCacheUpdate, ArpEntryTS}
import org.midonet.cluster.data.storage.{ArpCacheEntry, MergedMap}
import org.midonet.packets.{IPv4Addr, MAC}
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class ArpCacheMergedMapTest extends FeatureSpec with BeforeAndAfter
                            with Matchers {
    
    var map: MergedMap[IPv4Addr, ArpEntryTS] = _
    var arpCache: RouterArpCacheMergedMap = _
    
    before {
        map = mock(classOf[MergedMap[IPv4Addr, ArpEntryTS]])
        arpCache = new RouterArpCacheMergedMap(UUID.randomUUID(), map)
    }

    private def newArpEntry(mac: MAC): ArpCacheEntry =
        new ArpCacheEntry(mac, stale = 0l, expiry = 0l, lastArp = 0l)

    feature("StateTable trait methods") {
        scenario("add") {
            val ip = IPv4Addr.random
            val arpEntry = newArpEntry(MAC.random())

            val arg1 = ArgumentCaptor.forClass(classOf[IPv4Addr])
            val arg2 = ArgumentCaptor.forClass(classOf[ArpEntryTS])
            arpCache.add(ip, arpEntry)
            verify(map).putOpinion(arg1.capture(), arg2.capture())
            arg1.getValue shouldBe ip
            arg2.getValue.arpEntry shouldBe arpEntry

            arpCache.addPersistent(ip, arpEntry)
            verify(map, times(2)).putOpinion(arg1.capture(), arg2.capture())
            arg1.getValue shouldBe ip
            arg2.getValue.arpEntry shouldBe arpEntry
            arg2.getValue.ts shouldBe Long.MaxValue
        }

        scenario("get") {
            val ip = IPv4Addr.random
            arpCache.get(ip) shouldBe null

            val arpEntryTS = ArpEntryTS(newArpEntry(MAC.random()), 0L)
            when(map.get(ip)).thenReturn(arpEntryTS)
            arpCache.get(ip) shouldBe arpEntryTS.arpEntry
        }

        scenario("remove") {
            val ip = IPv4Addr.random
            val arpEntry = newArpEntry(MAC.random())

            arpCache.remove(ip)
            verify(map).removeOpinion(ip)

            arpCache.remove(ip, arpEntry)
            verify(map, times(2)).removeOpinion(ip)
        }

        scenario("contains") {
            val ip = IPv4Addr.random
            val arpEntry = newArpEntry(MAC.random())

            arpCache.contains(ip)
            verify(map).containsKey(ip)

            arpCache.contains(ip, arpEntry) shouldBe false
            verify(map).get(ip)

            when(map.get(ip)).thenReturn(ArpEntryTS(arpEntry, 0l))
            arpCache.contains(ip, arpEntry) shouldBe true
            verify(map, times(2)).get(ip)

            when(map.get(ip)).thenReturn(null)
            arpCache.contains(ip, arpEntry) shouldBe false
            verify(map, times(3)).get(ip)

            when(map.get(ip)).thenReturn(ArpEntryTS(arpEntry, Long.MaxValue))
            arpCache.containsPersistent(ip, arpEntry) shouldBe true
            verify(map, times(4)).get(ip)

            when(map.get(ip)).thenReturn(ArpEntryTS(arpEntry, 0l))
            arpCache.containsPersistent(ip, arpEntry) shouldBe false
            verify(map, times(5)).get(ip)

            when(map.get(ip)).thenReturn(null)
            arpCache.containsPersistent(ip, arpEntry) shouldBe false
            verify(map, times(6)).get(ip)
        }

        scenario("getByValue") {
            val ip = IPv4Addr.random
            val arpEntry = newArpEntry(MAC.random())
            when(map.snapshot).thenReturn(Map(ip -> ArpEntryTS(arpEntry, 0l)))
            arpCache.getByValue(arpEntry) shouldBe Set(ip)
            verify(map).snapshot
        }

        scenario("snapshot") {
            val ip = IPv4Addr.random
            val arpEntryTS = ArpEntryTS(newArpEntry(MAC.random()), 0l)
            when(map.snapshot).thenReturn(Map(ip -> arpEntryTS))
            arpCache.snapshot shouldBe Map(ip -> arpEntryTS.arpEntry)
            verify(map).snapshot
        }

        scenario("observable") {
            val ip = IPv4Addr.random
            val arpEntryTS1 = ArpEntryTS(newArpEntry(MAC.random()), 0l)
            val arpEntryTS2 = ArpEntryTS(newArpEntry(MAC.random()), 1l)
            val update1 = new Update[IPv4Addr, ArpEntryTS](ip, null, arpEntryTS1)
            val update2 = new Update[IPv4Addr, ArpEntryTS](ip, arpEntryTS1, null)
            val update3 = new Update[IPv4Addr, ArpEntryTS](ip, arpEntryTS1,
                                                           arpEntryTS2)
            when(map.observable)
                .thenReturn(Observable.just[Update[IPv4Addr, ArpEntryTS]](update1,
                                                                          update2,
                                                                          update3))
            val observer = new TestAwaitableObserver[ArpCacheUpdate]()
            arpCache.observable.subscribe(observer)

            val timeout = new FiniteDuration(1000, TimeUnit.MILLISECONDS)
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBe
                ArpCacheUpdate(ip, null, arpEntryTS1.arpEntry)
            verify(map).observable

            observer.awaitOnNext(2, timeout) shouldBe true
            observer.getOnNextEvents.get(1) shouldBe
                ArpCacheUpdate(ip, arpEntryTS1.arpEntry, null)

            observer.awaitOnNext(3, timeout) shouldBe true
            observer.getOnNextEvents.get(2) shouldBe
                ArpCacheUpdate(ip, arpEntryTS1.arpEntry, arpEntryTS2.arpEntry)
        }

        scenario("close") {
            arpCache.close()
            verify(map).close()
        }
    }

    feature("ArpCacheUpdate") {
        scenario("toString of an ArpCacheUpdate") {
            val ip = IPv4Addr.random
            val oldEntry = newArpEntry(MAC.random())
            val newEntry = newArpEntry(MAC.random())
            val update1 = ArpCacheUpdate(ip, oldEntry, newEntry)
            update1.toString shouldBe s"[ip=$ip oldEntry=${oldEntry.toString} " +
                                      s"newEntry=${newEntry.toString}]"

            val update2 = ArpCacheUpdate(ip, null, newEntry)
            update2.toString shouldBe s"[ip=$ip oldEntry=null " +
                                      s"newEntry=${newEntry.toString}]"

            val update3 = ArpCacheUpdate(ip, oldEntry, null)
            update3.toString shouldBe s"[ip=$ip oldEntry=${oldEntry.toString} " +
                                      s"newEntry=null]"
        }
    }

    feature("ArpCacheOrdering") {
        scenario("Comparing two ArpEntryTS") {
            val ordering = new ArpCacheOrdering()
            val arpEntry1 = ArpEntryTS(newArpEntry(MAC.random()), 0l)
            val arpEntry2 = ArpEntryTS(newArpEntry(MAC.random()), 1l)

            ordering.compare(arpEntry1, arpEntry2) shouldBe -1
            ordering.compare(arpEntry2, arpEntry1) shouldBe 1
            ordering.compare(arpEntry1, arpEntry1) shouldBe 0
        }
    }
}
