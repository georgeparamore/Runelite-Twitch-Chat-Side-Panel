package com.twitchsidepanel;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup("twitchsidepanel")
public interface TwitchSidePanelConfig extends Config
{
	@ConfigItem(
		keyName = "channel",
		name = "Twitch channel",
		description = "The Twitch channel to show chat for - a plain channel name, or you can paste the channel's full twitch.tv URL and it'll be cleaned up automatically. The side panel only ever connects to this one channel - there's no in-panel way to switch to another.",
		position = 1
	)
	default String channel()
	{
		return "";
	}

	@ConfigItem(
		keyName = "autoConnect",
		name = "Auto-connect on login",
		description = "Connects to the configured channel's chat automatically when the client starts.",
		position = 2
	)
	default boolean autoConnect()
	{
		return false;
	}

	@ConfigItem(
		keyName = "colorUsernames",
		name = "Color usernames",
		description = "Shows each chatter's name in their Twitch-chosen color, like Twitch's own chat.",
		position = 3
	)
	default boolean colorUsernames()
	{
		return true;
	}

	@ConfigItem(
		keyName = "showTimestamps",
		name = "Show timestamps",
		description = "Shows the time each message arrived.",
		position = 4
	)
	default boolean showTimestamps()
	{
		return true;
	}

	@ConfigItem(
		keyName = "maxMessages",
		name = "Message history",
		description = "How many messages to keep visible before older ones scroll off.",
		position = 5
	)
	default int maxMessages()
	{
		return 200;
	}

	// --- Internal state below, not shown in the settings UI. Written directly via
	// ConfigManager.setConfiguration() rather than through this interface. ---

	@ConfigItem(keyName = "accessToken", name = "", description = "", hidden = true)
	default String accessToken()
	{
		return "";
	}

	@ConfigItem(keyName = "loggedInUsername", name = "", description = "", hidden = true)
	default String loggedInUsername()
	{
		return "";
	}
}
