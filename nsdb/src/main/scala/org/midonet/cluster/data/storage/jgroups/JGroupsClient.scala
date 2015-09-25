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

import io.netty.bootstrap.Bootstrap
import io.netty.channel.nio.NioEventLoopGroup
import io.netty.channel.socket.nio.{NioSocketChannel, NioServerSocketChannel}
import io.netty.handler.logging.{LogLevel, LoggingHandler}
import org.jgroups.stack.IpAddress
import org.midonet.cluster.data.storage.MergedMap
import org.midonet.cluster.data.storage.jgroups.JGroupsClient.{KeyPayloadOwnerTriple}
import rx.Observable.OnSubscribe
import rx.{Subscriber, Observer, Observable}
import rx.subjects.PublishSubject

import scala.collection.mutable
import scala.util.Random
import scala.util.control.NonFatal

import org.jgroups.Address
import org.jgroups.blocks.cs.{NioClient, ReceiverAdapter, TcpClient}
import org.slf4j.LoggerFactory

import org.midonet.cluster.data.storage.jgroups.JGroupsBroker._
import org.midonet.cluster.storage.JGroupsConfig
import org.midonet.util.functors.{makeRunnable, makeFunc1, makeAction1}

import io.netty.channel._

object JGroupsClient {

    type Key = String
    type Payload = String
    type Owner = String
    type KeyPayloadOwnerTriple = (Key, Payload, Owner)

    /**
     * Simple message Serializer for debugging the JGroups implementation
     */
    class MessageSerializer() extends JGroupsMessageSerializer[String, String] {
        override def decodeMessage(keyPayloadOwnerTriple: KeyPayloadOwnerTriple):
        (String, String, String) = {
            keyPayloadOwnerTriple
        }

        override def encodeMessage(opinion: (String, String, String)):
        KeyPayloadOwnerTriple = {
            opinion
        }
    }

    /**
     * Main method for debugging the JGroups Client, can be removed
     * @param args
     */
    def main(args: Array[String]): Unit = {
        val bindHost = if (args.length >= 1) args(0) else ""
        val serializer = new MessageSerializer()
        val randomOwner = "fred" + Random.nextInt(10000)
        val client = new JGroupsClient(bindHost)
        val map = new MergedMap[String, String](new JGroupsBus("test-map", randomOwner, serializer, client))

        map.observable.subscribe(new Observer[map.MapUpdate] {
            override def onCompleted(): Unit = {
                System.out.println("Map observable completed")
            }

            override def onError(e: Throwable): Unit = {
                System.out.println("ERROR: " + e.toString)
            }

            override def onNext(t: map.MapUpdate): Unit = {
                System.out.println("Received map update: " + t.key + ":" + t.newValue)
                System.out.println("Current map:")
                map.snapshot.foreach({case (key:String, value:String) => System.out.println("- " + key + ":" + value)})

            }
        })

        map.putOpinion("testkey" + Random.nextInt(10000), "myfirstvalue")
        val otherKey = "testkey" + Random.nextInt(10000)
        map.putOpinion(otherKey, "0myfirstvalue")
        map.putOpinion(otherKey, "1mysecondvalue")
    }

