/*
 * Copyright (c) 2014 Midokura SARL, All Rights Reserved.
 */
package org.midonet.tools.ovsdb.cli;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.EnumMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.ResourceBundle;

import javax.annotation.concurrent.Immutable;

import com.google.gson.internal.Pair;

import org.opendaylight.controller.sal.core.Node;
import org.opendaylight.ovsdb.plugin.ConfigurationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A command line client for an OVS-DB database server.
 */
@Immutable
public enum OvsDbCli {

    USAGE {
        @Override
        protected void run(final Deque<String> params) {
            throw new IllegalArgumentException();
        }
    },
    HELP {
        @Override
        protected void run(final Deque<String> params) {
            System.out.printf(
                valueOf(OvsDbCli.class,
                        params.pop().toUpperCase(Locale.ROOT)).help());
        }
    },
    EXEC {
        @Override
        protected void run(final Deque<String> params) throws Exception {

            // Parse the run options.
            LinkedList<String> arguments = new LinkedList<>();
            Map<Option, String> options =
                parseOptions(params, Option.class, arguments);

            if (options.isEmpty())
                throw new IllegalArgumentException(EXEC.help());

            String address = options.get(Option.DB_ADDRESS);
            String port = options.get(Option.DB_PORT);
            String methodName = options.get(Option.METHOD);

            if (null == address)
                throw new IllegalAccessException(
                    "The DB address cannot be null.");
            if (null == port)
                throw new IllegalArgumentException(
                    "The DB port cannot be null.");
            if (null == methodName) {
                // List all methods.
                System.out.println("No method specified. "
                                   + "The available method are:\n");
                for (Method method :
                    ConfigurationService.class.getDeclaredMethods()) {
                    if (validateMethod(method)) {
                        System.out.println("\t" + printMethod(method));
                    }
                }
                return;
            }

            // Parse the method arguments.
            List<Pair<OvsDbConverter.Entry, Object>> args =
                parseArguments(arguments);

            // Get the method corresponding to the name and argument list.
            Method method = getMethod(methodName, args);

            // Create the OVS-DB client.
            OvsDbDataClient client = new OvsDbDataClient();

            // Connect to the client.
            client.connect(address, port);
            try {

                log.info("Invoking method {} with {} arguments", methodName,
                         args);

                Object result = method.invoke(client, getValues(
                    args, client.getNode()));

                log.info("Result:\n{}", result);

            } catch (final Throwable ex) {
                log.error("An error occurred while executing the OVS-DB "
                          + "command ", ex);
            } finally {
                client.disconnect();
            }
        }
    };

    protected abstract void run(final Deque<String> params) throws Exception;

    /**
     * The program entry method.
     * @param args The list of command line arguments.
     */
    public static void main(String... args) {
        int status;
        try {
            process(args);
            status = 0;
        } catch (IllegalArgumentException | NoSuchElementException ex) {
            printUsage(ex);
            status = 1;
        } catch (NoSuchMethodException ex) {
            log.error("Method not found ", ex);
            status = 2;
        } catch (Throwable ex) {
            ex.printStackTrace();
            status = 3;
        }
        System.exit(status);
    }

    /**
     * Processes the arguments by executing the corresponding command.
     * @param args The list of command line arguments.
     */
    private static void process(String... args) throws Exception {
        Deque<String> params = new LinkedList<>(Arrays.asList(args));
        String command = params.pop().toUpperCase(Locale.ROOT);
        valueOf(command).run(params);
    }

