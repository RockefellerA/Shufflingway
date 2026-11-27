package shufflingway;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
import java.awt.EventQueue;
import java.awt.Graphics2D;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.RenderingHints;
import java.awt.event.InputEvent;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.awt.image.ConvolveOp;
import java.awt.image.Kernel;
import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.FutureTask;
import java.util.function.BooleanSupplier;

import javax.imageio.ImageIO;
import javax.swing.JFrame;
import javax.swing.JRootPane;
import javax.swing.text.StyledDocument;

import com.formdev.flatlaf.ui.FlatTitlePane;

import shufflingway.audio.SoundEffects;
import shufflingway.graphics.FieldSlotLabel;

/**
 * Renders the README's screenshots from the current build, once in each of the Paladin and Dark
 * Knight themes:
 *
 * <ul>
 *   <li>{@code deck-manager-<theme>.png}: the Deck Manager open over an idle main window, the saved
 *       deck "Summons" selected in My Decks and loaded into Deck Contents, and Auron 1-001H selected
 *       in the Card Browser and shown in the preview.
 *   <li>{@code game-board-<theme>.png}: a game on turn 3, P1 in the Attack Phase with an attacker
 *       chosen and the Attack button live, over the board the README's screenshot showed (see
 *       {@link #stageBoard} and {@link #ATTACKERS}).
 * </ul>
 *
 * <pre>
 *   mvn -q test-compile
 *   mvn exec:exec -Dexec.classpathScope=test -Dexec.executable=java \
 *       -Dexec.args="--enable-native-access=ALL-UNNAMED -cp %classpath shufflingway.ReadmeScreenshots"
 *   ... shufflingway.ReadmeScreenshots &lt;outDir&gt; [PALADIN|DARK_KNIGHT ...]
 * </pre>
 *
 * <p>The output directory defaults to {@code target/screenshots}. Naming themes renders only
 * those.
 *
 * <p>Nothing is shown on screen. Each window is laid out at the size the app opens at on this
 * machine (UiScale reads the monitor and {@code settings.ini}) and painted into an image at the
 * display's scale. FlatLaf draws the title bar inside the window, so it is in the picture. The
 * Deck Manager is composited over the main window with the rounded corners and soft shadow Windows
 * gives a window. The player's own settings apply: card back, board colours, side panel side and
 * width, the not-ready marker. Nothing is written back to them.
 *
 * <p>The board is staged rather than played. Cards are placed through the same calls the Debug
 * menu's spawns use, with their enters-the-field abilities silenced. The turn is advanced in
 * GameState alone, so that P1's turn 1 Forwards are ready by turn 3 and the ones placed on turn 3
 * are not. The last steps are played for real: Next is pressed in Main Phase 1 and the attacker's
 * card is clicked, so the Attack Phase, the attacker glows, the raised attacker and the combat
 * buttons are whatever the game shows for them today. The log is a script up to that point (see
 * {@link #LOG}), and the game writes the rest.
 *
 * <p><b>Not a test.</b> No {@code Test} suffix, so JUnit never picks it up. Like
 * {@link CardProbe}, it lives under {@code src/test/java} so a change to anything it calls breaks
 * the build rather than this tool. It reads the card database and the saved decks, and fails with
 * a message if "Summons" is not among them.
 */
public final class ReadmeScreenshots {

	private ReadmeScreenshots() {}

	// =============================================================================================
	// The two scenes
	// =============================================================================================

	/** The saved deck selected in the Deck Manager shot. */
	private static final String DECK_NAME = "Summons";
	/** The card selected in that shot's Card Browser and shown in its preview: Auron. */
	private static final String BROWSER_CARD = "1-001H";

	// P1: a Water/Fire deck on its third turn.
	/** On the field since turn 1, so ready to attack: Charlotte, Leo. */
	private static final String[] P1_READY_FORWARDS = {"27-128S", "22-123R"};
	/** Played this turn and without Haste, so not ready: Ward, Tidus. */
	private static final String[] P1_NEW_FORWARDS = {"21-001R", "1-163L"};
	/** Played this turn, and a Backup enters dull: Geomancer. */
	private static final String[] P1_BACKUPS = {"1-169C"};
	/** Alphinaud. */
	private static final String[] P1_HAND = {"20-106R"};
	/** Bottom to top, discarded for Ward's CP: Zack, Wuk Lamat. */
	private static final String[] P1_BREAK_ZONE = {"27-123S", "27-122S"};
	private static final String[] P1_LB_DECK = {"22-112R", "25-106R", "25-107H", "23-128R", "24-120R", "25-116H"};
	/** What P1's main deck is dealt from, round robin. It is face down, so only its count shows. */
	private static final String[] P1_DECK_CARDS = {"27-129S", "27-128S", "27-122S", "27-123S", "21-001R", "1-163L",
			"20-106R", "1-169C"};
	private static final int P1_DECK_SIZE = 41;

