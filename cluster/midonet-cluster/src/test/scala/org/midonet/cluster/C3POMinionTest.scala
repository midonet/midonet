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

package org.midonet.cluster

import java.io.PrintWriter
import java.sql.{Connection, DriverManager}
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.sql.DataSource

import scala.collection.JavaConverters._
import scala.concurrent.ExecutionContext.Implicits.global
import scala.util.{Random, Try}

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.typesafe.config.ConfigFactory
import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.services.c3po.{C3POState, C3POMinion}
import org.midonet.cluster.data.neutron.NeutronResourceType.{AgentMembership => AgentMembershipType, Config => ConfigType, Network => NetworkType, NoData, Port => PortType, Router => RouterType, SecurityGroup => SecurityGroupType, Subnet => SubnetType}
import org.midonet.cluster.data.neutron.TaskType._
import org.midonet.cluster.data.neutron.{NeutronResourceType, TaskType}
import org.midonet.cluster.data.storage.ObjectReferencedException
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons.{EtherType, Protocol, RuleDirection}
import org.midonet.cluster.models.Neutron.NeutronConfig.TunnelProtocol
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron.SecurityGroup
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.MidonetBackendService
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.conf.MidoTestConfigurator
import org.midonet.packets.{IPSubnet, IPv4Subnet, UDP}
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.toFutureOps

