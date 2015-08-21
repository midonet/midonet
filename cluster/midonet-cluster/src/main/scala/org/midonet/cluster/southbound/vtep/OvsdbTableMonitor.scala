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

package org.midonet.cluster.southbound.vtep

import java.util.concurrent.Executor

import scala.collection.JavaConversions._
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.message.TableUpdate
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema
import rx.Observable.OnSubscribe
import rx.{Observable, Subscriber}

import org.midonet.cluster.data.vtep.model.VtepEntry
import org.midonet.cluster.southbound.vtep.OvsdbTools.{tableEntries, tableUpdates}
import org.midonet.cluster.southbound.vtep.VtepEntryUpdate.addition
import org.midonet.cluster.southbound.vtep.schema.Table
import org.midonet.util.functors.makeFunc1

/**
 * A class implementing the vtep table monitor for ovsdb-based vteps
 */
class OvsdbTableMonitor[T <: Table, Entry <: VtepEntry](client: OvsdbClient,
                                                        table: T,
                                                        clazz: Class[Entry],
                                                        executor: Executor)
    extends VtepTableMonitor[Entry] {

    private val executionContext = ExecutionContext.fromExecutor(executor)

    private val onUpdate = makeFunc1[TableUpdate[GenericTableSchema],
                                     Observable[VtepTableUpdate[Entry]]](u => {
        val changes = u.getRows.keySet().map { rowId =>
            VtepEntryUpdate[Entry](table.parseEntry(u.getOld(rowId), clazz),
                                   table.parseEntry(u.getNew(rowId), clazz))
        }
        Observable.from(changes)
    })

    private val onSubscribe = new OnSubscribe[VtepTableUpdate[Entry]] {
        override def call(s: Subscriber[_ >: VtepTableUpdate[Entry]]) = {

            val entries = tableEntries(client, table.getDbSchema,
                                       table.getSchema, table.getColumnSchemas,
                                       null, executor)
            entries.future.onComplete {
                case Failure(exc) => s.onError(exc)
                case Success(rows) =>
                    rows.toIterable.foreach(r => s.onNext (
                        addition(table.parseEntry(r, clazz)))
                    )
                    tableUpdates(client, table.getDbSchema, table.getSchema,
                                 table.getColumnSchemas)
                        .concatMap(onUpdate)
                        .startWith(VtepTableReady[Entry]())
                        .subscribe(s)
            }(executionContext)
        }
    }

    override def observable: Observable[VtepTableUpdate[Entry]] =
        Observable.create(onSubscribe)
}
