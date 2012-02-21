/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.sensor;

import org.slf4j.Logger;

import java.util.List;

/**
 * Author: Toader Mihai Claudiu <mtoader@gmail.com>
 * <p/>
 * Date: 11/20/11
 * Time: 10:23 PM
 */
public class DrainTargets {

    public static ProcessOutputDrainer.DrainTarget noneTarget() {
        return new ProcessOutputDrainer.DrainTarget() {
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
    public static ProcessOutputDrainer.DrainTarget slf4jTarget(final Logger logger, final String prefix) {
        return new ProcessOutputDrainer.DrainTarget() {
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


    public static ProcessOutputDrainer.DrainTarget stringCollector(final List<String> stdOutStrList) {
        return stringCollector(stdOutStrList, null);
    }

    public static ProcessOutputDrainer.DrainTarget stringCollector(final List<String> stdOutStrList, final List<String> stdErrStrList) {
        return new ProcessOutputDrainer.DrainTarget() {
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
}
