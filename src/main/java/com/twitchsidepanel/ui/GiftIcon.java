package com.twitchsidepanel.ui;

import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import javax.swing.ImageIcon;

/**
 * A small hand-drawn gift-box icon for the sub/gift carousel, in the same spirit as
 * {@link TwitchPanelIcon} - drawn in code rather than shipped as a bundled image file.
 */
public final class GiftIcon
{
	private static final ImageIcon INSTANCE = create();

	private GiftIcon()
	{
	}

	public static ImageIcon get()
	{
		return INSTANCE;
	}

	private static ImageIcon create()
	{
		BufferedImage image = new BufferedImage(14, 14, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = image.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

		Color box = new Color(0xff, 0x6b, 0xd0);
		Color ribbon = new Color(0xff, 0xe0, 0x66);

		g.setColor(box);
		g.fillRoundRect(1, 5, 12, 8, 2, 2);
		g.fillRect(0, 3, 14, 3);

		g.setColor(ribbon);
		g.fillRect(6, 3, 2, 10);
		g.fillRect(0, 3, 14, 1);

		g.dispose();
		return new ImageIcon(image);
	}
}
