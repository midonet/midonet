package com.midokura.tools.process;

import org.slf4j.Logger;

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
                if ( logger.isDebugEnabled()) {
                    logger.debug(String.format("%s <stdout> %s", prefix, line));
                }
            }

            @Override
            public void errLine(String line) {
                if ( logger.isDebugEnabled()) {
                    logger.debug(String.format("%s <stderr> %s", prefix, line));
                }
            }
        };
    }
}
