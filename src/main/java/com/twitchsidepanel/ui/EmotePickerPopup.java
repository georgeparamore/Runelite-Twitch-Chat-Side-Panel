package com.twitchsidepanel.ui;

import com.twitchsidepanel.twitch.EmoteSetLoader;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.Graphics;
import java.awt.GridLayout;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;

/**
 * The popup grid shown when {@link EmoteButton} is clicked - a scrollable grid of every
 * emote available in the current channel (its own subscriber/follower emotes plus Twitch's
 * global set), split into sections the same way Twitch's own picker does. Clicking an emote
 * inserts its text code into the message field via {@code onEmoteChosen} rather than
 * inserting it directly, so this class stays free of any knowledge of the send box.
 * <p>
 * Built on {@link JPopupMenu} so it auto-dismisses on an outside click or Escape like any
 * other popup, but holds one plain component instead of menu items - a JButton inside
 * doesn't trigger JPopupMenu's own dismiss-on-selection behavior, so the picker stays open
 * across multiple picks, letting you add more than one emote per message like Twitch's does.
 */
final class EmotePickerPopup
{
	private static final Color BACKGROUND = new Color(0x17, 0x17, 0x1d);
	private static final Color HOVER = new Color(0x26, 0x26, 0x2e);
	private static final Color BORDER = new Color(0x33, 0x33, 0x3d);
	private static final Color MUTED_TEXT = new Color(0x8a, 0x8a, 0x95);
	private static final int COLUMNS = 5;
	private static final int CELL = 32;
	private static final int MAX_HEIGHT = 240;

	private EmotePickerPopup()
	{
	}

	/**
	 * Builds and shows the popup, returning it so the caller can track/toggle its
	 * visibility (e.g. a second click on the button that opened it should close it again
	 * rather than reopening a new one).
	 */
	static JPopupMenu show(JComponent invoker, EmoteSetLoader.Result emotes, Map<String, ImageIcon> icons,
		Consumer<String> onEmoteChosen)
	{
		JPopupMenu popup = new JPopupMenu();
		popup.setBackground(BACKGROUND);
		popup.setBorder(BorderFactory.createLineBorder(BORDER));
		popup.setLayout(new BorderLayout());

		boolean anyEmotes = !emotes.channelEmotes.isEmpty() || !emotes.globalEmotes.isEmpty();
		if (!anyEmotes)
		{
			// A successful fetch always includes at least Twitch's built-in global
			// emotes, so a genuinely empty result here almost always means the fetch
			// itself failed - most commonly a token issued before the picker's
			// user:read:emotes scope was added, which needs a fresh login to fix.
			JLabel empty = new JLabel("<html><body style='width:150px'>No emotes available."
				+ " If you logged in before an update, try logging out and back in.</body></html>");
			empty.setForeground(MUTED_TEXT);
			empty.setBorder(BorderFactory.createEmptyBorder(12, 14, 12, 14));
			popup.add(empty, BorderLayout.CENTER);
		}
		else
		{
			JPanel content = new JPanel();
			content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
			content.setBackground(BACKGROUND);
			content.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

			addSection(content, "CHANNEL EMOTES", emotes.channelEmotes, icons, popup, onEmoteChosen);
			addSection(content, "GLOBAL EMOTES", emotes.globalEmotes, icons, popup, onEmoteChosen);

			JScrollPane scrollPane = new JScrollPane(content);
			scrollPane.setBorder(BorderFactory.createEmptyBorder());
			scrollPane.getViewport().setBackground(BACKGROUND);
			scrollPane.setPreferredSize(new Dimension(COLUMNS * (CELL + 4) + 16,
				Math.min(MAX_HEIGHT, content.getPreferredSize().height + 10)));
			popup.add(scrollPane, BorderLayout.CENTER);
		}

		popup.show(invoker, 0, -popup.getPreferredSize().height - 4);
		return popup;
	}

	private static void addSection(JPanel content, String title, List<EmoteSetLoader.EmoteInfo> section,
		Map<String, ImageIcon> icons, JPopupMenu popup, Consumer<String> onEmoteChosen)
	{
		if (section.isEmpty())
		{
			return;
		}

		JLabel header = new JLabel(title);
		header.setForeground(MUTED_TEXT);
		header.setFont(header.getFont().deriveFont(Font.BOLD, 10f));
		header.setAlignmentX(JComponent.LEFT_ALIGNMENT);
		header.setBorder(BorderFactory.createEmptyBorder(4, 2, 4, 0));
		content.add(header);

		JPanel grid = new JPanel(new GridLayout(0, COLUMNS, 4, 4));
		grid.setBackground(BACKGROUND);
		grid.setAlignmentX(JComponent.LEFT_ALIGNMENT);

		for (EmoteSetLoader.EmoteInfo emote : section)
		{
			ImageIcon icon = icons.get(emote.id);
			if (icon == null)
			{
				// Image fetch failed or hasn't resolved - skip rather than show a blank
				// cell, same tradeoff EmoteImageCache already makes for chat messages.
				continue;
			}
			grid.add(new EmoteCell(emote.name, icon, () ->
			{
				onEmoteChosen.accept(emote.name);
				popup.setVisible(true);
			}));
		}

		content.add(grid);
	}

	/**
	 * A single emote cell in the grid. Paints its own background instead of relying on
	 * JButton's default icon rendering with a transparent content area - the same
	 * RuneLite LAF quirk noted on {@link PillButton} and {@link EmoteButton}.
	 */
	private static class EmoteCell extends JButton
	{
		private final ImageIcon icon;

		EmoteCell(String tooltip, ImageIcon icon, Runnable onClick)
		{
			this.icon = icon;
			setToolTipText(tooltip);
			setOpaque(true);
			setContentAreaFilled(true);
			setFocusPainted(false);
			setBorderPainted(false);
			setPreferredSize(new Dimension(CELL, CELL));
			setBackground(BACKGROUND);
			addActionListener(e -> onClick.run());
		}

		@Override
		protected void paintComponent(Graphics g)
		{
			g.setColor(getModel().isRollover() ? HOVER : BACKGROUND);
			g.fillRect(0, 0, getWidth(), getHeight());
			int x = (getWidth() - icon.getIconWidth()) / 2;
			int y = (getHeight() - icon.getIconHeight()) / 2;
			icon.paintIcon(this, g, x, y);
		}
	}
}
