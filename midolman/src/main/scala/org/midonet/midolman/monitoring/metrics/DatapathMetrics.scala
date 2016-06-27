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

package org.midonet.midolman.monitoring.metrics

import com.codahale.metrics.MetricRegistry
import com.codahale.metrics.MetricRegistry.name

trait DatapathMeter

class DatapathMetrics(val registry: MetricRegistry) {

    val flowsCreated = registry.meter(
        name(classOf[DatapathMeter], "flows", "created"))

    val flowCreateErrors = registry.meter(
        name(classOf[DatapathMeter], "flows", "createErrors"))

    val flowCreateDupes = registry.meter(
        name(classOf[DatapathMeter], "flows", "createDupeErrors"))

    val flowsDeleted = registry.meter(
        name(classOf[DatapathMeter], "flows", "deleted"))

    val flowDeleteErrors = registry.meter(
        name(classOf[DatapathMeter], "flows", "deleteErrors"))

}

