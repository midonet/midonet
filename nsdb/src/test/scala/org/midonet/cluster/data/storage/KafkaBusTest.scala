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

import java.util

import scala.collection.JavaConverters._
import scala.concurrent.duration.DurationInt

import kafka.serializer.{StringDecoder, StringEncoder}
import org.apache.kafka.clients.consumer.KafkaConsumer
import org.apache.kafka.common.TopicPartition
import org.apache.kafka.common.serialization.{Deserializer, Serializer, StringDeserializer, StringSerializer}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import rx.observers.TestObserver

import org.midonet.cluster.data.storage.KafkaBusTest.Opinion
import org.midonet.util.reactivex.AwaitableObserver

object KafkaBusTest {
    type Opinion = (String, String, String)

    class OpinionEncoder() extends Serializer[Opinion] {
        val stringEncoder = new StringSerializer()

        override def close(): Unit = stringEncoder.close()
        override def configure(configs: util.Map[String, _],
                               isKey: Boolean): Unit = {}

        override def serialize(topic: String, opinion: Opinion): Array[Byte] = {
            val msg = opinion._1 + "-" + opinion._2 + "-" + opinion._3
            stringEncoder.serialize(topic, msg)
        }
    }

    class OpinionDecoder() extends Deserializer[Opinion] {
        val stringDecoder = new StringDeserializer()

        private def nullFilter(value: String): String = value match {
            case "null" => null
            case _ => value
        }

        override def close(): Unit = stringDecoder.close()
        override def configure(configs: util.Map[String, _],
                               isKey: Boolean): Unit = {}

        override def deserialize(topic: String, opinion: Array[Byte])
        : Opinion = {
            val msgAsString = stringDecoder.deserialize(topic, opinion)
            val tokens = msgAsString.split("-")
            (nullFilter(tokens(0)), nullFilter(tokens(1)),
                nullFilter(tokens(2)))
        }
    }
}

@RunWith(classOf[JUnitRunner])
class KafkaBusTest extends KafkaTest[Opinion] {

    import KafkaBusTest._

    val timeout = 5 seconds
    val stringEncoder = new StringEncoder()
    val stringDecoder = new StringDecoder()
    val mapId = "Arp"
    var bus: KafkaBus[String, String] = _
    var map: MergedMap[String, String] = _

    class MySerialization extends KafkaSerialization[String, String] {
        override def keyAsString(key: String): String = key
        override def keyFromString(key: String): String = key
        override def messageEncoder = new OpinionEncoder()
        override def messageDecoder = new OpinionDecoder()
    }

    override def setup(): Unit = {
        val (kafkaBus, mergedMap) = newBusAndMap(mapId, "owner")
        bus = kafkaBus
        map = mergedMap
    }

    override def teardown(): Unit = {
        bus.close()
    }

    private def newBusAndMap(mapId: String, owner: String)
    : (KafkaBus[String, String], MergedMap[String, String]) = {
        val storage = new KafkaBus[String, String](mapId, owner,
            fillConfig(), kafkaServer.zkClient, new MySerialization)
        val map = new MergedMap[String, String](storage)
        (storage, map)
    }

    feature("PutOpinion/RemoveOpinion") {
        scenario("One map") {
            Given("An opinion")
            map.putOpinion("myKey", "myValue")

            When("We create a 2nd consumer for the same topic")
            val consumer = createConsumer(config, "group2",
                                          classOf[OpinionDecoder].getName)
            val topicPartition = new TopicPartition(mapId, 0 /* partition */)
            consumer.subscribe(topicPartition)
            consumer.seekToBeginning(topicPartition)

            Then("The opinion is published on the Kafka topic")
            var msgs = consumeMsg(consumer)
            msgs should contain theSameElementsAs
                List(("myKey", "myValue", "owner"))

            And("The opinion is consumed by the map")
            val obs = createObserverAndSubscribe(map)
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            map.get("myKey") shouldBe "myValue"

            When("We remove the opinion")
            map.removeOpinion("myKey")

            Then("The opinion removal is published on the Kafka topic")
            msgs = consumeMsg(consumer)
            msgs should contain theSameElementsAs List(("myKey", null, "owner"))

            And("The map consumes the opinion")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            map.get("myKey") shouldBe null

            consumer.close()
        }

        scenario("Two maps, the 2nd one joins later") {
            Given("One map that has 100 opinions")
            for (i <- 1 to 100) {
                map.putOpinion("myKey" + i, "b" + i)
            }

            When("We subscribe to it")
            val obs = createObserverAndSubscribe(map)

            Then("We are notified of the map's content")
            obs.awaitOnNext(100, timeout) shouldBe true

            When("We close the bus")
            bus.close()

            Then("The observable completes")
            obs.awaitCompletion(timeout)

            When("We create a new bus and map")
            val (_, newMap) = newBusAndMap(mapId, "owner")

            And("We subscribe to the new map")
            val obs2 = createObserverAndSubscribe(newMap)

            Then("We get notified of the 100 opinions")
            obs2.awaitOnNext(100, timeout) shouldBe true

            And("The two maps have the same key-value pairs")
            for (i <- 1 to 100) {
                map.get("myKey" + i) shouldBe "b" + i
                newMap.get("myKey" + i) shouldBe "b" + i
            }
            map.size shouldBe 100
            newMap.size shouldBe 100
        }
    }

    feature("Compute majority") {
        scenario("Let's compute!") {
            bus.computeMajority(1) shouldBe 1
            bus.computeMajority(2) shouldBe 2
            bus.computeMajority(3) shouldBe 2
            bus.computeMajority(4) shouldBe 3
        }
    }

    private def createObserverAndSubscribe(map: MergedMap[String, String]) = {
        val obs = new TestObserver[(String, String)]
                      with AwaitableObserver[(String, String)]
        map.observable.subscribe(obs)
        obs
    }

    private def consumeMsg(consumer: KafkaConsumer[String, Opinion])
    : Seq[Opinion] = {
        consumer.poll(timeout.toMillis).asScala.map(_.value).toSeq
    }
}
