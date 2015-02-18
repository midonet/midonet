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
package org.midonet.midolman.flows

import org.jctools.queues.{SpscArrayQueue, QueueFactory}
import org.jctools.queues.spec.ConcurrentQueueSpec._
import org.midonet.midolman.FlowController.FlowRemoveCommand

class FlowEjector(val maxPendingRequests: Int) {
    private val queue = new SpscArrayQueue[FlowRemoveCommand](maxPendingRequests)

    def size: Int = queue.size()

    def canEject: Boolean = queue.size() < maxPendingRequests

    def eject(flowDelete: FlowRemoveCommand): Boolean = queue.offer(flowDelete)

    def peek(): FlowRemoveCommand = queue.peek()

    def poll(): FlowRemoveCommand = queue.poll()
}
