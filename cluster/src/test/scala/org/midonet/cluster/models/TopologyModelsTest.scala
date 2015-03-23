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
package org.midonet.cluster.models

import com.google.common.base.CaseFormat
import org.scalatest.{FeatureSpec, Matchers}

class TopologyModelsTest extends FeatureSpec with Matchers {
    def upperToCamel(str: String) =
        CaseFormat.UPPER_UNDERSCORE.to(CaseFormat.UPPER_CAMEL, str)

    feature("Topology models") {
        scenario("Defined messages registered in enumeration") {
            val types = Topology.Type.values().toSet[Topology.Type]
                .map(_.name())
                .map(upperToCamel)
                .map(_.toLowerCase)
            val messages = classOf[Topology].getClasses.toSet[Class[_]]
                .map(_.getSimpleName)
                .filterNot(_.matches("(.*)OrBuilder"))
                .filterNot(_ == "Type")
                .map(_.toLowerCase)

            // Note: 'LowerCase' is used to deal with 'IPAddrGroup'
            // instead of 'IpAddrGroup'
            (messages -- types) shouldBe Set[String]()
        }
    }
}
