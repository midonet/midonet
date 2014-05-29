/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.util.collection

import org.junit.runner.RunWith
import org.scalatest.{FeatureSpec, BeforeAndAfterEach, Matchers}
import org.scalatest.junit.JUnitRunner
import scala.util.Random
import scala.collection.mutable

@RunWith(classOf[JUnitRunner])
class WeightedSelectorTest extends FeatureSpec
        with BeforeAndAfterEach with Matchers {

    // Not using case class because I want identity equality semantics.
    private class WeightedObject(val weight: Int) extends HasWeight
    private object WeightedObject {
        def apply(weight: Int) = new WeightedObject(weight)
    }

    feature("Exhaustive non-random selection") {
        scenario("Attempt to create a WeightedSelector with an empty list.") {
            intercept[IllegalArgumentException] {
                WeightedSelector(List[WeightedObject]())
            }
        }

        scenario("One object with weight 1") {
            val obj = WeightedObject(1)
            val ws = WeightedSelector(List(obj))
            ws.select(0) should be theSameInstanceAs obj
        }

        scenario("One object with weight 2") {
            val obj = WeightedObject(2)
            val ws = WeightedSelector(List(obj))
            ws.select(0) should be theSameInstanceAs obj
            ws.select(1) should be theSameInstanceAs obj
        }

        scenario("Five objects with weight 1") {
            val objs = List.fill(5)(1).map(WeightedObject(_))
            val ws = WeightedSelector(objs)
            (0 to 4) foreach { i =>
                ws.select(i) should be theSameInstanceAs objs(i)
            }
        }

        scenario("Five objects with incrementing weights") {
            val objs = (1 to 5).map(WeightedObject(_))
            val ws = WeightedSelector(objs)
            val expanded = objs.flatMap {
                (obj: WeightedObject) => List.fill(obj.weight)(obj)
            }
            (0 to 14) foreach { i =>
                ws.select(i) should be theSameInstanceAs expanded(i)
            }
        }

        scenario("100 objects with random weights") {
            val objs = List.fill(100)(WeightedObject(Random.nextInt(5) + 1))
            val ws = WeightedSelector(objs)
            val expanded = objs.flatMap {
                (obj: WeightedObject) => List.fill(obj.weight)(obj)
            }
            (0 to (expanded.length - 1)) foreach { i =>
                ws.select(i) should be theSameInstanceAs expanded(i)
            }
        }
    }

    feature("Random sampling") {
        scenario("1000000 samples of 100 objects with random weights") {
            val objs = List.fill(100)(WeightedObject(Random.nextInt(5) + 1))
            val ws = WeightedSelector(objs)

            val frequencies = mutable.Map[WeightedObject, Int]()
            objs.foreach(frequencies(_) = 0)

            val totalWeight = objs.foldLeft(0)(_ + _.weight)

            val iterations = 1000000
            (1 to iterations) foreach { dummy =>
                val obj = ws.select()
                frequencies(obj) += 1
            }

            // Check that each object comes up roughly in proportion
            // to its weight. I'm allowing 10% tolerance, which is not
            // statistically correct (tolerance should be larger,
            // percentage-wise, for small weights), but good enough
            // for these purposes.
            //
            // I think I made the tolerance wide enough to make it
            // very unlikely, but this test may fail rarely due to a
            // run of bad luck. If this happens and you haven't
            // touched WeightedSelector, consider increasing the
            // tolerance a bit.
            frequencies foreach { case(obj, timesSeen) =>
                val expectedTimesSeen =
                    obj.weight.toDouble * iterations / totalWeight
                timesSeen.toDouble should (be > 0.9 * expectedTimesSeen and
                                           be < 1.1 * expectedTimesSeen)
            }
        }
    }
}
