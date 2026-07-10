package com.twitchsidepanel.twitch;

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
}
