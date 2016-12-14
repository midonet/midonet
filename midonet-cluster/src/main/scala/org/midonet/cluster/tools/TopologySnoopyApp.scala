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

package org.midonet.cluster.tools

import java.util.concurrent.CountDownLatch

import scala.collection.JavaConversions._

import com.google.common.util.concurrent.Service.{State, Listener}
import com.google.inject.{AbstractModule, Guice}
import com.typesafe.config.ConfigFactory
import com.typesafe.config.ConfigValueFactory
import org.apache.commons.configuration.HierarchicalINIConfiguration
import org.slf4j.LoggerFactory

import org.midonet.util.concurrent.CallingThreadExecutionContext

/**
 * Stand-alone application to start the Topology Snoopy (Topology api client)
 */
object TopologySnoopyApp extends App {
    private val log = LoggerFactory.getLogger(this.getClass)

    private val cfgFile = args(0)

    private val topologySnoopyModule = new AbstractModule {
        override def configure(): Unit = {
            val ini = new HierarchicalINIConfiguration(cfgFile)
            val kvs = for (sectionName <- ini.getSections;
                           section = ini.getSection(sectionName);
                           k <- section.getKeys;
                           v = section.getString(k)) yield
                (s"cluster.$sectionName.$k", ConfigValueFactory.fromAnyRef(v))
            val config = (ConfigFactory.empty /: kvs) {
                case (acc, (k, v)) => acc.withValue(k, v)
            }

            bind(classOf[TopologySnoopyConfig]).toInstance(
                new TopologySnoopyConfig(config))
            bind(classOf[TopologySnoopy]).asEagerSingleton()
        }
    }

    protected[cluster] val injector = Guice.createInjector(
        topologySnoopyModule
    )

    private val app = injector.getInstance(classOf[TopologySnoopy])
    private val appEnded = new CountDownLatch(1)
    app.addListener(new Listener {
       override def terminated(from: State): Unit = appEnded.countDown()
    }, CallingThreadExecutionContext)

    sys.addShutdownHook {
        if (app.isRunning)
            app.stopAsync().awaitTerminated()
    }

    try {
        app.startAsync().awaitRunning()
        appEnded.await()
        log.info("Topology Snoopy terminating")
    } catch {
        case e: InterruptedException =>
            log.info("Topology Snoopy terminating")
            Thread.currentThread().interrupt()
        case e: Exception =>
            log.error("Failed to start Topology Snoopy", e)
    }
    System.exit(0)
}
