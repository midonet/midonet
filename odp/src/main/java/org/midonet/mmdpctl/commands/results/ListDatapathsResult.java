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
import java.util.Set;

public class ListDatapathsResult implements Result {
    Set<Datapath> datapaths;

    public ListDatapathsResult(Set<Datapath> datapaths) {
        this.datapaths = datapaths;
    }

    @Override
    public void printResult(OutputStream stream) {
        PrintStream out = new PrintStream(stream);
        if (datapaths.size() > 0) {
        out.println("Found " + datapaths.size() + " datapaths:");
        for (Datapath datapath : datapaths) {
            out.println("\t"+datapath.getName());
        }
        } else {
            out.println("Could not find any installed datapath.");
        }
    }

    @Override
    public void printResult() {
        printResult(System.out);
    }

}
