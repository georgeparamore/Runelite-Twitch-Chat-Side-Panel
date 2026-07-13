package com.twitchsidepanel.twitch;

import java.awt.Color;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.HashMap;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * Minimal read-only Twitch IRC client. Connects anonymously (Twitch allows reading chat
 * without an OAuth token as long as you never try to send a message, using a
 * "justinfan" throwaway nick) and requests the IRCv3 "tags" capability so each message
 * carries the sender's chosen display name and name color.
 * <p>
 * This never sends chat messages on the user's behalf - it only reads.
 */
public class TwitchChatClient
{
	private static final String HOST = "irc.chat.twitch.tv";
	private static final int PORT = 6697;
	// The plain createSocket(host, port) overload blocks on the OS's default TCP connect
	// timeout (can be 60s+), leaving the panel stuck on "Connecting..." with no feedback
	// if Twitch is unreachable. Connecting a plain socket first with an explicit timeout,
	// then upgrading it to TLS, bounds that wait and lets a real error reach the user.
	private static final int CONNECT_TIMEOUT_MS = 10_000;

	private final TwitchChatListener listener;
	private volatile SSLSocket socket;
	private volatile boolean stopRequested;
	private Thread readerThread;

	public TwitchChatClient(TwitchChatListener listener)
	{
		this.listener = listener;
	}

	/**
	 * Connects and joins the given channel on a background thread. Safe to call from the
	 * Swing EDT. {@code channel} may be typed with or without a leading '#' and in any case.
	 */
	public synchronized void connect(String channel)
	{
		String normalizedChannel = normalizeChannel(channel);
		stopRequested = false;

		readerThread = new Thread(() -> runConnection(normalizedChannel), "twitch-chat-reader");
		readerThread.setDaemon(true);
		readerThread.start();
	}

	public synchronized void disconnect()
	{
		stopRequested = true;
		closeSocketQuietly();
	}

	private void runConnection(String channel)
	{
		try
		{
			Socket rawSocket = new Socket();
			rawSocket.connect(new InetSocketAddress(HOST, PORT), CONNECT_TIMEOUT_MS);

			SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
			socket = (SSLSocket) factory.createSocket(rawSocket, HOST, PORT, true);
			socket.setSoTimeout(0);

			OutputStream out = socket.getOutputStream();
			BufferedReader in = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));

			// Twitch allows read-only IRC access with no OAuth token at all, as long as the
			// nick follows the "justinfanNNNNN" convention reserved for anonymous viewers.
			String anonymousNick = "justinfan" + (10000 + new SecureRandom().nextInt(90000));
			sendLine(out, "CAP REQ :twitch.tv/tags twitch.tv/commands");
			sendLine(out, "NICK " + anonymousNick);
			sendLine(out, "JOIN #" + channel);

			String line;
			while (!stopRequested && (line = in.readLine()) != null)
			{
				handleLine(out, line, channel);
			}

			if (!stopRequested)
			{
				notifyDisconnected("Connection closed by Twitch");
			}
		}
		catch (IOException e)
		{
			if (!stopRequested)
			{
				notifyDisconnected("Connection error: " + e.getMessage());
			}
		}
		finally
		{
			closeSocketQuietly();
		}
	}

	private void handleLine(OutputStream out, String line, String channel) throws IOException
	{
		if (line.startsWith("PING"))
		{
			sendLine(out, "PONG :tmi.twitch.tv");
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

	private void sendLine(OutputStream out, String line) throws IOException
	{
		out.write((line + "\r\n").getBytes(StandardCharsets.UTF_8));
		out.flush();
	}

	private void notifyDisconnected(String reason)
	{
		listener.onDisconnected(reason);
	}

	private void closeSocketQuietly()
	{
		SSLSocket s = socket;
		if (s != null)
		{
			try
			{
				s.close();
			}
			catch (IOException ignored)
			{
				// Nothing useful to do with a failure to close an already-broken socket.
			}
		}
	}

	private static String normalizeChannel(String channel)
	{
		String trimmed = channel.trim().toLowerCase();
		return trimmed.startsWith("#") ? trimmed.substring(1) : trimmed;
	}
}
