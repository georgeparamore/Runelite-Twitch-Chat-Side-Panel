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
	public final List<EmoteRef> emotes;

	public TwitchMessage(String displayName, String body, Color color, long receivedAtMillis,
		List<BadgeRef> badges, List<EmoteRef> emotes)
	{
		this.displayName = displayName;
		this.body = body;
		this.color = color;
		this.receivedAtMillis = receivedAtMillis;
		this.badges = badges == null ? Collections.emptyList() : badges;
		this.emotes = emotes == null ? Collections.emptyList() : emotes;
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

	/**
	 * One occurrence of an emote from the IRC {@code emotes} tag, as a position range
	 * (inclusive) into {@link #body}, measured in UTF-16 code units - the same unit
	 * Twitch uses when generating the positions.
	 */
	public static class EmoteRef
	{
		public final String id;
		public final int start;
		public final int end;

		public EmoteRef(String id, int start, int end)
		{
			this.id = id;
			this.start = start;
			this.end = end;
		}
	}
}
