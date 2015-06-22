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

package org.midonet.cluster.flowhistory

import java.util.UUID
import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, Matchers}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class TestSerialization extends FeatureSpec with Matchers {
    feature("Json serialization/deserialization")
    {
        scenario("serializing & serialized objects are the same") {
            for (i <- 0 until 100) {
                val flowRecord = FlowRecord.random

                val serializer = new JsonSerialization
                val buf = serializer.flowRecordToBuffer(flowRecord)

                println(new String(buf))

                val flowRecord2 = serializer.bufferToFlowRecord(buf)

                flowRecord2 should be (flowRecord)
            }
        }
    }
}
