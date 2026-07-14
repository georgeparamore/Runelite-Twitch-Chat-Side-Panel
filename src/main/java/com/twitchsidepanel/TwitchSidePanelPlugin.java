package com.twitchsidepanel;

import com.google.inject.Provides;
import com.twitchsidepanel.twitch.BadgeIconCache;
import com.twitchsidepanel.twitch.EmoteImageCache;
import com.twitchsidepanel.twitch.EmoteSetLoader;
import com.twitchsidepanel.twitch.TwitchAuthService;
import com.twitchsidepanel.twitch.TwitchChannelName;
import com.twitchsidepanel.twitch.TwitchChatClient;
import com.twitchsidepanel.twitch.TwitchChatListener;
import com.twitchsidepanel.twitch.TwitchMessage;
import com.twitchsidepanel.twitch.TwitchSubEvent;
import com.twitchsidepanel.ui.TwitchPanelIcon;
import com.twitchsidepanel.ui.TwitchSidePanel;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import javax.swing.ImageIcon;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/**
 * Shows Twitch chat in a RuneLite side panel, Party-Hub style, instead of the official
 * Twitch plugin's chatbox-PM format.
 * <p>
 * Only ever connects to the single channel set in this plugin's config - there's no
 * in-panel way to switch to a different channel at runtime. Reading chat works
 * anonymously with no login; logging in with Twitch (device code flow) additionally
 * unlocks sending messages as yourself.
 */
@PluginDescriptor(
	name = "Twitch Chat Side Panel",
	description = "Shows your Twitch channel's chat in a side panel instead of the chatbox PM format",
	tags = {"twitch", "chat", "stream", "panel"},
	enabledByDefault = false
)
public class TwitchSidePanelPlugin extends Plugin implements TwitchChatListener
{
	private static final String CONFIG_GROUP = "twitchsidepanel";

	// One shared Client ID for everyone using this plugin - it just identifies "which app
	// is asking" to Twitch and isn't secret (that's the whole point of the "Public"
	// client type), unlike a client secret. Each user still logs in with their own
	// account and gets their own token; nothing is shared between users except this ID.
	private static final String CLIENT_ID = "azsobegarjzbpgosk97ydc9g64j6ou";

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private TwitchSidePanelConfig config;

	@Inject
	private ConfigManager configManager;

	private TwitchSidePanel panel;
	private NavigationButton navButton;
	private TwitchChatClient chatClient;
	private final EmoteImageCache emoteImageCache = new EmoteImageCache();
	private final BadgeIconCache badgeIconCache = new BadgeIconCache();
	private final EmoteSetLoader emoteSetLoader = new EmoteSetLoader();
	private final TwitchAuthService authService = new TwitchAuthService();

	// Twitch's chat gateway NAKs the IRCv3 "echo-message" capability (confirmed live),
	// so a message you send is never echoed back over IRC - selfColor/selfBadges (from
	// the USERSTATE Twitch sends on an authenticated connection) let a locally-echoed
	// copy of a sent message look the same as if it had arrived from the server.
	private volatile Color selfColor;
	private volatile List<TwitchMessage.BadgeRef> selfBadges = Collections.emptyList();

	// Emote name -> id, for matching emotes typed into a message you send yourself (see
	// echoSentMessageLocally). Twitch's echo-message NAK means we never get told which
	// parts of our own sent text are emotes the way incoming messages' "emotes" IRC tag
	// tells us - the recipient's own client resolves that server-side. Keeping our own
	// name lookup, refreshed on login and whenever the emote picker is opened, lets local
	// echo render emotes the same way instead of just showing their plain text code.
	private volatile Map<String, String> knownEmoteNameToId = Collections.emptyMap();

	@Provides
	TwitchSidePanelConfig getConfig(final ConfigManager configManager)
	{
		return configManager.getConfig(TwitchSidePanelConfig.class);
	}

