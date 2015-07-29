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
import org.mockito.Matchers.{eq => Eq, same}
import org.mockito.Mockito
import org.mockito.Mockito.when
import org.scalatest._
import rx.Observable
import rx.subjects.PublishSubject

import org.midonet.cluster.data.storage._
import org.midonet.cluster.models.Topology.TunnelZone
import org.midonet.cluster.models.Topology.TunnelZone.Type
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.services.MidonetBackend.TunnelZoneKey
import org.midonet.cluster.topology.TopologyBuilder
import org.midonet.cluster.util.UUIDUtil.fromProto

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
    var backend: MidonetBackend = _

    before {
        _stateStore = Mockito.mock(classOf[StateStorage])
        backend = new MidonetBackend { // Easier than mocking
            override def stateStore: StateStorage = _stateStore
            override def store: Storage = ???
            override def curator: CuratorFramework = ???
            override def doStop(): Unit = ???
            override def doStart(): Unit = ???
        }
    }

    feature("The herald publishes updates properly") {
        scenario("Happy case of the fp lifecycle") {

            val stateInjector = PublishSubject.create[StateKey]
            val initialState = SingleValueKey(TunnelZoneKey,
                                              Some(h1Id.toString), 0)

            when (
                _stateStore.keyObservable(same(classOf[TunnelZone]), Eq(tzId),
                                         same(TunnelZoneKey))
            ).thenReturn(stateInjector)

            when (
                _stateStore.addValue(same(classOf[TunnelZone]), Eq(tzId),
                                    same(TunnelZoneKey), Eq(h2Id.toString))
            ).thenReturn(Observable.just(StateResult(0)))

            val herald = new WritableFloodingProxyHerald(backend,
                                                         sameThreadExecutor())

            tzsObservable.onNext(Observable.just(vtepTz))
            herald.lookup(tzId) shouldBe None

            stateInjector.onNext(initialState)

            herald.announce(tzId, h2Id, inocuousRetrier)
            // mock the NSDB notifying back
            stateInjector.onNext(SingleValueKey(TunnelZoneKey,
                                                Some(h2Id.toString), 0))

            herald.lookup(tzId) shouldBe Some(h2Id)

            // Test filtering of non-VTEP tunnel zones:
            when (
                _stateStore.keyObservable(same(classOf[TunnelZone]), Eq(tzId),
                                        same(TunnelZoneKey))
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
                _stateStore.keyObservable(same(classOf[TunnelZone]), Eq(tzId),
                                         same(TunnelZoneKey))
            ).thenReturn(stateInjector)

            when (
                _stateStore.addValue(same(classOf[TunnelZone]), Eq(tzId),
                                    same(TunnelZoneKey), Eq(h2Id.toString))
            ).thenReturn(
                Observable.error[StateResult](new NullPointerException)
            )

            val herald = new WritableFloodingProxyHerald(backend,
                                                         sameThreadExecutor())
            herald.lookup(tzId) shouldBe None

            herald.announce(tzId, h2Id, retry)

            latch.await(1, TimeUnit.SECONDS) shouldBe true

            // The change wasn't applied
            herald.lookup(tzId) shouldBe None
        }
    }
}
