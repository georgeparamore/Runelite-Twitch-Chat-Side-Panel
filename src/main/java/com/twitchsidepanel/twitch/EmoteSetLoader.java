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
 * Fetches the emotes you're actually entitled to use in a channel's chat, for the emote
 * picker button next to the send box - Twitch's global set plus any of that channel's
 * subscriber/follower emotes you actually have access to. Uses Helix's "Get User Emotes"
 * endpoint (needs the {@code user:read:emotes} scope) rather than "Get Channel Emotes",
 * since the latter returns a channel's *entire* emote set regardless of the requester's own
 * subscription status - which meant the picker was offering tier-locked emotes a
 * non-subscriber couldn't actually use, silently sending as plain text when picked (Twitch
 * itself, not just this plugin, only renders an emote you're entitled to). "Get User
 * Emotes" factors real entitlement (subscription tier included) in server-side, so what the
 * picker shows now matches what will actually render.
 * <p>
 * This only resolves emote ids/names; the actual images are fetched separately via
 * {@link EmoteImageCache}, reusing the same cache already used to render emotes inline in
 * chat messages.
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
	 * bad/under-scoped token or network hiccup just means an empty picker, not a broken
	 * chat feed. A token issued before {@code user:read:emotes} was added to the login
	 * scope will fail here until the user logs out and back in.
	 */
	public Result load(String clientId, String accessToken, String channel)
	{
		List<EmoteInfo> channelEmotes = new ArrayList<>();
		List<EmoteInfo> globalEmotes = new ArrayList<>();
		try
		{
			String myUserId = fetchOwnUserId(clientId, accessToken);
			String broadcasterId = fetchBroadcasterId(clientId, accessToken, channel);
			if (myUserId != null)
			{
				fetchEntitledEmotes(clientId, accessToken, myUserId, broadcasterId, channelEmotes, globalEmotes);
			}
		}
		catch (IOException | InterruptedException | RuntimeException e)
		{
			// See javadoc above - never let this take chat down with it.
		}
		return new Result(channelEmotes, globalEmotes);
	}

	private String fetchOwnUserId(String clientId, String accessToken) throws IOException, InterruptedException
	{
		// No login/id params - Helix returns the token owner's own record.
		return firstUserId(get(clientId, accessToken, "https://api.twitch.tv/helix/users"));
	}

	private String fetchBroadcasterId(String clientId, String accessToken, String channel)
		throws IOException, InterruptedException
	{
		return firstUserId(get(clientId, accessToken, "https://api.twitch.tv/helix/users?login=" + channel));
	}

	private String firstUserId(HttpResponse<String> response)
	{
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

	private void fetchEntitledEmotes(String clientId, String accessToken, String myUserId, String broadcasterId,
		List<EmoteInfo> channelEmotes, List<EmoteInfo> globalEmotes) throws IOException, InterruptedException
	{
		String url = "https://api.twitch.tv/helix/chat/emotes/user?user_id=" + myUserId
			+ (broadcasterId != null ? "&broadcaster_id=" + broadcasterId : "");
		HttpResponse<String> response = get(clientId, accessToken, url);
		if (response.statusCode() != 200)
		{
			return;
		}

		JsonObject json = new JsonParser().parse(response.body()).getAsJsonObject();
		JsonArray data = json.getAsJsonArray("data");
		if (data == null)
		{
			return;
		}

		for (int i = 0; i < data.size(); i++)
		{
			JsonObject emote = data.get(i).getAsJsonObject();
			EmoteInfo info = new EmoteInfo(emote.get("id").getAsString(), emote.get("name").getAsString());
			String ownerId = emote.has("owner_id") ? emote.get("owner_id").getAsString() : "";
			if (broadcasterId != null && broadcasterId.equals(ownerId))
			{
				channelEmotes.add(info);
			}
			else
			{
				globalEmotes.add(info);
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
