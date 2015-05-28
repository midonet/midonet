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
import java.sql.{Connection, DriverManager, Statement}
import java.util.UUID
import java.util.concurrent.TimeUnit

import javax.sql.DataSource

import scala.collection.JavaConverters._
import scala.util.{Random, Try}

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.node.JsonNodeFactory
import com.typesafe.config.ConfigFactory

import org.apache.curator.framework.{CuratorFramework, CuratorFrameworkFactory}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.apache.curator.test.TestingServer
import org.apache.zookeeper.KeeperException.NoNodeException
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, BeforeAndAfterAll, FlatSpec, Matchers}
import org.slf4j.LoggerFactory

import org.midonet.cluster.ClusterNode.Context
import org.midonet.cluster.data.Bridge
import org.midonet.cluster.data.neutron.NeutronResourceType.{AgentMembership => AgentMembershipType, Config => ConfigType, Network => NetworkType, Port => PortType, Router => RouterType, RouterInterface => RouterInterfaceType, SecurityGroup => SecurityGroupType, Subnet => SubnetType}
import org.midonet.cluster.data.neutron.TaskType._
import org.midonet.cluster.data.neutron.{NeutronResourceType, TaskType}
import org.midonet.cluster.models.Commons
import org.midonet.cluster.models.Commons._
import org.midonet.cluster.models.Neutron.NeutronConfig.TunnelProtocol
import org.midonet.cluster.models.Neutron.NeutronPort.DeviceOwner
import org.midonet.cluster.models.Neutron._
import org.midonet.cluster.models.Topology._
import org.midonet.cluster.services.MidonetBackendService
import org.midonet.cluster.services.c3po.C3POMinion
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.cluster.util.UUIDUtil._
import org.midonet.cluster.util.{IPAddressUtil, IPSubnetUtil}
import org.midonet.conf.MidoTestConfigurator
import org.midonet.midolman.state.{MacPortMap, PathBuilder}
import org.midonet.packets.{IPSubnet, IPv4Subnet, MAC, UDP}
import org.midonet.util.MidonetEventually
import org.midonet.util.concurrent.toFutureOps

