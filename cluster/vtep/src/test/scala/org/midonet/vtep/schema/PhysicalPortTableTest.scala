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

package org.midonet.vtep.schema

import java.util.{Random, UUID}
import java.util.concurrent.TimeUnit

import scala.collection.JavaConversions._
import scala.concurrent.duration.Duration

import org.junit.runner.RunWith
import org.opendaylight.ovsdb.lib.operations.{Update, Insert}
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.data.vtep.model.PhysicalPort
import org.midonet.vtep.mock.{MockOvsdbVtep, InMemoryOvsdbVtep}

@RunWith(classOf[JUnitRunner])
class PhysicalPortTableTest extends FeatureSpec with Matchers {

    val timeout = Duration(5000, TimeUnit.MILLISECONDS)
    val random = new Random()
    val vtep = new InMemoryOvsdbVtep()
    val db = vtep.getDbSchema(MockOvsdbVtep.DB_HARDWARE_VTEP)
    val table = new PhysicalPortTable(db)

    feature("Physical port table") {
        scenario("name") {
            table.getName shouldBe PhysicalPortTable.TB_NAME
        }
        scenario("columns") {
            val columns = table.getColumnSchemas
            columns.exists(_.getName == "_uuid") shouldBe true
            columns.length > 1 shouldBe true
        }
    }
    feature("Physical port entries") {
        scenario("basic row") {
            val entry = PhysicalPort(UUID.randomUUID(), "p1", "p1-desc")
            val row = table.generateRow(entry)
            val parsed = table.parseEntry(row, classOf[PhysicalPort])
            parsed shouldBe entry
            row.getColumns.size() shouldBe table.getColumnSchemas.size()
            row.getTableSchema shouldBe table.getSchema
        }
        scenario("bindings") {
            val base = PhysicalPort(UUID.randomUUID(), "p1", "p1-desc")
            val entry = base.newBinding(random.nextInt(4096), UUID.randomUUID())
            base shouldNot be (entry)
            val row = table.generateRow(entry)
            val parsed = table.parseEntry(row, classOf[PhysicalPort])
            parsed shouldBe entry
            row.getColumns.size() shouldBe table.getColumnSchemas.size()
            row.getTableSchema shouldBe table.getSchema
        }
    }
    feature("Physical port rows") {
        scenario("insertion") {
            val port = PhysicalPort(UUID.randomUUID(), "p1", "p1-desc")
            val op = table.insert(port).op.asInstanceOf[Insert[_]]
            op.getTable shouldBe table.getName
            op.getTableSchema shouldBe table.getSchema

            val base = table.generateRow(port)
            val row = table.generateRow(mapAsScalaMap(op.getRow).toMap)
            row shouldBe base

            val ebase = table.parseEntry(base, classOf[PhysicalPort])
            val erow = table.parseEntry(row, classOf[PhysicalPort])
            erow shouldBe ebase
        }
        scenario("update") {
            val lsId = UUID.randomUUID()
            val vni = random.nextInt(4096)

            val port = PhysicalPort(UUID.randomUUID(), "p1", "p1-desc")
            val row = table.generateRow(port)

            val updated = port.newBinding(vni, lsId)
            val urow = table.updateBindings(updated).op.asInstanceOf[Update[_]]

            val result = table.updateRow(row, urow.getRow)
            val entry = table.parseEntry(result, classOf[PhysicalPort])
            entry shouldBe updated
        }
    }
}
