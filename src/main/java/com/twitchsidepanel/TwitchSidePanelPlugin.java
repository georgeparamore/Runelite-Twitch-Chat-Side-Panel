package com.twitchsidepanel;

import com.google.inject.Provides;
import com.twitchsidepanel.twitch.BadgeIconCache;
import com.twitchsidepanel.twitch.EmoteImageCache;
import com.twitchsidepanel.twitch.TwitchAuthService;
import com.twitchsidepanel.twitch.TwitchChatClient;
import com.twitchsidepanel.twitch.TwitchChatListener;
import com.twitchsidepanel.twitch.TwitchMessage;
import com.twitchsidepanel.ui.TwitchPanelIcon;
import com.twitchsidepanel.ui.TwitchSidePanel;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
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
 * Shows your Twitch channel's live chat in a RuneLite side panel, Party-Hub style,
 * instead of the official Twitch plugin's chatbox-PM format.
 * <p>
 * Only ever connects to the channel set in this plugin's config (your own channel) - it
 * cannot be pointed at an arbitrary Twitch channel from the panel itself. Reading chat
 * works anonymously with no login; logging in with Twitch (device code flow) additionally
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
	private final TwitchAuthService authService = new TwitchAuthService();

	// Twitch's chat gateway NAKs the IRCv3 "echo-message" capability (confirmed live),
	// so a message you send is never echoed back over IRC - selfColor/selfBadges (from
	// the USERSTATE Twitch sends on an authenticated connection) let a locally-echoed
	// copy of a sent message look the same as if it had arrived from the server.
	private volatile Color selfColor;
	private volatile List<TwitchMessage.BadgeRef> selfBadges = Collections.emptyList();

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
		}, config.channel());

		BufferedImage icon = TwitchPanelIcon.create();

		navButton = NavigationButton.builder()
			.tooltip("Twitch Chat")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();

		clientToolbar.addNavigation(navButton);

		restoreLoginState();

		if (config.autoConnect() && !config.channel().trim().isEmpty())
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
		String channel = config.channel().trim();
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
		String clientId = config.clientId().trim();
		if (clientId.isEmpty())
		{
			panel.showLoginError("Set your Twitch app Client ID in the plugin settings first");
			return;
		}

		authService.startLogin(clientId, new TwitchAuthService.LoginListener()
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
				loadBadgeIcons(clientId, accessToken);
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
				loadBadgeIcons(config.clientId().trim(), accessToken);
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

	private void loadBadgeIcons(String clientId, String accessToken)
	{
		String channel = config.channel().trim();
		if (clientId.isEmpty() || channel.isEmpty())
		{
			return;
		}
		Thread thread = new Thread(() -> badgeIconCache.loadForChannel(clientId, accessToken, channel),
			"twitch-badge-icons");
		thread.setDaemon(true);
		thread.start();
	}

	private void clearLogin()
	{
		configManager.unsetConfiguration(CONFIG_GROUP, "accessToken");
		configManager.unsetConfiguration(CONFIG_GROUP, "loggedInUsername");
	}

	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (panel != null && CONFIG_GROUP.equals(event.getGroup()) && "channel".equals(event.getKey()))
		{
			panel.setChannel(config.channel());
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
				selfBadges, Collections.emptyList())),
			"twitch-local-echo");
		thread.setDaemon(true);
		thread.start();
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