/** Tests the service that syncs the Neutron DB into Midonet's backend. */
class C3POMinionTestBase extends FlatSpec with BeforeAndAfter
                                          with BeforeAndAfterAll
                                          with Matchers
                                          with MidonetEventually {

    protected val log = LoggerFactory.getLogger(this.getClass)

    private val ZK_PORT = 50000 + Random.nextInt(15000)
    private val ZK_HOST = s"127.0.0.1:$ZK_PORT"

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

    val rootPath = "/test"

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
          |zookeeper.root_key : "$rootPath"
          |zookeeper.use_new_stack : true
        """.stripMargin)

    private val clusterCfg = new ClusterConfig(C3PO_CFG_OBJECT)

    // Data sources
    private val zk: TestingServer = new TestingServer(ZK_PORT)

    protected val nodeFactory = new JsonNodeFactory(true)

    protected var pathBldr: PathBuilder = _

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

    // ---------------------
    // DATA FIXTURES
    // ---------------------

    private def withStatement[T](fn: Statement => T) = {
        var c: Connection = null
        try {
            c = eventually(dataSrc.getConnection())
            val stmt = c.createStatement()
            val t = fn(stmt)
            stmt.close()
            t
        } finally {
            if (c != null) c.close()
        }
    }

    protected def executeSqlStmts(sqls: String*): Unit = withStatement { stmt =>
        for (sql <- sqls) eventually(stmt.executeUpdate(sql))
    }

    protected def getLastProcessedIdFromTable: Option[Int] =
        withStatement { stmt =>
            val rs = stmt.executeQuery(LAST_PROCESSED_ID)
            if (rs.next()) Some(rs.getInt(LAST_PROCESSED_ID_COL)) else None
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

    protected def clearReplMaps(): Unit = {
        // All the maps are under these two.
        for (path <- List(pathBldr.getBridgesPath, pathBldr.getRoutersPath)) {
            try curator.delete.deletingChildrenIfNeeded.forPath(path) catch {
                case _: NoNodeException => // Already gone/never created.
            }
        }
    }

    protected def insertTaskSql(id: Int, taskType: TaskType,
                                dataType: NeutronResourceType[_],
                                json: String, resourceId: UUID,
                                txnId: String): String = {
        val taskTypeStr = if (taskType != null) s"'${taskType.id}'" else "NULL"
        val dataTypeStr = if (dataType != null) s"'${dataType.id}'" else "NULL"
        val rsrcIdStr = if (resourceId != null) s"'$resourceId'" else "NULL"

        "INSERT INTO midonet_tasks values(" +
        s"$id, $taskTypeStr, $dataTypeStr, '$json', $rsrcIdStr, '$txnId', " +
        "datetime('now'))"
    }

    protected def insertCreateTask(taskId: Int,
                                   rsrcType: NeutronResourceType[_],
                                   json: String, rsrcId: UUID): Unit = {
        executeSqlStmts(insertTaskSql(
            taskId, Create, rsrcType, json, rsrcId, "txn-" + taskId))
    }

    protected def insertUpdateTask(taskId: Int,
                                   rsrcType: NeutronResourceType[_],
                                   json: String, rsrcId: UUID): Unit = {
        executeSqlStmts(insertTaskSql(
            taskId, Update, rsrcType, json, rsrcId, "txn-" + taskId))
    }

    protected def insertDeleteTask(taskId: Int,
                                   rsrcType: NeutronResourceType[_],
                                   rsrcId: UUID): Unit = {
        executeSqlStmts(insertTaskSql(
            taskId, Delete, rsrcType, "", rsrcId, "txn-" + taskId))
    }

    protected var curator: CuratorFramework = _
    protected var backendCfg: MidonetBackendConfig = _
    protected var backend: MidonetBackendService = _
    private var c3po: C3POMinion = _

    // ---------------------
    // TEST SETUP
    // ---------------------

    override protected def beforeAll() {
        try {
            val retryPolicy = new ExponentialBackoffRetry(1000, 10)
            curator = CuratorFrameworkFactory.newClient(ZK_HOST, retryPolicy)

            // Initialize tasks and state tables.
            createTaskTable()
            createStateTable()

            zk.start()
        } catch {
            case e: Throwable =>
                log.error("Failing setting up environment", e)
                cleanup()
        }
    }

    protected def storage = backend.store

    before {
        curator = CuratorFrameworkFactory.newClient(ZK_HOST,
            new ExponentialBackoffRetry(1000, 10)
        )
        backendCfg = new MidonetBackendConfig(
            MidoTestConfigurator.forClusters(C3PO_CFG_OBJECT)
        )

        pathBldr = new PathBuilder(backendCfg.rootKey)
        backend = new MidonetBackendService(backendCfg, curator)
        backend.startAsync().awaitRunning()
        curator.blockUntilConnected()

        val nodeCtx = new Context(UUID.randomUUID())
        c3po = new C3POMinion(nodeCtx, clusterCfg, dataSrc, backend, curator,
                              backendCfg)
        c3po.startAsync()
        c3po.awaitRunning(5, TimeUnit.SECONDS)
    }

    after {

        // The importer stops
        c3po.stopAsync()
        c3po.awaitTerminated(5, TimeUnit.SECONDS)

        // Clean the task table
        executeSqlStmts(EMPTY_TASK_TABLE)
        executeSqlStmts(EMPTY_STATE_TABLE)
        executeSqlStmts(INIT_STATE_ROW)

        log.info("Emptied the task/state tables.")

        // Make sure that ZK is pristine (not only data, but stuff like
        // nodes used for leader election
        curator.delete().deletingChildrenIfNeeded().forPath(rootPath)
        log.info("ZK is clean")

        clearReplMaps()
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

    protected def portJson(id: UUID,
                           networkId: UUID,
                           name: String = null,
                           adminStateUp: Boolean = true,
                           macAddr: String = MAC.random().toString,
                           fixedIps: List[IPAlloc] = null,
                           deviceId: UUID = null,
                           deviceOwner: DeviceOwner = null,
                           tenantId: String = "tenant",
                           securityGroups: List[UUID] = null,
                           hostId: UUID = null,
                           ifName: String = null): JsonNode = {
        val p = nodeFactory.objectNode
        p.put("id", id.toString)
        p.put("network_id", networkId.toString)
        p.put("admin_state_up", adminStateUp)
        p.put("mac_address", macAddr)
        if (name != null) p.put("name", name)
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
        if (hostId != null) p.put("binding:host_id", hostId.toString)
        if (ifName != null)
            p.putObject("binding:profile").put("interface_name", ifName)
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

    protected def routerJson(id: UUID,
                             name: String = null,
                             adminStateUp: Boolean = true,
                             status: String = null,
                             tenantId: String = "tenant",
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
            r.set("extenal_gateway_info", egi)
        }
        r
    }

    protected def routerInterfaceJson(routerId: UUID, portId: UUID,
                                      subnetId: UUID, tenantId: String = null)
    : JsonNode = {
        val ri = nodeFactory.objectNode
        ri.put("id", routerId.toString)
        ri.put("port_id", portId.toString)
        ri.put("subnet_id", subnetId.toString)
        if (tenantId != null) ri.put("tenant_id", tenantId)
        ri
    }


    protected def networkJson(id: UUID, tenantId: String = "tenant",
                              name: String = null,
                              shared: Boolean = false,
                              adminStateUp: Boolean = true,
                              external: Boolean = false,
                              uplink: Boolean = false): JsonNode = {
        val n = nodeFactory.objectNode
        n.put("id", id.toString)
        if (tenantId != null) n.put("tenant_id", tenantId)
        if (name != null) n.put("name", name)
        n.put("admin_state_up", adminStateUp)
        n.put("external", external)
        if (uplink) n.put("provider:network_type", "local")
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

    protected def subnetJson(id: UUID, networkId: UUID,
                             tenantId: String = "tenant",
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

    protected def portBindingJson(id: UUID,
                                  hostId: UUID,
                                  interfaceName: String,
                                  portId: UUID): JsonNode = {
        val pb = nodeFactory.objectNode
        pb.put("id", id.toString)
        pb.put("host_id", hostId.toString)
        pb.put("interface_name", interfaceName)
        pb.put("port_id", portId.toString)
        pb
    }

    protected case class ChainPair(inChain: Chain, outChain: Chain)
    protected def getChains(inChainId: Commons.UUID,
                            outChainId: Commons.UUID): ChainPair = {
        val fs = storage.getAll(classOf[Chain], List(inChainId, outChainId))
        val chains = fs.await()
        ChainPair(chains(0), chains(1))
    }

    protected def getChains(ipg: IPAddrGroup): ChainPair =
        getChains(ipg.getInboundChainId, ipg.getOutboundChainId)

    protected def checkReplTables(bridgeId: UUID, shouldExist: Boolean)
    : Unit = {
        val arpPath = pathBldr.getBridgeIP4MacMapPath(bridgeId)
        val macPath = pathBldr.getBridgeMacPortsPath(bridgeId)
        val vlansPath = pathBldr.getBridgeVlansPath(bridgeId)
        if (shouldExist) {
            curator.checkExists.forPath(arpPath) shouldNot be(null)
            curator.checkExists.forPath(macPath) shouldNot be(null)
            curator.checkExists.forPath(vlansPath) shouldNot be(null)
        } else {
            curator.checkExists.forPath(arpPath) shouldBe null
            curator.checkExists.forPath(macPath) shouldBe null
            curator.checkExists.forPath(vlansPath) shouldBe null
        }
    }

    protected def macEntryPath(nwId: UUID, mac: String, portId: UUID) = {
        val entry = MacPortMap.encodePersistentPath(MAC.fromString(mac), portId)
        pathBldr.getBridgeMacPortEntryPath(nwId, Bridge.UNTAGGED_VLAN_ID, entry)
    }

    protected def checkPortBinding(hostId: UUID, portId: UUID,
                                   interfaceName: String): Unit = {
        val hostFtr = storage.get(classOf[Host], hostId)
        val portFtr = storage.get(classOf[Port], portId)
        val (host, port) = (hostFtr.await(), portFtr.await())
        host.getPortIdsList.asScala.map(_.asJava) should contain only portId
        port.getHostId.asJava shouldBe hostId
        port.getInterfaceName shouldBe interfaceName
    }

    protected def createHost(hostId: UUID = null): Host = {
        val id = if (hostId != null) hostId else UUID.randomUUID()
        val host = Host.newBuilder.setId(id).build()
        backend.ownershipStore.create(host, id)
        host
    }

    protected def deleteHost(hostId: UUID): Unit = {
        backend.ownershipStore.delete(classOf[Host], hostId, hostId)
    }

    protected def createTenantNetwork(taskId: Int, nwId: UUID,
                                      external: Boolean = false): Unit = {
        val json = networkJson(nwId, name = "tenant-network-" + nwId,
                               tenantId = "tenant").toString
        insertCreateTask(taskId, NetworkType, json, nwId)
    }

    protected def createUplinkNetwork(taskId: Int, nwId: UUID): Unit = {
        val json = networkJson(nwId, name = "uplink-network-" + nwId,
                               uplink = true).toString
        insertCreateTask(taskId, NetworkType, json, nwId)
    }

    protected def createRouter(taskId: Int, routerId: UUID,
                               gwPortId: UUID = null): Unit = {
        val json = routerJson(routerId, name = "router-" + routerId,
                              gwPortId = gwPortId).toString
        insertCreateTask(taskId, RouterType, json, routerId)
    }

    protected def createSubnet(taskId: Int, subnetId: UUID,
                               networkId: UUID, cidr: String): Unit = {
        val json = subnetJson(subnetId, networkId, cidr = cidr).toString
        insertCreateTask(taskId, SubnetType, json, subnetId)
    }

    protected def createDhcpPort(taskId: Int, portId: UUID, networkId: UUID,
                                 subnetId: UUID, ipAddr: String): Unit = {
        val json = portJson(portId, networkId, deviceOwner = DeviceOwner.DHCP,
                            fixedIps = List(IPAlloc(ipAddr, subnetId.toString)))
        insertCreateTask(taskId, PortType, json.toString, portId)
    }

    protected def createRouterGatewayPort(taskId: Int, portId: UUID,
                                          networkId: UUID, routerId: UUID,
                                          gwIpAddr: String, subnetId: UUID)
    : Unit = {
        val gwIpAlloc = IPAlloc(gwIpAddr, subnetId.toString)
        val json = portJson(portId, networkId, fixedIps = List(gwIpAlloc),
                            deviceId = routerId,
                            deviceOwner = DeviceOwner.ROUTER_GATEWAY)
        insertCreateTask(taskId, PortType, json.toString, portId)
    }

    protected def createRouterInterfacePort(taskId: Int, portId: UUID,
                                            networkId: UUID, routerId: UUID,
                                            ipAddr: String, macAddr: String,
                                            subnetId: UUID, hostId: UUID = null,
                                            ifName: String = null): Unit = {
        val json = portJson(portId, networkId, deviceId = routerId,
                            deviceOwner = DeviceOwner.ROUTER_INTERFACE, macAddr = macAddr,
                            fixedIps = List(IPAlloc(ipAddr, subnetId.toString)),
                            hostId = hostId, ifName = ifName)
        insertCreateTask(taskId, PortType, json.toString, portId)
    }

    protected def createRouterInterface(taskId: Int, routerId: UUID,
                                        portId: UUID, subnetId: UUID): Unit = {
        val json = routerInterfaceJson(routerId, portId, subnetId).toString
        insertCreateTask(taskId, RouterInterfaceType, json, routerId)
    }

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

        eventually(checkReplTables(network1Uuid, shouldExist = true))

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
        eventually(checkReplTables(network2Uuid, shouldExist = true))

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
            checkReplTables(network1Uuid, shouldExist = false)
        }

        eventually {
            storage.exists(classOf[Network], network2Uuid).await() shouldBe true
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
        val portMac = MAC.random().toString
        val vifPortJson = portJson(name = "port1", id = vifPortUuid,
                                   networkId = network1Uuid,
                                   macAddr = portMac).toString
        executeSqlStmts(insertTaskSql(
                id = 3, Create, PortType, vifPortJson, vifPortUuid, "tx2"))

        val vifPort = eventually(storage.get(classOf[Port], vifPortId).await())
        vifPort.getId should be (vifPortId)
        vifPort.getNetworkId should be (toProto(network1Uuid))
        vifPort.getAdminStateUp shouldBe true

        val network1 = storage.get(classOf[Network], network1Uuid).await()
        network1.getPortIdsList should contain (vifPortId)

        eventually(curator.checkExists.forPath(
            macEntryPath(network1Uuid, portMac, vifPortId)) shouldNot be(null))

        // Update the port admin status and MAC address. Through the Neutron
        // API, you cannot change the Network the port is attached to.
        val portMac2 = MAC.random().toString
        val vifPortUpdate = portJson(id = vifPortUuid, networkId = network1Uuid,
                                     adminStateUp = false,      // Down now.
                                     macAddr = portMac2).toString
        executeSqlStmts(insertTaskSql(
                id = 4, Update, PortType, vifPortUpdate, vifPortUuid, "tx3"))

        eventually {
            val updatedVifPort = storage.get(classOf[Port], vifPortId).await()
            updatedVifPort.getAdminStateUp shouldBe false
            curator.checkExists.forPath(
                macEntryPath(network1Uuid, portMac, vifPortId)) shouldBe null
            curator.checkExists.forPath(
                macEntryPath(network1Uuid,
                             portMac2, vifPortId)) shouldNot be(null)
        }

        // Delete the VIF port.
        executeSqlStmts(insertTaskSql(
                id = 5, Delete, PortType, json = null, vifPortUuid, "tx4"))

        eventually {
            storage.exists(classOf[Port], vifPortId).await() shouldBe false
            curator.checkExists.forPath(
                macEntryPath(network1Uuid, portMac2, vifPortId)) shouldBe null
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

        val ipg1 = eventually(storage.get(classOf[IPAddrGroup], sg1Id).await())
        val ChainPair(inChain1, outChain1) = getChains(ipg1)

        inChain1.getRuleIdsCount should be(0)
        outChain1.getRuleIdsCount should be(2)
        val outChain1Rules = storage.getAll(classOf[Rule],
                                            outChain1.getRuleIdsList.asScala)
                                    .await()
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
            val ipg1a = storage.get(classOf[IPAddrGroup], sg1Id).await()
            val ChainPair(inChain1a, outChain1a) = getChains(ipg1a)

            inChain1a.getId should be(inChain1.getId)
            inChain1a.getRuleIdsCount should be(1)
            inChain1a.getRuleIds(0) should be(toProto(rule1Id))

            outChain1a.getId should be(outChain1.getId)
            outChain1a.getRuleIdsCount should be(1)
            outChain1a.getRuleIds(0) should be(toProto(rule3Id))
        }

        val ipg1aRules = storage.getAll(classOf[Rule],
                                        List(rule1Id, rule3Id)).await()

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
                storage.getAll(classOf[IPAddrGroup]),
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
        val sJson = subnetJson(sId, nId, name = "test sub",
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
        val pJson = portJson(id = portId, networkId = nId,
            adminStateUp = true, deviceOwner = DeviceOwner.DHCP,
            fixedIps = List(IPAlloc(dhcpPortIp, sId.toString))).toString
        executeSqlStmts(insertTaskSql(id = 4, Create, PortType, pJson,
                                      portId, "tx3"))

        // Update the subnet
        val cidr2 = IPv4Subnet.fromCidr("10.0.1.0/24")
        val gatewayIp2 = "10.0.1.1"
        val dnss = List("8.8.4.4")
        val sJson2 = subnetJson(sId, nId, name = "test sub2",
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
