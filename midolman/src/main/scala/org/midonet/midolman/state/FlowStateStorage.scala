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

package org.midonet.midolman.state

import java.lang.{Integer => JInt}
import java.net.InetAddress
import java.util.{UUID, Set => JSet, Map => JMap, HashMap => JHashMap,
                  HashSet => JHashSet, Iterator => JIterator}
import java.util.concurrent.{TimeoutException, TimeUnit}
import org.midonet.util.concurrent.CallingThreadExecutionContext

import scala.concurrent.{ExecutionContext, Promise, Future}
import scala.concurrent.duration.Duration

import akka.actor.ActorSystem
import com.datastax.driver.core._
import com.google.common.util.concurrent.{FutureCallback, Futures}
import org.slf4j.{Logger, LoggerFactory}
import org.midonet.cluster.backend.cassandra.CassandraClient
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.{KeyType, NatKey, NatBinding}
import org.midonet.packets.{IPv4Addr, IPAddr}
import org.midonet.util.collection.Bimap

object FlowStateStorage {
    val KEYSPACE_NAME = "MidonetFlowState"

    val CONNTRACK_BY_INGRESS_TABLE = "conntrack_by_ingress_port"
    val CONNTRACK_BY_EGRESS_TABLE = "conntrack_by_egress_port"
    val NAT_BY_INGRESS_TABLE = "nat_by_ingress_port"
    val NAT_BY_EGRESS_TABLE = "nat_by_egress_port"

    object Schema {
        def CONNTRACK(name: String) =
            s"CREATE TABLE IF NOT EXISTS $name ( " +
                "        port uuid, " +
                "        proto int, " +
                "        srcIp inet, " +
                "        srcPort int, " +
                "        dstIp inet, " +
                "        dstPort int, " +
                "        device uuid, " +
                "PRIMARY KEY ((port, proto, srcIp, srcPort, dstIp, dstPort, device)));"

        def CONNTRACK_IDX(table: String) =
            s"CREATE INDEX IF NOT EXISTS ON $table (port);"

        def NAT(name: String) =
            s"CREATE TABLE IF NOT EXISTS $name ( " +
                "        port uuid, " +
                "        type text, " +
                "        proto int, " +
                "        srcIp inet, " +
                "        srcPort int, " +
                "        dstIp inet, " +
                "        dstPort int, " +
                "        device uuid, " +
                "        translateIp inet, " +
                "        translatePort int, " +
                "PRIMARY KEY ((port, type, proto, srcIp, srcPort, dstIp, dstPort, device)));"

        def NAT_IDX(table: String) =
            s"CREATE INDEX IF NOT EXISTS ON $table (port);"
    }

    val SCHEMA = Array[String](
            Schema.CONNTRACK(CONNTRACK_BY_INGRESS_TABLE),
            Schema.CONNTRACK_IDX(CONNTRACK_BY_INGRESS_TABLE),
            Schema.CONNTRACK(CONNTRACK_BY_EGRESS_TABLE),
            Schema.CONNTRACK_IDX(CONNTRACK_BY_EGRESS_TABLE),
            Schema.NAT(NAT_BY_INGRESS_TABLE),
            Schema.NAT_IDX(NAT_BY_INGRESS_TABLE),
            Schema.NAT(NAT_BY_EGRESS_TABLE),
            Schema.NAT_IDX(NAT_BY_EGRESS_TABLE))
    val SCHEMA_TABLE_NAMES = Array[String](
        CONNTRACK_BY_INGRESS_TABLE, CONNTRACK_BY_INGRESS_TABLE,
        CONNTRACK_BY_EGRESS_TABLE, CONNTRACK_BY_EGRESS_TABLE,
        NAT_BY_INGRESS_TABLE, NAT_BY_INGRESS_TABLE,
        NAT_BY_EGRESS_TABLE, NAT_BY_EGRESS_TABLE)

