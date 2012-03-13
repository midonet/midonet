/*
 * Copyright 2012 Midokura Pte. Ltd.
 */

package com.midokura.midolman.agent.command;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.midokura.midolman.agent.state.HostDirectory;
import com.midokura.midolman.packets.IPv4;

public abstract class TypeValidator<T> {

    private final static Logger log =
        LoggerFactory.getLogger(TypeValidator.class);

    public abstract T validateAndConvert(CommandProperty property,
                                         String value);

    public static class IntTypeValidator extends TypeValidator<Integer> {
        @Override
        public Integer validateAndConvert(CommandProperty property,
                                          String value) {
            Integer intValue;
            intValue = Integer.parseInt(value);
            return intValue;
        }
    }

    public static class StringValidator extends TypeValidator<String> {
        @Override
        public String validateAndConvert(CommandProperty property,
                                         String value) {
            return value;
        }
    }

    public static class InetAddressValidator extends TypeValidator<String> {
        static final Pattern addressPattern =
            Pattern.compile("(\\d+).(\\d+)\\.(\\d+)\\.(\\d+)(?:/(\\d+))?");

        @Override
        public String validateAndConvert(CommandProperty property,
                                         String value) {

            Matcher matcher = addressPattern.matcher(value);

            if (matcher.matches()) {
                int byte1 = Integer.parseInt(matcher.group(1));
                int byte2 = Integer.parseInt(matcher.group(2));
                int byte3 = Integer.parseInt(matcher.group(3));
                int byte4 = Integer.parseInt(matcher.group(4));

                if (byte1 <= 255 && byte2 <= 255 &&
                    byte3 <= 255 && byte4 <= 255)
                {
                    int mask = 32;
                    if ( matcher.group(5) != null ) {
                        mask = Integer.parseInt(matcher.group(5));
                    }

                    if ( mask > 32 ) {
                        mask = 32;
                    }

                    return String.format("%d.%d.%d.%d/%d", byte1, byte2, byte3, byte4, mask);
                }
            }

            throw new IllegalArgumentException("The address value is not an ip address of the form 255.255.255.255(/32)?");
        }
    }

    public static class InterfaceTypeValidator
        extends TypeValidator<HostDirectory.Interface.Type> {
        @Override
        public HostDirectory.Interface.Type validateAndConvert(
            CommandProperty property, String value) {
            return HostDirectory.Interface.Type.valueOf(value);
        }
    }
}

