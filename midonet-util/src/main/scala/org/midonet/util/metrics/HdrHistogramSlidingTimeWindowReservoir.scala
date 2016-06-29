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
package org.midonet.util.metrics

import java.io.OutputStream

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock

import com.codahale.metrics.{Clock, Reservoir, Snapshot}
import org.HdrHistogram.Histogram
import org.HdrHistogram.Recorder

import org.midonet.Util

/*
 * Metrics latency reservoir that provides a sliding window of latency
 * statistics.
 * The time window is broken into a number of buckets, each of which is a
 * HdrHistogram recorder. When reading the latency stats, the values from
 * the last N buckets are returned, where N is the number of buckets needed
 * to cover the time window.
 */
class HdrHistogramSlidingTimeWindowReservoir(window: Long, unit: TimeUnit,
                                             stepLength: Long, stepUnit: TimeUnit,
                                             clock: Clock)
        extends Reservoir {
    val stepNanos = Util.findPreviousPositivePowerOfTwo(
        TimeUnit.NANOSECONDS.convert(stepLength, stepUnit))
    val stepShift = Util.highestBit(stepNanos)

    val bucketsPerWindow = Math.ceil(
        TimeUnit.NANOSECONDS.convert(window, unit).toDouble / stepNanos).toInt
    val buckets = Util.findNextPositivePowerOfTwo(bucketsPerWindow)

    val bucketsMask = buckets - 1
    val measurements = new Array[Histogram](buckets.toInt)
    val recorder = new Recorder(3600000000L, 3)
    var lastTimeSlot = 0L
    val snapshotLock = new ReentrantLock()

    resetBuckets

    val snapshotHistogram = new Histogram(3600000000L, 3)
    val tmpHistogram = new Histogram(3600000000L, 3)
    val snapshot = new Snapshot() {
        def getValue(quantile: Double): Double =
            snapshotHistogram.getValueAtPercentile(quantile)

        def size: Int = snapshotHistogram.getTotalCount.toInt
        def getMax: Long = snapshotHistogram.getMaxValue
        def getMean: Double = snapshotHistogram.getMean
        def getMin: Long = snapshotHistogram.getMinValue
        def getStdDev: Double = snapshotHistogram.getStdDeviation

        // this is a noop, because you can't get all values from hdr histogram
        val getValues: Array[Long] = new Array[Long](0)

        def dump(output: OutputStream): Unit = {
            // noop, you can't get all values from hdr histogram
        }
    }

    override def size: Int = getSnapshot.size

    override def update(value: Long): Unit = {
        val currentTimeSlot = clock.getTick() >> stepShift
        getCurrentRecorder(currentTimeSlot).recordValue(value)
    }

    override def getSnapshot: Snapshot = {
        snapshotHistogram.reset()

        snapshotLock.lock()
        try {
            val currentTimeSlot = clock.getTick() >> stepShift

            // copy current bucket values into snapshot
            val recorder = getCurrentRecorder(currentTimeSlot)
            recorder.getIntervalHistogramInto(tmpHistogram)


            val bucket = (currentTimeSlot & bucketsMask).toInt
            measurements(bucket).add(tmpHistogram)
            snapshotHistogram.add(measurements(bucket))

            // copy previous buckets that make up window into snapshot
            var i = (currentTimeSlot - bucketsPerWindow) + 1
            while (i < currentTimeSlot) {
                val bucket = (i & bucketsMask).toInt
                if (measurements(bucket).getTotalCount > 0) {
                    snapshotHistogram.add(measurements(bucket))
                }
                i += 1
            }
        } finally {
            snapshotLock.unlock()
        }

        snapshot
    }

    private def getCurrentRecorder(currentTimeSlot: Long): Recorder = {
        if (currentTimeSlot != lastTimeSlot &&
                snapshotLock.tryLock()) {
            try {
                if (currentTimeSlot - lastTimeSlot > buckets) {
                    resetBuckets
                    lastTimeSlot = currentTimeSlot
                }
                while (lastTimeSlot < currentTimeSlot) {
                    val lastBucket = (lastTimeSlot & bucketsMask).toInt
                    recorder.getIntervalHistogramInto(tmpHistogram)
                    measurements(lastBucket).add(tmpHistogram)

                    lastTimeSlot += 1
                    val currentBucket = (lastTimeSlot & bucketsMask).toInt
                    measurements(currentBucket).reset()
                    recorder.reset()
                }
            } finally {
                snapshotLock.unlock()
            }
        }
        recorder
    }

    private def resetBuckets(): Unit = {
        var i = 0
        while (i < buckets) {
            if (measurements(i) == null) {
                measurements(i) = new Histogram(3600000000L, 3)
            }
            measurements(i).reset()
            i += 1
        }
    }
}