    val NAT_KEY_TYPES = Bimap[NatState.KeyType, String](List(
        NatState.FWD_DNAT -> "fwd_dnat",
        NatState.FWD_SNAT -> "fwd_snat",
        NatState.FWD_STICKY_DNAT -> "fwd_sticky_dnat",
        NatState.REV_DNAT -> "rev_dnat",
        NatState.REV_SNAT -> "rev_snat",
        NatState.REV_STICKY_DNAT -> "rev_sticky_dnat"))

    def natKeyTypeFromString(str: String): Option[KeyType] = NAT_KEY_TYPES.inverse.get(str)

    def natKeyTypeToString(k: KeyType): Option[String] = NAT_KEY_TYPES.get(k)

    implicit def inetToIPAddr(inet: InetAddress) = IPAddr.fromBytes(inet.getAddress)

    def ipAddrToInet(ip: IPAddr): InetAddress = InetAddress.getByAddress(ip.toBytes)

    def rowToConnTrack(r: Row) = ConnTrackKey(
            networkSrc = r.getInet("srcIp"),
            networkDst = r.getInet("dstIp"),
            icmpIdOrTransportSrc = r.getInt("srcPort"),
            icmpIdOrTransportDst = r.getInt("dstPort"),
            deviceId = r.getUUID("device"),
            networkProtocol = r.getInt("proto").toByte)

    def rowToNatKey(r: Row) = NatKey(
            keyType = NAT_KEY_TYPES.inverse.get(r.getString("type")).get,
            networkSrc = inetToIPAddr(r.getInet("srcIp")).asInstanceOf[IPv4Addr],
            networkDst = inetToIPAddr(r.getInet("dstIp")).asInstanceOf[IPv4Addr],
            transportSrc = r.getInt("srcPort"),
            transportDst = r.getInt("dstPort"),
            networkProtocol = r.getInt("proto").toByte,
            deviceId = r.getUUID("device"))

    def rowToNatBinding(r: Row) = NatBinding(
            networkAddress = inetToIPAddr(r.getInet("translateIp")).asInstanceOf[IPv4Addr],
            transportPort = r.getInt("translatePort"))

    def apply(sessionFuture: Future[Session]): FlowStateStorage = new FlowStateStorageImpl(sessionFuture)
}

trait FlowStateStorage {
    def fetchStrongConnTrackRefs(portId: UUID)
        (implicit ec: ExecutionContext, as: ActorSystem): Future[JSet[ConnTrackKey]]

    def fetchWeakConnTrackRefs(portId: UUID)
        (implicit ec: ExecutionContext, as: ActorSystem): Future[JSet[ConnTrackKey]]

    def fetchStrongNatRefs(portId: UUID)
        (implicit ec: ExecutionContext, as: ActorSystem): Future[JMap[NatKey, NatBinding]]

    def fetchWeakNatRefs(portId: UUID)
        (implicit ec: ExecutionContext, as: ActorSystem): Future[JMap[NatKey, NatBinding]]

    def touchNatKey(k: NatKey, v: NatBinding, strongRef: UUID, weakRefs: JIterator[UUID])
    def touchConnTrackKey(k: ConnTrackKey, strongRef: UUID, weakRefs: JIterator[UUID])

    def submit()
}


/**
 * FlowStateStorage: store & fetch flow state keys from Cassandra.
 *
 * This class is *NOT* thread safe, each thread that needs to submit or fetch
 * state keys from Cassandra should get its own instance. The only reason it
 * is not thread safe is because write operations are batched, a batch is
 * prepared by a series of touch*() method calls and it's then fired
 * by invoking submit.
 *
 * All operations are asynchronous, submit is meant to be fire-and-forget with
 * no error control and for this reason, returns Unit.
 */
class FlowStateStorageImpl(val sessionFuture: Future[Session]) extends FlowStateStorage {
    private val log: Logger = LoggerFactory.getLogger(classOf[FlowStateStorage])

    import FlowStateStorage._

    var batch: BatchStatement = new BatchStatement()
    val ASYNC_REQUEST_TIMEOUT = Duration.create(3, TimeUnit.SECONDS)

    def fetchByPortStatement(table: String) =
            s"SELECT * FROM $table  WHERE port = ?;"

