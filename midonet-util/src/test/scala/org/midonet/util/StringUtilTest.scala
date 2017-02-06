/*
 * Copyright 2017 Midokura SARL
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
package org.midonet.util

import java.io.{BufferedWriter, StringWriter}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}
import org.slf4j.{Logger, LoggerFactory}

@RunWith(classOf[JUnitRunner])
class StringUtilTest extends FeatureSpec with GivenWhenThen with Matchers {

  private val Log = LoggerFactory.getLogger("StringUtilTest")

  feature("Check long serialization") {
    scenario("Array of long converted v2") {
      Given("An input array of long values")
        val input = List[Long](Long.MinValue, Long.MinValue/13, 10, 9, 1, 0,
          -1, Long.MaxValue/17, Long.MaxValue)

      When("Transforming them to text")
        val outputAsString = input.map ( x => {
          val writer = new StringWriter()
          val bufferedWriter = new BufferedWriter(writer)
          StringUtil.append(bufferedWriter, x)
          bufferedWriter.flush
          writer.getBuffer.toString
        })

      Then("The transformation is equal to default one")
        var i = 0
        while (i < input.length) {
          assert(input(i).toString.equals(outputAsString(i)), "" + input(i) +
            " is not equal to " + outputAsString(i))
          i += 1
        }
    }
  }
}