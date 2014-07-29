/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */

package org.midonet.midolman.state

import java.lang.{Integer => JInt}
import java.net.InetAddress
import java.util.{UUID, Set => JSet, Map => JMap, HashMap => JHashMap, HashSet => JHashSet}
import scala.concurrent.{ExecutionContext, promise, Promise, Future}

import com.datastax.driver.core._
import com.google.common.util.concurrent.{FutureCallback, Futures}
import org.slf4j.{Logger, LoggerFactory}

import org.midonet.cassandra.CassandraClient
import org.midonet.midolman.state.ConnTrackState.ConnTrackKey
import org.midonet.midolman.state.NatState.NatKey
import org.midonet.midolman.state.NatState.NatBinding
import org.midonet.packets.IPAddr
import org.midonet.util.collection.Bimap


object FlowStateStorage {
    val CONNTRACK_BY_INGRESS_TABLE = "conntrack_by_ingress_port"
    val CONNTRACK_BY_EGRESS_TABLE = "conntrack_by_egress_port"

    val NAT_BY_INGRESS_TABLE = "nat_by_ingress_port"
    val NAT_BY_EGRESS_TABLE = "nat_by_egress_port"

    val FLOW_STATE_TTL_SECONDS = 60

    val NAT_KEY_TYPES = Bimap[NatKey.Type, String](List(
        NatKey.FWD_DNAT -> "fwd_dnat",
        NatKey.FWD_SNAT -> "fwd_snat",
        NatKey.FWD_STICKY_DNAT -> "fwd_sticky_dnat",
        NatKey.REV_DNAT -> "rev_dnat",
        NatKey.REV_SNAT -> "rev_snat",
        NatKey.REV_STICKY_DNAT -> "rev_sticky_dnat"))

    def natKeyTypeFromString(str: String): Option[NatKey.Type] = NAT_KEY_TYPES.inverse.get(str)

    def natKeyTypeToString(k: NatKey.Type): Option[String] = NAT_KEY_TYPES.get(k)

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
            networkSrc = r.getInet("srcIp"),
            networkDst = r.getInet("dstIp"),
            transportSrc = r.getInt("srcPort"),
            transportDst = r.getInt("dstPort"),
            networkProtocol = r.getInt("proto").toByte)

    def rowToNatBinding(r: Row) = NatBinding(
            networkAddress = r.getInet("translateIp"),
            transportPort = r.getInt("translatePort"))

    def apply(client: CassandraClient) = new FlowStateStorageImpl(client)
}

trait FlowStateStorage {
    def fetchStrongConnTrackRefs(portId: UUID)(implicit ec: ExecutionContext): Future[JSet[ConnTrackKey]]
    def fetchWeakConnTrackRefs(portId: UUID)(implicit ec: ExecutionContext): Future[JSet[ConnTrackKey]]
    def fetchStrongNatRefs(portId: UUID)(implicit ec: ExecutionContext): Future[JMap[NatKey, NatBinding]]
    def fetchWeakNatRefs(portId: UUID)(implicit ec: ExecutionContext): Future[JMap[NatKey, NatBinding]]

    def touchNatKey(k: NatKey, v: NatBinding, strongRef: UUID, weakRefs: Iterator[UUID])
    def touchConnTrackKey(k: ConnTrackKey, strongRef: UUID, weakRefs: Iterator[UUID])

    def submit()
}

class FlowStateStorageImpl(val client: CassandraClient) extends FlowStateStorage {
    private val log: Logger = LoggerFactory.getLogger(classOf[FlowStateStorage])

    import FlowStateStorage._

    // FIXME(guillermo) nat keys should have a device id
    val DUMMY_UUID = UUID.fromString("4d6b7c7f-d8ec-42c0-a4bb-a1a0777605cc")

    var batch: BatchStatement = new BatchStatement()

    class Prepared(query: String) {
        var _statement: PreparedStatement = null

        def apply(s: Session) = {
            if (_statement eq null)
                _statement = s.prepare(query)
            _statement
        }
    }

    def fetchByPortStatement(table: String) =
        new Prepared(s"SELECT * FROM $table  WHERE port = ?;")

    def touchConnTrackStatement(table: String) =
        new Prepared(
            s"INSERT INTO $table " +
                "  (port, proto, srcIp, srcPort, dstIp, dstPort, device) " +
                " VALUES (?, ?, ?, ?, ?, ?, ?) " +
                s" USING TTL $FLOW_STATE_TTL_SECONDS;")

    def touchNatStatement(table: String) =
        new Prepared(
            s"INSERT INTO $table " +
                "  (port, type, proto, srcIp, srcPort, dstIp, dstPort, device, translateIp, translatePort) " +
                " VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?) " +
                s" USING TTL $FLOW_STATE_TTL_SECONDS;")

