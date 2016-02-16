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

import java.io.File
import java.nio.file.{FileSystems, Files}
import java.time.format.DateTimeFormatter
import java.time.{ZoneId, Instant, LocalDateTime}
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
import org.midonet.midolman.containers.ContainerLogger.ContainerKey
import org.midonet.midolman.containers.{ContainerFlag, ContainerOp, ContainerLogger}
import org.midonet.util.UnixClock

@RunWith(classOf[JUnitRunner])
class ContainerLoggerTest extends FlatSpec with BeforeAndAfter
                                  with BeforeAndAfterAll with Matchers
                                  with GivenWhenThen {

    private var config: ContainerConfig = _
    private val log = Logger(LoggerFactory.getLogger(getClass))
    private val logDir = s"${FileUtils.getTempDirectory}/${UUID.randomUUID}"
    private val random = new Random
    private var dateTime: String = _

    protected override def beforeAll(): Unit = {
        System.setProperty("midolman.log.dir", logDir)
        System.setProperty(UnixClock.USE_MOCK_CLOCK_PROPERTY, "yes")
        Files.createDirectories(FileSystems.getDefault.getPath(logDir))
        dateTime = LocalDateTime.ofInstant(Instant.ofEpochMilli(UnixClock.MOCK.time),
                                           ZoneId.systemDefault())
                                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME)
    }

    before {
        config = new ContainerConfig(ConfigFactory.parseString(
            s"""
               |agent.containers.log_directory : containers
            """.stripMargin), ConfigFactory.empty())
    }

    after {
        val directory = new File(logDir)
        FileUtils.cleanDirectory(directory)
    }

    "Logger" should "compute the correct log path" in {
        Given("A container logger")
        val logger = new ContainerLogger(config, log)

        Then("The log path is set to the log dir and log file")
        logger.logDirectory shouldBe logDir
        logger.directoryPath.toString shouldBe s"$logDir/${config.logDirectory}"
    }

    "Logger" should "write container operations" in {
        Given("A container logger")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(s"$logDir/${config.logDirectory}")

        Then("The log directory does not exist")
        Files.exists(path) shouldBe false

        When("Logging a configuration")
        logger.log("A", ContainerOp(ContainerFlag.Created, "a"))

        Then("The log directory exists")
        Files.exists(path) shouldBe true

        And("The directory should contain the container log files")
        path.toFile.list() should contain only "a.A"

        When("Logging a configuration")
        logger.log("B", ContainerOp(ContainerFlag.Deleted, "b"))

        Then("The directory should contain the container log files")
        path.toFile.list() should contain only "a.A"

        When("Logging a configuration")
        logger.log("B", ContainerOp(ContainerFlag.Created, "b"))

        Then("The directory should contain the container log files")
        path.toFile.list() should contain allOf ("a.A", "b.B")

        When("Logging a configuration")
        logger.log("A", ContainerOp(ContainerFlag.Deleted, "a"))

        Then("The directory should contain the container log files")
        path.toFile.list() should contain only "b.B"
    }

    "Logger" should "handle an existing container" in {
        Given("A container logger")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(s"$logDir/${config.logDirectory}")

        Then("The log directory does not exist")
        Files.exists(path) shouldBe false

        When("Logging a configuration")
        logger.log("A", ContainerOp(ContainerFlag.Created, "a"))

        Then("The log directory exists")
        Files.exists(path) shouldBe true

        And("The directory should contain the container log files")
        path.toFile.list() should contain only "a.A"

        When("Logging the same configuration")
        logger.log("A", ContainerOp(ContainerFlag.Created, "a"))

        Then("The log directory exists")
        Files.exists(path) shouldBe true

        And("The directory should contain the container log files")
        path.toFile.list() should contain only "a.A"
    }

    "Logger" should "clear the log directory" in {
        Given("A logger and an existing log file")
        val logger = new ContainerLogger(config, log)
        val dirPath = FileSystems.getDefault.getPath(
            s"$logDir/${config.logDirectory}")
        val filePath = FileSystems.getDefault.getPath(
            s"$logDir/${config.logDirectory}/a.A")
        Files.createDirectory(dirPath)
        Files.createFile(filePath)

        Then("The log file exists")
        Files.exists(filePath) shouldBe true

        When("Clearing the log directory")
        logger.clear()

        Then("The log file should not exist")
        Files.exists(filePath) shouldBe false
    }

    "Logger" should "parse valid log files" in {
        Given("A logger and an existing log file")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(
            s"$logDir/${config.logDirectory}")
        Files.createDirectory(path)
        Files.createFile(FileSystems.getDefault.getPath(
            s"$logDir/${config.logDirectory}/a.A"))
        Files.createFile(FileSystems.getDefault.getPath(
            s"$logDir/${config.logDirectory}/b.B"))

        When("Reading the current containers")
        val containers = logger.currentContainers()

        Then("The containers should match the log file")
        containers should have size 2
        containers should contain allOf (ContainerKey(name = "a", `type` = "A"),
                                         ContainerKey(name = "b", `type` = "B"))
    }

    "Logger" should "return an empty list if log directory not exist" in {
        Given("A logger")
        val logger = new ContainerLogger(config, log)

        When("Reading the current containers")
        val containers = logger.currentContainers()

        Then("The containers should be empty")
        containers shouldBe empty
    }

    "Logger" should "return an empty list if log directory is empty" in {
        Given("A logger and an existing log directory")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(
            s"$logDir/${config.logDirectory}")
        Files.createDirectory(path)

        When("Reading the current containers")
        val containers = logger.currentContainers()

        Then("The containers should be empty")
        containers shouldBe empty
    }

    "Logger" should "return an empty list if log directory is a file" in {
        Given("A logger and an existing log directory")
        val logger = new ContainerLogger(config, log)
        val path = FileSystems.getDefault.getPath(
            s"$logDir/${config.logDirectory}")
        Files.createFile(path)

        When("Reading the current containers")
        val containers = logger.currentContainers()

        Then("The containers should be empty")
        containers shouldBe empty
    }

}
