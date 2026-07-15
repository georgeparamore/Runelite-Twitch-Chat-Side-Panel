package com.twitchsidepanel.ui;

import com.twitchsidepanel.twitch.TwitchMessage;
import com.twitchsidepanel.twitch.TwitchSubEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Desktop;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Insets;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import java.awt.Rectangle;
import net.runelite.client.ui.PluginPanel;

/**
 * Side panel that shows Twitch chat as a scrolling message feed, styled to match Twitch's
 * own chat window as closely as a Swing side panel reasonably can, instead of the official
 * Twitch plugin's chatbox-PM format. Only ever connects to the single channel configured
 * in the plugin's settings - there is no free-text field or other way to switch to a
 * different channel from the panel itself.
 * <p>
 * Reading chat works with no login. Logging in with Twitch (device code flow) additionally
 * unlocks sending messages.
 */
public class TwitchSidePanel extends PluginPanel
{
	public interface Handlers
	{
		void onConnectClicked();

		void onDisconnectClicked();

		void onLoginClicked();

		void onCancelLoginClicked();

		void onLogoutClicked();

		void onSendMessage(String text);
	}

	private enum AuthState
	{
		LOGGED_OUT,
		WAITING_FOR_CODE,
		LOGGED_IN
	}

	private static final Color BACKGROUND = new Color(0x0e, 0x0e, 0x12);
	private static final Color TAB_BAR_BACKGROUND = new Color(0x17, 0x17, 0x1d);
	private static final Color DIVIDER = new Color(0x26, 0x26, 0x2e);
	private static final Color MUTED_TEXT = new Color(0x8a, 0x8a, 0x95);
	private static final Color ACCENT = new Color(0x91, 0x46, 0xff);
	private static final Color ACCENT_HOVER = new Color(0xa8, 0x6c, 0xff);

	private final JLabel channelLabel;
	private final OpenChannelButton openChannelButton;
	private final PillButton connectButton;
	private final JLabel statusLabel;
	private final JLabel authStatusLabel;
	private final PillButton authButton;
	private final SubGiftCarouselPanel subGiftCarousel;
	private final JPanel messageListPanel;
	private final JScrollPane scrollPane;
	private final PlaceholderTextField messageField;
	private final PillButton sendButton;
	private final JPanel sendRow;

	private final Handlers handlers;
	private boolean connected;
	private boolean channelConfigured;
	private AuthState authState = AuthState.LOGGED_OUT;
	private volatile String myUsername;
	private volatile String currentChannel = "";

	// Most-recently-seen chatters (most recent first), for the "@" mention autocomplete -
	// capped so a long-running session doesn't grow this unbounded.
	private static final int MAX_RECENT_USERNAMES = 300;
	private final List<String> recentUsernames = new ArrayList<>();

