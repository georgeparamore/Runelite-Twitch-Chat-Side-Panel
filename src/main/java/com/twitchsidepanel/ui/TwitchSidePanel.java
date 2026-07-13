package com.twitchsidepanel.ui;

import com.twitchsidepanel.twitch.TwitchMessage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

/**
 * Party-Hub-style side panel that shows Twitch chat as a scrolling message feed instead
 * of the official Twitch plugin's chatbox-PM format. Only ever connects to the channel
 * configured in the plugin's settings (your own channel) - there is no way to type in and
 * join an arbitrary channel from the panel itself.
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

	private static final Color BACKGROUND = new Color(0x1b, 0x18, 0x24);
	private static final Color CARD_BACKGROUND = new Color(0x2a, 0x24, 0x38);

	private final JLabel channelLabel;
	private final PillButton connectButton;
	private final JLabel statusLabel;
	private final JLabel authStatusLabel;
	private final PillButton authButton;
	private final JPanel messageListPanel;
	private final JScrollPane scrollPane;
	private final JTextField messageField;
	private final PillButton sendButton;
	private final JPanel sendRow;

	private final Handlers handlers;
	private boolean connected;
	private boolean channelConfigured;
	private AuthState authState = AuthState.LOGGED_OUT;

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
		setLayout(new BorderLayout(0, 8));

		JPanel header = new JPanel();
		header.setLayout(new BoxLayout(header, BoxLayout.Y_AXIS));
		header.setBackground(BACKGROUND);
		header.setBorder(BorderFactory.createEmptyBorder(10, 10, 8, 10));

		JLabel title = new JLabel("Twitch Chat");
		title.setForeground(Color.WHITE);
		title.setFont(title.getFont().deriveFont(java.awt.Font.BOLD, 16f));
		title.setAlignmentX(LEFT_ALIGNMENT);

		JPanel connectRow = new JPanel(new BorderLayout(6, 0));
		connectRow.setBackground(BACKGROUND);
		connectRow.setAlignmentX(LEFT_ALIGNMENT);
		connectRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));

		channelLabel = new JLabel();
		channelLabel.setForeground(Color.LIGHT_GRAY);

		connectButton = new PillButton("Connect", new Color(0x91, 0x46, 0xff), new Color(0xa8, 0x6c, 0xff));
		connectButton.addActionListener(e -> handleConnectButton());

		connectRow.add(channelLabel, BorderLayout.CENTER);
		connectRow.add(connectButton, BorderLayout.EAST);

		statusLabel = new JLabel("Not connected");
		statusLabel.setForeground(Color.GRAY);
		statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
		statusLabel.setAlignmentX(LEFT_ALIGNMENT);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		JPanel authRow = new JPanel(new BorderLayout(6, 0));
		authRow.setBackground(BACKGROUND);
		authRow.setAlignmentX(LEFT_ALIGNMENT);
		authRow.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
		authRow.setBorder(BorderFactory.createEmptyBorder(6, 0, 0, 0));

		authStatusLabel = new JLabel("Not logged in");
		authStatusLabel.setForeground(Color.LIGHT_GRAY);
		authStatusLabel.setFont(authStatusLabel.getFont().deriveFont(11f));

		authButton = new PillButton("Log in with Twitch", new Color(0x6a, 0x3d, 0xc7), new Color(0x81, 0x54, 0xdd));
		authButton.addActionListener(e -> handleAuthButton());

		authRow.add(authStatusLabel, BorderLayout.CENTER);
		authRow.add(authButton, BorderLayout.EAST);

		header.add(title);
		header.add(Box.createVerticalStrut(8));
		header.add(connectRow);
		header.add(statusLabel);
		header.add(authRow);

		messageListPanel = new JPanel();
		messageListPanel.setLayout(new BoxLayout(messageListPanel, BoxLayout.Y_AXIS));
		messageListPanel.setBackground(BACKGROUND);

		scrollPane = new JScrollPane(messageListPanel);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.getViewport().setBackground(BACKGROUND);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		sendRow = new JPanel(new BorderLayout(6, 0));
		sendRow.setBackground(BACKGROUND);
		sendRow.setBorder(BorderFactory.createEmptyBorder(8, 10, 10, 10));
		sendRow.setVisible(false);

		messageField = new JTextField();
		messageField.setToolTipText("Send a message");
		messageField.addActionListener(e -> handleSend());

		sendButton = new PillButton("Chat", new Color(0x91, 0x46, 0xff), new Color(0xa8, 0x6c, 0xff));
		sendButton.addActionListener(e -> handleSend());

		sendRow.add(messageField, BorderLayout.CENTER);
		sendRow.add(sendButton, BorderLayout.EAST);

		add(header, BorderLayout.NORTH);
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
			channelConfigured = channel != null && !channel.trim().isEmpty();
			channelLabel.setText(channelConfigured ? "#" + channel.trim() : "No channel set");
			connectButton.setEnabled(channelConfigured || connected);
			if (!channelConfigured)
			{
				setStatus("Set your channel in the plugin settings", true);
			}
		});
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
			statusLabel.setForeground(isError ? new Color(0xff, 0x6b, 0x6b) : Color.GRAY);
		});
	}

	/** Not logged in, no login attempt in progress. */
	public void showLoginPrompt()
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.LOGGED_OUT;
			authStatusLabel.setForeground(Color.LIGHT_GRAY);
			authStatusLabel.setText("Not logged in");
			authButton.setText("Log in with Twitch");
			sendRow.setVisible(false);
		});
	}

	/** A device code was issued and this is waiting for the user to approve it. */
	public void showDeviceCode(String userCode, String verificationUri)
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.WAITING_FOR_CODE;
			authStatusLabel.setForeground(Color.LIGHT_GRAY);
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
		});
	}

	public void showLoggedIn(String username)
	{
		SwingUtilities.invokeLater(() ->
		{
			authState = AuthState.LOGGED_IN;
			authStatusLabel.setForeground(Color.LIGHT_GRAY);
			authStatusLabel.setText("Logged in as " + username);
			authButton.setText("Log out");
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
		Map<String, ImageIcon> emoteIcons, Map<String, ImageIcon> badgeIcons)
	{
		SwingUtilities.invokeLater(() ->
		{
			ChatMessageRowPanel row = new ChatMessageRowPanel(message, colorUsernames, showTimestamps,
				emoteIcons, badgeIcons);
			row.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createEmptyBorder(0, 0, 4, 0),
				row.getBorder()));

			messageListPanel.add(row);

			while (messageListPanel.getComponentCount() > maxMessages)
			{
				messageListPanel.remove(0);
			}

			messageListPanel.revalidate();
			messageListPanel.repaint();

			// Scroll to bottom on every new message - simple v1 behavior, doesn't yet
			// preserve scroll position if the user has scrolled up to read history.
			SwingUtilities.invokeLater(() ->
				scrollPane.getVerticalScrollBar().setValue(scrollPane.getVerticalScrollBar().getMaximum()));
		});
	}

	public void clearMessages()
	{
		SwingUtilities.invokeLater(() ->
		{
			messageListPanel.removeAll();
			messageListPanel.revalidate();
			messageListPanel.repaint();
		});
	}
}
