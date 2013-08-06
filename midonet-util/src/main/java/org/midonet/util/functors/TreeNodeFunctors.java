/*
* Copyright 2012 Midokura Europe SARL
*/
package org.midonet.util.functors;

import java.util.List;

/**
 * Applies a functor on all nodes of a tree,
 * from the bottom leaves up to the root,
 * accumulating the results in the 'acc' list.
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
