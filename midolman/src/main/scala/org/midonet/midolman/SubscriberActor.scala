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

package org.midonet.midolman

import akka.actor.Actor
import org.midonet.midolman.DatapathController.DatapathReady
import org.midonet.midolman.topology.LocalPortActive

trait SubscriberActor extends Actor {

    def subscribedClasses: Seq[Class[_]]

    override def preStart() {
        super.preStart()
        subscribedClasses.foreach(
            this.context.system.eventStream.subscribe(this.self, _))
    }

    override def postStop() {
        subscribedClasses.foreach(
            this.context.system.eventStream.unsubscribe(this.self, _))
        super.postStop()
    }
}

trait DatapathReadySubscriberActor extends SubscriberActor {
    override def subscribedClasses = Seq(classOf[DatapathReady])
}

trait LocalPortActiveSubscriberActor extends SubscriberActor {
    override def subscribedClasses = Seq(classOf[LocalPortActive])
}