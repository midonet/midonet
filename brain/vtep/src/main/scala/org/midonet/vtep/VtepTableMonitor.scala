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

package org.midonet.vtep

import rx.Observable

import org.midonet.cluster.data.vtep.model.VtepEntry

/**
 * An interface for providing vtep table changes via observable
 */
trait VtepTableMonitor[EntryType <: VtepEntry] {

    /** Generate an observable returning the current contents of the table
      * plus any following updates */
    def observable: Observable[VtepTableUpdate[EntryType]]
}

case class VtepTableUpdate[EntryType <: VtepEntry](oldValue: EntryType,
                                                   newValue: EntryType)

object VtepTableUpdate {
    def addition[EntryType <: VtepEntry](value: EntryType) =
        new VtepTableUpdate(null, value)
    def removal[EntryType <: VtepEntry](value: EntryType) =
        new VtepTableUpdate(value, null)
}
