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

package org.midonet

/**
  * Package object that handle package level constants. All minions log names
  * must be specified here so all of them follow the same pattern and can be
  * disabled/enabled/configured following a hierarchy.
  */
package object services {

    final val AgentServicesLog = "org.midonet.services"

    final val FlowStateLog =  "org.midonet.services.flowstate"

    final val FlowStateStreamLog = "org.midonet.services.flowstate.stream"

    final val FlowStateTransferLog = "org.midonet.services.flowstate.transfer"

    final val BindingApiLog = "org.midonet.services.binding-api"
}
