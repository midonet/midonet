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

import scala.concurrent.duration.{Duration, DurationLong}

import org.scalatest.concurrent.Eventually

/**
 * This trait overrides the eventually trait to provide a custom patience
 * configuration used by "eventually" blocks. To change the default values for
 * the timeout used in tests, update the expiration value below.
 */
trait MidonetEventually extends Eventually {
    val expiration: Duration = 3 seconds
    override implicit val patienceConfig = PatienceConfig(timeout = scaled(expiration))

    override def eventually[T](fun: => T)(implicit config: PatienceConfig): T = {
        super.eventually(fun)(patienceConfig)
    }
}
