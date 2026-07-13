# Twitch Chat Side Panel (RuneLite plugin)

Shows your Twitch channel's live chat in a RuneLite side panel - Party-Hub style - instead
of the official [Twitch plugin](https://github.com/runelite/runelite/wiki/Twitch)'s
chatbox-PM format.

Read-only: it connects to Twitch chat anonymously (no OAuth token, no login) and only
displays messages. It never sends chat messages or otherwise acts on your behalf. It also
only ever connects to **your own** channel, as set in the plugin's config - there's no
free-text field or other way to point it at an arbitrary Twitch channel from the panel.

## Using it

1. Open the plugin's settings (gear icon in the plugin list) and set **Your Twitch
   channel** to your own Twitch username.
2. Open the plugin's side panel (toolbar icon) and click **Connect**.
3. Chat streams into the panel as a scrolling feed with colored usernames and timestamps.

If no channel is configured, the panel shows "No channel set" and the Connect button is
disabled until you set one.

Config options (gear icon in the plugin list):

- **Your Twitch channel** - the only channel this plugin will ever connect to.
- **Auto-connect on login** - connects automatically when the client starts.
- **Color usernames** - use each chatter's Twitch name color.
- **Show timestamps** - show `HH:mm` per message.
- **Message history** - how many messages to keep before older ones scroll off.

## How the connection works

Twitch's chat-over-WebSocket gateway (`wss://irc-ws.chat.twitch.tv:443`) allows read-only
access with no OAuth token, as long as the connecting nick follows the `justinfanNNNNN`
convention reserved for anonymous viewers. WebSocket-over-443 was chosen over raw IRC
sockets (`irc.chat.twitch.tv:6697`) specifically because it also works on networks that
only allow outbound HTTPS - hotel wifi, corporate proxies, etc. This plugin requests the
IRCv3 `tags` capability so each message carries the sender's display name and chat color,
joins the configured channel, and renders incoming `PRIVMSG` lines. See
`TwitchChatClient`, built on `java.net.http.HttpClient`'s WebSocket API (JDK 11+, no
extra dependency).

## Building / running locally

```
./gradlew runTwitchSidePanel
```

Logs in with your own RuneLite account; enable "Twitch Chat Side Panel" in the plugin list.

## Status

Verified live end-to-end against a real RuneLite client (headless, via Xvfb) connected to
an actual live, high-traffic Twitch channel: the panel renders correctly, live chat
messages stream in with correct display names/colors/timestamps, and auto-scroll works.

This live test caught and fixed two real bugs before they reached anyone:

1. The original raw-IRC-socket transport (`irc.chat.twitch.tv:6697`) had no connect
   timeout, so an unreachable network left the panel stuck on "Connecting..." forever.
   Switched to a WebSocket transport over 443 (see above), which also fixed this since
   `HttpClient` has a real connect timeout.
2. The message feed's `JScrollPane` viewport was permanently stuck at 0 height - messages
   were being received and parsed correctly (confirmed via raw IRC logging) but never
   became visible. Root cause: `PluginPanel`'s default constructor wraps all content in
   its own internal `DynamicGridLayout` + `JScrollPane`, which silently conflicted with
   this panel's own layout. Fixed by calling `super(false)` to opt out of that wrapping.

**Not yet verified**: long-running sessions (hours), very high sustained chat volume,
emote-rendering (emotes currently show as their text name, e.g. `Kappa`, not an image),
non-Latin channel names.
