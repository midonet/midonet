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

package org.midonet.util.collection

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer

import org.midonet.util.collection.PathMap.TrieNode

/**
 * Map whose keys are slash-delimited paths (e.g. "/a/b/c"). Extends usual
 * map functionality with the ability to retrieve the children of a given
 * path.
 *
 * As I plan on using this only with fairly shallow path hierarchies and in a
 * non-perf-critical context, I used a tree-based implementation, giving log(N)
 * running time for all operations.
 */
class PathMap[T] extends mutable.AbstractMap[String, T]
                         with mutable.Map[String, T]
                         with mutable.MapLike[String, T, PathMap[T]] {

    private val root = new TrieNode[T]("", None)

    override def +=(kv: (String, T)): this.type = {
        val pathSteps = parsePath(kv._1)
        root.put(pathSteps, kv._2)
        this
    }

    override def -=(key: String): this.type = {
        val pathSteps = parsePath(key)
        if (pathSteps.isEmpty) root.data = None
        else root.remove(pathSteps)
        this
    }

    override def get(key: String): Option[T] = {
        root.getData(parsePath(key))
    }

    def getChildren(path: String): Seq[(String, T)] = {
        val pathPrefix = if (path == "/") path else path + "/"
        val childNodes = root.getChildren(parsePath(path))
        childNodes.collect {
            case child if child.data.isDefined =>
                (pathPrefix + child.step, child.data.get)
        }(scala.collection.breakOut)
    }

    def getDescendants(path: String): Seq[(String, T)] = {
        // Get a list of all path-node pairs in breadth-first order.
        val rootNode = root.get(parsePath(path))
        if (rootNode.isEmpty) return List()

        val buf = new ArrayBuffer[(String, TrieNode[T])]
        buf += ((path, rootNode.get))
        var i = 0
        while (i < buf.size) {
            val (path, node) = buf(i)
            for ((childStep, childNode) <- node.children) {
                val childPath = if (node == root) path + childStep
                else path + "/" + childStep
                buf +=((childPath, childNode))
            }
            i += 1
        }

        // Get data from nodes that have it and filter out the rest.
        buf.collect {
            case (p, node) if node.data.isDefined =>
                (p, node.data.get)
        }
    }

    override def iterator: Iterator[(String, T)] = getDescendants("/").iterator

    override def empty: PathMap[T] = PathMap.empty[T]

    private def parsePath(path: String): Seq[String] = {
        if (path == null || path.isEmpty || path.head != '/')
            throw new IllegalArgumentException("Invalid path: " + path)
        path.split("/").filter(_.nonEmpty)
    }
}

object PathMap {
    def empty[T] = new PathMap[T]

    private class TrieNode[T](val step: String, var data: Option[T]) {

        val children: mutable.Map[String, TrieNode[T]] = mutable.HashMap()

        def put(pathSteps: Seq[String], value: T): Option[T] = {
            if (pathSteps.isEmpty) {
                val oldData = data
                data = Some(value)
                oldData
            } else {
                children.get(pathSteps.head) match {
                    case Some(child) => child.put(pathSteps.tail, value)
                    case None =>
                        val child = new TrieNode[T](pathSteps.head, None)
                        children(pathSteps.head) = child
                        child.put(pathSteps.tail, value)
                }
            }
        }

        def remove(pathSteps: Seq[String]): Option[T] = {
            assert(pathSteps.nonEmpty)
            val child = children.getOrElse(pathSteps.head, return None)
            if (pathSteps.size == 1) {
                val oldData = child.data
                if (child.children.isEmpty) {
                    children.remove(pathSteps.head)
                } else {
                    child.data = None
                }
                oldData
            } else {
                child.remove(pathSteps.tail)
            }
        }

        def getData(pathSteps: Seq[String]): Option[T] = {
            get(pathSteps).flatMap(_.data)
        }

        def get(pathSteps: Seq[String]): Option[TrieNode[T]] = {
            if (pathSteps.isEmpty) Some(this)
            else children.get(pathSteps.head).flatMap(_.get(pathSteps.tail))
        }

        def getChildren(pathSteps: Seq[String]): Iterable[TrieNode[T]] = {
            if (pathSteps.isEmpty) children.values
            else children.get(pathSteps.head) match {
                case Some(child) => child.getChildren(pathSteps.tail)
                case None => Iterable.empty[TrieNode[T]]
            }
        }
    }
}