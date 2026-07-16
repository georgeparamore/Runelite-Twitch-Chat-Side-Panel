package com.twitchsidepanel.twitch;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.awt.Desktop;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Logs into Twitch using the OAuth 2.0 Device Code Grant flow - the user is shown a short
 * code and a URL, enters it on any device in a browser, and this polls Twitch until it's
 * approved. Chosen over the more common Authorization Code / Implicit flows because those
 * both need either a client secret (server-side apps only) or a localhost redirect
 * listener; device flow needs neither, which suits a desktop client with no backend.
 * <p>
 * Requests {@code chat:read chat:edit} scope - read/send chat as the logged in user.
 */
public class TwitchAuthService
{
	private static final String DEVICE_CODE_URL = "https://id.twitch.tv/oauth2/device";
	private static final String TOKEN_URL = "https://id.twitch.tv/oauth2/token";
	private static final String VALIDATE_URL = "https://id.twitch.tv/oauth2/validate";
	private static final String SCOPES = "chat:read chat:edit";

	private final HttpClient httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
	private final Gson gson;
	private final ScheduledExecutorService executor;
	private final AtomicBoolean cancelled = new AtomicBoolean(false);

	public TwitchAuthService(Gson gson, ScheduledExecutorService executor)
	{
		this.gson = gson;
		this.executor = executor;
	}

	public interface LoginListener
	{
		/**
		 * The user needs to visit {@code verificationUri} and enter {@code userCode}.
		 * Called once per login attempt, before any polling begins.
		 */
		void onCodeReady(String userCode, String verificationUri);

		void onAuthorized(String accessToken, String username);

		/**
		 * Login failed, expired, or was rejected - never called after {@link #cancel()}.
		 */
		void onError(String message);
	}

	/**
	 * Starts a login attempt on the client's shared executor. Only one attempt should be
	 * in flight at a time - call {@link #cancel()} first if a previous attempt is still
	 * polling.
	 */
	public void startLogin(String clientId, LoginListener listener)
	{
		cancelled.set(false);
		executor.execute(() -> runLogin(clientId, listener));
	}

	public void cancel()
	{
		cancelled.set(true);
	}

	private void runLogin(String clientId, LoginListener listener)
	{
		try
		{
			HttpResponse<String> deviceResponse = post(DEVICE_CODE_URL,
				"client_id=" + urlEncode(clientId) + "&scopes=" + urlEncode(SCOPES));

			if (deviceResponse.statusCode() != 200)
			{
				listener.onError("Twitch rejected the login request (HTTP " + deviceResponse.statusCode()
					+ "). Double check your Client ID.");
				return;
			}

			JsonObject device = gson.fromJson(deviceResponse.body(), JsonObject.class);
			String deviceCode = device.get("device_code").getAsString();
			String userCode = device.get("user_code").getAsString();
			String verificationUri = device.get("verification_uri").getAsString();
			int expiresInSeconds = device.get("expires_in").getAsInt();
			int intervalSeconds = device.has("interval") ? device.get("interval").getAsInt() : 5;

			listener.onCodeReady(userCode, verificationUri);
			openInBrowser(verificationUri);

			long deadline = System.currentTimeMillis() + expiresInSeconds * 1000L;
			schedulePoll(clientId, deviceCode, deadline, intervalSeconds, listener, intervalSeconds);
		}
		catch (Exception e)
		{
			// Deliberately broad: this runs on the executor with no uncaught-exception
			// handler, so anything narrower risks the task dying silently on a response
			// shaped differently than expected - "nothing happens" when you click the
			// button, with no error and nothing to debug.
			if (!cancelled.get())
			{
				listener.onError("Login error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
			}
		}
	}

	/**
	 * Best-effort convenience so the user doesn't have to copy the URL themselves - if
	 * this fails (unsupported platform/desktop environment) they can still use the code
	 * and link shown in the panel.
	 */
	private void openInBrowser(String uri)
	{
		try
		{
			if (Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.BROWSE))
			{
				Desktop.getDesktop().browse(URI.create(uri));
			}
		}
		catch (Exception ignored)
		{
			// Not fatal - the panel still shows the code and link to open manually.
		}
	}

