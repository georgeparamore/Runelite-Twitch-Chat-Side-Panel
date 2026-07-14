# Twitch Chat Side Panel (RuneLite plugin)

Shows your Twitch channel's live chat in a RuneLite side panel - Party-Hub style - instead
of the official [Twitch plugin](https://github.com/runelite/runelite/wiki/Twitch)'s
chatbox-PM format.

It only ever connects to **your own** channel, as set in the plugin's config - there's no
free-text field or other way to point it at an arbitrary Twitch channel from the panel.

Reading chat works anonymously with no login at all. Logging in with your Twitch account
additionally lets you send messages from the panel, same as Twitch's own chat box.

## Using it

1. Open the plugin's settings (gear icon in the plugin list) and set **Your Twitch
   channel** to your own Twitch username.
2. Open the plugin's side panel (toolbar icon) and click **Connect** to start reading
   chat - no login needed for this part.
3. To also send messages, register a Twitch application (one-time setup, see below), put
   its Client ID in the plugin settings, then click **Log in with Twitch** in the panel
   and follow the on-screen code/link. Once logged in, a message box appears at the
   bottom of the panel.

If no channel is configured, the panel shows "No channel set" and the Connect button is
disabled until you set one.

### Registering a Twitch app (only needed to send messages)

1. Go to https://dev.twitch.tv/console/apps -> **Register Your Application**.
2. Name: anything (e.g. "My RuneLite Chat").
3. OAuth Redirect URL: any valid URL, e.g. `https://twitchapps.com/tokengen/` - it isn't
   actually used by the login flow this plugin uses, but Twitch requires one to be set.
4. Client Type: **Public**.
5. Save, copy the **Client ID**, paste it into the plugin's **Twitch app Client ID**
   setting.

Config options (gear icon in the plugin list):

- **Twitch app Client ID** - only needed to log in and send messages.
- **Your Twitch channel** - the only channel this plugin will ever connect to.
- **Auto-connect on login** - connects automatically when the client starts.
- **Color usernames** - use each chatter's Twitch name color.
- **Show timestamps** - show `HH:mm` per message.
- **Message history** - how many messages to keep before older ones scroll off.

## How it works

**Chat feed**: Twitch's chat-over-WebSocket gateway (`wss://irc-ws.chat.twitch.tv:443`)
allows read-only access with no OAuth token, as long as the connecting nick follows the
`justinfanNNNNN` convention reserved for anonymous viewers. WebSocket-over-443 was chosen
over raw IRC sockets (`irc.chat.twitch.tv:6697`) specifically because it also works on
networks that only allow outbound HTTPS - hotel wifi, corporate proxies, etc. The plugin
requests the IRCv3 `tags` capability so each message carries the sender's display name,
chat color, badges, and emotes. See `TwitchChatClient`, built on
`java.net.http.HttpClient`'s WebSocket API (JDK 11+, no extra dependency).

**Emotes**: rendered as real inline images, not text - fetched from Twitch's
unauthenticated emote CDN by id (from the `emotes` IRC tag) and downscaled to a fixed
20px height so they sit inline with the message text. See `EmoteImageCache` /
`ChatMessageRowPanel`.

**Login / sending**: uses OAuth 2.0's Device Code Grant (`TwitchAuthService`) - you're
shown a short code and told to enter it at a URL in any browser, and the plugin polls
Twitch until it's approved. This was chosen over the Authorization Code / Implicit flows
because both of those need either a client secret (server apps only) or a localhost
redirect listener; device flow needs neither, which suits a plugin with no backend. Once
authorized, the same WebSocket connection is upgraded to log in as the real account
(`PASS oauth:<token>` / real nick instead of `justinfanNNNNN`), which is what allows
`sendMessage()` to actually post to chat.

**Badge icons**: badge *names* (subscriber, moderator, vip, ...) arrive over IRC for every
message regardless of login state, but turning those into actual icon images needs an
authenticated Helix API call (`GET /helix/chat/badges/global` and
`/helix/chat/badges?broadcaster_id=...`), so icons only render once you're logged in - see
`BadgeIconCache`. A failed/missing badge icon never breaks the message, it just renders
without one.

**Your own sent messages**: Twitch's chat gateway rejects (`NAK`s) the standard IRCv3
`echo-message` capability - confirmed live, not just documented behavior - so a message
you send is never sent back to you over IRC the way it would be on a normal IRC server.
Instead, `TwitchSidePanelPlugin` renders a local copy of what you just sent immediately,
styled with your real name color and badges (captured from the `USERSTATE` message
Twitch sends on an authenticated connection).

## Building / running locally

```
./gradlew runTwitchSidePanel
```

Logs in with your own RuneLite account; enable "Twitch Chat Side Panel" in the plugin list.

## Status

Verified fully live end-to-end against a real RuneLite client (headless, via Xvfb), a
real registered Twitch application, and the developer's real Twitch account:

- Chat feed renders correctly against a live, high-traffic channel - display
  names/colors/timestamps, auto-scroll.
- Emotes render as real inline images (initially discovered they were rendering at their
  native 56x56 size and blowing up row heights - fixed by downscaling to 20px).
- OAuth device-code login completed for real: code issued, approved in a real browser,
  token retrieved, username validated ("Logged in as ...") - not just the error path.
- Token persistence confirmed - restarting the client silently logged back in from the
  stored token with no need to redo the device code flow.
- A real message was sent from the panel to a real live channel and rendered correctly,
  styled with the sender's actual Twitch name color.
- Badge icons render for real - fetched from Twitch's Helix API and displayed next to a
  real message (confirmed with a broadcaster badge).

This live testing caught and fixed three real bugs before they reached anyone:

1. The original raw-IRC-socket transport (`irc.chat.twitch.tv:6697`) had no connect
   timeout, so an unreachable network left the panel stuck on "Connecting..." forever.
   Switched to the WebSocket transport described above, which also fixed this since
   `HttpClient` has a real connect timeout.
2. The message feed's `JScrollPane` viewport was permanently stuck at 0 height - messages
   were being received and parsed correctly (confirmed via raw IRC logging) but never
   became visible. Root cause: `PluginPanel`'s default constructor wraps all content in
   its own internal `DynamicGridLayout` + `JScrollPane`, which silently conflicted with
   this panel's own layout. Fixed by calling `super(false)` to opt out of that wrapping.
3. A message sent from the panel never appeared in the feed even though it sent
   successfully - Twitch's chat gateway actually `NAK`s the IRCv3 `echo-message`
   capability (confirmed live, an initial fix attempt requesting it was silently
   rejected by Twitch), so there is no server echo to rely on. Fixed with a local echo -
   see "Your own sent messages" above.

**Not yet verified**: sub/gift carousel (not started - needs Twitch's EventSub, a
separate real-time system from IRC, and was always the lowest-priority, most speculative
part of the original design), long-running sessions (hours), very high sustained chat
volume, non-Latin channel names, token refresh (the plugin does not yet refresh an
expired token - you'd need to log in again; unclear yet how long a device-flow token
actually lasts before that matters in practice).
