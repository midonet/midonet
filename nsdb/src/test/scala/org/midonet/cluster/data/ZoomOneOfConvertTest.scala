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

package org.midonet.cluster.data

import java.util.UUID

import scala.collection.JavaConverters._

import org.junit.runner.RunWith
import org.scalatest.{Matchers, FeatureSpec}
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.data.ZoomConvert.Factory
import org.midonet.cluster.data.ZoomOneOfConvertTest._
import org.midonet.cluster.models.TestModels._
import org.midonet.cluster.util.UUIDUtil._

@RunWith(classOf[JUnitRunner])
class ZoomOneOfConvertTest extends FeatureSpec with Matchers {

    feature("Test conversion from Protocol Buffers") {
        scenario("Test conversion for first one-of") {
            val proto = TestOneOfMessage.newBuilder
                .setId(UUID.randomUUID.asProto)
                .setName(UUID.randomUUID.toString)
                .setDevice(newDeviceMessage)
                .setFirst(TestFirst.newBuilder
                    .setFirstId(UUID.randomUUID.asProto)
                    .setFirstName(UUID.randomUUID.toString))
                .build()

            val pojo = ZoomConvert.fromProto(proto, classOf[Base])

            pojo shouldEqual proto
        }

        scenario("Test conversion for second one-of") {
            val proto = TestOneOfMessage.newBuilder
                .setId(UUID.randomUUID.asProto)
                .setName(UUID.randomUUID.toString)
                .setDevice(newDeviceMessage)
                .setSecond(TestSecond.newBuilder
                    .setSecondId(UUID.randomUUID.asProto)
                    .setSecondName(UUID.randomUUID.toString))
                .build()

            val pojo = ZoomConvert.fromProto(proto, classOf[Base])

            pojo shouldEqual proto
        }

        scenario("Test conversion for third-alpha one-of") {
            val proto = TestOneOfMessage.newBuilder
                .setId(UUID.randomUUID.asProto)
                .setName(UUID.randomUUID.toString)
                .setDevice(newDeviceMessage)
                .setThird(TestThird.newBuilder
                    .setThirdId(UUID.randomUUID.asProto)
                    .setThirdName(UUID.randomUUID.toString)
                    .setAlpha(TestThirdAlpha.newBuilder
                        .setAlphaId(UUID.randomUUID.asProto)
                        .setAlphaName(UUID.randomUUID.toString)
                        .build())
                    .build())
                .build()

            val pojo = ZoomConvert.fromProto(proto, classOf[Base])

            pojo shouldEqual proto
        }

        scenario("Test conversion for third-beta one-of") {
            val proto = TestOneOfMessage.newBuilder
                .setId(UUID.randomUUID.asProto)
                .setName(UUID.randomUUID.toString)
                .setDevice(newDeviceMessage)
                .setThird(TestThird.newBuilder
                    .setThirdId(UUID.randomUUID.asProto)
                    .setThirdName(UUID.randomUUID.toString)
                    .setBeta(TestThirdBeta.newBuilder
                        .setBetaId(UUID.randomUUID.asProto)
                        .setBetaName(UUID.randomUUID.toString)
                        .build())
                    .build())
                .build()

            val pojo = ZoomConvert.fromProto(proto, classOf[Base])

            pojo shouldEqual proto
        }
    }

    feature("Test conversion to Protocol Buffers") {
        scenario("Test conversion for first one-of") {
            val pojo = newFirst

            val proto = ZoomConvert.toProto(pojo, classOf[TestOneOfMessage])

            pojo shouldEqual proto
        }

        scenario("Test conversion for second one-of") {
            val pojo = newSecond

            val proto = ZoomConvert.toProto(pojo, classOf[TestOneOfMessage])

            pojo shouldEqual proto
        }

        scenario("Test conversion for third-alpha one-of") {
            val pojo = newThirdAlpha

            val proto = ZoomConvert.toProto(pojo, classOf[TestOneOfMessage])

            pojo shouldEqual proto
        }

        scenario("Test conversion for third-beta one-of") {
            val pojo = newThirdBeta

            val proto = ZoomConvert.toProto(pojo, classOf[TestOneOfMessage])

            pojo shouldEqual proto
        }
    }

    private def newDeviceMessage: FakeDevice = {
        FakeDevice.newBuilder
            .setId(UUID.randomUUID.asProto)
            .setName(UUID.randomUUID.toString)
            .addAllPortIds(Seq(UUID.randomUUID.toString,
                               UUID.randomUUID.toString).asJava)
            .build()
    }

    private def newDevice: Device = {
        val device = new Device
        device.id = UUID.randomUUID
        device.name = UUID.randomUUID.toString
        device.portIds = Set(UUID.randomUUID.toString, UUID.randomUUID.toString)
        device
    }

    private def newFirst: First = {
        val first = new First
        first.id = UUID.randomUUID
        first.name = UUID.randomUUID.toString
        first.device = newDevice
        first.firstId = UUID.randomUUID
        first.firstName = UUID.randomUUID.toString
        first
    }

    private def newSecond: Second = {
        val second = new Second
        second.id = UUID.randomUUID
        second.name = UUID.randomUUID.toString
        second.device = newDevice
        second.secondId = UUID.randomUUID
        second.secondName = UUID.randomUUID.toString
        second
    }

    private def newThirdAlpha: ThirdAlpha = {
        val thirdAlpha = new ThirdAlpha
        thirdAlpha.id = UUID.randomUUID
        thirdAlpha.name = UUID.randomUUID.toString
        thirdAlpha.device = newDevice
        thirdAlpha.thirdId = UUID.randomUUID
        thirdAlpha.thirdName = UUID.randomUUID.toString
        thirdAlpha.alphaId = UUID.randomUUID
        thirdAlpha.alphaName = UUID.randomUUID.toString
        thirdAlpha
    }

