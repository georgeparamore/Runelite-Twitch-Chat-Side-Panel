package com.twitchsidepanel.ui;

import java.awt.Color;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;

/**
 * Small icon-only button that opens the emote picker, overlaid inside the message field's
 * own right edge - the same embedded placement Twitch's own chat box uses for its smiley
 * emote button, rather than a separate control taking up its own row width. Its background
 * is painted to match the field's exactly so there's no visible seam, with just a plain
 * outlined smiley (no filled chip behind it) that brightens on hover - matching Twitch's own
 * plain icon-in-field look. Drawn directly in code rather than shipping a bundled image
 * asset, same approach as {@link TwitchPanelIcon}. Stays opaque with a manually painted
 * background rather than using setContentAreaFilled(false) + setOpaque(false) - that
 * combination renders as literally nothing under RuneLite's runtime LAF (see the same note
 * on {@link PillButton}).
 */
public class EmoteButton extends JButton
{
	private static final Color ICON_COLOR = new Color(0x8a, 0x8a, 0x95);
	private static final Color ICON_HOVER_COLOR = Color.WHITE;

	private final Color fieldBackground;

	public EmoteButton(Color fieldBackground)
	{
		this.fieldBackground = fieldBackground;
		setOpaque(true);
		setContentAreaFilled(true);
		setFocusPainted(false);
		setBorderPainted(false);
		setPreferredSize(new Dimension(22, 22));
		setToolTipText("Emotes");
		// Being nested inside a JTextField (a null cursor inherits the parent's), this
		// would otherwise show the field's text-editing I-beam cursor instead of a normal
		// pointer while hovering the button.
		setCursor(Cursor.getDefaultCursor());
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g2.setColor(fieldBackground);
		g2.fillRect(0, 0, getWidth(), getHeight());

		g2.setColor(getModel().isRollover() ? ICON_HOVER_COLOR : ICON_COLOR);
		// Anchored close to the button's own right edge (which itself sits flush against
		// the field's true right edge - see the setMargin() note at the call site) rather
		// than centered, so the glyph reads as tucked into the corner like Twitch's own
		// embedded emote button instead of floating mid-button with dead space beside it.
		int cx = getWidth() - 8;
		int cy = getHeight() / 2;
		g2.drawOval(cx - 6, cy - 6, 12, 12);
		g2.fillRect(cx - 4, cy - 1, 2, 2);
		g2.fillRect(cx + 2, cy - 1, 2, 2);
		g2.drawArc(cx - 4, cy - 1, 8, 5, 200, 140);

		g2.dispose();
	}
}
