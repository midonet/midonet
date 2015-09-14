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
package org.midonet.midolman.logging

import java.util.{Date, UUID}
import com.datastax.driver.core.{BoundStatement, PreparedStatement, Session}

object FlowTracingSchema {
    val KEYSPACE_NAME = "MidonetFlowTracing"
    val FLOW_EVENTS_TABLE = "flow_events"
    val FLOWS_TABLE = "flows"

    object Schema {
        def FLOW_EVENTS(name: String) = s"""
            CREATE TABLE IF NOT EXISTS $name (
                traceRequestId uuid, flowTraceId uuid, time timeuuid, host uuid,
                data varchar, PRIMARY KEY((traceRequestId,flowTraceId), time))
                WITH CLUSTERING ORDER BY (time DESC);"""
        def FLOWS(name: String) = s"""
            CREATE TABLE IF NOT EXISTS $name (
                traceRequestId uuid, flowTraceId timeuuid,
                ethSrc varchar, ethDst varchar,  etherType int,
                networkSrc varchar, networkDst varchar,
                networkProto int, srcPort int, dstPort int,
                PRIMARY KEY(traceRequestId,flowTraceId))
                WITH CLUSTERING ORDER BY (flowTraceId DESC);"""
    }

    val SCHEMA = Array[String](Schema.FLOW_EVENTS(FLOW_EVENTS_TABLE),
                               Schema.FLOWS(FLOWS_TABLE))
    val SCHEMA_TABLE_NAMES = Array[String](FLOW_EVENTS_TABLE, FLOWS_TABLE)

    val dataInsertCQL = s"""
        INSERT INTO ${FLOW_EVENTS_TABLE}
        (traceRequestId, flowTraceId, time, host, data)
        VALUES(?,?, now(), ?, ?)"""
    val flowInsertCQL = s"""
        INSERT INTO ${FLOWS_TABLE}
        (traceRequestId, flowTraceId, ethSrc, ethDst, etherType,
         networkSrc, networkDst, networkProto, srcPort, dstPort)
        VALUES(?, ?, ?, ?, ?, ?, ?, ?, ?, ?)"""
    val countFlowTracesCQL = s"""
        SELECT COUNT(*) AS count FROM ${FLOWS_TABLE}
        WHERE traceRequestId = ?
        AND flowTraceId >= minTimeuuid(?)
        AND flowTraceId < maxTimeuuid(?)"""

    val getFlowTraceCQL = s"""
        SELECT traceRequestId, flowTraceId, ethSrc, ethDst, etherType,
               networkSrc, networkDst, networkProto, srcPort, dstPort
        FROM ${FLOWS_TABLE} WHERE traceRequestId = ? AND flowTraceId = ?"""
    private val getFlowTracesCQLBase = s"""
        SELECT traceRequestId, flowTraceId, ethSrc, ethDst, etherType,
               networkSrc, networkDst, networkProto, srcPort, dstPort
        FROM ${FLOWS_TABLE} WHERE traceRequestId = ?
        AND flowTraceId >= minTimeuuid(?)
        AND flowTraceId < maxTimeuuid(?)"""
    val getFlowTracesCQLAsc = getFlowTracesCQLBase + " ORDER BY flowTraceId ASC LIMIT ?"
    val getFlowTracesCQL = getFlowTracesCQLBase + " LIMIT ?"

    private val getTraceDataCQLBase = s"""
        SELECT time, host, data FROM ${FLOW_EVENTS_TABLE}
        WHERE traceRequestId = ? AND flowTraceId = ?
        AND time >= minTimeuuid(?)
        AND time < maxTimeuuid(?)"""
    val getTraceDataCQLAsc = getTraceDataCQLBase + " ORDER BY time ASC LIMIT ?"
    val getTraceDataCQL = getTraceDataCQLBase + " LIMIT ?"
}


class FlowTracingSchema(session: Session) {
    val insertStatement: PreparedStatement =
        session.prepare(FlowTracingSchema.flowInsertCQL)
    val dataInsertStatement: PreparedStatement =
        session.prepare(FlowTracingSchema.dataInsertCQL)
    val getCountStatement: PreparedStatement =
        session.prepare(FlowTracingSchema.countFlowTracesCQL)
    val getFlowTraceStatement: PreparedStatement =
        session.prepare(FlowTracingSchema.getFlowTraceCQL)
    val getFlowTracesStatement: PreparedStatement =
        session.prepare(FlowTracingSchema.getFlowTracesCQL)
    val getFlowTracesStatementAsc: PreparedStatement =
        session.prepare(FlowTracingSchema.getFlowTracesCQLAsc)
    val getTraceDataStatement: PreparedStatement =
        session.prepare(FlowTracingSchema.getTraceDataCQL)
    val getTraceDataStatementAsc: PreparedStatement =
        session.prepare(FlowTracingSchema.getTraceDataCQLAsc)

    def bindFlowInsertStatement(traceRequestId: UUID, flowTraceId: UUID,
                                ethSrc: String, ethDst: String,
                                etherType: Integer,
                                networkSrc: String, networkDst: String,
                                networkProto: Integer,
                                srcPort: Integer, dstPort: Integer):
            BoundStatement = {
        new BoundStatement(insertStatement).bind(traceRequestId, flowTraceId,
                                                 ethSrc, ethDst, etherType,
                                                 networkSrc, networkDst,
                                                 networkProto,
                                                 srcPort, dstPort)
    }

    def bindDataInsertStatement(traceRequestId: UUID, flowTraceId: UUID,
                                host: UUID, data: String): BoundStatement = {
        new BoundStatement(dataInsertStatement).bind(traceRequestId, flowTraceId,
                                                     host, data)
    }

    def bindFlowCountStatement(traceRequestId: UUID,
                               minTime: Option[Date] = None,
                               maxTime: Option[Date] = None): BoundStatement = {
        new BoundStatement(getCountStatement).bind(traceRequestId,
                                                   minTime.getOrElse(new Date(0)),
                                                   maxTime.getOrElse(new Date()))
    }

    def bindGetFlowStatement(traceRequestId: UUID, flowTraceId: UUID):
            BoundStatement = {
        new BoundStatement(getFlowTraceStatement).bind(traceRequestId,
                                                       flowTraceId)
    }

    def bindGetFlowsStatement(traceRequestId: UUID,
                              minTime: Option[Date] = None,
                              maxTime: Option[Date] = None,
                              ascending: Boolean = false,
                              limit: Option[Int] = None): BoundStatement = {
        val statement = if (ascending) {
            getFlowTracesStatementAsc
        } else {
            getFlowTracesStatement
        }
        new BoundStatement(statement).bind(traceRequestId,
                                           minTime.getOrElse(new Date(0)),
                                           maxTime.getOrElse(new Date()),
                                           Int.box(limit.getOrElse(Int.MaxValue)))
    }

    def bindGetDataStatement(traceRequestId: UUID, flowTraceId: UUID,
                             minTime: Option[Date] = None,
                             maxTime: Option[Date] = None,
                             ascending: Boolean = false,
                             limit: Option[Int] = None):
            BoundStatement = {
        val statement = if (ascending) {
            getTraceDataStatementAsc
        } else {
            getTraceDataStatement
        }
        new BoundStatement(statement).bind(traceRequestId, flowTraceId,
                                           minTime.getOrElse(new Date(0)),
                                           maxTime.getOrElse(new Date()),
                                           Int.box(limit.getOrElse(Int.MaxValue)))
    }

}
