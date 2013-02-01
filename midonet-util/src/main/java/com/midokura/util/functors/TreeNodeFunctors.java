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
public class TreeNodeFunctors {

    public static <T, Node> List<T> recursiveBottomUpFold(TreeNode<Node> root,
                                                       Functor<Node, T> func,
                                                       List<T> acc)
        throws Exception {

        List<TreeNode<Node>> children = root.getChildren();

        for (TreeNode<Node> child : children) {
            recursiveBottomUpFold(child, func, acc);
        }

        T processedRoot = func.apply(root.getValue());
        if (processedRoot != null) {
            acc.add(processedRoot);
        }

        return acc;
    }

}