	public TwitchSidePanel(Handlers handlers, String channel)
	{
		// PluginPanel's default (no-arg) constructor wraps all content in its own
		// DynamicGridLayout + JScrollPane machinery, which conflicts with our own
		// BorderLayout + JScrollPane below (the message feed's viewport was permanently
		// stuck at 0 height until this was found). super(false) opts out of that, giving
		// this panel full control over its own layout and scrolling.
		super(false);

		this.handlers = handlers;

		setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 0));
		setBackground(BACKGROUND);
		setLayout(new BorderLayout());

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(BACKGROUND);
		header.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));

		JLabel title = new JLabel("Twitch Chat");
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(Font.BOLD, 16f));
		title.setAlignmentX(LEFT_ALIGNMENT);

		JPanel connectRow = new JPanel(new BorderLayout(6, 0));
		connectRow.setBackground(BACKGROUND);
		connectRow.setAlignmentX(LEFT_ALIGNMENT);
		connectRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		connectRow.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));

		channelLabel = new JLabel();
		channelLabel.setForeground(MUTED_TEXT);

		openChannelButton = new OpenChannelButton(BACKGROUND);
		openChannelButton.addActionListener(e -> openChannelInBrowser());

		JPanel channelNameRow = new JPanel(new BorderLayout(2, 0));
		channelNameRow.setOpaque(false);
		channelNameRow.add(channelLabel, BorderLayout.CENTER);
		channelNameRow.add(openChannelButton, BorderLayout.EAST);

		connectButton = new PillButton("Connect", ACCENT, ACCENT_HOVER);
		connectButton.addActionListener(e -> handleConnectButton());

		connectRow.add(channelNameRow, BorderLayout.CENTER);
		connectRow.add(connectButton, BorderLayout.EAST);

		statusLabel = new JLabel("Not connected");
		statusLabel.setForeground(MUTED_TEXT);
		statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
		statusLabel.setAlignmentX(LEFT_ALIGNMENT);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		JPanel authRow = new JPanel(new BorderLayout(6, 0));
		authRow.setBackground(BACKGROUND);
		authRow.setAlignmentX(LEFT_ALIGNMENT);
		authRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		authRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		authStatusLabel = new JLabel("Not logged in");
		authStatusLabel.setForeground(MUTED_TEXT);
		authStatusLabel.setFont(authStatusLabel.getFont().deriveFont(11f));

		authButton = new PillButton("Log in with Twitch", new Color(0x6a, 0x3d, 0xc7), new Color(0x81, 0x54, 0xdd));
		authButton.addActionListener(e -> handleAuthButton());

		authRow.add(authStatusLabel, BorderLayout.CENTER);
		authRow.add(authButton, BorderLayout.EAST);

		header.add(title);
		header.add(connectRow);
		header.add(statusLabel);
		header.add(authRow);

		JPanel tabBar = new JPanel(new BorderLayout());
		tabBar.setBackground(TAB_BAR_BACKGROUND);
		tabBar.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 1, 0, DIVIDER),
			BorderFactory.createEmptyBorder(6, 0, 6, 0)));
		JLabel tabLabel = new JLabel("STREAM CHAT", SwingConstants.CENTER);
		tabLabel.setForeground(MUTED_TEXT);
		tabLabel.setFont(tabLabel.getFont().deriveFont(Font.BOLD, 11f));
		tabBar.add(tabLabel, BorderLayout.CENTER);

		subGiftCarousel = new SubGiftCarouselPanel();

		JPanel topContainer = new JPanel();
		topContainer.setLayout(new BoxLayout(topContainer, BoxLayout.Y_AXIS));
		topContainer.setBackground(BACKGROUND);
		topContainer.add(header);
		topContainer.add(tabBar);
		topContainer.add(subGiftCarousel);

		// A plain JPanel doesn't implement Scrollable, so a JScrollPane's viewport never
		// forces its width to match the viewport - it's left free to grow as wide as its
		// widest child wants. Since HORIZONTAL_SCROLLBAR_NEVER means there's no horizontal
		// scrollbar to reveal that overflow, long chat lines were simply clipped at the
		// panel's edge instead of wrapping. Overriding getScrollableTracksViewportWidth()
		// to always return true pins this panel's width to the viewport, which in turn
		// gives each row's JTextPane a real bounded width to wrap its text against.
		messageListPanel = new ScrollableMessagePanel();
		messageListPanel.setLayout(new BoxLayout(messageListPanel, BoxLayout.Y_AXIS));
		messageListPanel.setBackground(BACKGROUND);
		messageListPanel.setBorder(BorderFactory.createEmptyBorder(6, 0, 6, 0));
		// Pinned as the last component always (see appendMessage/clearMessages) so any
		// leftover vertical space in a quiet channel collects here at the bottom instead
		// of BoxLayout distributing it into the message rows themselves.
		messageListPanel.add(Box.createVerticalGlue());

		scrollPane = new JScrollPane(messageListPanel);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.getViewport().setBackground(BACKGROUND);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		sendRow = new JPanel(new BorderLayout(8, 0));
		sendRow.setBackground(BACKGROUND);
		sendRow.setBorder(BorderFactory.createCompoundBorder(
			BorderFactory.createMatteBorder(1, 0, 0, 0, DIVIDER),
			BorderFactory.createEmptyBorder(8, 10, 10, 10)));
		sendRow.setVisible(false);

		Color fieldBackground = new Color(0x1e, 0x1e, 0x26);

		// The side panel is much narrower than Twitch's own chat box, which is where the
		// full "Send a message" wording comes from - shortened so it reliably fits
		// alongside the "Chat" send button without clipping.
		messageField = new PlaceholderTextField("Message");
		messageField.setBackground(fieldBackground);
		messageField.setForeground(Color.WHITE);
		messageField.setCaretColor(Color.WHITE);
		messageField.setBorder(BorderFactory.createEmptyBorder());
		messageField.setMargin(new Insets(6, 10, 6, 10));
		messageField.addActionListener(e -> handleSend());
		new MentionAutocomplete(messageField, () -> recentUsernames);

		sendButton = new PillButton("Chat", ACCENT, ACCENT_HOVER);
		sendButton.addActionListener(e -> handleSend());

		sendRow.add(messageField, BorderLayout.CENTER);
		sendRow.add(sendButton, BorderLayout.EAST);

		add(topContainer, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);
		add(sendRow, BorderLayout.SOUTH);

		setChannel(channel);
		setConnected(false);
	}

	private void handleConnectButton()
	{
		if (connected)
		{
			handlers.onDisconnectClicked();
		}
		else if (channelConfigured)
		{
			handlers.onConnectClicked();
		}
	}

	private void handleAuthButton()
	{
		switch (authState)
		{
			case LOGGED_OUT:
				handlers.onLoginClicked();
				break;
			case WAITING_FOR_CODE:
				handlers.onCancelLoginClicked();
				showLoginPrompt();
				break;
			case LOGGED_IN:
				handlers.onLogoutClicked();
				break;
		}
	}

	private void handleSend()
	{
		String text = messageField.getText();
		if (text != null && !text.trim().isEmpty())
		{
			handlers.onSendMessage(text);
			messageField.setText("");
		}
	}

	/**
	 * Updates the channel this panel will connect to (read from plugin config - there is
	 * no in-panel way to type a different one). Safe to call again later if the user edits
	 * the config while the panel is open.
	 */
	public void setChannel(String channel)
	{
		SwingUtilities.invokeLater(() ->
		{
			currentChannel = channel == null ? "" : channel.trim();
			channelConfigured = !currentChannel.isEmpty();
			channelLabel.setText(channelConfigured ? "#" + currentChannel : "No channel set");
			connectButton.setEnabled(channelConfigured || connected);
			openChannelButton.setEnabled(channelConfigured);
			if (!channelConfigured)
			{
				setStatus("Set your channel in the plugin settings", true);
			}
		});
	}

	/**
	 * Opens the configured channel's Twitch page in the system's default browser - works
	 * regardless of connection state, since it's just a URL, not anything the plugin's own
	 * chat connection is involved in.
	 */
	private void openChannelInBrowser()
	{
		String channel = currentChannel;
		if (channel.isEmpty())
		{
			return;
		}

		try
		{
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
			{
				Desktop.getDesktop().browse(URI.create("https://www.twitch.tv/" + channel));
			}
		}
		catch (Exception ignored)
		{
			// Best-effort - nothing else to do if the platform can't open a browser.
		}
	}

	public void setConnected(boolean connected)
	{
		SwingUtilities.invokeLater(() ->
		{
			this.connected = connected;
			connectButton.setText(connected ? "Disconnect" : "Connect");
			connectButton.setEnabled(connected || channelConfigured);
			updateSendRowEnabled();
		});
	}

	public void setStatus(String text, boolean isError)
	{
		SwingUtilities.invokeLater(() ->
		{
			statusLabel.setText(text);
			statusLabel.setForeground(isError ? new Color(0xff, 0x6b, 0x6b) : MUTED_TEXT);
		});
	}

	/** Not logged in, no login attempt in progress. */
	public void showLoginPrompt()
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.LOGGED_OUT;
			authStatusLabel.setForeground(MUTED_TEXT);
			authStatusLabel.setText("Not logged in");
			authButton.setText("Log in with Twitch");
			sendRow.setVisible(false);
			myUsername = null;
		});
	}

	/** A device code was issued and this is waiting for the user to approve it. */
	public void showDeviceCode(String userCode, String verificationUri)
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.WAITING_FOR_CODE;
			authStatusLabel.setForeground(MUTED_TEXT);
			authStatusLabel.setText("Go to " + verificationUri + " and enter: " + userCode);
			authButton.setText("Cancel");
		});
	}

	public void showLoginError(String message)
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.LOGGED_OUT;
			authStatusLabel.setForeground(new Color(0xff, 0x6b, 0x6b));
			authStatusLabel.setText(message);
			authButton.setText("Log in with Twitch");
			sendRow.setVisible(false);
			myUsername = null;
		});
	}

	public void showLoggedIn(String username)
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.LOGGED_IN;
			authStatusLabel.setForeground(MUTED_TEXT);
			authStatusLabel.setText("Logged in as " + username);
			authButton.setText("Log out");
			myUsername = username;
			updateSendRowEnabled();
		});
	}

	private void updateSendRowEnabled()
	{
		sendRow.setVisible(authState == AuthState.LOGGED_IN);
		messageField.setEnabled(connected);
		sendButton.setEnabled(connected);
	}

	public void appendMessage(TwitchMessage message, boolean colorUsernames, boolean showTimestamps, int maxMessages,
		Map<String, ImageIcon> badgeIcons)
	{
		SwingUtilities.invokeLater(() ->
		{
			recordUsername(message.displayName);

			ChatMessageRowPanel row = new ChatMessageRowPanel(message, colorUsernames, showTimestamps,
				badgeIcons, myUsername, this::startReplyTo);
			insertRow(row);

			while (messageListPanel.getComponentCount() - 1 > maxMessages)
			{
				messageListPanel.remove(0);
			}

			messageListPanel.revalidate();
			messageListPanel.repaint();

			// revalidate() only marks the tree invalid - the actual re-layout (which is
			// what grows the scrollbar's max to account for the new row) happens later on
			// the event queue. Reading getMaximum() right after revalidate() picked up the
			// *previous* max, landing one message short and clipping the newest row until
			// the user scrolled manually. validate() forces that re-layout synchronously,
			// so the scrollbar's max is already correct by the time we read it below.
			scrollPane.validate();

			// Scroll to bottom on every new message - simple v1 behavior, doesn't yet
			// preserve scroll position if the user has scrolled up to read history.
			scrollPane.getVerticalScrollBar().setValue(scrollPane.getVerticalScrollBar().getMaximum());
		});
	}

	/**
	 * Appends a plugin-generated notice - e.g. "Connected to #channel" - styled distinctly
	 * from a real chat message (see {@link SystemMessageRowPanel}) so it reads as a system
	 * line, most usefully as a visible marker in the feed itself of exactly when a channel
	 * switch/reconnect happened, not just in the status line above the feed.
	 */
	public void appendSystemMessage(String text)
	{
		SwingUtilities.invokeLater(() ->
		{
			insertRow(new SystemMessageRowPanel(text));
			messageListPanel.revalidate();
			messageListPanel.repaint();
			scrollPane.validate();
			scrollPane.getVerticalScrollBar().setValue(scrollPane.getVerticalScrollBar().getMaximum());
		});
	}

	/** The trailing glue component must stay last, so insert new rows just before it. */
	private void insertRow(Component row)
	{
		messageListPanel.add(row, messageListPanel.getComponentCount() - 1);
	}

	public void clearMessages()
	{
		SwingUtilities.invokeLater(() ->
		{
			messageListPanel.removeAll();
			messageListPanel.add(Box.createVerticalGlue());
			messageListPanel.revalidate();
			messageListPanel.repaint();
		});
	}

	public void addSubEvent(TwitchSubEvent event)
	{
		subGiftCarousel.addEvent(event);
	}

	/**
	 * Called (from the EDT, since it's the message-list swing thread already) whenever a
	 * message is appended, so the "@" mention autocomplete has a live list of who's
	 * actually in chat right now to suggest from. Most-recent-first, deduplicated
	 * case-insensitively (a chatter typing again just moves back to the front) and capped
	 * so a long-running session doesn't grow this unbounded.
	 */
	private void recordUsername(String username)
	{
		recentUsernames.removeIf(existing -> existing.equalsIgnoreCase(username));
		recentUsernames.add(0, username);
		if (recentUsernames.size() > MAX_RECENT_USERNAMES)
		{
			recentUsernames.remove(recentUsernames.size() - 1);
		}
	}

	/**
	 * Starts a reply to {@code username} - the same click-a-name-to-@mention gesture
	 * Twitch's own chat offers - by replacing the message field's contents with
	 * "@username ". Triggered by clicking a sender's name in {@link ChatMessageRowPanel}.
	 */
	private void startReplyTo(String username)
	{
		String text = "@" + username + " ";
		messageField.setText(text);
		messageField.requestFocusInWindow();
		messageField.setCaretPosition(text.length());
	}

	/**
	 * A plain JPanel doesn't implement {@link Scrollable}, so a JScrollPane's viewport
	 * never forces its width to match the viewport - it's left free to grow as wide as its
	 * widest child wants. Since the message feed disables the horizontal scrollbar, that
	 * overflow was simply clipped at the panel's edge instead of wrapping, cutting off long
	 * chat lines. Tracking the viewport's width here gives each row's JTextPane a real
	 * bounded width to wrap its text against.
	 */
	private static class ScrollableMessagePanel extends JPanel implements Scrollable
	{
		@Override
		public boolean getScrollableTracksViewportWidth()
		{
			return true;
		}

		@Override
		public boolean getScrollableTracksViewportHeight()
		{
			return false;
		}

		@Override
		public Dimension getPreferredScrollableViewportSize()
		{
			return getPreferredSize();
		}

		@Override
		public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return 16;
		}

		@Override
		public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction)
		{
			return visibleRect.height;
		}
	}
}