/** Tests the service that syncs the Neutron DB into Midonet's backend. */
class C3POMinionTestBase extends FlatSpec with BeforeAndAfter
                                          with BeforeAndAfterAll
                                          with Matchers
                                          with MidonetEventually {

    protected val log = LoggerFactory.getLogger(this.getClass)

    // Data sources
    private val DB_CONNECT_STR =
        "jdbc:sqlite:file:taskdb?mode=memory&cache=shared"

    private val DROP_TASK_TABLE = "DROP TABLE IF EXISTS midonet_tasks"
    private val EMPTY_TASK_TABLE = "DELETE FROM midonet_tasks"
    private val CREATE_TASK_TABLE =
        "CREATE TABLE midonet_tasks (" +
        "    id int(11) NOT NULL," +
        "    type varchar(36) NOT NULL," +
        "    data_type varchar(36) DEFAULT NULL," +
        "    data longtext," +
        "    resource_id varchar(36) DEFAULT NULL," +
        "    transaction_id varchar(40) NOT NULL," +
        "    created_at datetime NOT NULL," +
        "    PRIMARY KEY (id)" +
        ")"

    private val DROP_STATE_TABLE = "DROP TABLE IF EXISTS midonet_data_state"
    private val EMPTY_STATE_TABLE = "DELETE FROM midonet_data_state"
    private val CREATE_STATE_TABLE =
        "CREATE TABLE midonet_data_state (" +
        "    id int(11) NOT NULL," +
        "    last_processed_task_id int(11) DEFAULT NULL," +
        "    updated_at datetime NOT NULL," +
        "    PRIMARY KEY (id)" +
        ")"
    private val INIT_STATE_ROW =
        "INSERT INTO midonet_data_state values(1, NULL, datetime('now'))"
    private val LAST_PROCESSED_ID =
        "SELECT last_processed_task_id FROM midonet_data_state WHERE id = 1"
    private val LAST_PROCESSED_ID_COL = 1

    val C3PO_CFG_OBJECT = ConfigFactory.parseString(
        s"""
          |cluster.neutron_importer.period : 100ms
          |cluster.neutron_importer.delay : 0
          |cluster.neutron_importer.enabled : true
          |cluster.neutron_importer.with : ${classOf[C3POMinion].getName}
          |cluster.neutron_importer.threads : 1
          |cluster.neutron_importer.connection_string : "$DB_CONNECT_STR"
          |cluster.neutron_importer.user : ""
          |cluster.neutron_importer.password : ""
          |zookeeper.root_key : "/test"
          |zookeeper.use_new_stack : true
        """.stripMargin)

    private val clusterCfg = new ClusterConfig(C3PO_CFG_OBJECT)

    protected val nodeFactory = new JsonNodeFactory(true)

    // Adapt the DriverManager interface to DataSource interface.
    // SQLite doesn't seem to provide JDBC 2.0 API.
    private val dataSrc = new DataSource() {
        override def getConnection() =
            DriverManager.getConnection(DB_CONNECT_STR)

        override def getConnection(username: String, password: String) = null

        override def getLoginTimeout = -1

        override def getLogWriter = null

        override def setLoginTimeout(seconds: Int) {}

        override def setLogWriter(out: PrintWriter) {}

        override def getParentLogger = null

        override def isWrapperFor(clazz: Class[_]) = false

        override def unwrap[T](x: Class[T]): T = null.asInstanceOf[T]
    }

    // We need to keep one connection open to maintain the shared in-memory DB
    // during the test.
    private val dummyConnection = dataSrc.getConnection()

    private val zk: TestingServer = new TestingServer()
    private val ZK_HOST = s"127.0.0.1:${zk.getPort}"

    // ---------------------
    // DATA FIXTURES
    // ---------------------

    protected def executeSqlStmts(sqls: String*) {
        var c: Connection = null
        try {
            c = eventually(dataSrc.getConnection())
            val stmt = c.createStatement()
            sqls.foreach(sql => eventually(stmt.executeUpdate(sql)))
            stmt.close()
        } finally {
            if (c != null) c.close()
        }
    }

    protected def getLastProcessedIdFromTable: Option[Int] = {
        var c: Connection = null
        var lastProcessed: Option[Int] = None
        try {
            c = eventually(dataSrc.getConnection())
            val stmt = c.createStatement()
            val result = stmt.executeQuery(LAST_PROCESSED_ID)

            if (result.next())
                lastProcessed = Some(result.getInt(LAST_PROCESSED_ID_COL))
            stmt.close()
        } finally {
            if (c != null) c.close()
        }
        lastProcessed
    }


    private def createTaskTable() = {
        // Just in case an old DB file / table exits.
        executeSqlStmts(DROP_TASK_TABLE)
        executeSqlStmts(CREATE_TASK_TABLE)
        log.info("Created the midonet_tasks table.")
    }

    private def createStateTable() = {
        executeSqlStmts(DROP_STATE_TABLE)
        executeSqlStmts(CREATE_STATE_TABLE)
        executeSqlStmts(INIT_STATE_ROW)
        log.info("Created and initialized the midonet_data_state table.")
    }

    protected def emptyTaskAndStateTablesAndSendFlushTask() = {
        executeSqlStmts(EMPTY_TASK_TABLE)
        executeSqlStmts(EMPTY_STATE_TABLE)
        executeSqlStmts(INIT_STATE_ROW)
        log.info("Emptied the task/state tables.")

        // A flush task must have an id of 1 by spec.
        executeSqlStmts(insertTaskSql(id = 1, Flush, NoData, json = "",
                                      null, "flush_txn"))
        log.info("Inserted a flush task.")

        // Wait for flush to finish before starting test
        eventually {
            val state = storage.get(classOf[C3POState], C3POState.ID).await()
            state.lastProcessedTaskId shouldBe C3POState.NO_TASKS_PROCESSED
        }
    }

    protected def insertTaskSql(id: Int, taskType: TaskType,
                                dataType: NeutronResourceType[_],
                                json: String, resourceId: UUID,
                                txnId: String): String = {
        val taskTypeStr = if (taskType != null) s"'${taskType.id}'"
        else "NULL"
        val dataTypeStr = if (dataType != null) s"'${dataType.id}'"
        else "NULL"
        val rsrcIdStr = if (resourceId != null) s"'$resourceId'"
        else "NULL"

        "INSERT INTO midonet_tasks values(" +
        s"$id, $taskTypeStr, $dataTypeStr, '$json', $rsrcIdStr, '$txnId', " +
        "datetime('now'))"
    }

    private var curator: CuratorFramework = _
    protected var backend: MidonetBackendService = _
    private var c3po: C3POMinion = _

    // ---------------------
    // TEST SETUP
    // ---------------------

    override protected def beforeAll() {
        try {
            printf("Entered BeforeAll()")
            val retryPolicy = new ExponentialBackoffRetry(1000, 10)
            curator = CuratorFrameworkFactory.newClient(ZK_HOST, retryPolicy)
            // Initialize tasks and state tables.
            createTaskTable()
            createStateTable()

            zk.start()

            val cfg = new MidonetBackendConfig(MidoTestConfigurator.forClusters(C3PO_CFG_OBJECT))

            backend = new MidonetBackendService(cfg, curator)
            backend.startAsync().awaitRunning()
            curator.blockUntilConnected()

            val nodeCtx = new Context(UUID.randomUUID())
            c3po = new C3POMinion(nodeCtx, clusterCfg, dataSrc, backend, curator)
            c3po.startAsync()
            c3po.awaitRunning(2, TimeUnit.SECONDS)
        } catch {
            case e: Throwable =>
                log.error("Failing setting up environment", e)
                cleanup()
        }
    }

    protected def storage = backend.store

    before {
        // Empties the task table and flush the topology before each test run.
        // NOTE: Closing the ZK Testing Server doesn't work because the Curator
        // client loses the connection. If we convert the test so that it runs
        // C3PO alone, we can instead just call C3PO.flushTopology() on C3PO.
        // Calling Storage.flush() doesn't do the job as it doesn't do necessary
        // initialization.
        emptyTaskAndStateTablesAndSendFlushTask()
    }

    after {
        // curator.delete().deletingChildrenIfNeeded().forPath("/")
    }

    override protected def afterAll() {
        cleanup()
    }

    private def cleanup(): Unit = {
        Try(backend.stopAsync().awaitTerminated())
                               .getOrElse(log.error("Failed stopping backend"))
        Try(c3po.stopAsync().awaitTerminated())
                            .getOrElse(log.error("Failed stopping C3PO"))
        Try(curator.close()).getOrElse(log.error("Failed stopping curator"))
        Try(zk.stop()).getOrElse(log.error("Failed stopping zk"))
        Try(if (dummyConnection != null) dummyConnection.close())
        .getOrElse(log.error("Failed stopping the keep alive DB cnxn"))
    }

    case class IPAlloc(ipAddress: String, subnetId: String)

    protected def portJson(name: String, id: UUID,
                           networkId: UUID,
                           adminStateUp: Boolean = true,
                           mac_address: String = null,
                           fixedIps: List[IPAlloc] = null,
                           deviceId: UUID = null,
                           deviceOwner: DeviceOwner = null,
                           tenantId: String = null,
                           securityGroups: List[UUID] = null): JsonNode = {
        val p = nodeFactory.objectNode
        p.put("name", name)
        p.put("id", id.toString)
        p.put("network_id", networkId.toString)
        p.put("admin_state_up", adminStateUp)
        p.put("mac_address", mac_address)
        if (fixedIps != null) {
            val fi = p.putArray("fixed_ips")
            for (fixedIp <- fixedIps) {
                val ip = nodeFactory.objectNode
                ip.put("ip_address", fixedIp.ipAddress)
                ip.put("subnet_id", fixedIp.subnetId)
                fi.add(ip)
            }
        }
        if (deviceId != null) p.put("device_id", deviceId.toString)
        if (deviceOwner != null) p.put("device_owner", deviceOwner.toString)
        if (tenantId != null) p.put("tenant_id", tenantId)
        if (securityGroups != null) {
            val sgList = p.putArray("security_groups")
            securityGroups.foreach(sgid => sgList.add(sgid.toString))
        }
        p
    }

    protected def sgJson(name: String, id: UUID,
                         desc: String = null,
                         tenantId: String = null,
                         rules: List[JsonNode]): JsonNode = {
        val sg = nodeFactory.objectNode
        sg.put("name", name)
        sg.put("id", id.toString)
        if (desc != null) sg.put("description", desc)
        if (tenantId != null) sg.put("tenant_id", tenantId)
        if (rules != null)
            sg.putArray("security_group_rules").addAll(rules.asJava)
        sg
    }

    protected def ruleJson(id: UUID, sgId: UUID,
                           direction: RuleDirection = RuleDirection.INGRESS,
                           etherType: EtherType = EtherType.IPV4,
                           protocol: Protocol = Protocol.TCP,
                           portRange: Range = null,
                           remoteSgId: UUID = null,
                           remoteIpPrefix: IPSubnet[_] = null): JsonNode = {
        val r = nodeFactory.objectNode
        r.put("id", id.toString)
        r.put("security_group_id", sgId.toString)
        r.put("direction", direction.toString)
        if (etherType != null)
            r.put("ethertype", etherType.toString)
        if (protocol != null)
            r.put("protocol", protocol.toString)
        if (portRange != null) {
            r.put("port_range_min", portRange.start)
            r.put("port_range_max", portRange.end)
        }
        if (remoteSgId != null)
            r.put("remote_group_id", remoteSgId.toString)
        if (remoteIpPrefix != null)
            r.put("remote_ip_prefix", remoteIpPrefix.toString)
        r
    }

    protected def routerJson(name: String, id: UUID,
                             adminStateUp: Boolean = true,
                             status: String = null,
                             tenantId: String = null,
                             gwPortId: UUID = null,
                             enableSnat: Boolean = false,
                             extGwNetworkId: UUID = null): JsonNode = {
        val r = nodeFactory.objectNode
        r.put("name", name)
        r.put("id", id.toString)
        r.put("admin_state_up", adminStateUp)
        if (status != null) r.put("status", status)
        if (tenantId != null) r.put("tenant_id", tenantId)
        if (gwPortId != null) r.put("gw_port_id", gwPortId.toString)
        if (enableSnat || extGwNetworkId != null) {
            val egi = nodeFactory.objectNode
            if (extGwNetworkId != null)
                egi.put("network_id", extGwNetworkId.toString)
            egi.put("enable_snat", enableSnat)
            r.put("extenal_gateway_info", egi)
        }
        r
    }

    protected def networkJson(id: UUID, tenantId: String, name: String = null,
                              shared: Boolean = false,
                              adminStateUp: Boolean = true,
                              external: Boolean = false): JsonNode = {
        val n = nodeFactory.objectNode
        n.put("id", id.toString)
        n.put("tenant_id", tenantId)
        if (name != null) n.put("name", name)
        n.put("admin_state_up", adminStateUp)
        n.put("external", external)
        n
    }

    protected def configJson(id: UUID,
                             tunnelProtocol: TunnelProtocol): JsonNode = {
        val c = nodeFactory.objectNode
        c.put("id", id.toString)
        c.put("tunnel_protocol", tunnelProtocol.toString)
        c
    }

    protected def agentMembershipJson(id: UUID, ipAddress: String): JsonNode = {
        val c = nodeFactory.objectNode
        c.put("id", id.toString)
        c.put("ip_address", ipAddress)
        c
    }

    protected case class HostRoute(destination: String, nextHop: String)

    protected def subnetJson(id: UUID, networkId: UUID, tenantId: String,
                           name: String = null, cidr: String = null,
                           ipVersion: Int = 4, gatewayIp: String = null,
                           enableDhcp: Boolean = true,
                           dnsNameservers: List[String] = null,
                           hostRoutes: List[HostRoute] = null): JsonNode = {
        val s = nodeFactory.objectNode
        s.put("id", id.toString)
        s.put("network_id", networkId.toString)
        s.put("tenant_id", tenantId)
        if (name != null) s.put("name", name)
        if (cidr != null) s.put("cidr", cidr)
        s.put("ip_version", ipVersion)
        if (gatewayIp != null) s.put("gateway_ip", gatewayIp)
        s.put("enable_dhcp", enableDhcp)
        if (dnsNameservers != null) {
            val nameServers = s.putArray("dns_nameservers")
            for (nameServer <- dnsNameservers) {
                nameServers.add(nameServer)
            }
        }
        if (hostRoutes != null) {
            val routes = s.putArray("host_routes")
            for (route <- hostRoutes) {
                val r = nodeFactory.objectNode
                r.put("destination", route.destination)
                r.put("nexthop", route.nextHop)
                routes.add(r)
            }
        }

        s
    }

    protected case class ChainPair(inChain: Chain, outChain: Chain)
    protected def getChains(inChainId: Commons.UUID,
                            outChainId: Commons.UUID): ChainPair = {
        val fs = storage.getAll(classOf[Chain], List(inChainId, outChainId))
        val chains = fs.map(_.await())
        ChainPair(chains(0), chains(1))
    }

    protected def getChains(ipg: IpAddrGroup): ChainPair =
        getChains(ipg.getInboundChainId, ipg.getOutboundChainId)
}

