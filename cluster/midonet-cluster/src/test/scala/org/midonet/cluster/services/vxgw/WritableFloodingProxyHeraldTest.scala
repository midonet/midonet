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

package org.midonet.cluster.services.vxgw

import java.util.UUID
import java.util.concurrent.{CountDownLatch, TimeUnit}

import com.google.common.util.concurrent.MoreExecutors.sameThreadExecutor
import org.apache.curator.framework.CuratorFramework
import org.junit.runner.RunWith
import org.mockito.Matchers.{anyObject, eq => Eq, same}
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.models.Topology.TunnelZone.Type
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.FloodingProxyKey
import org.midonet.cluster.services.vxgw.FloodingProxyHerald.FloodingProxy
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil.fromProto
import org.midonet.packets.IPv4Addr

@RunWith(classOf[JUnitRunner])
class WritableFloodingProxyHeraldTest extends FeatureSpec
                                      with ShouldMatchers
                                      with Matchers
                                      with TopologyBuilder
                                      with BeforeAndAfter {

    val vtepTz = createTunnelZone(UUID.randomUUID(), Type.VTEP)

    val inocuousRetrier = (t: Throwable) => {}

    val tzsObservable = PublishSubject.create[Observable[TunnelZone]]

    val tzId = fromProto(vtepTz.getId)
    val h1Id = UUID.randomUUID()
    val h2Id = UUID.randomUUID()

    var _stateStore: StateStorage = _
    var _store: Storage = _
    var backend: MidonetBackend = _

    before {
        _stateStore = Mockito.mock(classOf[StateStorage])
        _store = Mockito.mock(classOf[Storage])
        backend = new MidonetBackend { // Easier than mocking
            override def stateStore: StateStorage = _stateStore
            override def store: Storage = _store
            override def curator: CuratorFramework = ???
            override def doStop(): Unit = ???
            override def doStart(): Unit = ???
        }
    }

    feature("The herald publishes updates properly") {

        scenario("State key serialization") {
            val fp = FloodingProxy(UUID.randomUUID(), IPv4Addr.random)
            val _1 = FloodingProxyHerald.deserialize(s"${fp.hostId}#${fp.tunnelIp}")
            val _2 = FloodingProxy(fp.hostId, fp.tunnelIp)
            _1 shouldBe _2
            _1.serialized shouldBe FloodingProxy(fp.hostId, fp.tunnelIp).serialized
        }

        scenario("Happy case of the fp lifecycle") {

            val initialFp = FloodingProxy(h1Id, IPv4Addr.random)
            val stateInjector = PublishSubject.create[StateKey]
            val initialState = SingleValueKey(FloodingProxyKey,
                                              Some(initialFp.serialized), 0)

            when (
                _stateStore.keyObservable(anyObject(), anyObject(), anyObject())
            ).thenReturn(stateInjector)

            when (
                _stateStore.addValue(anyObject(), anyObject(), anyObject(),
                                     anyObject())
            ).thenReturn(Observable.just(StateResult(0)))

            when(
                _store.observable(anyObject[Class[TunnelZone]]())
            ).thenReturn(tzsObservable.asObservable())

            val herald = new WritableFloodingProxyHerald(backend,
                                                         sameThreadExecutor())

            tzsObservable.onNext(Observable.just(vtepTz))
            herald.lookup(tzId) shouldBe None

            stateInjector.onNext(initialState)

            val fp = FloodingProxy(h2Id, IPv4Addr.random)
            herald.announce(tzId, fp, inocuousRetrier)
            // mock the NSDB notifying back
            stateInjector.onNext(SingleValueKey(FloodingProxyKey,
                                                Some(fp.serialized), 0))

            herald.lookup(tzId) shouldBe Some(fp)

            // Test filtering of non-VTEP tunnel zones:
            when (
                _stateStore.keyObservable(same(classOf[TunnelZone]), Eq(tzId),
                                        same(FloodingProxyKey))
            ).thenThrow(new IllegalStateException("UNEXPECTED"))

            val greTzId = UUID.randomUUID()
            val greTz = createTunnelZone(greTzId, Type.GRE)
            tzsObservable.onNext(Observable.just(greTz))

            herald.lookup(greTzId) shouldBe None
        }

        scenario("An announcement fails and the retry is invoked") {

            val stateInjector = PublishSubject.create[StateKey]
            val latch = new CountDownLatch(1)
            val retry = (t: Throwable) => latch.countDown()

            when (
                _stateStore.keyObservable(anyObject(), anyObject(), anyObject())
            ).thenReturn(stateInjector)

            when (
                _stateStore.addValue(anyObject(), anyObject(), anyObject(),
                                     anyObject())
            ).thenReturn(
                Observable.error[StateResult](new NullPointerException)
            )

            when(
                _store.observable(anyObject[Class[TunnelZone]]())
            ).thenReturn(
                Observable.error[Observable[TunnelZone]](new NullPointerException)
            )

            val herald = new WritableFloodingProxyHerald(backend,
                                                         sameThreadExecutor())
            herald.lookup(tzId) shouldBe None

            val fp = FloodingProxy(h2Id, IPv4Addr.random)
            herald.announce(tzId, fp, retry)

            latch.await(1, TimeUnit.SECONDS) shouldBe true

            // The change wasn't applied
            herald.lookup(tzId) shouldBe None
        }
    }
}
