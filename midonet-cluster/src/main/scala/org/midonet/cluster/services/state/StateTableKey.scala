/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.cluster.services.state

import java.util.UUID

import com.google.common.base.MoreObjects

/**
  * Represents the index key for a state table. Each table is uniquely
  * identified by such a key.
  */
case class StateTableKey(objectClass: Class[_], objectId: UUID,
                         keyClass: Class[_], valueClass: Class[_],
                         tableName: String, tableArgs: Seq[String]) {

    override def toString: String = {
        MoreObjects.toStringHelper(getClass).omitNullValues()
            .add("objectClass", objectClass)
            .add("objectId", objectId)
            .add("keyClass", keyClass)
            .add("valueClass", valueClass)
            .add("tableName", tableName)
            .add("tableArgs", tableArgs)
            .toString
    }
}
