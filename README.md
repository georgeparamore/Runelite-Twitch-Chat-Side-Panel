# Twitch Chat Side Panel (RuneLite plugin)

Shows Twitch chat in a RuneLite side panel - Party-Hub style - instead of the official
[Twitch plugin](https://github.com/runelite/runelite/wiki/Twitch)'s chatbox-PM format.

It only ever connects to the single channel configured in the plugin's settings - there's
no free-text field or other way to switch to a different channel from the panel itself.

Reading chat works anonymously with no login at all. Logging in with your Twitch account
additionally lets you send messages from the panel, same as Twitch's own chat box - no
setup needed, just click **Log in with Twitch**.

## Using it

1. Open the plugin's settings (gear icon in the plugin list) and set **Twitch channel**
   to whichever channel you want chat for.
2. Open the plugin's side panel (toolbar icon) and click **Connect** to start reading
   chat - no login needed for this part.
3. To also send messages, click **Log in with Twitch** in the panel and follow the
   code/link it shows you (it also tries to open your browser to it automatically). Once
   logged in, a message box appears at the bottom of the panel.

If no channel is configured, the panel shows "No channel set" and the Connect button is
disabled until you set one.

Config options (gear icon in the plugin list):

- **Twitch channel** - the only channel this plugin will ever connect to.
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
shown a short code and told to enter it at a URL in any browser (which the plugin also
tries to open automatically), and it polls Twitch until it's approved. This was chosen
over the Authorization Code / Implicit flows because both of those need either a client
secret (server apps only) or a localhost redirect listener; device flow needs neither,
which suits a plugin with no backend. Once authorized, the same WebSocket connection is
upgraded to log in as the real account (`PASS oauth:<token>` / real nick instead of
`justinfanNNNNN`), which is what allows `sendMessage()` to actually post to chat.

One shared Twitch application (Client ID) is baked into the plugin (`TwitchSidePanelPlugin.CLIENT_ID`)
so every user just logs in with their own account with zero setup - a Client ID isn't a
secret (that's the point of the "Public" client type Twitch app registration uses; no
client secret is involved anywhere in this flow), it just identifies "which app is
asking." Each user's login produces their own personal token; nothing is shared between
users except this one identifier.

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

**Sub/gift carousel**: turned out not to need Twitch's EventSub (a separate real-time
system) at all - sub, resub, and gift-sub events arrive as `USERNOTICE` messages on the
same IRC connection already used for chat, for both anonymous and authenticated
connections. `TwitchChatClient.parseUserNotice()` turns those into a `TwitchSubEvent`;
`SubGiftCarouselPanel` shows the most recent ones as a horizontally-scrolling strip of
chips above the message feed, hidden until the first event of a session arrives.

## Building / running locally

```
./gradlew runTwitchSidePanel
```

Logs in with your own RuneLite account; enable "Twitch Chat Side Panel" in the plugin list.

## Status

Verified live end-to-end against a real RuneLite client (both headless/Xvfb testing and
the maintainer's real Mac), a real registered Twitch application, and a real Twitch
account:

- Chat feed renders correctly against a live channel - display names/colors/timestamps.
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

This live testing (including on a real Mac, not just headless sandbox testing) caught and
fixed six real bugs before they reached most users:

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
4. On a quiet channel with only a couple of messages, rows rendered with huge vertical
   gaps between them instead of stacking normally - a plain `JPanel` inside a
   `BoxLayout` column defaults to an unbounded maximum height (its layout, `BorderLayout`,
   isn't a `LayoutManager2` and never supplies one), so `BoxLayout` was dumping all the
   scroll pane's empty leftover space into the message rows themselves. Only showed up on
   a channel quiet enough to have empty space to distribute - a busy test channel always
   had enough messages to fill the panel, hiding the bug entirely during earlier testing.
   Fixed by bounding each row's maximum height to its live preferred height
   (`ChatMessageRowPanel.getMaximumSize()`) plus a trailing glue component in the message
   list so leftover space collects at the bottom instead.
5. Login used to require pasting a personally-registered Client ID into config first,
   and clicking "Log in with Twitch" with that field empty produced a real but easy-to-miss
   error label - functionally "nothing happens" from a user's perspective. Fixed by baking
   in one shared Client ID (see "Login / sending" above) so there's no setup step at all,
   and by widening exception handling in the login flow so any unexpected failure (not
   just network errors) surfaces a visible message instead of the background thread dying
   silently.
6. Long chat messages were clipped at the panel's right edge instead of wrapping to a
   second line. The message feed's `JScrollPane` disables the horizontal scrollbar, and
   the panel holding the rows was a plain `JPanel`, which doesn't implement `Scrollable` -
   so the viewport never forced its width to match the visible area, leaving each row's
   `JTextPane` free to grow as wide as its longest line and get clipped rather than wrap.
   Fixed by having that panel implement `Scrollable` with
   `getScrollableTracksViewportWidth()` returning `true`, pinning its width to the
   viewport and giving each row a real bounded width to wrap against.

The sub/gift carousel's `USERNOTICE` parsing (`parseUserNotice`) is covered by 7 unit
tests (`TwitchChatClientTest`) built from Twitch's documented tag format, since there's
no practical way to trigger a real sub/gift event live without spending real money -
sub/resub, single gift, gift bomb, anonymous gift bomb, and two "ignore this, it's not a
type we show" cases (raids, tag-less lines) are all covered and passing. The UI rendering
(chip layout, icon, auto-scroll-to-newest) was visually verified with synthetic events
injected locally, then that test-only code was removed before committing.

**Not yet verified**: an actual real sub/gift event end-to-end (parsing logic is unit
tested against Twitch's documented format; UI rendering was checked with synthetic data;
the two have not been proven together against a real Twitch event). Also: long-running
sessions (hours), very high sustained chat volume, non-Latin channel names, token refresh
(the plugin does not yet refresh an expired token - you'd need to log in again; unclear
yet how long a device-flow token actually lasts before that matters in practice).
