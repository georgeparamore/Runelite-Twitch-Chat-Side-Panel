package com.twitchsidepanel.twitch;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

/**
 * Verifies USERNOTICE parsing against real-shaped tag lines. There's no practical way to
 * trigger a real sub/gift event live against this codebase's test channel, so this is the
 * substitute for that - lines below are built from Twitch's documented USERNOTICE tag
 * format, cross-checked against the badges/USERSTATE tags already confirmed live in
 * production traffic.
 */
public class TwitchChatClientTest
{
	private final TwitchChatClient client = new TwitchChatClient(new TwitchChatListener()
	{
		@Override
		public void onConnected(String channel)
		{
		}

		@Override
		public void onDisconnected(String reason)
		{
		}

		@Override
		public void onMessage(TwitchMessage message)
		{
		}
	});

	@Test
	public void parsesNewSub()
	{
		String line = "@badge-info=;badges=subscriber/0;color=#FF0000;display-name=NewSubber;"
			+ "msg-id=sub;msg-param-months=1;system-msg=NewSubber\\ssubscribed\\swith\\sPrime.;"
			+ "tmi-sent-ts=1783982700000 :tmi.twitch.tv USERNOTICE #qualify333";

		TwitchSubEvent event = client.parseUserNotice(line);

		assertEquals(TwitchSubEvent.Type.SUB, event.type);
		assertEquals("NewSubber", event.displayName);
	}

	@Test
	public void parsesResub()
	{
		String line = "@badge-info=subscriber/6;badges=subscriber/6;color=#00C4DA;display-name=LoyalViewer;"
			+ "msg-id=resub;msg-param-cumulative-months=6;msg-param-months=0;"
			+ "system-msg=LoyalViewer\\ssubscribed\\sfor\\s6\\smonths! :tmi.twitch.tv USERNOTICE #qualify333";

		TwitchSubEvent event = client.parseUserNotice(line);

		assertEquals(TwitchSubEvent.Type.SUB, event.type);
		assertEquals("LoyalViewer", event.displayName);
	}

	@Test
	public void parsesSingleGiftSub()
	{
		String line = "@badge-info=;badges=sub-gifter/5;color=;display-name=GenerousGifter;"
			+ "msg-id=subgift;msg-param-recipient-display-name=LuckyViewer;"
			+ "msg-param-recipient-user-name=luckyviewer;"
			+ "system-msg=GenerousGifter\\sgifted\\sa\\ssub\\sto\\sLuckyViewer! :tmi.twitch.tv USERNOTICE #qualify333";

		TwitchSubEvent event = client.parseUserNotice(line);

		assertEquals(TwitchSubEvent.Type.GIFT_SUB, event.type);
		assertEquals("LuckyViewer", event.displayName);
	}

	@Test
	public void parsesGiftBomb()
	{
		String line = "@badge-info=;badges=sub-gifter/25;color=#8A2BE2;display-name=BigSpender;"
			+ "msg-id=submysterygift;msg-param-mass-gift-count=25;msg-param-sender-count=50;"
			+ "system-msg=BigSpender\\sis\\sgifting\\s25\\sTier\\s1\\sSubs! :tmi.twitch.tv USERNOTICE #qualify333";

		TwitchSubEvent event = client.parseUserNotice(line);

		assertEquals(TwitchSubEvent.Type.GIFT_BOMB, event.type);
		assertEquals("BigSpender", event.displayName);
		assertEquals(25, event.count);
	}

	@Test
	public void parsesAnonymousGiftBombWithFallbackName()
	{
		String line = "@badge-info=;badges=;color=;display-name=;msg-id=anonsubmysterygift;"
			+ "msg-param-mass-gift-count=10;"
			+ "system-msg=An\\sanonymous\\suser\\sis\\sgifting\\s10\\sSubs! :tmi.twitch.tv USERNOTICE #qualify333";

		TwitchSubEvent event = client.parseUserNotice(line);

		assertEquals(TwitchSubEvent.Type.GIFT_BOMB, event.type);
		assertEquals("An anonymous gifter", event.displayName);
		assertEquals(10, event.count);
	}

	@Test
	public void ignoresUnhandledMsgIdsLikeRaids()
	{
		String line = "@display-name=RaidLeader;msg-id=raid;msg-param-viewerCount=42;"
			+ "msg-param-displayName=RaidLeader :tmi.twitch.tv USERNOTICE #qualify333";

		assertNull(client.parseUserNotice(line));
	}

	@Test
	public void ignoresLineWithNoMsgId()
	{
		String line = "@display-name=Someone :tmi.twitch.tv USERNOTICE #qualify333";

		assertNull(client.parseUserNotice(line));
	}
}
