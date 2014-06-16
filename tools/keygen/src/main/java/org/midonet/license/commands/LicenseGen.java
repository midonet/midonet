/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.license.commands;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.*;
import javax.annotation.concurrent.Immutable;
import javax.security.auth.x500.X500Principal;

import net.java.truelicense.core.License;
import net.java.truelicense.core.LicenseManagementException;
import net.java.truelicense.core.LicenseParameters;
import net.java.truelicense.core.LicenseVendorContext;
import net.java.truelicense.core.LicenseVendorManager;
import net.java.truelicense.core.io.Store;
import org.midonet.license.LicenseInformation;
import org.midonet.license.LicenseManager;
import org.midonet.license.LicenseVersion;

/**
 * A tool that creates license keys for MidoNet.
 */
@Immutable
public enum LicenseGen {

    USAGE {
        @Override protected void run(final Deque<String> params) {
            throw new IllegalArgumentException();
        }
    },
    CREATE {
        @Override protected void run(final Deque<String> params) {
            // Parse the create options.
            final Map<CreateOption, String>
                options = parse(params, CreateOption.class);

            if (options.isEmpty())
                throw new IllegalArgumentException(CREATE.help());

            X500Principal issuer = parseX500Principal(
                options.get(CreateOption.ISSUER), MessageCode.INVALID_ISSUER,
                LicenseManager.ISSUER_X500);
            X500Principal holder = parseX500Principal(
                options.get(CreateOption.HOLDER), MessageCode.INVALID_HOLDER,
                MessageCode.NO_HOLDER);
            Date startDate = parseDate(
                options.get(CreateOption.START_DATE), MessageCode.INVALID_START_DATE,
                (Date)null);
            Date endDate = parseDate(
                options.get(CreateOption.END_DATE), MessageCode.INVALID_END_DATE,
                (Date)null);
            Integer duration = parseInt(
                options.get(CreateOption.DURATION), MessageCode.INVALID_DURATION,
                (Integer)null);
            String description = options.get(CreateOption.DESCRIPTION);
            String out = options.get(CreateOption.OUT);
            LicenseVersion mnVersion = parseVersion(
                options.get(CreateOption.MN_VERSION),
                MessageCode.INVALID_MN_VERSION, MessageCode.NO_MN_VERSION);
            Integer mnAgentQuota = parseInt(
                options.get(CreateOption.MN_AGENT_QUOTA),
                MessageCode.INVALID_MN_AGENT_QUOTA, MessageCode.NO_MN_AGENT_QUOTA);
            String mnName = options.get(CreateOption.MN_NAME);
            UUID mnId = parseUuid(
                options.get(CreateOption.MN_ID), MessageCode.INVALID_MN_ID,
                null);

            Date now = new Date();

            if (null == out)
                throw new IllegalArgumentException(message(MessageCode.NO_OUT));
            if (null == startDate)
                startDate = now;
            if (null == endDate && null == duration)
                throw new IllegalArgumentException(message(MessageCode.NO_END_DATE));
            if (null == endDate) {
                Calendar calendar = Calendar.getInstance();
                calendar.setTime(startDate);
                calendar.add(Calendar.DATE, duration);
                endDate = calendar.getTime();
            }

            final File fileOut = new File(out);

            if (fileOut.exists()) {
                BufferedReader reader = new BufferedReader(
                    new InputStreamReader(System.in));
                String response;
                String responseYes = message(MessageCode.CONFIRM_OVERWRITE_YES);
                String responseNo = message(MessageCode.CONFIRM_OVERWRITE_NO);
                do {
                    System.out.println(
                        String.format(message(MessageCode.CONFIRM_OVERWRITE),
                            responseYes, responseNo));
                    try {
                        response = reader.readLine().trim();
                    } catch (final IOException ex) {
                        throw new IllegalArgumentException(message(
                            MessageCode.IO_WRITE));
                    }
                    if (response.equalsIgnoreCase(responseNo))
                        return;
                } while(!response.equalsIgnoreCase(responseYes));
            }

            // Display the license information.
            System.out.printf(message(MessageCode.LICENSE_CONFIRMATION),
                              issuer.toString(), holder.toString(),
                              startDate.toString(), endDate.toString(),
                              mnVersion.toString(), mnAgentQuota.toString(),
                              mnName != null ? mnName :
                                  message(MessageCode.VALUE_NOT_SET),
                              mnId != null ? mnId :
                                  message(MessageCode.VALUE_NOT_SET));

            final Store store = context.fileStore(fileOut);
            License license = context.license();

            license.setIssuer(issuer);
            license.setHolder(holder);
            license.setIssued(now);
            license.setNotBefore(startDate);
            license.setNotAfter(endDate);
            license.setSubject(LicenseManager.PRODUCT_NAME);
            license.setInfo(description);
            license.setExtra(new LicenseInformation(
                null,
                UUID.randomUUID(),
                mnId,
                LicenseManager.PRODUCT_NAME,
                mnVersion.getMajor(),
                mnVersion.getMinor(),
                mnAgentQuota
            ));

            try {
                manager.create(license, store);

                System.out.printf(message(MessageCode.OUT_SUCCESS),
                                  fileOut);
            } catch (final LicenseManagementException ex) {
                throw new UnsupportedOperationException(
                    String.format(message(MessageCode.LICENSE_EXCEPTION),
                                  ex.getMessage()));
            }
        }
    },
    HELP {
        @Override protected void run(final Deque<String> params) {
            System.out.printf(valueOf(LicenseGen.class,
                                      params.pop()
                                            .toUpperCase(Locale.ROOT)).help());
        }
    },
    VERSION {
        @Override protected void run(final Deque<String> params) {
            System.out.printf(command("version"),
                              LicenseGen.class.getSimpleName());
        }
    };

