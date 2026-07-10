package com.twitchsidepanel.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JPanel;

/**
 * A JPanel with rounded corners, hand-painted because Swing (and RuneLite's LAF on top of
 * it) has no native support for rounded containers.
 */
public class RoundedPanel extends JPanel
{
	private final int arc;
	private Color background;

	public RoundedPanel(int arc, Color background)
	{
		this.arc = arc;
		this.background = background;
		setOpaque(false);
	}

	public void setBackgroundColor(Color background)
	{
		this.background = background;
		repaint();
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(background);
		g2.fillRoundRect(0, 0, getWidth(), getHeight(), arc, arc);
		g2.dispose();
		super.paintComponent(g);
	}
}
