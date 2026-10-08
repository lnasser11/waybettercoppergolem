package io.github.lnasser11.waybettercoppergolem.client;

/** Layout constants shared by the mod's screens, so they line up with each other. */
public final class Ui {
	/** Width of one column of controls. */
	public static final int COLUMN_WIDTH = 150;
	/** Space between widgets. */
	public static final int GAP = 4;
	public static final int BUTTON_HEIGHT = 20;
	/** One button row including its gap. */
	public static final int ROW = BUTTON_HEIGHT + GAP;
	/** Two columns side by side. */
	public static final int PANEL_WIDTH = 2 * COLUMN_WIDTH + GAP;
	/** Space reserved for a 16px item icon to the left of a button. */
	public static final int ICON_SLOT = 20;

	public static final int TEXT = 0xFFFFFFFF;
	public static final int TEXT_MUTED = 0xFFAAAAAA;
	public static final int TEXT_HINT = 0xFF888888;

	private Ui() {
	}
}