@RunWith(classOf[JUnitRunner])
class C3POMinionTest extends C3POMinionTestBase {

    "C3PO" should "poll DB and update ZK via C3POStorageMgr" in {
        val network1Uuid = UUID.randomUUID()

        // Initially the Storage is empty.
        storage.exists(classOf[Network], network1Uuid).await() shouldBe false

        // Create a private Network
        val network1Name = "private-network"
        val network1Json = networkJson(network1Uuid, "tenant1", network1Name)
        executeSqlStmts(insertTaskSql(2, Create, NetworkType,
                                      network1Json.toString,
                                      network1Uuid, "tx1"))
        val network1 = eventually(
            storage.get(classOf[Network], network1Uuid).await())

        network1.getId shouldBe toProto(network1Uuid)
        network1.getName shouldBe network1Name
        network1.getAdminStateUp shouldBe true
        eventually(getLastProcessedIdFromTable shouldBe Some(2))

        // Creates Network 2 and updates Network 1
        val network2Uuid = UUID.randomUUID()
        val network2Name = "corporate-network"
        val network2Json = networkJson(network2Uuid, "tenant1", network2Name)

        // Create a public Network
        val network1Name2 = "public-network"
        val network1Json2 = networkJson(network1Uuid, "tenant1", network1Name2,
                                       external = true, adminStateUp = false)

        executeSqlStmts(
                insertTaskSql(id = 3, Create, NetworkType,
                              network2Json.toString, network2Uuid, "tx2"),
                insertTaskSql(id = 4, Update, NetworkType,
                              network1Json2.toString, network1Uuid, "tx2"))

        val network2 = eventually(
            storage.get(classOf[Network], network2Uuid).await())
        network2.getId shouldBe toProto(network2Uuid)
        network2.getName shouldBe "corporate-network"

        eventually {
            val network1a = storage.get(classOf[Network], network1Uuid).await()
            network1a.getId shouldBe toProto(network1Uuid)
            network1a.getName shouldBe "public-network"
            network1a.getAdminStateUp shouldBe false
            getLastProcessedIdFromTable shouldBe Some(4)
        }

        // Deletes Network 1
        executeSqlStmts(insertTaskSql(
                id = 5, Delete, NetworkType, json = "", network1Uuid, "tx3"))
        eventually {
            storage.exists(classOf[Network], network1Uuid).await() shouldBe false
            getLastProcessedIdFromTable shouldBe Some(5)
        }

        // Empties the Task table and flushes the Topology.
        emptyTaskAndStateTablesAndSendFlushTask()

        eventually {
            storage.exists(classOf[Network], network2Uuid).await() shouldBe false
        }

        // Can create Network 1 & 2 again.
        executeSqlStmts(
                insertTaskSql(id = 2, Create, NetworkType,
                              network1Json.toString, network1Uuid, "tx4"),
                insertTaskSql(id = 3, Create, NetworkType,
                              network2Json.toString, network2Uuid,  "tx4"))

        eventually {
            // Both networks should exist.
            for (id <- List(network1Uuid, network2Uuid);
                 nwExists <- storage.exists(classOf[Network], id)) {
                nwExists shouldBe true
            }
            getLastProcessedIdFromTable shouldBe Some(3)
        }
    }

