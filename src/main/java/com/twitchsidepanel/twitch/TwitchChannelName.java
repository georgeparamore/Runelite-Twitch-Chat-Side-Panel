package com.twitchsidepanel.twitch;

import java.util.Locale;

/**
 * Normalizes whatever gets typed or pasted into the "Twitch channel" config field into a
 * plain channel name - so pasting a full channel URL straight from a browser's address bar
 * (e.g. {@code https://www.twitch.tv/somechannel} or {@code twitch.tv/somechannel/videos})
 * works exactly the same as typing the name itself.
 */
public final class TwitchChannelName
{
	private TwitchChannelName()
	{
	}

	public static String normalize(String raw)
	{
		if (raw == null)
		{
			return "";
		}

		String value = raw.trim();
		if (value.isEmpty())
		{
			return "";
		}

		// Strip a leading "@" or "#" (out of habit from other platforms, or a plain
		// "#channel" someone pasted) before URL handling below - a real URL never starts
		// with either, so this is unambiguous.
		while (value.startsWith("@") || value.startsWith("#"))
		{
			value = value.substring(1);
		}

		int twitchTv = value.toLowerCase(Locale.ROOT).indexOf("twitch.tv/");
		if (twitchTv >= 0)
		{
			value = value.substring(twitchTv + "twitch.tv/".length());
		}

		// Drop any path/query/fragment after the channel name itself, e.g. the
		// "/videos" in ".../somechannel/videos" or a "?foo=bar" some share links add.
		value = value.split("[/?#]", 2)[0];

		return value.trim();
	}
}
