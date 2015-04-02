package org.midonet.midolman.topology

import org.junit.runner.RunWith
import org.scalatest.FeatureSpec
import org.scalatest.junit.JUnitRunner

import rx.{Notification, Subscriber, Observable}
import rx.subjects.PublishSubject

import org.midonet.util.functors._

@RunWith(classOf[JUnitRunner])
class BGPMapperTest extends FeatureSpec {

    feature("Test") {
        scenario("Test") {

            val parent = PublishSubject.create[Observable[Int]]
            val child1 = PublishSubject.create[Int]
            var child2 = PublishSubject.create[Int]
            var child3 = PublishSubject.create[Int]

            val sub = new Subscriber[Notification[Int]] {
                override def onNext(i: Notification[Int]) = println(s"onNext $i")
                override def onError(e: Throwable) = println(s"onError $e")
                override def onCompleted() = println("onCompleted")
            }
            Observable.merge(parent).materialize().subscribe(sub)

            parent onNext child1
            parent onNext child2
                .doOnError(makeAction1(e => {
                    child1 onNext 10
                    child3 onNext 30
                    parent onNext Observable.just(-1)
                }))
            parent onNext child3
            child1 onNext 11
            child2 onNext 21
            child3 onNext 31
            child2 onError new IllegalArgumentException()

            sub.isUnsubscribed

        }
    }

}