    final LicenseVendorManager manager = LicenseManager.manager();
    final LicenseVendorContext context = manager.context();
    final LicenseParameters parameters = manager.parameters();

    /**
     * Runs the selected command.
     * @param params The command parameters.
     * @throws Exception The exception.
     */
    protected abstract void run(Deque<String> params) throws Exception;

    public static void main(String... args) {
        System.exit(processAndHandleExceptions(args));
    }

    private static int processAndHandleExceptions(final String... args) {
        int status;
        try {
            process(args);
            status = 0;
        } catch (final IllegalArgumentException ex) {
            printUsage(ex);
            status = 1;
        } catch (final NoSuchElementException ex) {
            printUsage(ex);
            status = 1;
        } catch (final UnsupportedOperationException ex) {
            System.err.println(ex.getMessage());
            status = 2;
        } catch (final Throwable ex) {
            ex.printStackTrace();
            status = 2;
        }
        return status;
    }

    private static void process(final String... args) throws Exception {
        final Deque<String> params = new LinkedList<>(Arrays.asList(args));
        final String command = params.pop().toUpperCase(Locale.ROOT);
        valueOf(command).run(params);
    }

    private static void printUsage(Exception ex) {
        if (null == ex.getMessage()) {
            final StringBuilder builder = new StringBuilder();
            for (final LicenseGen commands : values())
                builder.append(commands.usage());
            System.out.println(builder.toString());
        } else {
            System.out.println(ex.getMessage());
        }
    }

    /**
     * Returns the usage information string.
     * @return The usage string.
     */
    private String usage() {
        return command("usage");
    }

    /**
     * Returns the help.
     * @return The help string.
     */
    private String help() {
        return command("help");
    }

    /**
     * Returns a user message for the specified key.
     * @param key The key.
     * @return The message.
     */
    String command(String key) {
        return ResourceBundle
                .getBundle(LicenseGen.class.getName())
                .getString(name() + "." + key);
    }

    /**
     * Returns the message for the specified message code.
     * @param code The message code.
     * @return The message.
     */
    static String message(MessageCode code) {
        return ResourceBundle
                .getBundle(LicenseGen.class.getName())
                .getString("MESSAGE." + code.name());
    }

    /**
     * Parses the command parameters for the options of the given enumeration
     * class. Any parameters that do not correspond to an option are left
     * in the parameters queue.
     * @param params The command parameters.
     * @param type The enumeration class for the options.
     * @param <T> The type of the enumeration class.
     * @return An enumeration map with the options and parameters.
     */
    private static <T extends Enum<T>> EnumMap<T, String> parse(
        final Deque<String> params,
        final Class<T> type) {
        final EnumMap<T, String> options = new EnumMap<T, String>(type);
        final Deque<String> args = new LinkedList<String>();

        for (String param; null != (param = params.peek());) {
            params.pop();
            if (param.startsWith("--")) {
                param = param.substring(2).replace('-', '_').toUpperCase(
                    Locale.ROOT);
                options.put(valueOf(type, param), params.pop());
            } else {
                args.push(param);
            }
        }

        return options;
    }

