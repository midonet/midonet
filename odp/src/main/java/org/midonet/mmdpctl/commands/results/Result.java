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

/**
 * As this is the result for a Command Line tool, all the command results need to be displayed correctly on the screen.
 * This interface provides the method that the tool will use to display the results.
 */
public interface Result {

    /**
     * Outputs the result to the screen in a nice formatted way.
     */
    void printResult();

    void printResult(OutputStream out);
}
