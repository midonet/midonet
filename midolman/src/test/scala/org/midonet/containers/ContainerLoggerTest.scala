/*
 * Copyright 2016 Midokura SARL
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

package org.midonet.containers

import java.nio.file.{FileSystems, Files}
import java.util.UUID

import scala.util.Random

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.Logger

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest._
import org.scalatest.junit.JUnitRunner
import org.slf4j.LoggerFactory

import org.midonet.midolman.config.ContainerConfig
import org.midonet.midolman.containers.ContainerLogger.Key
import org.midonet.midolman.containers.{ContainerFlag, ContainerConfiguration, ContainerLogger}
import org.midonet.util.UnixClock

@RunWith(classOf[JUnitRunner])
class ContainerLoggerTest extends FlatSpec with BeforeAndAfter
                                  with BeforeAndAfterAll with Matchers
                                  with GivenWhenThen {

    private var config: ContainerConfig = _
    private val log = Logger(LoggerFactory.getLogger(getClass))
    private val logDir = s"${FileUtils.getTempDirectory}/${UUID.randomUUID}"
    private val random = new Random

    protected override def beforeAll(): Unit = {
        System.setProperty("midolman.log.dir", logDir)
        System.setProperty(UnixClock.USE_MOCK_CLOCK_PROPERTY, "yes")
        Files.createDirectories(FileSystems.getDefault.getPath(logDir))
    }

    before {
        config = new ContainerConfig(ConfigFactory.parseString(
            s"""
               |agent.containers.log_file_name : log-${UUID.randomUUID}.log
            """.stripMargin), ConfigFactory.empty())
    }

    "Logger" should "compute the correct log path" in {
        Given("A container logger")
        val logger = new ContainerLogger(config, log)

        Then("The log path is set to the log dir and log file")
        logger.logPath.toString shouldBe s"$logDir/${config.logFileName}"
    }

    "Logger" should "write configuration" in {
        Given("A container logger")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(s"$logDir/${config.logFileName}")

        Then("The log file does not exist")
        Files.exists(path) shouldBe false

        When("Logging a configuration")
        logger.log("A", ContainerConfiguration("a", ContainerFlag.Created,
                                               "some-config"))

        Then("The log file exists")
        Files.exists(path) shouldBe true

        And("The file should contain the log entry")
        val str1 = FileUtils.readFileToString(path.toFile)
        str1 shouldBe "1970-01-01T01:00:00|A|a|CREATED|some-config\n"

        When("Logging a configuration")
        logger.log("B", ContainerConfiguration("b", ContainerFlag.Deleted,
                                               "other-config"))

        Then("The file should contain the log entry")
        val str2 = FileUtils.readFileToString(path.toFile)
        str2 shouldBe "1970-01-01T01:00:00|A|a|CREATED|some-config\n" +
                      "1970-01-01T01:00:00|B|b|DELETED|other-config\n"
    }

    "Logger" should "fail to write very long configurations" in {
        Given("A container logger")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(s"$logDir/${config.logFileName}")

        When("Logging a log configuration")
        logger.log("A", ContainerConfiguration("a", ContainerFlag.Created,
                                               random.nextString(3000)))

        Then("The log file exists")
        Files.exists(path) shouldBe true

        And("The file should not contain any entries")
        FileUtils.readFileToString(path.toFile) shouldBe ""
    }

    "Logger" should "clear a closed log file" in {
        Given("A logger and an existing log file")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(s"$logDir/${config.logFileName}")
        Files.createFile(path)

        Then("The log file exists")
        Files.exists(path) shouldBe true

        When("Clearing the log file")
        logger.clear()

        Then("The log file should not exist")
        Files.exists(path) shouldBe false
    }

    "Logger" should "clear an open log file" in {
        Given("A logger and an existing log file")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(s"$logDir/${config.logFileName}")

        When("Logging a log configuration")
        logger.log("A", ContainerConfiguration("a", ContainerFlag.Created,
                                               "some-config"))


        Then("The log file exists")
        Files.exists(path) shouldBe true

        When("Clearing the log file")
        logger.clear()

        Then("The log file should not exist")
        Files.exists(path) shouldBe false
    }

    "Logger" should "parse valid log files" in {
        Given("A logger and an existing log file")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(s"$logDir/${config.logFileName}")
        FileUtils.writeStringToFile(
            path.toFile,
            "1970-01-01T01:00:00|A|a|CREATED|some-config\n" +
            "1970-01-01T01:00:00|B|b|CREATED|other-config\n")

        When("Reading the current containers")
        val containers = logger.currentContainers()

        Then("The containers should match the log file")
        containers should have size 2
        containers shouldBe Map(Key("A", "a") -> "some-config",
                                Key("B", "b") -> "other-config")
    }

    "Logger" should "return an empty map if file does not exist" in {
        Given("A logger and an existing log file")
        val logger = new ContainerLogger(config, log)

        When("Reading the current containers")
        val containers = logger.currentContainers()

        Then("The containers should be empty")
        containers shouldBe empty
    }

    "Logger" should "fail to read invalid log lines" in {
        Given("A logger and an existing log file")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(s"$logDir/${config.logFileName}")
        FileUtils.writeStringToFile(
            path.toFile,
            "1970-01-01T01:00:00|A|a|CREATED|some-config\n" +
            "1970-01-01T01:00:00|C|c|CREATED\n" +
            "1970-01-01T01:00:00|B|b|CREATED|other-config\n")

        When("Reading the current containers")
        val containers = logger.currentContainers()

        Then("The containers should match the log file")
        containers should have size 2
        containers shouldBe Map(Key("A", "a") -> "some-config",
                                Key("B", "b") -> "other-config")
    }

    "Logger" should "fail to read invalid log operations" in {
        Given("A logger and an existing log file")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(s"$logDir/${config.logFileName}")
        FileUtils.writeStringToFile(
            path.toFile,
            "1970-01-01T01:00:00|A|a|CREATED|some-config\n" +
            "1970-01-01T01:00:00|C|c|INVALID|config\n" +
            "1970-01-01T01:00:00|B|b|CREATED|other-config\n")

        When("Reading the current containers")
        val containers = logger.currentContainers()

        Then("The containers should match the log file")
        containers should have size 2
        containers shouldBe Map(Key("A", "a") -> "some-config",
                                Key("B", "b") -> "other-config")
    }

    "Logger" should "aggregate the log operations by container" in {
        Given("A logger and an existing log file")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(s"$logDir/${config.logFileName}")
        FileUtils.writeStringToFile(
            path.toFile,
            "1970-01-01T01:00:00|A|a|CREATED|some-config-1\n" +
            "1970-01-01T01:00:00|A|a|CREATED|some-config-2\n" +
            "1970-01-01T01:00:00|B|b|CREATED|some-config-3\n" +
            "1970-01-01T01:00:00|B|b|DELETED|some-config-4\n" +
            "1970-01-01T01:00:00|C|c|CREATED|some-config-5\n" +
            "1970-01-01T01:00:00|C|c|CREATED|some-config-6\n" +
            "1970-01-01T01:00:00|B|b|CREATED|some-config-7\n" +
            "1970-01-01T01:00:00|A|a|DELETED|some-config-8\n" +
            "1970-01-01T01:00:00|D|d|CREATED|some-config-9\n" +
            "1970-01-01T01:00:00|E|e|CREATED|some-config-10\n" +
            "1970-01-01T01:00:00|F|f|DELETED|some-config-11\n" +
            "1970-01-01T01:00:00|E|e|DELETED|some-config-12\n" +
            "1970-01-01T01:00:00|B|b|CREATED|some-config-13\n")

        When("Reading the current containers")
        val containers = logger.currentContainers()

        Then("The containers should match the log file")
        containers should have size 3
        containers shouldBe Map(Key("B", "b") -> "some-config-13",
                                Key("C", "c") -> "some-config-6",
                                Key("D", "d") -> "some-config-9")
    }

}
