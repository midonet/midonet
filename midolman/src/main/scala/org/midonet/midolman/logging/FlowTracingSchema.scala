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
import com.datastax.driver.core.PreparedStatement
import com.datastax.driver.core.BoundStatement

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
        WHERE traceRequestId = ? AND flowTraceId < maxTimeuuid(?) limit ?;"""
    val getFlowTraceCQL = s"""
        SELECT traceRequestId, flowTraceId, ethSrc, ethDst, etherType,
               networkSrc, networkDst, networkProto, srcPort, dstPort
        FROM ${FLOWS_TABLE} WHERE traceRequestId = ? AND flowTraceId = ?"""
    val getFlowTracesCQL = s"""
        SELECT traceRequestId, flowTraceId, ethSrc, ethDst, etherType,
               networkSrc, networkDst, networkProto, srcPort, dstPort
        FROM ${FLOWS_TABLE} WHERE traceRequestId = ?
                                  AND flowTraceId < maxTimeuuid(?) limit ?"""
    val getTraceDataCQL = s"""
        SELECT time, host, data FROM ${FLOW_EVENTS_TABLE}
        WHERE traceRequestId = ? AND flowTraceId = ?
              AND time < maxTimeuuid(?) limit ?"""

    def bindFlowInsertStatement(insertStatement: PreparedStatement,
                                traceRequestId: UUID, flowTraceId: UUID,
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

    def bindDataInsertStatement(insertStatement: PreparedStatement,
                                traceRequestId: UUID, flowTraceId: UUID,
                                host: UUID, data: String): BoundStatement = {
        new BoundStatement(insertStatement).bind(traceRequestId, flowTraceId,
                                                 host, data)
    }

    def bindFlowCountStatement(flowCountStatement: PreparedStatement,
                               traceRequestId: UUID, maxTime: Date,
                               limit: Integer): BoundStatement = {
        new BoundStatement(flowCountStatement).bind(traceRequestId,
                                                    maxTime, limit)
    }

    def bindGetFlowStatement(getFlowStatement: PreparedStatement,
                             traceRequestId: UUID, flowTraceId: UUID):
            BoundStatement = {
        new BoundStatement(getFlowStatement).bind(traceRequestId,
                                                   flowTraceId)
    }

    def bindGetFlowsStatement(getFlowsStatement: PreparedStatement,
                              traceRequestId: UUID, maxTime: Date,
                              limit: Integer): BoundStatement = {
        new BoundStatement(getFlowsStatement).bind(traceRequestId,
                                                   maxTime, limit)
    }

    def bindGetDataStatement(getDataStatement: PreparedStatement,
                             traceRequestId: UUID, flowTraceId: UUID,
                             maxTime: Date, limit: Integer):
            BoundStatement = {
        new BoundStatement(getDataStatement).bind(traceRequestId, flowTraceId,
                                                  maxTime, limit)
    }

}
