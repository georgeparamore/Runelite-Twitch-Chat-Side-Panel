package com.twitchsidepanel;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Launches the real RuneLite client locally with TwitchSidePanelPlugin registered, so it
 * can be tested without going through the Plugin Hub. Run this class's main method (e.g.
 * "Run" in your IDE); log in with your own account, then enable "Twitch Chat Side Panel"
 * in the plugin list.
 */
public class TwitchSidePanelPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(TwitchSidePanelPlugin.class);
		RuneLite.main(args);
	}
}
