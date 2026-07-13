# Twitch Chat Side Panel (RuneLite plugin)

Shows your Twitch channel's live chat in a RuneLite side panel - Party-Hub style - instead
of the official [Twitch plugin](https://github.com/runelite/runelite/wiki/Twitch)'s
chatbox-PM format.

Read-only: it connects to Twitch chat anonymously (no OAuth token, no login) and only
displays messages. It never sends chat messages or otherwise acts on your behalf.

## Using it

1. Open the plugin's side panel (toolbar icon).
2. Type a Twitch channel name and click **Connect**.
3. Chat streams into the panel as a scrolling feed with colored usernames and timestamps.

Config options (gear icon in the plugin list):

- **Twitch channel** - default channel to pre-fill.
- **Auto-connect on login** - connects automatically when the client starts.
- **Color usernames** - use each chatter's Twitch name color.
- **Show timestamps** - show `HH:mm` per message.
- **Message history** - how many messages to keep before older ones scroll off.

## How the connection works

Twitch's IRC gateway (`irc.chat.twitch.tv:6697`, TLS) allows read-only access with no
OAuth token, as long as the connecting nick follows the `justinfanNNNNN` convention
reserved for anonymous viewers. This plugin requests the IRCv3 `tags` capability so each
message carries the sender's display name and chat color, joins the configured channel,
and renders incoming `PRIVMSG` lines. See `TwitchChatClient`.

## Building / running locally

```
./gradlew runTwitchSidePanel
```

Logs in with your own RuneLite account; enable "Twitch Chat Side Panel" in the plugin list.

## Status

v1. Verified against a real RuneLite client running headlessly (Xvfb): the plugin
registers cleanly, the side panel opens and renders (title, channel field, Connect
button, status line, message feed), and the connect flow works end-to-end up to the
network boundary - clicking Connect drives the background IRC client through socket
setup and the error path correctly (confirmed via a forced timeout, since that sandbox
had no outbound TCP access to Twitch). That test also caught and fixed a real bug: the
original `SSLSocketFactory.createSocket(host, port)` call had no connect timeout, so an
unreachable network would leave the panel stuck on "Connecting..." forever - it now
connects a plain `Socket` with an explicit 10s timeout first, then upgrades to TLS.

**Not yet verified**: an actual successful connection to live Twitch chat and message
rendering (needs a network that can reach `irc.chat.twitch.tv:6697`), long-running
sessions, very high chat volume, emote-heavy messages, channel names with unusual
casing.