	@Override
	protected void startUp()
	{
		chatClient = new TwitchChatClient(this);

		panel = new TwitchSidePanel(new TwitchSidePanel.Handlers()
		{
			@Override
			public void onConnectClicked()
			{
				connectToChannel();
			}

			@Override
			public void onDisconnectClicked()
			{
				chatClient.disconnect();
				panel.setConnected(false);
				panel.setStatus("Not connected", false);
			}

			@Override
			public void onLoginClicked()
			{
				startLogin();
			}

			@Override
			public void onCancelLoginClicked()
			{
				authService.cancel();
			}

			@Override
			public void onLogoutClicked()
			{
				clearLogin();
				panel.showLoginPrompt();
			}

			@Override
			public void onSendMessage(String text)
			{
				chatClient.sendMessage(text);
				echoSentMessageLocally(text);
			}

			@Override
			public void onEmotePickerClicked()
			{
				loadEmotePicker();
			}
		}, TwitchChannelName.normalize(config.channel()));

		BufferedImage icon = TwitchPanelIcon.create();

		navButton = NavigationButton.builder()
			.tooltip("Twitch Chat")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		restoreLoginState();

		if (config.autoConnect() && !TwitchChannelName.normalize(config.channel()).isEmpty())
		{
			connectToChannel();
		}
	}

	@Override
	protected void shutDown()
	{
		authService.cancel();
		if (chatClient != null)
		{
			chatClient.disconnect();
		}
		if (navButton != null)
		{
			clientToolbar.removeNavigation(navButton);
		}
		panel = null;
	}

	private void connectToChannel()
	{
		String channel = TwitchChannelName.normalize(config.channel());
		panel.setStatus("Connecting to #" + channel + "...", false);

		String accessToken = config.accessToken();
		String username = config.loggedInUsername();
		if (!accessToken.isEmpty() && !username.isEmpty())
		{
			chatClient.connectAuthenticated(channel, username, accessToken);
		}
		else
		{
			chatClient.connect(channel);
		}
	}

	private void startLogin()
	{
		authService.startLogin(CLIENT_ID, new TwitchAuthService.LoginListener()
		{
			@Override
			public void onCodeReady(String userCode, String verificationUri)
			{
				if (panel != null)
				{
					panel.showDeviceCode(userCode, verificationUri);
				}
			}

			@Override
			public void onAuthorized(String accessToken, String username)
			{
				configManager.setConfiguration(CONFIG_GROUP, "accessToken", accessToken);
				configManager.setConfiguration(CONFIG_GROUP, "loggedInUsername", username);
				if (panel != null)
				{
					panel.showLoggedIn(username);
				}
				loadBadgeIcons(accessToken);
				loadEmoteSet(accessToken, null);
			}

			@Override
			public void onError(String message)
			{
				if (panel != null)
				{
					panel.showLoginError(message);
				}
			}
		});
	}

	private void restoreLoginState()
	{
		String accessToken = config.accessToken();
		if (accessToken.isEmpty())
		{
			panel.showLoginPrompt();
			return;
		}

		// Validating blocks on a network call - run it off the startUp() thread so
		// plugin startup itself never stalls waiting on Twitch.
		Thread thread = new Thread(() ->
		{
			String username = authService.validateToken(accessToken);
			if (panel == null)
			{
				return;
			}
			if (username != null)
			{
				configManager.setConfiguration(CONFIG_GROUP, "loggedInUsername", username);
				panel.showLoggedIn(username);
				loadBadgeIcons(accessToken);
				loadEmoteSet(accessToken, null);
			}
			else
			{
				clearLogin();
				panel.showLoginPrompt();
			}
		}, "twitch-token-validate");
		thread.setDaemon(true);
		thread.start();
	}

