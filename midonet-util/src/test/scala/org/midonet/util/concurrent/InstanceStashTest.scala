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

package org.midonet.util.concurrent

import org.junit.runner.RunWith
import org.scalatest.{OneInstancePerTest, Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class InstanceStashTest extends FeatureSpec
                             with Matchers
                             with OneInstancePerTest {

    case class MacGuffin(var what: Int)

    val stash = new InstanceStash1[MacGuffin, Int](() => MacGuffin(0), (m, w) => m.what = w)

    feature("Instance stash operations") {
        scenario("Object creation") {
            stash.size should be (0)
            stash.leasedInstances should be (0)
            stash(1).what should be (1)
        }

        scenario("reUp") {
            val o1 = stash(1)
            o1.what should be (1)
            stash.reUp()
            stash(2).what should be (2)
            o1.what should be (2)
        }

        scenario("size reporting") {
            stash(1)
            stash.size should be (0)
            stash.leasedInstances should be (1)

            stash(1)
            stash.size should be (0)
            stash.leasedInstances should be (2)

            stash(1)
            stash.size should be (0)
            stash.leasedInstances should be (3)

            stash.reUp()

            stash.size should be (3)
            stash.leasedInstances should be (0)

            stash(1)
            stash.size should be (2)
            stash.leasedInstances should be (1)

            stash(1)
            stash.size should be (1)
            stash.leasedInstances should be (2)

            stash(1)
            stash.size should be (0)
            stash.leasedInstances should be (3)

            stash(1)
            stash.size should be (0)
            stash.leasedInstances should be (4)
        }
    }
}
