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

package org.midonet.midolman.util

import java.nio.channels.spi.{AbstractSelectableChannel, AbstractSelector, SelectorProvider}
import java.nio.channels.{SelectableChannel, SelectionKey, Selector}
import java.util.{HashSet, Set}

class MockSelector(provider: SelectorProvider) extends AbstractSelector(provider) {

    override protected def implCloseSelector(): Unit = {  }

    override def register(ch: AbstractSelectableChannel,
                          ops: Int, att: AnyRef): SelectionKey = {
        new SelectionKey {
            override def readyOps(): Int = 0
            override def cancel(): Unit = { }
            override def isValid: Boolean = true
            override def selector(): Selector = MockSelector.this
            override def channel(): SelectableChannel = ch
            override def interestOps(): Int = 0
            override def interestOps(ops: Int): SelectionKey = this
        }
    }

    override def keys(): Set[SelectionKey] = new HashSet()
    override def selectNow(): Int = 0
    override def wakeup(): Selector = this
    override def select(timeout: Long): Int = 0
    override def select(): Int = 0
    override def selectedKeys(): Set[SelectionKey] = new HashSet()
}
