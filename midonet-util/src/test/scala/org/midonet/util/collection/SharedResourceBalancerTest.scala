/*
 * Copyright 2014 Midokura SARL
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
package org.midonet.util.collection

import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, BeforeAndAfterEach, Matchers}
import org.scalatest.junit.JUnitRunner
import scala.util.Random
import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class SharedResourceBalancerTest extends FeatureSpec
                                    with Matchers {

    feature("Resources are shared") {
        scenario("Single resource") {
            val srb = new SharedResourceBalancer( List(1) )
            srb.get shouldBe 1
            srb.get shouldBe 1
            srb.get shouldBe 1
            srb.release(1)
            srb.get shouldBe 1
            srb.get shouldBe 1
        }

        scenario("Distribution is even") {
            val srb = new SharedResourceBalancer( List(1, 2, 3) )
            srb.get shouldBe 1
            srb.get shouldBe 2
            srb.get shouldBe 3
            srb.get shouldBe 1
            srb.get shouldBe 2
            srb.get shouldBe 3
            srb.get shouldBe 1
            srb.get shouldBe 2
            srb.get shouldBe 3
        }
    }

    feature("Resource can be released") {
        scenario("3 resources") {
            val srb = new SharedResourceBalancer( List(1, 2, 3) )
            srb.get shouldBe 1
            srb.get shouldBe 2
            srb.get shouldBe 3
            srb.get shouldBe 1
            srb.release(3)
            srb.get shouldBe 3
        }
    }

    feature("No failure on out-of-place release") {
        scenario("Extra release doesn't make usage count negative") {
            val srb = new SharedResourceBalancer(List(1, 2, 3))
            srb.get shouldBe 1
            srb.get shouldBe 2
            srb.get shouldBe 3
            srb.release(1)
            srb.release(2)
            srb.release(3)
            srb.release(2)
            srb.release(2)
            srb.get shouldBe 1
        }
    }
}