	// P2: an Earth/Lightning deck that has had one turn.
	/** Played on turn 2: Maat, Cloud. */
	private static final String[] P2_FORWARDS = {"22-119R", "27-124S"};
	/** The damage Maat took from Ward's auto-ability, of his 8000 power. */
	private static final int MAAT_DAMAGE = 7000;
	/** Played on turn 2, so still dull: Red Mage. */
	private static final String[] P2_BACKUPS = {"1-121C"};
	/**
	 * Face down: Luso, Sphene, Lightning. Forwards without Back Attack, so the CPU has nothing to
	 * respond with when P1 passes priority into the Attack Phase.
	 */
	private static final String[] P2_HAND = {"27-125S", "27-126S", "27-127S"};
	/** Odin, discarded for CP. */
	private static final String[] P2_BREAK_ZONE = {"13-072R"};
	/** P2's damage, first point first: Hugh Yurg, then Noel, an EX Burst, which its slot marks. */
	private static final String[] P2_DAMAGE = {"24-063H", "16-092C"};
	private static final String[] P2_LB_DECK = {"22-118H", "25-113R", "22-121R", "24-119R", "23-127R", "25-112H"};
	private static final String[] P2_DECK_CARDS = {"27-124S", "27-125S", "27-126S", "27-127S", "13-072R", "1-121C"};
	private static final int P2_DECK_SIZE = 43;

	/** In the side panel's preview, as though the pointer were over it in the hand: Alphinaud. */
	private static final String PREVIEW = "20-106R";

	/**
	 * The Forwards P1 has clicked to attack with, by place in the Forward row, not yet declared:
	 * Charlotte. Each is raised out of its row and outlined, and the Attack button is live. A second
	 * one, Leo at 1, would make it a party attack.
	 */
	private static final int[] ATTACKERS = {0};

	/**
	 * The game log up to the moment P1 presses Next in Main Phase 1, in the game's own wording. The
	 * game logs the Attack Phase lines that follow.
	 */
	private static final String[] LOG = {
			"Game Start",
			"Turn 1 — Active Phase",
			"Draw Phase — Drew 1 card",
			"Main Phase 1",
			"Played \"Charlotte\"",
			"Cast LB \"Leo\"",
			"End Phase",
			"Turn 2 — P2 Active Phase",
			"[P2] Draw Phase — Drew 2 card(s)",
			"[P2] Main Phase 1",
			"[P2] Cast LB \"Maat\"",
			"[P2] Plays Cloud",
			"[P2] Cloud gains [BRAVE] until end of turn",
			"[P2] Discards Odin for CP",
			"[P2] Plays Red Mage",
			"[Priority] P2 passes — nothing you can play, passing automatically.",
			"[P2] Attack Phase — No attackers, skipping",
			"[P2] Main Phase 2",
			"[Priority] P2 passes — nothing you can play, passing automatically.",
			"[P2] End Phase",
			"Turn 3 — Active Phase",
			"Draw Phase — Drew 2 card(s)",
			"Main Phase 1",
			"Played \"Geomancer\"",
			"Played \"Tidus\"",
			"Discards Zack and Wuk Lamat for CP",
			"Played \"Ward\"",
			"[AutoAbility] Ward — pushed to stack",
			"[AutoAbility] Resolving \"Ward\": choose 1 Forward. Deal it 7000 damage.",
			"Choose 1 Forward — Deal 7000 damage",
			"[P2] Maat takes 7000 damage (1000 remaining)",
	};

	// =============================================================================================
	// Entry point
	// =============================================================================================

