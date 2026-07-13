package com.twitchsidepanel;

import com.google.inject.Provides;
import com.twitchsidepanel.twitch.TwitchChatClient;
import com.twitchsidepanel.twitch.TwitchChatListener;
import com.twitchsidepanel.twitch.TwitchMessage;
import com.twitchsidepanel.ui.TwitchPanelIcon;
import com.twitchsidepanel.ui.TwitchSidePanel;
import java.awt.image.BufferedImage;
import javax.inject.Inject;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;

/**
 * Shows your Twitch channel's live chat in a RuneLite side panel, Party-Hub style,
 * instead of the official Twitch plugin's chatbox-PM format. Read-only: it only displays
 * chat, it never sends messages or otherwise acts on the streamer's or viewers' behalf.
 * <p>
 * Only ever connects to the channel set in this plugin's config (your own channel) - it
 * cannot be pointed at an arbitrary Twitch channel from the panel itself.
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

	private TwitchSidePanel panel;
	private NavigationButton navButton;
	private TwitchChatClient chatClient;

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
				String channel = config.channel().trim();
				panel.setStatus("Connecting to #" + channel + "...", false);
				chatClient.connect(channel);
			}

			@Override
			public void onDisconnectClicked()
			{
				chatClient.disconnect();
				panel.setConnected(false);
				panel.setStatus("Not connected", false);
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

		if (config.autoConnect() && !config.channel().trim().isEmpty())
		{
			String channel = config.channel().trim();
			panel.setStatus("Connecting to #" + channel + "...", false);
			chatClient.connect(channel);
		}
	}

	@Override
	protected void shutDown()
	{
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
		if (panel == null)
		{
			return;
		}
		panel.appendMessage(message, config.colorUsernames(), config.showTimestamps(), config.maxMessages());
	}
}
