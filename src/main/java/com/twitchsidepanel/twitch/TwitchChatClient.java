package com.twitchsidepanel.twitch;

import java.awt.Color;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Twitch IRC client, over WebSocket rather than raw IRC sockets so it still works on
 * networks that only allow outbound HTTPS (hotel wifi, corporate proxies, etc).
 * <p>
 * Without a login (see {@link #connect(String)}), this connects anonymously using a
 * "justinfan" throwaway nick - Twitch allows this for read-only access, but a message
 * can never be sent on such a connection. With a login (see
 * {@link #connectAuthenticated(String, String, String)}), it authenticates as the real
 * account so {@link #sendMessage(String)} can post to chat.
 * <p>
 * Either way it requests the IRCv3 "tags" capability so each message carries the
 * sender's chosen display name, name color, badges, and emotes.
 */
public class TwitchChatClient
{
	private static final URI ENDPOINT = URI.create("wss://irc-ws.chat.twitch.tv:443");
	private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(10);

	private final TwitchChatListener listener;
	private final HttpClient httpClient = HttpClient.newBuilder()
		.connectTimeout(CONNECT_TIMEOUT)
		.build();

	private volatile WebSocket webSocket;
	private volatile boolean stopRequested;
	private volatile String currentChannel;

	public TwitchChatClient(TwitchChatListener listener)
	{
		this.listener = listener;
	}

	/**
	 * Connects anonymously (read-only) and joins the given channel. Safe to call from the
	 * Swing EDT. {@code channel} may be typed with or without a leading '#' and in any case.
	 */
	public void connect(String channel)
	{
		doConnect(channel, null, null);
	}

	/**
	 * Connects authenticated as {@code nick} using an OAuth access token (chat:read
	 * chat:edit scopes), enabling {@link #sendMessage(String)} on this connection.
	 */
	public void connectAuthenticated(String channel, String nick, String oauthAccessToken)
	{
		doConnect(channel, nick, oauthAccessToken);
	}

	private void doConnect(String channel, String nick, String oauthAccessToken)
	{
		String normalizedChannel = normalizeChannel(channel);
		currentChannel = normalizedChannel;
		stopRequested = false;

		httpClient.newWebSocketBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.buildAsync(ENDPOINT, new IrcListener(normalizedChannel, nick, oauthAccessToken))
			.exceptionally(error ->
			{
				if (!stopRequested)
				{
					listener.onDisconnected("Connection error: " + rootMessage(error));
				}
				return null;
			});
	}

	/**
	 * Sends a chat message to the currently connected channel. No-op if not connected on
	 * an authenticated connection. Never called automatically by this plugin - only in
	 * direct response to the user typing a message and pressing send.
	 */
	public void sendMessage(String text)
	{
		WebSocket ws = webSocket;
		String channel = currentChannel;
		if (ws == null || channel == null || text == null || text.trim().isEmpty())
		{
			return;
		}
		ws.sendText("PRIVMSG #" + channel + " :" + text.trim(), true);
	}

	public void disconnect()
	{
		stopRequested = true;
		WebSocket ws = webSocket;
		if (ws != null)
		{
			ws.sendClose(WebSocket.NORMAL_CLOSURE, "");
		}
	}

	private class IrcListener implements WebSocket.Listener
	{
		private final String channel;
		private final String nick;
		private final String oauthAccessToken;
		private final StringBuilder frameBuffer = new StringBuilder();

		IrcListener(String channel, String nick, String oauthAccessToken)
		{
			this.channel = channel;
			this.nick = nick;
			this.oauthAccessToken = oauthAccessToken;
		}

		@Override
		public void onOpen(WebSocket ws)
		{
			webSocket = ws;

			// Each WebSocket text frame is one IRC command - no trailing CRLF needed.
			ws.sendText("CAP REQ :twitch.tv/tags twitch.tv/commands", true);

			if (oauthAccessToken != null && nick != null)
			{
				ws.sendText("PASS oauth:" + oauthAccessToken, true);
				ws.sendText("NICK " + nick.toLowerCase(), true);
			}
			else
			{
				// Twitch allows read-only IRC access with no OAuth token at all, as long
				// as the nick follows the "justinfanNNNNN" convention reserved for
				// anonymous viewers.
				String anonymousNick = "justinfan" + (10000 + new SecureRandom().nextInt(90000));
				ws.sendText("NICK " + anonymousNick, true);
			}

			ws.sendText("JOIN #" + channel, true);

			WebSocket.Listener.super.onOpen(ws);
		}

		@Override
		public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last)
		{
			frameBuffer.append(data);
			if (last)
			{
				// Twitch can batch several IRC lines into one WebSocket frame, especially
				// during fast chat, so split on CRLF instead of assuming one line/frame.
				String batch = frameBuffer.toString();
				frameBuffer.setLength(0);
				for (String line : batch.split("\r\n"))
				{
					if (!line.isEmpty())
					{
						handleLine(ws, line, channel);
					}
				}
			}
			ws.request(1);
			return null;
		}

		@Override
		public void onError(WebSocket ws, Throwable error)
		{
			if (!stopRequested)
			{
				listener.onDisconnected("Connection error: " + rootMessage(error));
			}
		}

		@Override
		public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason)
		{
			if (!stopRequested)
			{
				listener.onDisconnected(reason == null || reason.isEmpty() ? "Connection closed by Twitch" : reason);
			}
			return null;
		}
	}

	private void handleLine(WebSocket ws, String line, String channel)
	{
		if (line.startsWith("PING"))
		{
			ws.sendText("PONG :tmi.twitch.tv", true);
			return;
		}

		if (line.contains("NOTICE") && (line.contains("Login authentication failed") || line.contains("Improperly formatted auth")))
		{
			listener.onDisconnected("Twitch rejected the login - try logging in again");
			return;
		}

		if (line.contains(" 001 ") || line.contains("JOIN #" + channel))
		{
			listener.onConnected(channel);
			return;
		}

		if (line.contains("PRIVMSG #" + channel))
		{
			TwitchMessage message = parsePrivmsg(line);
			if (message != null)
			{
				listener.onMessage(message);
			}
		}
	}

	/**
	 * Parses a single IRCv3-tagged PRIVMSG line, e.g.:
	 * {@code @color=#FF0000;display-name=SomeUser;... :someuser!someuser@someuser.tmi.twitch.tv PRIVMSG #channel :hello}
	 */
	private TwitchMessage parsePrivmsg(String line)
	{
		Map<String, String> tags = new HashMap<>();
		String rest = line;

		if (rest.startsWith("@"))
		{
			int spaceIndex = rest.indexOf(' ');
			if (spaceIndex < 0)
			{
				return null;
			}
			String tagSection = rest.substring(1, spaceIndex);
			rest = rest.substring(spaceIndex + 1);

			for (String tag : tagSection.split(";"))
			{
				int eq = tag.indexOf('=');
				if (eq > 0)
				{
					tags.put(tag.substring(0, eq), tag.substring(eq + 1));
				}
			}
		}

		int privmsgIndex = rest.indexOf("PRIVMSG");
		int messageStart = rest.indexOf(" :", privmsgIndex);
		if (privmsgIndex < 0 || messageStart < 0)
		{
			return null;
		}

		String body = rest.substring(messageStart + 2);

		String displayName = tags.get("display-name");
		if (displayName == null || displayName.isEmpty())
		{
			// Fall back to the IRC nick out of "prefix!user@host PRIVMSG ..." when a viewer
			// has never set a display name (rare, but happens for very old accounts).
			displayName = extractNickFromPrefix(rest.substring(0, privmsgIndex));
		}

		Color color = parseColor(tags.get("color"));
		List<TwitchMessage.BadgeRef> badges = parseBadges(tags.get("badges"));
		List<TwitchMessage.EmoteRef> emotes = parseEmotes(tags.get("emotes"));

		return new TwitchMessage(displayName, body, color, System.currentTimeMillis(), badges, emotes);
	}

	/**
	 * Parses the {@code badges} tag, e.g. {@code subscriber/12,moderator/1}.
	 */
	private List<TwitchMessage.BadgeRef> parseBadges(String raw)
	{
		List<TwitchMessage.BadgeRef> badges = new ArrayList<>();
		if (raw == null || raw.isEmpty())
		{
			return badges;
		}
		for (String entry : raw.split(","))
		{
			int slash = entry.indexOf('/');
			if (slash > 0)
			{
				badges.add(new TwitchMessage.BadgeRef(entry.substring(0, slash), entry.substring(slash + 1)));
			}
		}
		return badges;
	}

	/**
	 * Parses the {@code emotes} tag, e.g. {@code 25:0-4,12-16/1902:6-10} - each entry is
	 * an emote id followed by one or more start-end character ranges where it appears.
	 */
	private List<TwitchMessage.EmoteRef> parseEmotes(String raw)
	{
		List<TwitchMessage.EmoteRef> emotes = new ArrayList<>();
		if (raw == null || raw.isEmpty())
		{
			return emotes;
		}
		for (String entry : raw.split("/"))
		{
			int colon = entry.indexOf(':');
			if (colon <= 0)
			{
				continue;
			}
			String id = entry.substring(0, colon);
			for (String range : entry.substring(colon + 1).split(","))
			{
				int dash = range.indexOf('-');
				if (dash <= 0)
				{
					continue;
				}
				try
				{
					int start = Integer.parseInt(range.substring(0, dash));
					int end = Integer.parseInt(range.substring(dash + 1));
					emotes.add(new TwitchMessage.EmoteRef(id, start, end));
				}
				catch (NumberFormatException ignored)
				{
					// Malformed range from a source we don't control - skip it rather
					// than fail the whole message.
				}
			}
		}
		emotes.sort((a, b) -> Integer.compare(a.start, b.start));
		return emotes;
	}

	private String extractNickFromPrefix(String prefixSection)
	{
		String trimmed = prefixSection.trim();
		if (trimmed.startsWith(":"))
		{
			trimmed = trimmed.substring(1);
		}
		int bang = trimmed.indexOf('!');
		return bang > 0 ? trimmed.substring(0, bang) : trimmed;
	}

	private Color parseColor(String hex)
	{
		if (hex == null || hex.isEmpty())
		{
			return null;
		}
		try
		{
			return Color.decode(hex);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	private static String rootMessage(Throwable error)
	{
		Throwable cause = error;
		while (cause.getCause() != null)
		{
			cause = cause.getCause();
		}
		String message = cause.getMessage();
		return message != null ? message : cause.getClass().getSimpleName();
	}

	private static String normalizeChannel(String channel)
	{
		String trimmed = channel.trim().toLowerCase();
		return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
	}
}