	public static void main(String[] args) throws Exception {
		File out = new File(args.length > 0 ? args[0] : "target/screenshots");
		List<Theme> themes = new ArrayList<>();
		for (String name : Arrays.asList(args).subList(Math.min(1, args.length), args.length))
			themes.add(Theme.valueOf(name.toUpperCase(Locale.ROOT)));
		if (themes.isEmpty()) themes = List.of(Theme.PALADIN, Theme.DARK_KNIGHT);
		if (!out.isDirectory() && !out.mkdirs()) throw new IllegalStateException("cannot create " + out);

		SoundEffects.silence();
		try {
			for (Theme theme : themes) {
				String slug = theme.name().toLowerCase(Locale.ROOT).replace('_', '-');
				MainWindow mw = onEdt(() -> {
					theme.apply();
					MainWindow w = new MainWindow();
					layOut(w.frame);
					return w;
				});
				write(deckManagerShot(mw), new File(out, "deck-manager-" + slug + ".png"));
				write(gameBoardShot(mw), new File(out, "game-board-" + slug + ".png"));
				onEdt(() -> { mw.frame.dispose(); return null; });
			}
		} finally {
			// The CPU's timers, the image loaders and the Swing threads would otherwise keep the JVM up.
			System.exit(0);
		}
	}

	// =============================================================================================
	// Deck Manager
	// =============================================================================================

	/** The Deck Manager centred over the idle main window, whose title bar shows it inactive. */
	private static BufferedImage deckManagerShot(MainWindow mw) throws Exception {
		DeckManager dm = onEdt(() -> {
			DeckManager d = new DeckManager(mw.frame);
			d.addNotify();
			d.validate();
			return d;
		});
		try {
			waitFor("the Card Browser to load", dm::browserLoaded);
			onEdt(() -> {
				if (!dm.selectDeckNamed(DECK_NAME))
					throw new IllegalStateException("My Decks has no deck named \"" + DECK_NAME + "\"");
				if (!dm.selectBrowserCard(BROWSER_CARD))
					throw new IllegalStateException("the Card Browser does not list " + BROWSER_CARD);
				return null;
			});
			waitFor("the preview of " + BROWSER_CARD, () -> dm.previewShows(BROWSER_CARD));
			return shoot(() -> {
				double scale = displayScale(mw.frame);
				BufferedImage under = paint(mw.frame, false, scale);
				BufferedImage over = paint(dm, true, scale);
				return overWindow(under, over, scale);
			});
		} finally {
			onEdt(() -> { dm.dispose(); return null; });
		}
	}

	// =============================================================================================
	// Game board
	// =============================================================================================

	/** The staged game, in the Attack Phase with an attacker to declare. */
	private static BufferedImage gameBoardShot(MainWindow mw) throws Exception {
		onEdt(() -> { stageBoard(mw); return null; });
		// Next in Main Phase 1: P2 is offered priority on a timer, passes, and the Attack Phase opens
		// at Attack Preparation. With nothing for P1 to play, Auto passes that too; under Wait, or if
		// the game ever stops there, Next is pressed again, as the player would.
		onEdt(() -> { mw.onNextPhase(); return null; });
		long pressedAt = System.currentTimeMillis();
		boolean pressedAgain = false;
		waitFor("the Attack Phase to open", () -> mw.attackSubStep >= 0);
		while (onEdt(() -> mw.attackSubStep) != 1) {
			if (!pressedAgain && System.currentTimeMillis() - pressedAt > 3000) {
				onEdt(() -> { if (mw.attackSubStep == 0) mw.onNextPhase(); return null; });
				pressedAgain = true;
			}
			if (System.currentTimeMillis() - pressedAt > TIMEOUT_MS)
				throw new IllegalStateException("timed out waiting to declare an attacker");
			Thread.sleep(POLL_MS);
		}
		onEdt(() -> {
			for (int idx : ATTACKERS) click(mw.fieldSlot(true, ForwardTarget.CardZone.FORWARD, idx));
			return null;
		});
		waitFor("the attackers to be chosen", () -> mw.p1AttackSelection.size() == ATTACKERS.length);
		onEdt(() -> { mw.showZoomAt(mw.buildCardDataFromSerial(PREVIEW).imageUrl()); return null; });
		settle(SETTLE_MS);
		return shoot(() -> {
			// A game keeps the action buttons current on a timer it starts as it begins, which the
			// staged game never does; this is that timer's tick.
			mw.syncSlotButtons();
			return opaque(paint(mw.frame, true, displayScale(mw.frame)));
		});
	}

