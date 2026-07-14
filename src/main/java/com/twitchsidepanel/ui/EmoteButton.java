package com.twitchsidepanel.ui;

import java.awt.Color;
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
		setPreferredSize(new Dimension(24, 24));
		setToolTipText("Emotes");
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g2.setColor(fieldBackground);
		g2.fillRect(0, 0, getWidth(), getHeight());

		g2.setColor(getModel().isRollover() ? ICON_HOVER_COLOR : ICON_COLOR);
		int cx = getWidth() / 2;
		int cy = getHeight() / 2;
		g2.drawOval(cx - 7, cy - 7, 14, 14);
		g2.fillOval(cx - 4, cy - 3, 2, 2);
		g2.fillOval(cx + 2, cy - 3, 2, 2);
		g2.drawArc(cx - 4, cy - 3, 8, 6, 200, 140);

		g2.dispose();
	}
}
