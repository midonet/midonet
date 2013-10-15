/******************************************************************************
 *                                                                            *
 *      Copyright (c) 2013 Midokura Europe SARL, All Rights Reserved.         *
 *                                                                            *
 ******************************************************************************/

package org.midonet.midolman.simulation

import org.junit.runner.RunWith
import org.scalatest.BeforeAndAfter
import org.scalatest.Suite
import org.scalatest.junit.JUnitRunner
import org.scalatest.matchers.ShouldMatchers

@RunWith(classOf[JUnitRunner])
class CoordinatorTest
        extends Suite with BeforeAndAfter with ShouldMatchers {

    before { }

    after { }

    def testNothing() { }

    def testMatchObjectsSame() {

        def test(args: (Any, Any, Boolean)): Unit =
            Coordinator.matchObjectsSame(args._1, args._2) should be (args._3)

        val randomArgs =
            List[Any](0, 1, 2, 3, 0, 42, 100, 40.3, -12.7, 3.14159, ~0) ++
            List[Any]("foo", "bar", "midokura", "saucisse", "scala") ++
            List[Any](Nil, 10 :: Nil, "foo" :: Nil, "foo" :: "bar" :: Nil) ++
            List[Any](Set(1, 2), Set("foo")) ++
            List[Any](Map[Any, Any](), ("foo" -> "bar"))

        test((null, null, true)) // both null

        // test when first arg == null and second arg != null -> always false
        randomArgs.map{ (null, _, false) } foreach { test _ }

        //test when second arg == null -> always true
        randomArgs.map{ (_, null, true) } foreach { test _ }

        //test when both args are the same
        randomArgs.map{ x => (x, x, true) } foreach { test _ }

        // test when args are different
        randomArgs
            .map{ x => (x, randomArgs.filter{_ != x}) }
            .flatMap{ case (x, yl) => yl.map{ (x, _, false) } }
            .foreach{ test _ }
    }

}