	/** A left click on the middle of {@code slot}'s card, clear of its action buttons. */
	private static void click(FieldSlotLabel slot) {
		Rectangle card = FieldSlotLabel.iconBounds(slot, slot.getIcon());
		int x = card.x + card.width / 2, y = card.y + card.height / 2;
		for (int id : new int[] {MouseEvent.MOUSE_PRESSED, MouseEvent.MOUSE_RELEASED, MouseEvent.MOUSE_CLICKED})
			slot.dispatchEvent(new MouseEvent(slot, id, System.currentTimeMillis(),
					id == MouseEvent.MOUSE_PRESSED ? InputEvent.BUTTON1_DOWN_MASK : 0, x, y, 1, false, MouseEvent.BUTTON1));
	}

	/**
	 * Sets up the board, both decks and both hands, and leaves P1 in Main Phase 1 of turn 3 with
	 * the log written up to there.
	 */
	private static void stageBoard(MainWindow mw) throws Exception {
		mw.resetForNewGame();
		GameState gs = mw.gameState;

		List<CardData> p1Hand = cards(mw, P1_HAND, true);
		gs.initializeDeck(deck(mw, P1_DECK_CARDS, P1_DECK_SIZE, true), cards(mw, P1_LB_DECK, true), new Random(1));
		gs.keepHand(p1Hand);
		gs.initializeP2MainDeck(deck(mw, P2_DECK_CARDS, P2_DECK_SIZE, false), new Random(2));
		gs.initializeP2LbDeck(cards(mw, P2_LB_DECK, false));
		gs.getP2Hand().addAll(cards(mw, P2_HAND, false));

		gs.startFirstTurn(GameState.Player.P1);
		advanceTo(gs, 1, GameState.GamePhase.MAIN_1);
		for (CardData c : cards(mw, P1_READY_FORWARDS, true)) place(mw, c, true);
		advanceTo(gs, 2, GameState.GamePhase.MAIN_1);
		for (CardData c : cards(mw, P2_FORWARDS, false)) place(mw, c, false);
		for (CardData c : cards(mw, P2_BACKUPS, false)) place(mw, c, false);
		advanceTo(gs, 3, GameState.GamePhase.MAIN_1);
		for (CardData c : cards(mw, P1_BACKUPS, true)) place(mw, c, true);
		for (CardData c : cards(mw, P1_NEW_FORWARDS, true)) place(mw, c, true);

		mw.p2ForwardDamage.set(0, MAAT_DAMAGE);
		mw.refreshP2ForwardSlot(0);
		for (CardData c : cards(mw, P1_BREAK_ZONE, true)) mw.addToBreakZone(c);
		for (CardData c : cards(mw, P2_BREAK_ZONE, false)) mw.addToBreakZone(c);
		// As the Debug menu sets damage: into the zone, counted, and the slots redrawn.
		for (CardData c : cards(mw, P2_DAMAGE, false)) {
			gs.getP2DamageZone().add(c);
			mw.p2DamageCount++;
		}
		mw.refreshDamageZoneSlots(false);

		mw.refreshP1DeckLabel();
		mw.refreshP1LimitLabel();
		mw.refreshP1HandLabel();
		mw.refreshP2DeckLabel();
		mw.refreshP2LimitButton();
		mw.refreshP2HandCountLabel();
		mw.refreshPhaseTracker();
		mw.refreshAllForwardSlots();

		StyledDocument log = mw.gameLogDocument();
		log.remove(0, log.getLength());
		for (String line : LOG) mw.logEntry(line);
	}

	/** Advances GameState alone, with none of the phase changes' effects, to {@code phase} of {@code turn}. */
	private static void advanceTo(GameState gs, int turn, GameState.GamePhase phase) {
		while (gs.getTurnNumber() < turn || gs.getCurrentPhase() != phase) gs.advancePhase();
	}

	/** Puts {@code card} on its owner's field with its enters-the-field abilities silenced. */
	private static void place(MainWindow mw, CardData card, boolean isP1) {
		mw.suppressAutoAbilityForNextCards = 1;
		if (card.isBackup()) {
			if (isP1) mw.placeCardInFirstBackupSlot(card); else mw.placeP2CardInFirstBackupSlot(card);
		} else {
			if (isP1) mw.placeCardInForwardZone(card); else mw.placeP2CardInForwardZone(card);
		}
	}

