package com.twitchsidepanel.ui;

import com.twitchsidepanel.twitch.TwitchMessage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import net.runelite.client.ui.PluginPanel;

/**
 * Party-Hub-style side panel that shows Twitch chat as a scrolling message feed instead
 * of the official Twitch plugin's chatbox-PM format.
 */
public class TwitchSidePanel extends PluginPanel
{
	public interface Handlers
	{
		void onConnectClicked(String channel);

		void onDisconnectClicked();
	}

	private static final Color BACKGROUND = new Color(0x1b, 0x18, 0x24);
	private static final Color CARD_BACKGROUND = new Color(0x2a, 0x24, 0x38);

	private final JTextField channelField;
	private final PillButton connectButton;
	private final JLabel statusLabel;
	private final JPanel messageListPanel;
	private final JScrollPane scrollPane;

	private final Handlers handlers;
	private boolean connected;

	public TwitchSidePanel(Handlers handlers, String initialChannel)
	{
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

		channelField = new JTextField(initialChannel == null ? "" : initialChannel);
		channelField.setToolTipText("Twitch channel name");

		connectButton = new PillButton("Connect", new Color(0x91, 0x46, 0xff), new Color(0xa8, 0x6c, 0xff));
		connectButton.addActionListener(e -> handleConnectButton());

		connectRow.add(channelField, BorderLayout.CENTER);
		connectRow.add(connectButton, BorderLayout.EAST);

		statusLabel = new JLabel("Not connected");
		statusLabel.setForeground(Color.GRAY);
		statusLabel.setFont(statusLabel.getFont().deriveFont(11f));
		statusLabel.setAlignmentX(LEFT_ALIGNMENT);
		statusLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 0, 0));

		header.add(title);
		header.add(Box.createVerticalStrut(8));
		header.add(connectRow);
		header.add(statusLabel);

		messageListPanel = new JPanel();
		messageListPanel.setLayout(new BoxLayout(messageListPanel, BoxLayout.Y_AXIS));
		messageListPanel.setBackground(BACKGROUND);

		scrollPane = new JScrollPane(messageListPanel);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
		scrollPane.getViewport().setBackground(BACKGROUND);
		scrollPane.getVerticalScrollBar().setUnitIncrement(16);

		add(header, BorderLayout.NORTH);
		add(scrollPane, BorderLayout.CENTER);

		setConnected(false);
	}

	private void handleConnectButton()
	{
		if (connected)
		{
			handlers.onDisconnectClicked();
		}
		else
		{
			String channel = channelField.getText().trim();
			if (!channel.isEmpty())
			{
				handlers.onConnectClicked(channel);
			}
		}
	}

	public void setConnected(boolean connected)
	{
		SwingUtilities.invokeLater(() ->
		{
			this.connected = connected;
			connectButton.setText(connected ? "Disconnect" : "Connect");
			channelField.setEnabled(!connected);
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

	public void appendMessage(TwitchMessage message, boolean colorUsernames, boolean showTimestamps, int maxMessages)
	{
		SwingUtilities.invokeLater(() ->
		{
			ChatMessageRowPanel row = new ChatMessageRowPanel(message, colorUsernames, showTimestamps);
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