    val touchIngressConnTrack = touchConnTrackStatement(CONNTRACK_BY_INGRESS_TABLE)
    val touchEgressConnTrack = touchConnTrackStatement(CONNTRACK_BY_EGRESS_TABLE)

    val touchIngressNat = touchNatStatement(NAT_BY_INGRESS_TABLE)
    val touchEgressNat = touchNatStatement(NAT_BY_EGRESS_TABLE)

    val fetchIngressConnTrack = fetchByPortStatement(CONNTRACK_BY_INGRESS_TABLE)
    val fetchEgressConnTrack = fetchByPortStatement(CONNTRACK_BY_EGRESS_TABLE)
    val fetchIngressNat = fetchByPortStatement(NAT_BY_INGRESS_TABLE)
    val fetchEgressNat = fetchByPortStatement(NAT_BY_EGRESS_TABLE)

    final def withSession[U](body: (Session) => U): Option[U] = {
        val s = client.session
        if (s ne null)
            Option(body(s))
        else
            None
    }

    private def bind(st: PreparedStatement, port: UUID, k: ConnTrackKey) = {
        st.bind(port, k.networkProtocol.toInt.asInstanceOf[JInt],
                      ipAddrToInet(k.networkSrc), k.icmpIdOrTransportSrc.asInstanceOf[JInt],
                      ipAddrToInet(k.networkDst), k.icmpIdOrTransportDst.asInstanceOf[JInt],
                      k.deviceId)
    }

    private def bind(st: PreparedStatement, port: UUID, k: NatKey, v: NatBinding) = {
        st.bind(port, natKeyTypeToString(k.keyType).orNull,
                      k.networkProtocol.toInt.asInstanceOf[JInt],
                      ipAddrToInet(k.networkSrc), k.transportSrc.asInstanceOf[JInt],
                      ipAddrToInet(k.networkDst), k.transportDst.asInstanceOf[JInt],
                      DUMMY_UUID,
                      ipAddrToInet(v.networkAddress), v.transportPort.asInstanceOf[JInt])
    }

    override def touchConnTrackKey(k: ConnTrackKey, strongRef: UUID,
            weakRefs: Iterator[UUID]): Unit = withSession {
        s =>
            batch.add(bind(touchIngressConnTrack(s), strongRef, k))
            while (weakRefs.hasNext) {
                batch.add(bind(touchEgressConnTrack(s), weakRefs.next(), k))
            }
    }

    override def touchNatKey(k: NatKey, v: NatBinding, strongRef: UUID,
            weakRefs: Iterator[UUID]): Unit = withSession {
        s =>
            batch.add(bind(touchIngressNat(s), strongRef, k, v))
            while (weakRefs.hasNext) {
                batch.add(bind(touchEgressNat(s), weakRefs.next(), k, v))
            }
    }

    override def submit(): Unit = withSession {
        s =>
            val result = s.executeAsync(batch)
            Futures.addCallback(result, touchCallback)
            batch = new BatchStatement()
    }

    override def fetchStrongConnTrackRefs(port: UUID)(implicit ec: ExecutionContext) =
        fetch(fetchIngressConnTrack, port, resultSetToConnTrackKeys)

    override def fetchWeakConnTrackRefs(port: UUID)(implicit ec: ExecutionContext) =
        fetch(fetchEgressConnTrack, port, resultSetToConnTrackKeys)

    override def fetchStrongNatRefs(port: UUID)(implicit ec: ExecutionContext) =
        fetch(fetchIngressNat, port, resultSetToNatBindings)

    override def fetchWeakNatRefs(port: UUID)(implicit ec: ExecutionContext) =
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

    private def fetch[U](statement: Prepared, portId: UUID, transform: (ResultSet) => U)
                (implicit ec: ExecutionContext): Future[U] = {
        peelResult (withSession { s =>
            toScalaFuture(s.executeAsync(statement(s).bind(portId))) map transform
        })
    }

    private val touchCallback = new FutureCallback[ResultSet] {
        override def onSuccess(result: ResultSet): Unit = {
            log.info("key touch success: {}", result)
        }

        override def onFailure(t: Throwable): Unit = {
            log.warn("failed to touch keys", t)
        }
    }

    private def toScalaFuture(f: ResultSetFuture): Future[ResultSet] = {
        val p: Promise[ResultSet] = promise[ResultSet]()
        Futures.addCallback(f, new FutureCallback[ResultSet](){
            override def onSuccess(result: ResultSet): Unit = {
                p.success(result)
            }

            override def onFailure(t: Throwable): Unit = {
                p.failure(t)
            }
        })
        p.future
    }
}