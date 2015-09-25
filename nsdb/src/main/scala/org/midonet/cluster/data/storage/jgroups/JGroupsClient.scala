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
import org.jgroups.blocks.cs.{Connection, ConnectionListener, ReceiverAdapter, TcpClient}
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.jgroups.JGroupsBroker.{UnsubscribeMessage, SubscribeMessage, PublishMessage}

object JGroupsClient {
    def main(args: Array[String]): Unit = {
        val port = args(0).toInt
        new JGroupsClient(port)
    }
}

/**
 * A publish-subscribe client that relies on JGroups. This class is not
 * thread-safe.
 */
class JGroupsClient(port: Int) extends ReceiverAdapter with ConnectionListener {

    private val log = LoggerFactory.getLogger("org.midonet.cluster.JGroups.client")

    private val subscriptions = new mutable.HashSet[String]()
    private val client = new TcpClient(InetAddress.getLocalHost, 0,
                                       InetAddress.getLocalHost, port)
    client.receiver(this)
    client.addConnectionListener(this)
    client.start()

    subscribe("test-topic")
    publish("test-topic", "1", "titi" + Random.nextInt(100))
    publish("test-topic", "2", "tita" + Random.nextInt(100))
    publish("test-topic", "3", "tito" + Random.nextInt(100))

    /**
     * Called when receiving a message from a JGroups node.
     */
    override def receive(sender: Address, buf: ByteBuffer): Unit = {
        try {
            val msg = JGroupsBroker.toPubSubMessage(buf.array())
            log.debug("Received msg: " + msg)
        } catch {
            case NonFatal(e) => log.warn("Unable to receive message from client")
        }
    }

    /**
     * Called when receiving a message from a JGroups node.
     */
    override def receive(sender: Address, buf: Array[Byte], offset: Int,
                         length: Int): Unit = {
        try {
            val newArray = util.Arrays.copyOfRange(buf, offset, length)
            val msg = JGroupsBroker.toPubSubMessage(newArray)
            log.debug("Received msg: {}", msg)
        } catch {
            case NonFatal(e) => log.warn("Unable to receive message from client")
        }
    }

    override def connectionClosed(conn: Connection, reason: String): Unit = {
        log.info("Connection lost to server: {}, cause: {}", Array(conn.peerAddress(),
                 reason):_*)
        client.stop()

        // TODO: reconnect to new server and subscribe to all needed topics.
    }

    override def connectionEstablished(conn: Connection): Unit = {
        log.debug("Connection to server {} opened", conn.peerAddress())
    }

    // TODO: retry if unsuccessful with another server
    def subscribe(topic: String): Unit = {
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
        try {
            client.send(ByteBuffer.wrap(UnsubscribeMessage(topic).toByteArray()))
            subscriptions -= topic
        } catch {
            case NonFatal(e) => log.warn("Unable to unsubscribe from topic: " +
                                         topic, e)
        }
    }

    // TODO: retry if unsuccessful with another server
    def publish(topic: String, key: String, payload: String): Unit = {
        var msg: PublishMessage = null
        try {
            msg = PublishMessage(topic, key, payload)
            client.send(ByteBuffer.wrap(msg.toByteArray()))
        } catch {
            case NonFatal(e) => log.warn("Unable to publish msg: " + msg, e)
        }
    }
}
