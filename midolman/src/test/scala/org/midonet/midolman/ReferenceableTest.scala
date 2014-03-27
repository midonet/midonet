/*
* Copyright 2014 Midokura Europe SARL
*/
package org.midonet.midolman

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest._


@RunWith(classOf[JUnitRunner])
class ReferenceableTest extends FlatSpec with Matchers {
    "An actor path" should "consist of the names of it and its parent" in {
        Referenceable.getReferenceablePath("Supervisor", "Child") should be (
                "/user/Supervisor/Child")
    }
}
