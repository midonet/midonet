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

import scala.collection.JavaConversions.{asJavaIterable, iterableAsScalaIterable}
import scala.util.{Success, Failure}

import org.opendaylight.ovsdb.lib.OvsdbClient
import org.opendaylight.ovsdb.lib.message.TableUpdate
import org.opendaylight.ovsdb.lib.schema.GenericTableSchema
import rx.{Subscriber, Observable}
import rx.Observable.OnSubscribe

import org.midonet.cluster.data.vtep.model.VtepEntry
import org.midonet.util.concurrent.CallingThreadExecutionContext
import org.midonet.util.functors.makeFunc1
import org.midonet.vtep.schema.Table

/**
 * A class implementing the vtep table monitor for ovsdb-based vteps
 */
class OvsdbTableMonitor[T <: Table, Entry <: VtepEntry](client: OvsdbClient,
                                                        table: T)
    extends VtepTableMonitor[Entry] {

    private val onUpdate =
        makeFunc1[TableUpdate[GenericTableSchema],
                  Observable[VtepTableUpdate[Entry]]](u => {
            val changes = u.getRows.keySet().map(
                rowId => VtepTableUpdate[Entry](
                    table.parseEntry(u.getOld(rowId), classOf[Entry]),
                    table.parseEntry(u.getNew(rowId), classOf[Entry]))
            )
            Observable.from(changes)
        })

    private val onSubscribe = new OnSubscribe[VtepTableUpdate[Entry]] {
        override def call(subscriber: Subscriber[_ >: VtepTableUpdate[Entry]]) = {
            OvsdbUtil.tableEntries(client, table.getDbSchema, table.getSchema,
                                   table.getColumnSchemas, null)
                .future.onComplete({
                case Failure(exc) => subscriber.onError(exc)
                case Success(rows) =>
                    rows.toIterable.foreach(r => VtepTableUpdate.addition(
                        table.parseEntry(r, classOf[Entry])))
                    OvsdbUtil.tableUpdates(client, table.getDbSchema, table.getSchema,
                                           table.getColumnSchemas)
                        .concatMap(onUpdate)
                        .subscribe(subscriber)
            })(CallingThreadExecutionContext)
        }
    }

    override def observable: Observable[VtepTableUpdate[Entry]] =
        Observable.create(onSubscribe)
}
