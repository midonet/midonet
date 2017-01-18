/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.discovery

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import rx.Observable

@RunWith(classOf[JUnitRunner])
class MidonetDiscoverySelectorTest extends FeatureSpec with Matchers {

    class MockDiscoveryClient[T](var instances: Seq[T])
        extends MidonetDiscoveryClient[T] {

        override val observable = Observable.empty[Seq[T]]()

        override def stop(): Unit = {
        }
    }

    def expect[T,S <: Seq[T]](selector: MidonetDiscoverySelector[T],
                              expected: S): Unit = {

        if (expected.isEmpty) {
            selector.getInstance.isDefined shouldBe false
        } else {
            for (item <- expected) {
                val opt = selector.getInstance
                opt.isDefined shouldBe true
                opt.get shouldBe item
            }
        }
    }

    def expect[T,S <: Seq[T]](
            factory: MidonetDiscoverySelector.Factory[T],
            given: S,
            expected: S): Unit =
        expect(factory(new MockDiscoveryClient(given)), expected)

    feature("round-robin selector") {
        scenario("empty list") {
            expect(MidonetDiscoverySelector.roundRobin[AnyVal],
                   List(),
                   List())
        }

        scenario("single instance") {
            expect(MidonetDiscoverySelector.roundRobin[Int],
                   List(1),
                   List(1,1,1,1,1,1,1))
        }

        scenario("many instances") {
            expect(MidonetDiscoverySelector.roundRobin[Int],
                   List(1,2,3),
                   List(1,2,3,1,2,3,1,2,3))
        }

        scenario("on change") {
            val client = new MockDiscoveryClient[Int](List(1,2,3))
            val selector = MidonetDiscoverySelector.roundRobin(client)
            expect(selector, List(1))
            client.instances = List(3)
            expect(selector, List(3,3))
            client.instances = List()
            expect(selector, List())
            expect(selector, List())
            client.instances = List(1,2,3,4,5)
            expect(selector, List(4,5,1))
        }
    }

    feature("first selector") {
        scenario("empty list") {
            expect(MidonetDiscoverySelector.first,
                   List(),
                   List())
        }

        scenario("single instance") {
            expect(MidonetDiscoverySelector.first[Int],
                   List(1),
                   List(1,1,1,1,1,1,1))
        }

        scenario("many instances") {
            expect(MidonetDiscoverySelector.first[Int],
                   List(1,2,3),
                   List(1,1,1,1,1,1,1,1,1))
        }

        scenario("on change") {
            val client = new MockDiscoveryClient[Int](List(1,2,3))
            val selector = MidonetDiscoverySelector.first(client)
            expect(selector, List(1))
            client.instances = List(3)
            expect(selector, List(3,3))
            client.instances = List()
            expect(selector, List())
            expect(selector, List())
            client.instances = List(1,2,3,4,5)
            expect(selector, List(1,1,1))
        }
    }

    feature("random selector") {
        scenario("empty list") {
            expect(MidonetDiscoverySelector.random,
                   List(),
                   List())
        }
    }

    scenario("single instance") {
        expect(MidonetDiscoverySelector.random[Int],
               List(1),
               List(1,1,1,1,1,1,1))
    }

    scenario("updated instances") {
        val client = new MockDiscoveryClient[Int](List())
        val selector = MidonetDiscoverySelector.random[Int](client)

        def validate(list: List[Int]): Unit = {
            client.instances = list
            for (i <- 1 to 20) {
                val value = selector.getInstance
                if (list.nonEmpty) {
                    value.isDefined shouldBe true
                    list.contains(value.get) shouldBe true
                } else {
                    value.isDefined shouldBe false
                }
            }
        }
        validate(List(1,2,3))
        validate(List(10))
        validate(List())
    }
}
