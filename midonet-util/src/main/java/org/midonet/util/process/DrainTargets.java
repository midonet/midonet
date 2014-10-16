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

package org.midonet.util.process;

import org.slf4j.Logger;

import java.util.List;

import static org.midonet.util.process.ProcessOutputDrainer.DrainTarget;

/**
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 11/20/11
 */
public class DrainTargets {

    /**
     * This will create a simple drainer that just eats up the output. Useful
     * if you want to make sure that process is not blocking.
     *
     * @return a DrainTarget implementation that just eats up the input lines
     */
    public static DrainTarget noneTarget() {
        return new DrainTarget() {
            @Override
            public void outLine(String line) {
                // don't do anything.
            }

            @Override
            public void errLine(String line) {
                // don't do anything.
            }
        };
    }

    /**
     * This will create a drainer that will copy each line of the input to the
     * logger passed using a specific marker on the logger output (so you can
     * easily look for the process stdout/stderr dumps).
     *
     * @param logger the target logger
     * @param prefix the prefix to use on the message
     * @return a DrainTarget implementation
     */
    public static DrainTarget slf4jTarget(final Logger logger,
                                          final String prefix) {
        return new DrainTarget() {
            @Override
            public void outLine(String line) {
                logger.debug("{}:<stdout> {}", prefix, line);
            }

            @Override
            public void errLine(String line) {
                logger.debug("{}:<stderr> {}", prefix, line);
            }
        };
    }

    /**
     * This will create a DrainTarget that will just copy the input lines to
     * the provided list.
     * <p/>
     * It will do this just for the std out and just ignore the std err output.
     *
     * @param stdOutStrList is the target list that will collect the output
     * @return a proper DrainTarget implementation.
     */
    public static DrainTarget stringCollector(
        final List<String> stdOutStrList) {
        return stringCollector(stdOutStrList, null);
    }

    /**
     * This will create a DrainTarget that will just copy the input lines to
     * the provided list.
     * <p/>
     * It will do this just for the stdout and just ignore the stderr output.
     *
     * @param stdOutStrList is the target list that will collect the std output
     * @param stdErrStrList is the target list that will collect the err output
     * @return a proper DrainTarget implementation.
     */
    public static DrainTarget stringCollector(final List<String> stdOutStrList,
                                              final List<String> stdErrStrList) {
        return new DrainTarget() {
            @Override
            public void outLine(String line) {
                if (stdOutStrList != null) {
                    stdOutStrList.add(line);
                }
            }

            @Override
            public void errLine(String line) {
                if (stdErrStrList != null) {
                    stdErrStrList.add(line);
                }
            }
        };
    }

    /**
     * A drain target that stores the output as well as log it
     */
    public static DrainTarget collectorLogger(
            final List<String> stdOutStrList,
            final List<String> stdErrStrList,
            final Logger logger,
            final String prefix) {

        return new DrainTarget() {
            @Override
            public void outLine(String line) {
                if (stdOutStrList != null) {
                    stdOutStrList.add(line);
                }
                logger.debug("{}:<stdout> {}", prefix, line);
            }

            @Override
            public void errLine(String line) {
                if (stdErrStrList != null) {
                    stdErrStrList.add(line);
                }
                logger.debug("{}:<stderr> {}", prefix, line);
            }
        };
    }
}
