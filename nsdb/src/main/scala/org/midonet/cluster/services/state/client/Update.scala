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

package org.midonet.cluster.services.state.client

import scala.collection.JavaConversions._
import scala.collection.Map

import org.midonet.cluster.rpc.State

/**
  * The representation of an Update message from the State Proxy protocol
  */
abstract sealed class Update(val entries: Update.EntryMap)

object Update {
    sealed trait KeyValue
    case class Data32(value: Int) extends KeyValue
    case class Data64(value: Long) extends KeyValue
    case class DataVariable(value: Array[Byte]) extends KeyValue

    type EntryMap = Map[KeyValue,KeyValue]

    case class BeginSnapshot(override val entries: EntryMap) extends Update(entries)
    case class Snapshot(override val entries: EntryMap) extends Update(entries)
    case class EndSnapshot(override val entries: EntryMap) extends Update(entries)
    case class Relative(override val entries: EntryMap) extends Update(entries)

    def apply(msg: State.ProxyResponse.Notify.Update): Update = {

        @throws[MatchError]
        def convert(m: State.KeyValue): KeyValue = {
            // silence exhaustiveness warning (will throw MatchError)
            (m.getDataCase: @unchecked) match {
                case State.KeyValue.DataCase.DATA_32 => Data32(m.getData32)
                case State.KeyValue.DataCase.DATA_64 => Data64(m.getData64)
                case State.KeyValue.DataCase.DATA_VARIABLE =>
                    DataVariable(m.getDataVariable.toByteArray)
            }
        }

        var entries = Map.empty[KeyValue,KeyValue]
        msg.getEntriesList.foreach( e => entries += (convert(e.getKey) -> convert(e.getValue)) )

        msg.getType match {
            case State.ProxyResponse.Notify.Update.Type.BEGIN_SNAPSHOT =>
                BeginSnapshot(entries)
            case State.ProxyResponse.Notify.Update.Type.SNAPSHOT =>
                Snapshot(entries)
            case State.ProxyResponse.Notify.Update.Type.END_SNAPSHOT =>
                EndSnapshot(entries)
            case State.ProxyResponse.Notify.Update.Type.RELATIVE =>
                Relative(entries)
        }
    }
}