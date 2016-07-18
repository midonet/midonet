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

package org.midonet.cluster.data.storage.metrics

import com.codahale.metrics.MetricRegistry

import org.midonet.cluster.data.storage.ZookeeperObjectMapper

object ZoomMetricRegister {

    def apply(zoom: ZookeeperObjectMapper,
              registry: MetricRegistry): ZoomMetricRegister =
        new ZoomMetricRegister(zoom, registry)
}

class ZoomMetricRegister(zoom: ZookeeperObjectMapper,
                         override val registry: MetricRegistry)
    extends MetricRegister {

    def registerAll(): Unit = {
        registerGauge(gauge {
            zoom.zkConnectionState _
        }, "zkConnectionState")

        registerGauge(gauge {
            zoom.startedClassObservableCount _
        }, "typeObservableCount")

        registerGauge(gauge {
            zoom.startedObjectObservableCount _
        }, "objectObservableCount")

        registerGauge(gauge { () =>
            val res = new StringBuilder()
            for ((clazz, count) <- zoom.objectObservableCounters) {
                res.append(clazz + ":" + count + "\n")
            }
            res.toString()
        }, "objectObservableCountPerType")

        registerGauge(gauge { () =>
            val classes = zoom.startedClassObservables
            classes.foldLeft(
                new StringBuilder)((builder, clazz) => {
                builder.append(clazz.getName + "\n")
            }).toString()
        }, "typeObservableList")
    }
}
