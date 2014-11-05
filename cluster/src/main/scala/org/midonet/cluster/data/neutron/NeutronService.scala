/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.cluster.data.neutron

import java.sql.{ResultSet, Connection}
import java.util.UUID

import org.midonet.cluster.data.neutron.NeutronResourceType.NeutronResourceType
import org.midonet.cluster.data.neutron.TaskType.TaskType

import scala.collection.mutable.ListBuffer

protected[data] object TaskType extends Enumeration {
    type TaskType = Value
    val Create = Value(1)
    val Delete = Value(2)
    val Update = Value(3)
    val Flush = Value(4)

    private val vals = Array(Create, Delete, Update, Flush)
    def valueOf(i: Int) = vals(i - 1)
}

protected[data] object NeutronResourceType extends Enumeration {
    type NeutronResourceType = Value
    val Network = Value(1)
    val Subnet = Value(2)
    val Router = Value(3)
    val Port = Value(4)
    val FloatingIp = Value(5)
    val SecurityGroup = Value(6)
    val SecurityGroupRule = Value(7)
    val RouterInterface = Value(8)

    private val vals = Array(Network, Subnet, Router, Port, FloatingIp,
                             SecurityGroup, SecurityGroupRule,
                             RouterInterface)
    def valueOf(i: Int) = vals(i - 1)
}

protected[data] sealed trait Operation {
    val opId: Int
}
protected[data] case class Create(opId: Int, rsrcType: NeutronResourceType,
                                  json: String) extends Operation
protected[data] case class Delete(opId: Int, rsrcType: NeutronResourceType,
                                  objId: UUID) extends Operation
protected[data] case class Update(opId: Int, rsrcType: NeutronResourceType,
                                  json: String) extends Operation
protected[data] case class Flush(opId: Int) extends Operation

protected[data] class Transaction(id: String, ops: List[Operation]) {
    val lastOpId = ops.last.opId
}

protected[data] trait NeutronService {
    def getTxnsSince(lastOpId: Int): List[Transaction]
    def deleteOp(id: UUID)
}

protected[data] class RemoteNeutronService(conn: Connection) {
    private val getTxnStmt = conn.prepareStatement(
        "select id, type_id, data_type_id, resource_id, transaction_id, data " +
        "from task where transaction_id > ? " +
        s"or (transaction_id = 1 and type_id = ${TaskType.Flush.id} " +
        "order by transaction_id")

    private val deleteOpStmt =
        conn.prepareStatement("delete from task where id = ?")

    val idCol = 1
    val typeIdCol = 2
    val dataTypeIdCol = 3
    val resourceIdCol = 4
    val txnIdCol = 5
    val dataCol = 6

    def getTxnsSince(lastOpId: Int): List[Transaction] = {
        getTxnStmt.setInt(1, lastOpId)
        val rslt = getTxnStmt.executeQuery()
        val txns = ListBuffer[Transaction]()
        var lastTxnId: String = null
        val txnOps = ListBuffer[Operation]()
        while (rslt.next()) {
            val row = parseRow(rslt)
            if (row.taskType == TaskType.Flush) {
                assert(row.opId == 1)
                return List(new Transaction(row.txnId,
                                            List(new Flush(row.opId))))
            }

            // Rows should be grouped by transaction, so if this row's txnId
            // is different from the last, we can close off the last transaction
            // and start a new one.
            if (lastTxnId != row.txnId) {
                if (lastTxnId != null) {
                    txns += new Transaction(lastTxnId, txnOps.toList)
                    txnOps.clear()
                }
                lastTxnId = row.txnId
            }

            txnOps += row.toOp
        }

        // Close off the last transaction.
        if (lastTxnId != null) {
            txns += new Transaction(lastTxnId, txnOps.toList)
        }

        txns.toList
    }

    def deleteOp(id: Int): Unit = {
        deleteOpStmt.setInt(1, id)
        deleteOpStmt.executeUpdate()
    }

    protected case class Row(opId: Int, taskType: TaskType,
                             rsrcType: NeutronResourceType, rsrcId: UUID,
                             txnId: String, json: String) {
        def toOp = taskType match {
            case TaskType.Create => Create(opId, rsrcType, json)
            case TaskType.Delete => Delete(opId, rsrcType, rsrcId)
            case TaskType.Update => Update(opId, rsrcType, json)
        }
    }

    protected def parseRow(rslt: ResultSet): Row = {
        val opId = rslt.getInt(idCol)
        val taskType = TaskType.valueOf(rslt.getInt(typeIdCol))
        val rsrcType = NeutronResourceType.valueOf(rslt.getInt(resourceIdCol))
        val rsrcIdStr = rslt.getString(resourceIdCol)
        val rsrcId = if (rsrcIdStr == null) null else UUID.fromString(rsrcIdStr)
        val txnId = rslt.getString(txnIdCol)
        val json = rslt.getString(dataCol)
        Row(opId, taskType, rsrcType, rsrcId, txnId, json)
    }

}