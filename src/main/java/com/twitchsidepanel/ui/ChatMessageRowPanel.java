package com.twitchsidepanel.ui;

import com.twitchsidepanel.twitch.TwitchMessage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * A single chat line: badge icons, timestamp, colored username, then a message body that
 * renders Twitch emotes as inline images (falling back to their plain-text code if the
 * image failed to load or hasn't been fetched yet).
 */
public class ChatMessageRowPanel extends RoundedPanel
{
	private static final Color DEFAULT_NAME_COLOR = new Color(0x9b, 0x9b, 0xff);
	private static final Color BODY_COLOR = new Color(0xd8, 0xd8, 0xe0);
	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm");

	public ChatMessageRowPanel(TwitchMessage message, boolean colorUsernames, boolean showTimestamp,
		Map<String, ImageIcon> emoteIcons, Map<String, ImageIcon> badgeIcons)
	{
		super(8, new Color(45, 40, 60));
		setLayout(new BorderLayout(6, 2));
		setBorder(BorderFactory.createEmptyBorder(6, 8, 6, 8));

		// Never let this row cap out at a stale (0,0) BoxLayout-computed max size - that
		// happens if setMaximumSize() is called before a parent BoxLayout has real
		// children, and the row silently renders as a sliver forever after.
		setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		setAlignmentX(LEFT_ALIGNMENT);

		JPanel headerRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
		headerRow.setOpaque(false);

		if (showTimestamp)
		{
			JLabel timeLabel = new JLabel(TIME_FORMAT.format(new Date(message.receivedAtMillis)));
			timeLabel.setForeground(Color.GRAY);
			timeLabel.setFont(timeLabel.getFont().deriveFont(11f));
			headerRow.add(timeLabel);
		}

		for (TwitchMessage.BadgeRef badge : message.badges)
		{
			ImageIcon icon = badgeIcons.get(badge.setId + "/" + badge.version);
			if (icon != null)
			{
				headerRow.add(new JLabel(icon));
			}
		}

		JLabel nameLabel = new JLabel(message.displayName);
		Color nameColor = colorUsernames && message.color != null ? message.color : DEFAULT_NAME_COLOR;
		nameLabel.setForeground(nameColor);
		nameLabel.setFont(nameLabel.getFont().deriveFont(java.awt.Font.BOLD, 12f));
		headerRow.add(nameLabel);

		JTextPane bodyPane = buildBodyPane(message, emoteIcons);

		add(headerRow, BorderLayout.NORTH);
		add(bodyPane, BorderLayout.CENTER);
	}

	private JTextPane buildBodyPane(TwitchMessage message, Map<String, ImageIcon> emoteIcons)
	{
		JTextPane bodyPane = new JTextPane();
		bodyPane.setEditable(false);
		bodyPane.setOpaque(false);
		bodyPane.setBorder(null);
		bodyPane.setFont(bodyPane.getFont().deriveFont(12f));

		SimpleAttributeSet textStyle = new SimpleAttributeSet();
		StyleConstants.setForeground(textStyle, BODY_COLOR);

		StyledDocument doc = bodyPane.getStyledDocument();
		String body = message.body;

		try
		{
			int cursor = 0;
			for (TwitchMessage.EmoteRef emote : message.emotes)
			{
				if (emote.start < cursor || emote.end >= body.length() || emote.start > emote.end)
				{
					// Overlapping/out-of-range ranges shouldn't happen from Twitch, but
					// skip defensively rather than corrupt the rendered message.
					continue;
				}

				if (emote.start > cursor)
				{
					doc.insertString(doc.getLength(), body.substring(cursor, emote.start), textStyle);
				}

				ImageIcon icon = emoteIcons.get(emote.id);
				if (icon != null)
				{
					bodyPane.setCaretPosition(doc.getLength());
					bodyPane.insertIcon(icon);
				}
				else
				{
					// Emote image not available (fetch failed, or not resolved yet) -
					// fall back to the plain-text emote code so the message still reads.
					doc.insertString(doc.getLength(), body.substring(emote.start, emote.end + 1), textStyle);
				}

				cursor = emote.end + 1;
			}

			if (cursor < body.length())
			{
				doc.insertString(doc.getLength(), body.substring(cursor), textStyle);
			}
		}
		catch (BadLocationException e)
		{
			// Only possible if our own offset bookkeeping above is wrong; fall back to
			// the raw, un-emoted message rather than showing nothing.
			bodyPane.setText(body);
		}

		return bodyPane;
	}
}
