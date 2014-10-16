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

import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.PrintWriter;
import java.util.*;

import org.midonet.odp.Datapath;
import org.midonet.odp.Flow;

public class DumpDatapathResult implements Result {

    static public interface Filter {
        boolean predicate(Flow flow);
    }

    Set<Flow> flows;
    Datapath datapath;

    public DumpDatapathResult(Datapath datapath, Set<Flow> flows) {
        this.flows = flows;
        this.datapath = datapath;
    }

    public ArrayList<Flow> sortFlows(Set<Flow> flows) {
        ArrayList<Flow> toPrint = new ArrayList<>(flows);

        Collections.sort(toPrint, new Comparator<Flow>() {
            @Override public int compare(Flow o1, Flow o2) {
                long t1 = 0, t2 = 0;
                if (o1.getLastUsedTime() != null) t1 = o1.getLastUsedTime();
                if (o2.getLastUsedTime() != null) t2 = o2.getLastUsedTime();
                return Long.compare(t1, t2); // oldest first (lowest long)
            }
        });

        return toPrint;
    }

    @Override
    public void printResult(OutputStream stream) {
        PrintStream out = new PrintStream(stream);
        out.println("" + flows.size() + " flows");
        for (Flow flow : sortFlows(flows)) {
            out.println("  Flow:");
            for (String s: flow.toPrettyStrings()) {
                out.println("    " + s);
            }
        }
    }

    @Override
    public void printResult() {
        printResult(System.out);
    }
}
