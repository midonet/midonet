/*
 * Copyright 2015 Midokura SARL
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

package org.midonet.cluster.services.rest_api.neutron.plugin

import java.util
import java.util.UUID
import javax.annotation.Nonnull

import org.midonet.cluster.rest_api.neutron.models.{SecurityGroup, SecurityGroupRule}
import org.midonet.cluster.rest_api.{ConflictHttpException, NotFoundHttpException}

trait SecurityGroupApi {

    /**
     * Create a new security group object
     *
     * @param sg SecurityGroup to create
     * @return SecurityGroup created
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createSecurityGroup(@Nonnull sg: SecurityGroup): SecurityGroup

    /**
     * Create multiple security group objects.
     *
     * @param sgs util.List of SecurityGroup objects to create
     * @return util.List of SecurityGroup objects created
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createSecurityGroupBulk(@Nonnull sgs: util.List[SecurityGroup])
    : util.List[SecurityGroup]

    /**
     * Delete a security group
     *
     * @param id ID of the security group to delete
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteSecurityGroup(@Nonnull id: UUID)

    /**
     * Get a security group
     *
     * @param id ID of the security group to fetch
     * @return Security group of the ID provided
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getSecurityGroup(@Nonnull id: UUID): SecurityGroup

    /**
     * Get security groups
     *
     * @return All the security groups
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getSecurityGroups: util.List[SecurityGroup]

    /**
     * Update a security group
     *
     * @param id ID of the security group to update
     * @return security group updated
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def updateSecurityGroup(@Nonnull id: UUID,
                            @Nonnull sg: SecurityGroup): SecurityGroup

    /**
     * Create a new security group rule object
     *
     * @param rule SecurityGroupRule to create
     * @return SecurityGroupRule created
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createSecurityGroupRule(@Nonnull rule: SecurityGroupRule): SecurityGroupRule

    /**
     * Create multiple security group rule objects.
     *
     * @param rules util.List of SecurityGroupRule objects to create
     * @return util.List of SecurityGroupRule objects created
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def createSecurityGroupRuleBulk(@Nonnull
                                    rules: util.List[SecurityGroupRule])
    : util.List[SecurityGroupRule]

    /**
     * Delete a security group rule
     *
     * @param id ID of the security group rule to delete
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def deleteSecurityGroupRule(@Nonnull id: UUID)

    /**
     * Get a security group rule
     *
     * @param id ID of the security group rule to fetch
     * @return Security group rule of the ID provided
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getSecurityGroupRule(@Nonnull id: UUID): SecurityGroupRule

    /**
     * Get security group rules
     *
     * @return All the security group rules
     */
    @throws(classOf[ConflictHttpException])
    @throws(classOf[NotFoundHttpException])
    def getSecurityGroupRules: util.List[SecurityGroupRule]
}