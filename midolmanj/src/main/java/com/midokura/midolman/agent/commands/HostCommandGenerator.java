/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.commands;

import com.midokura.midolman.agent.commands.executors.CommandProperty;
import com.midokura.midolman.packets.MAC;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.StringDescription;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

import java.net.InetAddress;
import java.util.Arrays;

import static com.midokura.hamcrest.RegexMatcher.matchesRegex;
import static com.midokura.midolman.agent.commands.executors.CommandProperty.*;
import static com.midokura.midolman.agent.commands.executors.CommandProperty.address;
import static com.midokura.midolman.agent.state.HostDirectory.Command;
import static com.midokura.midolman.agent.state.HostDirectory.Command.AtomicCommand.OperationType;
import static com.midokura.midolman.agent.state.HostDirectory.Interface;

/**
 * This class will take care of generating a HostDirectory.Command object given
 * two HostDirectory.Interface objects (with the first being a null object).
 * <p/>
 * Also takes care of validation of the parameters.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 2/22/12
 */
public class HostCommandGenerator {

    public Command createUpdateCommand(Interface current,
                                       Interface updated)
        throws DataValidationException {

        validateRequest(current, updated);

        // In this command we'll store the configuration changes
        Command command = new Command();
        command.setInterfaceName(updated.getName());

        // do we need to create a new interface? we know the new one is not empty
        if (current == null) {
            // create interface
            command.addAtomicCommand(
                newCommand(iface, OperationType.SET,
                           updated.getType().toString())
            );
        }

        // If MACs are different, create atomic command to update them
        if ((current == null && updated.getMac() != null) ||
            (current != null
                && !Arrays.equals(current.getMac(),
                                  updated.getMac()))) {
            command.addAtomicCommand(
                newCommand(mac, OperationType.SET,
                           new MAC(updated.getMac()).toString()));
        }

        // Look for addresses no longer used
        if (current != null && current.getAddresses() != null) {
            for (InetAddress inetAddress : current.getAddresses()) {
                if (!updated.hasAddress(inetAddress)) {
                    command.addAtomicCommand(
                        newCommand(address, OperationType.DELETE,
                                   inetAddress.getHostAddress()));
                }
            }
        }

        // Look for addresses new to add
        if (updated.getAddresses() != null) {
            for (InetAddress inetAddress : updated.getAddresses()) {
                if (current == null || !current.hasAddress(inetAddress)) {
                    command.addAtomicCommand(
                        newCommand(address, OperationType.SET,
                                   inetAddress.getHostAddress()));
                }
            }
        }

        return command;
    }

    /**
     * It will check the parameters for not allowed operations and if any
     * invalid operation is found (a not allowed by our rules) it will throw an
     * DataValidationException.
     *
     * Otherwise the method will complete properly.
     *
     * @param current is the current state of the interface we want to change
     * @param updated is the desired state of the interface we want to change
     *
     * @throws DataValidationException
     */
    private void validateRequest(Interface current, Interface updated)
        throws DataValidationException {
        // interface should have a proper name
        validateThat(
            "The interface name should be properly formed!",
            updated.getName(),
            allOf(notNullValue(), matchesRegex("[-a-zA-Z0-9]+{1,15}")));

        // compare the name against the old name
        if (current != null) {
            validateThat("The interface should not be changed!",
                         updated.getName(),
                         equalTo(current.getName()));
        }

        // validate interface type
        if (current == null) {
            validateThat("The interface type should only be of specific type",
                         updated.getType(),
                         anyOf(
                             equalTo(Interface.Type.Tunnel),
                             equalTo(Interface.Type.Virtual)));
        } else {
            validateThat("The interface type can't be changed!",
                         updated.getType(),
                         equalTo(current.getType()));
        }

        // validate interface status change (we only accept the setting of the link up status)
        validateThat("Only the UP interface status bit can be changed.",
                     updated.getStatus(),
                     anyOf(
                         equalTo(0),
                         equalTo(Interface.StatusType.Up.getMask()))
        );
    }

    private Command.AtomicCommand newCommand(CommandProperty property,
                                             OperationType operation,
                                             String value) {
        Command.AtomicCommand atomicCommand = new Command.AtomicCommand();

        atomicCommand.setProperty(property);
        atomicCommand.setOpType(operation);
        atomicCommand.setValue(value);

        return atomicCommand;
    }

    private <T> void validateThat(String message,
                                  T item, Matcher<? extends T> matcher)
        throws DataValidationException {
        if (!matcher.matches(item)) {
            Description description = new StringDescription();
            description.appendText(message)
                       .appendText("\nExpected: ")
                       .appendDescriptionOf(matcher)
                       .appendText("\n     but: ");
            matcher.describeMismatch(item, description);

            throw new DataValidationException(description.toString());
        }
    }
}
