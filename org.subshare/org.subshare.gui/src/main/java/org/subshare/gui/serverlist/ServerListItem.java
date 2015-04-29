package org.subshare.gui.serverlist;

import static co.codewizards.cloudstore.core.util.AssertUtil.assertNotNull;

import java.net.URL;

import org.subshare.core.server.Server;

public class ServerListItem {

	private Server server;

	public ServerListItem() { }

	public ServerListItem(final Server server) {
		this.server = server;
	}

	public Server getServer() {
		return server;
	}
	public void setServer(Server server) {
		this.server = server;
	}

	public String getName() {
		return assertNotNull("server", server).getName();
	}
	public void setName(String name) {
		assertNotNull("server", server).setName(name);
	}

	public URL getUrl() {
		return assertNotNull("server", server).getUrl();
	}
	public void setUrl(URL url) {
		assertNotNull("server", server).setUrl(url);
	}
}