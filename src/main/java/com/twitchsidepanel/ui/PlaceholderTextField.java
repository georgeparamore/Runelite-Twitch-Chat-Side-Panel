package com.twitchsidepanel.ui;

import java.awt.Color;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import javax.swing.JTextField;

/**
 * A JTextField that shows gray hint text when empty and unfocused - Swing has no native
 * placeholder-text support, so this paints it directly rather than pulling in a UI kit.
 */
public class PlaceholderTextField extends JTextField
{
	private final String placeholder;

	public PlaceholderTextField(String placeholder)
	{
		this.placeholder = placeholder;
	}

	@Override
	protected void paintComponent(Graphics g)
	{
		super.paintComponent(g);
		if (!getText().isEmpty())
		{
			return;
		}

		Graphics2D g2 = (Graphics2D) g.create();
		g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		g2.setColor(new Color(0x8a, 0x8a, 0x95));
		g2.setFont(getFont());

		// Clip to the text area (excluding the right inset reserved for an overlaid
		// button, if any) so a long placeholder truncates cleanly at that boundary
		// instead of drawing underneath the button and getting visually cut mid-word.
		java.awt.Insets insets = getInsets();
		g2.setClip(insets.left, 0, Math.max(0, getWidth() - insets.left - insets.right), getHeight());

		int textY = (getHeight() - g2.getFontMetrics().getHeight()) / 2 + g2.getFontMetrics().getAscent();
		g2.drawString(placeholder, insets.left, textY);
		g2.dispose();
	}
}
