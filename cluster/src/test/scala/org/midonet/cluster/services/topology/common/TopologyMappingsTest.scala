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

package org.midonet.cluster.services.topology.common

import com.google.protobuf.Message
import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.models.Topology

@RunWith(classOf[JUnitRunner])
class TopologyMappingsTest extends FeatureSpec with Matchers {

    val types: List[Topology.Type] = Topology.Type.values().toList
        .sortBy({_.name})

    val classes: List[Class[_ <: Message]] = classOf[Topology].getClasses.toList
        .filterNot(_.getSimpleName.matches("(.*)OrBuilder"))
        .filterNot(_.getSimpleName.matches("Type"))
        .sortBy({_.getSimpleName})
        .asInstanceOf[List[Class[_ <: Message]]]

    feature("map topology classes to type ids")
    {
        scenario("sanity checks") {
            types.length should be (Topology.Type.values.length)
            classes.length should be (Topology.Type.values.length)
            TopologyMappings.typeToKlass.size should be (Topology.Type.values.length)
        }

        scenario("convert from type to class") {
            types.zip(classes)
                .forall(x => {TopologyMappings.klassOf(x._1) == Some(x._2)}) shouldBe true
        }

        scenario("convert from class to type") {
            classes.zip(types)
                 .forall(x => {TopologyMappings.typeOf(x._1) == Some(x._2)}) shouldBe true
        }
    }
}


