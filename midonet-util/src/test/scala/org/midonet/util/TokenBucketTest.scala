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

package org.midonet.util

import scala.collection.JavaConversions._
import scala.collection.mutable

import org.slf4j.LoggerFactory

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, FeatureSpec}

@RunWith(classOf[JUnitRunner])
class TokenBucketTest extends FeatureSpec with Matchers {
    import TokenBucketTest._

    /* We build an HTB tree based on the id of each Bucket, obeying
     * the following rules:
     *   1) A child is always larger than its parent; and
     *   2) Siblings are in descending order.
     *
     *   For example, the ids [4 1 3 2] build the following hierarchy:
     *
     *                  +–––––+
     *                  |     | 0
     *                  |     |
     *                  +––+––+
     *                     |
     *             +–––––––+––––––+
     *             |              |
     *          +––+––+        +––+––+
     *        4 |     |        |     | 1
     *          |     |        |     |
     *          +–––––+        +––+––+
     *                            |
     *                    +–––––––+–––––––+
     *                    |               |
     *                 +––+––+         +––+––+
     *               3 |     |         |     | 2
     *                 |     |         |     |
     *                 +–––––+         +–––––+
     *
     *  We start by considering the root, which corresponds to 0:
     *      - 4 > 0, so we link a child of the root;
     *      - 1 < 4, so we link a sibling of the previous bucket;
     *      - 3 > 1, so we link a child of the previous bucket;
     *      - 2 < 3, so we link a sibling of the previous bucket.
     */

    (simulate scenario "one bucket can retrieve two times its capacity"
              during 2.ticks returning 16.tokens every tick) {
        id = 2                              //      ┌───┐
        max = 16.tokens                     //      │ 0 │
        verify {                            //      └───┘
            accumulated = 16.tokens         //        ↑
        }                                   //      __│__
    } {                                     //     │     │
        id = 1                              //   ┌───┐ ┌───┐
        max = 16.tokens                     //   │ 2 │ │ 1 │
        request = 16.tokens                 //   └───┘ └───┘
        verify {
            accumulated = 0.tokens
            out = 32.tokens
            share = 0.66
        }
    }

    (simulate scenario "distribution is fair" during 8.ticks
              returning 2.tokens every tick having 32.tokens inflight) {
        id = 0                                  //       ┌───┐
        verify {                                //       │ 0 │
            in = 16.tokens                      //       └───┘
            accumulated = 0.tokens              //         ↑
            share = 1                           //      ___│___
        }                                       //     │       │
    } {                                         //   ┌───┐   ┌───┐
        id = 4                                  //   │ 4 │   │ 1 │
        max = 16.tokens                         //   └───┘   └───┘
        request = 2.tokens                      //             ↑
        on = 1.tick                             //          ___│___
        verify {                                //         │       │
            accumulated = 0.tokens              //       ┌───┐   ┌───┐
            share = 0.5                         //       │ 3 │   │ 2 │
            out = 8.tokens                      //       └───┘   └───┘
        }
    } {
        id = 1
        max = 0
        verify {
            in = 8.tokens
            share = 0.5
        }
    } {
        id = 3
        max = 16.tokens
        initial = 8
        verify {
            in = 4.tokens
            accumulated = initial + in
            share = 0.25
        }
    } {
        id = 2
        max = 16.tokens
        initial = 8
        verify {
            in = 4.tokens
            accumulated = initial + in
            share = 0.25
        }
    }

    (simulate scenario "if all buckets are full, a sending bucket gets all tokens"
              during 3.ticks returning 1.tokens every tick
              having 3.tokens inflight) {
        id = 0                                  //       ┌───┐
        verify {                                //       │ 0 │
            in = 3.tokens                       //       └───┘
            accumulated = 0.tokens              //         ↑
        }                                       //      ___│___
    } {                                         //     │       │
        id = 4                                  //   ┌───┐   ┌───┐
        max = 3.tokens                          //   │ 4 │   │ 1 │
        initial = 3.tokens                      //   └───┘   └───┘
    } {                                         //             ↑
        id = 1                                  //          ___│___
        max = 0.tokens                          //         │       │
        verify {                                //       ┌───┐   ┌───┐
            in = 3.tokens                       //       │ 3 │   │ 2 │
        }                                       //       └───┘   └───┘
    } {
        id = 3
        max = 3.tokens
        initial = 0
        request = 1.token
        on = 1.tick
        verify {
            in = 3.tokens
            out = 3.tokens
            accumulated = 0.tokens
        }
    } {
        id = 2
        max = 3.tokens
        initial = 3.tokens
    }

    (simulate scenario "one token ping-pongs between leaf buckets"
              during 10.ticks returning 1.tokens every tick
              having 2.tokens inflight) {
        id = 2                                  //     ┌───┐
        max = 1.token                           //     │ 0 │
        request = 1.token                       //     └───┘
        on = 1.tick                             //       ↑
        verify {                                //    ___│___
            in = 5.tokens                       //   │       │
            out = 5.tokens                      // ┌───┐   ┌───┐
        }                                       // │ 2 │   │ 1 │
    } {                                         // └───┘   └───┘
        id = 1
        max = 1.token
        request = 1.token
        on = 1.tick
        verify {
            in = 5.tokens
            out = 5.tokens
        }
    }

