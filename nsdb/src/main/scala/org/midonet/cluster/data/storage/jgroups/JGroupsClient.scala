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

import org.jgroups.Address
import org.jgroups.blocks.cs.ConnectionListener
import org.jgroups.blocks.cs.{Connection, ReceiverAdapter, TcpClient}
import org.midonet.cluster.data.storage.jgroups.JGroupsBroker.{HeartBeat, PublishMessage, SubscribeMessage, UnsubscribeMessage}
import org.midonet.cluster.storage.JGroupsConfig
import org.slf4j.LoggerFactory

import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal

object JGroupsClient {
    def main(args: Array[String]): Unit = {
        new JGroupsClient()
    }
}

/**
 * A publish-subscribe client that relies on JGroups. This class is not
 * thread-safe.
 */
class JGroupsClient extends ReceiverAdapter
                            with ConnectionListener
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

    subscribe("test-topic")
    publish("test-topic", "1", "titi" + Random.nextInt(100))
    publish("test-topic", "2", "tita" + Random.nextInt(100))
    publish("test-topic", "3", "tito" + Random.nextInt(100))

    /**
      * Connects to a randomly selected broker and returns the [[TcpClient]].
      * Note that the list of alive brokers is obtained from ZooKeeper.
      */
    private def connectToBroker(): TcpClient = {
        val broker = jgroupsZkClient.randomBroker
        log.info("Connecting to broker server: {}", broker)

        val tcpClient = new TcpClient(InetAddress.getLocalHost, 0, broker.addr,
                                      broker.port)
        tcpClient.receiver(this)
        tcpClient.addConnectionListener(this)
        tcpClient.start()
        tcpClient
    }

    /**
     * Called when receiving a message from a JGroups node.
     */
    override def receive(sender: Address, buf: ByteBuffer): Unit = {
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
    }

    /**
     * Called when receiving a message from a JGroups node.
     */
    override def receive(sender: Address, buf: Array[Byte], offset: Int,
                         length: Int): Unit = {
        val msg = util.Arrays.copyOfRange(buf, offset, length)
        receive(sender, ByteBuffer.wrap(msg))
    }

    override def connectionClosed(conn: Connection, reason: String): Unit = {
        // Do nothing, a disconnection from a broker is handled via the
        // notifyFailed method.
    }

    override def connectionEstablished(conn: Connection): Unit = {
        log.debug("Connection to server {} opened", conn.peerAddress())
        schedulePeriodicHeartbeat(conn.peerAddress)
    }

    // TODO: retry if unsuccessful with another server
    def subscribe(topic: String): Unit = {
        log.info("Subscribing to topic: {} using broker: {}", Array(topic,
                 client.remoteAddress()):_*)
        try {
            client.send(ByteBuffer.wrap(SubscribeMessage(topic).toByteArray()))
            subscriptions += topic
        } catch {
            case NonFatal(e) => log.warn("Unable to subscribe to topic: " +
                                         topic, e)
        }
    }

    // TODO: retry if unsuccessful with another server
    def unsubscribe(topic: String): Unit = {
        log.info("Unsubscribing from topic: {} using broker: {}", Array(topic,
                 client.remoteAddress()):_*)
        try {
            client.send(ByteBuffer.wrap(UnsubscribeMessage(topic).toByteArray()))
            subscriptions -= topic
        } catch {
            case NonFatal(e) => log.warn("Unable to unsubscribe from topic: " +
                                         topic, e)
        }
    }

    def publish(topic: String, key: String, payload: String): Unit = {
        var msg: PublishMessage = null
        log.debug("Publishing message with key: {} and payload: {} on " +
                  "topic: {} using broker: {}", key, payload, topic,
                  client.remoteAddress)
        try {
            msg = PublishMessage(topic, key, payload)
            val msgInBytes = msg.toByteArray()
            client.send(ByteBuffer.wrap(msgInBytes))
            msgsToAck.put(key, msgInBytes)
        } catch {
            case NonFatal(e) => log.warn("Unable to publish msg: " + msg, e)
        }
    }

    private def publish(key: String, msg: Array[Byte]): Unit = {
        try {
            client.send(ByteBuffer.wrap(msg))
            msgsToAck.put(key, msg)
        } catch {
            case NonFatal(e) => log.warn("Unable to publish msg: " + msg, e)
        }
    }

    // TODO: It could be that the ephemeral node is not deleted yet when we try
    //       to reconnect. Make sure we discard that broker.
    //       The other solution is to use ZK for failure detection.
    override def notifyFailed(address: Address): Unit = {
        log.info("Detected the crash of broker: {}", address)
        client.stop()

        client = connectToBroker()

        for (topic <- subscriptions) {
            subscribe(topic)
        }

        for ((key, msg) <- msgsToAck) {
            publish(key, msg)
        }
    }

    override def sendHearbeat(address: Address): Unit = {
        log.debug("Sending heartbeat to broker: {}", address.toString)

        val heartbeat = HeartBeat(System.currentTimeMillis()).toByteArray()
        client.send(address, ByteBuffer.wrap(heartbeat))
    }
}
