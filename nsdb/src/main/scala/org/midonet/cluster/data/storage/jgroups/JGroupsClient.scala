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

package org.midonet.cluster.data.storage.jgroups

import java.net.InetAddress
import java.nio.ByteBuffer
import java.util

import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal

import org.jgroups.Address
import org.jgroups.blocks.cs.{ReceiverAdapter, TcpClient}
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.jgroups.JGroupsBroker.{HeartBeat, PublishMessage, SubscribeMessage, UnsubscribeMessage}
import org.midonet.cluster.storage.JGroupsConfig
import org.midonet.util.functors.makeRunnable

object JGroupsClient {
    def main(args: Array[String]): Unit = {
        new JGroupsClient()
    }
}

/**
 * A publish-subscribe client that relies on JGroups. If the broker this client
  * is connected to fails, the client will re-connect to another broker and
  * re-submit any pending subscriptions and messages. Note that messages
  * previously received may be received again after connecting to the new broker.
  *
  * Methods of this class are scheduled on the scheduler executor of the
  * [[HeartBeatEnabled]] trait to avoid the need for synchronization.
 */
class JGroupsClient extends ReceiverAdapter
                            with HeartBeatEnabled {

    private val log = LoggerFactory.getLogger("org.midonet.cluster.JGroups.client")
    private val jgroupsZkClient = new JGroupsZkClient()
    override protected var jgroupsConf: JGroupsConfig = jgroupsZkClient.jgroupsConf

    private val subscriptions = new mutable.HashSet[String]()

    /* Map of messages to be acknowledged by a broker. This is a mapping
       from message key to the messag in byte array format. */
    private val msgsToAck = new mutable.HashMap[String, Array[Byte]]()

    private var client = connectToBroker()

    private val thread = new Thread {
        override def run(): Unit = Thread.sleep(10000000l)
    }
    thread.setDaemon(false)
    thread.start()

    val topic = "test-topic" + Random.nextInt
    subscribe(topic)
    publish(topic, "1", "titi" + Random.nextInt(100))
    publish(topic, "2", "tita" + Random.nextInt(100))
    publish(topic, "2", "tito" + Random.nextInt(100))

    /**
      * Connects to a randomly selected broker and returns the [[TcpClient]].
      * Note that the list of alive brokers is obtained from ZooKeeper.
      */
    private def connectToBroker(brokerToDiscard: Option[Address] = None)
    : TcpClient = {
        val broker = jgroupsZkClient.randomBroker(brokerToDiscard)
        log.info("Connecting to broker server: {}", broker)

        val tcpClient = new TcpClient(InetAddress.getLocalHost, 0,
                                      broker.getIpAddress,
                                      broker.getPort)
        tcpClient.receiver(this)
        tcpClient.start()

        schedulePeriodicHeartbeat(tcpClient.remoteAddress)
        tcpClient
    }

    /**
     * Called when receiving a message from a JGroups node.
     */
    override def receive(sender: Address, buf: ByteBuffer): Unit =
        scheduler.submit(makeRunnable {

        try {
            JGroupsBroker.toPubSubMessage(buf.array()) match {
                case PublishMessage(topic, key, payload) =>
                    if (msgsToAck.remove(key).isEmpty) {
                        log.debug("Received msg for topic: {} with key: {} " +
                                  "and payload: {} from broker: {}",
                                  Array(topic, key, payload, sender.toString):_*)
                    } else {
                        log.debug("Received acknowledgment for message with " +
                                  "topic: {}, key: {}, and payload: {} from " +
                                  "broker: {}",
                                  Array(topic, key, payload, sender.toString):_*)
                    }
                case HeartBeat(ts) =>
                    log.debug("Received heartbeat with ts: {} from broker: {}",
                              ts, sender)
                    receivedHB(ts, sender)

                case msg =>
                    log.warn("Received unexpected message: {} from broker: {}",
                             Array(msg, sender.toString):_*)
            }

        } catch {
            case NonFatal(e) => log.warn("Unable to receive message from broker")
        }
        }).get

    /**
     * Called when receiving a message from a JGroups node.
     */
    override def receive(sender: Address, buf: Array[Byte], offset: Int,
                         length: Int): Unit = {
        val buffer = ByteBuffer.wrap(buf, offset, length)
        receive(sender, buffer)
    }

    def subscribe(topic: String): Unit = scheduler.submit(makeRunnable {
        log.info("Subscribing to topic: {} using broker: {}", Array(topic,
                 client.remoteAddress()):_*)
        try {
            subscriptions += topic
            client.send(ByteBuffer.wrap(SubscribeMessage(topic).toByteArray()))
        } catch {
            case NonFatal(e) => log.warn("Unable to subscribe to topic: " +
                                         topic, e)
        }
    })

    def unsubscribe(topic: String): Unit = scheduler.submit(makeRunnable {
        log.info("Unsubscribing from topic: {} using broker: {}", Array(topic,
                 client.remoteAddress()):_*)
        try {
            subscriptions -= topic
            client.send(ByteBuffer.wrap(UnsubscribeMessage(topic).toByteArray()))
        } catch {
            case NonFatal(e) => log.warn("Unable to unsubscribe from topic: " +
                                         topic, e)
        }
    })

    def publish(topic: String, key: String, payload: String): Unit =
        scheduler.submit(makeRunnable {
            var msg: PublishMessage = null
            log.debug("Publishing message with key: {} and payload: {} on " +
                      "topic: {} using broker: {}", key, payload, topic,
                      client.remoteAddress)
            try {
                msg = PublishMessage(topic, key, payload)
                val msgInBytes = msg.toByteArray()
                msgsToAck.put(key, msgInBytes)
                client.send(ByteBuffer.wrap(msgInBytes))
            } catch {
                case NonFatal(e) => log.warn("Unable to publish msg: " + msg, e)
            }
        })

    /* The methods below are called from the scheduler thread. */
    private def publish(key: String, msg: Array[Byte]): Unit = {
        try {
            client.send(ByteBuffer.wrap(msg))
            msgsToAck.put(key, msg)
        } catch {
            case NonFatal(e) => log.warn("Unable to publish msg: " + msg, e)
        }
    }

    override def notifyFailed(address: Address): Unit = {
        log.info("Detected the crash of broker: {}", address)
        client.stop()

        /* We discard the broker we just suspected of having crashed because
           it could be that this broker's node is still present in ZooKeeper.
           We do not however rely on ZooKeeper nodes to perform failure detection
           because the broker must also detect client failures and we chose
           to not add a ZooKeeper node for each client. */
        client = connectToBroker(brokerToDiscard = Some(address))

        for (topic <- subscriptions) { subscribe(topic) }

        for ((key, msg) <- msgsToAck) { publish(key, msg) }
    }

    override def sendHeartbeat(address: Address): Unit = {
        log.debug("Sending heartbeat to broker: {}", address.toString)

        val heartbeat = HeartBeat(System.currentTimeMillis()).toByteArray()
        client.send(address, ByteBuffer.wrap(heartbeat))
    }
}
