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
package org.midonet.cluster.data.reactivex

import scala.concurrent.duration._

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, Matchers}

import org.midonet.cluster.TestUtil.SimplePojo
import org.midonet.cluster.data.reactivex.InstanceObservable.{Complete, Error, Update, Event}
import org.midonet.cluster.data.storage.{NotFoundException, ZookeeperObjectMapper, Storage}
import org.midonet.cluster.util.CuratorTestFramework
import org.midonet.util.reactivex.AwaitableObserver
import org.midonet.util.reactivex.AwaitableObserver.{OnCompleted, OnError, OnNext}

@RunWith(classOf[JUnitRunner])
class InstanceObservableTest extends FeatureSpec with Matchers
                                     with CuratorTestFramework {

    private implicit var store: Storage = _
    type InstanceObserver = AwaitableObserver[Event[SimplePojo]]

    override protected def setup(): Unit = {
        store = new ZookeeperObjectMapper(ZK_ROOT, curator)
        store.registerClass(classOf[SimplePojo])
        store.build()
    }

    object Pojo {
        def apply(id: Int, value: String) = new SimplePojo(id, value)
    }

    feature("Test subscriber receives notifications") {

        scenario("Notification of instance does not exist") {
            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer = new InstanceObserver(2)

            observable.subscribe(observer)
            observer.await(1.second) should be (true)
            observer.notifications should contain inOrderOnly (
                OnNext(Error(0, new NotFoundException(classOf[SimplePojo], None))),
                OnError(new NotFoundException(classOf[SimplePojo], None)))
        }

        scenario("Notification of current instance") {
            store.create(Pojo(0, "0"))

            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer = new InstanceObserver()

            observable.subscribe(observer)
            observer.await(1.second) should be (true)
            observer.notifications should contain only OnNext(Update(0, Pojo(0, "0")))
        }

        scenario("Notification of updates") {
            store.create(Pojo(0, "0"))

            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer = new InstanceObserver(2)

            observable.subscribe(observer)

            store.update(Pojo(0, "1"))

            observer.await(1.second) should be (true)
            observer.notifications should contain inOrderOnly (
                OnNext(Update(0, Pojo(0, "0"))),
                OnNext(Update(0, Pojo(0, "1"))))
        }

        scenario("Notification of deletion") {
            store.create(Pojo(0, "0"))

            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer = new InstanceObserver(3)

            observable.subscribe(observer)

            store.delete(classOf[SimplePojo], 0)

            observer.await(1.second) should be (true)
            observer.notifications should contain inOrderOnly (
                OnNext(Update(0, Pojo(0, "0"))), OnNext(Complete(0)),
                OnCompleted())
        }

        scenario("Notifications are not received after unsubscribe") {
            store.create(Pojo(0, "0"))

            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer = new InstanceObserver()

            val sub = observable.subscribe(observer)
            observer.await(1.second, 1) should be (true)
            observer.notifications should contain only OnNext(Update(0, Pojo(0, "0")))

            sub.unsubscribe()
            sub.isUnsubscribed should be (true)

            store.update(Pojo(0, "1"))

            observer.await(1.second) should be (false)
            observer.notifications should contain only OnNext(Update(0, Pojo(0, "0")))
        }
    }

    feature("Test observable completion on demand") {
        scenario("Notification of completed") {
            store.create(Pojo(0, "0"))

            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer = new InstanceObserver()

            val sub = observable.subscribe(observer)
            observer.await(1.second, 2) should be (true)
            observable.complete()
            observer.await(1.second) should be (true)
            observer.notifications should contain inOrderOnly (
                OnNext(Update(0, Pojo(0, "0"))), OnNext(Complete(0)),
                OnCompleted())

            sub.isUnsubscribed should be (true)
        }

        scenario("Completed has no effect after unsubscribe") {
            store.create(Pojo(0, "0"))

            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer = new InstanceObserver()

            val sub = observable.subscribe(observer)
            observer.await(1.second, 1) should be (true)
            observer.notifications should contain only OnNext(Update(0, Pojo(0, "0")))

            sub.unsubscribe()
            sub.isUnsubscribed should be (true)

            observable.complete()
            observer.await(1.second) should be (false)
            observer.notifications should contain only OnNext(Update(0, Pojo(0, "0")))
        }

        scenario("Notifications are not received after completed") {
            store.create(Pojo(0, "0"))

            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer = new InstanceObserver()

            val sub = observable.subscribe(observer)
            observer.await(1.second, 2) should be (true)
            observable.complete()
            observer.await(1.second, 1) should be (true)
            sub.isUnsubscribed should be (true)

            store.update(Pojo(0, "1"))

            observer.await(1.second) should be (false)
            observer.notifications should contain inOrderOnly (
                OnNext(Update(0, Pojo(0, "0"))), OnNext(Complete(0)),
                OnCompleted())
        }
    }

    feature("Test multiple subscribers") {
        scenario("Initial state seen by all subscribers") {
            store.create(Pojo(0, "0"))

            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer1 = new InstanceObserver()
            val observer2 = new InstanceObserver()

            val sub1 = observable.subscribe(observer1)
            observer1.await(1.second) should be (true)
            observer1.notifications should contain only OnNext(Update(0, Pojo(0, "0")))

            val sub2 = observable.subscribe(observer2)
            observer2.await(1.second) should be (true)
            observer2.notifications should contain only OnNext(Update(0, Pojo(0, "0")))
        }

        scenario("Updates and deletions seen by all subscribers") {
            store.create(Pojo(0, "0"))

            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer1 = new InstanceObserver()
            val observer2 = new InstanceObserver()

            val sub1 = observable.subscribe(observer1)
            observer1.await(1.second, 1) should be (true)

            val sub2 = observable.subscribe(observer2)
            observer2.await(1.second, 1) should be (true)

            store.update(Pojo(0, "1"))

            observer1.await(1.second, 1) should be (true)
            observer2.await(1.second, 1) should be (true)

            store.delete(classOf[SimplePojo], 0)

            observer1.await(1.second, 2) should be (true)
            observer2.await(1.second, 2) should be (true)

            observer1.notifications should contain inOrderOnly (
                OnNext(Update(0, Pojo(0, "0"))),
                OnNext(Update(0, Pojo(0, "1"))),
                OnNext(Complete(0)),
                OnCompleted())
            observer2.notifications should contain inOrderOnly (
                OnNext(Update(0, Pojo(0, "0"))),
                OnNext(Update(0, Pojo(0, "1"))),
                OnNext(Complete(0)),
                OnCompleted())
        }

        scenario("Completion affects all subscribers") {
            store.create(Pojo(0, "0"))

            val observable = InstanceObservable.create(classOf[SimplePojo], 0)
            val observer1 = new InstanceObserver()
            val observer2 = new InstanceObserver()

            val sub1 = observable.subscribe(observer1)
            observer1.await(1.second, 2) should be (true)

            val sub2 = observable.subscribe(observer2)
            observer2.await(1.second, 2) should be (true)

            observable.complete()

            store.update(Pojo(0, "1"))

            observer1.await(1.second) should be (true)
            observer2.await(1.second) should be (true)

            observer1.notifications should contain inOrderOnly (
                OnNext(Update(0, Pojo(0, "0"))),
                OnNext(Complete(0)),
                OnCompleted())
            observer2.notifications should contain inOrderOnly (
                OnNext(Update(0, Pojo(0, "0"))),
                OnNext(Complete(0)),
                OnCompleted())
        }
    }

}
