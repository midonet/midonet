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

package org.midonet.quagga

import scala.annotation.tailrec

object BgpdConfiguration {
    abstract class ConfigPiece {
        def build(config: List[String]): this.type = {
            parse(config)
            this
        }

        protected def add(head: String, tail: List[String]): List[String]

        protected val nested = false

        @tailrec
        private[BgpdConfiguration] final def parse(config: List[String]): List[String] = {
            config match {
                case Nil => Nil
                case "end" :: _ => Nil // ignored == "end"
                case _ :: Nil => Nil // does not happen

                case "" :: tail => parse(tail)

                case "!" :: tail => parse(tail)

                case head :: tail if nested && !head.startsWith(" ") =>
                    config

                case head :: tail => parse(add(head.trim, tail))
            }
        }
    }

    case class Network(cidr: String)

    case class Neighbor(address: String,
                        as: Int,
                        var keepalive: Option[Int] = None,
                        var holdtime: Option[Int] = None,
                        var connect: Option[Int] = None)

    case class BgpRouter(as: Int,
                         var id: String = "0.0.0.0",
                         var neighbors: Map[String, Neighbor] = Map.empty,
                         var networks: Set[Network] = Set.empty) extends ConfigPiece {
        override val nested = true

        override def add(head: String, tail: List[String]): List[String] = {
            head.split(' ').toList match {
                case List("bgp", "router-id", _id) =>
                    id = _id
                    tail

                case List("network", net) =>
                    networks += Network(net)
                    tail

                case List("neighbor", peer, "remote-as", remoteAs) =>
                    neighbors += peer -> Neighbor(peer, remoteAs.toInt)
                    tail

                case List("neighbor", peer, "timers", "connect", connect) =>
                    val neigh = neighbors(peer)
                    neigh.connect = Some(connect.toInt)
                    tail

                case List("neighbor", peer, "timers", keepalive, holdtime) =>
                    val neigh = neighbors(peer)
                    neigh.keepalive = Some(keepalive.toInt)
                    neigh.holdtime = Some(holdtime.toInt)
                    tail

                case Nil => tail
                case _ => tail
            }
        }

    }

    case class BgpdRunningConfig(var debug: Boolean = false,
                                 var hostname: Option[String] = None,
                                 var logFile: Option[String] = None,
                                 var password: Option[String] = None,
                                 var router: Option[BgpRouter] = None) extends ConfigPiece {

        override def add(head: String, tail: List[String]): List[String] = {
            head.split(' ').toList match {
                case List("hostname", name) =>
                    hostname = Some(name)
                    tail

                case List("password", pwd) =>
                    password = Some(pwd)
                    tail

                case List("log", "file", filename) =>
                    logFile = Some(filename)
                    tail

                case List("debug", "bgp") =>
                    debug = true
                    tail

                case List("router", "bgp", as) =>
                    router = Some(BgpRouter(as.toInt))
                    router.get.parse(tail)

                case Nil => tail
                case List(prompt, "show", "run") => tail
                case List("Current", "configuration:") => tail
                case List("line", "vty") => tail
                case List("exec-timeout", _, _) => tail
                case _ => tail
            }
        }
    }
}