	private static List<CardData> cards(MainWindow mw, String[] serials, boolean isP1) {
		List<CardData> out = new ArrayList<>();
		for (String serial : serials) out.add(card(mw, serial, isP1));
		return out;
	}

	/** {@code size} cards dealt round robin from {@code serials}. */
	private static List<CardData> deck(MainWindow mw, String[] serials, int size, boolean isP1) {
		List<CardData> out = new ArrayList<>();
		for (int i = 0; i < size; i++) out.add(card(mw, serials[i % serials.length], isP1));
		return out;
	}

	/** A new copy of {@code serial}, owned by P1 or P2. */
	private static CardData card(MainWindow mw, String serial, boolean isP1) {
		CardData c = mw.buildCardDataFromSerial(serial);
		if (c == null) throw new IllegalStateException("the card database has no " + serial);
		mw.gameState.getIdentity().put(c, isP1);
		return c;
	}

	// =============================================================================================
	// Painting
	// =============================================================================================

	/** Windows 11's window corner radius, and the reach of the shadow round a window, at 100%. */
	private static final int CORNER_RADIUS = 8;
	private static final int SHADOW_RADIUS = 16;
	private static final int SHADOW_DROP = 6;
	private static final float SHADOW_ALPHA = 0.45f;

	/**
	 * Gives the unshown {@code frame} a native peer, so FlatLaf installs its title bar and fonts
	 * measure as they do on screen, and lays it out at the size the app opens at. A window that
	 * opens maximized fills the work area exactly, its resize borders off the screen, so the frame
	 * is the work area plus those borders and the root pane, which is what gets painted, the work
	 * area itself.
	 */
	private static void layOut(JFrame frame) {
		frame.addNotify();
		int w = UiScale.windowWidth(), h = UiScale.windowHeight();
		if (UiScale.isMaximized()) {
			Insets in = frame.getInsets();
			w += in.left + in.right;
			h += in.top + in.bottom;
		}
		frame.setSize(w, h);
		frame.validate();
	}

	/**
	 * Paints once and throws it away, lets what that started finish, then paints for keeps. Some
	 * images are only asked for by a paint, such as the faces in P1's hand fan, and a window that is
	 * never shown is never painted until this asks it to.
	 */
	private static BufferedImage shoot(Callable<BufferedImage> paint) throws Exception {
		onEdt(paint);
		settle(SETTLE_MS);
		return onEdt(paint);
	}