    /**
     * Prints the usage information.
     * @param ex The exception.
     */
    private static void printUsage(Exception ex) {
        if (null == ex.getMessage()) {
            final StringBuilder builder = new StringBuilder();
            for (final OvsDbCli command : values())
                builder.append(command.usage());
            log.error(builder.toString(), ex);
        } else {
            log.error("{}", ex.getMessage(), ex);
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
     *
     * @param key The key.
     * @return The message.
     */
    String command(String key) {
        return ResourceBundle.getBundle(OvsDbCli.class.getName())
            .getString(name() + "." + key);
    }

    /**
     * Parses the command parameters for the options of the given enumeration
     * class. Any parameters that do not correspond to an option are left
     * in the parameters queue.
     *
     * @param params The command parameters.
     * @param type The enumeration class for the options.
     * @param args The arguments.
     * @param <T> The type of the enumeration class.
     * @return An enumeration map with the options and parameters.
     */
    private static <T extends Enum<T>> EnumMap<T, String> parseOptions(
        Deque<String> params, Class<T> type, Deque<String> args) {
        EnumMap<T, String> options = new EnumMap<T, String>(type);

        for (String param; null != (param = params.peek());) {
            params.pop();
            if (param.startsWith("--")) {
                param = param.substring(2).replace('-', '_').toUpperCase(
                    Locale.ROOT);
                options.put(valueOf(type, param), params.pop());
            } else {
                args.add(param);
            }
        }

        return options;
    }

    /**
     * Parses the method arguments.
     *
     * @param arguments The arguments.
     * @return Pairs of type objects for the given arguments list.
     */
    private static List<Pair<OvsDbConverter.Entry, Object>> parseArguments(
        Deque<String> arguments) {

        List<Pair<OvsDbConverter.Entry, Object>> args = new ArrayList<>();

        for (String arg; null != (arg = arguments.peek()); ) {
            arguments.pop();
            if (arg.startsWith("~")) {
                arg = arg.substring(1).replace('-', '_').toLowerCase(
                    Locale.ROOT);
                // Get the converter entry for this argument type.
                OvsDbConverter.Entry entry = OvsDbConverter.get(arg);

                // If there is no entry, thrown an exception.
                if (null == entry) {
                    throw new IllegalArgumentException(
                        "Unknown argument type: " + arg);
                }

                // Add the entry to the arguments list.
                if (entry.hasConverter()) {
                    args.add(new Pair<>(entry, entry.convert(arguments.pop())));
                } else {
                    args.add(new Pair<>(entry, null));
                }

            } else throw new IllegalArgumentException(
                "Unknown argument type: " + arg);
        }

        return args;
    }

    /**
     * Validates the method against the supported list of argument types.
     *
     * @param method The Java method.
     * @return True if the method can be called using the list of available
     *         type convertes, false otherwise.
     */
    private static boolean validateMethod(Method method) {
        for (Class<?> param : method.getParameterTypes()) {
            if (null == OvsDbConverter.get(param))
                return false;
        }
        return true;
    }

    /**
     * Gets the method of the ConfigurationService class with the specified name
     * and argument list.
     *
     * @param name The method name.
     * @param args The list of arguments specified as a list of converter
     *             entry, object value pairs. To call this method, the object
     *             values can be null.
     * @return The method, if such a method can be found.
     * @throws NoSuchMethodException There is no method with the given name
     *                               and/or arguments list.
     */
    private static Method getMethod(
        String name, List<Pair<OvsDbConverter.Entry, Object>> args)
        throws NoSuchMethodException {
        Class<?>[] types = new Class<?>[args.size()];
        for (int index = 0; index < args.size(); index++) {
            types[index] = args.get(index).first.type;
        }
        return ConfigurationService.class.getDeclaredMethod(name, types);
    }

    /**
     * Gets the list of values for the specified arguments list.
     *
     * @param args The list of arguments specified as a list of converter
     *             entry, object value pairs.
     * @param node The OVS-DB node that will automatically replace arguments
     *             of Node type.
     * @return An array of argument values, where each object is of the type
     *         specified by the converter entry.
     */
    private static Object[] getValues(
        List<Pair<OvsDbConverter.Entry, Object>> args, Node node) {
        Object[] values = new Object[args.size()];
        for (int index = 0; index < args.size(); index++) {
            if (Node.class.equals(args.get(index).first.type)) {
                values[index] = node;
            } else {
                values[index] = args.get(index).second;
            }
        }
        return values;
    }

    /**
     * Prints the signature of the specified method.
     *
     * @param method The Java method.
     * @return The method signature as a string.
     */
    private static String printMethod(Method method) {
        StringBuilder builder = new StringBuilder();

        builder.append(method.getName());
        builder.append("( ");
        for (Class<?> param : method.getParameterTypes()) {
            builder.append(param.getSimpleName() + " ");
        }
        builder.append(")");
        return builder.toString();
    }

    private enum Option {
        DB_ADDRESS,
        DB_PORT,
        METHOD,
    }

    private static final Logger log =
        LoggerFactory.getLogger(OvsDbDataClient.class);
}
