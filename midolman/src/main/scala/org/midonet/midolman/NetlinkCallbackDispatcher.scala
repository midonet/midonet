// Copyright 2013 Midokura Europe SARL

package org.midonet.midolman

import scala.collection.JavaConversions._
import akka.actor._
import akka.event.LoggingReceive
import javax.inject.Inject

import org.midonet.midolman.logging.ActorLogWithoutPath
import org.midonet.midolman.guice.datapath.DatapathModule.UPCALL_DATAPATH_CONNECTION
import org.midonet.midolman.io.{ManagedDatapathConnection, DatapathConnectionPool}
import org.midonet.util.BatchCollector

object NetlinkCallbackDispatcher extends Referenceable {
    override val Name = "NetlinkCallbackDispatcher"
}

class NetlinkCallbackDispatcher extends Actor with ActorLogWithoutPath {
    case class _processCallbacks(callbacks: List[Array[Runnable]])

    @Inject
    var datapathConnPool: DatapathConnectionPool = null

    @Inject
    @UPCALL_DATAPATH_CONNECTION
    var upcallManagedConnection: ManagedDatapathConnection = null

    def upcallConnection = upcallManagedConnection.getConnection

    override def preStart() {
        super.preStart()
        upcallConnection.setCallbackDispatcher(makeBatchCollector)
        datapathConnPool.getAll foreach {
            case conn => conn.setCallbackDispatcher(makeBatchCollector)
        }
    }

    private def runBatch(batch: Array[Runnable]) {
        var i = 0
        while (i < batch.length && batch(i) != null) {
            batch(i).run()
            i += 1
        }
    }

    def receive = LoggingReceive {
        case _processCallbacks(Nil) =>
            log.debug("Received an empty callback batch")

        case _processCallbacks(List(head)) =>
            log.debug("Processing 1 netlink callback batch")
            runBatch(head)

        case _processCallbacks(head :: tail) =>
            log.debug("Processing {} netlink callback batches", tail.size + 1)
            tail foreach { batch =>
                context.dispatcher.execute(new Runnable() {
                    override def run() { runBatch(batch) }
                })
            }
            /* run one batch out of this actor directly, but do it at then end,
             * once the other batches have been scheduled. */
            runBatch(head)
    }

    private def makeBatchCollector =
        new BatchCollector[Runnable] {
            val BATCH_SIZE = 8
            var allCBs: List[Array[Runnable]] = Nil
            var currentCBs: Array[Runnable] = new Array[Runnable](BATCH_SIZE)
            var cursor = 0

            private def cycle() {
                allCBs ::= currentCBs
                currentCBs = new Array[Runnable](BATCH_SIZE)
                cursor = 0
            }

            override def endBatch() {
                if (cursor > 0)
                    cycle()

                if (allCBs.size > 0) {
                    self ! _processCallbacks(allCBs)
                    allCBs = Nil
                }
            }

            override def submit(r: Runnable) {
                currentCBs(cursor) = r
                cursor += 1
                if (cursor == BATCH_SIZE)
                    cycle()
            }
        }
}
