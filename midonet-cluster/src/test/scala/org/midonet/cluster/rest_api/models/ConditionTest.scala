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

package org.midonet.cluster.rest_api.models

import javax.validation.{ConstraintViolation, Validation}

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.util.Range

@RunWith(classOf[JUnitRunner])
class ConditionTest extends FeatureSpec with Matchers {

    val validator = Validation.buildDefaultValidatorFactory().getValidator

    val EmptySet = new java.util.HashSet[ConstraintViolation[Condition]]()

    private def check(expected: Boolean)(transform: Condition => Unit): Unit = {
        val condition = new Condition
        transform(condition)
        val violations = validator.validate(condition)
        if (expected) {
            violations should be (EmptySet)
        } else {
            violations shouldNot be (EmptySet)
        }
    }

    private def checkFail = check(false) _

    private def checkPass = check(true) _

    feature("Condition fields are validated") {
        scenario("Empty condition is valid") {
            checkPass( _ => Unit )
        }

        scenario("Network address length is between 0 and 32") {
            val setters: List[(Condition, Int) => Unit] = List(
                (c, v) => c.nwSrcLength = v,
                (c, v) => c.nwDstLength = v
            )

            for (s <- setters) {
                checkPass( s(_, 0) )
                checkPass( s(_, 32) )
                checkFail( s(_, -1) )
                checkFail( s(_, 33) )
            }
        }

        scenario("Ethertype is in range") {
            checkPass( _.dlType = 0x600 )
            checkPass( _.dlType = 0xFFFF )
            checkFail( _.dlType = 0x5FF )
            checkFail( _.dlType = 0x10000 )
            checkFail( _.dlType = 0 )
        }

        scenario("Mac addresses are validated") {
            val setters: List[(Condition, String) => Unit] = List(
                (c, v) => c.dlSrc = v,
                (c, v) => c.dlDst = v
            )

            for (s <- setters) {
                checkPass( s(_, null) )
                checkPass( s(_, "0:0:0:0:0:0") )
                checkPass( s(_, "FF:ff:FF:ff:FF:FF") )
                checkFail( s(_, "") )
                checkFail( s(_, "1:2:3:4:5") )
                checkFail( s(_, "1:2:3:4:5:6:7") )
                checkFail( s(_, "1.2.3.4.5.6") )
            }
        }

        scenario("Mac addresses masks are validated") {
            val setters: List[(Condition, String) => Unit] = List(
                (c, v) => c.dlSrcMask = v,
                (c, v) => c.dlDstMask = v
            )

            for (s <- setters) {
                checkPass( s(_, null) )
                checkPass( s(_, "ffff.0000.ffff") )
                checkPass( s(_, "0000.0000.0000") )
                checkFail( s(_, "0.0.0") )
                checkFail( s(_, "0000.0000.z000") )
                checkFail( s(_, "") )
            }
        }

        scenario("L4 port ranges should be valid") {
            val setters: List[(Condition, (Int, Int)) => Unit] = List(
                (c, v) => c.tpSrc = new Range[Integer](v._1, v._2),
                (c, v) => c.tpDst = new Range[Integer](v._1, v._2)
            )

            for (s <- setters) {
                checkPass( s(_, (80, 80)) )
                checkPass( s(_, (100, 101)) )
                checkPass( s(_, (1, 0xffff)) )
                checkFail( s(_, (0, 0)) )
                checkFail( s(_, (-1, 1)) )
            }
        }
    }

}
