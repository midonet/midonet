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

package org.midonet.cluster.util

import java.util.{UUID => JUUID}

import org.scalatest.FlatSpec

class UUIDUtilTest extends FlatSpec {

    import UUIDUtil._

    "Random UUIDs" should "translate back and forth correctly" in {
        1 to 10 foreach { i =>
            val uuid1 = JUUID.randomUUID()
            assert(uuid1 == UUIDUtil.fromProto(UUIDUtil.toProto(uuid1)))

            val uuid2 = java.util.UUID.randomUUID()
            assert(uuid2 == uuid2.asProto.asJava)
        }
    }
}
