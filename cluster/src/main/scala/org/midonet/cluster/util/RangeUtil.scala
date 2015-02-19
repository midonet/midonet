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

import java.lang.reflect.Type

import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Commons.Int32Range
import org.midonet.util.Range

object RangeUtil {
    sealed class Converter extends ZoomConvert.Converter[Range[Integer],
                                                         Int32Range] {

        override def toProto(value: Range[Integer], clazz: Type): Int32Range =
            Int32Range.newBuilder
                .setStart(value.start())
                .setEnd(value.end())
                .build()

        override def fromProto(value: Int32Range, clazz: Type): Range[Integer] = {
            val range = new Range[Integer](value.getStart, value.getEnd)
            range
        }
    }
}