	private void loadBadgeIcons(String accessToken)
	{
		String channel = TwitchChannelName.normalize(config.channel());
		if (channel.isEmpty())
		{
			return;
		}
		Thread thread = new Thread(() -> badgeIconCache.loadForChannel(CLIENT_ID, accessToken, channel),
			"twitch-badge-icons");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Fetches this channel's available emotes (its own + Twitch's global set) and shows
	 * them in the picker popup - mirrors the emote button next to Twitch's own chat
	 * input. Only meaningful once logged in, since the button only exists inside the send
	 * row, which is itself hidden until then.
	 */
	private void loadEmotePicker()
	{
		loadEmoteSet(config.accessToken(), emotes ->
		{
			Map<String, ImageIcon> icons = new HashMap<>();
			resolveIcons(emotes.channelEmotes, icons);
			resolveIcons(emotes.globalEmotes, icons);

			if (panel != null)
			{
				panel.showEmotePicker(emotes, icons);
			}
		});
	}

	/**
	 * Fetches this channel's available emotes (its own + Twitch's global set) and
	 * remembers their name -> id mapping for {@link #resolveLocalEmotes}, in addition to
	 * whatever {@code onLoaded} does with the result (e.g. showing the picker popup -
	 * {@code onLoaded} may be null when this is only warming the cache, such as right
	 * after login). Needs a user token, same as badge icons.
	 */
	private void loadEmoteSet(String accessToken, Consumer<EmoteSetLoader.Result> onLoaded)
	{
		String channel = TwitchChannelName.normalize(config.channel());
		if (channel.isEmpty() || accessToken.isEmpty())
		{
			return;
		}

		Thread thread = new Thread(() ->
		{
			EmoteSetLoader.Result emotes = emoteSetLoader.load(CLIENT_ID, accessToken, channel);
			rememberEmoteNames(emotes);
			if (onLoaded != null)
			{
				onLoaded.accept(emotes);
			}
		}, "twitch-emote-set");
		thread.setDaemon(true);
		thread.start();
	}

	private void rememberEmoteNames(EmoteSetLoader.Result emotes)
	{
		Map<String, String> names = new HashMap<>();
		for (EmoteSetLoader.EmoteInfo emote : emotes.channelEmotes)
		{
			names.put(emote.name, emote.id);
		}
		for (EmoteSetLoader.EmoteInfo emote : emotes.globalEmotes)
		{
			names.put(emote.name, emote.id);
		}
		knownEmoteNameToId = names;
	}

	private void resolveIcons(List<EmoteSetLoader.EmoteInfo> emotes, Map<String, ImageIcon> icons)
	{
		for (EmoteSetLoader.EmoteInfo emote : emotes)
		{
			ImageIcon icon = emoteImageCache.get(emote.id);
			if (icon != null)
			{
				icons.put(emote.id, icon);
			}
		}
	}

	private void clearLogin()
	{
		configManager.unsetConfiguration(CONFIG_GROUP, "accessToken");
		configManager.unsetConfiguration(CONFIG_GROUP, "loggedInUsername");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!CONFIG_GROUP.equals(event.getGroup()) || !"channel".equals(event.getKey()))
		{
			return;
		}

		String raw = config.channel();
		String normalized = TwitchChannelName.normalize(raw);
		if (!normalized.equals(raw))
		{
			// Rewriting the stored value (rather than only normalizing at the call
			// sites that actually connect) means the settings field itself shows the
			// cleaned-up channel name - visible confirmation that pasting a channel
			// URL worked, not just silent under-the-hood handling. This re-fires
			// onConfigChanged with the already-normalized value, which then falls
			// through to the branch below and stops.
			configManager.setConfiguration(CONFIG_GROUP, "channel", normalized);
			return;
		}

