package com.twitchsidepanel.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.awt.Image;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.imageio.ImageIO;
import javax.swing.ImageIcon;

/**
 * Fetches and caches Twitch chat badge icons (moderator, subscriber, vip, etc.) for a
 * channel. Needs an authenticated Helix API call (Client ID + user OAuth token) - so badge
 * icons only render once the user has logged in. Badge *names* still arrive over IRC even
 * for anonymous connections; this only concerns fetching the icon images to go with them.
 */
public class BadgeIconCache
{
	private static final int TARGET_HEIGHT = 18;
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	private final Map<String, String> iconUrlsBySetVersion = new ConcurrentHashMap<>();
	private final Map<String, ImageIcon> iconCache = new ConcurrentHashMap<>();

	/**
	 * Fetches the global and channel-specific badge sets for {@code channel} and remembers
	 * their icon URLs (the actual images are still fetched lazily, on first use, via
	 * {@link #get}). Blocks on several network calls - call from a background thread, and
	 * expect it to silently do nothing if the token/client ID turn out to be invalid,
	 * since a missing badge icon just falls back to no icon rather than breaking chat.
	 */
	public void loadForChannel(String clientId, String accessToken, String channel)
	{
		try
		{
			String broadcasterId = fetchBroadcasterId(clientId, accessToken, channel);

			loadBadgeSets(clientId, accessToken, "https://api.twitch.tv/helix/chat/badges/global");
			if (broadcasterId != null)
			{
				loadBadgeSets(clientId, accessToken,
					"https://api.twitch.tv/helix/chat/badges?broadcaster_id=" + broadcasterId);
			}
		}
		catch (IOException | InterruptedException | RuntimeException e)
		{
			// Badge icons are a nice-to-have on top of a working chat feed - a failure
			// here (bad token, bad client ID, network hiccup) should never take chat
			// down with it. Messages simply render without badge icons.
		}
	}

	/**
	 * Returns the cached/fetched icon for this badge set+version, or {@code null} if it's
	 * not known (never loaded, or failed to load). Blocks on a network fetch the first
	 * time a given badge is seen - call from a background thread, never the Swing EDT.
	 */
	public ImageIcon get(String setId, String version)
	{
		String key = setId + "/" + version;
		ImageIcon cached = iconCache.get(key);
		if (cached != null)
		{
			return cached;
		}

		String url = iconUrlsBySetVersion.get(key);
		if (url == null)
		{
			return null;
		}

		try
		{
			try (InputStream in = URI.create(url).toURL().openStream())
			{
				BufferedImage image = ImageIO.read(in);
				if (image == null)
				{
					return null;
				}
				int scaledWidth = Math.max(1, Math.round(image.getWidth() * (TARGET_HEIGHT / (float) image.getHeight())));
				Image scaled = image.getScaledInstance(scaledWidth, TARGET_HEIGHT, Image.SCALE_SMOOTH);
				ImageIcon icon = new ImageIcon(scaled);
				iconCache.put(key, icon);
				return icon;
			}
		}
		catch (IOException e)
		{
			return null;
		}
	}

	private String fetchBroadcasterId(String clientId, String accessToken, String channel)
		throws IOException, InterruptedException
	{
		HttpResponse<String> response = get(clientId, accessToken,
			"https://api.twitch.tv/helix/users?login=" + channel);
		if (response.statusCode() != 200)
		{
			return null;
		}
		JsonObject json = new JsonParser().parse(response.body()).getAsJsonObject();
		JsonArray data = json.getAsJsonArray("data");
		if (data == null || data.size() == 0)
		{
			return null;
		}
		return data.get(0).getAsJsonObject().get("id").getAsString();
	}

	private void loadBadgeSets(String clientId, String accessToken, String url) throws IOException, InterruptedException
	{
		HttpResponse<String> response = get(clientId, accessToken, url);
		if (response.statusCode() != 200)
		{
			return;
		}

		JsonObject json = new JsonParser().parse(response.body()).getAsJsonObject();
		JsonArray sets = json.getAsJsonArray("data");
		if (sets == null)
		{
			return;
		}

		for (int i = 0; i < sets.size(); i++)
		{
			JsonObject set = sets.get(i).getAsJsonObject();
			String setId = set.get("set_id").getAsString();
			JsonArray versions = set.getAsJsonArray("versions");
			if (versions == null)
			{
				continue;
			}
			for (int v = 0; v < versions.size(); v++)
			{
				JsonObject version = versions.get(v).getAsJsonObject();
				String versionId = version.get("id").getAsString();
				String imageUrl = version.get("image_url_1x").getAsString();
				iconUrlsBySetVersion.put(setId + "/" + versionId, imageUrl);
			}
		}
	}

	private HttpResponse<String> get(String clientId, String accessToken, String url)
		throws IOException, InterruptedException
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.header("Client-Id", clientId)
			.header("Authorization", "Bearer " + accessToken)
			.GET()
			.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}
}
