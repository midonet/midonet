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

package org.midonet.cluster.services.state

import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

import scala.concurrent.{Future, Promise}
import scala.concurrent.duration._
import scala.reflect.ClassTag

import com.typesafe.config.ConfigFactory

import org.junit.runner.RunWith
import org.scalatest.junit.JUnitRunner
import org.scalatest.{FeatureSpec, GivenWhenThen, Matchers}

import rx.Observable

import org.midonet.cluster.StateProxyConfig
import org.midonet.cluster.data.storage.{PersistenceOp, StateTable, StateTablePaths, Transaction}
import org.midonet.cluster.rest_api.models.Bridge
import org.midonet.cluster.rpc.State.ProxyResponse.Notify
import org.midonet.cluster.services.MidonetBackend
import org.midonet.cluster.test.util.ZookeeperTestSuite
import org.midonet.packets.MAC
import org.midonet.util.concurrent.SameThreadButAfterExecutorService
import org.midonet.util.reactivex.TestAwaitableObserver

@RunWith(classOf[JUnitRunner])
class StateTableCacheTest extends FeatureSpec with Matchers with GivenWhenThen
                          with ZookeeperTestSuite {

    private class TestObserver(auto: Boolean = true)
        extends TestAwaitableObserver[Notify] with StateTableObserver {
        var promise = Promise[AnyRef]()
        override def next(notify: Notify): Future[AnyRef] = {
            onNext(notify)
            if (auto) Future.successful(null) else promise.future
        }
    }

    class NotifyMatcher(notify: Notify) {
        def shouldBeSnapshotFor(begin: Boolean, end: Boolean, entries: (Any, Any)*)
        : Unit = {
            notify.hasUpdate shouldBe true
            notify.getUpdate.getType shouldBe Notify.Update.Type.SNAPSHOT
            notify.getUpdate.getBegin shouldBe begin
            notify.getUpdate.getEnd shouldBe end
            notify.getUpdate.getEntriesCount shouldBe entries.size
        }
    }

    private var proxyConfig: StateProxyConfig = _
    private val paths = new StateTablePaths {
        override protected def pathExists(path: String): Boolean = false
        override protected def rootPath: String = ZK_ROOT
        override protected def zoomPath: String = ZK_ROOT
        override protected def version = new AtomicLong()

        override def getTable[K, V](clazz: Class[_], id: Any, name: String,
                                    args: Any*)(implicit key: ClassTag[K],
                                                value: ClassTag[V]): StateTable[K, V] = ???
        override def tableArguments(clazz: Class[_], id: Any, name: String,
                                    args: Any*): Future[Set[String]] = ???
        override def multi(ops: Seq[PersistenceOp]): Unit = ???
        override def isRegistered(clazz: Class[_]): Boolean = ???
        override def observable[T](clazz: Class[T],
                                   id: Any): Observable[T] = ???
        override def observable[T](clazz: Class[T]): Observable[Observable[T]] = ???
        override def registerClass(clazz: Class[_]): Unit = ???
        override def transaction(): Transaction = ???
        override def get[T](clazz: Class[T], id: Any): Future[T] = ???
        override def exists(clazz: Class[_], id: Any): Future[Boolean] = ???
        override def getAll[T](clazz: Class[T],
                               ids: Seq[_ <: Any]): Future[Seq[T]] = ???
        override def getAll[T](clazz: Class[T]): Future[Seq[T]] = ???
    }
    private val timeout = 5 seconds

    before {
        proxyConfig = new StateProxyConfig(ConfigFactory.parseString(
            s"""
               |cluster.state_proxy.initial_subscriber_queue_size : 16
               |cluster.state_proxy.notify_batch_size : 64
             """.stripMargin))
    }

    private def newCache(create: Boolean = true)(onClose: => Unit)
    : StateTableCache = {
        val objectId = UUID.randomUUID()
        if (create) {
            zkClient.create().creatingParentsIfNeeded()
                    .forPath(paths.tablePath(classOf[Bridge], objectId,
                                             MidonetBackend.MacTable, Seq.empty))
        }
        new StateTableCache(proxyConfig, paths, zkClient, classOf[Bridge],
                            objectId, classOf[MAC], classOf[UUID],
                            MidonetBackend.MacTable, Seq.empty,
                            new SameThreadButAfterExecutorService,
                            onClose)
    }

    private implicit def asMatcher(notify: Notify): NotifyMatcher = {
        new NotifyMatcher(notify)
    }

    feature("Cache state lifecycle") {
        scenario("Cache can be created and closed") {
            Given("A state table cache")
            var closed = false
            val cache = newCache() { closed = true }

            Then("The cache is stopped")
            cache.isStopped shouldBe true
            cache.isClosed shouldBe false

            And("There are no subscribers")
            cache.hasSubscribers shouldBe false

            When("Closing the cache")
            cache.close()

            Then("The cache is closed")
            cache.isStopped shouldBe false
            cache.isClosed shouldBe true

            And("There are no subscribers")
            cache.hasSubscribers shouldBe false

            And("The close handler should be called")
            closed shouldBe true
        }
    }

    feature("Cache handles subscriptions") {
        scenario("Observer subscribes and unsubscribes") {
            Given("A state table cache")
            var closed = false
            val cache = newCache() { closed = true }

            And("An observer")
            val observer = new TestObserver

            When("The observer subscribes")
            val subscription = cache.subscribe(observer, lastVersion = None)

            Then("The subscription should be subscribed")
            subscription.isUnsubscribed shouldBe false

            When("The subscriber unsubscribes")
            subscription.unsubscribe()

            Then("The observer should receive one notification")
            observer.awaitOnNext(1, timeout) shouldBe true
            observer.getOnNextEvents.get(0) shouldBeSnapshotFor(begin = true, end = true)

            Then("The subscription should be unsubscribe")
            subscription.isUnsubscribed shouldBe true

            And("The cache should be closed")
            cache.isClosed shouldBe true
        }
    }

}