    def touchConnTrackStatement(table: String) =
            s"INSERT INTO $table " +
                "  (port, proto, srcIp, srcPort, dstIp, dstPort, device) " +
                " VALUES (?, ?, ?, ?, ?, ?, ?) " +
                " USING TTL ?;"

    def touchNatStatement(table: String) =
            s"INSERT INTO $table " +
                "  (port, type, proto, srcIp, srcPort, dstIp, dstPort, device, translateIp, translatePort) " +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                " USING TTL ?;"

    @volatile
    var session: Session = null

    var touchIngressConnTrack: PreparedStatement = _
    var touchEgressConnTrack: PreparedStatement = _

    var touchIngressNat: PreparedStatement = _
    var touchEgressNat: PreparedStatement = _

    var fetchIngressConnTrack: PreparedStatement = _
    var fetchEgressConnTrack: PreparedStatement = _
    var fetchIngressNat: PreparedStatement = _
    var fetchEgressNat: PreparedStatement = _

    sessionFuture.onSuccess {
        case s =>
            touchIngressConnTrack = s.prepare(touchConnTrackStatement(CONNTRACK_BY_INGRESS_TABLE))
            touchEgressConnTrack = s.prepare(touchConnTrackStatement(CONNTRACK_BY_EGRESS_TABLE))

            touchIngressNat = s.prepare(touchNatStatement(NAT_BY_INGRESS_TABLE))
            touchEgressNat = s.prepare(touchNatStatement(NAT_BY_EGRESS_TABLE))

            fetchIngressConnTrack = s.prepare(fetchByPortStatement(CONNTRACK_BY_INGRESS_TABLE))
            fetchEgressConnTrack = s.prepare(fetchByPortStatement(CONNTRACK_BY_EGRESS_TABLE))
            fetchIngressNat = s.prepare(fetchByPortStatement(NAT_BY_INGRESS_TABLE))
            fetchEgressNat = s.prepare(fetchByPortStatement(NAT_BY_EGRESS_TABLE))
            session = s
    }(CallingThreadExecutionContext)

    private def bind(st: PreparedStatement, port: UUID, k: ConnTrackKey) = {
        st.bind(port, k.networkProtocol.toInt.asInstanceOf[JInt],
                      ipAddrToInet(k.networkSrc), k.icmpIdOrTransportSrc.asInstanceOf[JInt],
                      ipAddrToInet(k.networkDst), k.icmpIdOrTransportDst.asInstanceOf[JInt],
                      k.deviceId,
                      k.expiresAfter.toSeconds.toInt: java.lang.Integer)
    }

    private def bind(st: PreparedStatement, port: UUID, k: NatKey, v: NatBinding) = {
        st.bind(port, natKeyTypeToString(k.keyType).orNull,
                      k.networkProtocol.toInt.asInstanceOf[JInt],
                      ipAddrToInet(k.networkSrc), k.transportSrc.asInstanceOf[JInt],
                      ipAddrToInet(k.networkDst), k.transportDst.asInstanceOf[JInt],
                      k.deviceId,
                      ipAddrToInet(v.networkAddress), v.transportPort.asInstanceOf[JInt],
                      k.expiresAfter.toSeconds.toInt: java.lang.Integer)
    }

    /**
     * Adds a connection tracking key to the next batch that will be sent
     * to cassandra.
     *
     * @param k The key
     * @param strongRef Ingress port.
     * @param weakRefs Egress ports.
     */
    override def touchConnTrackKey(k: ConnTrackKey, strongRef: UUID,
            weakRefs: JIterator[UUID]): Unit = if (session ne null) {
        if (strongRef ne null)
            batch.add(bind(touchIngressConnTrack, strongRef, k))
        while (weakRefs.hasNext) {
            batch.add(bind(touchEgressConnTrack, weakRefs.next(), k))
        }
    }

    /**
     * Addss a NAT key to the next batch that will be sent to Cassandra.
     *
     * @param k The key
     * @param v Its value
     * @param strongRef Ingress port
     * @param weakRefs Egress ports.
     */
    override def touchNatKey(k: NatKey, v: NatBinding, strongRef: UUID,
            weakRefs: JIterator[UUID]): Unit = if (session ne null) {
        if (strongRef ne null)
            batch.add(bind(touchIngressNat, strongRef, k, v))
        while (weakRefs.hasNext) {
            batch.add(bind(touchEgressNat, weakRefs.next(), k, v))
        }
    }

