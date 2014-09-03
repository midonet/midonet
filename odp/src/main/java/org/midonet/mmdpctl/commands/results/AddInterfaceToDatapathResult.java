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

package org.midonet.mmdpctl.commands.results;

import org.midonet.odp.Datapath;

import java.io.OutputStream;
import java.io.PrintStream;

public class AddInterfaceToDatapathResult implements Result {

    Datapath datapath;
    boolean isSucceeded;

    public AddInterfaceToDatapathResult(Datapath datapath, boolean isSucceeded) {
        this.datapath = datapath;
        this.isSucceeded = isSucceeded;
    }

    @Override
    public void printResult(OutputStream stream) {
        PrintStream out = new PrintStream(stream);
        if (isSucceeded) {
            out.println("Succeeded to add the interface to the datapath.");
        } else {
            out.println("Failed to add the interface to the datapath.");
        }
    }

    @Override
    public void printResult() {
        printResult(System.out);
    }
}