    private static X500Principal parseX500Principal(String value,
                                                    MessageCode codeFormat,
                                                    MessageCode codeNull)
            throws IllegalArgumentException {
        if (null == value)
            throw new IllegalArgumentException(message(codeNull));
        try {
            return new X500Principal(value);
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException(message(codeFormat));
        }
    }

    private static X500Principal parseX500Principal(String value,
                                                    MessageCode code,
                                                    X500Principal defaultValue)
            throws IllegalArgumentException {
        return null != value ?
            parseX500Principal(value, code, code) : defaultValue;
    }

    private static Date parseDate(String value, MessageCode codeFormat,
                                  MessageCode codeNull)
            throws IllegalArgumentException {
        if (null == value)
            throw new IllegalArgumentException(message(codeNull));
        try {
            if (value.length() != 8)
                throw new IllegalArgumentException(message(codeFormat));
            Calendar calendar = Calendar.getInstance();
            calendar.set(
                Integer.parseInt(value.substring(0, 4)),
                Integer.parseInt(value.substring(4, 6)) - 1,
                Integer.parseInt(value.substring(6)));
            return calendar.getTime();
        } catch (final NumberFormatException ex) {
            throw new IllegalArgumentException(message(codeFormat));
        }
    }

    private static Date parseDate(String value, MessageCode code,
                                  Date defaultValue)
            throws IllegalArgumentException {
        return null != value ? parseDate(value, code, code) : defaultValue;
    }

    private static Integer parseInt(String value, MessageCode codeFormat,
                                    MessageCode codeNull) {
        if (null == value)
            throw new IllegalArgumentException(message(codeNull));
        try {
            return Integer.parseInt(value);
        } catch (final NumberFormatException ex) {
            throw new IllegalArgumentException(message(codeFormat));
        }
    }

    private static Integer parseInt(String value, MessageCode code,
                                    Integer defaultValue) {
        return null != value ? parseInt(value, code, code) : defaultValue;
    }

    private static LicenseVersion parseVersion(String value, MessageCode codeFormat,
                                               MessageCode codeNull)
            throws IllegalArgumentException {
        if (null == value)
            throw new IllegalArgumentException(message(codeNull));
        try {
            String[] tokens = value.split("\\.");
            int major = Integer.parseInt(tokens[0].trim());
            int minor = Integer.parseInt(tokens[1].trim());
            return new LicenseVersion(major, minor);
        } catch (final IndexOutOfBoundsException ex) {
            throw new IllegalArgumentException(message(codeFormat));
        }
    }

    private static UUID parseUuid(String value, MessageCode code)
            throws IllegalArgumentException {
        try {
            return UUID.fromString(value);
        } catch (final IllegalArgumentException ex) {
            throw new IllegalArgumentException(message(code));
        } catch (final NullPointerException ex) {
            throw new IllegalArgumentException(message(code));
        }
    }

    private static UUID parseUuid(String value, MessageCode code,
                                  UUID defaultValue)
            throws IllegalArgumentException {
        return null != value ? parseUuid(value, code) : defaultValue;
    }

    private enum MessageCode {
        INVALID_ISSUER,
        INVALID_HOLDER,
        INVALID_START_DATE,
        INVALID_END_DATE,
        INVALID_DURATION,
        INVALID_OUT,
        INVALID_MN_VERSION,
        INVALID_MN_AGENT_QUOTA,
        INVALID_MN_NAME,
        INVALID_MN_ID,
        NO_HOLDER,
        NO_START_DATE,
        NO_END_DATE,
        NO_OUT,
        NO_MN_VERSION,
        NO_MN_AGENT_QUOTA,
        NO_FILE_WRITE,
        IO_WRITE,
        UNEXPECTED_DURATION,
        CONFIRM_OVERWRITE,
        CONFIRM_OVERWRITE_YES,
        CONFIRM_OVERWRITE_NO,
        LICENSE_EXCEPTION,
        LICENSE_CONFIRMATION,
        VALUE_NOT_SET,
        OUT_SUCCESS
    }

    private enum CreateOption {
        ISSUER,
        HOLDER,
        START_DATE,
        END_DATE,
        DURATION,
        DESCRIPTION,
        OUT,
        MN_VERSION,
        MN_AGENT_QUOTA,
        MN_NAME,
        MN_ID
    }
}
