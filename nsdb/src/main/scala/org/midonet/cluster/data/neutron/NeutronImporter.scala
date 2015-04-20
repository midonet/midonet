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

import org.midonet.cluster.data.neutron.importer.Transaction
import org.midonet.cluster.models.Neutron
import org.midonet.cluster.models.Neutron._

/**
 * Neutron task type. The value is the ID used to represent the task type
 * in Neutron's task table.
 */
case class TaskType(id: String)

/**
 * Declares supported Neutron Task types. Create, Delete, and Update are
 * self-explanatory, while Flush is a command to delete the Cluster's topology
 * data and rebuild from Neutron.  The names are in upper case to match how
 * they are stored in Neutron.
 */
object TaskType extends Enumeration {
    val Create = TaskType("CREATE")
    val Delete = TaskType("DELETE")
    val Update = TaskType("UPDATE")
    val Flush = TaskType("FLUSH")

    private val vals = Map(Create.id -> Create, Delete.id -> Delete,
                           Update.id -> Update, Flush.id -> Flush)
    def valueOf(i: String) = vals(i)
}

/**
 * Neutron resource type The value is the ID used to represent the resource
 * type in Neutron's task table.
 */
case class NeutronResourceType[M <: Message](id: String, clazz: Class[M])

/** Declares the different types of supported Neutron types. */
object NeutronResourceType extends Enumeration {
    val NoData = NeutronResourceType("NULL", classOf[Null])
    val Config = NeutronResourceType("CONFIG", classOf[NeutronConfig])
    val Network = NeutronResourceType("NETWORK", classOf[NeutronNetwork])
    val Subnet = NeutronResourceType("SUBNET", classOf[NeutronSubnet])
    val Router = NeutronResourceType("ROUTER", classOf[NeutronRouter])
    val Port = NeutronResourceType("PORT", classOf[NeutronPort])
    val FloatingIp = NeutronResourceType("FLOATINGIP",
                                         classOf[Neutron.FloatingIp])
    val SecurityGroup = NeutronResourceType("SECURITYGROUP",
                                            classOf[Neutron.SecurityGroup])
    val SecurityGroupRule = NeutronResourceType(
        "SECURITYGROUPRULE", classOf[Neutron.SecurityGroupRule])
    val PortBinding = NeutronResourceType("PORTBINDING", classOf[PortBinding])
    val AgentMembership = NeutronResourceType(
        "AGENTMEMBERSHIP", classOf[AgentMembership])

    private val vals = Map[String, NeutronResourceType[_ <: Message]](
        NoData.id -> NoData,
        AgentMembership.id -> AgentMembership,
        Config.id -> Config,
        Network.id -> Network, Subnet.id -> Subnet,
        Router.id -> Router, Port.id -> Port,
        FloatingIp.id -> FloatingIp,
        SecurityGroup.id -> SecurityGroup,
        SecurityGroupRule.id -> SecurityGroupRule,
        PortBinding.id -> PortBinding)

    def valueOf(i: String): NeutronResourceType[_ <: Message] = vals(i)
}

/** Interface for access to Neutron database. */
trait NeutronImporter {
    /** Gets all tasks with task ID greater than taskId, ordered by task ID and
      * grouped into Transactions according to transaction ID. */
    def getTasksSince(taskId: Int): List[importer.Transaction]

    /** Deletes the specified task. */
    def deleteTask(taskId: Int)
}

/** Implementation of NeutronService that obtains data from a remote
  * SQL database using the provided JDBC connection. */
class SqlNeutronImporter(dataSrc: DataSource) extends NeutronImporter {

    private val log = LoggerFactory.getLogger(classOf[SqlNeutronImporter])

    private val NEW_TASKS_QUERY = "select id, type, data_type," +
                                   "resource_id, transaction_id, data " +
                                   "from midonet_tasks where id > ? or " +
                                   s"(id = 1 and type = '${TaskType.Flush.id}') " +
                                   "order by id"

    private val idCol = 1
    private val typeCol = 2
    private val dataTypeCol = 3
    private val resourceIdCol = 4
    private val txnIdCol = 5
    private val dataCol = 6

    override def getTasksSince(taskId: Int): List[Transaction] = {
        val con = dataSrc.getConnection
        try getTasksSince(taskId, con) finally con.close()
    }

    private def getTasksSince(taskId: Int,
                              con: Connection): List[Transaction] = {
        log.debug("Querying Neutron DB for tasks with ID > {}", taskId)
        val rslt = queryTasksSince(taskId, con)
        val txns = ListBuffer[Transaction]()
        var lastTxnId: String = null
        val txnTasks = ListBuffer[importer.Task]()

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
        val stmt = con.prepareStatement(NEW_TASKS_QUERY)
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
            case TaskType.Create => importer.Create(id, rsrcType, json)
            case TaskType.Delete => importer.Delete(id, rsrcType, rsrcId)
            case TaskType.Update => importer.Update(id, rsrcType, json)
            case TaskType.Flush  => importer.Flush(id)
        }
    }

    /**
     * Creates a TaskRow from the ResultSet's current row. Does not advance
     * or otherwise modify the ResultSet.
     */
    private def parseTaskRow(rslt: ResultSet): TaskRow = {
        val id = rslt.getInt(idCol)
        val taskType = TaskType.valueOf(rslt.getString(typeCol))
        val rsrcType =
            NeutronResourceType.valueOf(rslt.getString(dataTypeCol))
        val rsrcIdStr = rslt.getString(resourceIdCol)
        val rsrcId = if (rsrcIdStr == null) null else UUID.fromString(rsrcIdStr)
        val txnId = rslt.getString(txnIdCol)
        val json = rslt.getString(dataCol)

        // Task ID for flush should always be 1.
        assert(taskType != TaskType.Flush || id == 1)

        TaskRow(id, taskType, rsrcType, rsrcId, txnId, json)
    }

}