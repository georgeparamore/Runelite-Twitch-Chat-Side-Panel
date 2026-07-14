package com.twitchsidepanel.twitch;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

public class TwitchChannelNameTest
{
	@Test
	public void plainNameIsUnchanged()
	{
		assertEquals("qualify333", TwitchChannelName.normalize("qualify333"));
	}

	@Test
	public void trimsWhitespace()
	{
		assertEquals("qualify333", TwitchChannelName.normalize("  qualify333  "));
	}

	@Test
	public void extractsNameFromPlainUrl()
	{
		assertEquals("qualify333", TwitchChannelName.normalize("https://www.twitch.tv/qualify333"));
	}

	@Test
	public void extractsNameFromUrlWithoutProtocol()
	{
		assertEquals("qualify333", TwitchChannelName.normalize("twitch.tv/qualify333"));
	}

	@Test
	public void extractsNameFromUrlWithTrailingPath()
	{
		assertEquals("qualify333", TwitchChannelName.normalize("https://www.twitch.tv/qualify333/videos"));
	}

	@Test
	public void extractsNameFromUrlWithQueryString()
	{
		assertEquals("qualify333", TwitchChannelName.normalize("https://www.twitch.tv/qualify333?foo=bar"));
	}

	@Test
	public void stripsLeadingAtOrHash()
	{
		assertEquals("qualify333", TwitchChannelName.normalize("@qualify333"));
		assertEquals("qualify333", TwitchChannelName.normalize("#qualify333"));
	}

	@Test
	public void emptyAndNullBecomeEmptyString()
	{
		assertEquals("", TwitchChannelName.normalize(""));
		assertEquals("", TwitchChannelName.normalize(null));
		assertEquals("", TwitchChannelName.normalize("   "));
	}
}