    private def newThirdBeta: ThirdBeta = {
        val thirdBeta = new ThirdBeta
        thirdBeta.id = UUID.randomUUID
        thirdBeta.name = UUID.randomUUID.toString
        thirdBeta.device = newDevice
        thirdBeta.thirdId = UUID.randomUUID
        thirdBeta.thirdName = UUID.randomUUID.toString
        thirdBeta.betaId = UUID.randomUUID
        thirdBeta.betaName = UUID.randomUUID.toString
        thirdBeta
    }
}

object ZoomOneOfConvertTest {

    @ZoomClass(clazz = classOf[FakeDevice])
    class Device extends ZoomObject with Matchers {
        @ZoomField(name = "id")
        var id: UUID = _
        @ZoomField(name = "name")
        var name: String = _
        @ZoomField(name = "port_ids")
        var portIds: Set[String] = _

        def shouldEqual(device: FakeDevice): Unit = {
            device.hasId shouldBe true
            device.hasName shouldBe true
            id shouldBe device.getId.asJava
            name shouldBe device.getName
            portIds should contain theSameElementsAs device.getPortIdsList.asScala
        }
    }

    @ZoomClass(clazz = classOf[TestOneOfMessage], factory = classOf[BaseFactory])
    abstract class Base extends ZoomObject with Matchers {
        @ZoomField(name = "id")
        var id: UUID = _
        @ZoomField(name = "name")
        var name: String = _
        @ZoomField(name = "device")
        var device: Device = _

        def shouldEqual(message: TestOneOfMessage): Unit = {
            message.hasId shouldBe true
            message.hasName shouldBe true
            message.hasDevice shouldBe true
            id shouldBe message.getId.asJava
            name shouldBe message.getName
            device shouldEqual message.getDevice
        }
    }

    @ZoomOneOf(name = "first")
    abstract class FirstAbstract extends Base {
        @ZoomField(name = "first_id")
        var firstId: UUID = _

        override def shouldEqual(message: TestOneOfMessage): Unit = {
            super.shouldEqual(message)
            message.hasFirst shouldBe true
            message.getFirst.hasFirstId shouldBe true
            firstId shouldBe message.getFirst.getFirstId.asJava
        }
    }

    class First extends FirstAbstract {
        @ZoomField(name = "first_name")
        var firstName: String = _

        override def shouldEqual(message: TestOneOfMessage): Unit = {
            super.shouldEqual(message)
            message.getFirst.hasFirstName shouldBe true
            firstName shouldBe message.getFirst.getFirstName
        }
    }

    @ZoomOneOf(name = "second")
    class Second extends Base {
        @ZoomField(name = "second_id")
        var secondId: UUID = _
        @ZoomField(name = "second_name")
        var secondName: String = _

        override def shouldEqual(message: TestOneOfMessage): Unit = {
            super.shouldEqual(message)
            message.hasSecond shouldBe true
            message.getSecond.hasSecondId shouldBe true
            message.getSecond.hasSecondName shouldBe true
            secondId shouldBe message.getSecond.getSecondId.asJava
            secondName shouldBe message.getSecond.getSecondName
        }
    }

    @ZoomOneOf(name = "third")
    abstract class Third extends Base {
        @ZoomField(name = "third_id")
        var thirdId: UUID = _
        @ZoomField(name = "third_name")
        var thirdName: String = _

        override def shouldEqual(message: TestOneOfMessage): Unit = {
            super.shouldEqual(message)
            message.hasThird shouldBe true
            message.getThird.hasThirdId shouldBe true
            message.getThird.hasThirdName shouldBe true
            thirdId shouldBe message.getThird.getThirdId.asJava
            thirdName shouldBe message.getThird.getThirdName
        }
    }

    @ZoomOneOf(name = "alpha")
    class ThirdAlpha extends Third {
        @ZoomField(name = "alpha_id")
        var alphaId: UUID = _
        @ZoomField(name = "alpha_name")
        var alphaName: String = _

        override def shouldEqual(message: TestOneOfMessage): Unit = {
            super.shouldEqual(message)
            message.getThird.hasAlpha shouldBe true
            message.getThird.getAlpha.hasAlphaId shouldBe true
            message.getThird.getAlpha.hasAlphaName shouldBe true
            alphaId shouldBe message.getThird.getAlpha.getAlphaId.asJava
            alphaName shouldBe message.getThird.getAlpha.getAlphaName
        }
    }

    @ZoomOneOf(name = "beta")
    class ThirdBeta extends Third {
        @ZoomField(name = "beta_id")
        var betaId: UUID = _
        @ZoomField(name = "beta_name")
        var betaName: String = _

        override def shouldEqual(message: TestOneOfMessage): Unit = {
            super.shouldEqual(message)
            message.getThird.hasBeta shouldBe true
            message.getThird.getBeta.hasBetaId shouldBe true
            message.getThird.getBeta.hasBetaName shouldBe true
            betaId shouldBe message.getThird.getBeta.getBetaId.asJava
            betaName shouldBe message.getThird.getBeta.getBetaName
        }
    }

    class BaseFactory extends Factory[Base, TestOneOfMessage] {
        override def getType(proto: TestOneOfMessage): Class[_ <: Base] = {
            if (proto.hasFirst) classOf[First]
            else if (proto.hasSecond) classOf[Second]
            else if (proto.hasThird && proto.getThird.hasAlpha) classOf[ThirdAlpha]
            else if (proto.hasThird && proto.getThird.hasBeta) classOf[ThirdBeta]
            else throw new Exception("Unknown class")
        }
    }

}