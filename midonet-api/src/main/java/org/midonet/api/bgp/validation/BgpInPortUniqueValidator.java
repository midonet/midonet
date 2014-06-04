/*
 * Copyright 2013 Midokura Pte. Ltd.
 */

package org.midonet.api.bgp.validation;

import com.google.inject.Inject;
import org.midonet.api.bgp.Bgp;
import org.midonet.cluster.DataClient;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class BgpInPortUniqueValidator implements
        ConstraintValidator<IsUniqueBgpInPort, Bgp>
{
    private final DataClient dataClient;

    @Inject
    public BgpInPortUniqueValidator(DataClient dataClient) {
        this.dataClient = dataClient;
    }

    @Override
    public void initialize(IsUniqueBgpInPort constraintAnnotation) {
    }

    @Override
    public boolean isValid(Bgp bgp, ConstraintValidatorContext context) {
        if (bgp == null) {
            return false;
        }

        if (bgp.getPortId() == null) {
            return false;
        }

        try {
            if (!dataClient.bgpFindByPort(bgp.getPortId()).isEmpty()) {
                return false;
            }
        } catch (StateAccessException e) {
            throw new RuntimeException("Error while validating bgp", e);
        } catch (SerializationException e) {
            throw new RuntimeException("Serialization Error while validating bgp", e);
        }

        return true;
    }

}