    it should "execute VIF port CRUD tasks" in {
        // Creates Network 1.
        val network1Uuid = UUID.randomUUID()
        val network1Json = networkJson(network1Uuid, "tenant1", "private-net")
        executeSqlStmts(insertTaskSql(
                id = 2, Create, NetworkType, network1Json.toString,
                network1Uuid, "tx1"))

        val vifPortUuid = UUID.randomUUID()
        val vifPortId = toProto(vifPortUuid)
        eventually {
            storage.exists(classOf[Port], vifPortId).await() shouldBe false
        }

        // Creates a VIF port.
        val vifPortJson = portJson(name = "port1", id = vifPortUuid,
                                   networkId = network1Uuid).toString
        executeSqlStmts(insertTaskSql(
                id = 3, Create, PortType, vifPortJson, vifPortUuid, "tx2"))

        val vifPort = eventually(storage.get(classOf[Port], vifPortId).await())
        vifPort.getId should be (vifPortId)
        vifPort.getNetworkId should be (toProto(network1Uuid))
        vifPort.getAdminStateUp shouldBe true

        val network1 = storage.get(classOf[Network], network1Uuid).await()
        network1.getPortIdsList should contain (vifPortId)

        // Update the port admin status. Through the Neutron API, you cannot
        // change the Network the port is attached to.
        val vifPortUpdate = portJson(name = "port1", id = vifPortUuid,
                                     networkId = network1Uuid,
                                     adminStateUp = false      // Down now.
                                     ).toString
        executeSqlStmts(insertTaskSql(
                id = 4, Update, PortType, vifPortUpdate, vifPortUuid, "tx3"))

        eventually {
            val updatedVifPort = storage.get(classOf[Port], vifPortId).await()
            updatedVifPort.getAdminStateUp shouldBe false
        }

        // Deleting a network while ports are attached should throw exception.
        intercept[ObjectReferencedException] {
            storage.delete(classOf[Network], network1Uuid)
        }

        // Delete the VIF port.
        executeSqlStmts(insertTaskSql(
                id = 5, Delete, PortType, json = null, vifPortUuid, "tx4"))

        eventually {
            storage.exists(classOf[Port], vifPortId).await() shouldBe false
        }
        // Back reference was cleared.
        val finalNw1 = storage.get(classOf[Network], network1Uuid).await()
        finalNw1.getPortIdsList should not contain vifPortId
        // You can delete the Network1 now.
        storage.delete(classOf[Network], network1Uuid)
    }

