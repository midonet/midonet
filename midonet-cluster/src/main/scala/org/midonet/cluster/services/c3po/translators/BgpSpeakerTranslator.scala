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

import org.midonet.cluster.data.storage.{ReadOnlyStorage, StateTableStorage}
import org.midonet.cluster.models.Neutron.NeutronBgpSpeaker

class BgpSpeakerTranslator(protected val storage: ReadOnlyStorage,
                           protected val stateTableStorage: StateTableStorage)
    extends Translator[NeutronBgpSpeaker] {

    override protected def translateCreate(bgpSpeaker: NeutronBgpSpeaker)
    : OperationList = {
        List()
    }

    override protected def translateDelete(bgpSpeaker: NeutronBgpSpeaker)
    : OperationList = {
        List()
    }

    override protected def translateUpdate(bgpSpeaker: NeutronBgpSpeaker)
    : OperationList = {
        List()
    }
}

