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

import kafka.consumer.ConsumerIterator
import kafka.javaapi.consumer.ConsumerConnector
import kafka.serializer.{Decoder, StringDecoder, StringEncoder}
import kafka.utils.VerifiableProperties
import org.apache.kafka.common.serialization.{Serializer, StringSerializer}
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
class KafkaBusTest extends KafkaTest[String, Opinion] {

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

    override def setup = {
        bus = new KafkaBus[String, String](mapId, "owner", fillConfig(),
                  kafkaServer.zkClient, new MySerialization)
        map = new MergedMap[String, String](bus)
    }

    private def newMap(mapId: String, owner: String)
    : MergedMap[String, String] = {
        val storage = new KafkaBus[String, String](mapId, owner,
            fillConfig(), kafkaServer.zkClient, new MySerialization)
        new MergedMap[String, String](storage)
    }

    feature("PutOpinion/RemoveOpinion") {
        scenario("One map") {
            Given("A merged map")
            val (cons, consIt) = createConsumerIterator("myGroup2")
            When("We put an opinion")
            map.putOpinion("myKey", "myValue")

            Then("The opinion is published on the Kafka topic")
            consIt.hasNext() shouldBe true
            var kafkaMsg = consIt.next
            kafkaMsg.key shouldBe "myKey-owner"
            kafkaMsg.message shouldBe ("myKey", "myValue", "owner")

            And("The opinion is consumed by the map")
            val obs = createObserverAndSubscribe(map)
            obs.awaitOnNext(1, timeout) shouldBe true
            obs.getOnNextEvents should have size 1
            map.get("myKey") shouldBe "myValue"

            When("We remove the opinion")
            map.removeOpinion("myKey")

            Then("The opinion removal is published on the Kafka topic")
            consIt.hasNext() shouldBe true
            kafkaMsg = consIt.next
            kafkaMsg.key shouldBe "myKey-owner"
            kafkaMsg.message shouldBe ("myKey", null, "owner")

            And("The map consumes the opinion")
            obs.awaitOnNext(2, timeout) shouldBe true
            obs.getOnNextEvents should have size 2
            map.get("myKey") shouldBe null

            cons.shutdown()
        }

        scenario("Two maps, the 2nd one joins later") {
            Given("One map that has 100 opinions")
            val obs = createObserverAndSubscribe(map)
            for (i <- 1 to 100) {
                map.putOpinion("myKey" + i, "b" + i)
            }
            obs.awaitOnNext(100, timeout) shouldBe true

            When("A 2nd map is created later on")
            val map2 = newMap(mapId, "owner2")
            val obs2 = createObserverAndSubscribe(map2)
            obs2.awaitOnNext(100, timeout) shouldBe true

            Then("The two maps have the same key-value pairs")
            for (i <- 1 to 100) {
                map.get("myKey" + i) shouldBe "b" + i
                map2.get("myKey" + i) shouldBe "b" + i
            }
            map.size shouldBe 100
            map2.size shouldBe 100

            And("When the 2nd map inserts a superseding opinion")
            map2.putOpinion("myKey1", "c1")
            obs.awaitOnNext(101, timeout) shouldBe true
            obs2.awaitOnNext(101, timeout) shouldBe true

            Then("The two maps contain the superseding opinion")
            map.get("myKey1") shouldBe "c1"
            map2.get("myKey1") shouldBe "c1"
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

    private def createConsumerIterator(groupId: String)
    : (ConsumerConnector, ConsumerIterator[String, Opinion]) = {
        val topic = mapId.toString
        val newCons = createConsumer(config, groupId)
        val topicCountMap = Map(topic -> Int.box(1) /* #partitions */).asJava
        val consumerMap = newCons.createMessageStreams(topicCountMap,
                                                       new StringDecoder(),
                                                       new OpinionDecoder())
        val stream =  consumerMap.get(topic).get(0)
        (newCons, stream.iterator())
    }

    private def createObserverAndSubscribe(map: MergedMap[String, String]) = {
        val obs = new TestObserver[(String, String)]
                      with AwaitableObserver[(String, String)]
        map.observable.subscribe(obs)
        obs
    }
}
