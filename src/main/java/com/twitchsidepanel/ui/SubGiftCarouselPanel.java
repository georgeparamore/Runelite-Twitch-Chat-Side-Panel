package com.twitchsidepanel.ui;

import com.twitchsidepanel.twitch.TwitchSubEvent;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.SwingUtilities;

/**
 * Horizontally-scrolling strip of recent sub/gift-sub events, Twitch-mockup style. Hidden
 * until the first event of a connection arrives, so channels with no sub activity don't
 * carry an empty row of chrome.
 */
public class SubGiftCarouselPanel extends JPanel
{
	private static final Color BACKGROUND = new Color(0x0e, 0x0e, 0x12);
	private static final Color CHIP_BACKGROUND = new Color(0x26, 0x1f, 0x33);
	private static final int MAX_CHIPS = 30;

	private final JPanel chipsRow;
	private final JScrollPane scrollPane;

	public SubGiftCarouselPanel()
	{
		setLayout(new BorderLayout());
		setBackground(BACKGROUND);
		setBorder(BorderFactory.createEmptyBorder(0, 10, 8, 10));

		chipsRow = new JPanel();
		chipsRow.setLayout(new BoxLayout(chipsRow, BoxLayout.X_AXIS));
		chipsRow.setBackground(BACKGROUND);

		scrollPane = new JScrollPane(chipsRow);
		scrollPane.setBorder(BorderFactory.createEmptyBorder());
		scrollPane.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_NEVER);
		scrollPane.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.getViewport().setBackground(BACKGROUND);
		scrollPane.setPreferredSize(new Dimension(Integer.MAX_VALUE, 34));
		scrollPane.setMaximumSize(new Dimension(Integer.MAX_VALUE, 34));

		add(scrollPane, BorderLayout.CENTER);
		setVisible(false);
	}

	public void addEvent(TwitchSubEvent event)
	{
		SwingUtilities.invokeLater(() ->
		{
			chipsRow.add(buildChip(event));

			while (chipsRow.getComponentCount() > MAX_CHIPS)
			{
				chipsRow.remove(0);
			}

			setVisible(true);
			chipsRow.revalidate();
			chipsRow.repaint();

			// Scroll to show the newest (rightmost) chip.
			SwingUtilities.invokeLater(() ->
				scrollPane.getHorizontalScrollBar().setValue(scrollPane.getHorizontalScrollBar().getMaximum()));
		});
	}

	private JPanel buildChip(TwitchSubEvent event)
	{
		RoundedPanel chip = new RoundedPanel(14, CHIP_BACKGROUND);
		chip.setLayout(new BoxLayout(chip, BoxLayout.X_AXIS));
		chip.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
		chip.setAlignmentY(CENTER_ALIGNMENT);

		JLabel icon = new JLabel(GiftIcon.get());
		icon.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 4));

		String text = event.type == TwitchSubEvent.Type.GIFT_BOMB && event.count > 1
			? event.displayName + " x" + event.count
			: event.displayName;

		JLabel nameLabel = new JLabel(text);
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setFont(nameLabel.getFont().deriveFont(Font.BOLD, 11f));

		chip.add(icon);
		chip.add(nameLabel);

		// A wrapper (rather than a border/margin directly on the chip) keeps the outer
		// gap between chips from being painted over by RoundedPanel's own rounded
		// background fill, which covers its full bounds.
		JPanel wrapper = new JPanel(new BorderLayout());
		wrapper.setOpaque(false);
		wrapper.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
		wrapper.add(chip, BorderLayout.CENTER);
		return wrapper;
	}
}
