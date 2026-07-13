package com.twitchsidepanel.twitch;

import java.awt.Color;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletionStage;

/**
 * Minimal read-only Twitch IRC client, over WebSocket rather than raw IRC sockets so it
 * still works on networks that only allow outbound HTTPS (hotel wifi, corporate proxies,
 * etc). Connects anonymously (Twitch allows reading chat without an OAuth token as long
 * as you never try to send a message, using a "justinfan" throwaway nick) and requests
 * the IRCv3 "tags" capability so each message carries the sender's chosen display name
 * and name color.
 * <p>
 * This never sends chat messages on the user's behalf - it only reads.
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

	public TwitchChatClient(TwitchChatListener listener)
	{
		this.listener = listener;
	}

	/**
	 * Connects and joins the given channel asynchronously. Safe to call from the Swing
	 * EDT. {@code channel} may be typed with or without a leading '#' and in any case.
	 */
	public void connect(String channel)
	{
		String normalizedChannel = normalizeChannel(channel);
		stopRequested = false;

		httpClient.newWebSocketBuilder()
			.connectTimeout(CONNECT_TIMEOUT)
			.buildAsync(ENDPOINT, new IrcListener(normalizedChannel))
			.exceptionally(error ->
			{
				if (!stopRequested)
				{
					listener.onDisconnected("Connection error: " + rootMessage(error));
				}
				return null;
			});
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
		private final StringBuilder frameBuffer = new StringBuilder();

		IrcListener(String channel)
		{
			this.channel = channel;
		}

		@Override
		public void onOpen(WebSocket ws)
		{
			webSocket = ws;

			// Twitch allows read-only IRC access with no OAuth token at all, as long as
			// the nick follows the "justinfanNNNNN" convention reserved for anonymous
			// viewers. Each WebSocket text frame is one IRC command - no trailing CRLF.
			String anonymousNick = "justinfan" + (10000 + new SecureRandom().nextInt(90000));
			ws.sendText("CAP REQ :twitch.tv/tags twitch.tv/commands", true);
			ws.sendText("NICK " + anonymousNick, true);
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

		return new TwitchMessage(displayName, body, color, System.currentTimeMillis());
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
