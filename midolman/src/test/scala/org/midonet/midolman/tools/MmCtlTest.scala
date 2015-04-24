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

package org.midonet.midolman.tools

import org.junit.Before
import org.junit.runner.RunWith
import org.midonet.cluster.DataClient
import org.midonet.cluster.data.storage.Storage
import org.midonet.midolman.config.MidolmanConfig
import org.mockito.Mockito._
import org.scalatest.junit.JUnitRunner
import org.scalatest.{BeforeAndAfter, FlatSpec, Matchers}

import scala.concurrent.Promise

@RunWith(classOf[JUnitRunner])
class MmCtlTest extends FlatSpec with BeforeAndAfter with Matchers {

    var mmCtl: MmCtl = null
    var config: MidolmanConfig = null
    var dataClient: DataClient = null
    var storage: Storage = null

    before {
        mmCtl = new MmCtl
        config = mock(classOf[MidolmanConfig])
        dataClient = mock(classOf[DataClient])
        storage = mock(classOf[Storage])

               //               when(storage.get(classOf[MidolmanConfig], id))
//            .thenReturn(Promise.successful(router).future)

    }
}


