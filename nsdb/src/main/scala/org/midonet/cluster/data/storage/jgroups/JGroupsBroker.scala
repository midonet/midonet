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
import java.util.concurrent.TimeUnit

import io.netty.buffer.ByteBuf
import io.netty.channel.Channel
import io.netty.channel.epoll.{EpollServerSocketChannel, EpollEventLoopGroup}
import io.netty.handler.codec.serialization.{ClassResolvers, ObjectDecoder, ObjectEncoder}
import io.netty.{bootstrap, channel}
import io.netty.channel.socket.SocketChannel
import io.netty.channel._
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.NioServerSocketChannel
import io.netty.bootstrap.ServerBootstrap
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import org.jgroups.jmx.JmxConfigurator
import org.jgroups.protocols.TCP
import org.midonet.cluster.data.storage.jgroups.JGroupsBroker.PubSubMessage

import scala.collection.mutable
import scala.util.control.NonFatal

import org.jgroups.{JChannel, ReceiverAdapter, Message, View}
import org.jgroups.blocks.cs.{Receiver => ClientReceiver, NioServer, Connection, ConnectionListener, TcpServer}
import org.jgroups.util.Util
import org.midonet.cluster.storage.JGroupsConfig
import org.slf4j.LoggerFactory

import org.midonet.util.functors.makeRunnable

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
                    case "latency" => LatencyMeasurementMessage(tokens(1).toLong)
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

            if (tokens.length == 5) {
                PublishMessage(tokens(0), tokens(1), tokens(2), tokens(3).toLong, tokens(4))
            } else {
                throw new IllegalArgumentException(
                    "Unable to deserialize message: " + msg)
            }
        }
    }

    abstract class PubSubMessage {
        def toByteArray(): Array[Byte]
    }
    case class LatencyMeasurementMessage(timestamp: Long) extends PubSubMessage{
        override def toByteArray(): Array[Byte] = {
            ("latency/" + timestamp).getBytes(defaultCharset)
        }
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

    case class PublishMessage(topic: String,
                              key: String,
                              owner: String,
                              stamp: Long,
                              payload: String)
        extends PubSubMessage {

        override def toByteArray(): Array[Byte] = {
            val msg = topic +
                "#" + key +
                "#" + owner +
                "#" + stamp.toString() +
                "#" + payload
            msg.getBytes(defaultCharset)
        }
    }

    /* (Owner, Key) -> (Stamp, Message) */
    type TopicLog = mutable.Map[(String, String), (Long, PublishMessage)]
    type MessageLog = mutable.Map[String, TopicLog]

    private class State(messages: MessageLog = mutable.HashMap.empty) {

        /**
          * Adds the given message to the set of messages of the given topic and
          * returns true if the message was not previously added. False is returned
          * otherwise.
          */
        def addMessage(topic: String, key: String, owner: String, stamp: Long, msg: PublishMessage)
        : Boolean = {
            this.synchronized {
                if (messages.get(topic).isEmpty) {
                    val map: TopicLog = new mutable.HashMap[(String, String), (Long, PublishMessage)]
                    messages += topic -> map
                }

                val topicLog = messages(topic)
                if (!topicLog.contains( (owner, key) )) {
                    topicLog += (owner, key) -> (stamp, msg)
                    true
                } else {
                    if (topicLog( (owner, key) )._1 < stamp) {
                        topicLog += (owner, key) -> (stamp, msg)
                        true
                    } else {
                        false
                    }
                }
            }
        }

        def addMessage(msg: PublishMessage)
        : Boolean = addMessage(msg.topic, msg.key, msg.owner, msg.stamp, msg)

        def containsMessage(topic: String, key: String, owner: String, stamp: Long): Boolean = {
            this.synchronized {
                messages.get(topic).nonEmpty &&
                    messages(topic).contains( (key, owner) ) &&
                    messages(topic)( (key, owner) )._1 >= stamp
            }
        }

        def topicMessages(topic: String): Iterable[PublishMessage] = {
            this.synchronized {
                messages.getOrElse(topic, Map.empty).values.map(_._2)
            }
        }

        def saveState(output: OutputStream): Unit = {
            this.synchronized {
                Util.objectToStream(messages, new DataOutputStream(output))
            }
        }
    }

    /**
     * Main method for debugging the JGroupsBroker, can be removed
     * @param args
     */
    def main(args: Array[String]):Unit = {
        val serverPort = args(0).toInt
        val serverHost = if (args.length > 1) args(1) else ""
        new JGroupsBroker(serverPort, serverHost)
    }
}



/**
  * A broker part of a publish-subscribe system. The broker functionnality
  * is replicated to mask failures. Messages published to a specific broker
  * is reliably broadcast to the other broker replicas using JGroups.
  *
  * Methods of this class are scheduled on the executor of the
  * [[HeartBeatEnabled]] trait to avoid the need for synchronization.
  */