    it should "handle security group CRUD" in {
        val sg1Id = UUID.randomUUID()
        val rule1Id = UUID.randomUUID()
        val rule1Json = ruleJson(
            rule1Id, sg1Id, portRange = 15000 to 15500,
            remoteIpPrefix = new IPv4Subnet("10.0.0.1", 24))

        val sg2Id = UUID.randomUUID()
        val rule2Id = UUID.randomUUID()
        val rule2Json = ruleJson(rule2Id, sg1Id, etherType = EtherType.IPV6,
                                 remoteSgId = sg2Id)

        val sg1Json = sgJson(name = "sg1", id = sg1Id,
                             desc = "Security group", tenantId = "tenant",
                             rules = List(rule1Json, rule2Json))
        val sg2Json = sgJson(name ="sg2", id = sg2Id,
                             tenantId = "tenant", rules = List())
        executeSqlStmts(insertTaskSql(2, Create, SecurityGroupType,
                                      sg1Json.toString, sg1Id, "tx1"),
                        insertTaskSql(3, Create, SecurityGroupType,
                                      sg2Json.toString, sg2Id, "tx1"))

        val ipg1 = eventually(storage.get(classOf[IpAddrGroup], sg1Id).await())
        val ChainPair(inChain1, outChain1) = getChains(ipg1)

        inChain1.getRuleIdsCount should be(0)
        outChain1.getRuleIdsCount should be(2)
        val outChain1Rules = storage.getAll(classOf[Rule],
                                            outChain1.getRuleIdsList.asScala)
                                    .map(_.await())
        outChain1Rules(0).getId should be(toProto(rule1Id))
        outChain1Rules(0).getTpDst.getStart should be(15000)
        outChain1Rules(0).getTpDst.getEnd should be(15500)
        outChain1Rules(0).getNwSrcIp.getAddress should be("10.0.0.1")
        outChain1Rules(0).getNwSrcIp.getPrefixLength should be(24)

        outChain1Rules(1).getId should be(toProto(rule2Id))
        outChain1Rules(1).getDlType should be(EtherType.IPV6_VALUE)
        outChain1Rules(1).getIpAddrGroupIdSrc should be(toProto(sg2Id))

        val rule1aJson = ruleJson(rule1Id, sg1Id,
                                  direction = RuleDirection.EGRESS,
                                  protocol = Protocol.UDP)
        val rule3Id = UUID.randomUUID()
        val rule3Json = ruleJson(rule3Id, sg1Id)
        val sg1aJson = sgJson(name = "sg1-updated", id = sg1Id,
                              desc = "Security group", tenantId = "tenant",
                              rules = List(rule1aJson, rule3Json))
        executeSqlStmts(insertTaskSql(4, Update, SecurityGroupType,
                                      sg1aJson.toString,
                                      sg1Id, "tx2"),
                        insertTaskSql(5, Delete, SecurityGroupType,
                                      null, sg2Id, "tx2"))
        eventually {
            val ipg1a = storage.get(classOf[IpAddrGroup], sg1Id).await()
            val ChainPair(inChain1a, outChain1a) = getChains(ipg1a)

            inChain1a.getId should be(inChain1.getId)
            inChain1a.getRuleIdsCount should be(1)
            inChain1a.getRuleIds(0) should be(toProto(rule1Id))

            outChain1a.getId should be(outChain1.getId)
            outChain1a.getRuleIdsCount should be(1)
            outChain1a.getRuleIds(0) should be(toProto(rule3Id))
        }

        val ipg1aRules = storage.getAll(classOf[Rule],
                                        List(rule1Id, rule3Id)).map(_.await())

        val inChain1aRule1 = ipg1aRules(0)
        inChain1aRule1.getId should be(toProto(rule1Id))
        inChain1aRule1.getNwProto should be(UDP.PROTOCOL_NUMBER)

        val outChain1aRule1 = ipg1aRules(1)
        outChain1aRule1.getId should be(toProto(rule3Id))

        executeSqlStmts(insertTaskSql(6, Delete, SecurityGroupType,
                                      null, sg1Id, "tx3"))

        eventually {
            val delFutures = List(
                storage.getAll(classOf[SecurityGroup]),
                storage.getAll(classOf[IpAddrGroup]),
                storage.getAll(classOf[Chain]),
                storage.getAll(classOf[Rule]))
            val delResults = delFutures.map(_.await())
            delResults.foreach(r => r should be(empty))
        }
    }

