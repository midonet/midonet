/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.conf

import java.util.UUID

import com.typesafe.config.{ConfigException, Config, ConfigFactory}
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{GivenWhenThen, FeatureSpecLike, Matchers}
import org.scalatest.concurrent.Eventually._
import org.slf4j.LoggerFactory
import rx.Observer

import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.conf.MidoConf._

@RunWith(classOf[JUnitRunner])
class MidoConfTest extends FeatureSpecLike
                            with Matchers
                            with GivenWhenThen
                            with CuratorTestFramework {

    val NODE = UUID.randomUUID()

    val SCHEMA = ConfigFactory.parseString("""
         |schemaVersion = 23
         |which.source = "schema"
        """.stripMargin)
    val LOGGER_CONF = ConfigFactory.parseString("""
         |loggers {
         |  class.a = "INFO"
         |  class.b = "INFO"
         |}
        """.stripMargin)

    val TEMPLATE_A = ConfigFactory.parseString("which.source = \"template a\"")
    val TEMPLATE_B = ConfigFactory.parseString("which.source = \"template b\"")
    val NODE_CONF = ConfigFactory.parseString("which.source = \"node\"")

    var configurator: MidoNodeConfigurator = _

    override def setup(): Unit = {
        configurator = new MidoNodeConfigurator(curator, UUID.randomUUID().toString)
        configurator.schema.setAsSchema(SCHEMA)
    }

    scenario("log levels are adjusted") {
        val watcher = new LoggerLevelWatcher()
        val conf = configurator.centralPerNodeConfig(NODE)
        conf.observable.subscribe(watcher)
        conf.mergeAndSet(LOGGER_CONF)

        val classA = LoggerFactory.getLogger("class.a")
        val classB = LoggerFactory.getLogger("class.b")
        val classC = LoggerFactory.getLogger("class.c")

        eventually { classA.isDebugEnabled should be (false) }
        eventually { classB.isDebugEnabled should be (false) }

        conf.set("loggers.class.a", "DEBUG")
        eventually { classA.isDebugEnabled should be (true) }

        classC.isTraceEnabled should be (false)
        conf.set("loggers.root", "TRACE")
        eventually { classC.isTraceEnabled should be (true) }

        conf.set("loggers.root", "DEBUG")
        eventually { classC.isTraceEnabled should be (false) }
    }

    scenario("zookeeper: begins as an empty source") {
        val path = "/testsource-" + UUID.randomUUID()
        val conf = new ZookeeperConf(curator, path)
        conf.get.isEmpty should be (true)
    }

    scenario("zookeeper: set and unset a key") {
        val path = "/testsource-" + UUID.randomUUID()
        val conf = new ZookeeperConf(curator, path)

        conf.set("a.key", "a value")
        eventually { conf.get.getString("a.key") should be ("a value") }

        conf.unset("a.key")
        conf.unset("a")
        eventually { conf.get.isEmpty should be (true) }
    }

    scenario("zookeeper: merge a config") {
        val path = "/testsource-" + UUID.randomUUID()
        val conf = new ZookeeperConf(curator, path)

        conf.set("a.key", "a value")
        conf.set("another.key", 42)
        eventually { conf.get.getString("a.key") should be ("a value") }
        eventually { conf.get.getInt("another.key") should be (42) }

        conf.mergeAndSet(ConfigFactory.parseString("another.key : 84"))
        eventually { conf.get.getInt("another.key") should be (84) }
        conf.get.getString("a.key") should be ("a value")
    }

    scenario("zookeeper: clear and set a config") {
        val path = "/testsource-" + UUID.randomUUID()
        val conf = new ZookeeperConf(curator, path)

        conf.set("a.key", "a value")
        conf.set("another.key", 42)
        eventually { conf.get.getString("a.key") should be ("a value") }
        eventually { conf.get.getInt("another.key") should be (42) }

        conf.clearAndSet(ConfigFactory.parseString("another.key : 84"))
        eventually {
            intercept[ConfigException.Missing] {
                conf.get.getString("a.key") should be ("a value")
            }
        }
        conf.get.getInt("another.key") should be (84)
    }

    private def makeObserver() = new Observer[Config] {
        var get: Config = _
        override def onNext(c: Config) = { get = c }
        override def onCompleted() = throw new Exception("should not complete")
        override def onError(t: Throwable) = throw t
    }

    scenario("zookeeper: can be observed across path delete() and create() ops") {
        val key = "a.key"
        val path = "/testsource-" + UUID.randomUUID()
        val conf = new ZookeeperConf(curator, path)
        val observer = makeObserver()

        conf.observable.subscribe(observer)
        eventually { observer.get should not be (null) }
        observer.get.isEmpty should be (true)

        conf.set(key, 1)
        eventually { observer.get should not be (null) }
        eventually { observer.get.getInt(key) should be (1) }

        curator.delete().forPath(path)
        eventually { observer.get.isEmpty should be (true) }
        conf.set(key, 1)
        eventually { observer.get.getInt(key) should be (1) }

        conf.set(key, 2)
        eventually { observer.get.getInt(key) should be (2) }
    }

    scenario("template based config can be observed across template assignments") {
        val observer = makeObserver()
        configurator.observableTemplateForNode(NODE).subscribe(observer)

        configurator.templateByName("default").mergeAndSet(TEMPLATE_A)
        eventually { observer.get.getString("which.source") should be ("template a") }

        configurator.templateByName("b").mergeAndSet(TEMPLATE_B)
        configurator.assignTemplate(NODE, "b")
        eventually { observer.get.getString("which.source") should be ("template b") }

        configurator.assignTemplate(NODE, "default")
        eventually { observer.get.getString("which.source") should be ("template a") }
    }

    scenario("cluster based config can be observed and is layered correctly") {
        val observer = makeObserver()
        configurator.observableCentralConfig(NODE).subscribe(observer)

        eventually { observer.get.getString("which.source") should be ("schema") }

        configurator.templateByName("default").mergeAndSet(TEMPLATE_A)
        eventually { observer.get.getString("which.source") should be ("template a") }

        configurator.templateByName("b").mergeAndSet(TEMPLATE_B)
        configurator.assignTemplate(NODE, "b")
        eventually { observer.get.getString("which.source") should be ("template b") }

        configurator.centralPerNodeConfig(NODE).mergeAndSet(NODE_CONF)
        eventually { observer.get.getString("which.source") should be ("node") }

        configurator.centralPerNodeConfig(NODE).unset("which.source")
        eventually { observer.get.getString("which.source") should be ("template b") }

        configurator.templateByName("b").unset("which.source")
        eventually { observer.get.getString("which.source") should be ("template a") }

        configurator.templateByName("default").unset("which.source")
        eventually { observer.get.getString("which.source") should be ("schema") }
    }

    scenario("reads and updates schemas") {
        val schema = configurator.schema

        schema.get.getString("which.source") should be ("schema")

        var newSchema = schema.get.withValue("which.source", "new schema")
        schema.setAsSchema(newSchema) should be (false)
        schema.get.getString("which.source") should be ("schema")

        newSchema = newSchema.withValue("schemaVersion", 24)
        configurator.schema.setAsSchema(newSchema) should be (true)
        eventually { schema.get.getString("which.source") should be ("new schema") }
    }
}
