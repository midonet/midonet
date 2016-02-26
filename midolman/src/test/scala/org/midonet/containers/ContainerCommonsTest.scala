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

package org.midonet.containers

import java.io.{IOException, FileNotFoundException, File}
import java.util.UUID

import org.apache.commons.io.FileUtils
import org.junit.runner.RunWith
import org.scalatest.{Matchers, GivenWhenThen, FlatSpec}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class ContainerCommonsTest extends FlatSpec with Matchers with GivenWhenThen {

    class TestContainer extends ContainerCommons
    class CommandsContainer extends ContainerCommons {
        var results = Seq.empty[String]
        val commands = Map("+1" -> (() => { 0 }),
                           "+2" -> (() => { 0 }),
                           "+3" -> (() => { 0 }),
                           "+4" -> (() => { -1 }),
                           "+5" -> (() => { throw new Exception() }),
                           "-1" -> (() => { 0 }),
                           "-2" -> (() => { 0 }),
                           "-3" -> (() => { 0 }),
                           "-4" -> (() => { -1 }),
                           "-5" -> (() => { throw new Exception() }))

        override def execute(cmd: String): Int = {
            results = results :+ cmd
            commands(cmd)()
        }
    }

    "Container commons" should "write to file" in {
        Given("A container")
        val container = new TestContainer

        And("A file name")
        val path = s"${FileUtils.getTempDirectory}/${UUID.randomUUID}"

        When("Writing to the file")
        container.writeFile("test-line1\ntest-line2", path)

        Then("The file should be created")
        val file = new File(path)
        file.exists() shouldBe true

        And("The file should contain the conte nt")
        val lines = FileUtils.readLines(file)
        lines.size() shouldBe 2
        lines.get(0) shouldBe "test-line1"
        lines.get(1) shouldBe "test-line2"

        FileUtils.forceDelete(file)
    }

    "Container commons" should "fail to write to invalid file name" in {
        Given("A container")
        val container = new TestContainer

        And("A file name")
        val path = s"${FileUtils.getTempDirectory}/\u0000"

        When("Writing to the file")
        intercept[FileNotFoundException] {
            container.writeFile("test-line1\ntest-line2", path)
        }
    }

    "Container commons" should "execute valid successful command" in {
        Given("A container")
        val container = new TestContainer

        When("Executing a valid command")
        val code = container.execute(s"ls ${FileUtils.getTempDirectory}")

        Then("The return code should be zero")
        code shouldBe 0
    }

    "Container commons" should "return the command output" in {
        Given("A container")
        val container = new TestContainer

        When("Executing a valid command")
        val (code, out) =
            container.executeWithOutput(s"ls ${FileUtils.getTempDirectory}")

        Then("The return code should be zero")
        code shouldBe 0
        out should not be empty
    }

    "Container commons" should "execute valid failed command" in {
        Given("A container")
        val container = new TestContainer

        When("Executing a valid command")
        val code = container.execute(s"ls ${UUID.randomUUID()}")

        Then("The return code should not be zero")
        code should not be 0
    }

    "Container commons" should "execute invalid command" in {
        Given("A container")
        val container = new TestContainer

        When("Executing an invalid command")
        intercept[IOException] {
            container.execute(UUID.randomUUID().toString)
        }
    }

    "Container commons" should "execute sequence of valid commands" in {
        Given("A container")
        val container = new CommandsContainer

        When("Executing valid commands")
        container.executeCommands(Seq(("+1","-1"), ("+2","-2"), ("+3","-3")))

        Then("The command should be executed")
        container.results shouldBe Seq("+1","+2","+3")
    }

    "Container commons" should "rollback command after error code" in {
        Given("A container")
        val container = new CommandsContainer

        When("Executing a command that returns an error code")
        intercept[Exception] {
            container.executeCommands(Seq(("+1", "-1"), ("+4", "-2"), ("+3", "-3")))
        }

        Then("The failed and previous commands should be rolled back")
        container.results shouldBe Seq("+1","+4","-2","-1")
    }

    "Container commons" should "rollback command after exception" in {
        Given("A container")
        val container = new CommandsContainer

        When("Executing a command that returns an error code")
        intercept[Exception] {
            container.executeCommands(Seq(("+1", "-1"), ("+5", "-2"), ("+3", "-3")))
        }

        Then("The failed and previous commands should be rolled back")
        container.results shouldBe Seq("+1","+5","-2","-1")
    }

    "Container commons" should "ignore null rollback comand" in {
        Given("A container")
        val container = new CommandsContainer

        When("Executing a command that returns an error code")
        intercept[Exception] {
            container.executeCommands(Seq(("+1", "-1"), ("+2", "-2"), ("+4", null)))
        }

        Then("The failed and previous commands should be rolled back")
        container.results shouldBe Seq("+1","+2","+4","-2","-1")
    }

    "Container commons" should "ignore error codes on rollback commands" in {
        Given("A container")
        val container = new CommandsContainer

        When("Executing a command that returns an error code")
        intercept[Exception] {
            container.executeCommands(Seq(("+1", "-1"), ("+2", "-4"), ("+4", "-3")))
        }

        Then("The failed and previous commands should be rolled back")
        container.results shouldBe Seq("+1","+2","+4","-3","-4","-1")
    }


    "Container commons" should "stop on rollback commands that throw exceptions" in {
        Given("A container")
        val container = new CommandsContainer

        When("Executing a command that returns an error code")
        intercept[Exception] {
            container.executeCommands(Seq(("+1", "-1"), ("+2", "-5"), ("+4", "-3")))
        }

        Then("The failed and previous commands should be rolled back")
        container.results shouldBe Seq("+1","+2","+4","-3","-5")
    }

}
