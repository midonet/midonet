/*
 * Copyright 2012 Midokura Europe SARL
 */
package com.midokura.midolman.agent.commands;

import com.midokura.midolman.packets.MAC;
import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.StringDescription;
import static org.hamcrest.CoreMatchers.allOf;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.equalTo;
import static org.hamcrest.CoreMatchers.notNullValue;

import com.midokura.midolman.agent.state.HostDirectory;

import java.net.InetAddress;
import java.util.Arrays;

import static com.midokura.hamcrest.RegexMatcher.matchesRegex;
import static com.midokura.midolman.agent.state.HostDirectory.Command;
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

    public Command createUpdateCommand(Interface curHostInterface,
                                       Interface newHostInterface)
        throws DataValidationException {

        // interface should have a proper name
        validateThat(
            "The interface name should be properly formed!",
            newHostInterface.getName(),
            allOf(notNullValue(), matchesRegex("[-a-zA-Z0-9]+")));

        // compare the name against the old name
        if (curHostInterface != null) {
            validateThat("The interface should not be changed!",
                         newHostInterface.getName(),
                         equalTo(curHostInterface.getName()));
        }

        // validate interface type
        if (curHostInterface == null) {
            validateThat("The interface type should only be of specific type",
                         newHostInterface.getType(),
                         anyOf(
                             equalTo(Interface.Type.Tunnel),
                             equalTo(Interface.Type.Virtual)));
        } else {
            validateThat("The interface type can't be changed!",
                         newHostInterface.getType(),
                         equalTo(curHostInterface.getType()));
        }

        // validate interface status change (we only accept the setting of the link up status)
        validateThat("Only the UP interface status bit can be changed.",
                     newHostInterface.getStatus(),
                     anyOf(
                         equalTo(0),
                         equalTo(Interface.StatusType.Up.getMask()))
                     );

        // TODO: finish this once we get the classes from Rossella
//        validateThat("");

        // In this command we'll store the configuration changes
        Command command = new Command();
        command.setInterfaceName(newHostInterface.getName());

        // do we need to create a new interface? we know the new one is not empty
        if (curHostInterface == null) {
            // create interface
            Command.AtomicCommand atomicCommand = new Command.AtomicCommand();
            atomicCommand.setProperty("interface");
            atomicCommand.setOpType(Command.AtomicCommand.OperationType.SET);
            atomicCommand.setValue(newHostInterface.getType().toString());
            return command;
        }

        // If MACs are different, create atomic command to update them
        if (!Arrays.equals(curHostInterface.getMac(), newHostInterface.getMac())) {
            Command.AtomicCommand atomicCommand = new Command.AtomicCommand();
            atomicCommand.setProperty("mac");
            atomicCommand.setOpType(Command.AtomicCommand.OperationType.SET);
            MAC mac = new MAC(newHostInterface.getMac());
            atomicCommand.setValue(mac.toString());
            command.addAtomicCommand(atomicCommand);
        }

        // Look for addresses no longer used
        for (InetAddress address : curHostInterface.getAddresses()) {
            if (!newHostInterface.hasAddress(address)) {
                Command.AtomicCommand atomicCommand = new Command.AtomicCommand();
                atomicCommand.setProperty("address");
                atomicCommand.setOpType(Command.AtomicCommand.OperationType.DELETE);
                atomicCommand.setValue(address.toString());
                command.addAtomicCommand(atomicCommand);
            }
        }

        // Look for addresses new to add
        for (InetAddress address : newHostInterface.getAddresses()) {
            if (!curHostInterface.hasAddress(address)) {
                Command.AtomicCommand atomicCommand = new Command.AtomicCommand();
                atomicCommand.setProperty("address");
                atomicCommand.setOpType(Command.AtomicCommand.OperationType.SET);
                atomicCommand.setValue(address.toString());
                command.addAtomicCommand(atomicCommand);
            }
        }

        return command;
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
