/**
 * 
 */
package org.midonet.midolman.state.zkManagers;

import java.util.Collection;

/**
 * Interface for tag-able ZooKeeper data.
 * @author tomohiko
 *
 */
public interface TaggableConfig {
	
	/**
	 * Returns a number of tags attached to the config.
	 * @return a number of tags.
	 */
	public int tagSize();
	
	public boolean addTag(String tag);
	
	public boolean addTags(Collection<String> tags);
	
	public Collection<String> getTags();
	
	public boolean containsTag(String tag);

}
