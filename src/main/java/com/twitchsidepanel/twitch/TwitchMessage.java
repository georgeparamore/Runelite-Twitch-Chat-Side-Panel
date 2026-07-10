package com.twitchsidepanel.twitch;

import java.awt.Color;

/**
 * A single parsed Twitch PRIVMSG, ready for display.
 */
public class TwitchMessage
{
	public final String displayName;
	public final String body;
	public final Color color;
	public final long receivedAtMillis;

	public TwitchMessage(String displayName, String body, Color color, long receivedAtMillis)
	{
		this.displayName = displayName;
		this.body = body;
		this.color = color;
		this.receivedAtMillis = receivedAtMillis;
	}
}
