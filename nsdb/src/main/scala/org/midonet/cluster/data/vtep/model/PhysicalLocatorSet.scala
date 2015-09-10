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

import java.util.{Objects, UUID}

/**
 * Represents a VTEP's physical locator set. The set of locators may be empty,
 * but not null.
 *
 * Note that several switches don't really support multiple locator entries,
 * so we'll use only one.  The public constructors for this object don't
 * take sets for this reason.
 *
 * @param id is UUID of this locator set in OVSDB
 * @param locators is the (immutable) set of the ids of locators in this set
 */
final class PhysicalLocatorSet(id: UUID, locators: Option[String])
    extends VtepEntry {
    override val uuid = if (id == null) UUID.randomUUID() else id

    val locatorIds: Set[String] = if (locators == null) Set()
                                  else locators.toSet

    override def toString: String = "PhysicalLocatorSet{" +
        "uuid=" + uuid + ", " +
        "locatorIds='" + locatorIds + "'}"

    override def equals(o: Any): Boolean = o match {
        case that: PhysicalLocatorSet =>
            Objects.equals(uuid, that.uuid) &&
            Objects.equals(locatorIds, that.locatorIds)
        case other => false
    }
}

object PhysicalLocatorSet {
    def apply(id: UUID, locator: String): PhysicalLocatorSet =
        new PhysicalLocatorSet(id, Option(locator))

    def apply(locator: String): PhysicalLocatorSet =
        new PhysicalLocatorSet(null, Option(locator))
}



