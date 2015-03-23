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

package org.midonet.midolman.host.guice

import com.google.inject._

import org.midonet.config.ConfigProvider
import org.midonet.midolman.host.config.HostConfig
import org.midonet.midolman.host.scanner.InterfaceScanner
import org.midonet.midolman.host.services.HostService
import org.midonet.midolman.host.state.HostZkManager
import org.midonet.midolman.host.updater.{DefaultInterfaceDataUpdater, InterfaceDataUpdater}
import org.midonet.midolman.services.HostIdProviderService
import org.midonet.midolman.util.mock.MockInterfaceScanner

class MockHostModule extends PrivateModule {

    protected def configure() {
        binder.requireExplicitBindings()

        bind(classOf[InterfaceScanner]).to(classOf[MockInterfaceScanner])
        expose(classOf[InterfaceScanner])

        bind(classOf[InterfaceDataUpdater]).to(
            classOf[DefaultInterfaceDataUpdater])
        expose(classOf[InterfaceDataUpdater])

        requireBinding(classOf[ConfigProvider])
        bind(classOf[HostConfig]).toProvider(
            classOf[HostConfigProvider]).asEagerSingleton()
        expose(classOf[HostConfig])
        expose(classOf[HostIdProviderService])
        requireBinding(classOf[HostZkManager])

        bind(classOf[HostIdProviderService]).to(
            classOf[HostService]).in(classOf[Singleton])
        expose(classOf[HostIdProviderService])

        bind(classOf[HostService]).in(classOf[Singleton])
        expose(classOf[HostService])
    }
}