    class NotSubscribedToTopicException(topic: String)
      extends Exception("You are not subscribed to the requested topic: " + topic) {
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
class JGroupsClient(bindHost: String = "", initialBroker: Option[IpAddress] = None) extends PubSubMessageReceiver
                            with HeartBeatEnabled {

    def this(bindHost: String,
             brokerHostPort: String) = {
        this(bindHost, Some(new IpAddress(brokerHostPort)))
    }

    private val log = LoggerFactory.getLogger("org.midonet.cluster.JGroups.client")

    private val jgroupsZkClient = new JGroupsZkClient()

    override protected var jgroupsConf: JGroupsConfig = JGroupsZkClient.jgroupsConf

    private val subscriptions = new mutable.HashSet[String]()

    private val messageSubject = PublishSubject.create[PublishMessage]()


    /* Map of messages to be acknowledged by a broker. This is a mapping
       from message key to the message in byte array format.
       MessageIdentifier = (Topic, Key, Owner)
       */
    type MessageIdentifier = (String, String, String)
    private val msgsToAck = new mutable.HashMap[MessageIdentifier, (Long, PublishMessage)]()

    private val eventLoopGroup: EventLoopGroup = new NioEventLoopGroup()
    private var brokerChannel: Option[Channel] = None

    if (initialBroker.isEmpty) connectToRandomBroker()
    else connectToBroker(initialBroker.get)

    private var msgsReceived: Int = 0

    /**
      * Connects to a randomly selected broker and returns the [[TcpClient]].
      * Note that the list of alive brokers is obtained from ZooKeeper.
      */
    private def connectToRandomBroker(brokerToDiscard: Option[Address] = None)
    : Unit = {

        val broker = jgroupsZkClient.randomBroker(brokerToDiscard)
        connectToBroker(broker)
    }

    /**
     * Connects to a given broker
     */
    private def connectToBroker(broker: IpAddress)
    : Unit = {
        log.info("Connecting to broker server: {}", broker)

        val bootstrap = new Bootstrap()
        bootstrap.group(eventLoopGroup)
            .channel(classOf[NioSocketChannel])
            .handler(new PubSubChannelInitializer(this));

        // Start the connection attempt.
        bootstrap.connect(broker.getIpAddress, broker.getPort).sync()

        //TODO: Retry on connection failure
    }

    /**
     * Checks if the message was an acknowledgement, and removes it from the list
     * if it was
     */
    private def handlePotentialAcknowledgment(pm: PublishMessage): Boolean =
        msgsToAck.get((pm.topic, pm.key, pm.owner)) match {
            case Some((stamp, msg)) =>
                if (pm.stamp >= stamp) {
                    msgsToAck.remove((pm.topic, pm.key, pm.owner))
                    log.debug("Received acknowledgment for message: {} | msgsToAck.size={}",
                        (pm.topic, pm.key, pm.owner), msgsToAck.size)
                } else {
                    log.debug("Stale ACK for {}",
                        Array((pm.topic, pm.key, pm.owner)):_*)
                }
                true
            case None =>
                false
        }

    def connectionEstablished(channel: Channel) = {
        brokerChannel = Some(channel)
        schedulePeriodicHeartbeat(channel)

        //Subscribe and send pending messages
        for (topic <- subscriptions) { subscribe(topic) }
        for ((key, msg) <- msgsToAck) { publish(msg._2) }
    }

    /**
     * Called when receiving a message from a JGroups node.
     */
    override def receive(channel: Channel, pubSubMessage: PubSubMessage): Unit =
        scheduler.submit(makeRunnable {
        try {
            pubSubMessage match {
                case msg: PublishMessage =>
                    if (!handlePotentialAcknowledgment(msg)) {
                        log.debug("Received msg for topic: {} with key: {} " +
                            "and payload: {} from broker: {}",
                            Array(msg.topic, msg.key, msg.payload,
                                channel.toString):_*)
                    }
                    msgsReceived += 1

                    messageSubject.onNext(msg)
                case HeartBeat(ts) =>
                    log.debug("Received heartbeat with ts: {} from broker: {}",
                              ts, channel)
                    receivedHB(ts, channel)

                case msg =>
                    log.warn("Received unexpected message: {} from broker: {}",
                             Array(msg, channel.toString):_*)
            }

        } catch {
            case NonFatal(e) => log.warn("Unable to receive message from broker")
        }
        })

    def subscribe(topic: String): Unit = scheduler.submit(makeRunnable {
        log.info("Subscribing to topic: {}", topic)
        try {
            subscriptions += topic
            sendToBroker(SubscribeMessage(topic))
        } catch {
            case NonFatal(e) => log.warn("Unable to subscribe to topic: " +
                                         topic, e)
        }
    })

    def unsubscribe(topic: String): Unit = scheduler.submit(makeRunnable {
        log.info("Unsubscribing from topic: {}", topic)
        try {
            subscriptions -= topic
            sendToBroker(UnsubscribeMessage(topic))
        } catch {
            case NonFatal(e) => log.warn("Unable to unsubscribe from topic: " +
                                         topic, e)
        }
    })

    def publish(topic: String,
                kpo: KeyPayloadOwnerTriple,
                stamp: Long): Unit =
        publish(PublishMessage(topic, kpo._1, kpo._3, stamp, kpo._2))

    private def publish(msg: PublishMessage): Unit =
        scheduler.submit(makeRunnable {
            log.debug("Publishing message with key: {} and payload: {} on " +
                "topic: {}", msg.key, msg.payload, msg.topic)
            try {
                msgsToAck.put((msg.topic, msg.key, msg.owner),
                    (msg.stamp, msg))
                sendToBroker(msg)
            } catch {
                case NonFatal(e) => log.warn("Unable to publish msg: " + msg, e)
            }
        })

    private def sendToBroker(p: PubSubMessage): Unit = {
        if (brokerChannel.isEmpty) {
            //TODO: Can cause flooding of the log
            log.warn("Dropped message, no channel: {}", p.toString)
        } else {
            brokerChannel.get.writeAndFlush(p)
        }
    }

    override def notifyFailed(channel: Channel): Unit = {
        try {
            log.warn("Detected the crash of broker: {}", channel.toString)
            brokerChannel = None

            /* We discard the broker we just suspected of having crashed because
           it could be that this broker's node is still present in ZooKeeper.
           We do not however rely on ZooKeeper nodes to perform failure detection
           because the broker must also detect client failures and we chose
           to not add a ZooKeeper node for each client. */
            val (brokerHost, brokerPort) = PubSubClientServerCommunication.channelHostPortTuple(channel)
            connectToRandomBroker(brokerToDiscard = Some(new IpAddress(brokerHost, brokerPort)))
        } catch {
            case e:Exception => log.error("Exception during broker crash", e)
        }
    }

    override def sendHeartbeat(channel: Channel): Unit = {
        log.debug("Sending heartbeat to broker")

        val heartbeat = HeartBeat(System.currentTimeMillis())
        sendToBroker(heartbeat)
    }

    def messageObservable: Observable[PublishMessage] = messageSubject

    def topicObservable(topic: String): Observable[KeyPayloadOwnerTriple] = {

        Observable.create(new OnSubscribe[KeyPayloadOwnerTriple] {
            override def call(subscriber: Subscriber[_ >: KeyPayloadOwnerTriple]): Unit = {

                def extractKeyOwnerAndPayload(msg: PublishMessage): KeyPayloadOwnerTriple =
                    (msg.key, msg.payload, msg.owner)

                val topicObs: Observable[KeyPayloadOwnerTriple] = messageSubject
                    .filter(makeFunc1(_.topic == topic))
                    .map(makeFunc1(extractKeyOwnerAndPayload))

                topicObs.subscribe(subscriber)
                subscribe(topic)
            }
        })
    }

}