    it should "handle Subnet CRUD" in {
        val nId = UUID.randomUUID()
        val nJson = networkJson(nId, "net tenant")

        // Create a subnet
        val sId = UUID.randomUUID()
        val cidr = IPv4Subnet.fromCidr("10.0.0.0/24")
        val gatewayIp = "10.0.0.1"
        val nameServers = List("8.8.8.8")
        val hrDest = "10.0.0.0/24"
        val hrNexthop = "10.0.0.27"
        val hostRoutes = List(HostRoute(hrDest, hrNexthop))
        val sJson = subnetJson(sId, nId, "net tenant", name = "test sub",
                               cidr = cidr.toString, gatewayIp = gatewayIp,
                               dnsNameservers = nameServers,
                               hostRoutes = hostRoutes)
        executeSqlStmts(insertTaskSql(2, Create, NetworkType,
                                      nJson.toString, nId, "tx1"),
                        insertTaskSql(3, Create, SubnetType,
                                      sJson.toString, sId, "tx2"))

        // Verify the created subnet
        val dhcp = eventually(storage.get(classOf[Dhcp], sId).await())
        dhcp should not be null
        dhcp.getDefaultGateway.getAddress should be(gatewayIp)
        dhcp.getEnabled shouldBe true
        dhcp.getSubnetAddress.getAddress should be(cidr.getAddress.toString)
        dhcp.getSubnetAddress.getPrefixLength should be(cidr.getPrefixLen)
        dhcp.getServerAddress.getAddress should be(gatewayIp)
        dhcp.getDnsServerAddressCount shouldBe 1
        dhcp.getDnsServerAddress(0) shouldBe
            IPAddressUtil.toProto(nameServers(0))
        dhcp.getOpt121RoutesCount shouldBe 1
        dhcp.getOpt121Routes(0).getDstSubnet shouldBe
            IPSubnetUtil.toProto(hrDest)
        dhcp.getOpt121Routes(0).getGateway shouldBe
            IPAddressUtil.toProto(hrNexthop)

        // Create a DHCP port to verify that the metadata opt121 route
        val portId = UUID.randomUUID()
        val dhcpPortIp = "10.0.0.7"
        val pJson = portJson(name = "port1", id = portId, networkId = nId,
            adminStateUp = true, deviceOwner = DeviceOwner.DHCP,
            fixedIps = List(IPAlloc(dhcpPortIp, sId.toString))).toString
        executeSqlStmts(insertTaskSql(id = 4, Create, PortType, pJson,
                                      portId, "tx3"))

        // Update the subnet
        val cidr2 = IPv4Subnet.fromCidr("10.0.1.0/24")
        val gatewayIp2 = "10.0.1.1"
        val dnss = List("8.8.4.4")
        val sJson2 = subnetJson(sId, nId, "net tenant", name = "test sub2",
                                cidr = cidr2.toString, gatewayIp = gatewayIp2,
                                dnsNameservers = dnss)
        executeSqlStmts(insertTaskSql(5, Update, SubnetType,
                                      sJson2.toString, sId, "tx4"))

        // Verify the updated subnet
        eventually {
            val dhcp2 = storage.get(classOf[Dhcp], sId).await()
            dhcp2 should not be null
            dhcp2.getDefaultGateway.getAddress shouldBe gatewayIp2
            dhcp2.getEnabled shouldBe true
            dhcp2.getSubnetAddress.getAddress shouldBe cidr2.getAddress.toString
            dhcp2.getSubnetAddress.getPrefixLength shouldBe cidr2.getPrefixLen
            dhcp2.getServerAddress.getAddress shouldBe dhcpPortIp
            dhcp2.getDnsServerAddressCount shouldBe 1
            dhcp2.getDnsServerAddress(0) shouldBe IPAddressUtil.toProto(dnss(0))
            dhcp2.getOpt121RoutesCount shouldBe 1
            dhcp2.getOpt121Routes(0).getGateway shouldBe
            IPAddressUtil.toProto(dhcpPortIp)
        }

        // Delete the subnet
        executeSqlStmts(insertTaskSql(6, Delete, SubnetType, null, sId, "tx5"))

        // Verify deletion
        eventually {
            storage.getAll(classOf[Dhcp]).await().size shouldBe 0
        }
    }

