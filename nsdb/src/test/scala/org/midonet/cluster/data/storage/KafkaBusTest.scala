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
import org.apache.kafka.clients.producer.ProducerRecord
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
        scenario("The map interacts with the Kafka topic correctly") {
            Given("An opinion")
            map.putOpinion("myKey", "myValue")

            When("We create a consumer for the same topic")
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

            When("We create a producer")
            val producer = createProducer(config,
                                          classOf[StringSerializer].getName,
                                          classOf[OpinionEncoder].getName)

            And("We publish a message on the map's topic")
            val msg = new ProducerRecord[String, Opinion](mapId,
                          0 /* partition */, "myKey2-owner2",
                          ("myKey2", "myValue2", "owner2"))
            producer.send(msg)

            Then("The map consumes the opinion")
            obs.awaitOnNext(3, timeout) shouldBe true
            obs.getOnNextEvents should have size 3
            map.get("myKey2") shouldBe "myValue2"

            producer.close()
            consumer.close()
        }

        scenario("Closing the bus and creating new ones") {
            When("We subscribe to the map's observable")
            val obs = createObserverAndSubscribe(map)

            And("We close the bus")
            bus.close()

            Then("The map's observable should complete")
            obs.awaitCompletion(timeout)

            And("The multi-topic consumer should be stopped")
            KafkaBus.isMultiTopicConsumerStopped shouldBe true
            And("The reference count to the multi-topic consumer should be 0")
            KafkaBus.multiTopicConsumerRefCount shouldBe 0

            When("We create a new bus and map")
            val (bus2, map2) = newBusAndMap("Arp2", "owner2")

            Then("The reference count to the multi-topic consumer should be 1")
            KafkaBus.multiTopicConsumerRefCount shouldBe 1

            And("When we create a producer")
            val producer = createProducer(config,
                                          classOf[StringSerializer].getName,
                                          classOf[OpinionEncoder].getName)

            And("We publish a message on the topic")
            val msg = new ProducerRecord[String, Opinion]("Arp2",
                          0 /* partition */, "myKey2-owner2",
                          ("myKey2", "myValue2", "owner2"))
            producer.send(msg)

            Then("The new map consumes the opinion")
            val obs2 = createObserverAndSubscribe(map2)
            obs2.awaitOnNext(1, timeout) shouldBe true
            obs2.getOnNextEvents should have size 1
            map2.get("myKey2") shouldBe "myValue2"

            When("We create a 3rd bus and map")
            val (bus3, map3) = newBusAndMap("Arp3", "owner3")

            Then("The reference count to the multi-topic consumer should be 2")
            KafkaBus.multiTopicConsumerRefCount shouldBe 2

            And("When we publish a message on the 3rd map's topic")
            val msg2 = new ProducerRecord[String, Opinion]("Arp3",
                           0 /* partition */, "myKey3-owner3",
                           ("myKey3", "myValue3", "owner3"))
            producer.send(msg2)

            Then("The map consumes the opinion")
            val obs3 = createObserverAndSubscribe(map3)
            obs3.awaitOnNext(1, timeout) shouldBe true
            obs3.getOnNextEvents should have size 1
            map3.get("myKey3") shouldBe "myValue3"

            And("When we close the 3rd bus")
            bus3.close()

            Then("The map's observable should complete")
            obs3.awaitCompletion(timeout)

            And("The multi-topic consumer should not be stopped")
            KafkaBus.isMultiTopicConsumerStopped shouldBe false

            And("The consumer reference count should be 1")
            KafkaBus.multiTopicConsumerRefCount shouldBe 1

            When("We close the remaining bus")
            bus2.close()

            Then("The consumer reference count should be 0")
            KafkaBus.multiTopicConsumerRefCount shouldBe 0

            producer.close()
        }
    }
    
    feature("Compute majority") {
        scenario("Let's compute!") {
            bus.computeMajority(1) shouldBe 1
            bus.computeMajority(2) shouldBe 2
            bus.computeMajority(3) shouldBe 2
            bus.computeMajority(4) shouldBe 3
            bus.computeMajority(5) shouldBe 3
            bus.computeMajority(6) shouldBe 4
            bus.computeMajority(7) shouldBe 4
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
