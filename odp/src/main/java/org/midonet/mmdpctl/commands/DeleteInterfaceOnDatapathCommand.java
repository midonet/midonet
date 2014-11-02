/*
 * Copyright 2014 Midokura SARL
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

package org.midonet.mmdpctl.commands;

import org.midonet.mmdpctl.commands.callables.DeleteInterfaceOnDatapathCallback;
import org.midonet.mmdpctl.commands.results.DeleteInterfaceOnDatapathResult;
import org.midonet.odp.protos.OvsDatapathConnection;

import java.util.concurrent.Future;

public class DeleteInterfaceOnDatapathCommand
        extends Command<DeleteInterfaceOnDatapathResult> {

    private String interfaceName;
    private String datapathName;

    public DeleteInterfaceOnDatapathCommand(String interfaceName,
                                            String datapathName) {
        this.interfaceName = interfaceName;
        this.datapathName = datapathName;
    }

    public Future<DeleteInterfaceOnDatapathResult>
    execute(OvsDatapathConnection connection) {
        return run(new DeleteInterfaceOnDatapathCallback(
                connection, interfaceName, datapathName));
    }
}
