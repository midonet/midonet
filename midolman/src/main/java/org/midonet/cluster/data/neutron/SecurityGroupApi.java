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
package org.midonet.cluster.data.neutron;


import org.midonet.cluster.data.Rule;
import org.midonet.midolman.serialization.SerializationException;
import org.midonet.midolman.state.StateAccessException;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;

public interface SecurityGroupApi {

    /**
     * Create a new security group object
     *
     * @param sg SecurityGroup to create
     * @return SecurityGroup created
     */
    SecurityGroup createSecurityGroup(@Nonnull SecurityGroup sg)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;

    /**
     * Create multiple security group objects.
     *
     * @param sgs List of SecurityGroup objects to create
     * @return List of SecurityGroup objects created
     */
    List<SecurityGroup> createSecurityGroupBulk(
            @Nonnull List<SecurityGroup> sgs)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;

    /**
     * Delete a security group
     *
     * @param id ID of the security group to delete
     */
    void deleteSecurityGroup(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get a security group
     *
     * @param id ID of the security group to fetch
     * @return Security group of the ID provided
     */
    SecurityGroup getSecurityGroup(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get security groups
     *
     * @return All the security groups
     */
    List<SecurityGroup> getSecurityGroups()
            throws StateAccessException, SerializationException;

    /**
     * Update a security group
     *
     * @param id ID of the security group to update
     * @return security group updated
     */
    SecurityGroup updateSecurityGroup(@Nonnull UUID id,
                                             @Nonnull SecurityGroup sg)
            throws StateAccessException, SerializationException;

    /**
     * Create a new security group rule object
     *
     * @param rule SecurityGroupRule to create
     * @return SecurityGroupRule created
     */
    SecurityGroupRule createSecurityGroupRule(
            @Nonnull SecurityGroupRule rule)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;

    /**
     * Create multiple security group rule objects.
     *
     * @param rules List of SecurityGroupRule objects to create
     * @return List of SecurityGroupRule objects created
     */
    List<SecurityGroupRule> createSecurityGroupRuleBulk(
            @Nonnull List<SecurityGroupRule> rules)
            throws StateAccessException, SerializationException,
            Rule.RuleIndexOutOfBoundsException;

    /**
     * Delete a security group rule
     *
     * @param id ID of the security group rule to delete
     */
    void deleteSecurityGroupRule(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get a security group rule
     *
     * @param id ID of the security group rule to fetch
     * @return Security group rule of the ID provided
     */
    SecurityGroupRule getSecurityGroupRule(@Nonnull UUID id)
            throws StateAccessException, SerializationException;

    /**
     * Get security group rules
     *
     * @return All the security group rules
     */
    List<SecurityGroupRule> getSecurityGroupRules()
            throws StateAccessException, SerializationException;

}
