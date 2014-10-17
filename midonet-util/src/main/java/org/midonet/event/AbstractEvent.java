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

package org.midonet.event;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.text.MessageFormat;
import java.util.MissingResourceException;
import java.util.ResourceBundle;

/**
 * Abstract class to handle Event. This is responsible for reading
 * resource bundle to get message and its level, and log to event
 * specific logger.
 */
public abstract class AbstractEvent {

    private static final Logger log = LoggerFactory.getLogger(
            AbstractEvent.class);
    ResourceBundle resourceBundle = ResourceBundle.getBundle("EventMessage");

    /**
     * Key for the resource bundle as well as logger, e.g.
     * org.midonet.event.agent.X.
     * This is supposed to be set by concrete classes' ctor.
     */
    private String eventKey = null;

    protected AbstractEvent(String key) {
        eventKey = key;
    }

    protected Logger getLogger() {
        return LoggerFactory.getLogger(eventKey);
    }

    /**
     * Returns resource bundle value given an event and an item
     *
     * @param event type of event for the resource
     * @param item  e.g. message, level, explanation, etc.
     * @return
     */
    protected String getResourceBundle(String event, String item) {

        String fullKey = String.format(eventKey + ".%s.%s", event, item);
        try {

            String string = resourceBundle.getString(fullKey);
            return string;
        } catch (MissingResourceException e) {
            log.warn("Resource bundle for {} not found", fullKey);
            return "Resource not found for" + fullKey;
        }
    }

    /**
     * Returns format string given the event
     *
     * @param event string name of the event, e.g. CREATE, DELETE
     * @return format string defined in resource bundle with the event. An
     * example key would be
     * org.midonet.event.topology.Router.CREATE.message.
     */
    protected String getMessageFormatByEvent(String event) {

        String format = getResourceBundle(event, "message");

        if (format == null) {
            String warnMsg = "Message format for event=" + event + "not found.";
            log.warn(warnMsg);
            format = warnMsg;
        }
        return format;
    }


    /**
     * Returns log level string given the event
     *
     * @param event
     * @return
     */
    protected String getMessageLevel(String event) {

        String level = getResourceBundle(event, "level");
        if (level == null) {
            String warnMsg = "Message level for event=" + event + "not found.";
            log.warn(warnMsg);
            level = "ERROR";
        }
        return level;
    }


    protected String getMessage(String event, Object... args) {
        String msg = MessageFormat.format(getMessageFormatByEvent(event), args);
        return msg;
    }

    /**
     * Handles the event. For now, just log with message and level specified
     * in the resource bundle.
     *
     * @param event name of the event, e.g. CREATE, DELETE
     * @param args  parameters to pass to the format string
     */
    protected void handleEvent(String event, Object... args) {
        String msg = getMessage(event, args);
        String level = getMessageLevel(event);

        switch (level.toUpperCase()) {
            case "DEBUG":
                getLogger().debug(msg);
                break;
            case "ERROR":
                getLogger().error(msg);
                break;
            case "INFO":
                getLogger().info(msg);
                break;
            case "TRACE":
                getLogger().trace(msg);
                break;
            case "WARN":
                getLogger().warn(msg);
                break;
            default:
                log.error("Could not find level={}}", level);
                break;
        }
    }
}
