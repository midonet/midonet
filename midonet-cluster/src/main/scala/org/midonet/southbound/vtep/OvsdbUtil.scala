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

package org.midonet.southbound.vtep

import java.lang.{Long => JLong}
import java.util.{HashMap => JHashMap, HashSet => JHashSet, Map => JMap, Set => JSet}
import java.util.UUID

import scala.collection.JavaConverters._

import org.opendaylight.ovsdb.lib.notation.{UUID => OvsdbUUID}

import org.midonet.packets.IPv4Addr
import org.midonet.util.functors.makeAction0
import org.midonet.util.logging.Logger

/**
 * Type translations to and from ovsdb-specific data models
 * This code also 'normalizes' sets and maps, so that null values from ovsdb
 * are converted into the corresponding empty collections. The guarantee of not
 * being null simplifies the rest of the code.
 * TODO: when converting to ovsdb, the new code should not provide null values
 * when a collection is required. The checks should be removed when the legacy
 * code is replaced by the new one.
 */
object OvsdbUtil {

    private final val EmptyJavaUuidSet = new JHashSet[UUID]()
    private final val EmptyJavaUuidMap = new JHashMap[JLong, UUID]()
    private final val EmptyOvsdbUuidSet = new JHashSet[OvsdbUUID]()
    private final val EmptyOvsdbUuidMap = new JHashMap[JLong, OvsdbUUID]()
    private final val EmptyStringSet = new JHashSet[String]()
    private final val EmptyIPv4AddrSet = new JHashSet[IPv4Addr]()

    /** Provides extension methods for [[UUID]]. */
    class RichUUID(val id: UUID) extends AnyVal {
        def asOvsdb = toOvsdb(id)
    }

    /** Provides extension methods for [[OvsdbUUID]]. */
    class RichOvsdbUUID(val id: OvsdbUUID) extends AnyVal {
        def asJava = fromOvsdb(id)
    }

    /** Convert from Ovsdb UUIDs to Java UUIDs */
    @inline
    implicit def fromOvsdb(id: OvsdbUUID): UUID = UUID.fromString(id.toString)

    /** Convert from Java UUIDs to Ovsdb UUIDs */
    @inline
    implicit def toOvsdb(id: UUID): OvsdbUUID = new OvsdbUUID(id.toString)

    /** Convert from String to Ovsdb UUIDs. */
    def toOvsdb(id: String): OvsdbUUID = new OvsdbUUID(id)

    @inline
    implicit def asRichUUID(id: UUID): RichUUID = new RichUUID(id)

    @inline
    implicit def asRichOvsdbUUID(id: OvsdbUUID): RichOvsdbUUID =
        new RichOvsdbUUID(id)

    /** Convert a set of OvsdbUUIDs to a set of Java UUIDs
      * The input set can be a null value, resulting in an empty output set */
    def fromOvsdb(set: JSet[OvsdbUUID]): JSet[UUID] = {
        if (set eq null) EmptyJavaUuidSet
        else set.asScala.map(_.asJava).asJava
    }

    /** Convert a set of Java UUIDs to a set of ovsdb UUIDs
      * The input set can be a null value, resulting in an empty output set */
    def toOvsdb(set: Set[UUID]): JSet[OvsdbUUID] = {
        if (set eq null) EmptyOvsdbUuidSet
        else set.map(_.asOvsdb).asJava
    }

    /** Convert from (Long -> OvsdbUUID) to a map of (Long -> java UUID).
      * The input map can be a null value, resulting in an empty output map. */
    def fromOvsdb(map: JMap[JLong, OvsdbUUID]): JMap[JLong, UUID] = {
        if (map eq null) EmptyJavaUuidMap
        else map.asScala.map(e => (e._1, e._2.asJava)).asJava
    }

    /** Convert from (Integer -> java UUID) to a map of (Long -> OvsdbUUID)
      * The input map can be a null value, resulting in an empty output map */
    def toOvsdb(map: Map[JLong, UUID]): JMap[JLong, OvsdbUUID] = {
        if (map eq null) EmptyOvsdbUuidMap
        else map.map(e => (e._1, e._2.asOvsdb)).asJava
    }

    /** Convert from a set of strings to a set of the ip addresses */
    def fromOvsdbIpSet(set: JSet[String]): JSet[IPv4Addr] = {
        if (set eq null) EmptyIPv4AddrSet
        else set.asScala.map(IPv4Addr(_)).asJava
    }

    /** Convert from a set of IP addresses to a set of OVSDB strings. */
    def toOvsdbIpSet(set: Set[IPv4Addr]): JSet[String] = {
        if (set eq null) EmptyStringSet
        else set.map(_.toString).asJava
    }

    /** Convert from a set of strings to a set of OVSDB UUIDs. */
    def toOvsdbUuid(set: Set[String]): JSet[OvsdbUUID] = {
        if (set eq null) EmptyOvsdbUuidSet
        else set.map(new OvsdbUUID(_)).asJava
    }

    def panicAlert(log: Logger) = makeAction0 {
        log.error("OVSDB client buffer overflow. The VxGW service will NOT " +
                  "function correctly, please restart the Cluster server.")
    }

}
