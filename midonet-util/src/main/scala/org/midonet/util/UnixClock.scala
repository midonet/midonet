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
package org.midonet.util

import scala.compat.Platform

object UnixClock {
    val DEFAULT: UnixClock = new UnixClock {
        override def time: Long = Platform.currentTime
    }

    val MOCK = new MockUnixClock()

    def get: UnixClock = {
        val p = System.getProperties
        val useMock: String = p.getProperty(USE_MOCK_CLOCK_PROPERTY)
        if ((useMock ne null) && useMock == "yes")
            MOCK
        else
            DEFAULT
    }

    def time = get.time
    def timeNanos = get.timeNanos

    val USE_MOCK_CLOCK_PROPERTY = "org.midonet.internal.tests.use_mock_clock"
}

trait UnixClock {
    def time: Long
    def timeNanos: Long = time * 1000 * 1000
    def isMock = false
}

class MockUnixClock extends UnixClock {
    var time: Long = 0
    override def isMock = true
}
