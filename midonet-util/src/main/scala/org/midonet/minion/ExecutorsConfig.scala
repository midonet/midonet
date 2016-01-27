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

package org.midonet.minion

import java.util.concurrent.TimeUnit

import com.typesafe.config.Config

class ExecutorsConfig(val conf: Config, val prefix: String) {

    def threadPoolName = conf.getString(s"$prefix.executors.thread_pool_name")

    def threadPoolSize = conf.getInt(s"$prefix.executors.max_thread_pool_size")

    def threadPoolShutdownTimeoutMs =
            conf.getDuration(s"$prefix.executors.thread_pool_shutdown_timeout",
                             TimeUnit.MILLISECONDS)

}
