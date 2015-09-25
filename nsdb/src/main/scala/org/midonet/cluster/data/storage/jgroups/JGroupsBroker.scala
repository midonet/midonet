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

import java.io._
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.util

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.control.NonFatal

import com.typesafe.config.Config
import org.apache.curator.RetryPolicy
import org.apache.curator.framework.{CuratorFrameworkFactory, CuratorFramework}
import org.apache.curator.retry.ExponentialBackoffRetry
import org.jgroups._
import org.jgroups.blocks.cs.{Receiver => ClientReceiver, TcpServer}
import org.jgroups.util.Util
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.jgroups.JGroupsBroker.{PublishMessage, SubscribeMessage, UnsubscribeMessage}
import org.midonet.cluster.storage.MidonetBackendConfig
import org.midonet.conf.MidoNodeConfigurator

object JGroupsBroker {

    private val defaultCharset = Charset.forName("UTF-8")

    @throws[IllegalArgumentException]
    def toPubSubMessage(bytes: Array[Byte]): PubSubMessage = {
        val msg = new String(bytes, defaultCharset)
        if (msg.contains("/")) {
            val tokens = msg.split("/")
            if (tokens.length == 2) {
                if (tokens(0) == "subscribe")
                    SubscribeMessage(tokens(1))
                else if (tokens(1) == "unsubscribe")
                    UnsubscribeMessage(tokens(1))
                else
                    throw new IllegalArgumentException(
                        "Unable to deserialize message: " + msg)
            } else {
                throw new IllegalArgumentException(
                    "Unable to deserialize message: " + msg)
            }
        } else {
            val tokens = msg.split("#")
            if (tokens.length == 3) {
                PublishMessage(tokens(0), tokens(1), tokens(2))
            } else {
                throw new IllegalArgumentException(
                    "Unable to deserialize message: " + msg)
            }
        }
    }

    abstract class PubSubMessage {
        def toByteArray(): Array[Byte]
    }

    case class SubscribeMessage(topic: String) extends PubSubMessage {
        override def toByteArray(): Array[Byte] = {
            ("subscribe/" + topic).getBytes(defaultCharset)
        }
    }
    case class UnsubscribeMessage(topic: String) extends PubSubMessage {
        override def toByteArray(): Array[Byte] = {
            ("unsubscribe/" + topic).getBytes(defaultCharset)
        }
    }
    case class PublishMessage(topic: String, key: String, payload: String)
        extends PubSubMessage {

        override def toByteArray(): Array[Byte] = {
            val msg = topic + "#" + key + "#" + payload
            msg.getBytes(defaultCharset)
        }
    }

    def main(args: Array[String]):Unit = {
        val serverPort = args(0).toInt
        new JGroupsBroker(serverPort)
    }
}

class JGroupsBroker(port: Int) extends ReceiverAdapter with ClientReceiver {

    import JGroupsBroker._

    type MessageLog = mutable.Map[String, mutable.Map[String, Array[Byte]]]

    private val log = LoggerFactory.getLogger("org.midonet.cluster.jgroups.broker")
    private val subscriptions = new TrieMap[String, TrieMap[Address, AnyRef]]

    private var channel: JChannel = _
    private var server: TcpServer = _
    @volatile private var state: State = _

    init()

    private def init(): Unit = {
        state = new State()

        // TODO: make this easily configurable.
        channel = new JChannel("jgroups-tcp.xml")
        channel.connect("test_cluster")
        channel.setReceiver(this)
        channel.getState(null /*target*/, 0 /*wait until state received*/)

        val addr = InetAddress.getLocalHost
        server = new TcpServer(addr, port)
        server.receiver(this)
        server.start()

        /* Write server address to ZooKeeper. */
        if (Utils.writeBrokerToZK(addr, port)) {
            log.info("Wrote server: {}:{} to ZooKeeper", addr.getHostName, port)
        } else {
            log.warn("Unable to write server IP:port to ZooKeeper")
        }
    }

    /** Callback methods for the cluster node **/
    override def viewAccepted(newView: View): Unit = {
        log.debug("Installed view: " + newView)
    }

