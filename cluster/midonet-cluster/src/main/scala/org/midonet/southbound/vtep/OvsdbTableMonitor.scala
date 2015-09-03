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

import java.util.{Map => JMap}
import java.util.concurrent.Executor

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.message.TableUpdate
import org.opendaylight.ovsdb.lib.schema.{ColumnSchema, GenericTableSchema}

import rx.Observable.OnSubscribe
import rx.{Observable, Subscriber}

import org.midonet.cluster.data.vtep.model.VtepEntry
import org.midonet.southbound.vtep.OvsdbOperations._
import org.midonet.southbound.vtep.VtepEntryUpdate.addition
import org.midonet.southbound.vtep.schema.Table
import org.midonet.util.functors.makeFunc1

/**
 * A class implementing the VTEP table monitor for an OVSDB-based VTEP.
 */
class OvsdbTableMonitor[T <: Table, Entry <: VtepEntry](client: OvsdbClient,
                                                        table: T,
                                                        clazz: Class[Entry],
                                                        executor: Executor)
    extends VtepTableMonitor[Entry] {

    private val executionContext = ExecutionContext.fromExecutor(executor)

    private val onUpdate = makeFunc1[TableUpdate[GenericTableSchema],
                                     Observable[VtepTableUpdate[Entry]]](u => {
        val changes = u.getRows.keySet().asScala.map { rowId =>
            val oldRow = u.getOld(rowId)
            val newRow = u.getNew(rowId)
            // For a modify update (when both rows are present), the old row
            // contains only the modified columns (see RFC 7047). Therefore,
            // merge the unmodified columns from the new row to parse to entry.
            if ((oldRow ne null) && (newRow ne null)) {
                val columnSchemas: JMap[String, ColumnSchema[GenericTableSchema, _]] =
                    newRow.getTableSchema.getColumnSchemas
                          .asInstanceOf[JMap[String, ColumnSchema[GenericTableSchema, _]]]
                for ((name, schema) <- columnSchemas.asScala
                     if (oldRow.getColumn(schema) eq null) &&
                        (newRow.getColumn(schema) ne null)) {
                    oldRow.addColumn(name, newRow.getColumn(schema))
                }
            }

            VtepEntryUpdate[Entry](table.parseEntry(oldRow, clazz),
                                   table.parseEntry(newRow, clazz))
        }
        Observable.from(changes.asJava)
    })

    private val onSubscribe = new OnSubscribe[VtepTableUpdate[Entry]] {
        override def call(s: Subscriber[_ >: VtepTableUpdate[Entry]]) = {
            tableEntries(client, table.getDbSchema, table.getSchema,
                         table.getColumnSchemas.asScala, null,
                         executor).onComplete {
                case Failure(exc) => s.onError(exc)
                case Success(rows) =>
                    // TODO: Improve this to catch any updates that may be
                    // TODO: emitted between fetching the table entries and
                    // TODO: subscribing to the table updates.
                    var initialUpdates: Seq[VtepTableUpdate[Entry]] =
                        for (row <- rows)
                            yield addition(table.parseEntry(row, clazz))
                    initialUpdates = initialUpdates :+ VtepTableReady[Entry]()
                    tableUpdates(client, table.getDbSchema, table.getSchema,
                                 table.getColumnSchemas.asScala)
                        .concatMap(onUpdate)
                        .startWith(initialUpdates.asJava)
                        .subscribe(s)
            }(executionContext)
        }
    }

    override def observable: Observable[VtepTableUpdate[Entry]] =
        Observable.create(onSubscribe)

}
