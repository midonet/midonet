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

package org.midonet.cluster.data.storage

import java.util.UUID
import java.util.concurrent.Executors

import scala.collection.JavaConverters._
import scala.concurrent.duration.DurationInt

import kafka.consumer.ConsumerIterator
import kafka.javaapi.consumer.ConsumerConnector
import kafka.serializer.{Decoder, Encoder, StringDecoder, StringEncoder}
import kafka.utils.VerifiableProperties
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.HAMergedMapTest.Opinion
import org.midonet.cluster.data.storage.MergedMap.{MergedMapType, MergedMapId}
import org.midonet.util.reactivex.AwaitableObserver

object HAMergedMapTest {
    type Opinion = (String, String, String)

    class OpinionEncoder(props: VerifiableProperties = null)
        extends Encoder[Opinion] {

        val stringEncoder = new StringEncoder()
        override def toBytes(msg: Opinion): Array[Byte] = {
            stringEncoder.toBytes(msg._1 + "-" + msg._2 + "-" + msg._3)
        }
    }

    class OpinionDecoder(props: VerifiableProperties = null)
        extends Decoder[Opinion] {

        val stringDecoder = new StringDecoder()
        private def nullFilter(value: String): String = value match {
            case "null" => null
            case _ => value
        }
        override def fromBytes(msg: Array[Byte]): Opinion = {
            val msgAsString = stringDecoder.fromBytes(msg)
            val tokens = msgAsString.split("-")
            (nullFilter(tokens(0)), nullFilter(tokens(1)), nullFilter(tokens(2)))
        }
    }
}

@RunWith(classOf[JUnitRunner])
class HAMergedMapTest extends KafkaTest[String, Opinion] {

    import HAMergedMapTest._

    val timeout = 5 seconds
    val executor = Executors.newSingleThreadExecutor()
    val stringEncoder = new StringEncoder()
    val stringDecoder = new StringDecoder()
    var map: MergedMap[String, String] = _
    var mapId: MergedMapId = _

    class MySerialization extends KafkaSerialization[String, String] {
        override def keyDecoder: Decoder[String] = new StringDecoder()
        override def messageEncoder = new OpinionEncoder()
        override def keyEncoder: Encoder[String] = new StringEncoder()
        override def messageDecoder = new OpinionDecoder()
    }

    override def setup = {
        mapId = new MergedMapId(UUID.randomUUID(), MergedMapType.ARP)
        val storage =
            new HAMergedMap[String, String](mapId, "owner", fillConfig(),
                                            kafkaServer.zkClient,
                                            new MySerialization)
        map = new MergedMap[String, String](storage)
    }

    private def createConsumerIterator(topic: String, groupId: String)
    : (ConsumerConnector, ConsumerIterator[String, Opinion]) = {
        val newCons = createConsumer(config, groupId)
        val topicCountMap = Map(topic -> Int.box(1) /* #partitions */).asJava
        val consumerMap = newCons.createMessageStreams(topicCountMap,
                                                       new StringDecoder(),
                                                       new OpinionDecoder())
        val stream =  consumerMap.get(topic).get(0)
        (newCons, stream.iterator())
    }

    private def newMap(mapId: MergedMapId, owner: String)
    : MergedMap[String, String] = {

        val newId = new MergedMapId(UUID.randomUUID(), MergedMapType.ARP)
        val storage = new HAMergedMap[String, String](newId, owner,
                                                      fillConfig(),
                                                      kafkaServer.zkClient,
                                                      new MySerialization)
        new MergedMap[String, String](storage)
    }

    feature("Kafka") {
        scenario("PutOpinion/RemoveOpinion") {
            Given("A merged map")
            val (newCons, consIt) = createConsumerIterator(mapId.toString,
                                                           "myGroup2")
            When("We put an opinion")
            map.putOpinion("myKey", "myValue")

            Then("The opinion is published on the Kafka topic")
            consIt.hasNext() shouldBe true
            var kafkaMsg = consIt.next
            kafkaMsg.key shouldBe "myKey"
            kafkaMsg.message shouldBe ("myKey", "myValue", "owner")

            And("The opinion is consumed by the map")
            val obs = createObserverAndSubscribe(map)
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            map.get("myKey") shouldBe "myValue"

//            When("A new merged map is created with the same map id")
//            val map2 = newMap(mapId, "owner2")
//
//            Then("The new map contains the opinion")
//            val obs2 = createObserverAndSubscribe(map2)
//            obs2.awaitOnNext(1, timeout) shouldBe true
//            map2.get("myKey") shouldBe "myValue"

            When("We remove the opinion")
            map.removeOpinion("myKey")

            Then("The opinion removal is published on the Kafka topic")
            consIt.hasNext() shouldBe true
            kafkaMsg = consIt.next
            kafkaMsg.key shouldBe "myKey"
            kafkaMsg.message shouldBe ("myKey", null, "owner")

            And("The map consumes the opinion")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            map.get("myKey") shouldBe null

            newCons.shutdown()
        }
    }

    private def createObserverAndSubscribe(map: MergedMap[String, String]) = {
        val obs = new TestObserver[(String, String)]
                      with AwaitableObserver[(String, String)]
        map.observable.subscribe(obs)
        obs
    }
}