    override def receive(msg: Message): Unit = {
        JGroupsBroker.toPubSubMessage(msg.getBuffer) match {
            case PublishMessage(topic, key, payload) =>
                state.addMessage(topic, key, msg.getBuffer)
                log.debug("Deliver msg: {} for topic: {}", Array(payload, topic):_*)

                /* Send message to topic subscribers. */
                for (client <- subscriptions.getOrElse(topic, Map.empty).keySet) {
                    server.send(client, ByteBuffer.wrap(msg.getBuffer))
                }

            case msg => log.warn("Unexpected message {}", msg)
        }
    }

    //TODO
    @throws(classOf[Exception])
    override def getState(output: OutputStream): Unit = {
        val start = System.nanoTime()
        log.debug("Get state called")
        state.saveState(output)
        val end = System.nanoTime()
        log.debug("State saved in {} ms", (end-start)/1000000)
    }

    //TODO
    @throws(classOf[Exception])
    override def setState(input: InputStream): Unit = {
        val messages = Util.objectFromStream(new DataInputStream(input))
                           .asInstanceOf[MessageLog]
        state = new State(messages)
        log.debug("Set state called, message topics: {}", messages.keySet)
    }
    /** End of callback methods for the cluster node **/

    /**
     * Removes the client from the list of subscribers to the topic and
     * returns true if the client was previously subscribed to the topic.
     */
    def removeSubscriber(client: Address, topic: String): Boolean =
        subscriptions.get(topic) match {
            case Some(subscribers) =>
                subscribers.remove(client).nonEmpty
            case None => false
        }

    /**
     * Adds the client to the list of subscribers to the topic and returns
     * true if the client had not subscribed to this topic previously.
     */
    def addSubscriber(client: Address, topic: String): Boolean = {
        if (subscriptions.get(topic).isEmpty)
            subscriptions.put(topic, new TrieMap[Address, AnyRef])

        subscriptions(topic).putIfAbsent(client, null).isEmpty
    }

    private class State(messages: MessageLog = mutable.HashMap.empty) {
        def addMessage(topic: String, key: String, bytes: Array[Byte]): Unit = {
            this.synchronized {
               if (messages.get(topic).isEmpty)
                   messages += topic -> new mutable.HashMap[String, Array[Byte]]

               messages(topic) += key -> bytes
            }
        }

        def topicMessages(topic: String): Iterable[Array[Byte]] = {
            this.synchronized {
                messages.getOrElse(topic, Map.empty).values
            }
        }

        def saveState(output: OutputStream): Unit = {
            this.synchronized {
                Util.objectToStream(messages, new DataOutputStream(output))
            }
        }
    }

    private def handleClientMessage(sender: Address, buf: Array[Byte]): Unit = {
        try {
            JGroupsBroker.toPubSubMessage(buf) match {
                case SubscribeMessage(topic) =>
                    if (addSubscriber(sender, topic)) {
                        log.debug("Subscribe from client: {} topic: {}",
                                  Array(sender, topic):_*)

                        /* Send the topic messages when 1st subscribing to a
                           topic. */
                        state.topicMessages(topic).foreach(msg => {
                            log.debug("Sending msg: {} to client: {}",
                                      Array(JGroupsBroker.toPubSubMessage(msg), sender):_*)
                            server.send(sender, ByteBuffer.wrap(msg))
                        })

                    } else {
                        log.debug("Client: {} is already subscribed to topic: {}",
                                  Array(sender, topic):_*)
                    }

                case UnsubscribeMessage(topic) =>
                    log.debug("Unsubscribe from client: {} topic: {}",
                              Array(sender, topic):_*)
                    removeSubscriber(sender, topic)

                case PublishMessage(topic, key, payload) =>
                    log.debug("Publish message: {} from client: {}",
                              Array(payload, sender):_*)

                    /* Broadcast publish message to group. */
                    channel.send(new Message(null /*send to all*/,
                                             channel.getAddress, buf))
            }

        } catch {
            case NonFatal(e) => log.warn("Unable to handle message from client", e)
        }
    }

    /**
     * Called when receiving a message from a client.
     */
    override def receive(sender: Address, buf: ByteBuffer): Unit =
        handleClientMessage(sender, buf.array())

    /**
     * Called when receiving a message from a client.
     */
    override def receive(sender: Address, buf: Array[Byte], offset: Int,
                         length: Int): Unit = {
        val newBuf = util.Arrays.copyOfRange(buf, offset, length)
        handleClientMessage(sender, newBuf)
    }
}
