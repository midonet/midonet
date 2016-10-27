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

package org.midonet.cluster.services.c3po.translators

import org.junit.runner.RunWith
import org.mockito.Matchers._
import org.mockito.Mockito._
import org.scalatest.junit.JUnitRunner

import org.midonet.cluster.models.Commons.UUID
import org.midonet.cluster.models.ModelsUtil._
import org.midonet.cluster.models.Neutron.TapService
import org.midonet.cluster.models.Topology.Mirror
import org.midonet.cluster.services.c3po.NeutronTranslatorManager._
import org.midonet.cluster.util.UUIDUtil

class TapServiceTranslatorTestBase extends TranslatorTestBase {

    protected var translator: TapServiceTranslator = _
    protected val mirrorId = UUIDUtil.toProtoFromProtoStr("msb: 1 lsb: 1")
    protected val portId = UUIDUtil.toProtoFromProtoStr("msb: 2 lsb: 1")

    protected def neutronTapService(portId: UUID = portId) =
        nTapServiceFromTxt(s"""
            id { $mirrorId }
            tenant_id: "tenant_id"
            name: "test tap service"
            description: "test tap service description"
            port_id { $portId }
            """)

    protected def midoMirror = mMirrorFromTxt(s"""
        id { $mirrorId }
        to_port_id { $portId }
        conditions {
            fragment_policy: ANY
        }
        """)
}

/**
  * Tests Neutron Tap Service Create translation.
  */
@RunWith(classOf[JUnitRunner])
class TapServiceTranslatorCreateTest extends TapServiceTranslatorTestBase {

    before {
        initMockStorage()
        translator = new TapServiceTranslator()
    }

    "Neutron Tap Service Create" should "create a Midonet Mirror." in {
        translator.translate(transaction, Create(neutronTapService()))
        verify(transaction, times(1)).create(midoMirror)
    }
}

/**
  * Tests Neutron Tap Service Delete translation.
  */
@RunWith(classOf[JUnitRunner])
class TapServiceTranslatorDeleteTest extends TapServiceTranslatorTestBase {

    before {
        initMockStorage()
        translator = new TapServiceTranslator()
    }

    "Neutron Tap Service Delete" should "delete a Midonet Mirror." in {
        bind(mirrorId, neutronTapService())

        translator.translate(transaction, Delete(classOf[TapService], mirrorId))
        verify(transaction, times(1)).delete(classOf[Mirror], mirrorId,
                                             ignoresNeo = true)
    }
}

/**
  * Tests Neutron Tap Service Update translation.
  */
@RunWith(classOf[JUnitRunner])
class TapServiceTranslatorUpdateTest extends TapServiceTranslatorTestBase {

    private val updatedPortId = UUIDUtil.toProtoFromProtoStr("msb: 10 lsb: 1")
    private val updatedTapService = neutronTapService(updatedPortId)

    before {
        initMockStorage()
        translator = new TapServiceTranslator()
    }

    "Neutron Tap Service Update" should "do nothing" in {
        translator.translate(transaction, Update(updatedTapService))
        verify(transaction, never()).update(any(), any())
    }
}