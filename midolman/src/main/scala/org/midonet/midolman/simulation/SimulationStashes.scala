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

package org.midonet.midolman.simulation

import java.util.ArrayList

import org.midonet.midolman.PacketWorkflow._
import org.midonet.midolman.simulation.Simulator.{ContinueWith, ForkAction}
import org.midonet.odp.FlowMatch
import org.midonet.util.concurrent.{InstanceStash1, InstanceStash2, InstanceStash0}

object SimulationStashes {
    val Stack = new InstanceStash0[ArrayList[SimulationResult]](
            () => new ArrayList[SimulationResult])

    val Fork = new InstanceStash2[ForkAction, SimulationResult, SimulationResult](
            () => ForkAction(null, null),
            (fork, a, b) => {
                fork.first = a
                fork.second = b
            })

    val Continuations = new InstanceStash1[ContinueWith, SimStep](
            () => ContinueWith(null),
            (cont, step) => {
                cont.step = step
            })

    val PooledMatches = new InstanceStash1[FlowMatch, FlowMatch](
            () => new FlowMatch(),
            (fm, template) => fm.reset(template))

    def reUpStashes(): Unit = {
        Stack.reUp()
        Fork.reUp()
        PooledMatches.reUp()
        Continuations.reUp()
    }
}
