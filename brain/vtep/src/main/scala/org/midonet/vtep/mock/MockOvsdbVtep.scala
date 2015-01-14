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

package org.midonet.vtep.mock

import org.opendaylight.ovsdb.lib.notation.{UUID => OvsdbUUID}
import org.opendaylight.ovsdb.lib.schema.{GenericTableSchema, ColumnSchema}

import org.midonet.vtep.mock.MockOvsdbColumn.{mkColumnSchema, mkSetColumnSchema}

/**
 * An In-memory mocked vtep for use in tests
 */
class MockOvsdbVtep {

    private val lsSchema =
        Map[String, ColumnSchema[GenericTableSchema, _]](
            ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
            ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
            ("name", mkColumnSchema("name", classOf[String])),
            ("description", mkColumnSchema("description", classOf[String])),
            ("tunnel_key", mkSetColumnSchema("description", classOf[Long]))
        )
    private val uLocalSchema =
        Map[String, ColumnSchema[GenericTableSchema, _]](
            ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
            ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
            ("MAC", mkColumnSchema("MAC", classOf[String])),
            ("logical_switch", mkColumnSchema("logical_switch", classOf[OvsdbUUID])),
            ("locator", mkColumnSchema("locator", classOf[OvsdbUUID])),
            ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
            )
    private val uRemoteSchema =
        Map[String, ColumnSchema[GenericTableSchema, _]](
            ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
            ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
            ("MAC", mkColumnSchema("MAC", classOf[String])),
            ("logical_switch", mkColumnSchema("logical_switch", classOf[OvsdbUUID])),
            ("locator", mkColumnSchema("locator", classOf[OvsdbUUID])),
            ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
            )
    private val mLocalSchema =
        Map[String, ColumnSchema[GenericTableSchema, _]](
            ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
            ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
            ("MAC", mkColumnSchema("MAC", classOf[String])),
            ("logical_switch", mkColumnSchema("logical_switch", classOf[OvsdbUUID])),
            ("locator_set", mkColumnSchema("locator_set", classOf[OvsdbUUID])),
            ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
        )
    private val mRemoteSchema =
        Map[String, ColumnSchema[GenericTableSchema, _]](
            ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
            ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
            ("MAC", mkColumnSchema("MAC", classOf[String])),
            ("logical_switch", mkColumnSchema("logical_switch", classOf[OvsdbUUID])),
            ("locator_set", mkColumnSchema("locator_set", classOf[OvsdbUUID])),
            ("ipaddr", mkColumnSchema("ipaddr", classOf[String]))
            )
    private val locSetSchema =
        Map[String, ColumnSchema[GenericTableSchema, _]](
            ("_uuid", mkColumnSchema("_uuid", classOf[OvsdbUUID])),
            ("_version", mkColumnSchema("_version", classOf[OvsdbUUID])),
            ("locators", mkSetColumnSchema("locators", classOf[OvsdbUUID]))
        )


}

