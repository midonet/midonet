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

import com.google.common.annotations.VisibleForTesting

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
                         with mutable.MapLike[String, T, PathMap[T]] {

    @VisibleForTesting
    private[collection] val root = new TrieNode[T]("", None)

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

    /**
     * Gets value at the specified path, or None if no such value exists.
     */
    override def get(key: String): Option[T] = {
        root.getData(parsePath(key))
    }

    /**
     * Gets the children of the specified path as an unordered collection of
     * path-value pairs. The paths are fully qualified.
     */
    def getChildren(path: String): Iterable[(String, T)] = {
        val pathPrefix = if (path == "/") path else path + "/"
        val childNodes = root.getChildren(parsePath(path))
        childNodes.collect {
            case child if child.data.isDefined =>
                (pathPrefix + child.step, child.data.get)
        }(scala.collection.breakOut)
    }

    /**
     * Gets the node at the specified path and all descendents as a
     * sequence of path-value pairs. The paths are fully qualified. While
     * the nodes returned are guaranteed to be ordered by depth (root first),
     * nodes of the same depth have no defined order.
     */
    def getDescendants(path: String): Seq[(String, T)] = {
        // Get a list of all path-node pairs in breadth-first order.
        val rootNode = root.get(parsePath(path))
        if (rootNode.isEmpty) return Seq()

        val buf = new ArrayBuffer[(String, TrieNode[T])]
        buf += ((path, rootNode.get))
        var i = 0
        while (i < buf.size) {
            val (path, node) = buf(i)
            for ((childStep, childNode) <- node.children) {
                val prefix = if (path == "/") path else path + "/"
                val childPath = prefix + childStep
                buf += ((childPath, childNode))
            }
            i += 1
        }

        // Get data from nodes that have it and filter out the rest.
        buf.collect {
            case (p, node) if node.data.isDefined =>
                (p, node.data.get)
        }(scala.collection.breakOut)
    }

    /**
     * Provides an iterator to iterate all values in breadth-first order. The
     * iterator does not reflect changes made to the underlying data after
     * it is created.
     */
    override def iterator: Iterator[(String, T)] = getDescendants("/").iterator

    override def empty: PathMap[T] = PathMap.empty[T]

    /**
     * Converts the specified path to a sequence of steps, starting with the
     * topmost step. E.g. "/a/b/c" becomes Seq("a", "b", "c")
     */
    private def parsePath(path: String): Seq[String] = {
        if (path == null || path.isEmpty || path.head != '/')
            throw new IllegalArgumentException("Invalid path: " + path)
        path.split("/").filter(_.nonEmpty)
    }
}

object PathMap {
    def empty[T] = new PathMap[T]

    @VisibleForTesting
    private[collection]
    class TrieNode[T](val step: String, var data: Option[T]) {

        val children: mutable.Map[String, TrieNode[T]] = mutable.HashMap()

        /**
         * Store the specified value at the specified path, creating empty
         * nodes as needed to make the path accessible to root.
         * @return The old value at the specified path.
         */
        def put(pathSteps: Seq[String], value: T): Option[T] = {
            if (pathSteps.isEmpty) {
                val oldData = data
                data = Some(value)
                oldData
            } else {
                val child = children.getOrElseUpdate(
                    pathSteps.head, new TrieNode[T](pathSteps.head, None))
                child.put(pathSteps.tail, value)
            }
        }

        /**
         * Sets the data of the specified node to None, removing the node itself
         * if it has no children.
         * @return The old value of the node at the specified path.
         */
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
                val removedData = child.remove(pathSteps.tail)
                if (child.data.isEmpty && child.children.isEmpty)
                    children.remove(pathSteps.head)
                removedData
            }
        }

        /**
          * Gets the data from the node at the specified path. Returns None if
          * no such node exists.
          */
        def getData(pathSteps: Seq[String]): Option[T] = {
            get(pathSteps).flatMap(_.data)
        }

        /**
         * Gets the node at the specified path, or None if no such node exists.
         */
        def get(pathSteps: Seq[String]): Option[TrieNode[T]] = {
            if (pathSteps.isEmpty) Some(this)
            else children.get(pathSteps.head).flatMap(_.get(pathSteps.tail))
        }

        /**
         * Gets the children of the specified node, or Iterable.empty if no
         * such node exists.
         */
        def getChildren(pathSteps: Seq[String]): Iterable[TrieNode[T]] = {
            if (pathSteps.isEmpty) children.values
            else children.get(pathSteps.head) match {
                case Some(child) => child.getChildren(pathSteps.tail)
                case None => Iterable.empty[TrieNode[T]]
            }
        }
    }
}