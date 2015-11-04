/*
 * Copyright 2015 Midokura SARL
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
package org.midonet.conf

import ch.qos.logback.classic.Level

import scala.collection.JavaConversions._

import com.typesafe.config.{ConfigException, Config}
import com.typesafe.scalalogging.Logger
import org.slf4j.LoggerFactory
import rx.Observer

class LoggerLevelWatcher(prefix: Option[String] = None) extends Observer[Config] {
    val log = Logger(LoggerFactory.getLogger("org.midonet.config"))

    private def logbackLogger(name: String) = LoggerFactory.getLogger(name).
        asInstanceOf[ch.qos.logback.classic.Logger]

    private def loggerConf(config: Config) = prefix match {
        case Some(p) => config.getConfig(s"$p.loggers")
        case None => config.getConfig("loggers")
    }

    override def onNext(config: Config): Unit = {
        try {
            val logconf = loggerConf(config)
            for (entry <- logconf.entrySet
                    if !entry.getKey.endsWith("_description") && entry.getKey != "root") {
                val level = Level.toLevel(logconf.getString(entry.getKey), Level.INFO)
                val key =
                    if (entry.getKey.endsWith(".root")) entry.getKey.stripSuffix(".root")
                    else entry.getKey
                logbackLogger(key).setLevel(level)
                log.info(s"Set logging level of $key to $level")
            }

            val rootLevel = Level.toLevel(logconf.getString("root"), Level.INFO)
            logbackLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).setLevel(rootLevel)
            log.info(s"Set root logging level to $rootLevel")
        } catch {
            case e: ConfigException.Missing => // ignore
        }
    }

    override def onError(t: Throwable) {
        log.error("Error in logger level watcher", t)
    }

    override def onCompleted() { }
}
