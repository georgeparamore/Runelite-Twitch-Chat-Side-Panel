package com.twitchsidepanel.ui;

import com.twitchsidepanel.twitch.TwitchMessage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import javax.swing.BorderFactory;
import javax.swing.ImageIcon;
import javax.swing.JPanel;
import javax.swing.JTextPane;
import javax.swing.text.BadLocationException;
import javax.swing.text.SimpleAttributeSet;
import javax.swing.text.StyleConstants;
import javax.swing.text.StyledDocument;

/**
 * A single chat line, flowing as one continuous wrapped paragraph the way Twitch's own
 * chat renders it - "[badges] Username: message text" all in one JTextPane, rather than a
 * name row stacked above a separate body - with Twitch emotes rendered as inline images.
 */
public class ChatMessageRowPanel extends JPanel
{
	private static final Color DEFAULT_NAME_COLOR = new Color(0x9b, 0x9b, 0xff);
	private static final Color BODY_COLOR = new Color(0xef, 0xef, 0xf1);
	private static final Color TIME_COLOR = new Color(0x6d, 0x6d, 0x78);
	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm");

	public ChatMessageRowPanel(TwitchMessage message, boolean colorUsernames, boolean showTimestamp,
		Map<String, ImageIcon> emoteIcons, Map<String, ImageIcon> badgeIcons)
	{
		setOpaque(false);
		setLayout(new BorderLayout());
		setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));

		// Never let this row cap out at a stale (0,0) BoxLayout-computed max size - that
		// happens if setMaximumSize() is called before a parent BoxLayout has real
		// children, and the row silently renders as a sliver forever after.
		setMaximumSize(new Dimension(Integer.MAX_VALUE, Integer.MAX_VALUE));
		setAlignmentX(LEFT_ALIGNMENT);

		JTextPane pane = buildLine(message, colorUsernames, showTimestamp, emoteIcons, badgeIcons);
		add(pane, BorderLayout.CENTER);
	}

	private JTextPane buildLine(TwitchMessage message, boolean colorUsernames, boolean showTimestamp,
		Map<String, ImageIcon> emoteIcons, Map<String, ImageIcon> badgeIcons)
	{
		JTextPane pane = new JTextPane();
		pane.setEditable(false);
		pane.setOpaque(false);
		pane.setBorder(null);
		pane.setFont(pane.getFont().deriveFont(12f));

		StyledDocument doc = pane.getStyledDocument();

		SimpleAttributeSet timeStyle = new SimpleAttributeSet();
		StyleConstants.setForeground(timeStyle, TIME_COLOR);

		SimpleAttributeSet nameStyle = new SimpleAttributeSet();
		StyleConstants.setForeground(nameStyle, colorUsernames && message.color != null ? message.color : DEFAULT_NAME_COLOR);
		StyleConstants.setBold(nameStyle, true);

		SimpleAttributeSet bodyStyle = new SimpleAttributeSet();
		StyleConstants.setForeground(bodyStyle, BODY_COLOR);

		try
		{
			if (showTimestamp)
			{
				doc.insertString(doc.getLength(), TIME_FORMAT.format(new Date(message.receivedAtMillis)) + "  ", timeStyle);
			}

			for (TwitchMessage.BadgeRef badge : message.badges)
			{
				ImageIcon icon = badgeIcons.get(badge.setId + "/" + badge.version);
				if (icon != null)
				{
					pane.setCaretPosition(doc.getLength());
					pane.insertIcon(icon);
					doc.insertString(doc.getLength(), " ", bodyStyle);
				}
			}

			doc.insertString(doc.getLength(), message.displayName + ": ", nameStyle);
			insertBodyWithEmotes(pane, doc, message, emoteIcons, bodyStyle);
		}
		catch (BadLocationException e)
		{
			// Only possible if our own offset bookkeeping above is wrong; fall back to
			// the raw, un-styled message rather than showing nothing.
			pane.setText(message.displayName + ": " + message.body);
		}

		return pane;
	}

	private void insertBodyWithEmotes(JTextPane pane, StyledDocument doc, TwitchMessage message,
		Map<String, ImageIcon> emoteIcons, SimpleAttributeSet bodyStyle) throws BadLocationException
	{
		String body = message.body;
		int cursor = 0;

		for (TwitchMessage.EmoteRef emote : message.emotes)
		{
			if (emote.start < cursor || emote.end >= body.length() || emote.start > emote.end)
			{
				// Overlapping/out-of-range ranges shouldn't happen from Twitch, but skip
				// defensively rather than corrupt the rendered message.
				continue;
			}

			if (emote.start > cursor)
			{
				doc.insertString(doc.getLength(), body.substring(cursor, emote.start), bodyStyle);
			}

			ImageIcon icon = emoteIcons.get(emote.id);
			if (icon != null)
			{
				pane.setCaretPosition(doc.getLength());
				pane.insertIcon(icon);
			}
			else
			{
				// Emote image not available (fetch failed, or not resolved yet) - fall
				// back to the plain-text emote code so the message still reads.
				doc.insertString(doc.getLength(), body.substring(emote.start, emote.end + 1), bodyStyle);
			}

			cursor = emote.end + 1;
		}

		if (cursor < body.length())
		{
			doc.insertString(doc.getLength(), body.substring(cursor), bodyStyle);
		}
	}
}
