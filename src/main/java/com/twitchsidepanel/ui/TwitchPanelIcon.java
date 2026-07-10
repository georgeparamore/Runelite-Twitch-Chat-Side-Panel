package com.twitchsidepanel.ui;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;

/**
 * The sidebar navigation icon, drawn in code rather than shipped as a bundled PNG - a
 * simple purple chat bubble with a "T" is enough to distinguish this panel in the toolbar.
 */
public final class TwitchPanelIcon
{
	private TwitchPanelIcon()
	{
	}

	public static BufferedImage create()
	{
		BufferedImage image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		g.setColor(new Color(0x91, 0x46, 0xff));
		g.fillRoundRect(0, 0, 16, 12, 4, 4);
		g.fillPolygon(new int[]{4, 8, 4}, new int[]{12, 12, 16}, 3);

		g.setColor(Color.WHITE);
		g.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 9));
		g.drawString("T", 5, 10);

		g.dispose();
		return image;
	}
}