	/**
	 * Schedules the next poll attempt {@code delaySeconds} from now on the client's shared
	 * executor, rather than blocking a thread on {@code Thread.sleep} between attempts.
	 */
	private void schedulePoll(String clientId, String deviceCode, long deadlineMillis, int intervalSeconds,
		LoginListener listener, int delaySeconds)
	{
		executor.schedule(() -> pollForToken(clientId, deviceCode, deadlineMillis, intervalSeconds, listener),
			delaySeconds, TimeUnit.SECONDS);
	}

	private void pollForToken(String clientId, String deviceCode, long deadlineMillis, int intervalSeconds,
		LoginListener listener)
	{
		if (cancelled.get())
		{
			return;
		}
		if (System.currentTimeMillis() >= deadlineMillis)
		{
			listener.onError("Login code expired - try again");
			return;
		}

		try
		{
			HttpResponse<String> tokenResponse = post(TOKEN_URL,
				"client_id=" + urlEncode(clientId)
					+ "&device_code=" + urlEncode(deviceCode)
					+ "&grant_type=" + urlEncode("urn:ietf:params:oauth:grant-type:device_code"));

			if (tokenResponse.statusCode() == 200)
			{
				JsonObject token = gson.fromJson(tokenResponse.body(), JsonObject.class);
				String accessToken = token.get("access_token").getAsString();
				String username = validateToken(accessToken);
				if (username == null)
				{
					listener.onError("Logged in, but couldn't verify the account - try again");
					return;
				}
				listener.onAuthorized(accessToken, username);
				return;
			}

			String message = safeMessage(tokenResponse.body());
			if ("authorization_pending".equals(message))
			{
				schedulePoll(clientId, deviceCode, deadlineMillis, intervalSeconds, listener, intervalSeconds);
				return;
			}
			if ("slow_down".equals(message))
			{
				int slowerInterval = intervalSeconds + 5;
				schedulePoll(clientId, deviceCode, deadlineMillis, slowerInterval, listener, slowerInterval);
				return;
			}

			listener.onError("Login failed: " + (message.isEmpty() ? ("HTTP " + tokenResponse.statusCode()) : message));
		}
		catch (Exception e)
		{
			// Same broad-catch reasoning as runLogin() - this callback has no other
			// safety net, so an unexpected failure should surface a visible error rather
			// than silently stop polling.
			if (!cancelled.get())
			{
				listener.onError("Login error: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
			}
		}
	}

	/**
	 * Validates an access token (freshly issued, or one restored from a previous
	 * session) and returns the logged-in Twitch username, or {@code null} if it's
	 * invalid/expired.
	 */
	public String validateToken(String accessToken)
	{
		try
		{
			HttpRequest request = HttpRequest.newBuilder(URI.create(VALIDATE_URL))
				.header("Authorization", "OAuth " + accessToken)
				.GET()
				.build();
			HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
			if (response.statusCode() != 200)
			{
				return null;
			}
			JsonObject json = gson.fromJson(response.body(), JsonObject.class);
			return json.has("login") ? json.get("login").getAsString() : null;
		}
		catch (Exception e)
		{
			// Broad on purpose - callers run this on background threads with no outer
			// safety net, so an unexpected response shape should fall back to "not
			// logged in" rather than kill the thread silently.
			return null;
		}
	}

	private HttpResponse<String> post(String url, String form) throws IOException, InterruptedException
	{
		HttpRequest request = HttpRequest.newBuilder(URI.create(url))
			.header("Content-Type", "application/x-www-form-urlencoded")
			.POST(HttpRequest.BodyPublishers.ofString(form))
			.build();
		return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
	}

	private String safeMessage(String body)
	{
		try
		{
			JsonObject json = gson.fromJson(body, JsonObject.class);
			return json != null && json.has("message") ? json.get("message").getAsString() : "";
		}
		catch (RuntimeException e)
		{
			return "";
		}
	}

	private static String urlEncode(String value)
	{
		return URLEncoder.encode(value, StandardCharsets.UTF_8);
	}
}