	/**
	 * {@code window}'s root pane, title bar and all, painted at {@code scale} device pixels to the
	 * pixel, its title bar drawn active or inactive as asked: the window is never shown, so it is
	 * never really either.
	 */
	private static BufferedImage paint(JFrame window, boolean active, double scale) throws Exception {
		JRootPane root = window.getRootPane();
		FlatTitlePane title = find(root, FlatTitlePane.class);
		if (title != null) {
			Method activeChanged = FlatTitlePane.class.getDeclaredMethod("activeChanged", boolean.class);
			activeChanged.setAccessible(true);
			activeChanged.invoke(title, active);
		}
		root.validate();
		BufferedImage img = new BufferedImage((int) Math.round(root.getWidth() * scale),
				(int) Math.round(root.getHeight() * scale), BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = img.createGraphics();
		g.scale(scale, scale);
		root.paint(g);
		g.dispose();
		return img;
	}

	/** {@code over} centred on {@code under}, with a window's rounded corners and soft drop shadow. */
	private static BufferedImage overWindow(BufferedImage under, BufferedImage over, double scale) {
		int w = over.getWidth(), h = over.getHeight();
		int x = (under.getWidth() - w) / 2, y = (under.getHeight() - h) / 2;
		float arc = (float) (2 * CORNER_RADIUS * scale);
		int blur = (int) Math.round(SHADOW_RADIUS * scale);

		// The shadow: the window's shape in black, blurred, drawn a little low. Two passes spread it
		// 2 * blur, and a ConvolveOp leaves a band of blur at the image's edge unfiltered, so the
		// margin is 3 * blur: less cuts the shadow off with a hard edge where it has not yet faded.
		int pad = 3 * blur;
		BufferedImage shadow = new BufferedImage(w + 2 * pad, h + 2 * pad, BufferedImage.TYPE_INT_ARGB);
		Graphics2D s = shadow.createGraphics();
		s.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		s.setColor(new Color(0, 0, 0, Math.round(SHADOW_ALPHA * 255)));
		s.fill(new RoundRectangle2D.Float(pad, pad, w, h, arc, arc));
		s.dispose();
		shadow = boxBlur(boxBlur(shadow, blur), blur);

		BufferedImage window = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
		Graphics2D r = window.createGraphics();
		r.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
		r.setColor(Color.WHITE);
		r.fill(new RoundRectangle2D.Float(0, 0, w, h, arc, arc));
		r.setComposite(AlphaComposite.SrcIn);
		r.drawImage(over, 0, 0, null);
		// The hairline Windows draws round a window, which keeps a dark one apart from a dark board.
		r.setComposite(AlphaComposite.SrcOver);
		r.setColor(Theme.current().isDark() ? new Color(255, 255, 255, 48) : new Color(0, 0, 0, 64));
		r.draw(new RoundRectangle2D.Float(0.5f, 0.5f, w - 1, h - 1, arc, arc));
		r.dispose();

		BufferedImage out = new BufferedImage(under.getWidth(), under.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(under, 0, 0, null);
		g.drawImage(shadow, x - pad, y - pad + (int) Math.round(SHADOW_DROP * scale), null);
		g.drawImage(window, x, y, null);
		g.dispose();
		return out;
	}

	/** A separable box blur of {@code radius} pixels, horizontal then vertical. */
	private static BufferedImage boxBlur(BufferedImage img, int radius) {
		int size = 2 * radius + 1;
		float[] weights = new float[size];
		Arrays.fill(weights, 1f / size);
		BufferedImage across = new ConvolveOp(new Kernel(size, 1, weights), ConvolveOp.EDGE_NO_OP, null).filter(img, null);
		return new ConvolveOp(new Kernel(1, size, weights), ConvolveOp.EDGE_NO_OP, null).filter(across, null);
	}

	private static BufferedImage opaque(BufferedImage img) {
		BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_RGB);
		Graphics2D g = out.createGraphics();
		g.drawImage(img, 0, 0, null);
		g.dispose();
		return out;
	}

	/**
	 * Windows' display scaling for the screen {@code frame} opens on: Java lays windows out in
	 * logical pixels, and a screenshot is taken in device pixels.
	 */
	private static double displayScale(JFrame frame) {
		return frame.getGraphicsConfiguration().getDefaultTransform().getScaleX();
	}

	private static <T extends Component> T find(Container root, Class<T> type) {
		for (Component c : root.getComponents()) {
			if (type.isInstance(c)) return type.cast(c);
			if (c instanceof Container k) {
				T found = find(k, type);
				if (found != null) return found;
			}
		}
		return null;
	}

	private static void write(BufferedImage img, File file) throws Exception {
		ImageIO.write(img, "png", file);
		System.out.println("wrote " + file.getPath() + " (" + img.getWidth() + "x" + img.getHeight() + ")");
	}

	// =============================================================================================
	// Waiting on the event thread
	// =============================================================================================

	private static final long TIMEOUT_MS = 30_000;
	private static final long POLL_MS = 100;
	/**
	 * How long animations, fades and image loads are given before a picture is taken: field
	 * arrivals, the attacker glows, the preview's fade-in and the hand fan's faces all run on timers
	 * or background loads.
	 */
	private static final long SETTLE_MS = 2500;

	/** Runs {@code task} on the event thread and returns its result, rethrowing what it throws. */
	private static <T> T onEdt(Callable<T> task) throws Exception {
		FutureTask<T> f = new FutureTask<>(task);
		EventQueue.invokeLater(f);
		try {
			return f.get();
		} catch (ExecutionException e) {
			if (e.getCause() instanceof Exception x) throw x;
			throw e;
		}
	}

	/** Polls {@code done} on the event thread until it holds, failing after {@link #TIMEOUT_MS}. */
	private static void waitFor(String what, BooleanSupplier done) throws Exception {
		long deadline = System.currentTimeMillis() + TIMEOUT_MS;
		while (!onEdt(done::getAsBoolean)) {
			if (System.currentTimeMillis() > deadline) throw new IllegalStateException("timed out waiting for " + what);
			Thread.sleep(POLL_MS);
		}
	}

	/** Lets timers and background loads run for {@code ms}, then waits for the event queue to drain. */
	private static void settle(long ms) throws Exception {
		Thread.sleep(ms);
		onEdt(() -> null);
	}
}