    (simulate scenario "can't get tokens from 0-sized leafs"
              during 2.ticks returning 2.tokens every tick) {
        id = 0                                  //     ┌───┐
        max = 1.token                           //     │ 0 │
        initial = 1.token                       //     └───┘
    } {                                         //       ↑
        id = 1                                  //       │
        max = 0.token                           //     ┌───┐
        request = 1.token                       //     │ 1 │
        on = 1.tick                             //     └───┘
        verify {
            in = 0.tokens
            out = 0.tokens
        }
    }

    feature("TokenBucket correctly distributes tokens") {
        for (sim <- simulations) {
            scenario(sim.description) {
                sim.run()
            }
        }
    }

    feature("TokenBucketSystemRate correctly accounts for tokens") {
        scenario("without using a multiplier") {
            val c = new StatisticalCounter(1)
            val r = new TokenBucketSystemRate(c)

            r.getNewTokens should be (0)

            c.addAndGet(0, 3)
            r.getNewTokens should be (3)

            c.addAndGet(0, 2)
            r.getNewTokens should be (2)
        }

        scenario("using a multiplier") {
            val multiplier = 8
            val c = new StatisticalCounter(1)
            val r = new TokenBucketSystemRate(c, multiplier)

            val tb = TokenBucket.create(100, "root", r).link(100, "bucket")
            val bucket = new org.midonet.util.Bucket(tb, multiplier, c, 0, false)

            tb.addTokens(3)
            bucket.prepare()
            for (_ <- 0 until 3 * multiplier) {
                bucket.consumeToken() should be (true)
                c.addAndGet(0, 1)
            }
            bucket.done()

            c.getValue should be (3 * multiplier)
            r.getNewTokens should be (3)

            tb.addTokens(3)
            bucket.prepare()
            for (_ <- 0 until 3 * multiplier - 1) {
                bucket.consumeToken() should be (true)
                c.addAndGet(0, 1)
            }

            c.getValue should be (3 * multiplier * 2 - 1)
            r.getNewTokens should be (2)

            bucket.done()

            c.getValue should be (3 * multiplier * 2)
            r.getNewTokens should be (1)
        }
    }
}

object TokenBucketTest {
    class Bucket {
        var id: Int = _
        var name: Option[String] = None
        var bucket: TokenBucket = _

        var parent: Bucket = _
        val children = new mutable.ListBuffer[Bucket]()
        def hasChildren = !children.isEmpty

        // Bucket configuration
        var capacity = 0
        var initialTokens = 0

        // Simulation configuration
        var requestTokens = 0
        var withFrequency = 1

        // Expectations to assert against
        var accumulatedTokens = -1
        var tokensIn = -1
        var tokensOut = -1
        var share = -1d

        // State maintained during the simulation
        var requestTick = 0
        var tokensObtained = 0
    }

    class Simulation extends (Unit => Simulation) with Matchers {
        var description: String = _
        var buckets = mutable.ListBuffer[Bucket]()

        var totalTicks: Int = _
        var inFlight = 0
        var returnTokens: Int = _
        var returnAfterTicks: Int = _

        var returnTick = 0

        def apply(bucketConfiguration: Unit): Simulation = this

        def scenario(desc: String) = {
            description = desc
            this
        }

        def during(ticks: Int) = {
            totalTicks = ticks
            this
        }

        def returning(tokens: Int) = {
            returnTokens = tokens
            this
        }

        def every(ticks: Int) = {
            returnAfterTicks = ticks
            this
        }

        def having(tokens: Int) = {
            inFlight = tokens
            this
        }

        def inflight = this

        def root = buckets.head

        def run(): Unit = {
            if (buckets.head.id != 0) {
                val root = new Bucket()
                root.id = 0
                buckets.insert(0, new Bucket())
            }

            val root = buckets.head
            val rate = new TokenBucketTestRate
            buildHtb(root, buckets.drop(1), rate)

            val max = Math.max(
                        root.capacity,
                        (children(buckets) foldLeft 0)(_ + _.bucket.getCapacity))
            val tokens = (buckets foldLeft 0)(_ + _.initialTokens)
            val injected = max - inFlight - tokens

            root.bucket setCapacity max
            root.bucket addTokens root.initialTokens
            if (injected > 0)
                rate setNewTokens injected

            for (tick <- 0 until totalTicks) {
                if (returnTick == tick) {
                    val toReturn = Math.min(inFlight, returnTokens)
                    inFlight -= toReturn
                    rate addTokens toReturn
                    returnTick += returnAfterTicks
                }

                for (bucket <- children(buckets)) {
                    if (bucket.requestTick == tick) {
                        val tokens = bucket.bucket.tryGet(bucket.requestTokens)
                        inFlight += tokens
                        bucket.tokensObtained += tokens
                        bucket.requestTick += bucket.withFrequency
                    }
                }
            }

            assert()
        }

