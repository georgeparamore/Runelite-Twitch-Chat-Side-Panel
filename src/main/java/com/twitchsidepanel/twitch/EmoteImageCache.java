package com.twitchsidepanel.twitch;

import java.awt.Image;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/**
 * Fetches and caches Twitch emote images by emote id. The CDN is unauthenticated - no
 * client ID or token needed, unlike badge icons. Always requests the "static" format so
 * animated emotes still decode as a single frame; Java's built-in ImageIO doesn't reliably
 * handle animated PNG/WebP, and a still image is a reasonable tradeoff for chat legibility.
 */
public class EmoteImageCache
{
	private static final String URL_TEMPLATE = "https://static-cdn.jtvnw.net/emoticons/v2/%s/static/dark/2.0";
	// Twitch's CDN sizes are discrete buckets (~28/56/112px) that vary per emote, so
	// rather than pick a bucket and hope it's close enough, always downscale to a fixed
	// height matching the chat font instead of letting emotes tower over the message
	// text (56px emotes were blowing up row heights before this was added).
	private static final int TARGET_HEIGHT = 20;

	private final Map<String, ImageIcon> cache = new ConcurrentHashMap<>();
	private final Map<String, Boolean> failed = new ConcurrentHashMap<>();

	/**
	 * Returns the cached/fetched icon for this emote id, or {@code null} if it couldn't be
	 * loaded. Blocks on a network fetch the first time a given id is seen - call this from
	 * a background thread, never the Swing EDT.
	 */
	public ImageIcon get(String emoteId)
	{
		if (failed.containsKey(emoteId))
		{
			return null;
		}

		ImageIcon cached = cache.get(emoteId);
		if (cached != null)
		{
			return cached;
		}

		try
		{
			URL url = URI.create(String.format(URL_TEMPLATE, emoteId)).toURL();
			try (InputStream in = url.openStream())
			{
				java.awt.image.BufferedImage image = ImageIO.read(in);
				if (image == null)
				{
					failed.put(emoteId, Boolean.TRUE);
					return null;
				}

				int scaledWidth = Math.max(1, Math.round(image.getWidth() * (TARGET_HEIGHT / (float) image.getHeight())));
				Image scaled = image.getScaledInstance(scaledWidth, TARGET_HEIGHT, Image.SCALE_SMOOTH);

				ImageIcon icon = new ImageIcon(scaled);
				cache.put(emoteId, icon);
				return icon;
			}
		}
		catch (IOException e)
		{
			failed.put(emoteId, Boolean.TRUE);
			return null;
		}
	}
}
