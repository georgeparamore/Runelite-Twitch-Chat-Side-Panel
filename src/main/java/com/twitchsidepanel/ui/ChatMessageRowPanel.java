package com.twitchsidepanel.ui;

import com.twitchsidepanel.twitch.TwitchMessage;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Map;
import java.util.function.Consumer;
import java.util.regex.Pattern;
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
 * A message that mentions {@code myUsername} is highlighted the way Twitch's own chat
 * highlights mentions, and clicking any username (the sender's, at the start of the line)
 * starts a reply to that person via {@code onUsernameClicked}.
 */
public class ChatMessageRowPanel extends JPanel
{
	private static final Color DEFAULT_NAME_COLOR = new Color(0x9b, 0x9b, 0xff);
	private static final Color BODY_COLOR = new Color(0xef, 0xef, 0xf1);
	private static final Color TIME_COLOR = new Color(0x6d, 0x6d, 0x78);
	private static final Color MENTION_BACKGROUND = new Color(0x3a, 0x2d, 0x5c);
	private static final Color MENTION_BORDER = new Color(0x91, 0x46, 0xff);
	private static final SimpleDateFormat TIME_FORMAT = new SimpleDateFormat("HH:mm");

	public ChatMessageRowPanel(TwitchMessage message, boolean colorUsernames, boolean showTimestamp,
		Map<String, ImageIcon> emoteIcons, Map<String, ImageIcon> badgeIcons, String myUsername,
		Consumer<String> onUsernameClicked)
	{
		setLayout(new BorderLayout());
		setAlignmentX(LEFT_ALIGNMENT);

		if (mentionsUser(message.body, myUsername))
		{
			setOpaque(true);
			setBackground(MENTION_BACKGROUND);
			setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createMatteBorder(0, 3, 0, 0, MENTION_BORDER),
				BorderFactory.createEmptyBorder(2, 7, 2, 10)));
		}
		else
		{
			setOpaque(false);
			setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
		}

		JTextPane pane = buildLine(message, colorUsernames, showTimestamp, emoteIcons, badgeIcons, onUsernameClicked);
		add(pane, BorderLayout.CENTER);
	}

	/**
	 * A plain JPanel inside a BoxLayout column defaults to an unbounded maximum height
	 * (BorderLayout - this row's own layout - isn't a LayoutManager2, so it never
	 * supplies one), so with only a few short messages in a tall scroll panel, BoxLayout
	 * was distributing all the empty leftover vertical space into the rows themselves,
	 * stretching them apart with huge gaps instead of leaving the space blank at the
	 * bottom. Tying the maximum height to the current preferred height - recomputed live
	 * on every call rather than a one-time snapshot, so it reflects the real wrapped-text
	 * height once this row has an actual assigned width - stops BoxLayout from ever
	 * growing this row past its natural content size.
	 */
	@Override
	public Dimension getMaximumSize()
	{
		return new Dimension(Integer.MAX_VALUE, getPreferredSize().height);
	}

	/**
	 * True if {@code body} mentions {@code username} as a whole word, with or without a
	 * leading "@" - matching Twitch's own "you were mentioned" highlight rule.
	 */
	private static boolean mentionsUser(String body, String username)
	{
		if (username == null || username.isEmpty())
		{
			return false;
		}
		String pattern = "(?i)(?<![A-Za-z0-9_])@?" + Pattern.quote(username) + "(?![A-Za-z0-9_])";
		return Pattern.compile(pattern).matcher(body).find();
	}

	private JTextPane buildLine(TwitchMessage message, boolean colorUsernames, boolean showTimestamp,
		Map<String, ImageIcon> emoteIcons, Map<String, ImageIcon> badgeIcons, Consumer<String> onUsernameClicked)
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

		int nameStart = -1;
		int nameEnd = -1;

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

			nameStart = doc.getLength();
			doc.insertString(doc.getLength(), message.displayName + ": ", nameStyle);
			nameEnd = nameStart + message.displayName.length();

			insertBodyWithEmotes(pane, doc, message, emoteIcons, bodyStyle);
		}
		catch (BadLocationException e)
		{
			// Only possible if our own offset bookkeeping above is wrong; fall back to
			// the raw, un-styled message rather than showing nothing.
			pane.setText(message.displayName + ": " + message.body);
		}

		addUsernameClickHandling(pane, nameStart, nameEnd, message.displayName, onUsernameClicked);

		return pane;
	}

	/**
	 * Lets clicking the sender's name (the "Username" in "Username: message") start a
	 * reply to them, the same click-a-name-to-@mention gesture Twitch's own chat offers.
	 * A hand cursor over the name is the only visual cue since the name is already bold
	 * and colored - anything more (underline, etc.) would fight the emote/badge icons
	 * sharing the same line.
	 */
	private void addUsernameClickHandling(JTextPane pane, int nameStart, int nameEnd, String displayName,
		Consumer<String> onUsernameClicked)
	{
		if (nameStart < 0)
		{
			return;
		}

		pane.addMouseListener(new MouseAdapter()
		{
			@Override
			public void mouseClicked(MouseEvent e)
			{
				if (isOverName(pane, e, nameStart, nameEnd))
				{
					onUsernameClicked.accept(displayName);
				}
			}

			@Override
			public void mouseExited(MouseEvent e)
			{
				pane.setCursor(Cursor.getDefaultCursor());
			}
		});

		pane.addMouseMotionListener(new MouseMotionAdapter()
		{
			@Override
			public void mouseMoved(MouseEvent e)
			{
				pane.setCursor(Cursor.getPredefinedCursor(
					isOverName(pane, e, nameStart, nameEnd) ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
			}
		});
	}

	private static boolean isOverName(JTextPane pane, MouseEvent e, int nameStart, int nameEnd)
	{
		int offset = pane.viewToModel2D(e.getPoint());
		return offset >= nameStart && offset < nameEnd;
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