        private def assert(): Unit = {
            val globalTokensIn = tokensIn(root)
            for (bucket <- buckets) {
                try {
                    assert(bucket, globalTokensIn)
                } catch { case t: Throwable =>
                    val log = LoggerFactory.getLogger(classOf[TokenBucket])
                    log.error(s"Failed on bucket ${bucket.id}")
                    root.bucket.dumpToLog()
                    for (bucket <- buckets) {
                        log.error(s"Bucket ${bucket.id}: \n" +
                                s"${bucket.bucket.getNumTokens} tokens\n" +
                                s"${requestedTokens(bucket)} tokens requested\n" +
                                s"${bucket.tokensObtained} tokens obtained\n" +
                                s"${tokensIn(bucket)} tokens in")
                    }
                    throw t
                }
            }
        }

        private def assert(bucket: Bucket, globalTokensIn: Int): Unit = {
            if (bucket.accumulatedTokens >= 0)
                bucket.accumulatedTokens should be (bucket.bucket.getNumTokens)

            if (bucket.tokensOut >= 0)
                bucket.tokensOut should be (tokensOut(bucket))

            if (bucket.tokensIn >= 0)
                bucket.tokensIn should be (tokensIn(bucket))

            if (bucket.share >= 0d) {
                implicit val precision = Precision(0.01)
                val actualShare = tokensIn(bucket) / globalTokensIn.toDouble
                try {
                    (bucket.share ~= actualShare) should be (true)
                } catch { case t: Throwable =>
                    println (s"${bucket.share} was not $actualShare")
                    throw t
                }
            }
        }

        private def tokensIn(bucket: Bucket): Int =
            bucket.bucket.getNumTokens - bucket.initialTokens + (
                if (bucket.hasChildren)
                    (bucket.children foldLeft 0)(_ + tokensIn(_))
                else
                    bucket.tokensObtained)

        private def tokensOut(bucket: Bucket): Int =
            if (bucket.hasChildren)
                (bucket.children foldLeft 0)(_ + tokensOut(_))
            else
                bucket.tokensOut

        private def requestedTokens(bucket: Bucket) =
            (totalTicks / bucket.withFrequency) * bucket.requestTokens
    }

    implicit class toDSL(i: Int) {
        def tick = i
        def ticks = i
        def token = i
        def tokens = i
    }

    var simulations = mutable.ListBuffer[Simulation]()

    def simulate: Simulation = {
        var sim = new Simulation()
        simulations += sim
        sim
    }

    def ticks: Int =
        simulations.last.totalTicks

    def tick = 1

    var curBucket: Bucket = null

    def id: Int = curBucket.id
    def id_=(id: Int): Unit = {
        curBucket = new Bucket()
        simulations.last.buckets += curBucket
        curBucket.id = id
    }

    def name: String = curBucket.name.orNull
    def name_=(name: String): Unit =
        curBucket.name = Some(name)

    def initial: Int = curBucket.initialTokens
    def initial_=(tokens: Int): Unit =
        curBucket.initialTokens = tokens

    def max: Int = curBucket.capacity
    def max_=(tokens: Int): Unit =
        curBucket.capacity = tokens

    def request: Int = curBucket.requestTokens
    def request_=(tokens: Int): Unit =
        curBucket.requestTokens = tokens

    def on: Int = curBucket.withFrequency
    def on_=(ticks: Int): Unit =
        curBucket.withFrequency = ticks

    def verify(x: Unit): Unit = { }

    def out: Int = curBucket.tokensOut
    def out_=(tokens: Int): Unit =
        curBucket.tokensOut = tokens

    def in: Int = curBucket.tokensIn
    def in_=(tokens: Int): Unit =
        curBucket.tokensIn = tokens

    def accumulated: Int = curBucket.accumulatedTokens
    def accumulated_=(tokens: Int): Unit =
        curBucket.accumulatedTokens = tokens

    def share: Double = curBucket.share
    def share_=(share: Double): Unit =
        curBucket.share = share

    def buildHtb(root: Bucket, blueprint: Iterable[Bucket],
                 rate: TokenBucketFillRate): Unit = {
        root.bucket = TokenBucket.create(0, "root", rate)
        var mark = root
        for (b <- blueprint) {
            if (mark.id > b.id) {
                do mark = mark.parent while ((mark ne root) && mark.id > b.id)
            }

            val tb = mark.bucket.link(b.capacity,
                                      b.name getOrElse Integer.toString(b.id))
            tb.setCapacity(b.capacity)
            tb.addTokens(b.initialTokens)
            b.bucket = tb
            b.parent = mark
            mark.children.add(b)

            mark = b
        }
    }

    def children(buckets: Iterable[Bucket]): Iterable[Bucket] =
        buckets filterNot (_.hasChildren)

    case class Precision(precision: Double)
    implicit class ApproximateEquals(d: Double) {
        def ~=(d2: Double)(implicit p: Precision) = (d - d2).abs <= p.precision
    }
}