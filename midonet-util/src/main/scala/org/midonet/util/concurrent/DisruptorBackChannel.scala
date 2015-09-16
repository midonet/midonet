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

package org.midonet.util.concurrent

/**
 * An interface to be used together with the BackChannelEventProcessor.
 * The shouldProcess() method informs whether there is work to be processed
 * on this back channel and it should be thread-safe. The process() method
 * does the actual work and can be called even when shouldProcess() returns
 * false.
 */
trait DisruptorBackChannel {
    def shouldProcess(): Boolean
    def process(): Unit
}
