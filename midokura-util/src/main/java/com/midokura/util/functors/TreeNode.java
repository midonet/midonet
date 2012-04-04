/*
* Copyright 2012 Midokura Europe SARL
*/
package com.midokura.util.functors;

import java.util.List;

/**
 * // TODO: Explain yourself.
 *
 * @author Mihai Claudiu Toader <mtoader@midokura.com>
 *         Date: 4/5/12
 */
public interface TreeNode<DataType> {

    public DataType getValue();

    List<TreeNode<DataType>> getChildren() throws Exception;
}
