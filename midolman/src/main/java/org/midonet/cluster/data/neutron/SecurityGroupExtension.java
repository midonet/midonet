/*
 * Copyright (c) 2014 Midokura Europe SARL, All Rights Reserved.
 */
package org.midonet.cluster.data.neutron;


import org.midonet.cluster.data.Rule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

public interface SecurityGroupExtension {

    /**
     * Create a new security group object
     *
     * @param sg SecurityGroup to create
     * @return SecurityGroup created
     */
    public SecurityGroup createSecurityGroup(@Nonnull SecurityGroup sg)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;

    /**
     * Create multiple security group objects.
     *
     * @param sgs List of SecurityGroup objects to create
     * @return List of SecurityGroup objects created
     */
    public List<SecurityGroup> createSecurityGroupBulk(
            @Nonnull List<SecurityGroup> sgs)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;

    /**
     * Delete a security group
     *
     * @param id ID of the security group to delete
     */
    public void deleteSecurityGroup(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get a security group
     *
     * @param id ID of the security group to fetch
     * @return Security group of the ID provided
     */
    public SecurityGroup getSecurityGroup(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get security groups
     *
     * @return All the security groups
     */
    public List<SecurityGroup> getSecurityGroups()
            throws StateAccessException, SerializationException;

    /**
     * Update a security group
     *
     * @param id ID of the security group to update
     * @return security group updated
     */
    public SecurityGroup updateSecurityGroup(@Nonnull UUID id,
                                             @Nonnull SecurityGroup sg)
            throws StateAccessException, SerializationException;

    /**
     * Create a new security group rule object
     *
     * @param rule SecurityGroupRule to create
     * @return SecurityGroupRule created
     */
    public SecurityGroupRule createSecurityGroupRule(
            @Nonnull SecurityGroupRule rule)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;

    /**
     * Create multiple security group rule objects.
     *
     * @param rules List of SecurityGroupRule objects to create
     * @return List of SecurityGroupRule objects created
     */
    public List<SecurityGroupRule> createSecurityGroupRuleBulk(
            @Nonnull List<SecurityGroupRule> rules)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;

    /**
     * Delete a security group rule
     *
     * @param id ID of the security group rule to delete
     */
    public void deleteSecurityGroupRule(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get a security group rule
     *
     * @param id ID of the security group rule to fetch
     * @return Security group rule of the ID provided
     */
    public SecurityGroupRule getSecurityGroupRule(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get security group rules
     *
     * @return All the security group rules
     */
    public List<SecurityGroupRule> getSecurityGroupRules()
            throws StateAccessException, SerializationException;

}