		if (panel != null)
		{
			panel.setChannel(normalized);
		}
	}

	@Override
	public void onConnected(String channel)
	{
		if (panel == null)
		{
			return;
		}
		panel.setConnected(true);
		panel.setStatus("Connected to #" + channel, false);
		panel.appendSystemMessage("Connected to #" + channel);
	}

	@Override
	public void onDisconnected(String reason)
	{
		if (panel == null)
		{
			return;
		}
		panel.setConnected(false);
		panel.setStatus(reason, true);
	}

	@Override
	public void onMessage(TwitchMessage message)
	{
		renderMessage(message);
	}

	@Override
	public void onSelfUserState(Color color, List<TwitchMessage.BadgeRef> badges)
	{
		selfColor = color;
		selfBadges = badges;
	}

	@Override
	public void onSubEvent(TwitchSubEvent event)
	{
		if (panel != null)
		{
			panel.addSubEvent(event);
		}
	}

	/**
	 * Renders a message you just sent yourself, since Twitch never echoes it back over
	 * IRC (see the NAK note near {@link #selfColor}). Runs the icon lookups on a
	 * background thread, same as a normal incoming message, so a cache-miss network
	 * fetch never blocks the button click that triggered this.
	 */
	private void echoSentMessageLocally(String text)
	{
		String username = config.loggedInUsername();
		if (username.isEmpty())
		{
			return;
		}

		Thread thread = new Thread(() ->
			renderMessage(new TwitchMessage(username, text, selfColor, System.currentTimeMillis(),
				selfBadges, resolveLocalEmotes(text))),
			"twitch-local-echo");
		thread.setDaemon(true);
		thread.start();
	}

	/**
	 * Finds emote codes in a message you're about to locally echo, using
	 * {@link #knownEmoteNameToId} - a message you send never comes back over IRC with its
	 * own {@code emotes} tag the way an incoming one does, so without this every emote
	 * you typed would render as its plain text code instead of an icon. Only ever matches
	 * emotes you're actually entitled to use (that's what populates the cache - see
	 * {@link #rememberEmoteNames}), so a tier-locked emote you typed by hand falls back to
	 * plain text here too, the same as what Twitch itself will actually render for it.
	 */
	private List<TwitchMessage.EmoteRef> resolveLocalEmotes(String text)
	{
		Map<String, String> names = knownEmoteNameToId;
		if (names.isEmpty())
		{
			return Collections.emptyList();
		}

		List<TwitchMessage.EmoteRef> emotes = new ArrayList<>();
		Matcher matcher = Pattern.compile("\\S+").matcher(text);
		while (matcher.find())
		{
			String id = names.get(matcher.group());
			if (id != null)
			{
				emotes.add(new TwitchMessage.EmoteRef(id, matcher.start(), matcher.end() - 1));
			}
		}
		return emotes;
	}

	/**
	 * Resolves emote/badge icons (blocking on a network fetch for any not already
	 * cached) and hands the message off to the panel to render. Must be called from a
	 * background thread, never the Swing EDT - {@code onMessage} already runs on the
	 * WebSocket listener's thread, and {@link #echoSentMessageLocally} hops onto one.
	 */
	private void renderMessage(TwitchMessage message)
	{
		if (panel == null)
		{
			return;
		}

		Map<String, ImageIcon> emoteIcons = new HashMap<>();
		for (TwitchMessage.EmoteRef emote : message.emotes)
		{
			if (!emoteIcons.containsKey(emote.id))
			{
				ImageIcon icon = emoteImageCache.get(emote.id);
				if (icon != null)
				{
					emoteIcons.put(emote.id, icon);
				}
			}
		}

		Map<String, ImageIcon> badgeIcons = new HashMap<>();
		for (TwitchMessage.BadgeRef badge : message.badges)
		{
			String key = badge.setId + "/" + badge.version;
			if (!badgeIcons.containsKey(key))
			{
				ImageIcon icon = badgeIconCache.get(badge.setId, badge.version);
				if (icon != null)
				{
					badgeIcons.put(key, icon);
				}
			}
		}

		panel.appendMessage(message, config.colorUsernames(), config.showTimestamps(), config.maxMessages(),
			emoteIcons, badgeIcons);
	}
}
