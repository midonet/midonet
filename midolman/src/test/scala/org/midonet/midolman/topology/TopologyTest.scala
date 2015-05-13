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

package org.midonet.midolman.topology

import org.scalatest.exceptions.TestFailedException

import rx.observers.TestObserver

import org.midonet.midolman.topology.VirtualTopology.Device
import org.midonet.util.reactivex.{AssertableObserver, AwaitableObserver}

object TopologyTest {

    class DeviceObserver[D <: Device](vt: VirtualTopology)
        extends TestObserver[D] with AwaitableObserver[D]
                with AssertableObserver[D] {

        val threadId = Thread.currentThread().getId

        override def assert() = {
            val currentThreadId = Thread.currentThread().getId
            if ((currentThreadId != threadId) &&
                (currentThreadId != vt.vtThreadId)) {
                throw new TestFailedException(
                    s"Call on thread $currentThreadId but expected on test " +
                    s"thread $threadId or VT thread ${vt.vtThreadId}", 0)
            }
        }
    }

}