    /**
     * Sends all state accumulated through touchConnTrackKey() and touchNatKey()
     * to Cassandra, asynchronously. Errors will be logged but ignored.
     */
    override def submit(): Unit = if (session ne null) {
        val result = session.executeAsync(batch)
        Futures.addCallback(result, touchCallback)
        batch = new BatchStatement()
    }

    /**
     * Fetch all conntrack keys for which a give port is ingress.
     */
    override def fetchStrongConnTrackRefs(port: UUID)(implicit ec: ExecutionContext, as: ActorSystem) =
        fetch(fetchIngressConnTrack, port, resultSetToConnTrackKeys)

    /**
     * Fetch all conntrack keys for which a give port is egress.
     */
    override def fetchWeakConnTrackRefs(port: UUID)(implicit ec: ExecutionContext, as: ActorSystem) =
        fetch(fetchEgressConnTrack, port, resultSetToConnTrackKeys)

    /**
     * Fetch all nat keys for which a give port is ingress.
     */
    override def fetchStrongNatRefs(port: UUID)(implicit ec: ExecutionContext, as: ActorSystem) =
        fetch(fetchIngressNat, port, resultSetToNatBindings)

    /**
     * Fetch all nat keys for which a give port is egress.
     */
    override def fetchWeakNatRefs(port: UUID)(implicit ec: ExecutionContext, as: ActorSystem) =
        fetch(fetchEgressNat, port, resultSetToNatBindings)

    private def resultSetToConnTrackKeys(rs: ResultSet): JSet[ConnTrackKey] = {
        val keys = new JHashSet[ConnTrackKey]()
        val rows = rs.iterator()
        while (rows.hasNext) {
            keys.add(rowToConnTrack(rows.next()))
        }
        keys
    }

    private def resultSetToNatBindings(rs: ResultSet): JMap[NatKey, NatBinding] = {
        val rows = rs.iterator()
        val bindings = new JHashMap[NatKey, NatBinding]()
        while (rows.hasNext) {
            val row = rows.next()
            bindings.put(rowToNatKey(row), rowToNatBinding(row))
        }
        bindings
    }

    private def peelResult[U](result: Option[Future[U]]): Future[U] = result match {
        case Some(f) => f
        case None =>
            Future.failed(new IllegalStateException("Cassandra client is not connected"))
    }

    private def fetch[U](statement: PreparedStatement, portId: UUID, transform: (ResultSet) => U)
                (implicit ec: ExecutionContext, as: ActorSystem): Future[U] = {
        peelResult (Option(session) map { s =>
            toScalaFuture(s.executeAsync(statement.bind(portId))) map transform
        })
    }

    private val touchCallback = new FutureCallback[ResultSet] {
        override def onSuccess(result: ResultSet): Unit = {
            log.debug("key touch success: {}", result)
        }

        override def onFailure(t: Throwable): Unit = {
            log.warn("failed to touch keys", t)
        }
    }

    private def toScalaFuture(f: ResultSetFuture)
            (implicit ec: ExecutionContext,
                      as: ActorSystem): Future[ResultSet] = {

        val p: Promise[ResultSet] = Promise[ResultSet]()
        Futures.addCallback(f, new FutureCallback[ResultSet](){
            override def onSuccess(result: ResultSet): Unit = {
                if(!p.trySuccess(result)) {
                    log.warn("failed to complete future with success {}", result)
                }
            }

            override def onFailure(t: Throwable): Unit = {
                if (!p.tryFailure(t)) {
                    log.warn("failed to complete future with failure {}", t)
                }
            }
        })
        val exp = as.scheduler.scheduleOnce(ASYNC_REQUEST_TIMEOUT) {
            p tryFailure new TimeoutException()
        }
        p.future onComplete {
            case _ => if (!exp.isCancelled) exp.cancel()
        }
        p.future
    }
}
