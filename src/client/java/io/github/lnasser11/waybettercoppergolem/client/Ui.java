package io.github.lnasser11.waybettercoppergolem.client;

/**
 * The mod's screen design, in one place.
 *
 * <p><b>Brief.</b> The screens open from chests and from the hotbar HUD, so
 * they should read as part of the game, not as a web page: a dark
 * translucent panel like vanilla's modern list screens, vanilla buttons
 * for actions people already know, and flat list rows (icon, name,
 * detail) for everything that is a list. Density stays at vanilla's 20px
 * controls. Color is used for state only: white for what the player set,
 * blue for what the mod derived, gold for something to look at, red for
 * something stuck, green for on.
 */
public final class Ui {
	// ---- layout
	/** Width of one column of controls. */
	public static final int COLUMN_WIDTH = 150;
	/** Space between widgets. */
	public static final int GAP = 4;
	public static final int BUTTON_HEIGHT = 20;
	/** One button row including its gap. */
	public static final int ROW = BUTTON_HEIGHT + GAP;
	/** Two columns side by side. */
	public static final int PANEL_WIDTH = 2 * COLUMN_WIDTH + GAP;
	/** Space reserved for a 16px item icon to the left of a row's text. */
	public static final int ICON_SLOT = 20;
	/** Padding between a panel's border and its content. */
	public static final int PADDING = 8;
	/** Three columns side by side (the picker on wide enough screens). */
	public static final int WIDE_PANEL_WIDTH = 3 * COLUMN_WIDTH + 2 * GAP;
	/** The smallest GUI the layouts are designed for: 1080p at "Auto" scale is 480 × 270. */
	public static final int MIN_GUI_HEIGHT = 270;
	/** Height of a panel header: title, subtitle, separator. */
	public static final int HEADER_HEIGHT = 34;
	/** Height of a section label line above a group of controls. */
	public static final int SECTION_LABEL = 12;

	// ---- text
	public static final int TEXT = 0xFFFFFFFF;
	public static final int TEXT_MUTED = 0xFFB0B0B0;
	public static final int TEXT_HINT = 0xFF8A8A8A;
	public static final int TEXT_DISABLED = 0xFF6A6A6A;

	// ---- state
	/** Something the player set explicitly. */
	public static final int EXPLICIT = TEXT;
	/** Something the mod derived (auto labels). */
	public static final int AUTO = 0xFF9FC5E8;
	/** Worth a look: unlabeled, misplaced, duplicate. */
	public static final int ATTENTION = 0xFFFFC66D;
	/** Stuck or refused: nowhere to go, not allowed. */
	public static final int PROBLEM = 0xFFFF7B72;
	/** Enabled, added, fine. */
	public static final int OK = 0xFF8CE99A;

	// ---- surfaces
	public static final int PANEL_BACKGROUND = 0xE0121212;
	public static final int PANEL_BORDER = 0xFF4A4A4A;
	public static final int PANEL_INNER_LINE = 0x40FFFFFF;
	public static final int SEPARATOR = 0xFF353535;
	public static final int ROW_BACKGROUND = 0x30000000;
	public static final int ROW_HOVER = 0x38FFFFFF;
	public static final int ROW_DISABLED = 0x18000000;
	public static final int HUD_BACKGROUND = 0xB0121212;

	private Ui() {
	}

	/**
	 * How many list rows fit on this screen next to {@code fixedContentHeight}
	 * of other content (labels, buttons), inside a panel with header and
	 * padding, leaving a small margin. Clamped to [{@code min}, {@code max}].
	 */
	public static int rowsThatFit(int screenHeight, int fixedContentHeight, int min, int max) {
		int available = screenHeight - 8 - HEADER_HEIGHT - 2 * PADDING - fixedContentHeight;
		return Math.clamp(available / ROW, min, max);
	}

	/** Whether three columns fit side by side on this screen. */
	public static boolean wide(int screenWidth) {
		return screenWidth >= WIDE_PANEL_WIDTH + 2 * PADDING + 4;
	}
}
