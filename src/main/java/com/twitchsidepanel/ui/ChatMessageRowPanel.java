package com.twitchsidepanel.ui;

import com.twitchsidepanel.twitch.TwitchMessage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.text.SimpleDateFormat;
import java.util.Date;
import javax.swing.BorderFactory;
import javax.swing.JLabel;
import javax.swing.JTextArea;

/**
 * A single chat line: "[hh:mm] Username: message body". Built with a JTextArea for the
 * body so long messages wrap instead of getting clipped or forcing horizontal scroll.
 */
public class ChatMessageRowPanel extends RoundedPanel
{
	private static final Color DEFAULT_NAME_COLOR = new Color(0x9b, 0x9b, 0xff);
	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm");

	public ChatMessageRowPanel(TwitchMessage message, boolean colorUsernames, boolean showTimestamp)
	{
		super(8, new Color(45, 40, 60));
		setLayout(new BorderLayout(6, 2));
		setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		// Never let this row cap out at a stale (0,0) BoxLayout-computed max size - that
		// happens if setMaximumSize() is called before a parent BoxLayout has real
		// children, and the row silently renders as a sliver forever after.
		setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		setAlignmentX(LEFT_ALIGNMENT);

		StringBuilder header = new StringBuilder();
		if (showTimestamp)
		{
			header.append(TIME_FORMAT.format(new Date(message.receivedAtMillis))).append("  ");
		}
		header.append(message.displayName);

		JLabel nameLabel = new JLabel(header.toString());
		Color nameColor = colorUsernames && message.color != null ? message.color : DEFAULT_NAME_COLOR;
		nameLabel.setForeground(nameColor);
		nameLabel.setFont(nameLabel.getFont().deriveFont(java.awt.Font.BOLD, 12f));

		JTextArea bodyArea = new JTextArea(message.body);
		bodyArea.setEditable(false);
		bodyArea.setLineWrap(true);
		bodyArea.setWrapStyleWord(true);
		bodyArea.setOpaque(false);
		bodyArea.setForeground(Color.WHITE.darker());
		bodyArea.setFont(bodyArea.getFont().deriveFont(12f));
		bodyArea.setBorder(null);

		add(nameLabel, BorderLayout.NORTH);
		add(bodyArea, BorderLayout.CENTER);
	}
}