    it should "handle Config / AgentMembership Create" in {
        val cId = UUID.randomUUID()
        val cJson = configJson(cId, TunnelProtocol.VXLAN)
        executeSqlStmts(insertTaskSql(2, Create, ConfigType,
                                      cJson.toString, cId, "tx1"))

        // Verify the created default tunnel zone
        val tz = eventually(storage.get(classOf[TunnelZone], cId).await())
        tz.getId shouldBe toProto(cId)
        tz.getType shouldBe TunnelZone.Type.VXLAN
        tz.getName shouldBe "DEFAULT"

        // Set up the host.
        val hostId = UUID.randomUUID()
        val host = Host.newBuilder.setId(hostId).build()
        backend.ownershipStore.create(host, hostId)

        val ipAddress = "192.168.0.1"
        val amJson = agentMembershipJson(hostId, ipAddress)
        executeSqlStmts(insertTaskSql(3, Create, AgentMembershipType,
                                      amJson.toString, hostId, "tx2"))

        eventually {
            val tz1 = storage.get(classOf[TunnelZone], cId).await()
            tz1.getHostsCount shouldBe 1
            tz1.getHosts(0).getHostId shouldBe toProto(hostId)
            tz1.getHosts(0).getIp shouldBe IPAddressUtil.toProto(ipAddress)
        }

        // Tests that the host's reference to the tunnel zone is updated.
        val hostWithTz = storage.get(classOf[Host], hostId).await()
        hostWithTz.getTunnelZoneIdsCount shouldBe 1
        hostWithTz.getTunnelZoneIds(0) shouldBe toProto(cId)

        executeSqlStmts(insertTaskSql(4, Delete, AgentMembershipType,
                                      "", hostId, "tx3"))
        eventually {
            val tz2 = storage.get(classOf[TunnelZone], cId).await()
            tz2.getHostsList.size shouldBe 0
        }

        // Tests that the host's reference to the tunnel zone is cleared.
        val hostNoTz = storage.get(classOf[Host], hostId).await()
        hostNoTz.getTunnelZoneIdsCount shouldBe 0
    }
}
