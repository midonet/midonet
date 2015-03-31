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

import org.junit.runner.RunWith
import org.scalatest.{Matchers, FlatSpec}
import org.scalatest.junit.JUnitRunner

@RunWith(classOf[JUnitRunner])
class PathMapTest extends FlatSpec with Matchers {

    "PathTrieMap" should "store and retrieve data" in {
        val pm = new PathMap[String]
        pm("/a/b/c") = "abc"
        pm("/a/b/d") = "abd"
        pm("/a/b/c") shouldBe "abc"
        pm("/a/b/d") shouldBe "abd"
    }

    it should "allow deletion" in {
        val pm = new PathMap[String]
        pm("/a") = "a"
        pm("/a/b") = "ab"
        pm("/a/b/c") = "abc"
        pm.remove("/a/b") shouldBe Some("ab")
        pm.toList should contain only(("/a", "a"), ("/a/b/c", "abc"))
    }

    it should "return the children of a given path" in {
        val pm = new PathMap[String]
        pm("/a") = "a"
        pm("/a/b") = "ab"
        pm("/a/b/c") = "abc"
        pm("/a/b/d") = "abd"
        pm("/a/b/e") = "abe"
        pm.getChildren("/") should contain only (("/a", "a"))
        pm.getChildren("/a/b") should contain only
            (("/a/b/c", "abc"), ("/a/b/d", "abd"), ("/a/b/e", "abe"))

        pm -= "/a/b/d"
        pm.getChildren("/a/b") should contain only
            (("/a/b/c", "abc"), ("/a/b/e", "abe"))

        // Should still be able to get children of a deleted node, but the
        // deleted node should not show in its parent's children.
        pm -= "/a/b"
        pm.getChildren("/a") shouldBe empty
        pm.getChildren("/a/b") should contain only
            (("/a/b/c", "abc"), ("/a/b/e", "abe"))
    }

    it should "iterate in breadth-first order" in {
        val pm = new PathMap[String]
        pm("/a") = "a"
        pm("/a/b") = "ab"
        pm("/a/c") = "ac"
        pm("/a/d") = "ad"
        pm("/a/b/e") = "abe"
        pm("/a/b/f") = "abf"
        pm("/a/c/g") = "acg"
        pm("/a/c/h") = "ach"
        pm("/a/d/i") = "adi"
        pm("/a/d/j") = "adj"
        pm("/a/b/e/k") = "abek"
        pm("/a/d/j/l") = "adjl"

        val keys = pm.keys.toList
        keys.size shouldBe 12
        keys.head shouldBe "/a"
        keys.slice(1, 4) should contain only("/a/b", "/a/c", "/a/d")
        keys.slice(4, 10) should contain only(
            "/a/b/e", "/a/b/f", "/a/c/g", "/a/c/h", "/a/d/i", "/a/d/j")
        keys.slice(10, 12) should contain only("/a/b/e/k", "/a/d/j/l")
    }

    it should "return descendants of a specified node" in {
        val pm = new PathMap[String]
        pm("/a") = "a"
        pm("/b") = "b"
        pm("/a/c") = "ac"
        pm("/b/d") = "bd"
        pm("/b/e") = "be"
        pm("/b/d/f") = "bdf"
        pm.getDescendants("/b") should contain only
            (("/b", "b"), ("/b/d", "bd"), ("/b/e", "be"), ("/b/d/f", "bdf"))
        pm.getDescendants("/a") should contain only
            (("/a", "a"), ("/a/c", "ac"))
    }

    it should "Reject invalid paths" in {
        val pm = new PathMap[String]
        an [IllegalArgumentException] should be thrownBy (pm(null) = "test")
        an [IllegalArgumentException] should be thrownBy (pm("") = "test")
        an [IllegalArgumentException] should be thrownBy (pm("test") = "test")
        an [IllegalArgumentException] should be thrownBy (pm("te/st") = "test")

    }
}
