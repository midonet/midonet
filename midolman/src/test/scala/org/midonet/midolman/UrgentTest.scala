/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.midolman

import org.junit.runner.RunWith

import org.scalatest.junit.JUnitRunner
import org.scalatest.{Matchers, FeatureSpec}

import scala.concurrent.duration._
import scala.concurrent.{Promise, Await, ExecutionContext, Future}

//@RunWith(classOf[JUnitRunner])
class UrgentTest extends FeatureSpec
                 with Matchers {

    private val myVal: String = "hello"

    private def assertReady[T](u: Urgent[_])(v: T) {
        u.isReady should be (true)
        u.get shouldEqual v
        u.isInstanceOf[Ready[T]] should be (true)
    }

    private def assertNotYet[T](u: Urgent[_]) {
        u.isReady should be (false)
        u.isInstanceOf[NotYet[T]] should be (true)
        intercept[UnavailableException[String]] {
            u.get
        }
    }

    private def assertNotYetContains[T](u: Urgent[T])(v: T) {
        assertNotYet[T](u)
        u match {
            case NotYet(ft) =>
                Await.result(ft, 100 millis) shouldEqual v
            case _ => fail("Unexpected Ready")
        }
    }

    /*
    feature("Construct") {
        scenario("From value") {
            assertReady(Urgent.apply(myVal))(myVal)
            assertReady(Urgent.ready(myVal))(myVal)
        }
        scenario("From successful future") {
            assertNotYet(Urgent.unavailable(Future.successful(myVal)))
        }
        scenario("From failed future") {
            assertNotYet(Urgent.unavailable(Future.failed(null)))
        }
        scenario("From incompleted future which then completes") {
            val f = Promise[Int]()
            val u = Urgent.unavailable(f.future)
            assertNotYet(u)
            f.success(1)
            assertNotYet(u)
        }
    }

    feature("Map") {
        scenario("On a Ready") {
            val u = Ready(myVal)
            val u1 = u map { v => v.toUpperCase }
            assertReady(u1)(myVal.toUpperCase)
            val u2 = u1 map { v => v.hashCode }
            assertReady[Int](u2)(myVal.toUpperCase.hashCode)
        }
        scenario("On a NotYet") {
            val u = NotYet(Future.successful(myVal))
            val u1 = u map { v => v.hashCode }
            assertNotYet[Int](u1)
            val u2 = u1 map { v => v.toString }
            assertNotYet[String](u2)
        }
    }

    feature("FlatMap") {
        scenario("Composing Readys has effect") {
            val u = Ready(myVal)
            val u1 = u flatMap { v => Ready(v.toUpperCase) }
            assertReady(u1)(myVal.toUpperCase)
            val u2 = u1 flatMap { v => Ready(v.hashCode) }
            assertReady[Int](u2)(myVal.toUpperCase.hashCode)
        }
        scenario("Composing Readys with NotYets only has effect for Readys") {
            val u = Ready(myVal)
            val u1 = u flatMap { v => Ready(v + "1") }
            assertReady[String](u1)(myVal + "1")
            val u2 = u1 flatMap { v => NotYet(Future.successful(v.reverse)) }
            assertNotYetContains(u2)((myVal+"1").reverse)
        }
        scenario("Composing NotYets has no effect") {
            val u = NotYet(Future.successful(myVal))
            val u1 = u flatMap { v => NotYet(Future.successful(v + "1")) }
            assertNotYetContains(u1)(myVal)
            val u2 = u1 flatMap { v => NotYet(Future.successful(v + "2")) }
            assertNotYetContains(u2)(myVal)
        }
        scenario("Composing NotYets with Readys has no effect") {
            val u = NotYet(Future.successful(myVal))
            val u1 = u flatMap { v => NotYet(Future.successful(v + "1")) }
            assertNotYetContains(u1)(myVal)
            val u2 = u1 flatMap { v => Ready(v + "2") }
            assertNotYetContains(u2)(myVal)
        }
    }

    feature("Flatten") {
        import ExecutionContext.Implicits.global
        scenario("On a sequence of Readys") {
            val s = Seq(Ready(1), Ready(2), Ready(3), Ready(4))
            val u = Urgent.flatten(s)
            assertReady[Seq[Int]](u)(Seq(1, 2, 3, 4))
        }
        scenario("On a mixed sequence") {
            val s = Seq(Ready(1), Ready(2), NotYet(Future.successful(3)), Ready(4))
            val u = Urgent.flatten(s)
            assertNotYetContains(u)(Seq(3))
        }
    }
    */

}
