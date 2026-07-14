package com.twitchsidepanel.twitch;

/**
 * A subscription or gift-sub event parsed from a Twitch IRC {@code USERNOTICE}. Twitch
 * sends these on the same chat connection as regular messages - no separate EventSub
 * infrastructure needed to know about them.
 */
public class TwitchSubEvent
{
	public enum Type
	{
		/** A new or repeat ("resub") subscription, paid for by the subscriber themselves. */
		SUB,
		/** A single gift sub - {@link #displayName} is the recipient. */
		GIFT_SUB,
		/** A batch of gift subs from one gifter - {@link #displayName} is the gifter. */
		GIFT_BOMB
	}

	public final Type type;
	public final String displayName;
	/** Number of subs gifted at once, for {@link Type#GIFT_BOMB}; meaningless otherwise. */
	public final int count;
	public final long receivedAtMillis;

	public TwitchSubEvent(Type type, String displayName, int count, long receivedAtMillis)
	{
		this.type = type;
		this.displayName = displayName;
		this.count = count;
		this.receivedAtMillis = receivedAtMillis;
	}
}
