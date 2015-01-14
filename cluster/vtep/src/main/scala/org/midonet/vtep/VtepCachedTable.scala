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

import java.util.UUID

import scala.concurrent.Future

import org.midonet.cluster.data.vtep.model.VtepEntry
import org.midonet.vtep.schema.Table

/**
 * A local mirror of a VTEP cache
 */
trait VtepCachedTable[T <: Table, Entry <: VtepEntry] {
    def get(id: UUID): Option[Entry]
    def getAll: Map[UUID, Entry]
    def insert(row: Entry): Future[UUID]
    def ready: Future[Boolean]
    def isReady: Boolean
}
