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

package org.midonet.cluster.data.vtep.model

import java.util
import java.util.{Objects, UUID}

/**
 * Represents a VTEP's physical locator set. The set of locators may be empty,
 * but not null.
 * @param uuid is UUID of this locator set in OVSDB
 * @param locators is the (immutable) set of the ids of locators in this set
 */
final class PhysicalLocatorSet(val uuid: UUID, locators: util.Set[UUID]) {
    val locatorIds: util.Set[UUID] =
        if (locators == null) new util.HashSet[UUID]() else locators

    override def toString: String = "PhysicalLocatorSet{" +
        "uuid=" + uuid + ", " +
        "locatorIds='" + locatorIds + "'}"

    override def equals(o: Any): Boolean = o match {
        case null => false
        case that: PhysicalLocatorSet =>
            Objects.equals(uuid, that.uuid) &&
            Objects.equals(locatorIds, that.locatorIds)
        case other => false
    }

    override def hashCode: Int =
        if (uuid == null) Objects.hash(locatorIds) else uuid.hashCode
}

object PhysicalLocatorSet {
    def apply(id: UUID, locators: util.Set[UUID]): PhysicalLocatorSet =
        new PhysicalLocatorSet(id, locators)
    def apply(locators: util.Set[UUID]): PhysicalLocatorSet =
        new PhysicalLocatorSet(null, locators)
}



