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

import org.jgroups._
import org.jgroups.blocks.cs.{Receiver => ClientReceiver, Connection, ConnectionListener, TcpServer}
import org.jgroups.util.Util
import org.midonet.cluster.storage.JGroupsConfig
import org.slf4j.LoggerFactory

import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.control.NonFatal

object JGroupsBroker {

    private val defaultCharset = Charset.forName("UTF-8")

    @throws[IllegalArgumentException]
    def toPubSubMessage(bytes: Array[Byte]): PubSubMessage = {
        val msg = new String(bytes, defaultCharset)
        if (msg.contains("/")) {
            val tokens = msg.split("/")
            if (tokens.length == 2) {
                tokens(0) match {
                    case "subscribe" => SubscribeMessage(tokens(1))
                    case "unsubscribe" => UnsubscribeMessage(tokens(1))
                    case "heartbeat" => HeartBeat(tokens(1).toLong)
                    case _ =>
                        throw new IllegalArgumentException(
                            "Unable to deserialize message: " + msg)
                }
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
    case class HeartBeat(ts: Long) extends PubSubMessage {
        override def toByteArray(): Array[Byte] = {
            ("heartbeat/" + ts).getBytes(defaultCharset)
        }
    }

    case class PublishMessage(topic: String, key: String, payload: String) extends PubSubMessage {

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

class JGroupsBroker(port: Int) extends ReceiverAdapter with ClientReceiver
                                       with ConnectionListener
                                       with HeartBeatEnabled {

    import JGroupsBroker._

    type MessageLog = mutable.Map[String, mutable.Map[String, Array[Byte]]]

    private val log = LoggerFactory.getLogger("org.midonet.cluster.jgroups.broker")
    /* Map of topics to set of clients */
    private val subscriptions = new TrieMap[String, TrieMap[Address, AnyRef]]
    /* Map of clients to set of topics */
    private val clients = new TrieMap[Address, TrieMap[String, AnyRef]]
    private var channel: JChannel = _
    private var server: TcpServer = _
    @volatile private var state: State = _

    private var jgroupsZkClient: JGroupsZkClient = _
    override protected var jgroupsConf: JGroupsConfig = _

    init()

    private def init(): Unit = {
        jgroupsZkClient = new JGroupsZkClient()
        jgroupsConf = jgroupsZkClient.jgroupsConf

        state = new State()

        channel = new JChannel("jgroups-tcp.xml")
        channel.connect(jgroupsZkClient.clusterName)
        channel.setReceiver(this)
        channel.getState(null /*target*/, 0 /* wait until state received */)

        val addr = InetAddress.getLocalHost
        server = new TcpServer(addr, port)
        server.receiver(this)
        server.addConnectionListener(this)
        server.start()

        if (jgroupsZkClient.writeBrokerToZK(addr, port)) {
            log.debug("Wrote broker ip:port: {}:{} to ZooKeeper",
                      addr.getHostName, port)
        } else {
            log.warn("Unable to write broker IP:port to ZooKeeper")
        }
    }

    /** Callback methods for JGroups **/
    override def viewAccepted(newView: View): Unit = {
        log.debug("Installed view: " + newView)
    }

    override def receive(msg: Message): Unit = {
        JGroupsBroker.toPubSubMessage(msg.getBuffer) match {
            case PublishMessage(topic, key, payload) =>
                state.addMessage(topic, key, msg.getBuffer)
                log.debug("Delivered msg with key: {} and payload: {} for " +
                          "topic: {}", Array(key, payload, topic):_*)

                /* Send message to topic subscribers. */
                for (client <- subscriptions.getOrElse(topic, Map.empty).keySet) {
                    server.send(client, ByteBuffer.wrap(msg.getBuffer))
                }

            case msg => log.warn("Unexpected delivered message {}", msg)
        }
    }

    //TODO
    @throws(classOf[Exception])
    override def getState(output: OutputStream): Unit = {
        val start = System.nanoTime()
        log.debug("Saving state")
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
        for (topic <- messages.keySet) {
            log.debug("State transfer: topic: {} messages: {}",
                      Array(topic, messages(topic).toSeq):_*)
        }
    }
    /** End of callback methods for JGroups **/

    /**
     * Removes the client from the list of subscribers to the topic and
     * returns true if the client was previously subscribed to the topic.
     */
    def removeSubscriber(client: Address, topic: String): Boolean =
        subscriptions.get(topic) match {
            case Some(subscribers) =>
                clients(client).remove(topic)
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

        if (clients.get(client).isEmpty)
            clients.put(client, new TrieMap[String, AnyRef]())

        clients(client).put(topic, null)
        subscriptions(topic).putIfAbsent(client, null).isEmpty
    }

    private class State(messages: MessageLog = mutable.HashMap.empty) {
        def addMessage(topic: String, key: String, bytes: Array[Byte])
        : Unit = {
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
                                      Array(JGroupsBroker.toPubSubMessage(msg),
                                            sender.toString):_*)
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

                case HeartBeat(ts) =>
                    log.debug("Received heartbeat with ts: {} from client: {}",
                              ts, sender)
                    receivedHB(ts, sender)
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

    /**
     * Called to indicate that the client with the given addressed is suspected
     * to have crashed.
     */
    override def notifyFailed(address: Address): Unit = {
        log.info("Detected crash of client: {}", address)

        for ((topic, _) <- clients(address)) {
            subscriptions(topic).remove(address)
        }
        clients.remove(address)
    }

    override def sendHearbeat(address: Address): Unit = {
        log.debug("Sending heartbeat to client: {}", address.toString)

        val heartbeat = HeartBeat(System.currentTimeMillis()).toByteArray()
        server.send(address, ByteBuffer.wrap(heartbeat))
    }

    override def connectionClosed(conn: Connection, reason: String): Unit = {
        // Do nothing, a disconnection of a client is handled by method
        // notifyFailed.
    }

    override def connectionEstablished(conn: Connection): Unit =
        schedulePeriodicHeartbeat(conn.peerAddress)
}
