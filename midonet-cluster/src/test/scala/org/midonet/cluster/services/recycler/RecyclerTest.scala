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

package org.midonet.cluster.services.recycler

import org.junit.runner.RunWith
import org.scalatest.FeatureSpec
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.util.CuratorTestFramework

@RunWith(classOf[JUnitRunner])
class RecyclerTest extends FeatureSpec with CuratorTestFramework {

    feature("Recycler deletes unused namespaces") {
        scenario("Old namespaces for non-existing hosts") {

        }

        scenario("Recycler does not delete new namespaces") {

        }

        scenario("Recycler does not delete the cluster namespace") {

        }
    }

    feature("Recycler deletes unused objects") {

    }
}
