package com.twitchsidepanel.twitch;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Fetches the emotes available to use in a channel's chat - Twitch's global emote set plus
 * that channel's own subscriber/follower emotes - for the emote picker button next to the
 * send box. Needs an authenticated Helix API call (Client ID + user OAuth token), same as
 * badge icons, so the picker only works once logged in. This only resolves emote ids/names;
 * the actual images are fetched separately via {@link EmoteImageCache}, reusing the same
 * cache already used to render emotes inline in chat messages.
 */
public class EmoteSetLoader
{
	private static final Duration TIMEOUT = Duration.ofSeconds(10);

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	public static class EmoteInfo
	{
		public final String id;
		public final String name;

		public EmoteInfo(String id, String name)
		{
			this.id = id;
			this.name = name;
		}
	}

	public static class Result
	{
		public final List<EmoteInfo> channelEmotes;
		public final List<EmoteInfo> globalEmotes;

		public Result(List<EmoteInfo> channelEmotes, List<EmoteInfo> globalEmotes)
		{
			this.channelEmotes = channelEmotes;
			this.globalEmotes = globalEmotes;
		}
	}

	/**
	 * Blocks on several network calls - call from a background thread, never the Swing EDT.
	 * Returns empty lists (never null) on any failure, the same tradeoff as badge icons: a
	 * bad token or network hiccup just means an empty picker, not a broken chat feed.
	 */
	public Result load(String clientId, String accessToken, String channel)
	{
		List<EmoteInfo> channelEmotes = new ArrayList<>();
		List<EmoteInfo> globalEmotes = new ArrayList<>();
		try
		{
			String broadcasterId = fetchBroadcasterId(clientId, accessToken, channel);
			if (broadcasterId != null)
			{
				channelEmotes.addAll(fetchEmotes(clientId, accessToken,
					"https://api.twitch.tv/helix/chat/emotes?broadcaster_id=" + broadcasterId));
			}
			globalEmotes.addAll(fetchEmotes(clientId, accessToken, "https://api.twitch.tv/helix/chat/emotes/global"));
		}
		catch (IOException | InterruptedException | RuntimeException e)
		{
			// See javadoc above - never let this take chat down with it.
		}
		return new Result(channelEmotes, globalEmotes);
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

	private List<EmoteInfo> fetchEmotes(String clientId, String accessToken, String url)
		throws IOException, InterruptedException
	{
		List<EmoteInfo> result = new ArrayList<>();
		HttpResponse<String> response = get(clientId, accessToken, url);
		if (response.statusCode() != 200)
		{
			return result;
		}
		JsonObject json = new JsonParser().parse(response.body()).getAsJsonObject();
		JsonArray data = json.getAsJsonArray("data");
		if (data == null)
		{
			return result;
		}
		for (int i = 0; i < data.size(); i++)
		{
			JsonObject emote = data.get(i).getAsJsonObject();
			result.add(new EmoteInfo(emote.get("id").getAsString(), emote.get("name").getAsString()));
		}
		return result;
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
