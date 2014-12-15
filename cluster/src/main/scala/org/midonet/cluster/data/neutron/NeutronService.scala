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

import java.sql.{Connection, ResultSet}
import java.util.UUID

import javax.sql.DataSource

import scala.collection.mutable.ListBuffer

import com.google.protobuf.Message

import org.slf4j.LoggerFactory

import org.midonet.cluster.data.neutron.TaskType.TaskType
import org.midonet.cluster.models.Neutron
import org.midonet.cluster.models.Neutron._

/**
 * Neutron task type. The value is the ID used to represent the task type
 * in Neutron's task table. Create, Delete, and Update are self-explanatory,
 * while Flush is a command to delete the Cluster's topology data and rebuild
 * from Neutron.
 */
protected[cluster] object TaskType extends Enumeration {
    type TaskType = Value
    val Create = Value(1)
    val Delete = Value(2)
    val Update = Value(3)
    val Flush = Value(4)

    private val vals = Array(Create, Delete, Update, Flush)
    def valueOf(i: Int) = vals(i - 1)
}

/**
 * Neutron resource type The value is the ID used to represent the resource
 * type in Neutron's task table.
 */
case class NeutronResourceType[M <: Message](id: Int, clazz: Class[M])

protected[cluster] object NeutronResourceType extends Enumeration {
    val Network = NeutronResourceType(1, classOf[NeutronNetwork])
    val Subnet = NeutronResourceType(2, classOf[NeutronSubnet])
    val Router = NeutronResourceType(3, classOf[NeutronRouter])
    val Port = NeutronResourceType(4, classOf[NeutronPort])
    val FloatingIp = NeutronResourceType(5, classOf[Neutron.FloatingIp])
    val SecurityGroup = NeutronResourceType(6, classOf[Neutron.SecurityGroup])
    val SecurityGroupRule =
        NeutronResourceType(7, classOf[Neutron.SecurityGroupRule])
    val RouterInterface =
        NeutronResourceType(8, classOf[NeutronRouterInterface])

    private val vals = Array(Network, Subnet, Router, Port, FloatingIp,
                             SecurityGroup, SecurityGroupRule, RouterInterface)
    def valueOf(i: Int) = vals(i - 1)
}

/**
 * Case classes representing Neutron tasks.
 */
sealed trait Task {
    val taskId: Int
}
case class Create(taskId: Int,
                  rsrcType: NeutronResourceType[_ <: Message],
                  json: String) extends Task
case class Delete(taskId: Int,
                  rsrcType: NeutronResourceType[_ <: Message],
                  objId: UUID) extends Task
case class Update(taskId: Int,
                  rsrcType: NeutronResourceType[_ <: Message],
                  json: String) extends Task
case class Flush(taskId: Int) extends Task

class Transaction(val id: String, val tasks: List[Task]) {
    val lastTaskId = tasks.last.taskId
    val isFlushTxn = tasks.size == 1 && tasks(0).isInstanceOf[Flush]
}

/**
 * Interface for access to Neutron database.
 */
trait NeutronService {
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
class RemoteNeutronService(dataSrc: DataSource) extends NeutronService {
    private val log = LoggerFactory.getLogger(classOf[RemoteNeutronService])

    private val idCol = 1
    private val typeIdCol = 2
    private val dataTypeIdCol = 3
    private val resourceIdCol = 4
    private val txnIdCol = 5
    private val dataCol = 6

    override def getTasksSince(lastTaskId: Int): List[Transaction] = {
        val con = dataSrc.getConnection
        try getTasksSince(lastTaskId, con) finally con.close()
    }

    private def getTasksSince(lastTaskId: Int,
                              con: Connection): List[Transaction] = {
        log.debug("Querying Neutron DB for tasks with ID > {}", lastTaskId)
        val rslt = queryTasksSince(lastTaskId, con)
        val txns = ListBuffer[Transaction]()
        var lastTxnId: String = null
        val txnTasks = ListBuffer[Task]()

        def buildTxn(): Transaction = {
            val tasks = txnTasks.toList
            txnTasks.clear()
            log.debug("Finished receiving transaction {}, containing tasks {}.",
                      lastTxnId, tasks.map(_.taskId).asInstanceOf[Any])
            new Transaction(lastTxnId, tasks)
        }

        while (rslt.next()) {
            val row = parseTaskRow(rslt)
            log.debug("Received task from Neutron DB: {}", row)

            // Rows should be grouped by transaction, so if this row's txnId
            // is different from the last, we can close off the last transaction
            // and start a new one.
            if (lastTxnId != row.txnId) {
                if (lastTxnId != null)
                    txns += buildTxn()
                log.debug("Began receiving transaction {}", row.txnId)
                lastTxnId = row.txnId
            }

            txnTasks += row.toTask
        }

        // Close off the last transaction.
        if (lastTxnId != null)
            txns += buildTxn()

        log.debug("Received {} transactions from Neutron DB.", txns.size)
        txns.toList
    }

    private def queryTasksSince(lastTaskId: Int, con: Connection): ResultSet = {
        val stmt = con.prepareStatement(
            "select id, type_id, data_type_id," +
            "resource_id, transaction_id, data " +
            "from midonet_tasks where id > ? or " +
            s"(id = 1 and type_id = ${TaskType.Flush.id}) " +
            "order by id")
        stmt.setInt(1, lastTaskId)
        val rslt = stmt.executeQuery()
        rslt
    }

    override def deleteTask(taskId: Int): Unit = {
        val con = dataSrc.getConnection
        try {
            val stmt =
                con.prepareStatement("delete from midonet_tasks where id = ?")
            stmt.setInt(1, taskId)
            stmt.executeUpdate()
        } finally con.close()
    }

    private case class TaskRow(id: Int, taskType: TaskType,
                               rsrcType: NeutronResourceType[_ <: Message],
                               rsrcId: UUID, txnId: String, json: String) {
        def toTask = taskType match {
            case TaskType.Create => Create(id, rsrcType, json)
            case TaskType.Delete => Delete(id, rsrcType, rsrcId)
            case TaskType.Update => Update(id, rsrcType, json)
            case TaskType.Flush => Flush(id)
        }
    }

    /**
     * Creates a TaskRow from the ResultSet's current row. Does not advance
     * or otherwise modify the ResultSet.
     */
    private def parseTaskRow(rslt: ResultSet): TaskRow = {
        val id = rslt.getInt(idCol)
        val taskType = TaskType.valueOf(rslt.getInt(typeIdCol))
        val rsrcType = NeutronResourceType.valueOf(rslt.getInt(dataTypeIdCol))
        val rsrcIdStr = rslt.getString(resourceIdCol)
        val rsrcId = if (rsrcIdStr == null) null else UUID.fromString(rsrcIdStr)
        val txnId = rslt.getString(txnIdCol)
        val json = rslt.getString(dataCol)

        // Task ID for flush should always be 1.
        assert(taskType != TaskType.Flush || id == 1)

        TaskRow(id, taskType, rsrcType, rsrcId, txnId, json)
    }

}