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
package org.midonet.cluster.util

import java.lang.reflect.Type

import com.google.common.base.Preconditions
import org.midonet.cluster.data.ZoomConvert
import org.midonet.cluster.models.Commons.Int32Range
import org.midonet.util.Range

object RangeUtil {
    sealed class Converter extends ZoomConvert.Converter[Range[Integer],
                                                         Int32Range] {

        override def toProto(value: Range[Integer], clazz: Type): Int32Range =
            RangeUtil.toProto(value)

        override def fromProto(value: Int32Range, clazz: Type): Range[Integer] =
            RangeUtil.fromProto(value)
    }

    implicit def toProto(range: Range[Integer]): Int32Range = {
        val builder = Int32Range.newBuilder
        if (range.start() ne null) builder.setStart(range.start())
        if (range.end() ne null) builder.setEnd(range.end())
        builder.build()
    }

    implicit def fromProto(value: Int32Range): Range[Integer] = {
        new Range[Integer](if (value.hasStart) value.getStart else null,
                           if (value.hasEnd) value.getEnd else null)
    }

    /**
     * This method converts a range provided as string either as a range
     * delimited by ':' ("start:end") or as a single number ("80'), to an
     * Int32Range object.
     */
    def strToInt32Range(rangeStr: String): Int32Range = {
        Preconditions.checkNotNull(rangeStr)
        val range = rangeStr.split(':')
        range.length match {
            case 1 =>
                Int32Range.newBuilder
                    .setStart(range(0).toInt)
                    .setEnd(range(0).toInt).build()
            case 2 =>
                Int32Range.newBuilder
                    .setStart(range(0).toInt)
                    .setEnd(range(1).toInt).build()
            case _ =>
                throw new IllegalArgumentException("Invalid range " + rangeStr)
        }
    }
}
