package com.twitchsidepanel.twitch;

import java.awt.Color;
import java.util.Collections;
import java.util.List;

/**
 * A single parsed Twitch PRIVMSG, ready for display.
 */
public class TwitchMessage
{
	public final String displayName;
	public final String body;
	public final Color color;
	public final long receivedAtMillis;
	public final List<BadgeRef> badges;

	public TwitchMessage(String displayName, String body, Color color, long receivedAtMillis,
		List<BadgeRef> badges)
	{
		this.displayName = displayName;
		this.body = body;
		this.color = color;
		this.receivedAtMillis = receivedAtMillis;
		this.badges = badges == null ? Collections.emptyList() : badges;
	}

	/**
	 * One badge from the IRC {@code badges} tag, e.g. {@code subscriber/12}.
	 */
	public static class BadgeRef
	{
		public final String setId;
		public final String version;

		public BadgeRef(String setId, String version)
		{
			this.setId = setId;
			this.version = version;
		}
	}
}
