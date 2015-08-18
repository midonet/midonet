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

object StringUtil {

    /**
     * Returns o.toString with each line indented by the specified number of
     * spaces.
     *
     * Be aware that this will add trailing spaces if o.toString ends with a
     * newline.
     */
    def indent(o: Object, spaces: Int) = {
        val margin = " " * spaces
        margin + o.toString.replaceAllLiterally("\n", "\n" + margin)
    }

    /** Returns null if o is null, otherwise o.toString. */
    def toStringOrNull(o: AnyRef) = if (o == null) null else o.toString
}