class JGroupsBroker(port: Int, host: String = "") extends ReceiverAdapter
                                        with HeartBeatEnabled
                                        with PubSubMessageReceiver {

    import JGroupsBroker._

    private val log = LoggerFactory.getLogger("org.midonet.cluster.jgroups.broker")
    /* Map of topics to set of clients */
    private val subscriptions = new mutable.HashMap[String, mutable.HashMap[String, Channel]]
    /* Map of clients to set of topics */
    private val clients = new mutable.HashMap[String, mutable.HashMap[String, AnyRef]]
    private var channel: JChannel = _
    //private var server: NioServer = _

    @volatile private var state: State = _

    private var jgroupsZkClient: JGroupsZkClient = _
    override protected var jgroupsConf: JGroupsConfig = _

    private var messagesIn: Long = 0
    private var messagesOut: Long = 0

    init()

    private def initServer(address: InetAddress, port: Int): Unit = {
        //TODO:Shutdown event loops
        val bossGroup: EventLoopGroup = new NioEventLoopGroup(1)
        val workerGroup: EventLoopGroup = new NioEventLoopGroup()

        val bootstrap: ServerBootstrap = new ServerBootstrap()
        bootstrap.group(bossGroup, workerGroup)
            .channel(classOf[NioServerSocketChannel])
            .handler(new LoggingHandler(LogLevel.INFO))
            .childHandler(new PubSubChannelInitializer(this));

        bootstrap.bind(address, port).sync()

    }

    private def init(): Unit = {
        jgroupsZkClient = new JGroupsZkClient()
        jgroupsConf = JGroupsZkClient.jgroupsConf

        state = new State()

        channel = new JChannel("jgroups-tcp.xml")
        channel.connect(jgroupsZkClient.clusterName)
        channel.setReceiver(this)
        channel.getState(null /*target*/, 0 /* wait until state received */)

        //JmxConfigurator.registerChannel(channel, Util.getMBeanServer(),
        //    "broker", channel.getClusterName, true)

        val addr = if (host.isEmpty) InetAddress.getLocalHost else InetAddress.getByName(host)
        initServer(addr, port)

        //Periodically measure the group communication latency
        scheduler.scheduleAtFixedRate(new Runnable {
            override def run(): Unit = {
                val buf = LatencyMeasurementMessage(System.currentTimeMillis()).toByteArray()
                channel.send(new Message(null /*send to all*/,
                    channel.getAddress, buf))
            }
        }, 20, 15, TimeUnit.SECONDS)

        //Periodically measure the scheduler queue waiting time
        scheduler.scheduleAtFixedRate(new Runnable {
            override def run(): Unit = {
                val scheduledAt = System.currentTimeMillis()
                scheduler.submit(makeRunnable({
                    log.info("[QUEUE] {} ms | queue: {}", System.currentTimeMillis() - scheduledAt, scheduler.getQueue.size())
                    log.info("[MSGS] {} in | {} out", messagesIn, messagesOut)
                }))
            }
        }, 25, 15, TimeUnit.SECONDS)

        if (jgroupsZkClient.writeBrokerToZK(addr, port)) {
            log.info("Wrote broker ip:port: {}:{} to ZooKeeper",
                      addr.getHostName, port)
            //log.debug("Receive buffer size {}", server.receiveBufferSize())
            //log.debug("Send buffer size {}", server.sendBufferSize())
            //log.debug("Send queue size {}", server.sendQueueSize())
        } else {
            log.warn("Unable to write broker IP:port to ZooKeeper")
        }
    }

    /** Callback methods for JGroups **/
    override def viewAccepted(newView: View): Unit = {
        log.debug("Installed view: " + newView)
    }

    /**
      * Method called by the JGroups system when a message is ready to be
      * delivered.
      */
    override def receive(msg: Message): Unit = {
        val message = JGroupsBroker.toPubSubMessage(msg.getBuffer)
        message match {
            case pm: PublishMessage =>

                scheduler.submit(makeRunnable {
                    if (state.addMessage(pm)) {
                        log.debug("Delivered msg with key: {} and payload: {} " +
                            "for topic: {}, owner: {}, stamp: {}",
                            Array(pm.key, pm.payload, pm.topic, pm.owner, pm.stamp.toString): _*)

                        /* Send message to topic subscribers. */
                        for ((client, channel) <-
                             subscriptions.getOrElse(pm.topic, Map.empty)) {
                            channel.writeAndFlush(pm, channel.voidPromise())
                            messagesOut += 1
                        }
                    } else {
                        log.debug("Ignoring duplicate delivered message with " +
                            "key: {} and payload: {} for " +
                            "topic: {}, owner: {}, stamp: {}",
                            Array(pm.key, pm.payload, pm.topic, pm.owner, pm.stamp.toString): _*)
                    }
                })

            case LatencyMeasurementMessage(timestamp) =>
                if (msg.getSrc().equals(channel.getAddress)) {
                    log.info("[LATENCY] OWN: {} ms",
                        System.currentTimeMillis() - timestamp)
                } else {
                    log.info("[LATENCY] {}: {} ms", msg.getSrc().toString(), System.currentTimeMillis() - timestamp)
                }

            case msg => log.warn("Unexpected delivered message {}", msg)
        }
    }

    @throws(classOf[Exception])
    override def getState(output: OutputStream): Unit =
        scheduler.submit(makeRunnable {
            val start = System.nanoTime()
            log.debug("Saving state")
            state.saveState(output)
            val end = System.nanoTime()
            log.debug("State saved in {} ms", (end-start)/1000000)
        }).get

    @throws(classOf[Exception])
    override def setState(input: InputStream): Unit =
        scheduler.submit(makeRunnable {
            val messages = Util.objectFromStream(new DataInputStream(input))
                               .asInstanceOf[MessageLog]
            state = new State(messages)
            for (topic <- messages.keySet) {
                log.debug("State transfer: topic: {} messages: {}",
                          Array(topic, messages(topic).toSeq):_*)
            }
        })
    /** End of callback methods for JGroups **/

    /**
     * Removes the client from the list of subscribers to the topic and
     * returns true if the client was previously subscribed to the topic.
     */
    private def removeSubscriber(topic: String, channel: Channel): Boolean = {
        val id  = PubSubClientServerCommunication.clientIdentifier(channel)
        subscriptions.get(topic) match {
            case Some(subscribers) =>
                clients(id).remove(topic)
                subscribers.remove(id).nonEmpty
            case None => false
        }
    }

    /**
     * Adds the client to the list of subscribers to the topic and returns
     * true if the client had not subscribed to this topic previously.
     */
    private def addSubscriber(topic: String, channel: Channel): Boolean = {
        val id  = PubSubClientServerCommunication.clientIdentifier(channel)
        if (subscriptions.get(topic).isEmpty)
            subscriptions.put(topic, new mutable.HashMap[String, Channel])

        if (clients.get(id).isEmpty)
            clients.put(id, new mutable.HashMap[String, AnyRef]())

        clients(id).put(topic, null)
        subscriptions(topic).put(id, channel).isEmpty
    }

    private def handleClientMessage(clientChannel: Channel, pubSubMessage: PubSubMessage): Unit = {
        try {
            pubSubMessage match {
                case SubscribeMessage(topic) =>
                    if (addSubscriber(topic, clientChannel)) {
                        log.debug("Subscribe from client: {} topic: {}",
                                  Array(clientChannel.toString, topic):_*)

                        /* Send the topic messages when 1st subscribing to a
                           topic. */
                        state.topicMessages(topic).foreach(msg => {
                            log.debug("Sending msg: {} to client: {}",
                                Array(msg, clientChannel.toString):_*)
                            clientChannel.write(msg, clientChannel.voidPromise())
                        })
                        clientChannel.flush()

                    } else {
                        log.debug("Client: {} is already subscribed to topic: {}",
                                  Array(clientChannel.toString, topic):_*)
                    }

                case UnsubscribeMessage(topic) =>
                    log.debug("Unsubscribe from client: {} topic: {}",
                              Array(clientChannel.toString, topic):_*)
                    removeSubscriber(topic, clientChannel)

                case pm: PublishMessage =>
                    if (!state.containsMessage(pm.topic, pm.key, pm.owner,
                    pm.stamp)) {
                        log.debug("Publish message {} from client: {}",
                                  Array(pm.toString, clientChannel.toString):_*)

                        /* Broadcast publish message to group. */
                        channel.send(new Message(null /*send to all*/,
                                                 channel.getAddress, pm.toByteArray()))

                        messagesIn += 1
                    } else {
                        log.debug("Ignoring duplicate message {}",
                                  Array(pm.toString):_*)
                    }

                case HeartBeat(ts) =>
                    log.debug("Received heartbeat with ts: {} from client: {}",
                              ts, clientChannel.toString)
                    receivedHB(ts, clientChannel)
            }

        } catch {
            case NonFatal(e) => log.warn("Unable to handle message from client",
                                         e)
        }
    }

    def receive(channel: Channel, pubSubMessage: PubSubMessage):Unit = {
        scheduler.submit(makeRunnable {handleClientMessage(channel, pubSubMessage)})
    }

    /**
     * Called to indicate that the client with the given addressed is suspected
     * to have crashed.
     */
    override def notifyFailed(channel: Channel): Unit = {
        val id = PubSubClientServerCommunication.clientIdentifier(channel)
        log.info("Detected crash of client: {}", id)

        for ((topic, _) <- clients(id)) {
            subscriptions(topic).remove(id)
        }
        clients.remove(id)
    }

    override def sendHeartbeat(channel: Channel): Unit = {
        log.debug("Sending heartbeat to client: {}", PubSubClientServerCommunication.clientIdentifier(channel))

        val heartbeat = HeartBeat(System.currentTimeMillis())
        channel.writeAndFlush(heartbeat, channel.voidPromise())
        //server.send(address, ByteBuffer.wrap(heartbeat))
    }

    override def connectionEstablished(channel: Channel): Unit = {
        log.debug("Connection established: {}", PubSubClientServerCommunication.clientIdentifier(channel))

        scheduler.submit(makeRunnable {
            schedulePeriodicHeartbeat(channel)
        })
    }
}
