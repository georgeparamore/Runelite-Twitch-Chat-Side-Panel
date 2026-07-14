package com.twitchsidepanel.ui;

import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JButton;

/**
 * Small round icon-only button that opens the emote picker, sitting next to the send
 * button - same spot Twitch's own chat box puts its smiley-face emote button. Drawn
 * directly in code rather than shipping a bundled image asset, same approach as
 * {@link TwitchPanelIcon}. Stays opaque with a manually painted background rather than
 * using setContentAreaFilled(false) + setOpaque(false) - that combination renders as
 * literally nothing under RuneLite's runtime LAF (see the same note on {@link PillButton}).
 */
public class EmoteButton extends JButton
{
	private final Color background;
	private final Color hoverBackground;

	public EmoteButton(Color background, Color hoverBackground)
	{
		this.background = background;
		this.hoverBackground = hoverBackground;
		setOpaque(true);
		setContentAreaFilled(true);
		setFocusPainted(false);
		setBorderPainted(false);
		setPreferredSize(new Dimension(28, 28));
		setToolTipText("Emotes");
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g2.setColor(getModel().isRollover() ? hoverBackground : background);
		g2.fillOval(0, 0, getWidth(), getHeight());

		g2.setColor(Color.WHITE);
		int cx = getWidth() / 2;
		int cy = getHeight() / 2;
		g2.fillOval(cx - 5, cy - 3, 2, 2);
		g2.fillOval(cx + 3, cy - 3, 2, 2);
		g2.drawArc(cx - 5, cy - 3, 10, 7, 200, 140);

		g2.dispose();
	}
}
