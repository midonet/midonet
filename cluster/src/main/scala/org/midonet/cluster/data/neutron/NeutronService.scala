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

import org.midonet.cluster.data.neutron.ResourceType.ResourceType
import org.midonet.cluster.data.neutron.TaskType.TaskType
import org.midonet.cluster.data.{ObjId, Obj}

import scala.collection.mutable.ListBuffer

/**
 * Neutron task type. The value is the ID used to represent the task type
 * in Neutron's task table. Create, Delete, and Update are self-explanatory,
 * while Flush is a command to delete the Cluster's topology data and rebuild
 * from Neutron.
 */
protected[data] object TaskType extends Enumeration {
    type TaskType = Value
    val Create = Value(1)
    val Delete = Value(2)
    val Update = Value(3)
    val Flush = Value(4)

    private val values = Array(Create, Delete, Update, Flush)
    def valueOf(i: Int) = values(i - 1)
}

/**
 * Neutron resource type The value is the ID used to represent the resource
 * type in Neutron's task table.
 */
protected[data] object ResourceType extends Enumeration {
    type ResourceType = Value
    val Network = Value(1)
    val Subnet = Value(2)
    val Router = Value(3)
    val Port = Value(4)
    val FloatingIp = Value(5)
    val SecurityGroup = Value(6)
    val SecurityGroupRule = Value(7)
    val RouterInterface = Value(8)

    private val values = Array(Network, Subnet, Router, Port, FloatingIp,
                               SecurityGroup, SecurityGroupRule,
                               RouterInterface)
    def valueOf(i: Int) = values(i - 1)
}

/**
 * Case classes representing Neutron tasks.
 */
protected[data] sealed trait Task {
    val taskId: Int
}
protected[data] case class Create(taskId: Int, rsrcType: ResourceType,
                                  json: String) extends Task
protected[data] case class Delete(taskId: Int, rsrcType: ResourceType,
                                  objId: UUID) extends Task
protected[data] case class Update(taskId: Int, rsrcType: ResourceType,
                                  json: String) extends Task
protected[data] case class Flush(taskId: Int) extends Task

protected[data] class Transaction(id: String, tasks: List[Task]) {
    val lastTaskId = tasks.last.taskId
}

/**
 * Interface for access to Neutron database.
 */
protected[data] trait NeutronService {
    /**
     * Gets all tasks with task ID greater than lastTaskId, ordered by task ID
     * and grouped into Transactions according to transaction ID.
     */
    def getTasksSince(lastTaskId: Int): List[Transaction]

    /**
     * Deletes the specified task.
     */
    def deleteTask(taskId: Int)
}

/**
 * Implementation of NeutronService that obtains data from a remote
 * SQL database using the provided JDBC connection.
 *
 * TODO: Use something smarter than a plain JDBC connection that will retry
 * and attempt to reconnect if the connection fails.
 */
protected[data] class RemoteNeutronService(conn: Connection)
    extends NeutronService{
    private val getTasksStmt = conn.prepareStatement(
        "select id, type_id, data_type_id, resource_id, transaction_id, data " +
        "from task where id > ? " +
        s"or (id = 1 and type_id = ${TaskType.Flush.id} " +
        "order by id")

    private val deleteTaskStmt =
        conn.prepareStatement("delete from task where id = ?")

    private val idCol = 1
    private val typeIdCol = 2
    private val dataTypeIdCol = 3
    private val resourceIdCol = 4
    private val txnIdCol = 5
    private val dataCol = 6

    override def getTasksSince(lastTaskId: Int): List[Transaction] = {
        getTasksStmt.setInt(1, lastTaskId)
        val rslt = getTasksStmt.executeQuery()
        val txns = ListBuffer[Transaction]()
        var lastTxnId: String = null
        val txnTasks = ListBuffer[Task]()
        while (rslt.next()) {
            val row = parseTaskRow(rslt)
            if (row.taskType == TaskType.Flush) {
                assert(row.id == 1)
                return List(new Transaction(row.txnId,
                                            List(new Flush(row.id))))
            }

            // Rows should be grouped by transaction, so if this row's txnId
            // is different from the last, we can close off the last transaction
            // and start a new one.
            if (lastTxnId != row.txnId) {
                if (lastTxnId != null) {
                    txns += new Transaction(lastTxnId, txnTasks.toList)
                    txnTasks.clear()
                }
                lastTxnId = row.txnId
            }

            txnTasks += row.toTask
        }

        // Close off the last transaction.
        if (lastTxnId != null) {
            txns += new Transaction(lastTxnId, txnTasks.toList)
        }

        txns.toList
    }

    override def deleteTask(taskId: Int): Unit = {
        deleteTaskStmt.setInt(1, taskId)
        deleteTaskStmt.executeUpdate()
    }

    private case class TaskRow(id: Int, taskType: TaskType,
                               rsrcType: ResourceType, rsrcId: UUID,
                               txnId: String, json: String) {
        def toTask = taskType match {
            case TaskType.Create => Create(id, rsrcType, json)
            case TaskType.Delete => Delete(id, rsrcType, rsrcId)
            case TaskType.Update => Update(id, rsrcType, json)
        }
    }

    /**
     * Creates a TaskRow from the ResultSet's current row. Does not advance
     * or otherwise modify the ResultSet.
     */
    private def parseTaskRow(rslt: ResultSet): TaskRow = {
        val id = rslt.getInt(idCol)
        val taskType = TaskType.valueOf(rslt.getInt(typeIdCol))
        val rsrcType = ResourceType.valueOf(rslt.getInt(resourceIdCol))
        val rsrcIdStr = rslt.getString(resourceIdCol)
        val rsrcId = if (rsrcIdStr == null) null else UUID.fromString(rsrcIdStr)
        val txnId = rslt.getString(txnIdCol)
        val json = rslt.getString(dataCol)
        TaskRow(id, taskType, rsrcType, rsrcId, txnId, json)
    }

}