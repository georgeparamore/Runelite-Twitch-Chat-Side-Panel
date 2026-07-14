package com.twitchsidepanel.twitch;

import java.awt.Color;
import java.util.List;

/**
 * Callbacks fired by {@link TwitchChatClient}. All callbacks arrive on the client's own
 * background thread, not the Swing EDT - implementations that touch UI must hop over via
 * SwingUtilities.invokeLater themselves.
 */
public interface TwitchChatListener
{
	void onConnected(String channel);

	void onDisconnected(String reason);

	void onMessage(TwitchMessage message);

	/**
	 * Twitch sends this on an authenticated connection (on join, and again after each
	 * message you send) with your own display color and badges in the channel. There is
	 * no default implementation needed for anonymous connections, which never receive it.
	 */
	default void onSelfUserState(Color color, List<TwitchMessage.BadgeRef> badges)
	{
	}

	/**
	 * A subscription or gift-sub happened in the channel. Arrives on any connection
	 * (anonymous or authenticated) - Twitch broadcasts these to every viewer, the same
	 * as chat messages.
	 */
	default void onSubEvent(TwitchSubEvent event)
	{
	}
}
