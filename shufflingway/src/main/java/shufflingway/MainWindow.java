package shufflingway;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.EventQueue;
import java.awt.FlowLayout;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Image;
import java.awt.Insets;
import java.awt.Point;
import java.awt.RenderingHints;
import java.awt.Toolkit;
import java.awt.color.ColorSpace;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.awt.image.ColorConvertOp;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.UIManager;
import javax.swing.border.BevelBorder;
import javax.swing.border.SoftBevelBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import scraper.DeckDatabase;
import scraper.DeckDatabase.DeckCardDetail;
import static shufflingway.CardAnimation.CARD_H;
import static shufflingway.CardAnimation.CARD_W;
import static shufflingway.CardFilters.cardNamesOverlap;
import static shufflingway.CardFilters.discardTypeKey;
import static shufflingway.CardFilters.isBlockingTargetFilter;
import static shufflingway.CardFilters.isEnteredThisTurnCondition;
import static shufflingway.CardFilters.matchesAltBzType;
import static shufflingway.CardFilters.matchesDiscardType;
import static shufflingway.CardFilters.meetsCardNameFilter;
import static shufflingway.CardFilters.meetsCategoryFilter;
import static shufflingway.CardFilters.meetsCostConstraint;
import static shufflingway.CardFilters.meetsElementExclusion;
import static shufflingway.CardFilters.meetsElementFilter;
import static shufflingway.CardFilters.meetsJobFilter;
import static shufflingway.CardFilters.meetsPowerConstraint;
import static shufflingway.CardFilters.meetsTargetCondition;
import static shufflingway.CpPaymentUtils.contributingElement;
import static shufflingway.CpPaymentUtils.matchesAnyElement;
import shufflingway.dialog.AbilityPaymentDialog;
import shufflingway.dialog.AltCostPaymentDialog;
import shufflingway.dialog.LbPaymentDialog;
import shufflingway.dialog.StandardPaymentDialog;
import shufflingway.dialog.WarpPaymentDialog;
import shufflingway.menu.FileMenu;
import shufflingway.menu.HelpMenu;
import shufflingway.menu.MultiplayerMenu;
import shufflingway.net.ActionType;
import shufflingway.net.GameConnection;

public class MainWindow {

	private JFrame frame;

	// Side info panel dimensions.
	// The panel is sized to the native card-image width on the first hover;
	// these are just the fallback values used before any image loads.
	private static final int    SIDE_MARGIN    = 4;                   // px between card and panel edge
	private static final double PREVIEW_SCALE  = 0.8;
	private static final int    RESIZE_HANDLE_W = 5;                 // draggable sidebar divider width
	private int sidePanelW = (int)(3 * CARD_W * PREVIEW_SCALE);   // updated on first image load
	private int previewH   =
			(int)(sidePanelW * (double) CARD_H / CARD_W);         // updated on first image load
	private boolean previewSized = false;
	private int nativeImgW   = 0;   // native card image dimensions (set on first hover)
	private int nativeImgH   = 0;
	private int minSidePanelW = 0;  // resize clamp bounds (set on first hover)
	private int maxSidePanelW = 0;

	// P1 zone labels that change during gameplay
	private JLabel p1DeckLabel;
	private JLabel p2DeckLabel;
	private CrystalDisplay p1CrystalDisplay;
	private CrystalDisplay p2CrystalDisplay;
	private JButton p1LimitLabel;
	private JPanel handPanel;
	private JLabel p1BreakLabel;
	private JLabel p2BreakLabel;
	private JLabel p2HandCountLabel;
	private GrayscaleLabel p1RemoveLabel;
	private GrayscaleLabel p2RemoveLabel;
	private JButton        p1RemoveButton;
	private JButton        p2RemoveButton;
	// Game event log
	private JTextArea gameLog;
	// Chat bar (enabled only when connected to multiplayer)
	private JTextField chatInput;
	private JButton    chatSendBtn;
	// Multiplayer menu reference (to access active connection)
	private MultiplayerMenu multiplayerMenu;
	// Side info panel (card preview + Next button + game log)
	private JPanel        sidePanel;
	private JPanel        sideWrapper;        // contains resizeHandle + sidePanel
	private JPanel        resizeHandle;       // draggable divider between board and sidebar
	private JPanel        cardPreviewPanel;   // custom-painted card preview
	private BufferedImage previewImage;       // current card to draw (null = empty)
	private float         previewAlpha  = 0f; // 0 = transparent, 1 = fully opaque
	private javax.swing.Timer fadeTimer;      // drives fade-in / fade-out animation
	private CardSlideAnimator cardSlideAnimator;
	private CardBreakAnimator breakAnimator;
	// Horizontal separator where the P1 and P2 fields meet (anchor for centered effect prompts)
	private JSeparator fieldDivider;
	// Opening hand confirmation popup
	private JWindow openingHandPopup;
	// Hand hover popover (deck zone mouseover)
	private JWindow handPopup;
	// Stack overlay (shown while any entry is on the resolution stack)
	private JWindow               summonStackWindow;
	private javax.swing.Timer     stackCountdownTimer;
	private int                   stackWindowGeneration = 0;
	private javax.swing.Timer handPopupHideTimer;
	private boolean handCardMenuOpen = false;


	// --- Game state ---
	private final GameState gameState   = new GameState();
	private LookAtDeckDialogs lookDialogsInstance;
	// UI-only state (not owned by GameState)
	private JLabel[]    p1BackupLabels = new JLabel[5];
	private String[]    p1BackupUrls   = new String[5];
	private CardData[]  p1BackupCards  = new CardData[5];
	private CardState[] p1BackupStates = new CardState[5];

	private final List<JLabel>    p1ForwardLabels      = new ArrayList<>();
	private final List<String>    p1ForwardUrls;
	private final List<CardData>  p1ForwardCards       = new ArrayList<>();
	private final List<CardState> p1ForwardStates      = new ArrayList<>();
	private final List<Integer>   p1ForwardPlayedOnTurn = new ArrayList<>();
	private final List<Integer>   p1ForwardDamage       = new ArrayList<>();
	/** Top card of a Primed stack; {@code null} at each index means not primed. */
	private final List<CardData>  p1ForwardPrimedTop   = new ArrayList<>();
	private final List<CardData>  p2ForwardPrimedTop   = new ArrayList<>();
	/** Per-slot frozen flags — independent of CardState (a card may be Dulled AND frozen). */
	private final List<Boolean>   p1ForwardFrozen      = new ArrayList<>();
	private final List<Boolean>   p2ForwardFrozen      = new ArrayList<>();
	private final List<Integer>                           p1ForwardPowerBoost     = new ArrayList<>();
	private final List<Integer>                           p2ForwardPowerBoost     = new ArrayList<>();
	private final List<Integer>                           p1ForwardPowerReduction = new ArrayList<>();
	private final List<Integer>                           p2ForwardPowerReduction = new ArrayList<>();
	private final List<java.util.EnumSet<CardData.Trait>> p1ForwardTempTraits    = new ArrayList<>();
	private final List<java.util.EnumSet<CardData.Trait>> p2ForwardTempTraits    = new ArrayList<>();
	private final List<java.util.EnumSet<CardData.Trait>> p1ForwardRemovedTraits = new ArrayList<>();
	private final List<java.util.EnumSet<CardData.Trait>> p2ForwardRemovedTraits = new ArrayList<>();
	/** Temporary job granted to P1/P2 Forwards until end of turn; {@code null} = no override. */
	private final List<String> p1ForwardTempJobs = new ArrayList<>();
	private final List<String> p2ForwardTempJobs = new ArrayList<>();
	/** Forwards that may not be chosen as a blocker for the remainder of this turn. */
	private final Set<Integer> p1ForwardCannotBlock = new HashSet<>();
	private final Set<Integer> p2ForwardCannotBlock = new HashSet<>();
	/** Forwards that must be chosen as a blocker this turn if they are eligible. */
	private final Set<Integer> p1ForwardMustBlock   = new HashSet<>();
	private final Set<Integer> p2ForwardMustBlock   = new HashSet<>();
	/** Forwards that may not attack for the remainder of this turn. */
	private final Set<Integer> p1ForwardCannotAttack = new HashSet<>();
	private final Set<Integer> p2ForwardCannotAttack = new HashSet<>();
	/** Forwards that must attack this turn if they are eligible. */
	private final Set<Integer> p1ForwardMustAttack   = new HashSet<>();
	private final Set<Integer> p2ForwardMustAttack   = new HashSet<>();
	/** Forwards restricted from attacking until the end of their owner's turn (survives one end-phase). */
	private final Set<Integer> p1ForwardCannotAttackPersistent = new HashSet<>();
	private final Set<Integer> p2ForwardCannotAttackPersistent = new HashSet<>();
	/** Forwards restricted from blocking until the end of their owner's turn (survives one end-phase). */
	private final Set<Integer> p1ForwardCannotBlockPersistent  = new HashSet<>();
	private final Set<Integer> p2ForwardCannotBlockPersistent  = new HashSet<>();
	/** Forwards that cannot be blocked this turn (attacker-side unblockability). */
	private final Set<Integer>          p1ForwardCannotBeBlocked       = new HashSet<>();
	private final Set<Integer>          p2ForwardCannotBeBlocked       = new HashSet<>();
	/** Forwards that cannot be blocked by Forwards whose cost matches the filter {costVal, 1=isMore/0=isLess}. */
	private final Map<Integer, int[]>   p1ForwardCannotBeBlockedByCost = new HashMap<>();
	private final Map<Integer, int[]>   p2ForwardCannotBeBlockedByCost = new HashMap<>();
	private final boolean[]       p1BackupFrozen       = new boolean[5];
	private final boolean[]       p2BackupFrozen       = new boolean[5];
	private final List<Boolean>   p1MonsterFrozen      = new ArrayList<>();
	private JPanel p1ForwardPanel;

	/** Turn number on which each backup slot was last filled (0 = empty/unknown). */
	private final int[] p1BackupPlayedOnTurn = new int[5];

	private final List<JLabel>   p1MonsterLabels      = new ArrayList<>();
	private final List<String>   p1MonsterUrls        = new ArrayList<>();
	private final List<CardData> p1MonsterCards       = new ArrayList<>();
	private final List<CardState> p1MonsterStates      = new ArrayList<>();
	private final List<Integer>  p1MonsterPlayedOnTurn = new ArrayList<>();
	private final List<Integer>  p1MonsterDamage       = new ArrayList<>();
	private int                  p1MonsterAttackIdx    = -1;
	private final java.util.Map<CardData, Integer> p1MonsterTempForwardPower = new HashMap<>();
	private JPanel p1MonsterPanel;

	private final List<Boolean>   p2MonsterFrozen       = new ArrayList<>();
	private final List<JLabel>    p2MonsterLabels        = new ArrayList<>();
	private final List<String>    p2MonsterUrls          = new ArrayList<>();
	private final List<CardData>  p2MonsterCards         = new ArrayList<>();
	private final List<CardState> p2MonsterStates        = new ArrayList<>();
	private final List<Integer>   p2MonsterPlayedOnTurn  = new ArrayList<>();
	private final List<Integer>   p2MonsterDamage        = new ArrayList<>();
	private final java.util.Map<CardData, Integer> p2MonsterTempForwardPower = new HashMap<>();
	private JPanel p2MonsterPanel;

	private int      p2DamageCount = 0;
	private JPanel[] p2DamageSlots = new JPanel[7];

	// P2 field state (managed by ComputerPlayer)
	private final JLabel[]     p2BackupLabels        = new JLabel[5];
	private final String[]     p2BackupUrls          = new String[5];
	private final CardData[]   p2BackupCards         = new CardData[5];
	private final CardState[]  p2BackupStates        = new CardState[5];
	private JPanel             p2ForwardPanel;
	private final List<JLabel>    p2ForwardLabels       = new ArrayList<>();
	private final List<String>    p2ForwardUrls         = new ArrayList<>();
	private final List<CardData>  p2ForwardCards        = new ArrayList<>();
	private final List<CardState> p2ForwardStates       = new ArrayList<>();
	private final List<Integer>   p2ForwardPlayedOnTurn = new ArrayList<>();
	private final List<Integer>   p2ForwardDamage       = new ArrayList<>();
	private ComputerPlayer        computerPlayer;

	private final Set<Integer> spentLbIndices   = new HashSet<>();
	private final Set<Integer> p2SpentLbIndices = new HashSet<>();
	private JButton            p2LimitButton;

	// Damage zone UI
	private JPanel   p1DamageSlotPanel;
	private JPanel[] p1DamageSlots = new JPanel[7];

	// Next-phase button and its glow animation
	private JButton              nextPhaseButton;
	private javax.swing.Timer    glowTimer;
	private final float[]        glowAngle = { 0f };

	// Phase tracker strip
	private PhaseTracker         phaseTracker;

	// Attack button and selection state for party attacks
	private JButton              attackButton;
	private JButton              skipAttackButton;
	private final List<Integer>  p1AttackSelection = new ArrayList<>();
	private int                  p1BlockingIdx     = -1;

	// In-place field targeting: while active, the normal field-card click handlers
	// (attack selection, context menus) are suppressed so clicks pick effect targets.
	private boolean fieldTargetingActive = false;

	// Temporary attack triggers registered by action abilities (cleared at end of turn)
	private final Map<CardData, List<Consumer<GameContext>>> p1TempAttackTriggers = new LinkedHashMap<>();
	private final Map<CardData, List<Consumer<GameContext>>> p2TempAttackTriggers = new LinkedHashMap<>();
	private final Map<CardData, List<Consumer<GameContext>>> p1TempBlockTriggers  = new LinkedHashMap<>();
	private final Map<CardData, List<Consumer<GameContext>>> p2TempBlockTriggers  = new LinkedHashMap<>();

	// Attack phase sub-step (0=Prep, 1=Declare, 2=Block, 3=Damage; -1=not in attack phase)
	private int attackSubStep = -1;

	// Non-modal P2-attack pending state: set while P1 is interactively declaring a blocker
	private CardData pendingP2Attacker        = null;
	private int      pendingP2AttackerIdx     = -1;
	private Runnable pendingP2BlockDone       = null;
	private boolean  pendingP2AttackerIsMonster = false;
	private int      pendingP2AttackerPower     = 0;
	private int           p1BlockerSelection      = -1;   // index of forward P1 clicked to block with
	private List<Integer> pendingP2PartyIndices   = null; // set while P1 declares blocker vs P2 party
	private int           pendingP2PartyCombined  = 0;

	// Blocking-target tracking: set between "Blocker Declared" and resolveCombat so that
	// "Choose 1 Forward blocking [Name/Job]" effects can identify the blocking forward.
	private CardData p1BlockedByAttacker  = null; // P2 attacker that p1BlockingIdx is blocking
	private int      p2BlockingIdx        = -1;   // P2 forward blocking a P1 attacker
	private CardData p2BlockedByAttacker  = null; // P1 attacker that p2BlockingIdx is blocking

	// Power of the Forward dulled as "Dull N active Forward" ability cost; set during payment.
	private int      lastDullForwardCostPower = 0;

	private boolean  effectProgress = true;

	// Separate JWindow for combat priority checkpoints (kept apart from summonStackWindow)
	private javax.swing.JWindow       combatPriorityWindow;
	private javax.swing.Timer         combatPriorityTimer;
	private javax.swing.Timer         p2AutoPassTimer;

	// Damage-shield / damage-modifier state (keyed by CardData identity; cleared at end of turn)
	private final Set<CardData>          nextIncomingDmgZeroSet   = new HashSet<>();
	private final Map<CardData, Integer> nextIncomingDmgReduceMap      = new HashMap<>();
	private final Map<CardData, Integer> nextAbilityDmgReduceMap       = new HashMap<>();
	private final Map<CardData, Integer> incomingDmgIncreaseMap   = new HashMap<>();
	private final Set<CardData>          nullifyAbilityDmgSet     = new HashSet<>();
	private final Set<CardData>          nullifyAbilityOnlyDmgSet = new HashSet<>();
	private final Set<CardData>          nextOutgoingDmgZeroSet   = new HashSet<>();
	private final Set<CardData>          perCardNonLethalDmgSet   = new HashSet<>();
	private boolean p1ReceivedDamageThisTurn = false;
	private boolean p2ReceivedDamageThisTurn = false;
	private int     p1CardsCastThisTurn      = 0;
	private boolean p1NonLethalProtection   = false;
	private boolean p2NonLethalProtection   = false;
	private boolean p1DmgReductionDisabled  = false;
	private boolean p2DmgReductionDisabled  = false;
	private int     p1GlobalDmgReduction    = 0;
	private int     p2GlobalDmgReduction    = 0;

	/** End-of-turn effects queued this turn; fired at the beginning of the END phase. */
	private final List<Consumer<GameContext>> endOfTurnEffects = new ArrayList<>();

	/** Active "next cast costs N less" modifiers; consumed on first matching cast, or cleared at EOT. */
	private final List<CostReductionModifier> activeCostReductions = new ArrayList<>();

	/** Effects deferred until the start of P1's next Main Phase 1. */
	private final List<Consumer<GameContext>> pendingMainPhase1Effects = new ArrayList<>();

	/** Tracks once-per-turn ability uses this turn; keyed by card instance identity, value is set of effectText strings used. */
	private final java.util.IdentityHashMap<CardData, java.util.Set<String>> usedOncePerTurnAbilities = new java.util.IdentityHashMap<>();

	/** Forwards that cannot be selected as targets by the opponent's Summons this turn. */
	private final Set<CardData> cannotBeChosenBySummons   = new HashSet<>();
	/** Forwards that cannot be selected as targets by the opponent's abilities this turn. */
	private final Set<CardData> cannotBeChosenByAbilities = new HashSet<>();
	/** Characters that cannot be broken this turn. */
	private final Set<CardData> cannotBeBrokenSet         = new HashSet<>();
	/** Characters that cannot be broken this turn by opposing non-damage abilities/summons. */
	private final Set<CardData> cannotBeBrokenByNonDmgSet = new HashSet<>();
	/** Forwards that have Breaktouch (battle damage) until end of turn. */
	private final Set<CardData> breaktouchBattleSet       = new HashSet<>();
	/** Cards that have escaped from the current Battle via an Escape ability — combat is skipped for their pairing. */
	private final Set<CardData> escapedFromBattle         = new HashSet<>();
	/** The card currently dealing ability damage (null when no ability damage is in flight). */
	private CardData currentBreaktouchSource    = null;
	private boolean  currentBreaktouchSourceIsP1 = false;
	/** Set to {@code true} while a Summon effect is resolving so {@link #selectCharacters} applies the correct protection set. */
	private boolean currentResolutionIsSummon = false;
	/** Set to {@code true} by {@code returnNamedCardToYourHand} when the Summon itself is being returned to hand. */
	private boolean pendingSummonReturnToHand = false;
	/** Set to {@code true} before placing a card whose ETF auto-ability should not fire (consumed on first trigger check). */
	private boolean suppressAutoAbilityForNextCard = false;

	/**
	 * Forwards currently stolen by P1 from P2, mapped to their restoration condition:
	 * {@code "permanent"}, {@code "endOfTurn"}, or {@code "whileCardOnField:Name"}.
	 */
	private final java.util.IdentityHashMap<CardData, String> stolenForwards = new java.util.IdentityHashMap<>();
	/** Distinct element types used to pay the most recent card's CP cost; checked by castPaymentMinElements conditions. */
	private int lastCastPaymentDistinctElements = 0;
	/** Specific element types used to pay the most recent card's CP cost; checked by castPaymentElement conditions. */
	private final java.util.Set<String> lastCastPaymentElements = new HashSet<>();
	/** True if the most recently cast card was paid entirely by dulling Backups (no hand discards). */
	private boolean lastCastWasPaidByBackupsOnly = false;
	/** True while a card is being placed as a direct result of being cast from hand; gates castOnly field abilities. */
	private boolean lastCardWasCast = false;
	/** True while a card is entering the field via Warp resolution; gates warpOnly field abilities. */
	private boolean lastCardWarpedIn = false;

	/** Set when "Take 1 more turn; lose at the end of that turn" fires. */
	private boolean p1ExtraTurnThenLose = false;

	public static void main(String[] args) {
		AppLogger.init();
		Thread.setDefaultUncaughtExceptionHandler((thread, throwable) ->
				AppLogger.log("Uncaught exception in thread: " + thread.getName(), throwable));
		Runtime.getRuntime().addShutdownHook(new Thread(ImageCache::shutdown));
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					MainWindow window = new MainWindow();
					window.frame.setVisible(true);
					ImageIcon icon40 = new ImageIcon(getClass().getResource("/resources/shufflingway.png"));
					window.frame.setIconImage(icon40.getImage());
				} catch (Exception e) {
					AppLogger.log("Startup exception", e);
				}
			}
		});
	}

	public MainWindow() {
        this.p1ForwardUrls = new ArrayList<>();
		initialize();
	}

	private void initialize() {
		frame = new JFrame("Shufflingway");
		frame.getContentPane().setBackground(Color.LIGHT_GRAY);
		frame.setBounds(0, 0, 1920, 1080);
		frame.setLocationRelativeTo(null);
		frame.setResizable(false);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override public void windowClosing(java.awt.event.WindowEvent e) {
				AppSettings.setSidePanelWidth(sidePanelW);
				AppSettings.save();
			}
		});
		frame.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		frame.getContentPane().setLayout(new BorderLayout());

		// --- Menu Bar ---
		JMenuBar menuBar = new JMenuBar();
		frame.setJMenuBar(menuBar);
		menuBar.add(new FileMenu(frame, (p1Id, p2Id) -> startGame(p1Id, p2Id),
				() -> applySidePanelSide(AppSettings.getSidePanelSide())));
		multiplayerMenu = new MultiplayerMenu(frame,
				() -> {
					logEntry("Multiplayer connection established");
					SwingUtilities.invokeLater(() -> {
						chatInput.setEnabled(true);
						chatSendBtn.setEnabled(true);
					});
				},
				() -> SwingUtilities.invokeLater(() -> {
					chatInput.setEnabled(false);
					chatSendBtn.setEnabled(false);
				}),
				action -> {
					if (action.type() == ActionType.CHAT) {
						String msg = action.payload().optString("msg", "");
						if (!msg.isEmpty()) logEntry("[Opponent] " + msg);
					}
				});
		menuBar.add(multiplayerMenu);
		menuBar.add(new HelpMenu(frame));

		Dimension cardSize = new Dimension(CARD_W, CARD_H);

		// --- P2 Zones (top of screen) ---
		p2RemoveLabel = new GrayscaleLabel("");

		int CORNER_BAR_H = 28;
		int LIMIT_W      = (CARD_W * 3) / 4;   // 105 px
		int REMOVE_W     = CARD_W - LIMIT_W;    //  35 px

		p2LimitButton = new JButton("LIMIT");
		JButton lblLimit_1 = p2LimitButton;
		lblLimit_1.setToolTipText("Player 2 LB Deck");
		lblLimit_1.setFont(FontLoader.loadPixelNESFont(10));
		lblLimit_1.setBackground(new Color(212, 175, 55));
		lblLimit_1.setForeground(Color.BLACK);
		lblLimit_1.setOpaque(true);
		lblLimit_1.setBorderPainted(false);
		lblLimit_1.setFocusPainted(false);
		lblLimit_1.setPreferredSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		lblLimit_1.setMinimumSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		lblLimit_1.setMaximumSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		lblLimit_1.addActionListener(e -> showP2LbViewerDialog());

		p2BreakLabel = new JLabel("BREAK");
		p2BreakLabel.setToolTipText("Player 2 Break Zone");
		p2BreakLabel.setHorizontalAlignment(SwingConstants.CENTER);
		p2BreakLabel.setFont(FontLoader.loadPixelNESFont(18));
		p2BreakLabel.setBorder(new SoftBevelBorder(BevelBorder.LOWERED, null, null, null, null));
		p2BreakLabel.setBackground(Color.DARK_GRAY);
		p2BreakLabel.setForeground(Color.WHITE);
		p2BreakLabel.setOpaque(true);
		p2BreakLabel.setPreferredSize(cardSize);
		p2BreakLabel.setMinimumSize(cardSize);
		p2BreakLabel.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent e) {
				List<CardData> zone = gameState.getP2BreakZone();
				if (!zone.isEmpty()) showZoomAt(zone.get(zone.size() - 1).imageUrl());
			}
			@Override public void mouseExited(MouseEvent e) { hideZoom(); }
			@Override public void mousePressed(MouseEvent e) {
				if (!gameState.getP2BreakZone().isEmpty()) { hideZoom(); showP2BreakZoneDialog(); }
			}
		});

		p2DeckLabel = new JLabel("DECK");
		p2DeckLabel.setFont(FontLoader.loadPixelNESFont(18));
		p2DeckLabel.setToolTipText("Player 2 Deck");
		p2DeckLabel.setHorizontalAlignment(SwingConstants.CENTER);
		p2DeckLabel.setBorder(new SoftBevelBorder(BevelBorder.LOWERED, null, null, null, null));
		p2DeckLabel.setBackground(Color.DARK_GRAY);
		p2DeckLabel.setForeground(Color.WHITE);
		p2DeckLabel.setOpaque(true);

		p2RemoveButton = new JButton("RFP");
		p2RemoveButton.setToolTipText("Player 2 Removed From Play");
		p2RemoveButton.setFont(FontLoader.loadPixelNESFont(7));
		p2RemoveButton.setBackground(new Color(30, 30, 30));
		p2RemoveButton.setForeground(Color.LIGHT_GRAY);
		p2RemoveButton.setOpaque(true);
		p2RemoveButton.setBorderPainted(false);
		p2RemoveButton.setFocusPainted(false);
		p2RemoveButton.setEnabled(false);
		p2RemoveButton.setPreferredSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p2RemoveButton.setMinimumSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p2RemoveButton.setMaximumSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p2RemoveButton.addActionListener(e -> showRemovedFromPlayDialog(p2RemoveLabel, "P2"));

		JPanel p2BottomBar = new JPanel(new GridBagLayout());
		p2BottomBar.setPreferredSize(new Dimension(CARD_W, CORNER_BAR_H));
		p2BottomBar.setMinimumSize(new Dimension(CARD_W, CORNER_BAR_H));
		{
			GridBagConstraints bbc = new GridBagConstraints();
			bbc.fill = GridBagConstraints.BOTH; bbc.weighty = 1.0; bbc.gridy = 0;
			bbc.gridx = 0; bbc.weightx = 0.75; p2BottomBar.add(lblLimit_1, bbc);
			bbc.gridx = 1; bbc.weightx = 0.25; p2BottomBar.add(p2RemoveButton, bbc);
		}

		p2DeckLabel.setPreferredSize(cardSize);
		p2DeckLabel.setMinimumSize(cardSize);

		p2CrystalDisplay = new CrystalDisplay(0);
		p2CrystalDisplay.setPreferredSize(new Dimension(REMOVE_W, CrystalDisplay.CRYSTAL_H));
		p2CrystalDisplay.setMinimumSize(new Dimension(REMOVE_W, CrystalDisplay.CRYSTAL_H));
		p2CrystalDisplay.setMaximumSize(new Dimension(REMOVE_W, CrystalDisplay.CRYSTAL_H));

		JPanel p2CornerPanel = new JPanel(new BorderLayout(0, 0));
		p2CornerPanel.add(p2BreakLabel, BorderLayout.NORTH);
		p2CornerPanel.add(p2DeckLabel,  BorderLayout.CENTER);
		p2CornerPanel.add(p2BottomBar,  BorderLayout.SOUTH);

		p2HandCountLabel = new JLabel("P2 Hand: 0", SwingConstants.CENTER) {
			@Override protected void paintComponent(Graphics g) {
				Graphics2D g2 = (Graphics2D) g.create();
				g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,
						RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
				FontMetrics fm = g2.getFontMetrics(getFont());
				String text = getText();
				int x = (getWidth()  - fm.stringWidth(text)) / 2;
				int y = fm.getAscent();
				g2.setFont(getFont());
				g2.setColor(new Color(0, 0, 0, 180));
				g2.drawString(text, x + 1, y + 1);
				g2.setColor(getForeground());
				g2.drawString(text, x, y);
				g2.dispose();
			}
		};
		p2HandCountLabel.setFont(FontLoader.loadPixelNESFont(10));
		p2HandCountLabel.setForeground(Color.LIGHT_GRAY);
		p2HandCountLabel.setOpaque(false);

		// Crystal display sits to the left of the hand-count label
		JPanel p2HandRow = new JPanel(new BorderLayout(0, 0));
		p2HandRow.setOpaque(false);
		p2HandRow.add(p2CrystalDisplay, BorderLayout.WEST);
		p2HandRow.add(p2HandCountLabel,  BorderLayout.CENTER);

		JPanel p2CornerWrapper = new JPanel(new BorderLayout(0, 2));
		p2CornerWrapper.setOpaque(false);
		p2CornerWrapper.add(p2CornerPanel, BorderLayout.CENTER);
		p2CornerWrapper.add(p2HandRow,     BorderLayout.SOUTH);

		JComboBox<String> p2ColorBox = buildColorDropdown();
		JPanel p2DamagePanel = buildDamageZonePanel("P2", p2ColorBox);

		JPanel p2BackupSlots = buildBackupZonePanel(p2BackupLabels);
		for (int i = 0; i < p2BackupLabels.length; i++) {
			final int backupIdx = i;
			p2BackupLabels[i].addMouseListener(new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					if (p2BackupLabels[backupIdx].getIcon() != null)
						showP2BackupContextMenu(backupIdx, p2BackupLabels[backupIdx], e);
				}
				@Override public void mouseEntered(MouseEvent e) {
					if (p2BackupLabels[backupIdx].getIcon() != null)
						showZoomAt(p2BackupUrls[backupIdx]);
				}
				@Override public void mouseExited(MouseEvent e) { hideZoom(); }
			});
		}
		JPanel p2BackupWrapper = new JPanel(new GridBagLayout());
		GridBagConstraints p2BackupGbc = new GridBagConstraints();
		p2BackupGbc.anchor = GridBagConstraints.NORTH;
		p2BackupGbc.weighty = 1.0;
		p2BackupWrapper.add(p2BackupSlots, p2BackupGbc);

		JScrollPane p2ForwardZone = buildForwardZonePanel(false);

		JPanel p2TopRow = new JPanel(new BorderLayout());
		p2TopRow.add(p2BackupWrapper, BorderLayout.CENTER);

		JPanel p2MainArea = new JPanel(new BorderLayout(0, 4));
		p2MainArea.add(p2TopRow,      BorderLayout.NORTH);
		p2MainArea.add(p2ForwardZone, BorderLayout.SOUTH);

		JPanel p2ZonesPanel = new JPanel(new GridBagLayout());
		{
			GridBagConstraints z = new GridBagConstraints();
			z.gridy = 0; z.fill = GridBagConstraints.NONE; z.anchor = GridBagConstraints.NORTH; z.weightx = 0;
			z.gridx = 0; p2ZonesPanel.add(p2CornerWrapper, z);
			z.gridx = 2; p2ZonesPanel.add(p2DamagePanel, z);
			z.gridx = 1; z.fill = GridBagConstraints.BOTH; z.weightx = 1.0; z.weighty = 1.0;
			p2ZonesPanel.add(p2MainArea, z);
		}

		// --- P1 Zones (bottom of screen) ---
		JComboBox<String> p1ColorBox = buildColorDropdown();
		JPanel p1DamagePanel = buildDamageZonePanel("P1", p1ColorBox);

		// P1 deck label — interactive
		p1DeckLabel = new JLabel("DECK");
		p1DeckLabel.setFont(FontLoader.loadPixelNESFont(18));
		p1DeckLabel.setToolTipText("Player 1 Deck");
		p1DeckLabel.setHorizontalAlignment(SwingConstants.CENTER);
		p1DeckLabel.setBorder(new SoftBevelBorder(BevelBorder.LOWERED, null, null, null, null));
		p1DeckLabel.setBackground(Color.DARK_GRAY);
		p1DeckLabel.setForeground(Color.WHITE);
		p1DeckLabel.setOpaque(true);
		p1DeckLabel.addMouseListener(new MouseAdapter() {
			@Override
			public void mousePressed(MouseEvent e) {
				onP1DeckClicked();
			}
		});

		p1BreakLabel = new JLabel("BREAK");
		p1BreakLabel.setToolTipText("Player 1 Break Zone");
		p1BreakLabel.setHorizontalAlignment(SwingConstants.CENTER);
		p1BreakLabel.setFont(FontLoader.loadPixelNESFont(18));
		p1BreakLabel.setBorder(new SoftBevelBorder(BevelBorder.LOWERED, null, null, null, null));
		p1BreakLabel.setBackground(Color.DARK_GRAY);
		p1BreakLabel.setForeground(Color.WHITE);
		p1BreakLabel.setOpaque(true);
		p1BreakLabel.setPreferredSize(cardSize);
		p1BreakLabel.setMinimumSize(cardSize);
		p1BreakLabel.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent e) {
				List<CardData> zone = gameState.getP1BreakZone();
				if (!zone.isEmpty()) showZoomAt(zone.get(zone.size() - 1).imageUrl());
			}
			@Override public void mouseExited(MouseEvent e) { hideZoom(); }
			@Override public void mousePressed(MouseEvent e) {
				if (!gameState.getP1BreakZone().isEmpty()) { hideZoom(); showBreakZoneDialog(); }
			}
		});

		// P1 limit button — gold, 3/4 of card width
		p1LimitLabel = new JButton("LIMIT");
		p1LimitLabel.setToolTipText("Player 1 LB Deck");
		p1LimitLabel.setFont(FontLoader.loadPixelNESFont(10));
		p1LimitLabel.setBackground(new Color(212, 175, 55));
		p1LimitLabel.setForeground(Color.BLACK);
		p1LimitLabel.setOpaque(true);
		p1LimitLabel.setBorderPainted(false);
		p1LimitLabel.setFocusPainted(false);
		p1LimitLabel.setPreferredSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		p1LimitLabel.setMinimumSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		p1LimitLabel.setMaximumSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		p1LimitLabel.addActionListener(e -> {
			GameState.GamePhase phase = gameState.getCurrentPhase();
			boolean isMainPhase = phase == GameState.GamePhase.MAIN_1
					|| phase == GameState.GamePhase.MAIN_2;
			if (!gameState.getP1LbDeck().isEmpty() && isMainPhase && !gameState.isP1GameOver()) showLbDialog();
		});

		p1RemoveLabel = new GrayscaleLabel("");

		p1RemoveButton = new JButton("RFP");
		p1RemoveButton.setToolTipText("Player 1 Removed From Play");
		p1RemoveButton.setFont(FontLoader.loadPixelNESFont(7));
		p1RemoveButton.setBackground(new Color(30, 30, 30));
		p1RemoveButton.setForeground(Color.LIGHT_GRAY);
		p1RemoveButton.setOpaque(true);
		p1RemoveButton.setBorderPainted(false);
		p1RemoveButton.setFocusPainted(false);
		p1RemoveButton.setEnabled(false);
		p1RemoveButton.setPreferredSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p1RemoveButton.setMinimumSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p1RemoveButton.setMaximumSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p1RemoveButton.addActionListener(e -> showRemovedFromPlayDialog(p1RemoveLabel, "P1"));

		p1CrystalDisplay = new CrystalDisplay(0);
		p1CrystalDisplay.setPreferredSize(new Dimension(REMOVE_W, CrystalDisplay.CRYSTAL_H));
		p1CrystalDisplay.setMinimumSize(new Dimension(REMOVE_W, CrystalDisplay.CRYSTAL_H));
		p1CrystalDisplay.setMaximumSize(new Dimension(REMOVE_W, CrystalDisplay.CRYSTAL_H));

		// Crystal sits above the full bar, pinned to the right to align with the RFP button
		JPanel p1CrystalRow = new JPanel(new BorderLayout(0, 0));
		p1CrystalRow.setOpaque(false);
		p1CrystalRow.add(p1CrystalDisplay, BorderLayout.EAST);

		// Restore the limit button's original height constraint
		p1LimitLabel.setMaximumSize(new Dimension(LIMIT_W, CORNER_BAR_H));

		// Restore the original two-button top bar
		JPanel p1TopBar = new JPanel(new GridBagLayout());
		p1TopBar.setPreferredSize(new Dimension(CARD_W, CORNER_BAR_H));
		p1TopBar.setMinimumSize(new Dimension(CARD_W, CORNER_BAR_H));
		{
			GridBagConstraints tbc = new GridBagConstraints();
			tbc.fill = GridBagConstraints.BOTH; tbc.weighty = 1.0; tbc.gridy = 0;
			tbc.gridx = 0; tbc.weightx = 0.75; p1TopBar.add(p1LimitLabel, tbc);
			tbc.gridx = 1; tbc.weightx = 0.25; p1TopBar.add(p1RemoveButton, tbc);
		}

		// Wrapper: crystal row above, top bar below
		JPanel p1NorthWrapper = new JPanel(new BorderLayout(0, 0));
		p1NorthWrapper.setOpaque(false);
		p1NorthWrapper.add(p1CrystalRow, BorderLayout.NORTH);
		p1NorthWrapper.add(p1TopBar,     BorderLayout.SOUTH);

		p1DeckLabel.setPreferredSize(cardSize);
		p1DeckLabel.setMinimumSize(cardSize);

		JPanel p1CornerPanel = new JPanel(new BorderLayout(0, 0));
		p1CornerPanel.add(p1NorthWrapper, BorderLayout.NORTH);
		p1CornerPanel.add(p1DeckLabel,    BorderLayout.CENTER);
		p1CornerPanel.add(p1BreakLabel,   BorderLayout.SOUTH);

		JPanel p1BackupSlots = buildBackupZonePanel(p1BackupLabels);
		for (int i = 0; i < p1BackupLabels.length; i++) {
			final int backupIdx = i;
			p1BackupLabels[i].addMouseListener(new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					if (p1BackupLabels[backupIdx].getIcon() != null)
						showBackupContextMenu(backupIdx, p1BackupLabels[backupIdx], e);
				}
				@Override public void mouseEntered(MouseEvent e) {
					if (p1BackupLabels[backupIdx].getIcon() != null)
						showZoomAt(p1BackupUrls[backupIdx]);
				}
				@Override public void mouseExited(MouseEvent e) { hideZoom(); }
			});
		}

		JPanel p1BackupWrapper = new JPanel(new GridBagLayout());
		GridBagConstraints p1BackupGbc = new GridBagConstraints();
		p1BackupGbc.anchor = GridBagConstraints.SOUTH;
		p1BackupGbc.weighty = 1.0;
		p1BackupWrapper.add(p1BackupSlots, p1BackupGbc);

		JScrollPane p1ForwardZone = buildForwardZonePanel(true);

		// --- Next Phase Button ---
		nextPhaseButton = new JButton("<html><center>NEXT<br>&#9658;</center></html>");
		nextPhaseButton.setFont(FontLoader.loadPixelNESFont(14));
		nextPhaseButton.setEnabled(false);
		nextPhaseButton.setFocusPainted(false);
		nextPhaseButton.addActionListener(e -> onNextPhase());

		// Pulsing glow border — runs continuously, only paints when enabled
		glowTimer = new javax.swing.Timer(40, e -> {
			if (nextPhaseButton == null || !nextPhaseButton.isEnabled()) return;
			glowAngle[0] += 0.09f;
			float t = (float)(0.5 + 0.5 * Math.sin(glowAngle[0]));
			int r = (int)(180 + t * 75);   // 180â€“255
			int g = (int)(110 + t * 80);   // 110â€“190
			nextPhaseButton.setBorder(BorderFactory.createLineBorder(
					new Color(Math.min(r, 255), Math.min(g, 255), 20), 3, true));
		});
		glowTimer.start();

		JPanel p1BottomRow = new JPanel(new BorderLayout());
		p1BottomRow.add(p1BackupWrapper, BorderLayout.CENTER);

		JPanel p1MainArea = new JPanel(new BorderLayout(0, 4));
		p1MainArea.add(p1ForwardZone,  BorderLayout.NORTH);
		p1MainArea.add(p1BottomRow,    BorderLayout.SOUTH);

		// Damage panel on the left, hand slot flush against its right edge at the bottom
		JPanel p1LeftGroup = new JPanel(new GridBagLayout());
		GridBagConstraints lgbc = new GridBagConstraints();
		lgbc.gridx = 0; lgbc.gridy = 0;
		lgbc.fill = GridBagConstraints.BOTH;
		lgbc.weighty = 1.0;
		p1LeftGroup.add(p1DamagePanel, lgbc);

		JPanel p1ZonesPanel = new JPanel(new GridBagLayout());
		{
			GridBagConstraints z = new GridBagConstraints();
			z.gridy = 0; z.fill = GridBagConstraints.NONE; z.anchor = GridBagConstraints.SOUTH; z.weightx = 0;
			z.gridx = 0; p1ZonesPanel.add(p1LeftGroup,   z);
			z.gridx = 2; p1ZonesPanel.add(p1CornerPanel, z);
			z.gridx = 1; z.fill = GridBagConstraints.BOTH; z.weightx = 1.0; z.weighty = 1.0;
			p1ZonesPanel.add(p1MainArea, z);
		}

		JPanel southPanel = new JPanel(new BorderLayout());
		southPanel.add(p1ZonesPanel, BorderLayout.CENTER);

		// --- Game Board ---
		GradientPanel p2Board = new GradientPanel(true);
		GradientPanel p1Board = new GradientPanel(false);

		JSeparator divider = new JSeparator(JSeparator.HORIZONTAL);
		divider.setForeground(Color.LIGHT_GRAY);
		fieldDivider = divider;

		JPanel gameBoard = new JPanel(new GridBagLayout());
		gameBoard.setBackground(UIManager.getColor("Panel.background"));

		GridBagConstraints gbc = new GridBagConstraints();
		gbc.fill    = GridBagConstraints.BOTH;
		gbc.weightx = 1.0;
		gbc.gridx   = 0;

		gbc.weighty = 1.0; gbc.gridy = 0; gameBoard.add(p2Board,  gbc);
		gbc.weighty = 0.0; gbc.gridy = 1; gameBoard.add(divider,  gbc);
		gbc.weighty = 1.0; gbc.gridy = 2; gameBoard.add(p1Board,  gbc);


		p2ColorBox.addActionListener(e -> {
			String sel = (String) p2ColorBox.getSelectedItem();
			Color c = "Default".equals(sel) ? null : ElementColor.fromName(sel).color;
			applyElementColor(sel, p2ZonesPanel);
			p2Board.setGradientColor(c);
			AppSettings.setP2BoardColor(sel);
			AppSettings.save();
		});
		p1ColorBox.addActionListener(e -> {
			String sel = (String) p1ColorBox.getSelectedItem();
			Color c = "Default".equals(sel) ? null : ElementColor.fromName(sel).color;
			applyElementColor(sel, p1ZonesPanel);
			p1Board.setGradientColor(c);
			AppSettings.setP1BoardColor(sel);
			AppSettings.save();
		});

		p2ColorBox.setSelectedItem(AppSettings.getP2BoardColor());
		p1ColorBox.setSelectedItem(AppSettings.getP1BoardColor());

		// --- Side Panel (card preview + Next button + Game Log) ---

		// Card preview — custom-painted panel that draws previewImage at native size
		cardPreviewPanel = new JPanel() {
			@Override
			protected void paintComponent(Graphics g) {
				super.paintComponent(g);
				if (previewImage != null && previewAlpha > 0f) {
					Graphics2D g2 = (Graphics2D) g;
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION,
							RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, previewAlpha));
					int m = SIDE_MARGIN / 2;
					g2.drawImage(previewImage,
							m, m, getWidth() - m, getHeight() - m,
							0, 0, previewImage.getWidth(), previewImage.getHeight(), null);
					g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, 1f));
				}
			}
		};
		cardPreviewPanel.setPreferredSize(new Dimension(sidePanelW, previewH));
		cardPreviewPanel.setMinimumSize (new Dimension(sidePanelW, previewH));
		cardPreviewPanel.setMaximumSize (new Dimension(sidePanelW, previewH));
		cardPreviewPanel.setBackground(Color.DARK_GRAY);
		cardPreviewPanel.setBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, Color.GRAY));

		// Attack button (enabled only during P1's Attack Phase with a selection)
		attackButton = new JButton("Attack");
		attackButton.setFont(FontLoader.loadPixelNESFont(12));
		attackButton.setEnabled(false);
		attackButton.setFocusPainted(false);
		attackButton.addActionListener(e -> {
			if (p1InBlockDeclaration()) {
				// Block declaration mode: P1 commits to their block choice (or takes damage)
				handleP1BlockAction();
			} else if (!p1AttackSelection.isEmpty()) {
				List<Integer> sel = new ArrayList<>(p1AttackSelection);
				p1AttackSelection.clear();
				refreshAttackButton();
				executeP1Attack(sel);
			} else if (p1MonsterAttackIdx >= 0) {
				int monIdx = p1MonsterAttackIdx;
				p1MonsterAttackIdx = -1;
				refreshAttackButton();
				executeP1MonsterAttack(monIdx);
			}
		});

		// Skip button — ends the attack phase without declaring another attacker
		skipAttackButton = new JButton("Skip");
		skipAttackButton.setFont(FontLoader.loadPixelNESFont(12));
		skipAttackButton.setEnabled(false);
		skipAttackButton.setFocusPainted(false);
		skipAttackButton.addActionListener(e -> {
			if (attackSubStep == 1
					&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK
					&& gameState.getCurrentPlayer() == GameState.Player.P1) {
				onNextPhase();
			}
		});

		// Next-phase button, centred below the preview
		JPanel nextBtnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
		nextBtnPanel.add(nextPhaseButton);
		nextBtnPanel.add(attackButton);
		nextBtnPanel.add(skipAttackButton);

		phaseTracker = new PhaseTracker();

		JPanel sideNorth = new JPanel();
		sideNorth.setLayout(new BoxLayout(sideNorth, BoxLayout.Y_AXIS));
		sideNorth.add(cardPreviewPanel);
		sideNorth.add(phaseTracker);
		sideNorth.add(nextBtnPanel);

		// Game log (scrollable, fills the rest of the side panel)
		gameLog = new JTextArea();
		gameLog.setEditable(false);
		gameLog.setLineWrap(true);
		gameLog.setWrapStyleWord(true);
		gameLog.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));
		gameLog.setBackground(Color.WHITE);
		gameLog.setForeground(Color.BLACK);
		gameLog.setMargin(new Insets(4, 4, 4, 4));
		gameLog.setCaretColor(Color.WHITE);
		logEntry("Welcome to Shufflingway!");

		JScrollPane logScrollPane = new JScrollPane(gameLog,
				JScrollPane.VERTICAL_SCROLLBAR_AS_NEEDED,
				JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);

		// ── Chat bar ─────────────────────────────────────────────────────────
		chatInput = new JTextField();
		chatInput.setFont(new Font("Serif", Font.PLAIN, 11));
		chatInput.setEnabled(false);
		chatInput.setToolTipText("Connect to multiplayer to chat");

		chatSendBtn = new JButton("Send");
		chatSendBtn.setFont(new Font("Serif", Font.PLAIN, 11));
		chatSendBtn.setEnabled(false);

		Runnable sendChat = () -> {
			String text = chatInput.getText().trim();
			if (text.isEmpty()) return;
			GameConnection conn = multiplayerMenu == null ? null : multiplayerMenu.getActiveConnection();
			if (conn == null) return;
			conn.send(shufflingway.net.GameAction.of(ActionType.CHAT, new org.json.JSONObject().put("msg", text)));
			logEntry("[You] " + text);
			chatInput.setText("");
		};
		chatInput.addActionListener(e -> sendChat.run());
		chatSendBtn.addActionListener(e -> sendChat.run());

		JPanel chatPanel = new JPanel(new BorderLayout(4, 0));
		chatPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY));
		chatPanel.add(chatInput,   BorderLayout.CENTER);
		chatPanel.add(chatSendBtn, BorderLayout.EAST);

		JPanel logWithChat = new JPanel(new BorderLayout());
		logWithChat.add(logScrollPane, BorderLayout.CENTER);
		logWithChat.add(chatPanel,     BorderLayout.SOUTH);

		handPanel = new JPanel(null);
		handPanel.setBackground(Color.DARK_GRAY);
		handPanel.setPreferredSize(new Dimension(sidePanelW, (int)(CARD_H * 0.6)));
		handPanel.setBorder(BorderFactory.createMatteBorder(1, 0, 0, 0, Color.GRAY));
		handPanel.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent e) {
				if (!gameState.getP1Hand().isEmpty()) showHandPopup();
			}
			@Override public void mouseExited(MouseEvent e) { scheduleHandPopupHide(); }
		});
		refreshHandPanel();

		sidePanel = new JPanel(new BorderLayout());
		sidePanel.setPreferredSize(new Dimension(sidePanelW, 0));
		sidePanel.add(sideNorth,    BorderLayout.NORTH);
		sidePanel.add(logWithChat,  BorderLayout.CENTER);
		sidePanel.add(handPanel,    BorderLayout.SOUTH);

		// Draggable divider between game board and side panel
		resizeHandle = new JPanel();
		resizeHandle.setPreferredSize(new Dimension(RESIZE_HANDLE_W, 0));
		resizeHandle.setBackground(Color.LIGHT_GRAY);
		MouseAdapter sideResizer = new MouseAdapter() {
			private int pressScreenX;
			private int pressW;
			@Override public void mousePressed(MouseEvent e) {
				pressScreenX = e.getXOnScreen();
				pressW = sidePanel.getWidth();
			}
			@Override public void mouseDragged(MouseEvent e) {
				if (nativeImgW == 0) return;
				int dx = e.getXOnScreen() - pressScreenX;
				boolean right = "right".equals(AppSettings.getSidePanelSide());
				int newW = right ? pressW - dx : pressW + dx;
				newW = Math.max(minSidePanelW, Math.min(maxSidePanelW, newW));
				setSidePanelWidth(newW);
			}
		};
		resizeHandle.addMouseListener(sideResizer);
		resizeHandle.addMouseMotionListener(sideResizer);

		// --- Main game area (wraps both player zones + board so the side panel
		//     spans the full frame height rather than just the centre strip) ---
		JPanel mainArea = new JPanel(new BorderLayout());
		mainArea.add(p2ZonesPanel, BorderLayout.NORTH);
		mainArea.add(southPanel,   BorderLayout.SOUTH);
		mainArea.add(gameBoard,    BorderLayout.CENTER);

		// --- Assemble ---
		frame.getContentPane().add(mainArea, BorderLayout.CENTER);
		applySidePanelSide(AppSettings.getSidePanelSide());
		cardSlideAnimator = CardSlideAnimator.install(frame);
		breakAnimator     = CardBreakAnimator.install(frame);
	}

	// -------------------------------------------------------------------------
	// Side panel docking
	// -------------------------------------------------------------------------

	/**
	 * Docks the side info panel to the left or right of the frame.
	 * Safe to call at any time after {@code initialize()} — removes the panel
	 * from its current position, flips its separator border, then re-adds it.
	 *
	 * @param side {@code "left"} or {@code "right"}
	 */
	private void applySidePanelSide(String side) {
		if (sidePanel == null) return;
		if (sideWrapper != null) frame.getContentPane().remove(sideWrapper);
		boolean right = "right".equals(side);
		sidePanel.setBorder(null);
		resizeHandle.setCursor(Cursor.getPredefinedCursor(
				right ? Cursor.W_RESIZE_CURSOR : Cursor.E_RESIZE_CURSOR));
		sideWrapper = new JPanel(new BorderLayout());
		sideWrapper.setPreferredSize(new Dimension(sidePanelW + RESIZE_HANDLE_W, 0));
		if (right) {
			sideWrapper.add(resizeHandle, BorderLayout.WEST);
			sideWrapper.add(sidePanel,    BorderLayout.CENTER);
		} else {
			sideWrapper.add(sidePanel,    BorderLayout.CENTER);
			sideWrapper.add(resizeHandle, BorderLayout.EAST);
		}
		frame.getContentPane().add(sideWrapper, right ? BorderLayout.EAST : BorderLayout.WEST);
		frame.revalidate();
		frame.repaint();
	}

	// -------------------------------------------------------------------------
	// Game startup
	// -------------------------------------------------------------------------

	private void startGame(int deckId, int p2DeckId) {
		gameState.reset();
		endOfTurnEffects.clear();
		pendingMainPhase1Effects.clear();
		activeCostReductions.clear();
		computerPlayer = new ComputerPlayer();
		clearUIZones();
		if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
		if (gameLog != null) gameLog.setText("");
		logEntry("Game Start");
		refreshP1HandLabel();

		new SwingWorker<Void, Void>() {
			List<DeckCardDetail> p1Cards;
			List<DeckCardDetail> p2Cards;
			String               p2DeckName;

			@Override
			protected Void doInBackground() throws Exception {
				try (DeckDatabase db = new DeckDatabase()) {
					p1Cards = db.getDeckCardsDetailed(deckId);

					scraper.DeckDatabase.DeckSummary p2Summary = db.getDecksSummary()
							.stream()
							.filter(d -> d.id() == p2DeckId)
							.findFirst()
							.orElseThrow();
					p2DeckName = p2Summary.name();
					p2Cards    = db.getDeckCardsDetailed(p2DeckId);
				}
				return null;
			}

			@Override
			protected void done() {
				try {
					get(); // surface any exception
				} catch (InterruptedException | ExecutionException ex) {
					JOptionPane.showMessageDialog(frame, "Error loading deck:\n" + ex.getMessage(),
							"Error", JOptionPane.ERROR_MESSAGE);
					return;
				}

				List<CardData> main = new ArrayList<>();
				List<CardData> lb   = new ArrayList<>();
				for (DeckCardDetail card : p1Cards) {
					String tx = card.textEn();
					CardData cd = new CardData(card.imageUrl(), card.name(), card.element(),
							card.cost(), card.power(), card.type(), card.isLb(), card.lbCost(), card.exBurst(),
							card.multicard(), CardData.parseTraits(tx),
							CardData.parseWarpValue(tx), CardData.parseWarpCost(tx),
							CardData.parsePrimingTarget(tx), CardData.parsePrimingCost(tx),
							CardData.parseActionAbilities(tx), CardData.parseAutoAbilities(tx),
							CardData.parseFieldAbilities(tx, card.type()),
							CardData.parseIfControlBoosts(tx, card.type()),
							CardData.parseFieldPowerGrants(tx, card.type()),
							CardData.parseFieldCostReductions(tx, card.type()),
							CardData.parseFieldPrimingAnyElements(tx, card.type()),
							CardData.parseWarpCostAnyElement(tx),
							card.job(), card.category1(), card.category2(), tx);
					if (card.isLb()) lb.add(cd);
					else             main.add(cd);
				}
				gameState.initializeDeck(main, lb);
				refreshP1DeckLabel();
				refreshP1LimitLabel();
				drawOpeningHand();

				List<CardData> p2Main = new ArrayList<>();
				List<CardData> p2Lb   = new ArrayList<>();
				for (DeckCardDetail card : p2Cards) {
					String tx = card.textEn();
					CardData cd = new CardData(card.imageUrl(), card.name(), card.element(),
							card.cost(), card.power(), card.type(), card.isLb(), card.lbCost(), card.exBurst(),
							card.multicard(), CardData.parseTraits(tx),
							CardData.parseWarpValue(tx), CardData.parseWarpCost(tx),
							CardData.parsePrimingTarget(tx), CardData.parsePrimingCost(tx),
							CardData.parseActionAbilities(tx), CardData.parseAutoAbilities(tx),
							CardData.parseFieldAbilities(tx, card.type()),
							CardData.parseIfControlBoosts(tx, card.type()),
							CardData.parseFieldPowerGrants(tx, card.type()),
							CardData.parseFieldCostReductions(tx, card.type()),
							CardData.parseFieldPrimingAnyElements(tx, card.type()),
							CardData.parseWarpCostAnyElement(tx),
							card.job(), card.category1(), card.category2(), tx);
					if (card.isLb()) p2Lb.add(cd);
					else             p2Main.add(cd);
				}
				gameState.initializeP2Deck(p2Main);
				gameState.initializeP2LbDeck(p2Lb);
				refreshP2DeckLabel();
				refreshP2HandCountLabel();
				refreshP2LimitButton();
				logEntry("P2 deck: " + p2DeckName);
			}
		}.execute();
	}

	// -------------------------------------------------------------------------
	// P1 deck interaction
	// -------------------------------------------------------------------------

	private void onP1DeckClicked() {
	}

	private void drawOpeningHand() {
		List<CardData> drawn = gameState.drawOpeningHand();
		refreshP1DeckLabel();
		logEntry("Drew opening hand (" + drawn.size() + " cards)");
		showOpeningHandPopup(drawn, !gameState.isP1MulliganUsed());
	}

	/**
	 * Shows the opening hand popup.
	 *
	 * @param cards             the 5 cards to display
	 * @param mulliganAvailable whether the Mulligan button should be enabled
	 */
	private void showOpeningHandPopup(List<CardData> cards, boolean mulliganAvailable) {
		if (openingHandPopup != null) openingHandPopup.dispose();
		openingHandPopup = new JWindow(frame);

		// Mutable display order — swapped in-place when player reorders
		List<CardData> handOrder = new ArrayList<>(cards);

		// ── Card labels ──────────────────────────────────────────────────────
		JLabel[] cardLabels = new JLabel[handOrder.size()];
		int[] selectedIdx = { -1 };  // -1 = nothing selected

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));

		for (int i = 0; i < handOrder.size(); i++) {
			final int idx = i;
			JLabel lbl = new JLabel("Loading...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setForeground(Color.WHITE);
			lbl.setFont(FontLoader.loadPixelNESFont(10));
			lbl.setHorizontalAlignment(SwingConstants.CENTER);

			lbl.addMouseListener(new MouseAdapter() {
				@Override
				public void mousePressed(MouseEvent e) {
					if (!mulliganAvailable) return;
					if (selectedIdx[0] == -1) {
						// Select this card
						selectedIdx[0] = idx;
						cardLabels[idx].setBorder(createCardGlowBorder(Color.YELLOW));
					} else if (selectedIdx[0] == idx) {
						// Deselect
						selectedIdx[0] = -1;
						cardLabels[idx].setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
					} else {
						// Swap the two cards
						int other = selectedIdx[0];

						CardData tmpCard = handOrder.get(idx);
						handOrder.set(idx, handOrder.get(other));
						handOrder.set(other, tmpCard);

						javax.swing.Icon tmpIcon = cardLabels[idx].getIcon();
						String tmpText = cardLabels[idx].getText();
						cardLabels[idx].setIcon(cardLabels[other].getIcon());
						cardLabels[idx].setText(cardLabels[other].getText());
						cardLabels[other].setIcon(tmpIcon);
						cardLabels[other].setText(tmpText);

						cardLabels[idx].setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
						cardLabels[other].setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
						selectedIdx[0] = -1;
					}
				}
				@Override
				public void mouseEntered(MouseEvent e) {
					showZoomAt(handOrder.get(idx).imageUrl());
				}
				@Override
				public void mouseExited(MouseEvent e) {
					hideZoom();
				}
			});

			cardLabels[i] = lbl;
			cardsPanel.add(lbl);
		}

		// Load card images asynchronously
		for (int i = 0; i < handOrder.size(); i++) {
			final int idx = i;
			final String url = handOrder.get(i).imageUrl();
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(url);
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try {
						ImageIcon icon = get();
						if (icon != null) { cardLabels[idx].setIcon(icon); cardLabels[idx].setText(null); }
					} catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();
		}

		// ── Instructions label ───────────────────────────────────────────────
		JLabel instructions = new JLabel(
				mulliganAvailable ? "Click a card to select it, then click another to swap positions." : " ",
				SwingConstants.CENTER);
		instructions.setFont(FontLoader.loadPixelNESFont(10));

		// ── Buttons ──────────────────────────────────────────────────────────
		JButton keepBtn = new JButton(mulliganAvailable ? "Keep Hand" : "Take Hand");
		keepBtn.setFont(FontLoader.loadPixelNESFont(11));
		keepBtn.addActionListener(e -> {
			hideZoom();
			openingHandPopup.dispose();
			openingHandPopup = null;
			if (mulliganAvailable) logEntry("Kept opening hand");
			gameState.keepHand(handOrder);
			boolean p1GoesFirst = Math.random() < 0.5;
			GameState.Player firstPlayer = p1GoesFirst
					? GameState.Player.P1 : GameState.Player.P2;
			gameState.startFirstTurn(firstPlayer);
			refreshPhaseTracker();
			refreshP1HandLabel();
			if (p1GoesFirst) {
				logEntry("Coin flip: You go first!");
				logEntry("Turn 1 — Active Phase");
				if (nextPhaseButton != null) nextPhaseButton.setEnabled(true);
				onNextPhase();
			} else {
				logEntry("Coin flip: Opponent goes first!");
				if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
				computerPlayer.runTurn();
			}
		});

		JButton mulliganBtn = new JButton("Mulligan");
		mulliganBtn.setFont(FontLoader.loadPixelNESFont(11));
		mulliganBtn.setEnabled(mulliganAvailable);
		mulliganBtn.setToolTipText(mulliganAvailable
				? "Put these cards on the bottom (in this order) and draw 5 new cards"
				: "Mulligan already used");
		mulliganBtn.addActionListener(e -> {
			hideZoom();
			logEntry("Took mulligan");
			// handOrder is the player's chosen bottom-of-deck order
			List<CardData> newCards = gameState.mulligan(new ArrayList<>(handOrder));
			refreshP1DeckLabel();
			showOpeningHandPopup(newCards, false);
		});

		JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonsPanel.add(keepBtn);
		buttonsPanel.add(mulliganBtn);

		// ── Assemble ─────────────────────────────────────────────────────────
		JLabel titleLabel = new JLabel("Opening Hand", SwingConstants.CENTER);
		titleLabel.setFont(FontLoader.loadPixelNESFont(14));

		JPanel bottomPanel = new JPanel(new BorderLayout(0, 2));
		bottomPanel.add(instructions, BorderLayout.NORTH);
		bottomPanel.add(buttonsPanel,  BorderLayout.SOUTH);

		JPanel mainPanel = new JPanel(new BorderLayout(0, 6));
		mainPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createRaisedBevelBorder(),
				BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		mainPanel.add(titleLabel,  BorderLayout.NORTH);
		mainPanel.add(cardsPanel,  BorderLayout.CENTER);
		mainPanel.add(bottomPanel, BorderLayout.SOUTH);

		openingHandPopup.getContentPane().add(mainPanel);
		openingHandPopup.pack();

		// Centre on screen
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		openingHandPopup.setLocation(
				(screen.width  - openingHandPopup.getWidth())  / 2,
				(screen.height - openingHandPopup.getHeight()) / 2);
		openingHandPopup.setVisible(true);
	}

	/**
	 * Native {@link JOptionPane} effect prompt, but centered where the two fields meet (like the
	 * opening-hand popup) rather than over the whole window.  Returns the index of the chosen
	 * option, or {@link JOptionPane#CLOSED_OPTION} if dismissed.
	 */
	private int showEffectOptionDialog(String message, String title, Object[] options) {
		JOptionPane pane = new JOptionPane(message, JOptionPane.PLAIN_MESSAGE,
				JOptionPane.DEFAULT_OPTION, null, options, options[0]);
		JDialog dlg = pane.createDialog(frame, title);
		positionAtFieldDivider(dlg);
		dlg.setVisible(true);
		dlg.dispose();
		Object val = pane.getValue();
		for (int i = 0; i < options.length; i++) if (options[i].equals(val)) return i;
		return JOptionPane.CLOSED_OPTION;
	}

	/** Centers {@code w} on the point where the two fields meet, falling back to screen center. */
	private void positionAtFieldDivider(java.awt.Window w) {
		int cx, cy;
		if (fieldDivider != null && fieldDivider.isShowing()) {
			java.awt.Point p = fieldDivider.getLocationOnScreen();
			cx = p.x + fieldDivider.getWidth()  / 2;
			cy = p.y + fieldDivider.getHeight() / 2;
		} else {
			Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
			cx = screen.width  / 2;
			cy = screen.height / 2;
		}
		w.setLocation(cx - w.getWidth() / 2, cy - w.getHeight() / 2);
	}

	private void refreshP1HandLabel() {
		refreshHandPanel();
	}

	private void refreshHandPanel() {
		if (handPanel == null) return;
		handPanel.removeAll();
		int n = gameState.getP1Hand().size();
		String text = n == 0 ? "HAND" : "HAND -" + n + "-";
		JLabel lbl = new JLabel(text, SwingConstants.CENTER);
		lbl.setFont(FontLoader.loadPixelNESFont(14));
		lbl.setForeground(Color.LIGHT_GRAY);
		int handH = handPanel.getHeight() > 0 ? handPanel.getHeight() : (int)(CARD_H * 0.6);
		lbl.setBounds(0, 0, handPanel.getWidth() > 0 ? handPanel.getWidth() : sidePanelW, handH);
		handPanel.add(lbl);
		handPanel.revalidate();
		handPanel.repaint();
	}


	private void refreshP1DeckLabel() {
		int count = gameState.getP1MainDeck().size();
		if (count == 0) {
			p1DeckLabel.setIcon(null);
			p1DeckLabel.setText("DECK");
		} else {
			p1DeckLabel.setIcon(scaledCardbackWithCount(new Dimension(CARD_W, CARD_H), count));
			p1DeckLabel.setText(null);
		}
	}

	private void refreshP2DeckLabel() {
		if (p2DeckLabel == null) return;
		int count = gameState.getP2MainDeck().size();
		if (count == 0) {
			p2DeckLabel.setIcon(null);
			p2DeckLabel.setText("DECK");
		} else {
			p2DeckLabel.setIcon(scaledCardbackWithCount(new Dimension(CARD_W, CARD_H), count));
			p2DeckLabel.setText(null);
		}
	}

	private void refreshP2HandCountLabel() {
		if (p2HandCountLabel == null) return;
		p2HandCountLabel.setText("P2 Hand: " + gameState.getP2Hand().size());
	}

	// -------------------------------------------------------------------------
	// Phase management
	// -------------------------------------------------------------------------

	/**
	 * Called when the player clicks the "Next" button.
	 * Executes any automatic actions for the phase being left, advances the
	 * phase in GameState, and logs the transition to the game log.
	 *
	 * <ul>
	 *   <li>ACTIVE  → DRAW   : activate dull cards, draw 1 (turn 1) or 2 cards</li>
	 *   <li>DRAW    → MAIN_1 : nothing automatic</li>
	 *   <li>MAIN_1  → ATTACK : nothing automatic</li>
	 *   <li>ATTACK  → MAIN_2 : nothing automatic</li>
	 *   <li>MAIN_2  → END    : nothing automatic</li>
	 *   <li>END     → ACTIVE : increment turn, immediately activate cards</li>
	 * </ul>
	 */
	private void onNextPhase() {
		if (gameState.isP1GameOver()) return;
		if (gameState.getCurrentPlayer() == GameState.Player.P2) return;
		GameState.GamePhase current = gameState.getCurrentPhase();
		if (current == null) return;

		switch (current) {

			case ACTIVE ->  {
				usedOncePerTurnAbilities.clear();
				// Advance first so getTurnNumber() still reflects the current turn
				gameState.advancePhase();   // ACTIVE → DRAW
				refreshPhaseTracker();
				int drawCount = gameState.getTurnNumber() == 1 ? 1 : 2;
				List<CardData> drawn = gameState.drawToHand(drawCount);
				animateCardDraw(true, drawn.size());
				refreshP1HandLabel();
				refreshP1DeckLabel();
				logEntry("Draw Phase — Drew " + drawn.size()
						+ " card" + (drawn.size() != 1 ? "s" : ""));
				if (drawn.size() < drawCount) {
					triggerGameOver("Milled Out - You Lose!");
					return;
				}
				// No choices to make during Draw phase — advance automatically
				onNextPhase();
			}

			case DRAW -> {
                            gameState.advancePhase();   // DRAW → MAIN_1
                            refreshPhaseTracker();
                            logEntry("Main Phase 1");
                            processWarpCounters();
                            if (!pendingMainPhase1Effects.isEmpty()) {
                                List<Consumer<GameContext>> pending = new ArrayList<>(pendingMainPhase1Effects);
                                pendingMainPhase1Effects.clear();
                                GameContext ctx = buildGameContext(true);
                                pending.forEach(e -> e.accept(ctx));
                            }
            }

			case MAIN_1 -> {
                            p1AttackSelection.clear();
                            p1MonsterAttackIdx = -1;
                            gameState.advancePhase();   // MAIN_1 → ATTACK
                            logEntry("Attack Phase");
                            refreshAllForwardSlots();
                            if (!hasAttackableForward() && !hasBackAttackInHand()) {
                                logEntry("No attackers available — skipping to Main Phase 2");
                                onNextPhase();
                                return;
                            }
                            // Sub-step 0: Attack Preparation — P1 has priority first
                            setAttackSubStep(0);
                            if (nextPhaseButton != null) nextPhaseButton.setEnabled(true);
                            refreshPhaseTracker();
                            refreshAttackButton();
                            logEntry("Attack Preparation — use abilities or click Next to pass priority.");
            }

			case ATTACK -> {
                            if (combatPriorityTimer  != null) { combatPriorityTimer.stop();    combatPriorityTimer  = null; }
                            if (combatPriorityWindow != null) { combatPriorityWindow.dispose(); combatPriorityWindow = null; }
                            if (p2AutoPassTimer      != null) { p2AutoPassTimer.stop();         p2AutoPassTimer      = null; }

                            if (attackSubStep == 0) {
                                // P1 passed priority — opponent auto-passes, then declare attackers
                                p2AutoPass(() -> {
                                    setAttackSubStep(1);
                                    refreshPhaseTracker();
                                    refreshAttackButton();
                                    refreshAllForwardSlots();
                                    logEntry("Declare an attacker, or click Skip to end the Attack Phase.");
                                });
                                return;
                            }

                            // ATTACK → MAIN_2 (all attacks finished or skipped)
                            p1AttackSelection.clear();
                            attackSubStep = -1;
                            if (skipAttackButton != null) skipAttackButton.setEnabled(false);
                            if (nextPhaseButton != null) nextPhaseButton.setEnabled(true);
                            refreshAttackButton();
                            gameState.advancePhase();   // ATTACK → MAIN_2
                            refreshPhaseTracker();
                            refreshAllForwardSlots();
                            logEntry("Main Phase 2");
			}

			case MAIN_2 -> {
                            gameState.advancePhase();   // MAIN_2 → END
                            refreshPhaseTracker();
                            logEntry("End Phase");
                            fireFieldEndOfTurnAbilities(true);
                            fireEndOfTurnEffects(true);
                            for (int i = 0; i < p1ForwardDamage.size(); i++) p1ForwardDamage.set(i, 0);
                            for (int i = 0; i < p1ForwardPowerBoost.size(); i++) p1ForwardPowerBoost.set(i, 0);
                            for (int i = 0; i < p1ForwardPowerReduction.size(); i++) p1ForwardPowerReduction.set(i, 0);
                            p1ForwardTempTraits.forEach(java.util.EnumSet::clear);
                            p1ForwardRemovedTraits.forEach(java.util.EnumSet::clear);
                            java.util.Collections.fill(p1ForwardTempJobs, null);
                            for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
                            for (int i = 0; i < p2ForwardDamage.size(); i++) p2ForwardDamage.set(i, 0);
                            for (int i = 0; i < p2ForwardPowerBoost.size(); i++) p2ForwardPowerBoost.set(i, 0);
                            for (int i = 0; i < p2ForwardPowerReduction.size(); i++) p2ForwardPowerReduction.set(i, 0);
                            p2ForwardTempTraits.forEach(java.util.EnumSet::clear);
                            p2ForwardRemovedTraits.forEach(java.util.EnumSet::clear);
                            java.util.Collections.fill(p2ForwardTempJobs, null);
                            p1ForwardCannotBeBlocked.clear();       p2ForwardCannotBeBlocked.clear();
                            p1ForwardCannotBeBlockedByCost.clear(); p2ForwardCannotBeBlockedByCost.clear();
                            p1ForwardCannotBlock.clear();           p2ForwardCannotBlock.clear();
                            p1ForwardMustBlock.clear();             p2ForwardMustBlock.clear();
                            p1ForwardCannotAttack.clear();          p2ForwardCannotAttack.clear();
                            p1ForwardMustAttack.clear();            p2ForwardMustAttack.clear();
                            p1ForwardCannotAttackPersistent.clear(); p1ForwardCannotBlockPersistent.clear();
                            p1TempAttackTriggers.clear();           p2TempAttackTriggers.clear();
                            p1TempBlockTriggers.clear();            p2TempBlockTriggers.clear();
                            nextIncomingDmgZeroSet.clear();   nextIncomingDmgReduceMap.clear();   nextAbilityDmgReduceMap.clear();
                            incomingDmgIncreaseMap.clear();   nullifyAbilityDmgSet.clear();
                            nullifyAbilityOnlyDmgSet.clear(); perCardNonLethalDmgSet.clear();
                            nextOutgoingDmgZeroSet.clear();
                            cannotBeChosenBySummons.clear();  cannotBeChosenByAbilities.clear();
                            cannotBeBrokenSet.clear();        cannotBeBrokenByNonDmgSet.clear();  breaktouchBattleSet.clear();
                            p1NonLethalProtection = false;    p2NonLethalProtection = false;
                            p1DmgReductionDisabled = false;   p2DmgReductionDisabled = false;
                            p1GlobalDmgReduction  = 0;        p2GlobalDmgReduction  = 0;
                            for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
                            showEndPhaseDiscardDialog();
                            onNextPhase();             // END → ACTIVE (auto-advance)
            }

			case END ->  {
				if (p1ExtraTurnThenLose) {
					p1ExtraTurnThenLose = false;
					logEntry("Extra Turn — P1 takes one additional turn");
					gameState.advancePhaseExtraTurn(); // END → ACTIVE, same player
					refreshPhaseTracker();
					nextPhaseButton.setEnabled(true);
					endOfTurnEffects.add(ctx -> triggerGameOver("Extra Turn ended — You Lose!"));
					onNextPhase(); // begin ACTIVE → DRAW automatically
				} else {
					// END → ACTIVE: increments turn number and switches to P2
					gameState.advancePhase();
					refreshPhaseTracker();
					for (int i = 0; i < p1MonsterCards.size(); i++) refreshP1MonsterSlot(i);
					for (int i = 0; i < p2MonsterCards.size(); i++) refreshP2MonsterSlot(i);
					nextPhaseButton.setEnabled(false);
					computerPlayer.runTurn();
				}
			}
		}
	}

	private void refreshPhaseTracker() {
		if (phaseTracker == null || gameState.getCurrentPhase() == null) return;
		phaseTracker.setState(
			PhaseTracker.PHASES[gameState.getCurrentPhase().ordinal()],
			gameState.getTurnNumber(),
			gameState.getCurrentPlayer() == GameState.Player.P1
		);
		if (gameState.getCurrentPhase() == GameState.GamePhase.ATTACK && attackSubStep >= 0)
			phaseTracker.setAttackStep(attackSubStep);
	}

	/** Appends a timestamped entry to the game log. */
	private void logEntry(String text) {
		if (gameLog == null) return;
		String time = java.time.LocalTime.now()
				.format(java.time.format.DateTimeFormatter.ofPattern("HH:mm:ss"));
		gameLog.append(time + "  " + text + "\n");
		gameLog.setCaretPosition(gameLog.getDocument().getLength());
	}

	/** Resets all interactive UI zones to their empty state for a new game. */
	private void clearUIZones() {
		// Backup slots
		for (int i = 0; i < p1BackupLabels.length; i++) {
			if (p1BackupLabels[i] != null) {
				p1BackupLabels[i].setIcon(null);
				p1BackupLabels[i].setText(null);
			}
			p1BackupUrls[i]   = null;
			p1BackupCards[i]  = null;
			p1BackupStates[i] = CardState.ACTIVE;
		}

		// Forward zone
		if (p1ForwardPanel != null) {
			p1ForwardPanel.removeAll();
			p1ForwardPanel.revalidate();
			p1ForwardPanel.repaint();
		}
		p1ForwardLabels.clear();
		p1ForwardUrls.clear();
		p1ForwardCards.clear();
		p1ForwardStates.clear();
		p1ForwardPlayedOnTurn.clear();
		p1ForwardDamage.clear();
		p1ForwardPowerBoost.clear();
		p1ForwardPowerReduction.clear();
		p1ForwardTempTraits.clear();
		p1ForwardRemovedTraits.clear();
		p1ForwardTempJobs.clear();
		p1ForwardPrimedTop.clear();
		p1ForwardFrozen.clear();
		p1MonsterFrozen.clear();
		p1AttackSelection.clear();
		java.util.Arrays.fill(p1BackupPlayedOnTurn, 0);
		java.util.Arrays.fill(p1BackupFrozen, false);
		lastCastPaymentDistinctElements = 0;

		// Monster zone
		if (p1MonsterPanel != null) {
			p1MonsterPanel.removeAll();
			p1MonsterPanel.revalidate();
			p1MonsterPanel.repaint();
		}
		p1MonsterLabels.clear();
		p1MonsterUrls.clear();
		p1MonsterCards.clear();
		p1MonsterStates.clear();
		p1MonsterPlayedOnTurn.clear();
		p1MonsterDamage.clear();
		p1MonsterAttackIdx = -1;
		p1MonsterTempForwardPower.clear();

		if (p2MonsterPanel != null) {
			p2MonsterPanel.removeAll();
			p2MonsterPanel.revalidate();
			p2MonsterPanel.repaint();
		}
		p2MonsterLabels.clear();
		p2MonsterUrls.clear();
		p2MonsterCards.clear();
		p2MonsterStates.clear();
		p2MonsterPlayedOnTurn.clear();
		p2MonsterDamage.clear();
		p2MonsterTempForwardPower.clear();
		p2MonsterFrozen.clear();
		spentLbIndices.clear();
		p2SpentLbIndices.clear();

		// Damage zone
		if (p1DamageSlotPanel != null) {
			p1DamageSlotPanel.putClientProperty("exBurst", Boolean.FALSE);
			p1DamageSlotPanel.repaint();
		}
		for (JPanel slot : p1DamageSlots) {
			if (slot != null) {
				slot.putClientProperty("cardImg", null);
				slot.putClientProperty("isExBurst", null);
				slot.repaint();
			}
		}

		// Break zone labels
		refreshP1BreakLabel();
		refreshP2BreakLabel();

		// Limit labels
		refreshP1LimitLabel();
		refreshP2LimitButton();

		// Removed from play labels
		p1RemoveLabel.setIcon(null);
		p1RemoveLabel.setUrl(null);
		p2RemoveLabel.setIcon(null);
		p2RemoveLabel.setUrl(null);
		refreshRemoveButtons();

		// Crystal badges — hard-reset so the display is hidden until crystals are earned
		if (p1CrystalDisplay != null) p1CrystalDisplay.hardReset();
		if (p2CrystalDisplay != null) p2CrystalDisplay.hardReset();

		// P2 backup slots
		for (int i = 0; i < p2BackupCards.length; i++) {
			if (p2BackupLabels[i] != null) {
				p2BackupLabels[i].setIcon(null);
				p2BackupLabels[i].setText(null);
			}
			p2BackupUrls[i]    = null;
			p2BackupCards[i]   = null;
			p2BackupStates[i]  = CardState.ACTIVE;
			p2BackupFrozen[i]  = false;
		}

		// P2 forward zone
		if (p2ForwardPanel != null) {
			p2ForwardPanel.removeAll();
			p2ForwardPanel.revalidate();
			p2ForwardPanel.repaint();
		}
		p2ForwardLabels.clear();
		p2ForwardUrls.clear();
		p2ForwardCards.clear();
		p2ForwardStates.clear();
		p2ForwardPlayedOnTurn.clear();
		p2ForwardDamage.clear();
		p2ForwardPowerBoost.clear();
		p2ForwardPowerReduction.clear();
		p2ForwardTempTraits.clear();
		p2ForwardRemovedTraits.clear();
		p2ForwardTempJobs.clear();
		p2ForwardPrimedTop.clear();
		p2ForwardFrozen.clear();
		java.util.Arrays.fill(p2BackupFrozen, false);

		// Reset P2 damage zone display
		p2DamageCount = 0;
		for (JPanel slot : p2DamageSlots) {
			if (slot != null) {
				slot.putClientProperty("cardImg", null);
				slot.putClientProperty("isExBurst", null);
				slot.repaint();
			}
		}
		refreshP2DeckLabel();
		refreshP2HandCountLabel();
	}

	// -------------------------------------------------------------------------
	// P1 LB deck interaction
	// -------------------------------------------------------------------------

	private void refreshP1LimitLabel() {
		int total    = gameState.getP1LbDeck().size();
		int playable = total - spentLbIndices.size();
		if (total == 0) {
			p1LimitLabel.setText("LIMIT");
			p1LimitLabel.setForeground(new Color(80, 65, 20));
		} else {
			p1LimitLabel.setText("LIMIT -" + playable + "-");
			p1LimitLabel.setForeground(Color.BLACK);
		}
	}


	private void refreshP2LimitButton() {
		if (p2LimitButton == null) return;
		int total    = gameState.getP2LbDeck().size();
		int playable = total - p2SpentLbIndices.size();
		if (total == 0) {
			p2LimitButton.setText("LIMIT");
			p2LimitButton.setForeground(new Color(80, 65, 20));
		} else {
			p2LimitButton.setText("LIMIT -" + playable + "-");
			p2LimitButton.setForeground(Color.BLACK);
		}
	}

	/** Shows P2's LB deck: cardback for unplayed cards, face-up for spent ones. */
	private void showP2LbViewerDialog() {
		List<CardData> lbDeck = gameState.getP2LbDeck();
		if (lbDeck.isEmpty()) {
			JOptionPane.showMessageDialog(frame, "P2 has no LB cards.",
					"P2 Limit Break Deck", JOptionPane.INFORMATION_MESSAGE);
			return;
		}

		JDialog dlg = new JDialog(frame, "P2 Limit Break Deck (" + lbDeck.size() + " cards)", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		for (int i = 0; i < lbDeck.size(); i++) {
			final int idx   = i;
			final CardData cd = lbDeck.get(i);
			boolean spent   = p2SpentLbIndices.contains(idx);

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			if (spent) {
				lbl.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) { showZoomAt(cd.imageUrl()); }
					@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				});
			} else {
				lbl.setBorder(createCardGlowBorder(new Color(212, 175, 55)));
			}

			new SwingWorker<ImageIcon, Void>() {
				final boolean loadFace = spent;
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = loadFace ? ImageCache.load(cd.imageUrl()) : loadCardbackImage();
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try {
						ImageIcon icon = get();
						if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
					} catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());
			JLabel nameLabel = new JLabel(spent ? cd.name() : "???", SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
			wrapper.add(lbl,       BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		JScrollPane scrollPane = new JScrollPane(cardsPanel,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(
				Math.min(lbDeck.size() * (CARD_W + 16) + 16, 900), CARD_H + 60));

		JButton closeBtn = new JButton("Close");
		closeBtn.setFont(FontLoader.loadPixelNESFont(11));
		closeBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		south.add(closeBtn);
		south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
		dlg.getContentPane().add(south,      BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	private void refreshRemoveButtons() {
		if (p1RemoveButton != null)
			p1RemoveButton.setEnabled(!gameState.getP1WarpZone().isEmpty()
					|| !gameState.getP1PermanentRfp().isEmpty());
		if (p2RemoveButton != null)
			p2RemoveButton.setEnabled(p2RemoveLabel != null && p2RemoveLabel.getUrl() != null);
	}

	/** Updates the P1 RFP label to show the most recently added removed card (warp or permanent). */
	private void refreshP1WarpZoneUI() {
		List<GameState.WarpEntry> zone = gameState.getP1WarpZone();
		List<CardData>            perm = gameState.getP1PermanentRfp();
		if (zone.isEmpty() && perm.isEmpty()) {
			p1RemoveLabel.setIcon(null);
			p1RemoveLabel.setUrl(null);
			refreshRemoveButtons();
			return;
		}
		// Prefer the last-added permanent RFP card for the label; fall back to last warp card
		String url = !perm.isEmpty()
				? perm.get(perm.size() - 1).imageUrl()
				: zone.get(zone.size() - 1).card.imageUrl();
		p1RemoveLabel.setUrl(url);
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image img = ImageCache.load(url);
				return img == null ? null
						: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
			}
			@Override protected void done() {
				try { ImageIcon ic = get(); if (ic != null) { p1RemoveLabel.setIcon(ic); } }
				catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
		refreshRemoveButtons();
	}

	/**
	 * Decrements Warp counters on every card in P1's warp zone at the start of Main Phase 1.
	 * Cards whose counter hits 0 are pushed onto the Stack as auto-abilities and resolved
	 * to the field.
	 */
	private void processWarpCounters() {
		List<GameState.WarpEntry> zone = gameState.getP1WarpZone();
		if (zone.isEmpty()) return;

		// Log the decrement and fire counter-removed triggers before we tick
		for (GameState.WarpEntry entry : zone) {
			int before = entry.counters;
			int after  = before - 1;
			logEntry("Warp: \"" + entry.card.name() + "\" counter " + before + " → " + after
					+ (after == 0 ? " (resolving!)" : ""));
			triggerAutoAbilitiesForWarpCounterRemoved(entry.card);
		}

		List<CardData> resolved = gameState.tickP1WarpCounters();
		for (CardData card : resolved) {
			logEntry("Warp: \"" + card.name() + "\" enters play (auto-ability)");
			lastCardWarpedIn = true;
			try {
				if (card.isForward()) {
					placeCardInForwardZone(card);
				} else if (card.isBackup()) {
					if (hasAvailableBackupSlot()) placeCardInFirstBackupSlot(card);
					else {
						addToP1BreakZone(card);
						logEntry("  No backup slot — \"" + card.name() + "\" → Break Zone");
					}
				} else if (card.isMonster()) {
					placeCardInMonsterZone(card);
				}
			} finally {
				lastCardWarpedIn = false;
			}
		}
		if (!resolved.isEmpty()) refreshP1BreakLabel();
		refreshP1WarpZoneUI();
	}

	private void showRemovedFromPlayDialog(GrayscaleLabel removeLabel, String player) {
		List<GameState.WarpEntry> warpZone = gameState.getP1WarpZone();
		List<CardData>            permZone = gameState.getP1PermanentRfp();
		if (warpZone.isEmpty() && permZone.isEmpty()) return;

		int total = warpZone.size() + permZone.size();
		JDialog dlg = new JDialog(frame, player + " — Removed From Play (" + total
				+ " card" + (total != 1 ? "s" : "") + ")", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		// Warp zone cards (show remaining counter)
		for (GameState.WarpEntry entry : warpZone) {
			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());
			JLabel lbl = makeRfpCardLabel(entry.card.imageUrl());
			JLabel info = new JLabel(entry.card.name() + "  [" + entry.counters + "]", SwingConstants.CENTER);
			info.setFont(FontLoader.loadPixelNESFont(9));
			info.setPreferredSize(new Dimension(CARD_W, 18));
			wrapper.add(lbl, BorderLayout.CENTER);
			wrapper.add(info, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		// Permanent RFP cards (Primed top cards, etc.)
		for (CardData card : permZone) {
			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());
			JLabel lbl = makeRfpCardLabel(card.imageUrl());
			JLabel info = new JLabel(card.name() + "  [RFG]", SwingConstants.CENTER);
			info.setFont(FontLoader.loadPixelNESFont(9));
			info.setPreferredSize(new Dimension(CARD_W, 18));
			wrapper.add(lbl, BorderLayout.CENTER);
			wrapper.add(info, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		dlg.getContentPane().add(new JScrollPane(cardsPanel));
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	private JLabel makeRfpCardLabel(String imageUrl) {
		JLabel lbl = new JLabel("...", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
		lbl.setOpaque(true);
		lbl.setBackground(Color.DARK_GRAY);
		lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
		lbl.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) showZoomAt(imageUrl); }
			@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
		});
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image img = ImageCache.load(imageUrl);
				return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
			}
			@Override protected void done() {
				try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
				catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
		return lbl;
	}

	private void showBreakZoneDialog() { showBreakZoneDialog(gameState.getP1BreakZone(), "P1 Break Zone"); }
	private void showP2BreakZoneDialog() { showBreakZoneDialog(gameState.getP2BreakZone(), "P2 Break Zone"); }

	private void showBreakZoneDialog(List<CardData> zone, String title) {
		if (zone.isEmpty()) return;

		JDialog dlg = new JDialog(frame, title + " (" + zone.size() + " cards)", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		for (CardData cd : zone) {
			JPanel cardWrapper = new JPanel(new BorderLayout(0, 4));
			cardWrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() != null) showZoomAt(cd.imageUrl());
				}
				@Override public void mouseExited(MouseEvent e) { hideZoom(); }
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					if (img == null) return null;
					BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = buf.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
					g2.dispose();
					return new ImageIcon(buf);
				}
				@Override protected void done() {
					try {
						ImageIcon icon = get();
						if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
					} catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			cardWrapper.add(lbl,       BorderLayout.CENTER);
			cardWrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(cardWrapper);
		}

		JScrollPane scrollPane = new JScrollPane(cardsPanel,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(
				Math.min(zone.size() * (CARD_W + 16) + 16, 900),
				CARD_H + 60));

		JButton closeBtn = new JButton("Close");
		closeBtn.setFont(FontLoader.loadPixelNESFont(11));
		closeBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		south.add(closeBtn);
		south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
		dlg.getContentPane().add(south,      BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	private void triggerGameOver(String reason) {
		gameState.setP1GameOver(true);
		logEntry(reason);
		if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
	}

	private void p1TakeDamage() { p1TakeDamage(null); }

	private void p1TakeDamage(Runnable onDone) {
		if (gameState.isP1GameOver()) { if (onDone != null) onDone.run(); return; }
		p1ReceivedDamageThisTurn = true;
		CardData drawn = gameState.drawToDamageZone();
		if (drawn == null) {
			triggerGameOver("P1 milled out — You Lose!");
			return;
		}
		int idx = gameState.getP1DamageZone().size() - 1;
		boolean isEx = drawn.exBurst();

		refreshP1DeckLabel();
		logEntry("P1 takes 1 damage — " + drawn.name() + (isEx ? " [EX BURST!]" : ""));
		triggerAutoAbilitiesForDamageZone(true);
		animateCardToDamage(true, idx);

		int animDelay = CardSlideAnimator.TOTAL_FRAMES * CardSlideAnimator.FRAME_MS;
		javax.swing.Timer revealTimer = new javax.swing.Timer(animDelay, e -> {
			if (p1DamageSlotPanel != null) {
				p1DamageSlotPanel.putClientProperty("exBurst", isEx ? Boolean.TRUE : Boolean.FALSE);
				for (JPanel s : p1DamageSlots) { if (s != null) s.repaint(); }
				p1DamageSlotPanel.repaint();
			}
			if (idx < 7 && p1DamageSlots[idx] != null) {
				JPanel slot = p1DamageSlots[idx];
				slot.putClientProperty("isExBurst", isEx ? Boolean.TRUE : Boolean.FALSE);
				slot.repaint();
				String url = drawn.imageUrl();
				new SwingWorker<Image, Void>() {
					@Override protected Image doInBackground() throws Exception {
						return ImageCache.load(url);
					}
					@Override protected void done() {
						try {
							Image img = get();
							if (img != null) { slot.putClientProperty("cardImg", img); slot.repaint(); }
						} catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();
			}
			if (gameState.getP1DamageZone().size() >= 7) {
				triggerGameOver("7 Damage Taken - You Lose!");
				return;
			}
			if (isEx) triggerExBurst(drawn, true);
			if (onDone != null) onDone.run();
		});
		revealTimer.setRepeats(false);
		revealTimer.start();
	}

	private void p2TakeDamage() { p2TakeDamage(null); }

	private void p2TakeDamage(Runnable onDone) {
		p2ReceivedDamageThisTurn = true;
		CardData drawn = gameState.drawToP2DamageZone();
		p2DamageCount++;
		boolean isEx = drawn != null && drawn.exBurst();
		String cardInfo = drawn != null ? " — " + drawn.name() + (isEx ? " [EX BURST!]" : "") : "";
		logEntry("P2 takes 1 damage (" + p2DamageCount + "/7)" + cardInfo);
		triggerAutoAbilitiesForDamageZone(false);

		int slotIdx = p2DamageCount - 1;
		if (drawn != null) animateCardToDamage(false, slotIdx);

		refreshP2DeckLabel();

		int animDelay = CardSlideAnimator.TOTAL_FRAMES * CardSlideAnimator.FRAME_MS;
		javax.swing.Timer revealTimer = new javax.swing.Timer(animDelay, e -> {
			if (slotIdx >= 0 && slotIdx < p2DamageSlots.length && p2DamageSlots[slotIdx] != null) {
				JPanel slot = p2DamageSlots[slotIdx];
				slot.putClientProperty("isExBurst", isEx ? Boolean.TRUE : Boolean.FALSE);
				slot.repaint();
				if (drawn != null) {
					String url = drawn.imageUrl();
					new SwingWorker<Image, Void>() {
						@Override protected Image doInBackground() throws Exception {
							return ImageCache.load(url);
						}
						@Override protected void done() {
							try {
								Image img = get();
								if (img != null) { slot.putClientProperty("cardImg", img); slot.repaint(); }
							} catch (InterruptedException | ExecutionException ignored) {}
						}
					}.execute();
				}
			}
			if (p2DamageCount >= 7) {
				triggerGameOver("Player 2 Defeated - You Win!");
				return;
			}
			if (isEx && drawn != null) triggerExBurst(drawn, false);
			if (onDone != null) onDone.run();
		});
		revealTimer.setRepeats(false);
		revealTimer.start();
	}

	// -------------------------------------------------------------------------
	// Combat: breaking forwards
	// -------------------------------------------------------------------------

	/** Removes P1's forward at {@code idx} from the field and sends it to P1's Break Zone. */
	/** Removes {@code removedIdx} from {@code set} and decrements all higher indices by 1. */
	private static void shiftBlockSet(Set<Integer> set, int removedIdx) {
		Set<Integer> updated = new HashSet<>();
		for (int i : set) {
			if      (i < removedIdx) updated.add(i);
			else if (i > removedIdx) updated.add(i - 1);
		}
		set.clear();
		set.addAll(updated);
	}

	private static void shiftBlockMap(Map<Integer, int[]> map, int removedIdx) {
		Map<Integer, int[]> updated = new HashMap<>();
		for (Map.Entry<Integer, int[]> e : map.entrySet()) {
			int i = e.getKey();
			if      (i < removedIdx) updated.put(i,     e.getValue());
			else if (i > removedIdx) updated.put(i - 1, e.getValue());
		}
		map.clear();
		map.putAll(updated);
	}

	private void breakP1Forward(int idx) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		startBreakAnim(p1ForwardLabels.get(idx));
		CardData card    = p1ForwardCards.get(idx);
		boolean  hadGrants      = !card.fieldPowerGrants().isEmpty();
		boolean  hadCostReduces = !card.fieldCostReductions().isEmpty();
		CardData topCard = p1ForwardPrimedTop.get(idx);

		if (topCard != null) {
			// Primed: both cards move to break zone, then top card is immediately RFP'd
			addToP1BreakZone(card);
			addToP1BreakZone(topCard);
			logEntry(card.name() + " + " + topCard.name() + " → Break Zone (Primed)");
			gameState.getP1BreakZone().remove(topCard);
			gameState.addToP1PermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Play");
		} else {
			addToP1BreakZone(card);
			logEntry(card.name() + " → Break Zone");
		}

		p1ForwardCards.remove(idx);
		p1ForwardUrls.remove(idx);
		p1ForwardStates.remove(idx);
		p1ForwardPlayedOnTurn.remove(idx);
		p1ForwardDamage.remove(idx);
		p1ForwardPowerBoost.remove(idx);
		p1ForwardPowerReduction.remove(idx);
		p1ForwardTempTraits.remove(idx);
		p1ForwardRemovedTraits.remove(idx);
		p1ForwardTempJobs.remove(idx);
		p1ForwardPrimedTop.remove(idx);
		p1ForwardFrozen.remove(idx);
		p1ForwardLabels.remove(idx);
		shiftBlockSet(p1ForwardCannotBlock,              idx);
		shiftBlockSet(p1ForwardMustBlock,                idx);
		shiftBlockSet(p1ForwardCannotAttack,             idx);
		shiftBlockSet(p1ForwardMustAttack,               idx);
		shiftBlockSet(p1ForwardCannotAttackPersistent,   idx);
		shiftBlockSet(p1ForwardCannotBlockPersistent,    idx);
		shiftBlockSet(p1ForwardCannotBeBlocked,          idx);
		shiftBlockMap(p1ForwardCannotBeBlockedByCost,    idx);

		if (p1ForwardPanel != null) {
			p1ForwardPanel.removeAll();
			p1ForwardLabels.clear();
			for (int i = 0; i < p1ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setForeground(Color.DARK_GRAY);
				lbl.setFont(FontLoader.loadPixelNESFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						if (SwingUtilities.isLeftMouseButton(e)
								&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
							handleP1ForwardLeftClick(fi);
						} else {
							showForwardContextMenu(fi, lbl, e);
						}
					}
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						CardData top = p1ForwardPrimedTop.get(fi);
						showZoomAt(top != null ? top.imageUrl() : p1ForwardUrls.get(fi));
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				p1ForwardLabels.add(lbl);
				p1ForwardPanel.add(lbl);
			}
			p1ForwardPanel.revalidate();
			p1ForwardPanel.repaint();
			for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
		}
		if (hadGrants) for (int i = 0; i < p1MonsterCards.size(); i++) refreshP1MonsterSlot(i);
		if (hadCostReduces) refreshHandPopupIfVisible();
		// If the broken card was itself stolen from P2, drop its tracking entry
		stolenForwards.remove(card);
		// Restore any forwards that were conditioned on this card remaining on the field
		checkAndRestoreStolenOnLeave(card.name());

		refreshP1BreakLabel();
		if (topCard != null) refreshP1WarpZoneUI();
		triggerAutoAbilitiesForLeavesField(card, true);
		triggerAutoAbilitiesForBreakZone(card, true);
	}

	/** Removes P2's forward at {@code idx} from the field and sends it to P2's Break Zone. */
	private void breakP2Forward(int idx) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		startBreakAnim(p2ForwardLabels.get(idx));
		CardData card    = p2ForwardCards.get(idx);
		boolean hadGrants      = !card.fieldPowerGrants().isEmpty();
		boolean hadCostReduces = !card.fieldCostReductions().isEmpty();
		CardData topCard = p2ForwardPrimedTop.get(idx);

		if (topCard != null) {
			addToP2BreakZone(card);
			addToP2BreakZone(topCard);
			logEntry("[P2] " + card.name() + " + " + topCard.name() + " → Break Zone (Primed)");
			gameState.getP2BreakZone().remove(topCard);
			gameState.addToP2PermanentRfp(topCard);
			logEntry("[P2] " + topCard.name() + " → Removed From Play");
		} else {
			addToP2BreakZone(card);
			logEntry("[P2] " + card.name() + " → Break Zone");
		}

		p2ForwardCards.remove(idx);
		p2ForwardUrls.remove(idx);
		p2ForwardStates.remove(idx);
		p2ForwardPlayedOnTurn.remove(idx);
		p2ForwardDamage.remove(idx);
		p2ForwardPowerBoost.remove(idx);
		p2ForwardPowerReduction.remove(idx);
		p2ForwardTempTraits.remove(idx);
		p2ForwardRemovedTraits.remove(idx);
		p2ForwardTempJobs.remove(idx);
		p2ForwardPrimedTop.remove(idx);
		p2ForwardFrozen.remove(idx);
		p2ForwardLabels.remove(idx);
		shiftBlockSet(p2ForwardCannotBlock,              idx);
		shiftBlockSet(p2ForwardMustBlock,                idx);
		shiftBlockSet(p2ForwardCannotAttack,             idx);
		shiftBlockSet(p2ForwardMustAttack,               idx);
		shiftBlockSet(p2ForwardCannotAttackPersistent,   idx);
		shiftBlockSet(p2ForwardCannotBlockPersistent,    idx);
		shiftBlockSet(p2ForwardCannotBeBlocked,          idx);
		shiftBlockMap(p2ForwardCannotBeBlockedByCost,    idx);

		if (p2ForwardPanel != null) {
			p2ForwardPanel.removeAll();
			p2ForwardLabels.clear();
			for (int i = 0; i < p2ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setFont(FontLoader.loadPixelNESFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() != null) showZoomAt(p2ForwardUrls.get(fi));
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				p2ForwardLabels.add(lbl);
				p2ForwardPanel.add(lbl);
			}
			p2ForwardPanel.revalidate();
			p2ForwardPanel.repaint();
			for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
		}
		if (hadGrants) for (int i = 0; i < p2MonsterCards.size(); i++) refreshP2MonsterSlot(i);
		if (hadCostReduces) refreshHandPopupIfVisible();
		refreshP2BreakLabel();
		triggerAutoAbilitiesForLeavesField(card, false);
		triggerAutoAbilitiesForBreakZone(card, false);
		if (topCard != null) triggerAutoAbilitiesForBreakZone(topCard, false);
	}

	// -------------------------------------------------------------------------
	// Gain-control helpers
	// -------------------------------------------------------------------------

	/**
	 * Rebuilds all P2 forward JLabels from scratch to match the current {@code p2ForwardCards} list.
	 * Must be called after any modification to the list so the panel stays in sync.
	 */
	private void rebuildP1ForwardPanel() {
		if (p1ForwardPanel == null) return;
		p1ForwardPanel.removeAll();
		p1ForwardLabels.clear();
		for (int i = 0; i < p1ForwardCards.size(); i++) {
			final int fi = i;
			JLabel lbl = new JLabel("", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
			lbl.setOpaque(false);
			lbl.setForeground(Color.DARK_GRAY);
			lbl.setFont(FontLoader.loadPixelNESFont(11));
			lbl.setBorder(BorderFactory.createEmptyBorder());
			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					if (lbl.getIcon() == null) return;
					if (SwingUtilities.isLeftMouseButton(e)
							&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
						handleP1ForwardLeftClick(fi);
					} else {
						showForwardContextMenu(fi, lbl, e);
					}
				}
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() == null) return;
					CardData top = p1ForwardPrimedTop.get(fi);
					showZoomAt(top != null ? top.imageUrl() : p1ForwardUrls.get(fi));
				}
				@Override public void mouseExited(MouseEvent e) { hideZoom(); }
			});
			p1ForwardLabels.add(lbl);
			p1ForwardPanel.add(lbl);
		}
		p1ForwardPanel.revalidate();
		p1ForwardPanel.repaint();
		for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
	}

	private void rebuildP2ForwardPanel() {
		if (p2ForwardPanel == null) return;
		p2ForwardPanel.removeAll();
		p2ForwardLabels.clear();
		for (int i = 0; i < p2ForwardCards.size(); i++) {
			final int fi = i;
			JLabel lbl = new JLabel("", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
			lbl.setOpaque(false);
			lbl.setFont(FontLoader.loadPixelNESFont(11));
			lbl.setBorder(BorderFactory.createEmptyBorder());
			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() != null) showZoomAt(p2ForwardUrls.get(fi));
				}
				@Override public void mouseExited(MouseEvent e) { hideZoom(); }
			});
			p2ForwardLabels.add(lbl);
			p2ForwardPanel.add(lbl);
		}
		p2ForwardPanel.revalidate();
		p2ForwardPanel.repaint();
		for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
	}

	/**
	 * Moves P2's forward at {@code p2Idx} to P1's field without triggering ETF or break-zone
	 * auto-abilities, and registers the restoration condition in {@link #stolenForwards}.
	 */
	private void stealForwardFromP2ToP1(int p2Idx, String condition, boolean activate) {
		if (p2Idx < 0 || p2Idx >= p2ForwardCards.size()) return;
		CardData   card       = p2ForwardCards.get(p2Idx);
		int        savedDmg   = p2ForwardDamage.get(p2Idx);
		CardState  savedState = p2ForwardStates.get(p2Idx);

		// Uniqueness rule: a non-multicard cannot coexist with another copy of itself.
		// If P1 already controls a Forward with this name, both copies go to their owner's Break Zones.
		if (!card.multicard()) {
			for (int i = 0; i < p1ForwardCards.size(); i++) {
				if (p1ForwardCards.get(i).name().equalsIgnoreCase(card.name())) {
					logEntry(card.name() + " — uniqueness rule: both copies sent to their owner's Break Zone");
					breakP2Forward(p2Idx);  // P2's copy → P2's Break Zone (triggers leave/break abilities)
					breakP1Forward(i);       // P1's copy → P1's Break Zone (triggers leave/break abilities)
					return;
				}
			}
		}

		// Remove from P2 (no break zone, no auto-ability trigger)
		p2ForwardCards.remove(p2Idx);
		p2ForwardUrls.remove(p2Idx);
		p2ForwardStates.remove(p2Idx);
		p2ForwardPlayedOnTurn.remove(p2Idx);
		p2ForwardDamage.remove(p2Idx);
		p2ForwardPowerBoost.remove(p2Idx);
		p2ForwardPowerReduction.remove(p2Idx);
		p2ForwardTempTraits.remove(p2Idx);
		p2ForwardRemovedTraits.remove(p2Idx);
		p2ForwardTempJobs.remove(p2Idx);
		p2ForwardPrimedTop.remove(p2Idx);
		p2ForwardFrozen.remove(p2Idx);
		p2ForwardLabels.remove(p2Idx);
		shiftBlockSet(p2ForwardCannotBlock,            p2Idx);
		shiftBlockSet(p2ForwardMustBlock,              p2Idx);
		shiftBlockSet(p2ForwardCannotAttack,           p2Idx);
		shiftBlockSet(p2ForwardMustAttack,             p2Idx);
		shiftBlockSet(p2ForwardCannotAttackPersistent, p2Idx);
		shiftBlockSet(p2ForwardCannotBlockPersistent,  p2Idx);
		shiftBlockSet(p2ForwardCannotBeBlocked,        p2Idx);
		shiftBlockMap(p2ForwardCannotBeBlockedByCost,  p2Idx);
		rebuildP2ForwardPanel();

		// Add to P1 with preserved damage; state forced ACTIVE if requested
		addStolenForwardToP1Field(card, savedDmg, activate ? CardState.ACTIVE : savedState);

		String condLabel = condition.equals("permanent") ? " (permanent)"
				: condition.equals("endOfTurn") ? " (until EOT)"
				: " (while " + condition.substring("whileCardOnField:".length()) + " on field)";
		logEntry(card.name() + " — control stolen by P1" + condLabel);

		if (!condition.equals("permanent")) {
			stolenForwards.put(card, condition);
			if (condition.equals("endOfTurn")) {
				endOfTurnEffects.add(ctx -> {
					if (stolenForwards.remove(card) != null) restoreStolenForward(card);
				});
			}
		}
	}

	/** Adds {@code card} to P1's forward zone with the given damage and state; does NOT fire ETF. */
	private void addStolenForwardToP1Field(CardData card, int damage, CardState state) {
		if (p1ForwardPanel == null) return;
		int idx = p1ForwardLabels.size();

		JLabel lbl = new JLabel("", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
		lbl.setOpaque(false);
		lbl.setForeground(Color.DARK_GRAY);
		lbl.setFont(FontLoader.loadPixelNESFont(11));
		lbl.setBorder(BorderFactory.createEmptyBorder());
		lbl.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				if (lbl.getIcon() == null) return;
				if (SwingUtilities.isLeftMouseButton(e)
						&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
					handleP1ForwardLeftClick(idx);
				} else {
					showForwardContextMenu(idx, lbl, e);
				}
			}
			@Override public void mouseEntered(MouseEvent e) {
				if (lbl.getIcon() == null) return;
				CardData top = p1ForwardPrimedTop.get(idx);
				showZoomAt(top != null ? top.imageUrl() : p1ForwardUrls.get(idx));
			}
			@Override public void mouseExited(MouseEvent e) { hideZoom(); }
		});

		p1ForwardUrls.add(card.imageUrl());
		p1ForwardCards.add(card);
		p1ForwardStates.add(state);
		p1ForwardPlayedOnTurn.add(gameState.getTurnNumber());
		p1ForwardDamage.add(damage);
		p1ForwardPowerBoost.add(0);
		p1ForwardPowerReduction.add(0);
		p1ForwardTempTraits.add(java.util.EnumSet.noneOf(CardData.Trait.class));
		p1ForwardRemovedTraits.add(java.util.EnumSet.noneOf(CardData.Trait.class));
		p1ForwardTempJobs.add(null);
		p1ForwardPrimedTop.add(null);
		p1ForwardFrozen.add(false);
		p1ForwardLabels.add(lbl);

		p1ForwardPanel.add(lbl);
		p1ForwardPanel.revalidate();
		p1ForwardPanel.repaint();
		refreshP1ForwardSlot(idx);
		if (!card.fieldPowerGrants().isEmpty()) refreshFieldGrantDependents(true);
		if (!card.fieldCostReductions().isEmpty()) refreshHandPopupIfVisible();
	}

	/**
	 * Removes a stolen forward from P1's field (without sending it to any zone) and returns
	 * it to P2's field with its current state.  If the card is no longer on P1's field
	 * (already broken), this is a no-op except for a log entry.
	 */
	private void restoreStolenForward(CardData card) {
		int p1Idx = -1;
		for (int i = 0; i < p1ForwardCards.size(); i++) {
			if (p1ForwardCards.get(i) == card) { p1Idx = i; break; }
		}
		if (p1Idx < 0) {
			logEntry(card.name() + " — already left field, P2 control restored implicitly");
			return;
		}

		int       dmg   = p1ForwardDamage.get(p1Idx);
		CardState state = p1ForwardStates.get(p1Idx);

		// Remove from P1 arrays (no break zone)
		p1ForwardCards.remove(p1Idx);
		p1ForwardUrls.remove(p1Idx);
		p1ForwardStates.remove(p1Idx);
		p1ForwardPlayedOnTurn.remove(p1Idx);
		p1ForwardDamage.remove(p1Idx);
		p1ForwardPowerBoost.remove(p1Idx);
		p1ForwardPowerReduction.remove(p1Idx);
		p1ForwardTempTraits.remove(p1Idx);
		p1ForwardRemovedTraits.remove(p1Idx);
		p1ForwardTempJobs.remove(p1Idx);
		p1ForwardPrimedTop.remove(p1Idx);
		p1ForwardFrozen.remove(p1Idx);
		p1ForwardLabels.remove(p1Idx);
		shiftBlockSet(p1ForwardCannotBlock,            p1Idx);
		shiftBlockSet(p1ForwardMustBlock,              p1Idx);
		shiftBlockSet(p1ForwardCannotAttack,           p1Idx);
		shiftBlockSet(p1ForwardMustAttack,             p1Idx);
		shiftBlockSet(p1ForwardCannotAttackPersistent, p1Idx);
		shiftBlockSet(p1ForwardCannotBlockPersistent,  p1Idx);
		shiftBlockSet(p1ForwardCannotBeBlocked,        p1Idx);
		shiftBlockMap(p1ForwardCannotBeBlockedByCost,  p1Idx);

		// Rebuild P1 panel
		if (p1ForwardPanel != null) {
			p1ForwardPanel.removeAll();
			p1ForwardLabels.clear();
			for (int i = 0; i < p1ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setForeground(Color.DARK_GRAY);
				lbl.setFont(FontLoader.loadPixelNESFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						if (SwingUtilities.isLeftMouseButton(e)
								&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
							handleP1ForwardLeftClick(fi);
						} else {
							showForwardContextMenu(fi, lbl, e);
						}
					}
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						CardData top = p1ForwardPrimedTop.get(fi);
						showZoomAt(top != null ? top.imageUrl() : p1ForwardUrls.get(fi));
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				p1ForwardLabels.add(lbl);
				p1ForwardPanel.add(lbl);
			}
			p1ForwardPanel.revalidate();
			p1ForwardPanel.repaint();
			for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
		}

		// Return to P2's field with current damage/state
		p2ForwardUrls.add(card.imageUrl());
		p2ForwardCards.add(card);
		p2ForwardStates.add(state);
		p2ForwardPlayedOnTurn.add(gameState.getTurnNumber());
		p2ForwardDamage.add(dmg);
		p2ForwardPowerBoost.add(0);
		p2ForwardPowerReduction.add(0);
		p2ForwardTempTraits.add(java.util.EnumSet.noneOf(CardData.Trait.class));
		p2ForwardRemovedTraits.add(java.util.EnumSet.noneOf(CardData.Trait.class));
		p2ForwardTempJobs.add(null);
		p2ForwardFrozen.add(false);
		rebuildP2ForwardPanel();
		if (!card.fieldPowerGrants().isEmpty()) refreshFieldGrantDependents(false);
		if (!card.fieldCostReductions().isEmpty()) refreshHandPopupIfVisible();

		logEntry(card.name() + " — control returned to P2");
	}

	/** Checks if any stolen forward had {@code leavingCardName} as its on-field condition and restores them. */
	private void checkAndRestoreStolenOnLeave(String leavingCardName) {
		String condKey = "whileCardOnField:" + leavingCardName;
		java.util.List<CardData> toRestore = new java.util.ArrayList<>();
		for (java.util.Map.Entry<CardData, String> e : stolenForwards.entrySet())
			if (e.getValue().equalsIgnoreCase(condKey)) toRestore.add(e.getKey());
		for (CardData c : toRestore) {
			stolenForwards.remove(c);
			restoreStolenForward(c);
		}
	}

	private void returnP1ForwardToDeck(int idx, boolean toBottom) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		CardData card    = p1ForwardCards.get(idx);
		CardData topCard = p1ForwardPrimedTop.get(idx);
		String   pos     = toBottom ? "bottom" : "top";
		if (topCard != null) {
			gameState.addToP1PermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Play");
		}
		if (toBottom) gameState.getP1MainDeck().addLast(card);
		else          gameState.getP1MainDeck().addFirst(card);
		logEntry(card.name() + " → " + pos + " of deck");

		p1ForwardCards.remove(idx);
		p1ForwardUrls.remove(idx);
		p1ForwardStates.remove(idx);
		p1ForwardPlayedOnTurn.remove(idx);
		p1ForwardDamage.remove(idx);
		p1ForwardPowerBoost.remove(idx);
		p1ForwardPowerReduction.remove(idx);
		p1ForwardTempTraits.remove(idx);
		p1ForwardRemovedTraits.remove(idx);
		p1ForwardPrimedTop.remove(idx);
		p1ForwardFrozen.remove(idx);
		p1ForwardLabels.remove(idx);
		shiftBlockSet(p1ForwardCannotBlock,            idx);
		shiftBlockSet(p1ForwardMustBlock,              idx);
		shiftBlockSet(p1ForwardCannotAttack,           idx);
		shiftBlockSet(p1ForwardMustAttack,             idx);
		shiftBlockSet(p1ForwardCannotAttackPersistent, idx);
		shiftBlockSet(p1ForwardCannotBlockPersistent,  idx);
		shiftBlockSet(p1ForwardCannotBeBlocked,        idx);
		shiftBlockMap(p1ForwardCannotBeBlockedByCost,  idx);

		if (p1ForwardPanel != null) {
			p1ForwardPanel.removeAll();
			p1ForwardLabels.clear();
			for (int i = 0; i < p1ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setForeground(Color.DARK_GRAY);
				lbl.setFont(FontLoader.loadPixelNESFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						if (SwingUtilities.isLeftMouseButton(e)
								&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
							handleP1ForwardLeftClick(fi);
						} else {
							showForwardContextMenu(fi, lbl, e);
						}
					}
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						CardData top = p1ForwardPrimedTop.get(fi);
						showZoomAt(top != null ? top.imageUrl() : p1ForwardUrls.get(fi));
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				p1ForwardLabels.add(lbl);
				p1ForwardPanel.add(lbl);
			}
			p1ForwardPanel.revalidate();
			p1ForwardPanel.repaint();
			for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
		}
		refreshP1DeckLabel();
		if (topCard != null) refreshP1WarpZoneUI();
		triggerAutoAbilitiesForLeavesField(card, true);
	}

	private void returnP2ForwardToDeck(int idx, boolean toBottom) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		CardData card    = p2ForwardCards.get(idx);
		CardData topCard = p2ForwardPrimedTop.get(idx);
		String   pos     = toBottom ? "bottom" : "top";
		if (topCard != null) {
			gameState.addToP2PermanentRfp(topCard);
			logEntry("[P2] " + topCard.name() + " → Removed From Play");
		}
		if (toBottom) gameState.getP2MainDeck().addLast(card);
		else          gameState.getP2MainDeck().addFirst(card);
		logEntry("[P2] " + card.name() + " → " + pos + " of deck");

		p2ForwardCards.remove(idx);
		p2ForwardUrls.remove(idx);
		p2ForwardStates.remove(idx);
		p2ForwardPlayedOnTurn.remove(idx);
		p2ForwardDamage.remove(idx);
		p2ForwardPowerBoost.remove(idx);
		p2ForwardPowerReduction.remove(idx);
		p2ForwardTempTraits.remove(idx);
		p2ForwardRemovedTraits.remove(idx);
		p2ForwardPrimedTop.remove(idx);
		p2ForwardFrozen.remove(idx);
		p2ForwardLabels.remove(idx);
		shiftBlockSet(p2ForwardCannotBlock,            idx);
		shiftBlockSet(p2ForwardMustBlock,              idx);
		shiftBlockSet(p2ForwardCannotAttack,           idx);
		shiftBlockSet(p2ForwardMustAttack,             idx);
		shiftBlockSet(p2ForwardCannotAttackPersistent, idx);
		shiftBlockSet(p2ForwardCannotBlockPersistent,  idx);
		shiftBlockSet(p2ForwardCannotBeBlocked,        idx);
		shiftBlockMap(p2ForwardCannotBeBlockedByCost,  idx);

		if (p2ForwardPanel != null) {
			p2ForwardPanel.removeAll();
			p2ForwardLabels.clear();
			for (int i = 0; i < p2ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setFont(FontLoader.loadPixelNESFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() != null) showZoomAt(p2ForwardUrls.get(fi));
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				p2ForwardLabels.add(lbl);
				p2ForwardPanel.add(lbl);
			}
			p2ForwardPanel.revalidate();
			p2ForwardPanel.repaint();
			for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
		}
		refreshP2DeckLabel();
		triggerAutoAbilitiesForLeavesField(card, false);
	}

	private void returnP1ForwardUnderDeckTop(int idx, int position) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		CardData card    = p1ForwardCards.get(idx);
		CardData topCard = p1ForwardPrimedTop.get(idx);
		if (topCard != null) {
			gameState.addToP1PermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Play");
		}
		Deque<CardData> deck = gameState.getP1MainDeck();
		List<CardData> preserved = new ArrayList<>();
		for (int i = 0; i < position && !deck.isEmpty(); i++) preserved.add(deck.pollFirst());
		deck.addFirst(card);
		for (int i = preserved.size() - 1; i >= 0; i--) deck.addFirst(preserved.get(i));
		logEntry(card.name() + " → under top " + position + " card(s) of deck");

		p1ForwardCards.remove(idx);
		p1ForwardUrls.remove(idx);
		p1ForwardStates.remove(idx);
		p1ForwardPlayedOnTurn.remove(idx);
		p1ForwardDamage.remove(idx);
		p1ForwardPowerBoost.remove(idx);
		p1ForwardPowerReduction.remove(idx);
		p1ForwardTempTraits.remove(idx);
		p1ForwardRemovedTraits.remove(idx);
		p1ForwardPrimedTop.remove(idx);
		p1ForwardFrozen.remove(idx);
		p1ForwardLabels.remove(idx);
		shiftBlockSet(p1ForwardCannotBlock,            idx);
		shiftBlockSet(p1ForwardMustBlock,              idx);
		shiftBlockSet(p1ForwardCannotAttack,           idx);
		shiftBlockSet(p1ForwardMustAttack,             idx);
		shiftBlockSet(p1ForwardCannotAttackPersistent, idx);
		shiftBlockSet(p1ForwardCannotBlockPersistent,  idx);
		shiftBlockSet(p1ForwardCannotBeBlocked,        idx);
		shiftBlockMap(p1ForwardCannotBeBlockedByCost,  idx);

		if (p1ForwardPanel != null) {
			p1ForwardPanel.removeAll();
			p1ForwardLabels.clear();
			for (int i = 0; i < p1ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setForeground(Color.DARK_GRAY);
				lbl.setFont(FontLoader.loadPixelNESFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						if (SwingUtilities.isLeftMouseButton(e)
								&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
							handleP1ForwardLeftClick(fi);
						} else {
							showForwardContextMenu(fi, lbl, e);
						}
					}
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						CardData top = p1ForwardPrimedTop.get(fi);
						showZoomAt(top != null ? top.imageUrl() : p1ForwardUrls.get(fi));
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				p1ForwardLabels.add(lbl);
				p1ForwardPanel.add(lbl);
			}
			p1ForwardPanel.revalidate();
			p1ForwardPanel.repaint();
			for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
		}
		refreshP1DeckLabel();
		if (topCard != null) refreshP1WarpZoneUI();
		triggerAutoAbilitiesForLeavesField(card, true);
	}

	private void returnP2ForwardUnderDeckTop(int idx, int position) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		CardData card    = p2ForwardCards.get(idx);
		CardData topCard = p2ForwardPrimedTop.get(idx);
		if (topCard != null) {
			gameState.addToP2PermanentRfp(topCard);
			logEntry("[P2] " + topCard.name() + " → Removed From Play");
		}
		Deque<CardData> deck = gameState.getP2MainDeck();
		List<CardData> preserved = new ArrayList<>();
		for (int i = 0; i < position && !deck.isEmpty(); i++) preserved.add(deck.pollFirst());
		deck.addFirst(card);
		for (int i = preserved.size() - 1; i >= 0; i--) deck.addFirst(preserved.get(i));
		logEntry("[P2] " + card.name() + " → under top " + position + " card(s) of deck");

		p2ForwardCards.remove(idx);
		p2ForwardUrls.remove(idx);
		p2ForwardStates.remove(idx);
		p2ForwardPlayedOnTurn.remove(idx);
		p2ForwardDamage.remove(idx);
		p2ForwardPowerBoost.remove(idx);
		p2ForwardPowerReduction.remove(idx);
		p2ForwardTempTraits.remove(idx);
		p2ForwardRemovedTraits.remove(idx);
		p2ForwardPrimedTop.remove(idx);
		p2ForwardFrozen.remove(idx);
		p2ForwardLabels.remove(idx);
		shiftBlockSet(p2ForwardCannotBlock,            idx);
		shiftBlockSet(p2ForwardMustBlock,              idx);
		shiftBlockSet(p2ForwardCannotAttack,           idx);
		shiftBlockSet(p2ForwardMustAttack,             idx);
		shiftBlockSet(p2ForwardCannotAttackPersistent, idx);
		shiftBlockSet(p2ForwardCannotBlockPersistent,  idx);
		shiftBlockSet(p2ForwardCannotBeBlocked,        idx);
		shiftBlockMap(p2ForwardCannotBeBlockedByCost,  idx);

		if (p2ForwardPanel != null) {
			p2ForwardPanel.removeAll();
			p2ForwardLabels.clear();
			for (int i = 0; i < p2ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setFont(FontLoader.loadPixelNESFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() != null) showZoomAt(p2ForwardUrls.get(fi));
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				p2ForwardLabels.add(lbl);
				p2ForwardPanel.add(lbl);
			}
			p2ForwardPanel.revalidate();
			p2ForwardPanel.repaint();
			for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
		}
		refreshP2DeckLabel();
		triggerAutoAbilitiesForLeavesField(card, false);
	}

	private void searchDeckForCard(boolean isP1,
			boolean inclForwards, boolean inclBackups,
			boolean inclMonsters, boolean inclSummons,
			int costVal, String costCmp, String cardNameFilter, String jobFilter,
			String categoryFilter, String elementFilter, String excludeName, String excludeElem,
			String destination) {
		Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
		boolean anyType = !inclForwards && !inclBackups && !inclMonsters && !inclSummons;
		List<CardData> matches = new ArrayList<>();
		for (CardData c : deck) {
			if (!anyType) {
				boolean typeMatch = (inclForwards && c.isForward())
				                 || (inclBackups  && c.isBackup())
				                 || (inclMonsters && (c.isMonster() || c.alsoCountsAsMonster()))
				                 || (inclSummons  && c.isSummon());
				if (!typeMatch) continue;
			}
			if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
			// Job+name: OR when both are set (e.g. "Job X or Card Name Y"); AND otherwise
			boolean passesNameJob = (jobFilter == null && cardNameFilter == null)
				|| (jobFilter != null && cardNameFilter != null
					? meetsJobFilter(c, jobFilter) || meetsCardNameFilter(c, cardNameFilter)
					: meetsJobFilter(c, jobFilter) && meetsCardNameFilter(c, cardNameFilter));
			if (!passesNameJob) continue;
			if (!meetsCategoryFilter(c, categoryFilter)) continue;
			if (!meetsElementFilter(c, elementFilter)) continue;
			if (excludeName != null && meetsCardNameFilter(c, excludeName)) continue;
			if (excludeElem != null) {
				boolean excluded = false;
				for (String ee : excludeElem.split("(?i)\\s+or\\s+"))
					if (c.containsElement(ee.trim())) { excluded = true; break; }
				if (excluded) continue;
			}
			matches.add(c);
		}
		if (matches.isEmpty()) {
			shuffleDeck(isP1);
			logEntry("Search: no matching card found in deck");
			return;
		}
		CardData chosen;
		if (!isP1) {
			List<CardData> copy = new ArrayList<>(matches);
			Collections.shuffle(copy);
			chosen = copy.get(0);
			logEntry("[AI] chose " + chosen.name());
		} else {
			chosen = showDeckSearchSelectDialog(matches);
		}
		if (chosen != null) {
			if (isP1) gameState.removeFromP1MainDeck(chosen);
			else      deck.remove(chosen);
		}
		shuffleDeck(isP1);
		if (chosen == null) {
			logEntry("Search: no card selected");
			return;
		}
		switch (destination) {
			case "hand" -> {
				playerHand(isP1).add(chosen);
				logEntry((isP1 ? "" : "[P2] ") + chosen.name() + " → hand (search)");
				if (isP1) refreshP1HandLabel(); else refreshP2HandCountLabel();
			}
			case "field" -> {
				logEntry((isP1 ? "" : "[P2] ") + chosen.name() + " → field (search)");
				if (isP1) {
					if (chosen.isBackup())       placeCardInFirstBackupSlot(chosen);
					else if (chosen.isMonster()) placeCardInMonsterZone(chosen);
					else                         placeCardInForwardZone(chosen);
				} else {
					if (chosen.isBackup())       placeP2CardInFirstBackupSlot(chosen);
					else if (chosen.isMonster()) placeP2CardInMonsterZone(chosen);
					else                         placeP2CardInForwardZone(chosen);
				}
			}
			case "underTop" -> {
				if (deck.isEmpty()) {
					deck.addFirst(chosen);
				} else {
					CardData top = deck.pollFirst();
					deck.addFirst(chosen);
					deck.addFirst(top);
				}
				logEntry((isP1 ? "" : "[P2] ") + chosen.name() + " → under top card of deck (search)");
				if (isP1) refreshP1DeckLabel(); else refreshP2DeckLabel();
			}
		}
	}

	private void shuffleDeck(boolean isP1) {
		Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
		List<CardData> list = new ArrayList<>(deck);
		Collections.shuffle(list);
		deck.clear();
		deck.addAll(list);
		if (isP1) refreshP1DeckLabel(); else refreshP2DeckLabel();
	}

	private CardData showDeckSearchSelectDialog(List<CardData> matches) {
		if (matches.size() == 1) return matches.get(0);
		JDialog dlg = new JDialog(frame, "Search — choose a card (" + matches.size() + " found)", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		CardData[] selection = {matches.get(0)};

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));
		for (CardData candidate : matches) {
			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
			lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() != null) showZoomAt(candidate.imageUrl());
					lbl.setBorder(createCardGlowBorder(Color.YELLOW));
				}
				@Override public void mouseExited(MouseEvent e) {
					hideZoom();
					lbl.setBorder(selection[0].equals(candidate)
							? createCardGlowBorder(Color.YELLOW)
							: BorderFactory.createLineBorder(Color.GRAY, 1));
				}
				@Override public void mousePressed(MouseEvent e) {
					selection[0] = candidate;
					dlg.dispose();
				}
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(candidate.imageUrl());
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(candidate.name(), SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			wrapper.add(lbl, BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		JLabel hint = new JLabel("Click a card to select it", SwingConstants.CENTER);
		hint.setFont(FontLoader.loadPixelNESFont(9));

		dlg.getContentPane().setLayout(new BorderLayout(0, 6));
		dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
		dlg.getContentPane().add(hint, BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);

		return selection[0];
	}

	/**
	 * Shows a modal dialog that displays {@code cards} as clickable card images and returns the
	 * index of the chosen card within {@code cards}, or {@code -1} if the player cancelled
	 * (only possible when {@code allowCancel} is true). Reporting the position — rather than the
	 * {@link CardData} — lets callers map back to a hand/zone index even when the list contains
	 * value-equal duplicates.
	 */
	private int showCardImageChooser(List<CardData> cards, String title, boolean allowCancel) {
		return showCardImageChooser(cards, title, allowCancel, true);
	}

	private int showCardImageChooser(List<CardData> cards, String title, boolean allowCancel, boolean showCost) {
		if (cards.isEmpty()) return -1;
		JDialog dlg = new JDialog(frame, title, true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(allowCancel ? JDialog.DISPOSE_ON_CLOSE : JDialog.DO_NOTHING_ON_CLOSE);

		int[] selection = { -1 };

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));
		for (int idx = 0; idx < cards.size(); idx++) {
			final int pos = idx;
			CardData candidate = cards.get(idx);
			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
			lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() != null) showZoomAt(candidate.imageUrl());
					lbl.setBorder(createCardGlowBorder(Color.YELLOW));
				}
				@Override public void mouseExited(MouseEvent e) {
					hideZoom();
					lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
				}
				@Override public void mousePressed(MouseEvent e) {
					selection[0] = pos;
					hideZoom();
					dlg.dispose();
				}
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(candidate.imageUrl());
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel;
			if (showCost) {
				nameLabel = new JLabel("<html><div style='width:" + CARD_W + "px;text-align:center'>"
						+ candidate.name() + "<br>(Cost: " + candidate.cost() + ")" + "</div></html>",
						SwingConstants.CENTER);
			} else {
				nameLabel = new JLabel(candidate.name(), SwingConstants.CENTER);
				nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
			}
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, showCost ? 30 : 18));

			wrapper.add(lbl, BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		JLabel hint = new JLabel(allowCancel ? "Click a card to play it, or close to decline"
				: "Click a card to select it", SwingConstants.CENTER);
		hint.setFont(FontLoader.loadPixelNESFont(9));

		dlg.getContentPane().setLayout(new BorderLayout(0, 6));
		dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
		dlg.getContentPane().add(hint, BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);

		return selection[0];
	}

	private void returnP1ForwardToHand(int idx) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		CardData card    = p1ForwardCards.get(idx);
		boolean hadGrants = !card.fieldPowerGrants().isEmpty();
		CardData topCard = p1ForwardPrimedTop.get(idx);
		if (topCard != null) {
			gameState.addToP1PermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Play");
		}
		gameState.getP1Hand().add(card);
		logEntry(card.name() + " → returned to hand");

		p1ForwardCards.remove(idx);
		p1ForwardUrls.remove(idx);
		p1ForwardStates.remove(idx);
		p1ForwardPlayedOnTurn.remove(idx);
		p1ForwardDamage.remove(idx);
		p1ForwardPowerBoost.remove(idx);
		p1ForwardPowerReduction.remove(idx);
		p1ForwardTempTraits.remove(idx);
		p1ForwardRemovedTraits.remove(idx);
		p1ForwardPrimedTop.remove(idx);
		p1ForwardFrozen.remove(idx);
		p1ForwardLabels.remove(idx);
		shiftBlockSet(p1ForwardCannotBlock,            idx);
		shiftBlockSet(p1ForwardMustBlock,              idx);
		shiftBlockSet(p1ForwardCannotAttack,           idx);
		shiftBlockSet(p1ForwardMustAttack,             idx);
		shiftBlockSet(p1ForwardCannotAttackPersistent, idx);
		shiftBlockSet(p1ForwardCannotBlockPersistent,  idx);
		shiftBlockSet(p1ForwardCannotBeBlocked,        idx);
		shiftBlockMap(p1ForwardCannotBeBlockedByCost,  idx);

		if (p1ForwardPanel != null) {
			p1ForwardPanel.removeAll();
			p1ForwardLabels.clear();
			for (int i = 0; i < p1ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setForeground(Color.DARK_GRAY);
				lbl.setFont(FontLoader.loadPixelNESFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						if (SwingUtilities.isLeftMouseButton(e)
								&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
							handleP1ForwardLeftClick(fi);
						} else {
							showForwardContextMenu(fi, lbl, e);
						}
					}
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						CardData top = p1ForwardPrimedTop.get(fi);
						showZoomAt(top != null ? top.imageUrl() : p1ForwardUrls.get(fi));
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				p1ForwardLabels.add(lbl);
				p1ForwardPanel.add(lbl);
			}
			p1ForwardPanel.revalidate();
			p1ForwardPanel.repaint();
			for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
		}
		if (hadGrants) for (int i = 0; i < p1MonsterCards.size(); i++) refreshP1MonsterSlot(i);
		refreshP1HandLabel();
		if (topCard != null) refreshP1WarpZoneUI();
		triggerAutoAbilitiesForLeavesField(card, true);
	}

	private void returnP2ForwardToHand(int idx) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		CardData card    = p2ForwardCards.get(idx);
		boolean hadGrants = !card.fieldPowerGrants().isEmpty();
		CardData topCard = p2ForwardPrimedTop.get(idx);
		if (topCard != null) {
			gameState.addToP2PermanentRfp(topCard);
			logEntry("[P2] " + topCard.name() + " → Removed From Play");
		}
		gameState.getP2Hand().add(card);
		logEntry("[P2] " + card.name() + " → returned to hand");

		p2ForwardCards.remove(idx);
		p2ForwardUrls.remove(idx);
		p2ForwardStates.remove(idx);
		p2ForwardPlayedOnTurn.remove(idx);
		p2ForwardDamage.remove(idx);
		p2ForwardPowerBoost.remove(idx);
		p2ForwardPowerReduction.remove(idx);
		p2ForwardTempTraits.remove(idx);
		p2ForwardRemovedTraits.remove(idx);
		p2ForwardPrimedTop.remove(idx);
		p2ForwardFrozen.remove(idx);
		p2ForwardLabels.remove(idx);
		shiftBlockSet(p2ForwardCannotBlock,            idx);
		shiftBlockSet(p2ForwardMustBlock,              idx);
		shiftBlockSet(p2ForwardCannotAttack,           idx);
		shiftBlockSet(p2ForwardMustAttack,             idx);
		shiftBlockSet(p2ForwardCannotAttackPersistent, idx);
		shiftBlockSet(p2ForwardCannotBlockPersistent,  idx);
		shiftBlockSet(p2ForwardCannotBeBlocked,        idx);
		shiftBlockMap(p2ForwardCannotBeBlockedByCost,  idx);

		if (p2ForwardPanel != null) {
			p2ForwardPanel.removeAll();
			p2ForwardLabels.clear();
			for (int i = 0; i < p2ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setFont(FontLoader.loadPixelNESFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() != null) showZoomAt(p2ForwardUrls.get(fi));
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				p2ForwardLabels.add(lbl);
				p2ForwardPanel.add(lbl);
			}
			p2ForwardPanel.revalidate();
			p2ForwardPanel.repaint();
			for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
		}
		if (hadGrants) for (int i = 0; i < p2MonsterCards.size(); i++) refreshP2MonsterSlot(i);
		refreshP2HandCountLabel();
		triggerAutoAbilitiesForLeavesField(card, false);
	}

	private void returnP1BackupToHand(int idx) {
		if (idx < 0 || idx >= p1BackupCards.length || p1BackupCards[idx] == null) return;
		CardData c = p1BackupCards[idx];
		gameState.getP1Hand().add(c);
		logEntry(c.name() + " → returned to hand");
		p1BackupCards[idx]  = null;
		p1BackupUrls[idx]   = null;
		p1BackupStates[idx] = CardState.ACTIVE;
		p1BackupFrozen[idx] = false;
		if (p1BackupLabels[idx] != null) { p1BackupLabels[idx].setIcon(null); p1BackupLabels[idx].setText(null); }
		refreshP1HandLabel();
		triggerAutoAbilitiesForLeavesField(c, true);
	}

	private void returnP2BackupToHand(int idx) {
		if (idx < 0 || idx >= p2BackupCards.length || p2BackupCards[idx] == null) return;
		CardData c = p2BackupCards[idx];
		gameState.getP2Hand().add(c);
		logEntry("[P2] " + c.name() + " → returned to hand");
		p2BackupCards[idx]  = null;
		p2BackupUrls[idx]   = null;
		p2BackupStates[idx] = CardState.ACTIVE;
		p2BackupFrozen[idx] = false;
		if (p2BackupLabels[idx] != null) { p2BackupLabels[idx].setIcon(null); p2BackupLabels[idx].setText(null); }
		triggerAutoAbilitiesForLeavesField(c, false);
	}

	private void returnP1MonsterToHand(int idx) {
		if (idx < 0 || idx >= p1MonsterCards.size()) return;
		CardData c = p1MonsterCards.get(idx);
		gameState.getP1Hand().add(c);
		logEntry(c.name() + " → returned to hand");
		p1MonsterTempForwardPower.remove(c);
		p1MonsterCards.remove(idx);
		p1MonsterStates.remove(idx);
		p1MonsterFrozen.remove(idx);
		p1MonsterPlayedOnTurn.remove(idx);
		p1MonsterUrls.remove(idx);
		JLabel lbl = p1MonsterLabels.remove(idx);
		if (p1MonsterPanel != null) { p1MonsterPanel.remove(lbl); p1MonsterPanel.revalidate(); p1MonsterPanel.repaint(); }
		refreshP1HandLabel();
		triggerAutoAbilitiesForLeavesField(c, true);
	}

	private void returnP2MonsterToHand(int idx) {
		if (idx < 0 || idx >= p2MonsterCards.size()) return;
		CardData c = p2MonsterCards.get(idx);
		gameState.getP2Hand().add(c);
		logEntry("[P2] " + c.name() + " → returned to hand");
		p2MonsterTempForwardPower.remove(c);
		p2MonsterCards.remove(idx);
		p2MonsterStates.remove(idx);
		p2MonsterFrozen.remove(idx);
		p2MonsterPlayedOnTurn.remove(idx);
		p2MonsterUrls.remove(idx);
		JLabel lbl = p2MonsterLabels.remove(idx);
		if (p2MonsterPanel != null) { p2MonsterPanel.remove(lbl); p2MonsterPanel.revalidate(); p2MonsterPanel.repaint(); }
		triggerAutoAbilitiesForLeavesField(c, false);
	}

	private int effectiveP1ForwardPower(int idx) {
		CardData top  = p1ForwardPrimedTop.get(idx);
		CardData card = p1ForwardCards.get(idx);
		int base = top != null ? top.power() : card.power();
		return base + p1ForwardPowerBoost.get(idx) - p1ForwardPowerReduction.get(idx)
				+ computeConditionalBoostForTarget(card, true);
	}

	private int effectiveP2ForwardPower(int idx) {
		CardData card = p2ForwardCards.get(idx);
		return card.power() + p2ForwardPowerBoost.get(idx) - p2ForwardPowerReduction.get(idx)
				+ computeConditionalBoostForTarget(card, false);
	}

	private boolean effectiveP1HasTrait(int idx, CardData.Trait trait) {
		if (p1ForwardRemovedTraits.get(idx).contains(trait)) return false;
		CardData card = p1ForwardCards.get(idx);
		return card.hasTrait(trait)
				|| p1ForwardTempTraits.get(idx).contains(trait)
				|| computeConditionalTraitsForTarget(card, true).contains(trait);
	}

	private boolean effectiveP2HasTrait(int idx, CardData.Trait trait) {
		if (p2ForwardRemovedTraits.get(idx).contains(trait)) return false;
		CardData card = p2ForwardCards.get(idx);
		return card.hasTrait(trait)
				|| p2ForwardTempTraits.get(idx).contains(trait)
				|| computeConditionalTraitsForTarget(card, false).contains(trait);
	}

	private boolean effectiveHasTrait(boolean isP1, int idx, CardData.Trait trait) {
		return isP1 ? effectiveP1HasTrait(idx, trait) : effectiveP2HasTrait(idx, trait);
	}

	/**
	 * Resolves combat between an attacker and a blocker.
	 * A forward breaks when the opponent's power equals or exceeds its own power.
	 * First Strike: if one side has it and the other doesn't, that side strikes first;
	 * if the strike kills the opponent, the survivor takes no damage.
	 */
	private void resolveCombat(CardData attacker, boolean attackerIsP1, int attackerIdx,
			CardData blocker, boolean blockerIsP1, int blockerIdx) {
		if (escapedFromBattle.contains(attacker)) {
			logEntry(attacker.name() + " escaped from the Battle — combat skipped");
			return;
		}
		if (escapedFromBattle.contains(blocker)) {
			logEntry(blocker.name() + " escaped from the Battle — combat skipped");
			return;
		}

		boolean attackerFirst = effectiveHasTrait(attackerIsP1, attackerIdx, CardData.Trait.FIRST_STRIKE)
				&& !effectiveHasTrait(blockerIsP1, blockerIdx, CardData.Trait.FIRST_STRIKE);
		boolean blockerFirst = effectiveHasTrait(blockerIsP1, blockerIdx, CardData.Trait.FIRST_STRIKE)
				&& !effectiveHasTrait(attackerIsP1, attackerIdx, CardData.Trait.FIRST_STRIKE);

		int effAttackerPow = attackerIsP1 ? effectiveP1ForwardPower(attackerIdx) : effectiveP2ForwardPower(attackerIdx);
		int effBlockerPow  = blockerIsP1  ? effectiveP1ForwardPower(blockerIdx)  : effectiveP2ForwardPower(blockerIdx);
		logEntry((attackerIsP1 ? "" : "[P2] ") + attacker.name() + " (" + effAttackerPow + ")"
				+ " vs " + (blockerIsP1 ? "" : "[P2] ") + blocker.name() + " (" + effBlockerPow + ")");

		// Compute actual damage each side deals after outgoing and incoming modifiers
		int rawDmgToBlocker  = modifyOutgoingCombatDamage(attackerIsP1, attackerIdx, effAttackerPow);
		int dmgToBlocker     = modifyIncomingDamage(blockerIsP1,  blockerIdx,  rawDmgToBlocker,  false, false);
		int rawDmgToAttacker = modifyOutgoingCombatDamage(blockerIsP1, blockerIdx, effBlockerPow);
		int dmgToAttacker    = modifyIncomingDamage(attackerIsP1, attackerIdx, rawDmgToAttacker, false, false);

		List<Integer> attackerDmgList = attackerIsP1 ? p1ForwardDamage : p2ForwardDamage;
		List<Integer> blockerDmgList  = blockerIsP1  ? p1ForwardDamage : p2ForwardDamage;
		boolean attackerBroken = dmgToAttacker > 0
				&& attackerDmgList.get(attackerIdx) + dmgToAttacker >= effAttackerPow;
		boolean blockerBroken  = dmgToBlocker  > 0
				&& blockerDmgList.get(blockerIdx)   + dmgToBlocker  >= effBlockerPow;

		if (attackerFirst && blockerBroken) {
			attackerBroken = false;
			dmgToAttacker  = 0; // attacker takes no return strike
		} else if (blockerFirst && attackerBroken) {
			blockerBroken = false;
			dmgToBlocker  = 0; // blocker takes no return strike
		}

		if (attackerBroken) {
			if (attackerIsP1) breakP1Forward(attackerIdx);
			else              breakP2Forward(attackerIdx);
		} else if (!blockerFirst && dmgToAttacker > 0) {
			List<Integer> dmgList = attackerIsP1 ? p1ForwardDamage : p2ForwardDamage;
			dmgList.set(attackerIdx, dmgList.get(attackerIdx) + dmgToAttacker);
			if (attackerIsP1) refreshP1ForwardSlot(attackerIdx); else refreshP2ForwardSlot(attackerIdx);
		}
		if (blockerBroken) {
			if (blockerIsP1) breakP1Forward(blockerIdx);
			else             breakP2Forward(blockerIdx);
		} else if (!attackerFirst && dmgToBlocker > 0) {
			List<Integer> dmgList = blockerIsP1 ? p1ForwardDamage : p2ForwardDamage;
			dmgList.set(blockerIdx, dmgList.get(blockerIdx) + dmgToBlocker);
			if (blockerIsP1) refreshP1ForwardSlot(blockerIdx); else refreshP2ForwardSlot(blockerIdx);
		}

		// Breaktouch (battle): temporary EOT grant — fires after main damage is resolved
		if (!blockerBroken && dmgToBlocker > 0 && breaktouchBattleSet.contains(attacker)) {
			logEntry((attackerIsP1 ? "" : "[P2] ") + attacker.name() + " — Breaktouch! "
					+ (blockerIsP1 ? "" : "[P2] ") + blocker.name() + " is broken.");
			if (blockerIsP1) breakP1Forward(blockerIdx); else breakP2Forward(blockerIdx);
			blockerBroken = true;
		}
		if (!attackerBroken && dmgToAttacker > 0 && breaktouchBattleSet.contains(blocker)) {
			logEntry((blockerIsP1 ? "" : "[P2] ") + blocker.name() + " — Breaktouch! "
					+ (attackerIsP1 ? "" : "[P2] ") + attacker.name() + " is broken.");
			if (attackerIsP1) breakP1Forward(attackerIdx); else breakP2Forward(attackerIdx);
			attackerBroken = true;
		}

		// Permanent "deals damage to forward" auto-abilities (e.g. Mandragora, Tonberry)
		if (dmgToBlocker > 0 && !blockerBroken) {
			if (fireBreaktouchForDamage(attacker, attackerIsP1, blockerIsP1, blockerIdx)) blockerBroken = true;
		}
		if (dmgToAttacker > 0 && !attackerBroken) {
			if (fireBreaktouchForDamage(blocker, blockerIsP1, attackerIsP1, attackerIdx)) attackerBroken = true;
		}

		if (!attackerBroken && !blockerBroken) {
			logEntry("Both forwards survive combat");
		}
	}

	/**
	 * P2 AI: returns the index of the best P2 blocker against {@code attacker},
	 * or -1 if P2 declines to block.
	 * Strategy: block with the highest-power active forward that can survive (power >= attacker) or trade evenly.
	 */
	private int p2ChooseBlocker(int effectiveAttackerPower, int attackerIdx) {
		// If the P1 attacker cannot be blocked at all, no blocker is possible
		if (p1ForwardCannotBeBlocked.contains(attackerIdx)) return -1;
		int[] costFilter = p1ForwardCannotBeBlockedByCost.get(attackerIdx);

		// Honour must-block forwards first: if any eligible must-block forward exists, the AI
		// must use one of them (preferring one that can survive the attack).
		if (!p2ForwardMustBlock.isEmpty()) {
			int bestIdx = -1, bestPower = -1;
			for (int i = 0; i < p2ForwardStates.size(); i++) {
				if (!p2ForwardMustBlock.contains(i))   continue;
				if (p2ForwardCannotBlock.contains(i) || p2ForwardCannotBlockPersistent.contains(i)) continue;
				if (costFilter != null && blockerCostExcluded(p2ForwardCards.get(i).cost(), costFilter)) continue;
				if (p2ForwardStates.get(i) != CardState.ACTIVE) continue;
				int effPow = effectiveP2ForwardPower(i);
				// Prefer a blocker that can survive; among those, the weakest (to preserve stronger ones)
				if (effPow >= effectiveAttackerPower && (bestIdx < 0 || effPow < bestPower)) {
					bestPower = effPow;
					bestIdx = i;
				}
			}
			if (bestIdx >= 0) return bestIdx;
			// Fall through: all must-block forwards are ineligible — constraint is lifted
		}

		int bestIdx = -1, bestPower = -1;
		for (int i = 0; i < p2ForwardStates.size(); i++) {
			if (p2ForwardCannotBlock.contains(i) || p2ForwardCannotBlockPersistent.contains(i)) continue;
			if (costFilter != null && blockerCostExcluded(p2ForwardCards.get(i).cost(), costFilter)) continue;
			if (p2ForwardStates.get(i) != CardState.ACTIVE) continue;
			int effPow = effectiveP2ForwardPower(i);
			if (effPow >= effectiveAttackerPower && effPow > bestPower) {
				bestPower = effPow;
				bestIdx = i;
			}
		}
		return bestIdx;
	}

	private static boolean blockerCostExcluded(int blockerCost, int[] costFilter) {
		return costFilter[1] == 1 ? blockerCost >= costFilter[0] : blockerCost <= costFilter[0];
	}

	/** Called after P1 attacks: gives P2 AI a chance to declare a blocker. */
	private void p2OfferBlock(CardData attacker, int attackerIdx) {
		int blockerIdx = p2ChooseBlocker(effectiveP1ForwardPower(attackerIdx), attackerIdx);
		if (blockerIdx >= 0) {
			CardData blocker = p2ForwardCards.get(blockerIdx);
			logEntry("[P2] " + blocker.name() + " blocks!");
			triggerAutoAbilitiesForBlock(blocker, false);
			triggerAutoAbilitiesForIsBlocked(attacker, true);
			resolveCombat(attacker, true, attackerIdx, blocker, false, blockerIdx);
		} else {
			p2TakeDamage();
			triggerAutoAbilitiesForDealsDamageToOpponent(attacker, true);
		}
	}

	/**
	 * Called when P2 attacks: sets up interactive block declaration so P1 can click
	 * a forward on the field (or click "Take Damage") instead of using a modal dialog.
	 * {@code onDone} is called asynchronously after combat or damage resolves.
	 */
	private void initP1BlockDeclaration(CardData attacker, int attackerIdx, Runnable onDone) {
		// Compute eligible blockers
		boolean anyEligible = false;
		for (int i = 0; i < p1ForwardStates.size(); i++) {
			CardState s = p1ForwardStates.get(i);
			if ((s == CardState.ACTIVE || s == CardState.BRAVE_ATTACKED)
					&& !p1ForwardCannotBlock.contains(i)
					&& !p1ForwardCannotBlockPersistent.contains(i)) {
				anyEligible = true;
				break;
			}
		}

		if (!anyEligible) {
			// No eligible blockers — auto-take damage
			p1TakeDamage(() -> {
				triggerAutoAbilitiesForDealsDamageToOpponent(attacker, false);
				onDone.run();
			});
			return;
		}

		int displayPow = pendingP2AttackerIsMonster ? pendingP2AttackerPower : effectiveP2ForwardPower(attackerIdx);
		logEntry("[P2] " + attacker.name() + " (" + displayPow + ") attacks!"
				+ " Select a blocker or click 'Take Damage'.");

		// Store pending state so the attack button and forward clicks know what to do
		pendingP2Attacker    = attacker;
		pendingP2AttackerIdx = attackerIdx;
		pendingP2BlockDone   = onDone;
		p1BlockerSelection   = -1;

		setAttackSubStep(2);
		refreshPhaseTracker();
		refreshAttackButton();
		refreshAllForwardSlots();

		// P2 attacked → P2 gets priority first, then P1
		combatPriority("P2 Attacker Declared", false, () -> {
			// After both priorities pass, P1 can now select a blocker via field clicks
			// The attack button already shows "Take Damage" — nothing more to do here;
			// handleP1BlockAction() fires when P1 clicks the button.
		});
	}

	private void initP1BlockDeclarationVsParty(List<Integer> attackerIndices, int combinedPower, Runnable onDone) {
		boolean anyEligible = false;
		for (int i = 0; i < p1ForwardStates.size(); i++) {
			CardState s = p1ForwardStates.get(i);
			if ((s == CardState.ACTIVE || s == CardState.BRAVE_ATTACKED)
					&& !p1ForwardCannotBlock.contains(i)
					&& !p1ForwardCannotBlockPersistent.contains(i)) {
				anyEligible = true;
				break;
			}
		}

		if (!anyEligible) {
			p1TakeDamage();
			for (int idx : attackerIndices)
				triggerAutoAbilitiesForDealsDamageToOpponent(p2ForwardCards.get(idx), false);
			onDone.run();
			return;
		}

		StringBuilder names = new StringBuilder();
		for (int idx : attackerIndices) {
			if (names.length() > 0) names.append(", ");
			names.append(p2ForwardCards.get(idx).name());
		}
		logEntry("[P2] Party Attack: " + names + " (" + combinedPower
				+ " combined)! Select a blocker or click 'Take Damage'.");

		pendingP2PartyIndices  = new ArrayList<>(attackerIndices);
		pendingP2PartyCombined = combinedPower;
		pendingP2BlockDone     = onDone;
		p1BlockerSelection     = -1;

		setAttackSubStep(2);
		refreshPhaseTracker();
		refreshAttackButton();
		refreshAllForwardSlots();

		combatPriority("P2 Party Attacker Declared", false, () -> { });
	}


	private void showP2DamageZoneDialog() {
		List<CardData> zone = gameState.getP2DamageZone();
		if (zone.isEmpty()) return;

		JDialog dlg = new JDialog(frame, "P2 Damage Zone (" + zone.size() + " cards)", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		for (CardData cd : zone) {
			JPanel cardWrapper = new JPanel(new BorderLayout(0, 4));
			cardWrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(cd.exBurst()
					? createCardGlowBorder(Color.YELLOW)
					: BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() != null) showZoomAt(cd.imageUrl());
				}
				@Override public void mouseExited(MouseEvent e) { hideZoom(); }
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					if (img == null) return null;
					BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = buf.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
					g2.dispose();
					return new ImageIcon(buf);
				}
				@Override protected void done() {
					try {
						ImageIcon icon = get();
						if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
					} catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			String labelText = cd.name() + (cd.exBurst() ? " EX" : "");
			JLabel nameLabel = cd.exBurst() ? new JLabel(labelText, SwingConstants.CENTER) {
				@Override protected void paintComponent(Graphics g) {
					Graphics2D g2 = (Graphics2D) g.create();
					g2.setFont(getFont());
					FontMetrics fm = g2.getFontMetrics();
					int x = (getWidth() - fm.stringWidth(labelText)) / 2;
					int y = fm.getAscent() + (getHeight() - fm.getHeight()) / 2;
					g2.setColor(new Color(0, 0, 0, 180));
					g2.drawString(labelText, x + 1, y + 1);
					g2.setColor(Color.YELLOW);
					g2.drawString(labelText, x, y);
					g2.dispose();
				}
			} : new JLabel(labelText, SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			if (!cd.exBurst()) nameLabel.setForeground(null);
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			cardWrapper.add(lbl,       BorderLayout.CENTER);
			cardWrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(cardWrapper);
		}

		JScrollPane scrollPane = new JScrollPane(cardsPanel,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(
				Math.min(zone.size() * (CARD_W + 16) + 16, 900),
				CARD_H + 60));

		JButton closeBtn = new JButton("Close");
		closeBtn.setFont(FontLoader.loadPixelNESFont(11));
		closeBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		south.add(closeBtn);
		south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
		dlg.getContentPane().add(south,      BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	private void showDamageZoneDialog() {
		List<CardData> zone = gameState.getP1DamageZone();
		if (zone.isEmpty()) return;

		JDialog dlg = new JDialog(frame, "Damage Zone (" + zone.size() + " cards)", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		for (CardData cd : zone) {
			JPanel cardWrapper = new JPanel(new BorderLayout(0, 4));
			cardWrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(cd.exBurst()
					? createCardGlowBorder(Color.YELLOW)
					: BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() != null) showZoomAt(cd.imageUrl());
				}
				@Override public void mouseExited(MouseEvent e) { hideZoom(); }
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					if (img == null) return null;
					BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = buf.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
					g2.dispose();
					return new ImageIcon(buf);
				}
				@Override protected void done() {
					try {
						ImageIcon icon = get();
						if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
					} catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			String labelText = cd.name() + (cd.exBurst() ? " EX" : "");
			JLabel nameLabel = cd.exBurst() ? new JLabel(labelText, SwingConstants.CENTER) {
				@Override protected void paintComponent(Graphics g) {
					Graphics2D g2 = (Graphics2D) g.create();
					g2.setFont(getFont());
					FontMetrics fm = g2.getFontMetrics();
					int x = (getWidth() - fm.stringWidth(labelText)) / 2;
					int y = fm.getAscent() + (getHeight() - fm.getHeight()) / 2;
					g2.setColor(new Color(0, 0, 0, 180));
					g2.drawString(labelText, x + 1, y + 1);
					g2.setColor(Color.YELLOW);
					g2.drawString(labelText, x, y);
					g2.dispose();
				}
			} : new JLabel(labelText, SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			if (!cd.exBurst()) nameLabel.setForeground(null);
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			cardWrapper.add(lbl,       BorderLayout.CENTER);
			cardWrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(cardWrapper);
		}

		JScrollPane scrollPane = new JScrollPane(cardsPanel,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(
				Math.min(zone.size() * (CARD_W + 16) + 16, 900),
				CARD_H + 60));

		JButton closeBtn = new JButton("Close");
		closeBtn.setFont(FontLoader.loadPixelNESFont(11));
		closeBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		south.add(closeBtn);
		south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
		dlg.getContentPane().add(south,      BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	private void showLbDialog() {
		List<CardData> lbDeck = gameState.getP1LbDeck();
		if (lbDeck.isEmpty()) return;

		JDialog dlg = new JDialog(frame, "Limit Break", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		// Track which cards are being cast / selected for payment in this dialog session
		int[] castingIdx       = { -1 };   // index of card being cast
		int[] paymentChosen    = { 0 };    // how many payment cards selected so far
		Set<Integer> paymentSet = new HashSet<>();

		JLabel statusLabel = new JLabel(" ", SwingConstants.CENTER);
		statusLabel.setFont(FontLoader.loadPixelNESFont(10));

		JButton confirmCastBtn = new JButton("Confirm Cast");
		confirmCastBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmCastBtn.setVisible(false);

		JButton cancelCastBtn = new JButton("Cancel");
		cancelCastBtn.setFont(FontLoader.loadPixelNESFont(11));
		cancelCastBtn.setVisible(false);

		// One label per LB card
		List<JLabel> cardLabels = new ArrayList<>();
		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		Runnable refreshLabels = () -> {
			for (int i = 0; i < cardLabels.size(); i++) {
				JLabel lbl = cardLabels.get(i);
				CardData lcd = lbDeck.get(i);
				boolean spent       = spentLbIndices.contains(i);
				boolean casting     = (castingIdx[0] == i);
				boolean payment     = paymentSet.contains(i);
				boolean inPaymentMode = castingIdx[0] >= 0;
				boolean nameBlocked = !inPaymentMode && !spent
						&& (lcd.isForward() || lcd.isBackup() || lcd.isMonster())
						&& ((!lcd.multicard() && hasCharacterNameOnField(lcd.name()))
							|| (lcd.isLightOrDark() && hasLightOrDarkOnField(true)));

				if (casting) {
					lbl.setBorder(createCardGlowBorder(new Color(255, 200, 0)));
				} else if (payment) {
					lbl.setBorder(createCardGlowBorder(Color.CYAN));
				} else if (spent) {
					lbl.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
				} else if (nameBlocked) {
					lbl.setBorder(createCardGlowBorder(Color.RED));
				} else {
					lbl.setBorder(BorderFactory.createLineBorder(
							inPaymentMode ? Color.GRAY : Color.LIGHT_GRAY, 1));
				}
				boolean canInteract = !spent && !nameBlocked && !casting
						&& (castingIdx[0] < 0 || !paymentSet.contains(i) || payment);
				lbl.setCursor(canInteract
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			if (castingIdx[0] >= 0) {
				int needed = lbDeck.get(castingIdx[0]).lbCost() - paymentSet.size();
				statusLabel.setText(needed > 0
						? "Choose " + needed + " more LB card(s) as payment"
						: "Ready — click Confirm Cast");
				confirmCastBtn.setEnabled(needed <= 0);
			} else {
				statusLabel.setText(" ");
			}
		};

		confirmCastBtn.addActionListener(ae -> {
			CardData cast = lbDeck.get(castingIdx[0]);
			dlg.dispose();
			if (cast.cost() == 0) {
				// No CP dialog — commit immediately
				spentLbIndices.add(castingIdx[0]);
				spentLbIndices.addAll(paymentSet);
				logEntry("Cast LB \"" + cast.name() + "\"");
				executeLbPlay(cast, Collections.emptyList(), Collections.emptyList());
			} else {
				// Defer committing until CP payment is confirmed
				int pendingCastIdx = castingIdx[0];
				Set<Integer> pendingPayment = new java.util.HashSet<>(paymentSet);
				showLbCpPaymentDialog(cast, pendingCastIdx, pendingPayment);
			}
		});

		cancelCastBtn.addActionListener(ae -> {
			hideZoom();
			castingIdx[0] = -1;
			paymentSet.clear();
			paymentChosen[0] = 0;
			confirmCastBtn.setVisible(false);
			cancelCastBtn.setVisible(false);
			refreshLabels.run();
		});

		for (int i = 0; i < lbDeck.size(); i++) {
			final int idx = i;
			CardData  cd  = lbDeck.get(i);

			JPanel cardWrapper = new JPanel(new BorderLayout(0, 4));
			cardWrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
			cardLabels.add(lbl);

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() != null) showZoomAt(cd.imageUrl());
				}
				@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				@Override public void mousePressed(MouseEvent e) {
					boolean spent = spentLbIndices.contains(idx);
					if (spent) return;
					boolean nameBlocked = castingIdx[0] < 0
							&& (cd.isForward() || cd.isBackup() || cd.isMonster())
							&& ((!cd.multicard() && hasCharacterNameOnField(cd.name()))
								|| (cd.isLightOrDark() && hasLightOrDarkOnField(true)));
					if (nameBlocked) return;

					if (castingIdx[0] < 0) {
						// Start casting this card
						castingIdx[0] = idx;
						paymentSet.clear();
						paymentChosen[0] = 0;
						confirmCastBtn.setVisible(true);
						cancelCastBtn.setVisible(true);
						confirmCastBtn.setEnabled(cd.lbCost() == 0);
					} else if (castingIdx[0] == idx) {
						// Click on casting card — cancel
						castingIdx[0] = -1;
						paymentSet.clear();
						confirmCastBtn.setVisible(false);
						cancelCastBtn.setVisible(false);
					} else {
						// Toggle payment selection
						if (paymentSet.contains(idx)) {
							paymentSet.remove(idx);
						} else if (paymentSet.size() < lbDeck.get(castingIdx[0]).lbCost()) {
							paymentSet.add(idx);
						}
					}
					refreshLabels.run();
				}
			});

			// Load full image, greyed if spent
			final boolean spent = spentLbIndices.contains(i);
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					if (img == null) return null;
					BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = buf.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
					g2.dispose();
					if (spent) {
						return new ImageIcon(new ColorConvertOp(
								ColorSpace.getInstance(ColorSpace.CS_GRAY), null).filter(buf, null));
					}
					return new ImageIcon(buf);
				}
				@Override protected void done() {
					try {
						ImageIcon icon = get();
						if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
					} catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(cd.name() + " - LB " + cd.lbCost() + "",
					SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			cardWrapper.add(lbl,       BorderLayout.CENTER);
			cardWrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(cardWrapper);
		}

		refreshLabels.run();

		JScrollPane scrollPane = new JScrollPane(cardsPanel,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(
				Math.min(lbDeck.size() * (CARD_W + 16) + 16, 900),
				CARD_H + 60));

		JPanel statusBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 4));
		statusBar.add(statusLabel);
		statusBar.add(confirmCastBtn);
		statusBar.add(cancelCastBtn);

		JButton closeBtn = new JButton("Close");
		closeBtn.setFont(FontLoader.loadPixelNESFont(11));
		closeBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

		JPanel south = new JPanel(new BorderLayout());
		south.add(statusBar,  BorderLayout.CENTER);
		south.add(closeBtn,   BorderLayout.EAST);
		south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
		dlg.getContentPane().add(south,      BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	private void showEndPhaseDiscardDialog() {
		List<CardData> hand = gameState.getP1Hand();
		if (hand.size() <= 5) return;

		JDialog dlg = new JDialog(frame, true);
		dlg.setUndecorated(true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		Set<Integer> selected = new HashSet<>();
		int mustDiscard = hand.size() - 5;

		JLabel statusLabel = new JLabel("Select " + mustDiscard + " card(s) to discard.", SwingConstants.CENTER);
		statusLabel.setFont(FontLoader.loadPixelNESFont(10));

		List<JLabel> cardLabels = new ArrayList<>();
		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setEnabled(false);

		Runnable refresh = () -> {
			int remaining = mustDiscard - selected.size();
			statusLabel.setText(remaining > 0
					? "Select " + remaining + " more card(s) to discard."
					: "Ready — click Confirm to discard.");
			confirmBtn.setEnabled(selected.size() == mustDiscard);
			for (int i = 0; i < cardLabels.size(); i++) {
				cardLabels.get(i).setBorder(selected.contains(i)
						? createCardGlowBorder(Color.RED)
						: BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
			}
		};

		for (int i = 0; i < hand.size(); i++) {
			final int idx = i;
			CardData cd = hand.get(i);

			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setForeground(Color.WHITE);
			lbl.setFont(FontLoader.loadPixelNESFont(10));
			lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
			lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			cardLabels.add(lbl);

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) showZoomAt(cd.imageUrl()); }
				@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				@Override public void mousePressed(MouseEvent e) {
					if (selected.contains(idx)) selected.remove(idx);
					else if (selected.size() < mustDiscard)  selected.add(idx);
					refresh.run();
				}
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon icon = get(); if (icon != null) { lbl.setIcon(icon); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			wrapper.add(lbl,       BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		confirmBtn.addActionListener(ae -> {
			hideZoom();
			List<Integer> toDiscard = new ArrayList<>(selected);
			toDiscard.sort(Collections.reverseOrder());
			for (int di : toDiscard) {
				gameState.breakFromHand(di);
			}
			logEntry("Discarded " + toDiscard.size() + " card(s) — hand reduced to 5");
			refreshP1HandLabel();
			refreshP1BreakLabel();
			dlg.dispose();
		});

		JScrollPane scrollPane = new JScrollPane(cardsPanel,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(
				Math.min(hand.size() * (CARD_W + 16) + 16, 900),
				CARD_H + 60));

		JLabel titleLabel = new JLabel("End Phase — Discard to 5", SwingConstants.CENTER);
		titleLabel.setFont(FontLoader.loadPixelNESFont(14));

		JPanel south = new JPanel(new BorderLayout());
		south.add(statusLabel, BorderLayout.CENTER);
		south.add(confirmBtn,  BorderLayout.EAST);

		JPanel mainPanel = new JPanel(new BorderLayout(0, 6));
		mainPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createRaisedBevelBorder(),
				BorderFactory.createEmptyBorder(8, 8, 8, 8)));
		mainPanel.add(titleLabel,  BorderLayout.NORTH);
		mainPanel.add(scrollPane,  BorderLayout.CENTER);
		mainPanel.add(south,       BorderLayout.SOUTH);

		dlg.getContentPane().add(mainPanel);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	/**
	 * Shows a modal dialog letting P1 choose exactly {@code count} cards
	 * (or fewer if hand is smaller) to discard to the Break Zone.  No CP is generated.
	 * Called when P2 activates a "Your opponent discards N cards" ability.
	 */
	private void showForcedDiscardDialog(int count, boolean forcedByOpponent) {
		List<CardData> hand = gameState.getP1Hand();
		int mustDiscard = Math.min(count, hand.size());
		if (mustDiscard == 0) return;

		JDialog dlg = new JDialog(frame, "Discard " + mustDiscard + " Card(s)", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		Set<Integer> selected = new HashSet<>();

		JLabel statusLabel = new JLabel("Select " + mustDiscard + " card(s) to discard.", SwingConstants.CENTER);
		statusLabel.setFont(FontLoader.loadPixelNESFont(10));

		List<JLabel> cardLabels = new ArrayList<>();
		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		JButton confirmBtn = new JButton("Discard");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setEnabled(false);

		Runnable refresh = () -> {
			int remaining = mustDiscard - selected.size();
			statusLabel.setText(remaining > 0
					? "Select " + remaining + " more card(s) to discard."
					: "Ready — click Discard to confirm.");
			confirmBtn.setEnabled(selected.size() == mustDiscard);
			for (int i = 0; i < cardLabels.size(); i++) {
				cardLabels.get(i).setBorder(BorderFactory.createLineBorder(
						selected.contains(i) ? Color.RED : Color.LIGHT_GRAY,
						selected.contains(i) ? 3 : 1));
			}
		};

		for (int i = 0; i < hand.size(); i++) {
			final int idx = i;
			CardData cd = hand.get(i);

			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
			lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			cardLabels.add(lbl);

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) showZoomAt(cd.imageUrl()); }
				@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				@Override public void mousePressed(MouseEvent e) {
					if (selected.contains(idx)) selected.remove(idx);
					else if (selected.size() < mustDiscard) selected.add(idx);
					refresh.run();
				}
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					if (img == null) return null;
					BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = buf.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
					g2.dispose();
					return new ImageIcon(buf);
				}
				@Override protected void done() {
					try { ImageIcon icon = get(); if (icon != null) { lbl.setIcon(icon); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			wrapper.add(lbl,       BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		confirmBtn.addActionListener(ae -> {
			hideZoom();
			dlg.dispose();
			List<Integer> toDiscard = new ArrayList<>(selected);
			toDiscard.sort(Collections.reverseOrder());
			for (int di : toDiscard) {
				CardData d = gameState.breakFromHand(di);
				if (d != null) logEntry("Discards " + d.name() + (forcedByOpponent ? " (forced by opponent)" : ""));
			}
			refreshP1HandLabel();
			refreshP1BreakLabel();
		});

		JScrollPane scrollPane = new JScrollPane(cardsPanel,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(
				Math.min(hand.size() * (CARD_W + 16) + 16, 900),
				CARD_H + 60));

		JPanel south = new JPanel(new BorderLayout());
		south.add(statusLabel, BorderLayout.CENTER);
		south.add(confirmBtn,  BorderLayout.EAST);
		south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
		dlg.getContentPane().add(south,      BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	/**
	 * Shows a modal dialog letting P1 select {@code count} cards from {@code targetHand}
	 * to remove from the game permanently.
	 * If {@code rfpIsP1}, the cards go to P1's permanent RFP zone (P1 removing from own hand);
	 * otherwise they go to P2's (P1 selecting from P2's revealed hand).
	 */
	private void showHandRfpSelectionDialog(List<CardData> targetHand, int count, boolean rfpIsP1) {
		int mustSelect = Math.min(count, targetHand.size());
		if (mustSelect == 0) return;

		JDialog dlg = new JDialog(frame, "Remove " + mustSelect + " Card(s) From Game", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		Set<Integer> selected = new HashSet<>();

		JLabel statusLabel = new JLabel("Select " + mustSelect + " card(s) to remove from the game.", SwingConstants.CENTER);
		statusLabel.setFont(FontLoader.loadPixelNESFont(10));

		List<JLabel> cardLabels = new ArrayList<>();
		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		JButton confirmBtn = new JButton("Remove From Game");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setEnabled(false);

		Runnable refresh = () -> {
			int remaining = mustSelect - selected.size();
			statusLabel.setText(remaining > 0
					? "Select " + remaining + " more card(s) to remove."
					: "Ready — click Remove From Game to confirm.");
			confirmBtn.setEnabled(selected.size() == mustSelect);
			for (int i = 0; i < cardLabels.size(); i++) {
				cardLabels.get(i).setBorder(BorderFactory.createLineBorder(
						selected.contains(i) ? new Color(0xff8800) : Color.LIGHT_GRAY,
						selected.contains(i) ? 3 : 1));
			}
		};

		for (int i = 0; i < targetHand.size(); i++) {
			final int idx = i;
			CardData cd = targetHand.get(i);

			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
			lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			cardLabels.add(lbl);

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) showZoomAt(cd.imageUrl()); }
				@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				@Override public void mousePressed(MouseEvent e) {
					if (selected.contains(idx)) selected.remove(idx);
					else if (selected.size() < mustSelect) selected.add(idx);
					refresh.run();
				}
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					if (img == null) return null;
					BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = buf.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
					g2.dispose();
					return new ImageIcon(buf);
				}
				@Override protected void done() {
					try { ImageIcon icon = get(); if (icon != null) { lbl.setIcon(icon); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			wrapper.add(lbl,       BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		confirmBtn.addActionListener(ae -> {
			hideZoom();
			dlg.dispose();
			List<Integer> toRemove = new ArrayList<>(selected);
			toRemove.sort(Collections.reverseOrder());
			for (int ri : toRemove) {
				if (ri < targetHand.size()) {
					CardData d = targetHand.remove(ri);
					if (rfpIsP1) {
						gameState.addToP1PermanentRfp(d);
						logEntry("Removed from game: " + d.name());
					} else {
						gameState.addToP2PermanentRfp(d);
						logEntry("[P2] Removed from game (selected by P1): " + d.name());
					}
				}
			}
			if (rfpIsP1) { refreshP1HandLabel(); refreshP1WarpZoneUI(); }
			else          { refreshP2HandCountLabel(); }
		});

		JScrollPane scrollPane = new JScrollPane(cardsPanel,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(
				Math.min(targetHand.size() * (CARD_W + 16) + 16, 900),
				CARD_H + 60));

		JPanel south = new JPanel(new BorderLayout(4, 4));
		south.add(statusLabel, BorderLayout.NORTH);
		south.add(confirmBtn,  BorderLayout.SOUTH);

		dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
		dlg.getContentPane().add(south,      BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	// -------------------------------------------------------------------------
	// Async image loading helpers
	// -------------------------------------------------------------------------

	/**
	 * Loads the card image for {@code url} at its native resolution and
	 * displays it in the side-panel preview.  The first time this is called
	 * the side panel is resized to exactly fit the card plus {@link #SIDE_MARGIN}.
	 */
	private void showZoomAt(String url) {
		if (url == null || cardPreviewPanel == null) return;
		new SwingWorker<BufferedImage, Void>() {
			@Override
			protected BufferedImage doInBackground() throws Exception {
				Image img = ImageCache.load(url);
				if (img == null) return null;
				int w = img.getWidth(null);
				int h = img.getHeight(null);
				BufferedImage buf = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
				buf.getGraphics().drawImage(img, 0, 0, null);
				return buf;
			}
			@Override
			protected void done() {
				try {
					BufferedImage img = get();
					if (img == null) return;
					sizePreviewPanel(img.getWidth(), img.getHeight());
					previewImage = img;
					startFadeIn();
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	/** Clears the side-panel card preview with a fade-out. */
	private void hideZoom() {
		startFadeOut();
	}

	/** Fades the preview in from transparent to opaque (~120 ms). */
	private void startFadeIn() {
		if (fadeTimer != null) fadeTimer.stop();
		previewAlpha = 0f;
		cardPreviewPanel.repaint();
		fadeTimer = new javax.swing.Timer(16, e -> {
			previewAlpha = Math.min(1f, previewAlpha + 0.15f);
			cardPreviewPanel.repaint();
			if (previewAlpha >= 1f) ((javax.swing.Timer) e.getSource()).stop();
		});
		fadeTimer.start();
	}

	/** Fades the preview out to transparent (~120 ms), then clears the image. */
	private void startFadeOut() {
		if (fadeTimer != null) fadeTimer.stop();
		if (cardPreviewPanel == null) { previewImage = null; return; }
		fadeTimer = new javax.swing.Timer(16, e -> {
			previewAlpha = Math.max(0f, previewAlpha - 0.15f);
			cardPreviewPanel.repaint();
			if (previewAlpha <= 0f) {
				((javax.swing.Timer) e.getSource()).stop();
				previewImage = null;
				cardPreviewPanel.repaint();
			}
		});
		fadeTimer.start();
	}

	/**
	 * On the first call, resizes the side panel and preview panel to the card's
	 * native image dimensions scaled by PREVIEW_SCALE, and establishes the min/max
	 * bounds for user-driven sidebar resizing. Subsequent calls are no-ops.
	 */
	private void sizePreviewPanel(int imgW, int imgH) {
		if (previewSized) return;
		previewSized  = true;
		nativeImgW    = imgW;
		nativeImgH    = imgH;
		minSidePanelW = (int)(imgW * 0.75) + SIDE_MARGIN;
		maxSidePanelW = imgW + SIDE_MARGIN;
		int defaultW  = (int)(imgW * PREVIEW_SCALE) + SIDE_MARGIN;
		int savedW    = AppSettings.getSidePanelWidth(defaultW);
		// Clamp to valid range; fall back to default if saved value is out of bounds
		int initialW  = (savedW >= minSidePanelW && savedW <= maxSidePanelW) ? savedW : defaultW;
		setSidePanelWidth(initialW);
	}

	private void setSidePanelWidth(int w) {
		sidePanelW = w;
		previewH = nativeImgH > 0
				? (int)((w - SIDE_MARGIN) * (double) nativeImgH / nativeImgW)
				: (int)(w * (double) CARD_H / CARD_W);
		cardPreviewPanel.setPreferredSize(new Dimension(w, previewH));
		cardPreviewPanel.setMinimumSize  (new Dimension(w, previewH));
		cardPreviewPanel.setMaximumSize  (new Dimension(w, previewH));
		sidePanel.setPreferredSize(new Dimension(w, 0));
		handPanel.setPreferredSize(new Dimension(w, (int)(CARD_H * 0.6)));
		if (sideWrapper != null)
			sideWrapper.setPreferredSize(new Dimension(w + RESIZE_HANDLE_W, 0));
		refreshHandPanel();
		frame.revalidate();
		frame.repaint();
	}

	// -------------------------------------------------------------------------
	// Hand card zoom / popup helpers
	// -------------------------------------------------------------------------

	private void showHandPopup() {
		cancelHandPopupHide();
		if (handPopup != null && handPopup.isVisible()) return;  // already open

		if (handPopup != null) { handPopup.dispose(); }
		handPopup = new JWindow(frame);

		List<CardData> hand = gameState.getP1Hand();

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
		cardsPanel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createRaisedBevelBorder(),
				BorderFactory.createEmptyBorder(4, 4, 4, 4)));
		cardsPanel.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent e) { cancelHandPopupHide(); }
			@Override public void mouseExited(MouseEvent e) { scheduleHandPopupHide(); }
		});

		for (int i = 0; i < hand.size(); i++) {
			final int idx = i;
			final CardData card = hand.get(i);
			final String url = card.imageUrl();

			JLabel lbl = new JLabel("Loading...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setForeground(Color.WHITE);
			lbl.setFont(FontLoader.loadPixelNESFont(10));
			lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
			lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					cancelHandPopupHide();
					showHandCardZoom(url);
				}
				@Override public void mouseExited(MouseEvent e) {
					hideZoom();
					scheduleHandPopupHide();
				}
				@Override public void mousePressed(MouseEvent e) {
					onHandPopupCardClicked(idx, card, lbl, e);
				}
			});

			int effectiveCost = effectiveCastCost(card);
			int delta = card.cost() - effectiveCost;

			GameState.GamePhase handPhase = gameState.getCurrentPhase();
			boolean handIsMainPhase = handPhase == GameState.GamePhase.MAIN_1 || handPhase == GameState.GamePhase.MAIN_2;
			boolean handCanPlayAction = handIsMainPhase && phaseTracker.isMyTurn() && gameState.getStack().isEmpty();
			boolean handIsCharacter = card.isForward() || card.isBackup() || card.isMonster();
			boolean handNameConflict = handIsCharacter && !card.multicard() && hasCharacterNameOnField(card.name());
			boolean handLightDarkConflict = handIsCharacter && card.isLightOrDark() && hasLightOrDarkOnField(true);
			final boolean canPlay = handCanPlayAction && !handNameConflict && !handLightDarkConflict
					&& canAffordCard(card, idx) && (!card.isBackup() || hasAvailableBackupSlot()) && castRestrictionMet(card);

			// Load image async; bake cost pill into the image when cost differs from base
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(url);
					if (img == null) return null;
					BufferedImage bi = CardAnimation.toARGB(img, CARD_W, CARD_H);
					if (delta != 0 || canPlay) {
						Graphics2D g2 = bi.createGraphics();
						g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
						if (delta != 0) {
							String text = String.valueOf(effectiveCost);
							g2.setFont(FontLoader.loadPixelNESFont(15));
							FontMetrics fm = g2.getFontMetrics();
							int x = 8, y = fm.getAscent() + 7;
							g2.setColor(Color.BLACK);
							g2.drawString(text, x + 1, y + 1);
							g2.drawString(text, x + 2, y + 1);
							g2.drawString(text, x + 1, y + 2);
							g2.drawString(text, x + 2, y + 2);
							g2.setColor(delta > 0 ? new Color(0x44EE44) : new Color(0xFF8844));
							g2.drawString(text, x, y);
						}
						if (canPlay) {
							CardAnimation.drawGlow(g2, new Color(30, 144, 255), 0, 0, CARD_W, CARD_H);
						}
						g2.dispose();
					}
					return new ImageIcon(bi);
				}
				@Override protected void done() {
					try {
						ImageIcon icon = get();
						if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
					} catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			cardsPanel.add(lbl);
		}

		handPopup.getContentPane().add(cardsPanel);
		handPopup.pack();

		// Position above the hand panel: extend right for left sidebar, left for right sidebar
		Point loc = handPanel.getLocationOnScreen();
		Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
		boolean sidebarOnRight = "right".equals(AppSettings.getSidePanelSide());
		int x = sidebarOnRight
				? loc.x + handPanel.getWidth() - handPopup.getWidth()
				: loc.x;
		int y = loc.y - handPopup.getHeight() - 4;
		x = Math.max(0, Math.min(x, screen.width  - handPopup.getWidth()));
		y = Math.max(0, Math.min(y, screen.height - handPopup.getHeight()));
		handPopup.setLocation(x, y);
		handPopup.setVisible(true);
	}

	/** Dismisses the hand popover after a short delay (cancelled if mouse re-enters). */
	private void scheduleHandPopupHide() {
		if (handCardMenuOpen) return;
		if (handPopupHideTimer != null) handPopupHideTimer.stop();
		handPopupHideTimer = new javax.swing.Timer(120, e -> {
			if (handPopup != null) { handPopup.dispose(); handPopup = null; }
			handPopupHideTimer = null;
		});
		handPopupHideTimer.setRepeats(false);
		handPopupHideTimer.start();
	}

	private void cancelHandPopupHide() {
		if (handPopupHideTimer != null) { handPopupHideTimer.stop(); handPopupHideTimer = null; }
	}

	private void refreshHandPopupIfVisible() {
		if (handPopup == null || !handPopup.isVisible()) return;
		handPopup.dispose();
		handPopup = null;
		showHandPopup();
	}

	private void onHandPopupCardClicked(int handIdx, CardData card, JLabel cardLabel, MouseEvent e) {
		if (gameState.isP1GameOver()) return;
		cancelHandPopupHide();
		handCardMenuOpen = true;

		JPopupMenu menu = new JPopupMenu();

		JMenuItem playItem = new JMenuItem("Play");
		GameState.GamePhase phase = gameState.getCurrentPhase();
		boolean isMainPhase = phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.MAIN_2;
		boolean canPlaySpecialAction = isMainPhase && phaseTracker.isMyTurn() && gameState.getStack().isEmpty();
		boolean isCharacter = card.isForward() || card.isBackup() || card.isMonster();
		boolean nameConflict = isCharacter && !card.multicard() && hasCharacterNameOnField(card.name());
		boolean lightDarkConflict = isCharacter && card.isLightOrDark() && hasLightOrDarkOnField(true);
		playItem.setEnabled(canPlaySpecialAction && !nameConflict && !lightDarkConflict && canAffordCard(card, handIdx)
				&& (!card.isBackup() || hasAvailableBackupSlot()) && castRestrictionMet(card));
		playItem.addActionListener(ae -> {
			hideZoom();
			if (handPopup != null) { handPopup.dispose(); handPopup = null; }
			showPaymentDialog(card, handIdx);
		});
		menu.add(playItem);

		if (card.hasWarp()) {
			JMenuItem warpItem = new JMenuItem("Play (Warp " + card.warpValue() + ")");
			warpItem.setEnabled(canPlaySpecialAction && canAffordWarpCost(card, handIdx) && castRestrictionMet(card));
			warpItem.addActionListener(ae -> {
				hideZoom();
				if (handPopup != null) { handPopup.dispose(); handPopup = null; }
				showWarpPaymentDialog(card, handIdx);
			});
			menu.add(warpItem);
		}

		if (card.altCrystalCost() > 0 || card.altCpCost() > 0) {
			int ac = card.altCrystalCost();
			List<String> altElems = card.altCpElements();
			String crystalStr = ac > 0 ? "《C》".repeat(ac) : "";
			String cpStr = altElems.isEmpty() ? "" : (ac > 0 ? " + " : "") + altElems.stream()
					.collect(java.util.stream.Collectors.groupingBy(elem -> elem.isEmpty() ? "generic" : elem, LinkedHashMap::new, java.util.stream.Collectors.counting()))
					.entrySet().stream().map(en -> (en.getKey().equals("generic") ? en.getValue() + " CP" : en.getValue() + " " + en.getKey() + " CP")).collect(java.util.stream.Collectors.joining(" + "));
			List<String> cond = card.altConditionCardNames();
			String condStr = cond.isEmpty() ? "" : " [req: " + String.join("/", cond) + "]";
			String altLabel = "Play (Alt: " + crystalStr + cpStr + condStr + ")";
			JMenuItem altItem = new JMenuItem(altLabel);
			altItem.setEnabled(canPlaySpecialAction && !nameConflict && !lightDarkConflict
					&& canAffordAltCost(card, handIdx)
					&& (!card.isBackup() || hasAvailableBackupSlot()) && castRestrictionMet(card));
			altItem.addActionListener(ae -> {
				hideZoom();
				if (handPopup != null) { handPopup.dispose(); handPopup = null; }
				showAltCostPlayDialog(card, handIdx);
			});
			menu.add(altItem);
		}

		for (ActionAbility ability : card.actionAbilities()) {
			if (!ability.whileCardInHand()) continue;
			JMenuItem item = new JMenuItem("Use: " + buildAbilityMenuLabel(ability));
			item.setEnabled(canActivateHandAbility(ability, card, true));
			item.addActionListener(ae -> {
				hideZoom();
				if (handPopup != null) { handPopup.dispose(); handPopup = null; }
				showActionAbilityPaymentDialog(ability, card, () -> {}, true);
			});
			menu.add(item);
		}

		menu.addPopupMenuListener(new PopupMenuListener() {
			@Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}
			@Override public void popupMenuCanceled(PopupMenuEvent e) {
				handCardMenuOpen = false;
			}
			@Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) {
				handCardMenuOpen = false;
				scheduleHandPopupHide();
			}
		});

		menu.show(cardLabel, e.getX(), e.getY());
	}

	/** Shows a preview of a hand card in the side panel. */
	private void showHandCardZoom(String url) {
		showZoomAt(url);
	}

	/**
	 * Adds {@code card} to P1's Break Zone. LB cards enter then are immediately removed,
	 * so "when put into the Break Zone" triggers fire but the card does not stay there.
	 */
	private void addToP1BreakZone(CardData card) {
		List<CardData> zone = gameState.getP1BreakZone();
		zone.add(card);
		if (card.isLb()) zone.remove(card);
	}

	/**
	 * Adds {@code card} to P2's Break Zone. LB cards enter then are immediately removed,
	 * so "when put into the Break Zone" triggers fire but the card does not stay there.
	 */
	private void addToP2BreakZone(CardData card) {
		List<CardData> zone = gameState.getP2BreakZone();
		zone.add(card);
		if (card.isLb()) zone.remove(card);
	}

	private void refreshP1BreakLabel() {
		List<CardData> zone = gameState.getP1BreakZone();
		if (zone.isEmpty()) {
			p1BreakLabel.setIcon(null);
			p1BreakLabel.setFont(FontLoader.loadPixelNESFont(18));
			p1BreakLabel.setText("BREAK");
			return;
		}
		String url = zone.get(zone.size() - 1).imageUrl();
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image img = ImageCache.load(url);
				return img == null ? null
						: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null) { p1BreakLabel.setIcon(icon); p1BreakLabel.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	private void refreshP2BreakLabel() {
		List<CardData> zone = gameState.getP2BreakZone();
		if (zone.isEmpty()) {
			p2BreakLabel.setIcon(null);
			p2BreakLabel.setFont(FontLoader.loadPixelNESFont(18));
			p2BreakLabel.setText("BREAK");
			return;
		}
		String url = zone.get(zone.size() - 1).imageUrl();
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image img = ImageCache.load(url);
				return img == null ? null
						: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null) { p2BreakLabel.setIcon(icon); p2BreakLabel.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	// -------------------------------------------------------------------------
	// Draw animation
	// -------------------------------------------------------------------------

	/**
	 * Triggers a card-slide animation from the deck toward the player's hand
	 * (off-screen bottom-center for P1, off-screen top-center for P2).
	 */
	private void animateCardDraw(boolean isP1, int count) {
		JLabel       deck = isP1 ? p1DeckLabel : p2DeckLabel;
		JLayeredPane lp   = frame.getRootPane().getLayeredPane();

		Point start = SwingUtilities.convertPoint(
				deck, deck.getWidth() / 2, deck.getHeight() / 2, lp);

		int   cx  = lp.getWidth() / 2;
		Point end = isP1
				? new Point(cx, lp.getHeight() + CardAnimation.CARD_H)
				: new Point(cx, -CardAnimation.CARD_H);

		BufferedImage img = CardAnimation.toARGB(
				loadCardbackImage(), CardAnimation.CARD_W, CardAnimation.CARD_H);
		for (int i = 0; i < count; i++)
			cardSlideAnimator.startSlide(img, start, end, i * 5);
	}

	private void startBreakAnim(JLabel label) {
		if (label == null) return;
		javax.swing.Icon icon = label.getIcon();
		if (!(icon instanceof ImageIcon ii)) return;
		JLayeredPane  lp     = frame.getRootPane().getLayeredPane();
		Point         center = SwingUtilities.convertPoint(
				label, label.getWidth() / 2, label.getHeight() / 2, lp);
		java.awt.image.BufferedImage img = CardAnimation.toARGB(
				ii.getImage(), ii.getIconWidth(), ii.getIconHeight());
		breakAnimator.startBreak(img, center);
	}

	private void animateCardToDamage(boolean isP1, int slotIdx) {
		JLabel   deck  = isP1 ? p1DeckLabel : p2DeckLabel;
		JPanel[] slots = isP1 ? p1DamageSlots : p2DamageSlots;
		if (slotIdx < 0 || slotIdx >= slots.length || slots[slotIdx] == null) return;
		JLayeredPane lp   = frame.getRootPane().getLayeredPane();
		JPanel       slot = slots[slotIdx];
		Point start = SwingUtilities.convertPoint(deck, deck.getWidth() / 2, deck.getHeight() / 2, lp);
		Point end   = SwingUtilities.convertPoint(slot, slot.getWidth() / 2, slot.getHeight() / 2, lp);
		BufferedImage img = CardAnimation.toARGB(
				loadCardbackImage(), CardAnimation.CARD_W, CardAnimation.CARD_H);
		cardSlideAnimator.startSlide(img, start, end, 0);
	}

	// -------------------------------------------------------------------------
	// Play / Payment
	// -------------------------------------------------------------------------

	/**
	 * Returns the effective cast cost of {@code card} after applying any active
	 * cost-reduction modifier that matches it.  Only the first matching modifier
	 * is applied (modifiers stack only when multiple fire independently).
	 */
	private int effectiveCastCost(CardData card) {
		int cost = Math.max(0, card.cost() - totalFieldReduction(card, true));
		for (CostReductionModifier m : activeCostReductions) {
			if (m.matches(card)) return m.apply(cost);
		}
		return cost;
	}

	private int totalFieldReduction(CardData card, boolean isP1) {
		int total = 0;
		for (int s = 0; s < 2; s++) {
			boolean sIsP1 = s == 0;
			List<CardData> fwds = sIsP1 ? p1ForwardCards : p2ForwardCards;
			CardData[]     bkps = sIsP1 ? p1BackupCards  : p2BackupCards;
			List<CardData> mons = sIsP1 ? p1MonsterCards : p2MonsterCards;
			for (CardData src : fwds)                        total += reductionFrom(src, card, isP1, sIsP1);
			for (CardData bkp : bkps) if (bkp != null)      total += reductionFrom(bkp, card, isP1, sIsP1);
			for (CardData src : mons)                        total += reductionFrom(src, card, isP1, sIsP1);
		}
		return total;
	}

	private int reductionFrom(CardData src, CardData card, boolean isP1, boolean srcIsP1) {
		int total = 0;
		for (FieldCostReduction fcr : src.fieldCostReductions()) {
			if (fcr.amountPerUnit() == 0) continue;
			if (fcr.ownerOnly() && srcIsP1 != isP1) continue;
			if (!fcr.matchesCard(card)) continue;
			int units = fcr.scalingJobFilter() != null
					? countForwardsWithJob(fcr.scalingJobFilter(), isP1) : 1;
			total += fcr.amountPerUnit() * units;
		}
		return total;
	}

	private int countForwardsWithJob(String job, boolean isP1) {
		int count = 0;
		for (CardData fwd : (isP1 ? p1ForwardCards : p2ForwardCards))
			if (job.equalsIgnoreCase(fwd.job())) count++;
		return count;
	}

	/**
	 * Returns true if the player can theoretically afford to play {@code card}
	 * by combining existing CP with potential discards from hand.
	 * {@code excludeHandIdx} is the index of the card being played (not available
	 * for discard).
	 */
	/**
	 * Returns {@code true} if the card's "You can only cast X …" restriction (if any) is
	 * satisfied by the current game state from P1's perspective.
	 */
	private boolean castRestrictionMet(CardData card) {
		CastRestriction cr = card.castRestriction();
		if (cr == null) return true;

		boolean isP1Turn = gameState.getCurrentPlayer() == GameState.Player.P1;

		if (cr.opponentTurnOnly() && isP1Turn)  return false;
		if ((cr.yourTurnOnly() || cr.mainPhaseOnly()) && !isP1Turn) return false;

		if (cr.requiresNoForwards() && !p1ForwardCards.isEmpty()) return false;

		if (!cr.requiredBZTypes().isEmpty()) {
			List<CardData> bz = gameState.getP1BreakZone();
			for (String requiredType : cr.requiredBZTypes()) {
				boolean found = switch (requiredType) {
					case "Forward" -> bz.stream().anyMatch(CardData::isForward);
					case "Backup"  -> bz.stream().anyMatch(CardData::isBackup);
					case "Monster" -> bz.stream().anyMatch(CardData::isMonster);
					case "Summon"  -> bz.stream().anyMatch(CardData::isSummon);
					default        -> false;
				};
				if (!found) return false;
			}
		}

		if (cr.minBZAndRfpSummons() > 0) {
			long bzSummons  = gameState.getP1BreakZone().stream().filter(CardData::isSummon).count();
			long rfpSummons = gameState.getP1PermanentRfp().stream().filter(CardData::isSummon).count();
			if (bzSummons + rfpSummons < cr.minBZAndRfpSummons()) return false;
		}

		return true;
	}

	/** Returns true if any on-field card (backup or forward) grants {@code backup} any-element CP. */
	private boolean isGrantedAnyElementCp(CardData backup) {
		for (CardData b : p1BackupCards) {
			if (b != null) {
				BackupCpGrant grant = b.backupCpGrant();
				if (grant != null && grant.appliesTo(backup)) return true;
			}
		}
		for (CardData fwd : p1ForwardCards) {
			BackupCpGrant grant = fwd.backupCpGrant();
			if (grant != null && grant.appliesTo(backup)) return true;
		}
		return false;
	}

	private boolean canAffordCard(CardData card, int excludeHandIdx) {
		String[]       elems = card.elements();
		List<CardData> hand  = gameState.getP1Hand();
		int totalGenerate = 0;

		if (card.isLightOrDark()) {
			// L/D cards accept any element — sum all banked CP and all available sources
			int totalExisting = gameState.getP1CpByElement().values().stream().mapToInt(Integer::intValue).sum();
			for (int i = 0; i < hand.size(); i++) {
				if (i == excludeHandIdx) continue;
				if (!hand.get(i).isLightOrDark()) totalGenerate += 2;
			}
			for (int i = 0; i < p1BackupCards.length; i++) {
				if (p1BackupCards[i] != null && p1BackupStates[i] == CardState.ACTIVE)
					totalGenerate += 1;
			}
			return totalExisting + totalGenerate >= effectiveCastCost(card);
		}

		boolean[] hasElemSource = new boolean[elems.length];
		int totalExisting = 0;
		for (int ei = 0; ei < elems.length; ei++) {
			int ex = gameState.getP1CpForElement(elems[ei]);
			totalExisting += ex;
			if (ex > 0) hasElemSource[ei] = true;
		}
		for (int i = 0; i < hand.size(); i++) {
			if (i == excludeHandIdx) continue;
			CardData h = hand.get(i);
			if (h.isLightOrDark()) continue;
			totalGenerate += 2;
			for (int ei = 0; ei < elems.length; ei++) {
				if (h.containsElement(elems[ei])) hasElemSource[ei] = true;
			}
		}
		for (int i = 0; i < p1BackupCards.length; i++) {
			if (p1BackupCards[i] != null && p1BackupStates[i] == CardState.ACTIVE) {
				CardData bkp = p1BackupCards[i];
				String anyElemCat = bkp.backupCpAnyElementCategory();
				boolean isAnyElem = bkp.backupCpAnyElement()
						|| (bkp.backupCpAnyElementOfForwards() && !p1ForwardCards.isEmpty())
						|| (!anyElemCat.isEmpty()
							&& (anyElemCat.equalsIgnoreCase(card.category1())
								|| anyElemCat.equalsIgnoreCase(card.category2())))
						|| isGrantedAnyElementCp(bkp);
				if (isAnyElem) {
					totalGenerate += 1;
					for (int ei = 0; ei < elems.length; ei++) hasElemSource[ei] = true;
				} else {
					for (int ei = 0; ei < elems.length; ei++) {
						if (bkp.containsElement(elems[ei])) {
							totalGenerate += 1;
							hasElemSource[ei] = true;
							break;
						}
					}
				}
			}
		}
		for (boolean hs : hasElemSource) if (!hs) return false;
		return totalExisting + totalGenerate >= effectiveCastCost(card);
	}

	/**
	 * Returns true when the player can pay the alternate cast cost for {@code card},
	 * including satisfying any field-presence condition.
	 */
	private boolean canAffordAltCost(CardData card, int handIdx) {
		// Condition check: "If you control a Card Name X or a Card Name Y"
		List<String> condNames = card.altConditionCardNames();
		if (!condNames.isEmpty() && condNames.stream().noneMatch(this::hasCharacterNameOnField)) return false;

		if (playerCrystals(true) < card.altCrystalCost()) return false;

		// Break Zone removal check
		List<String> bzReqs = card.altBzRemovals();
		if (!bzReqs.isEmpty()) {
			List<CardData> available = new ArrayList<>(gameState.getP1BreakZone());
			for (String req : bzReqs) {
				String[] parts = req.split(" ", 2);
				String elem = parts[0], type = parts.length > 1 ? parts[1] : "";
				boolean found = false;
				for (int i = 0; i < available.size(); i++) {
					CardData c = available.get(i);
					if (c.containsElement(elem) && matchesAltBzType(c, type)) {
						available.remove(i); found = true; break;
					}
				}
				if (!found) return false;
			}
		}

		List<String> altElems = card.altCpElements();
		if (altElems.isEmpty()) return true;

		// Count available CP sources (backup-only restriction respected)
		LinkedHashMap<String, Integer> needed = new LinkedHashMap<>();
		long genericNeeded = 0;
		for (String e : altElems) { if (e.isEmpty()) genericNeeded++; else needed.merge(e, 1, Integer::sum); }
		String[] elems = needed.keySet().toArray(String[]::new);

		int totalSources = 0;
		for (String e : elems) totalSources += gameState.getP1CpForElement(e);
		for (int i = 0; i < p1BackupCards.length; i++)
			if (p1BackupCards[i] != null && p1BackupStates[i] == CardState.ACTIVE
					&& (genericNeeded > 0 || matchesAnyElement(p1BackupCards[i], elems))) totalSources++;
		if (!card.altBackupOnlyCp()) {
			for (int i = 0; i < gameState.getP1Hand().size(); i++) {
				if (i == handIdx) continue;
				CardData h = gameState.getP1Hand().get(i);
				if (!h.isLightOrDark() && (genericNeeded > 0 || matchesAnyElement(h, elems))) totalSources += 2;
			}
		}
		return totalSources >= altElems.size();
	}

	/**
	 * Returns true if the player can afford the Warp alternate cost of {@code card}.
	 * Warp cost is a list of element CP requirements (e.g. ["Lightning"] = 1 Lightning CP).
	 */
	private boolean canAffordWarpCost(CardData card, int handIdx) {
		List<String> warpCost = card.warpCost();
		if (warpCost.isEmpty()) return true;

		// Separate element-specific requirements from generic CP (empty-string entries)
		boolean hasGeneric = warpCost.contains("");
		LinkedHashMap<String, Integer> needed = new LinkedHashMap<>();
		for (String e : warpCost) if (!e.isEmpty()) needed.merge(e, 1, Integer::sum);
		String[] elems = needed.keySet().toArray(String[]::new);
		int total = warpCost.size();

		boolean[] hasSrc = new boolean[elems.length];
		int available = 0;

		// Banked CP (element-specific)
		for (int ei = 0; ei < elems.length; ei++) {
			int b = gameState.getP1CpForElement(elems[ei]);
			available += b;
			if (b > 0) hasSrc[ei] = true;
		}
		// Banked CP of any element counts toward generic
		if (hasGeneric) {
			available += gameState.getP1CpByElement().values().stream().mapToInt(Integer::intValue).sum();
			for (int ei = 0; ei < elems.length; ei++)
				available -= gameState.getP1CpForElement(elems[ei]); // avoid double-counting
		}

		// Undulled backups: matching backups satisfy element requirements;
		// any backup can cover generic CP
		for (int i = 0; i < p1BackupCards.length; i++) {
			if (p1BackupCards[i] == null || p1BackupStates[i] != CardState.ACTIVE) continue;
			boolean matched = false;
			for (int ei = 0; ei < elems.length; ei++) {
				if (p1BackupCards[i].containsElement(elems[ei])) {
					available++;
					hasSrc[ei] = true;
					matched = true;
					break;
				}
			}
			if (!matched && hasGeneric) available++;
		}

		// Non-L/D hand cards always contribute 2 CP toward total
		List<CardData> hand = gameState.getP1Hand();
		for (int i = 0; i < hand.size(); i++) {
			if (i == handIdx) continue;
			CardData h = hand.get(i);
			if (h.isLightOrDark()) continue;
			available += 2;
			for (int ei = 0; ei < elems.length; ei++) {
				if (h.containsElement(elems[ei])) hasSrc[ei] = true;
			}
		}

		for (boolean s : hasSrc) if (!s) return false;
		return available >= total;
	}

	/**
	 * Opens a payment dialog for the Warp alternate cost and, on confirm,
	 * moves the card from hand to the Removed-From-Play zone with Warp counters.
	 */
	/**
	 * Handles the alternate Crystal cast cost.
	 * <ul>
	 *   <li>Crystal-only (altCpCost == 0): confirms and spends crystals, then plays for free.</li>
	 *   <li>Crystal + reduced CP (altCpCost &gt; 0): shows a backup/discard selection dialog for
	 *       the reduced CP amount, spending crystals on confirm.</li>
	 * </ul>
	 */
	private void showAltCostPlayDialog(CardData card, int handIdx) {
		int altC  = card.altCrystalCost();
		int altCp = card.altCpCost();
		List<String> altElemsList = card.altCpElements();
		String followupText       = card.altFollowupText();
		List<String> bzRemovals   = card.altBzRemovals();
		boolean backupOnly        = card.altBackupOnlyCp();

		if (altElemsList.isEmpty()) {
			int choice = JOptionPane.showOptionDialog(frame,
					card.name() + " — Pay " + "《C》".repeat(altC) + (altCp > 0 ? " + " + altCp + " CP" : "") + " to cast?",
					"Alternate Cost",
					JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
					new Object[]{"Confirm", "Cancel"}, "Confirm");
			if (choice != 0) return;
			if (altC > 0) { playerSpendCrystals(true, altC); refreshCrystalDisplays(); }
			executePlay(card, handIdx, java.util.Collections.emptyList(), java.util.Collections.emptyList(), Map.of());
			executeAltFollowup(followupText, card);
			return;
		}

		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		long genericNeeded = 0;
		for (String elem : altElemsList) {
			if (elem.isEmpty()) genericNeeded++;
			else costByElem.merge(elem, 1, Integer::sum);
		}
		String[] elems = costByElem.keySet().toArray(String[]::new);

		new AltCostPaymentDialog(frame, card, handIdx, altCp, genericNeeded, elems, costByElem,
				backupOnly, gameState.getP1Hand(), playerBackupCards(true), playerBackupStates(true),
				playerBackupUrls(true), this::showZoomAt, this::hideZoom,
				(discards, backups) -> {
					if (altC > 0) { playerSpendCrystals(true, altC); refreshCrystalDisplays(); }
					executeAltBzRemovals(bzRemovals);
					executePlay(card, handIdx, discards, backups, Map.of());
					executeAltFollowup(followupText, card);
				}).show();
	}


	/**
	 * Removes one BZ card matching each entry in {@code removals} from P1's Break Zone and
	 * adds it to the permanent Removed-From-Play zone.  Auto-selects the first matching card.
	 */
	private void executeAltBzRemovals(List<String> removals) {
		if (removals.isEmpty()) return;
		List<CardData> bz = gameState.getP1BreakZone();
		for (String req : removals) {
			String[] parts = req.split(" ", 2);
			String elem = parts[0], type = parts.length > 1 ? parts[1] : "";
			for (int i = 0; i < bz.size(); i++) {
				CardData c = bz.get(i);
				if (c.containsElement(elem) && matchesAltBzType(c, type)) {
					bz.remove(i);
					gameState.addToP1PermanentRfp(c);
					logEntry(c.name() + " removed from Break Zone → Removed From Play (alt cost)");
					refreshP1BreakLabel();
					refreshP1WarpZoneUI();
					break;
				}
			}
		}
	}

	/** Executes the "If you do so" followup effect attached to an alternate cast, if any. */
	private void executeAltFollowup(String followupText, CardData source) {
		if (followupText == null || followupText.isBlank()) return;
		Consumer<GameContext> effect = ActionResolver.parse(followupText, source);
		if (effect != null) {
			logEntry("[AltCost followup] " + source.name() + " — " + followupText);
			effect.accept(buildGameContext(true));
		} else {
			logEntry("[AltCost followup] Unrecognized effect: " + followupText);
		}
	}

	private void showWarpPaymentDialog(CardData card, int handIdx) {
		new WarpPaymentDialog(frame, card, handIdx,
				gameState.getP1Hand(), p1BackupCards, p1BackupStates, p1BackupUrls,
				p1ForwardCards,
				this::showZoomAt, this::hideZoom,
				(discards, backups, overrides) -> executeWarpPlay(card, handIdx, discards, backups, overrides))
			.show();
	}


	/**
	 * Pays the Warp alternate cost (dulls backups, discards hand cards), removes the card
	 * from hand, and places it in the Removed-From-Play zone with Warp counters.
	 */
	private void executeWarpPlay(CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			java.util.Map<Integer, String> elementOverrides) {
		List<String> rawCost = card.warpCost();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);

		for (int bi : backupDullIndices) {
			p1BackupStates[bi] = CardState.DULL;
			animateDullBackup(bi, true);
			String cpElem = elementOverrides.containsKey(bi)
					? elementOverrides.get(bi)
					: matchesAnyElement(p1BackupCards[bi], elems)
					? contributingElement(p1BackupCards[bi], elems) : elems[0];
			gameState.addP1Cp(cpElem, 1);
		}
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
			CardData discarded = gameState.getP1Hand().get(di);
			String cpElem = matchesAnyElement(discarded, elems)
					? contributingElement(discarded, elems) : elems[0];
			gameState.addP1Cp(cpElem, 2);
			gameState.breakFromHand(di);
			if (di < cardHandIdx) cardHandIdx--;
		}
		for (String e : elems) {
			gameState.spendP1Cp(e, gameState.getP1CpForElement(e));
			gameState.clearP1Cp(e);
		}
		gameState.removeFromHand(cardHandIdx);

		gameState.addToP1WarpZone(card, card.warpValue());
		logEntry("Played \"" + card.name() + "\" via Warp — " + card.warpValue()
				+ " counter" + (card.warpValue() != 1 ? "s" : "") + " → Removed From Play");
		triggerAutoAbilitiesForWarpPlaced(card);
		refreshP1HandLabel();
		refreshP1BreakLabel();
		refreshP1WarpZoneUI();
	}

	/** Returns true if at least one P1 backup slot is currently empty. */
	private boolean hasCharacterNameOnField(String name) {
		for (CardData c : p1ForwardCards)
			if (name.equalsIgnoreCase(c.name())) return true;
		for (CardData c : p1MonsterCards)
			if (name.equalsIgnoreCase(c.name())) return true;
		for (CardData c : p1BackupCards)
			if (c != null && name.equalsIgnoreCase(c.name())) return true;
		return false;
	}

	private boolean p2HasCharacterNameOnField(String name) {
		for (CardData c : p2ForwardCards)
			if (name.equalsIgnoreCase(c.name())) return true;
		for (CardData c : p2BackupCards)
			if (c != null && name.equalsIgnoreCase(c.name())) return true;
		return false;
	}

	/** Returns true if any Light or Dark character is on the given player's field. */
	private boolean hasLightOrDarkOnField(boolean isP1) {
		if (isP1) {
			for (CardData c : p1ForwardCards) if (c.isLightOrDark()) return true;
			for (CardData c : p1MonsterCards) if (c.isLightOrDark()) return true;
			for (CardData c : p1BackupCards)  if (c != null && c.isLightOrDark()) return true;
		} else {
			for (CardData c : p2ForwardCards) if (c.isLightOrDark()) return true;
			for (CardData c : p2BackupCards)  if (c != null && c.isLightOrDark()) return true;
		}
		return false;
	}

	private boolean hasAvailableBackupSlot() {
		if (p1BackupLabels == null) return false;
		for (JLabel slot : p1BackupLabels) {
			if (slot != null && slot.getIcon() == null) return true;
		}
		return false;
	}

	/**
	 * Opens a modal payment dialog where the player selects backups to dull (1 CP each)
	 * and/or hand cards to discard (2 CP each) to cover the cost of {@code card}.
	 *
	 * Constraints enforced:
	 *   - Backups may not cause total CP to exceed the cost (no overpay via backups).
	 *   - Discards may overpay by 1 per element (total <= cost + elems.length - 1 after adding).
	 */
	private void showPaymentDialog(CardData card, int handIdx) {
		new StandardPaymentDialog(frame, card, handIdx, effectiveCastCost(card),
				gameState.getP1Hand(), p1BackupCards, p1BackupStates, p1BackupUrls,
				this::showZoomAt, this::hideZoom,
				new ArrayList<>(p1ForwardCards),
				(discards, backups, overrides) -> executePlay(card, handIdx, discards, backups, overrides),
				isAnyElementCast(card))
			.show();
	}

	/** Returns true if any field card grants any-element payment for {@code card}. */
	private boolean isAnyElementCast(CardData card) {
		for (int s = 0; s < 2; s++) {
			boolean sIsP1 = s == 0;
			List<CardData> fwds = sIsP1 ? p1ForwardCards : p2ForwardCards;
			CardData[]     bkps = sIsP1 ? p1BackupCards  : p2BackupCards;
			List<CardData> mons = sIsP1 ? p1MonsterCards : p2MonsterCards;
			for (CardData src : fwds)                   { if (srcGrantsAnyElement(src, card, sIsP1)) return true; }
			for (CardData bkp : bkps) if (bkp != null) { if (srcGrantsAnyElement(bkp, card, sIsP1)) return true; }
			for (CardData src : mons)                   { if (srcGrantsAnyElement(src, card, sIsP1)) return true; }
		}
		return false;
	}

	private boolean srcGrantsAnyElement(CardData src, CardData card, boolean srcIsP1) {
		for (FieldCostReduction fcr : src.fieldCostReductions()) {
			if (!fcr.anyElement()) continue;
			if (fcr.ownerOnly() && !srcIsP1) continue;
			if (fcr.matchesCard(card)) return true;
		}
		return false;
	}

	/**
	 * Executes the play: dulls selected backups, discards payment cards (high-index
	 * first to preserve indices), adds the generated CP to the bank, spends the cost,
	 * removes the played card from hand, and places it in the appropriate zone.
	 */
	private void executePlay(CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			Map<Integer, String> backupElementOverrides) {
		String[] elems = card.elements();
		boolean  isLD  = card.isLightOrDark();
		Map<String, Integer> execCostByElem = new LinkedHashMap<>();
		if (!isLD) for (String e : elems) execCostByElem.put(e, 1);
		Map<String, Integer> execCpAccum = new LinkedHashMap<>();

		// Backups: sort by fewest element matches first for optimal assignment.
		List<Integer> sortedBackups = new ArrayList<>(backupDullIndices);
		if (!isLD) sortedBackups.sort(Comparator.comparingInt(s ->
				(int) java.util.Arrays.stream(elems)
						.filter(e -> p1BackupCards[s].containsElement(e)).count()));
		for (int bi : sortedBackups) {
			p1BackupStates[bi] = CardState.DULL;
			animateDullBackup(bi, true);
			String cpElem;
			if (backupElementOverrides.containsKey(bi)) {
				cpElem = backupElementOverrides.get(bi);
			} else if (isLD) {
				cpElem = p1BackupCards[bi].elements()[0];
			} else {
				cpElem = contributingElement(p1BackupCards[bi], elems, execCpAccum, execCostByElem);
			}
			gameState.addP1Cp(cpElem, 1);
			execCpAccum.merge(cpElem, 1, Integer::sum);
		}

		// Discards: pre-compute optimal element assignments (fewer matches first),
		// then remove from hand in reverse-index order to avoid index shifting.
		List<Integer> assignOrder = new ArrayList<>(discardIndices);
		if (!isLD) assignOrder.sort(Comparator.comparingInt(i ->
				(int) java.util.Arrays.stream(elems)
						.filter(e -> gameState.getP1Hand().get(i).containsElement(e)).count()));
		Map<Integer, String> cpAssignments = new LinkedHashMap<>();
		for (int i : assignOrder) {
			CardData d = gameState.getP1Hand().get(i);
			String cpElem = isLD ? d.elements()[0]
					: contributingElement(d, elems, execCpAccum, execCostByElem);
			cpAssignments.put(i, cpElem);
			execCpAccum.merge(cpElem, 2, Integer::sum);
		}
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
			gameState.addP1Cp(cpAssignments.get(di), 2);
			gameState.breakFromHand(di);
			if (di < cardHandIdx) cardHandIdx--;
		}
		for (String e : elems) {
			gameState.spendP1Cp(e, gameState.getP1CpForElement(e));
			gameState.clearP1Cp(e);
		}
		// Record distinct element types used for payment (checked by castPaymentMinElements field abilities)
		lastCastPaymentDistinctElements = (int) execCpAccum.keySet().stream()
				.filter(e -> !e.isEmpty()).distinct().count();
		lastCastPaymentElements.clear();
		execCpAccum.keySet().stream().filter(e -> !e.isEmpty()).forEach(lastCastPaymentElements::add);
		lastCastWasPaidByBackupsOnly = discardIndices.isEmpty() && !backupDullIndices.isEmpty();
		gameState.removeFromHand(cardHandIdx);
		activeCostReductions.removeIf(m -> m.matches(card));
		p1CardsCastThisTurn++;
		logEntry("Played \"" + card.name() + "\"");

		lastCardWasCast = true;
		if (card.isBackup()) {
			placeCardInFirstBackupSlot(card);
		} else if (card.isForward()) {
			placeCardInForwardZone(card);
		} else if (card.isMonster()) {
			placeCardInMonsterZone(card);
		} else if (card.isSummon()) {
			showSummonOnStack(card);
		}
		lastCardWasCast = false;

		refreshP1HandLabel();
		refreshP1BreakLabel();
	}

	/** Pushes a Summon onto the stack and opens the stack overlay. */
	private void showSummonOnStack(CardData card) {
		gameState.pushStack(new StackEntry(card, null, true));
		logEntry("[Stack] \"" + card.name() + "\" — Summon on the stack");
		showStackWindow();
	}

	/**
	 * Shows the resolution overlay for the current top of the stack.
	 * Disposes any existing overlay first and increments the generation counter
	 * so stale timers from previous windows never fire.
	 *
	 * <p>The overlay has a 10-second countdown, an "OK" button (resolve immediately)
	 * and a "Respond" button that pauses the countdown and opens a 20-second response
	 * window during which the player may activate cards.  When the response window
	 * expires (or no new entry was pushed), the top entry resolves automatically.
	 */
	private void showStackWindow() {
		StackEntry entry = gameState.peekStack();
		if (entry == null) return;

		if (stackCountdownTimer != null) { stackCountdownTimer.stop(); stackCountdownTimer = null; }
		if (summonStackWindow   != null) { summonStackWindow.dispose(); summonStackWindow = null; }

		// P1 acted → CPU (P2) has priority → auto-resolve silently
		if (entry.isP1()) {
			resolveTopOfStack();
			return;
		}

		// P2 acted → P1 (human) has priority → show interactive Respond/OK window
		final int myGeneration = ++stackWindowGeneration;

		summonStackWindow = new JWindow(frame);

		JPanel panel = new JPanel(new BorderLayout(6, 6));
		panel.setBackground(new Color(28, 24, 40));
		panel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(new Color(160, 110, 220), 2),
				BorderFactory.createEmptyBorder(10, 14, 10, 14)));

		String headerText = entry.isSummon() ? "S U M M O N" : "A C T I O N";
		JLabel header = new JLabel(headerText, SwingConstants.CENTER);
		header.setFont(FontLoader.loadPixelNESFont(13));
		header.setForeground(new Color(210, 170, 255));
		panel.add(header, BorderLayout.NORTH);

		JLabel cardImg = new JLabel("", SwingConstants.CENTER);
		cardImg.setPreferredSize(new Dimension(CardAnimation.CARD_W, CardAnimation.CARD_H));

		JLabel nameLabel = new JLabel(entry.source().name(), SwingConstants.CENTER);
		nameLabel.setFont(FontLoader.loadPixelNESFont(10));
		nameLabel.setForeground(Color.WHITE);

		JPanel imagePanel = new JPanel(new BorderLayout(3, 3));
		imagePanel.setOpaque(false);
		imagePanel.add(cardImg,   BorderLayout.CENTER);
		imagePanel.add(nameLabel, BorderLayout.SOUTH);
		panel.add(imagePanel, BorderLayout.CENTER);

		int[] countdown = { 10 };
		JLabel countdownLabel = new JLabel("Resolving in 10...", SwingConstants.CENTER);
		countdownLabel.setFont(FontLoader.loadPixelNESFont(10));
		countdownLabel.setForeground(Color.LIGHT_GRAY);

		JButton okBtn      = new JButton("OK");
		JButton respondBtn = new JButton("Respond");
		okBtn.setFont(FontLoader.loadPixelNESFont(11));
		respondBtn.setFont(FontLoader.loadPixelNESFont(11));

		JPanel btnPanel = new JPanel(new java.awt.GridLayout(1, 2, 4, 0));
		btnPanel.setOpaque(false);
		btnPanel.add(respondBtn);
		btnPanel.add(okBtn);

		JPanel bottomPanel = new JPanel(new BorderLayout(4, 4));
		bottomPanel.setOpaque(false);
		bottomPanel.add(countdownLabel, BorderLayout.NORTH);
		bottomPanel.add(btnPanel,       BorderLayout.CENTER);
		panel.add(bottomPanel, BorderLayout.SOUTH);

		summonStackWindow.getContentPane().add(panel);
		summonStackWindow.pack();

		Point loc = frame.getLocationOnScreen();
		int wx = loc.x + (frame.getWidth()  - summonStackWindow.getWidth())  / 2;
		int wy = loc.y + (frame.getHeight() - summonStackWindow.getHeight()) / 2;
		summonStackWindow.setLocation(wx, wy);
		summonStackWindow.setVisible(true);

		// Load card image asynchronously
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image img = ImageCache.load(entry.source().imageUrl());
				return img == null ? null
						: new ImageIcon(img.getScaledInstance(
								CardAnimation.CARD_W, CardAnimation.CARD_H, Image.SCALE_SMOOTH));
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null) { cardImg.setIcon(icon); cardImg.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();

		// 10-second countdown timer
		stackCountdownTimer = new javax.swing.Timer(1000, null);
		stackCountdownTimer.addActionListener(e -> {
			if (stackWindowGeneration != myGeneration) { ((javax.swing.Timer) e.getSource()).stop(); return; }
			countdown[0]--;
			if (countdown[0] <= 0) {
				stackCountdownTimer.stop();
				resolveTopOfStack();
			} else {
				countdownLabel.setText("Resolving in " + countdown[0] + "...");
			}
		});
		stackCountdownTimer.start();

		okBtn.addActionListener(e -> {
			if (stackWindowGeneration != myGeneration) return;
			stackCountdownTimer.stop();
			resolveTopOfStack();
		});

		respondBtn.addActionListener(e -> {
			if (stackWindowGeneration != myGeneration) return;
			stackCountdownTimer.stop();
			respondBtn.setEnabled(false);

			// 20-second response window
			int[] responseCountdown = { 20 };
			countdownLabel.setText("Response window: 20s...");
			javax.swing.Timer responseTimer = new javax.swing.Timer(1000, null);
			responseTimer.addActionListener(re -> {
				if (stackWindowGeneration != myGeneration) { ((javax.swing.Timer) re.getSource()).stop(); return; }
				responseCountdown[0]--;
				if (responseCountdown[0] <= 0) {
					((javax.swing.Timer) re.getSource()).stop();
					// Only auto-resolve if we're still the top entry (nothing was pushed during response)
					if (gameState.peekStack() == entry) resolveTopOfStack();
				} else {
					countdownLabel.setText("Response window: " + responseCountdown[0] + "s...");
				}
			});
			responseTimer.start();
		});
	}

	/**
	 * Pops and executes the top entry of the stack, then shows the next entry
	 * if the stack is non-empty.
	 */
	private void resolveTopOfStack() {
		if (stackCountdownTimer != null) { stackCountdownTimer.stop(); stackCountdownTimer = null; }
		if (summonStackWindow   != null) { summonStackWindow.dispose(); summonStackWindow = null; }

		StackEntry entry = gameState.popStack();
		if (entry == null) return;

		GameContext ctx = buildGameContext(entry.isP1());
		if (entry.isSummon()) {
			String effectText = entry.effectText();
			logEntry("[Summon] Resolving \"" + entry.source().name() + "\": " + effectText);
			Consumer<GameContext> effect = ActionResolver.parse(effectText, entry.source());
			if (effect != null) {
				currentResolutionIsSummon   = true;
				currentBreaktouchSource     = entry.source();
				currentBreaktouchSourceIsP1 = entry.isP1();
				pendingSummonReturnToHand   = false;
				try { effect.accept(ctx); } finally {
					currentResolutionIsSummon = false;
					currentBreaktouchSource   = null;
				}
			} else logEntry("[ActionResolver] Summon effect not yet implemented: " + effectText);
			triggerAutoAbilitiesForCastSummon(entry.isP1());
			if (pendingSummonReturnToHand) {
				gameState.getP1Hand().add(entry.source());
				logEntry("\"" + entry.source().name() + "\" → Hand");
				refreshP1HandLabel();
				pendingSummonReturnToHand = false;
			} else {
				addToP1BreakZone(entry.source());
				logEntry("\"" + entry.source().name() + "\" → Break Zone");
				refreshP1BreakLabel();
			}
		} else {
			ActionResolver.resolve(entry.ability(), entry.source(), gameState, ctx, entry.xValue());
			refreshP1HandLabel();
			refreshP1BreakLabel();
		}

		if (!gameState.getStack().isEmpty()) showStackWindow();
	}

	/**
	 * CP payment dialog for LB casting — mirrors showPaymentDialog but has no
	 * hand-card to exclude and calls executeLbPlay on confirm.
	 */
	private void showLbCpPaymentDialog(CardData card, int lbCastIdx, Set<Integer> pendingLbPayment) {
		new LbPaymentDialog(frame, card,
				gameState.getP1Hand(), p1BackupCards, p1BackupStates, p1BackupUrls,
				this::showZoomAt, this::hideZoom,
				(discards, backups) -> {
					spentLbIndices.add(lbCastIdx);
					spentLbIndices.addAll(pendingLbPayment);
					logEntry("Cast LB \"" + card.name() + "\"");
					executeLbPlay(card, discards, backups);
				})
			.show();
	}


	/**
	 * Executes an LB cast: dulls selected backups, discards payment hand cards,
	 * spends CP, and places the card — without removing it from hand.
	 */
	private void executeLbPlay(CardData card, List<Integer> discardIndices,
			List<Integer> backupDullIndices) {
		String[] elems = card.elements();
		boolean  isLD  = card.isLightOrDark();
		for (int bi : backupDullIndices) {
			p1BackupStates[bi] = CardState.DULL;
			animateDullBackup(bi, true);
			String cpElem = isLD ? p1BackupCards[bi].elements()[0] : contributingElement(p1BackupCards[bi], elems);
			gameState.addP1Cp(cpElem, 1);
		}
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
			CardData discarded = gameState.getP1Hand().get(di);
			String cpElem = isLD ? discarded.elements()[0] : contributingElement(discarded, elems);
			gameState.addP1Cp(cpElem, 2);
			gameState.breakFromHand(di);
		}
		for (String e : elems) {
			gameState.spendP1Cp(e, gameState.getP1CpForElement(e));
			gameState.clearP1Cp(e);
		}
		if (card.isBackup()) {
			placeCardInFirstBackupSlot(card);
		} else if (card.isForward()) {
			placeCardInForwardZone(card);
		} else if (card.isMonster()) {
			placeCardInMonsterZone(card);
		}
		refreshP1HandLabel();
		refreshP1BreakLabel();
		refreshP1LimitLabel();
	}

	/** Places a card into the first empty P1 backup slot and renders it. */
	private void placeCardInFirstBackupSlot(CardData card) {
		if (p1BackupLabels == null) return;
		sendToBreakZoneByUniquenessRule(card, true);
		for (int i = 0; i < p1BackupLabels.length; i++) {
			if (p1BackupLabels[i] == null || p1BackupLabels[i].getIcon() != null) continue;
			p1BackupUrls[i]          = card.imageUrl();
			p1BackupCards[i]         = card;
			p1BackupStates[i]        = CardState.DULL;
			p1BackupPlayedOnTurn[i]  = gameState.getTurnNumber();
			refreshP1BackupSlot(i);
			triggerAutoAbilitiesForEntersField(card, true);
			break;
		}
	}

	private void animateDullBackup(int idx, boolean dulling) {
		String url  = p1BackupUrls[idx];
		JLabel slot = p1BackupLabels[idx];
		if (url == null || slot == null) return;

		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null : CardAnimation.toARGB(raw, CARD_W, CARD_H);
			}
			@Override protected void done() {
				try {
					BufferedImage card = get();
					if (card == null) { refreshP1BackupSlot(idx); return; }

					int   totalFrames = 12;
					int[] frame       = { 0 };
					javax.swing.Timer timer = new javax.swing.Timer(16, null);
					timer.addActionListener(ae -> {
						if (p1BackupUrls[idx] == null) { timer.stop(); slot.setIcon(null); slot.setText(null); return; }
						frame[0]++;
						double progress = Math.min(1.0, (double) frame[0] / totalFrames);
						// ease in-out
						double t = progress < 0.5
								? 2 * progress * progress
								: 1 - Math.pow(-2 * progress + 2, 2) / 2;
						double angle = dulling ? (Math.PI / 2 * t) : (Math.PI / 2 * (1 - t));
						slot.setIcon(new ImageIcon(CardAnimation.renderBackupCardAtAngle(card, angle)));
						slot.setText(null);
						if (frame[0] >= totalFrames) {
							timer.stop();
							refreshP1BackupSlot(idx);
						}
					});
					timer.start();
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}


	private void animateDullForward(int idx, Runnable onComplete) {
		String url  = p1ForwardUrls.get(idx);
		JLabel slot = p1ForwardLabels.get(idx);
		if (url == null || slot == null) { refreshP1ForwardSlot(idx); if (onComplete != null) onComplete.run(); return; }

		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null : CardAnimation.toARGB(raw, CARD_W, CARD_H);
			}
			@Override protected void done() {
				try {
					BufferedImage card = get();
					if (card == null) { refreshP1ForwardSlot(idx); if (onComplete != null) onComplete.run(); return; }

					int   totalFrames = 12;
					int[] frame       = { 0 };
					javax.swing.Timer timer = new javax.swing.Timer(16, null);
					timer.addActionListener(ae -> {
						frame[0]++;
						double progress = Math.min(1.0, (double) frame[0] / totalFrames);
						double t = progress < 0.5
								? 2 * progress * progress
								: 1 - Math.pow(-2 * progress + 2, 2) / 2;
						double angle = Math.PI / 2 * t;
						slot.setIcon(new ImageIcon(CardAnimation.renderBackupCardAtAngle(card, angle)));
						slot.setText(null);
						if (frame[0] >= totalFrames) {
							timer.stop();
							refreshP1ForwardSlot(idx);
							if (onComplete != null) onComplete.run();
						}
					});
					timer.start();
				} catch (InterruptedException | ExecutionException ignored) {
					refreshP1ForwardSlot(idx);
					if (onComplete != null) onComplete.run();
				}
			}
		}.execute();
	}

	private void animateDullP2Forward(int idx, Runnable onComplete) {
		String url  = p2ForwardUrls.get(idx);
		JLabel slot = p2ForwardLabels.get(idx);
		if (url == null || slot == null) { refreshP2ForwardSlot(idx); if (onComplete != null) onComplete.run(); return; }

		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null : CardAnimation.toARGB(raw, CARD_W, CARD_H);
			}
			@Override protected void done() {
				try {
					BufferedImage card = get();
					if (card == null) { refreshP2ForwardSlot(idx); if (onComplete != null) onComplete.run(); return; }

					int   totalFrames = 12;
					int[] frame       = { 0 };
					javax.swing.Timer timer = new javax.swing.Timer(16, null);
					timer.addActionListener(ae -> {
						frame[0]++;
						double progress = Math.min(1.0, (double) frame[0] / totalFrames);
						double t = progress < 0.5
								? 2 * progress * progress
								: 1 - Math.pow(-2 * progress + 2, 2) / 2;
						double angle = Math.PI / 2 * t;
						slot.setIcon(new ImageIcon(CardAnimation.renderBackupCardAtAngle(card, angle)));
						slot.setText(null);
						if (frame[0] >= totalFrames) {
							timer.stop();
							refreshP2ForwardSlot(idx);
							if (onComplete != null) onComplete.run();
						}
					});
					timer.start();
				} catch (InterruptedException | ExecutionException ignored) {
					refreshP2ForwardSlot(idx);
					if (onComplete != null) onComplete.run();
				}
			}
		}.execute();
	}

	private void animateActivateForward(int idx) {
		String url  = p1ForwardUrls.get(idx);
		JLabel slot = p1ForwardLabels.get(idx);
		if (url == null || slot == null) { refreshP1ForwardSlot(idx); return; }

		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null : CardAnimation.toARGB(raw, CARD_W, CARD_H);
			}
			@Override protected void done() {
				try {
					BufferedImage card = get();
					if (card == null) { refreshP1ForwardSlot(idx); return; }

					int   totalFrames = 12;
					int[] frame       = { 0 };
					javax.swing.Timer timer = new javax.swing.Timer(16, null);
					timer.addActionListener(ae -> {
						frame[0]++;
						double progress = Math.min(1.0, (double) frame[0] / totalFrames);
						double t = progress < 0.5
								? 2 * progress * progress
								: 1 - Math.pow(-2 * progress + 2, 2) / 2;
						double angle = Math.PI / 2 * (1 - t);
						slot.setIcon(new ImageIcon(CardAnimation.renderBackupCardAtAngle(card, angle)));
						slot.setText(null);
						if (frame[0] >= totalFrames) {
							timer.stop();
							refreshP1ForwardSlot(idx);
						}
					});
					timer.start();
				} catch (InterruptedException | ExecutionException ignored) {
					refreshP1ForwardSlot(idx);
				}
			}
		}.execute();
	}

	private void animateActivateP2Forward(int idx) {
		String url  = p2ForwardUrls.get(idx);
		JLabel slot = p2ForwardLabels.get(idx);
		if (url == null || slot == null) { refreshP2ForwardSlot(idx); return; }

		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null : CardAnimation.toARGB(raw, CARD_W, CARD_H);
			}
			@Override protected void done() {
				try {
					BufferedImage card = get();
					if (card == null) { refreshP2ForwardSlot(idx); return; }

					int   totalFrames = 12;
					int[] frame       = { 0 };
					javax.swing.Timer timer = new javax.swing.Timer(16, null);
					timer.addActionListener(ae -> {
						frame[0]++;
						double progress = Math.min(1.0, (double) frame[0] / totalFrames);
						double t = progress < 0.5
								? 2 * progress * progress
								: 1 - Math.pow(-2 * progress + 2, 2) / 2;
						double angle = Math.PI / 2 * (1 - t);
						slot.setIcon(new ImageIcon(CardAnimation.renderBackupCardAtAngle(card, angle)));
						slot.setText(null);
						if (frame[0] >= totalFrames) {
							timer.stop();
							refreshP2ForwardSlot(idx);
						}
					});
					timer.start();
				} catch (InterruptedException | ExecutionException ignored) {
					refreshP2ForwardSlot(idx);
				}
			}
		}.execute();
	}

	/** Reloads and re-renders a single P1 backup slot using its stored URL and state. */
	private void refreshP1BackupSlot(int idx) {
		String url  = p1BackupUrls[idx];
		CardState state = p1BackupStates[idx];
		JLabel slot  = p1BackupLabels[idx];
		if (slot == null) return;
		if (url == null) { slot.setIcon(null); slot.setText(null); return; }
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage card = CardAnimation.toARGB(raw, CARD_W, CARD_H);
				return new ImageIcon(CardAnimation.renderBackupCard(card, state, false, false, p1BackupFrozen[idx]));
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null && p1BackupUrls[idx] != null) { slot.setIcon(icon); slot.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	// -------------------------------------------------------------------------
	// Action Ability helpers
	// -------------------------------------------------------------------------

	/**
	 * Returns a display label for an action ability menu item, e.g.
	 * {@code "[Mug] Wind, Dull, S → ...effect..."} (truncated to 60 chars).
	 */
	private String buildAbilityMenuLabel(ActionAbility ability) {
		StringBuilder sb = new StringBuilder();
		if (ability.isSpecial() && !ability.abilityName().isEmpty())
			sb.append("[").append(ability.abilityName()).append("] ");

		// --- Cost section (left of →) ---
		StringBuilder cost = new StringBuilder();
		boolean firstCost = true;
		if (ability.requiresDull())    { cost.append("Dull");      firstCost = false; }
		if (ability.isSpecial())       { if (!firstCost) cost.append(", "); cost.append("S"); firstCost = false; }
		if (ability.hasXCost())        { if (!firstCost) cost.append(", "); cost.append("X"); firstCost = false; }
		if (ability.crystalCost() > 0) { if (!firstCost) cost.append(", "); cost.append(ability.crystalCost()).append(" Crystal"); firstCost = false; }
		if (ability.selfMillCost() > 0) { if (!firstCost) cost.append(", "); cost.append("mill ").append(ability.selfMillCost()); firstCost = false; }
		for (String e : ability.cpCost()) {
			if (!firstCost) cost.append(", ");
			cost.append(e.isEmpty() ? "any" : e);
			firstCost = false;
		}
		for (BreakZoneCost bz : ability.breakZoneCosts()) {
			if (!firstCost) cost.append(", ");
			cost.append("put ");
			if (bz.name().isEmpty()) cost.append(bz.count()).append(' ').append(bz.cardType());
			else cost.append(bz.name());
			cost.append("→BZ");
			firstCost = false;
		}
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts()) {
			if (!firstCost) cost.append(", ");
			cost.append("RFG ");
			if (rfg.cardName() != null) cost.append(rfg.cardName());
			else {
				cost.append(rfg.count() == -1 ? "all" : rfg.count());
				if (rfg.element()  != null) cost.append(' ').append(rfg.element());
				if (rfg.cardType() != null) cost.append(' ').append(rfg.cardType());
				else cost.append(" card");
			}
			cost.append(" (").append(rfg.zone().toLowerCase().replace('_', ' ')).append(')');
			firstCost = false;
		}
		for (ReturnToHandCost rth : ability.returnToHandCosts()) {
			if (!firstCost) cost.append(", ");
			cost.append("RTH ");
			if (rth.cardName() != null) cost.append(rth.cardName());
			else {
				cost.append(rth.count());
				if (rth.category() != null) cost.append(" Cat.").append(rth.category());
				if (rth.cardType() != null) cost.append(' ').append(rth.cardType());
			}
			firstCost = false;
		}
		sb.append("[").append(firstCost ? "0" : cost).append("] → ");

		// --- Restriction section (right of →, before effect) ---
		StringBuilder restrict = new StringBuilder();
		boolean firstRestrict = true;
		if (ability.damageThreshold() > 0)         { restrict.append("Dmg≥").append(ability.damageThreshold()); firstRestrict = false; }
		if (ability.yourTurnOnly())                 { if (!firstRestrict) restrict.append(", "); restrict.append("your turn");     firstRestrict = false; }
		if (ability.oncePerTurn())                  { if (!firstRestrict) restrict.append(", "); restrict.append("1/turn");        firstRestrict = false; }
		if (ability.mainPhaseOnly())                { if (!firstRestrict) restrict.append(", "); restrict.append("main phase");    firstRestrict = false; }
		if (ability.whilePartyAttacking())          { if (!firstRestrict) restrict.append(", "); restrict.append("while party atk"); firstRestrict = false; }
		else if (ability.whileCardAttacking() != null) { if (!firstRestrict) restrict.append(", "); restrict.append("while ").append(ability.whileCardAttacking()).append(" atk"); firstRestrict = false; }
		if (ability.whileCardBlocking() != null)    { if (!firstRestrict) restrict.append(", "); restrict.append("while ").append(ability.whileCardBlocking()).append(" blk"); }
		if (restrict.length() > 0) sb.append(restrict).append(" — ");

		String fx = ability.effectText();
		sb.append(fx.length() > 55 ? fx.substring(0, 52) + "..." : fx);
		return sb.toString();
	}

	/**
	 * Returns {@code true} if the player can afford the CP portion of an action
	 * ability's cost (element and generic CP only; Dull/S requirements are checked
	 * separately in the context-menu enable logic).
	 */
	private boolean canAffordAbilityCost(ActionAbility ability, boolean isP1) {
		List<String> cost = ability.cpCost();
		if (cost.isEmpty()) return true;

		boolean hasGeneric = cost.contains("");
		LinkedHashMap<String, Integer> needed = new LinkedHashMap<>();
		for (String e : cost) if (!e.isEmpty()) needed.merge(e, 1, Integer::sum);
		String[] elems = needed.keySet().toArray(String[]::new);
		int total = cost.size();

		boolean[] hasSrc = new boolean[elems.length];
		int available = 0;

		for (int ei = 0; ei < elems.length; ei++) {
			int b = playerCpForElem(isP1, elems[ei]);
			available += b;
			if (b > 0) hasSrc[ei] = true;
		}
		if (hasGeneric) {
			available += playerCpByElem(isP1).values().stream().mapToInt(Integer::intValue).sum();
			for (int ei = 0; ei < elems.length; ei++) available -= playerCpForElem(isP1, elems[ei]);
		}
		CardData[]  bkpCards  = playerBackupCards(isP1);
		CardState[] bkpStates = playerBackupStates(isP1);
		for (int i = 0; i < bkpCards.length; i++) {
			if (bkpCards[i] == null || bkpStates[i] != CardState.ACTIVE) continue;
			boolean matched = false;
			for (int ei = 0; ei < elems.length; ei++) {
				if (bkpCards[i].containsElement(elems[ei])) { available++; hasSrc[ei] = true; matched = true; break; }
			}
			if (!matched && hasGeneric) available++;
		}
		for (CardData h : playerHand(isP1)) {
			if (h.isLightOrDark()) continue;
			available += 2;
			for (int ei = 0; ei < elems.length; ei++) if (h.containsElement(elems[ei])) hasSrc[ei] = true;
		}
		for (boolean s : hasSrc) if (!s) return false;
		return available >= total;
	}

	/**
	 * Returns {@code true} if the given player has at least one card named {@code name}
	 * in hand (needed for Special Ability payment).
	 */
	private boolean hasSameNameInHand(String name, boolean isP1) {
		for (CardData c : playerHand(isP1))
			if (name.equalsIgnoreCase(c.name())) return true;
		return false;
	}

	// ---- Per-player data selectors used by the ability payment chain -----------

	private List<CardData> playerHand(boolean isP1)       { return isP1 ? gameState.getP1Hand()       : gameState.getP2Hand(); }
	private CardData[]     playerBackupCards(boolean isP1) { return isP1 ? p1BackupCards               : p2BackupCards; }
	private CardState[]    playerBackupStates(boolean isP1){ return isP1 ? p1BackupStates              : p2BackupStates; }
	private boolean[]      playerBackupFrozen(boolean isP1){ return isP1 ? p1BackupFrozen              : p2BackupFrozen; }
	private String[]       playerBackupUrls(boolean isP1)  { return isP1 ? p1BackupUrls                : p2BackupUrls; }
	private List<CardData> playerForwardCards(boolean isP1){ return isP1 ? p1ForwardCards              : p2ForwardCards; }
	private List<CardData> playerMonsterCards(boolean isP1){ return isP1 ? p1MonsterCards              : p2MonsterCards; }
	private int  playerCrystals(boolean isP1)              { return isP1 ? gameState.getP1Crystals()   : gameState.getP2Crystals(); }
	private int  playerCpForElem(boolean isP1, String e)   { return isP1 ? gameState.getP1CpForElement(e) : gameState.getP2CpForElement(e); }
	private Map<String, Integer> playerCpByElem(boolean isP1) { return isP1 ? gameState.getP1CpByElement() : gameState.getP2CpByElement(); }
	private void playerAddCp(boolean isP1, String e, int n)    { if (isP1) gameState.addP1Cp(e, n);   else gameState.addP2Cp(e, n); }
	private void playerSpendCp(boolean isP1, String e, int n)  { if (isP1) gameState.spendP1Cp(e, n); else gameState.spendP2Cp(e, n); }
	private void playerClearCp(boolean isP1, String e)         { if (isP1) gameState.clearP1Cp(e);    else gameState.clearP2Cp(e); }
	private void playerSpendCrystals(boolean isP1, int n)      { if (isP1) gameState.spendP1Crystals(n); else gameState.spendP2Crystals(n); }
	private CardData playerBreakFromHand(boolean isP1, int i)  { return isP1 ? gameState.breakFromHand(i) : gameState.breakP2FromHand(i); }
	private void playerDullBackupSlot(boolean isP1, int idx) {
		if (isP1) animateDullBackup(idx, true); else animateDullP2Backup(idx, true);
	}

	private void animateDullP2Backup(int idx, boolean dulling) {
		String url  = p2BackupUrls[idx];
		JLabel slot = p2BackupLabels[idx];
		if (url == null || slot == null) return;
		new SwingWorker<java.awt.image.BufferedImage, Void>() {
			@Override protected java.awt.image.BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null : CardAnimation.toARGB(raw, CARD_W, CARD_H);
			}
			@Override protected void done() {
				try {
					java.awt.image.BufferedImage card = get();
					if (card == null) { refreshP2BackupSlot(idx); return; }
					int totalFrames = 12; int[] frame = {0};
					javax.swing.Timer timer = new javax.swing.Timer(16, null);
					timer.addActionListener(ae -> {
						if (p2BackupUrls[idx] == null) { timer.stop(); slot.setIcon(null); slot.setText(null); return; }
						frame[0]++;
						double progress = Math.min(1.0, (double) frame[0] / totalFrames);
						double t = progress < 0.5 ? 2*progress*progress : 1 - Math.pow(-2*progress+2, 2)/2;
						double angle = dulling ? (Math.PI/2*t) : (Math.PI/2*(1-t));
						slot.setIcon(new ImageIcon(CardAnimation.renderBackupCardAtAngle(card, angle)));
						slot.setText(null);
						if (frame[0] >= totalFrames) { timer.stop(); refreshP2BackupSlot(idx); }
					});
					timer.start();
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	private void breakP2BackupSlot(int idx) {
		CardData c = p2BackupCards[idx];
		if (c == null) return;
		startBreakAnim(p2BackupLabels[idx]);
		logEntry("[P2] " + c.name() + " → Break Zone");
		addToP2BreakZone(c);
		p2BackupCards[idx]  = null;
		p2BackupUrls[idx]   = null;
		p2BackupStates[idx] = CardState.ACTIVE;
		p2BackupFrozen[idx] = false;
		if (p2BackupLabels[idx] != null) {
			p2BackupLabels[idx].setIcon(null);
			p2BackupLabels[idx].setText(null);
		}
		refreshP2BreakLabel();
		triggerAutoAbilitiesForLeavesField(c, false);
		triggerAutoAbilitiesForBreakZone(c, false);
	}

	private void breakP2MonsterSlot(int idx) {
		if (idx >= p2MonsterCards.size()) return;
		startBreakAnim(p2MonsterLabels.get(idx));
		CardData c = p2MonsterCards.get(idx);
		logEntry("[P2] " + c.name() + " → Break Zone");
		addToP2BreakZone(c);
		p2MonsterTempForwardPower.remove(c);
		p2MonsterCards.remove(idx);
		p2MonsterStates.remove(idx);
		p2MonsterFrozen.remove(idx);
		p2MonsterPlayedOnTurn.remove(idx);
		p2MonsterDamage.remove(idx);
		p2MonsterUrls.remove(idx);
		JLabel lbl = p2MonsterLabels.remove(idx);
		if (p2MonsterPanel != null) {
			p2MonsterPanel.remove(lbl);
			p2MonsterPanel.revalidate();
			p2MonsterPanel.repaint();
		}
		refreshP2BreakLabel();
		triggerAutoAbilitiesForBreakZone(c, false);
	}

	/**
	 * Returns {@code true} if {@code ability} can currently be activated by the
	 * card at the given slot.
	 *
	 * @param state       current card state (ACTIVE / DULL / BRAVE_ATTACKED)
	 * @param playedTurn  turn the card entered the field (0 = unknown)
	 * @param sourceName  card name, needed for special-ability hand check
	 */
	private boolean canActivateAbility(ActionAbility ability, boolean isFrozen, CardState state,
			int playedTurn, CardData source, boolean isP1) {
		if (ability.yourTurnOnly() && !isP1) return false;
		if (ability.oncePerTurn()
				&& usedOncePerTurnAbilities.getOrDefault(source, java.util.Set.of()).contains(ability.effectText()))
			return false;
		if (ability.mainPhaseOnly()) {
			GameState.GamePhase p = gameState.getCurrentPhase();
			if (p != GameState.GamePhase.MAIN_1 && p != GameState.GamePhase.MAIN_2) return false;
		}
		// Attack-phase restrictions — all require the game to be in the ATTACK phase
		if (ability.whileCardAttacking() != null || ability.whileCardBlocking() != null || ability.whilePartyAttacking()) {
			if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return false;
		}
		if (ability.whileCardAttacking() != null) {
			boolean found = p1AttackSelection.stream()
					.anyMatch(i -> i < p1ForwardCards.size()
							&& p1ForwardCards.get(i).name().equalsIgnoreCase(ability.whileCardAttacking()));
			if (!found) return false;
		}
		if (ability.whileCardBlocking() != null) {
			if (p1BlockingIdx < 0 || p1BlockingIdx >= p1ForwardCards.size()) return false;
			if (!p1ForwardCards.get(p1BlockingIdx).name().equalsIgnoreCase(ability.whileCardBlocking())) return false;
		}
		if (ability.whilePartyAttacking() && p1AttackSelection.size() < 2) return false;
		if (ability.hasBlockingTargetEffect()) {
			if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return false;
			if (attackSubStep != 3) return false;
			boolean anyBlocking = (p1BlockingIdx >= 0 && p1BlockingIdx < p1ForwardCards.size())
					|| (p2BlockingIdx >= 0 && p2BlockingIdx < p2ForwardCards.size());
			if (!anyBlocking) return false;
		}
		if (ability.requiresDull()) {
			if (state != CardState.ACTIVE) return false;
			if (playedTurn == gameState.getTurnNumber()) return false;
		}
		if (ability.isSpecial() && !hasSameNameInHand(source.name(), isP1)) return false;
		if (ability.damageThreshold() > 0) {
			int dmg = isP1 ? gameState.getP1DamageZone().size() : gameState.getP2DamageZone().size();
			if (dmg < ability.damageThreshold()) return false;
		}
		if (ability.controlCondition() != null && !controlConditionMet(ability.controlCondition(), isP1)) return false;
		if (ability.crystalCost() > 0 && playerCrystals(isP1) < ability.crystalCost()) return false;
		for (BreakZoneCost bz : ability.breakZoneCosts())
			if (!bzCostSatisfied(bz, isP1)) return false;
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			if (!rfgCostSatisfied(rfg, isP1)) return false;
		for (ReturnToHandCost rth : ability.returnToHandCosts())
			if (!rfthCostSatisfied(rth, isP1)) return false;
		for (CounterCost cc : ability.counterCosts())
			if (!counterCostSatisfied(cc, source)) return false;
		for (DullForwardCost dfc : ability.dullForwardCosts())
			if (!dullForwardCostSatisfied(dfc, isP1)) return false;
		return canAffordAbilityCost(ability, isP1);
	}

	/**
	 * Returns {@code true} when the "if you control [X]" restriction on an action ability is met
	 * by the controlling player's current field state.
	 */
	private boolean controlConditionMet(ControlCondition cond, boolean isP1) {
		return controlConditionMetWithPools(cond,
				isP1 ? p1ForwardCards : p2ForwardCards,
				isP1 ? p1BackupCards  : p2BackupCards,
				isP1 ? p1MonsterCards : p2MonsterCards);
	}

	private boolean controlConditionMetWithPools(ControlCondition cond,
			List<CardData> fwds, CardData[] bkps, List<CardData> mons) {
		if (cond.isNamedMode()) {
			for (String name : cond.requiredCardNames()) {
				boolean found = fwds.stream().anyMatch(c -> c.name().equalsIgnoreCase(name))
						|| mons.stream().anyMatch(c -> c.name().equalsIgnoreCase(name));
				if (!found) for (CardData bkp : bkps) if (bkp != null && bkp.name().equalsIgnoreCase(name)) { found = true; break; }
				if (!found) return false;
			}
			return true;
		}

		// Count mode: collect field cards that match the type filter
		String type = cond.cardType() != null ? cond.cardType().toLowerCase() : null;
		List<CardData> pool = new ArrayList<>();
		if (type == null || type.equals("forward") || type.equals("character")) pool.addAll(fwds);
		if (type == null || type.equals("monster")  || type.equals("character")) pool.addAll(mons);
		if (type == null || type.equals("backup")   || type.equals("character")) {
			for (CardData bkp : bkps) if (bkp != null) pool.add(bkp);
		}

		int count = 0;
		for (CardData card : pool) {
			boolean matchesAltName = !cond.orCardNames().isEmpty()
					&& cond.orCardNames().stream().anyMatch(n -> n.equalsIgnoreCase(card.name()));
			if (matchesAltName) { count++; continue; }
			if (cond.element()  != null && !card.containsElement(cond.element())) continue;
			if (cond.job()      != null && !meetsJobFilter(card, cond.job()))      continue;
			if (cond.category() != null && !meetsCategoryFilter(card, cond.category())) continue;
			if (cond.minPower() > 0     && card.power() < cond.minPower())         continue;
			count++;
		}
		return cond.exactCount() ? count == cond.minCount() : count >= cond.minCount();
	}

	/**
	 * Like {@link #controlConditionMet} but removes all instances of {@code exceptName} from
	 * every pool before evaluating — used for the "other than X" exclusion in
	 * {@link IfControlBoost}.
	 */
	private boolean controlConditionMetExcluding(ControlCondition cond, String exceptName, boolean isP1) {
		if (exceptName.isEmpty()) return controlConditionMet(cond, isP1);
		List<CardData> fwds = new ArrayList<>(isP1 ? p1ForwardCards : p2ForwardCards);
		CardData[] srcBkps  = isP1 ? p1BackupCards : p2BackupCards;
		CardData[] bkps     = java.util.Arrays.copyOf(srcBkps, srcBkps.length);
		List<CardData> mons = new ArrayList<>(isP1 ? p1MonsterCards : p2MonsterCards);
		fwds.removeIf(c -> c.name().equalsIgnoreCase(exceptName));
		mons.removeIf(c -> c.name().equalsIgnoreCase(exceptName));
		for (int i = 0; i < bkps.length; i++)
			if (bkps[i] != null && bkps[i].name().equalsIgnoreCase(exceptName)) bkps[i] = null;
		return controlConditionMetWithPools(cond, fwds, bkps, mons);
	}

	/** Returns {@code true} when all conditions of {@code icb} are satisfied for the given player. */
	private boolean icbConditionsMet(IfControlBoost icb, boolean isP1) {
		for (ControlCondition cond : icb.conditions())
			if (!controlConditionMetExcluding(cond, icb.exceptCardName(), isP1)) return false;
		return true;
	}

	/**
	 * Computes the total conditional power bonus for a field card named {@code targetName}
	 * on the given player's side, summing contributions from all {@link IfControlBoost}
	 * abilities across every card currently on that player's field.
	 */
	private int computeConditionalBoostForTarget(CardData target, boolean isP1) {
		int boost = 0;
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : fwds) boost += fieldBoostContribution(src, target, isP1);
		for (CardData bkp : bkps) if (bkp != null) boost += fieldBoostContribution(bkp, target, isP1);
		for (CardData src : mons) boost += fieldBoostContribution(src, target, isP1);
		return boost;
	}

	private int fieldBoostContribution(CardData src, CardData target, boolean isP1) {
		int boost = 0;
		for (IfControlBoost icb : src.ifControlBoosts())
			if (icb.targetCardName().equalsIgnoreCase(target.name()) && icbConditionsMet(icb, isP1))
				boost += icb.powerBonus();
		for (FieldPowerGrant fpg : src.fieldPowerGrants())
			if (fpg.appliesToCard(target))
				boost += fpg.powerBonus();
		return boost;
	}

	/**
	 * Collects all traits conditionally granted to {@code target} on the given player's side
	 * by any active {@link IfControlBoost} or {@link FieldPowerGrant} on the field.
	 */
	private java.util.EnumSet<CardData.Trait> computeConditionalTraitsForTarget(CardData target, boolean isP1) {
		java.util.EnumSet<CardData.Trait> out = java.util.EnumSet.noneOf(CardData.Trait.class);
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : fwds) collectFieldTraits(src, target, isP1, out);
		for (CardData bkp : bkps) if (bkp != null) collectFieldTraits(bkp, target, isP1, out);
		for (CardData src : mons) collectFieldTraits(src, target, isP1, out);
		return out;
	}

	private void collectFieldTraits(CardData src, CardData target, boolean isP1,
			java.util.EnumSet<CardData.Trait> out) {
		for (IfControlBoost icb : src.ifControlBoosts())
			if (icb.targetCardName().equalsIgnoreCase(target.name()) && icbConditionsMet(icb, isP1))
				out.addAll(icb.grantedTraits());
		for (FieldPowerGrant fpg : src.fieldPowerGrants())
			if (fpg.appliesToCard(target))
				out.addAll(fpg.grantedTraits());
	}

	private int effectiveP1MonsterPower(int idx) {
		CardData card = p1MonsterCards.get(idx);
		return card.power() + computeConditionalBoostForTarget(card, true);
	}

	private int effectiveP2MonsterPower(int idx) {
		CardData card = p2MonsterCards.get(idx);
		return card.power() + computeConditionalBoostForTarget(card, false);
	}

	// -------------------------------------------------------------------------
	// Auto Ability triggers
	// -------------------------------------------------------------------------

	/**
	 * Matches "remove N [Name] Counter(s) from [CardName][.] When/If you do so, sub-effect".
	 * Used for auto-ability costs that consume a named counter before resolving an effect.
	 */
	private static final java.util.regex.Pattern FA_REMOVE_COUNTER_WHEN_DO_SO =
			java.util.regex.Pattern.compile(
				"(?i)^remove\\s+(?<n>\\d+)\\s+(?<counterName>.+?)\\s+Counters?\\s+from" +
				"\\s+(?<target>.+?)[.,!]\\s+(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(?<sub>.+?)$",
				java.util.regex.Pattern.DOTALL
			);

	/**
	 * Matches "remove N [type] [without 《Keyword》] [you control / opponent controls]
	 * from the game. When/If you do so, sub-effect."
	 * <ul>
	 *   <li>{@code count}     — number of cards to remove</li>
	 *   <li>{@code targets}   — card type: Backup, Forward, Monster, or Character</li>
	 *   <li>{@code excludekw} — optional keyword exclusion (e.g. "Multicard") from "without 《Keyword》"</li>
	 *   <li>{@code control}   — "you control" or "opponent controls"</li>
	 *   <li>{@code sub}       — effect to execute after the removal succeeds</li>
	 * </ul>
	 */
	private static final java.util.regex.Pattern FA_REMOVE_FIELD_WHEN_DO_SO =
			java.util.regex.Pattern.compile(
				"(?i)^remove\\s+(?<count>\\d+)\\s+" +
				"(?<targets>Backups?|Forwards?|Monsters?|Characters?)\\s+" +
				"(?:without\\s+《(?<excludekw>[^》]+)》\\s+)?" +
				"(?<control>(?:your\\s+)?opponent\\s+controls|you\\s+control)\\s+" +
				"from\\s+the\\s+game[.,]?\\s+" +
				"(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+" +
				"(?<sub>.+?)$",
				java.util.regex.Pattern.DOTALL
			);

	/**
	 * Matches "put N [Job jobname / Card Name name / type] you control into the Break Zone.
	 * When/If you do so, sub-effect."
	 */
	private static final java.util.regex.Pattern FA_PUT_INTO_BZ_WHEN_DO_SO =
			java.util.regex.Pattern.compile(
				"(?i)^put\\s+(?<count>\\d+)\\s+" +
				"(?:" +
					"Job\\s+(?<job>.+?)\\s+you\\s+control" +
				"|" +
					"Card\\s+Name\\s+(?<cardname>\\S+(?:\\s+\\([^)]+\\))?)\\s+you\\s+control" +
				"|" +
					"(?<type>Forwards?|Backups?|Monsters?|Characters?)\\s+you\\s+control" +
				")" +
				"\\s+into\\s+the\\s+Break\\s+Zone[.,]?\\s+" +
				"(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+" +
				"(?<sub>.+?)$",
				java.util.regex.Pattern.DOTALL
			);

	/**
	 * Matches a card's own passive field ability text:
	 * "If &lt;cardName&gt; is dealt damage by your opponent's Summons, the damage becomes 0 instead."
	 * Checked inline in {@link #modifyIncomingDamage} against the receiving card's field abilities.
	 */
	private static final java.util.regex.Pattern FA_NULLIFY_SUMMON_DAMAGE =
			java.util.regex.Pattern.compile(
				"(?i)If\\s+(?<card>.+?)\\s+is\\s+dealt\\s+damage\\s+by\\s+your\\s+opponent's\\s+Summons?,\\s+the\\s+damage\\s+becomes\\s+0\\s+instead\\.?"
			);

	/**
	 * Matches "select [up to] N of the M following actions. "action1" "action2" ..."
	 * with an optional leading "if condition, " clause.
	 * <ul>
	 *   <li>{@code condition} — optional "if" clause text (without "if " prefix), e.g.
	 *       {@code "you control a Job AVALANCHE Operative Forward"}</li>
	 *   <li>{@code upTo}     — non-null when "up to" is present</li>
	 *   <li>{@code select}   — how many actions the player chooses</li>
	 *   <li>{@code total}    — total number of options listed</li>
	 *   <li>{@code actions}  — the remainder containing the quoted action strings</li>
	 * </ul>
	 */
	private static final java.util.regex.Pattern FA_SELECT_FOLLOWING_ACTIONS =
		java.util.regex.Pattern.compile(
			"(?i)^(?:if\\s+(?<condition>[^,]+),\\s+)?select\\s+(?<upTo>up\\s+to\\s+)?" +
			"(?<select>\\d+)\\s+of\\s+the\\s+(?<total>\\d+)\\s+following\\s+actions?[.!]?\\s*" +
			"(?<actions>.+)$",
			java.util.regex.Pattern.DOTALL
		);

	/** Extracts individual quoted action strings from the {@code actions} capture group above. */
	private static final java.util.regex.Pattern FA_QUOTED_ACTION =
		java.util.regex.Pattern.compile("\"([^\"]+)\"");

	/** Matches "pay 《cost》[.] When/If you do so, sub-effect[. The maximum you can pay for 《X》 is N]". */
	private static final java.util.regex.Pattern FA_PAY_WHEN_DO_SO = java.util.regex.Pattern.compile(
		"(?i)^pay\\s+《([^》]+)》[.,]?\\s+(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(.+?)(?:[.,]?\\s+The\\s+maximum\\s+you\\s+can\\s+pay\\s+for\\s+《X》\\s+is\\s+\\d+\\.?)?$",
		java.util.regex.Pattern.DOTALL
	);
	private static final java.util.regex.Pattern FA_MAX_X = java.util.regex.Pattern.compile(
		"(?i)The\\s+maximum\\s+you\\s+can\\s+pay\\s+for\\s+《X》\\s+is\\s+(\\d+)"
	);
	private static final java.util.Set<String> ELEMENT_NAMES = java.util.Set.of(
		"fire", "ice", "wind", "earth", "lightning", "water", "light", "dark"
	);

	private void triggerAutoAbilitiesForEntersField(CardData card, boolean isP1) {
		if (suppressAutoAbilityForNextCard) {
			suppressAutoAbilityForNextCard = false;
			// Re-evaluate field boosts even when ETF auto-abilities are suppressed
			refreshAllForwardSlots();
			for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
			return;
		}
		for (AutoAbility fa : card.autoAbilities()) {
			if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
			if (fa.trigger().contains("enter")) executeAutoAbility(fa, card, isP1);
		}
		// Re-evaluate all conditional field boosts now that the field composition has changed
		refreshAllForwardSlots();
		for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
	}

	private void triggerAutoAbilitiesForDealsDamageToOpponent(CardData attacker, boolean attackerIsP1) {
		for (AutoAbility fa : attacker.autoAbilities()) {
			if (!fa.triggerCard().equalsIgnoreCase(attacker.name())) continue;
			if (fa.trigger().equals("deals damage to opponent")) executeAutoAbility(fa, attacker, attackerIsP1);
		}
	}

	private void triggerAutoAbilitiesForAttack(CardData card, boolean isP1) {
		for (AutoAbility fa : card.autoAbilities()) {
			if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
			if (fa.trigger().contains("attack")) executeAutoAbility(fa, card, isP1);
		}
		// Fire any temporary attack triggers registered this turn by action abilities
		Map<CardData, List<Consumer<GameContext>>> tempTriggers
				= isP1 ? p1TempAttackTriggers : p2TempAttackTriggers;
		List<Consumer<GameContext>> effects = tempTriggers.get(card);
		if (effects != null) {
			GameContext ctx = buildGameContext(isP1);
			for (Consumer<GameContext> effect : effects)
				effect.accept(ctx);
		}
	}

	private void triggerAutoAbilitiesForBlock(CardData card, boolean isP1) {
		for (AutoAbility fa : card.autoAbilities()) {
			if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
			String t = fa.trigger();
			if (t.equals("blocks") || t.equals("attacks or blocks") || t.equals("blocks or is blocked"))
				executeAutoAbility(fa, card, isP1);
		}
		Map<CardData, List<Consumer<GameContext>>> tempTriggers
				= isP1 ? p1TempBlockTriggers : p2TempBlockTriggers;
		List<Consumer<GameContext>> effects = tempTriggers.get(card);
		if (effects != null) {
			GameContext ctx = buildGameContext(isP1);
			for (Consumer<GameContext> effect : effects)
				effect.accept(ctx);
		}
	}

	private void triggerAutoAbilitiesForIsBlocked(CardData card, boolean isP1) {
		for (AutoAbility fa : card.autoAbilities()) {
			if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
			String t = fa.trigger();
			if (t.equals("is blocked") || t.equals("blocks or is blocked"))
				executeAutoAbility(fa, card, isP1);
		}
	}

	/** Fires "party attacks" field abilities on every card the controller has on the field. */
	private void triggerAutoAbilitiesForPartyAttack(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		for (int i = 0; i < fwds.size(); i++) {
			CardData card = fwds.get(i);
			for (AutoAbility fa : card.autoAbilities())
				if (fa.trigger().equals("party attacks"))
					executeAutoAbility(fa, card, isP1);
		}
		CardData[] bkps = isP1 ? p1BackupCards : p2BackupCards;
		for (CardData card : bkps) {
			if (card == null) continue;
			for (AutoAbility fa : card.autoAbilities())
				if (fa.trigger().equals("party attacks"))
					executeAutoAbility(fa, card, isP1);
		}
	}

	/** Subject pattern for break-zone triggers: "a [Type] [you|opponent] control[s]". */
	private static final java.util.regex.Pattern BZ_SUBJECT_TYPE = java.util.regex.Pattern.compile(
		"(?i)^a\\s+(?<type>Character|Forward|Backup|Monster)\\s+(?<ctrl>you|opponent)\\s+controls?$"
	);

	/**
	 * Returns true when the broken card satisfies the break-zone trigger subject of {@code fa}.
	 * Handles named cards ("Geomancer") and type+controller phrases ("a Forward you control").
	 */
	private boolean matchesBreakZoneSubject(AutoAbility fa, CardData broken, boolean brokenIsP1, boolean abilityOwnerIsP1) {
		String subject = fa.triggerCard().trim();
		java.util.regex.Matcher m = BZ_SUBJECT_TYPE.matcher(subject);
		if (m.matches()) {
			boolean selfCtrl     = m.group("ctrl").equalsIgnoreCase("you");
			boolean brokenByOwner = (brokenIsP1 == abilityOwnerIsP1);
			if (selfCtrl != brokenByOwner) return false;
			return switch (m.group("type").toLowerCase(java.util.Locale.ROOT)) {
				case "forward"   -> broken.isForward();
				case "backup"    -> broken.isBackup();
				case "monster"   -> broken.isMonster();
				default          -> !broken.isSummon(); // "Character" = any non-Summon field card
			};
		}
		// Fall back to named card match (handles "Geomancer", etc.)
		return broken.name().equalsIgnoreCase(subject);
	}

	/**
	 * Fires "put into break zone" field abilities on all field cards whose subject matches
	 * the card that just broke.  Must be called after the card is removed from the field.
	 */
	private void triggerAutoAbilitiesForBreakZone(CardData broken, boolean brokenIsP1) {
		for (int pass = 0; pass < 2; pass++) {
			boolean ownerIsP1 = (pass == 0);
			List<CardData> fwds = new ArrayList<>(ownerIsP1 ? p1ForwardCards : p2ForwardCards);
			CardData[]     bkps = ownerIsP1 ? p1BackupCards : p2BackupCards;
			List<CardData> mons = new ArrayList<>(ownerIsP1 ? p1MonsterCards : p2MonsterCards);
			for (CardData c : fwds) fireBreakZoneTriggers(c, ownerIsP1, broken, brokenIsP1);
			for (CardData c : bkps) if (c != null) fireBreakZoneTriggers(c, ownerIsP1, broken, brokenIsP1);
			for (CardData c : mons) fireBreakZoneTriggers(c, ownerIsP1, broken, brokenIsP1);
		}
	}

	private void fireBreakZoneTriggers(CardData card, boolean ownerIsP1, CardData broken, boolean brokenIsP1) {
		for (AutoAbility fa : card.autoAbilities()) {
			if (!fa.trigger().equals("put into break zone")) continue;
			if (!matchesBreakZoneSubject(fa, broken, brokenIsP1, ownerIsP1)) continue;
			executeAutoAbility(fa, card, ownerIsP1);
		}
	}

	/**
	 * Fires "leaves the field" field abilities that belong to {@code departing} itself.
	 * Call this after the card has been removed from all field tracking lists.
	 */
	private void triggerAutoAbilitiesForLeavesField(CardData departing, boolean isP1) {
		for (AutoAbility fa : departing.autoAbilities()) {
			if (!fa.trigger().equals("leaves the field")) continue;
			if (!fa.triggerCard().equalsIgnoreCase(departing.name())) continue;
			executeAutoAbility(fa, departing, isP1);
		}
		gameState.clearCounters(departing);
		// Re-evaluate all conditional field boosts now that the field composition has changed
		refreshAllForwardSlots();
		for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
	}

	/** Fires "cast summon" field abilities for all field cards belonging to the casting player. */
	private void triggerAutoAbilitiesForCastSummon(boolean isP1) {
		triggerAutoAbilitiesForEvent("cast summon", isP1);
	}

	/** Fires "damage zone" field abilities for all field cards belonging to the player who took damage. */
	private void triggerAutoAbilitiesForDamageZone(boolean isP1) {
		triggerAutoAbilitiesForEvent("damage zone", isP1);
	}

	/**
	 * Resolves the EX Burst effect on {@code card} for the player whose damage zone received it.
	 * The controlling player may decline; if accepted the effect resolves immediately, bypassing
	 * the stack so neither player can respond.
	 * Summon effects run the full card effect; forward/backup/monster effects strip the auto-ability
	 * trigger prefix and run the bare effect text.
	 */
	private void triggerExBurst(CardData card, boolean isP1) {
		String effect = card.exBurstEffect();
		if (effect.isEmpty()) {
			logEntry("[EX BURST] " + card.name() + " — no parseable effect");
			return;
		}
		Consumer<GameContext> fn = ActionResolver.parse(effect, card);
		if (fn == null) {
			logEntry("[EX BURST] Effect not yet implemented: " + effect);
			return;
		}
		if (isP1) {
			JDialog dlg = new JDialog(frame, "EX Burst — " + card.name(), true);
			dlg.setResizable(false);
			dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

			JLabel cardLabel = new JLabel("...", SwingConstants.CENTER);
			cardLabel.setPreferredSize(new Dimension(CARD_W, CARD_H));
			cardLabel.setMinimumSize(new Dimension(CARD_W, CARD_H));
			cardLabel.setOpaque(true);
			cardLabel.setBackground(Color.DARK_GRAY);
			cardLabel.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
			cardLabel.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) { showZoomAt(card.imageUrl()); }
				@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
			});
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(card.imageUrl());
					return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { cardLabel.setIcon(ic); cardLabel.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(card.name(), SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			JLabel effectLabel = new JLabel(
					"<html><div style='text-align:center;width:" + CARD_W + "px'>" + effect + "</div></html>",
					SwingConstants.CENTER);

			JPanel infoPanel = new JPanel();
			infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
			nameLabel.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
			effectLabel.setAlignmentX(java.awt.Component.CENTER_ALIGNMENT);
			infoPanel.add(nameLabel);
			infoPanel.add(effectLabel);

			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
			wrapper.add(cardLabel,  BorderLayout.CENTER);
			wrapper.add(infoPanel,  BorderLayout.SOUTH);

			boolean[] activated = {false};
			JButton declineBtn = new JButton("Decline");
			declineBtn.setFont(FontLoader.loadPixelNESFont(11));
			declineBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });
			JButton okBtn = new JButton("OK");
			okBtn.setFont(FontLoader.loadPixelNESFont(11));
			okBtn.addActionListener(ae -> { activated[0] = true; hideZoom(); dlg.dispose(); });

			JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
			south.add(declineBtn);
			south.add(okBtn);
			south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

			dlg.getContentPane().setLayout(new BorderLayout(0, 4));
			dlg.getContentPane().add(wrapper, BorderLayout.CENTER);
			dlg.getContentPane().add(south,   BorderLayout.SOUTH);
			dlg.pack();
			dlg.setLocationRelativeTo(frame);
			dlg.setVisible(true);

			if (!activated[0]) {
				logEntry("[EX BURST] " + card.name() + " — declined");
				return;
			}
		} else {
			logEntry("[EX BURST] [AI] " + card.name() + " — auto-activates");
		}
		logEntry("[EX BURST] " + card.name() + " — " + effect);
		if (card.isSummon()) currentResolutionIsSummon = true;
		try { fn.accept(buildGameContext(isP1, true)); } finally { currentResolutionIsSummon = false; }
	}

	/**
	 * Fires "warp placed" field abilities on all P1 field cards whose {@code triggerCard}
	 * matches the card that was just moved from hand to the Warp zone.
	 */
	private void triggerAutoAbilitiesForWarpPlaced(CardData warped) {
		List<CardData> all = new ArrayList<>();
		all.addAll(p1ForwardCards);
		for (CardData c : p1BackupCards) if (c != null) all.add(c);
		all.addAll(p1MonsterCards);
		for (CardData card : all)
			for (AutoAbility fa : card.autoAbilities())
				if (fa.trigger().equals("warp placed")
						&& fa.triggerCard().equalsIgnoreCase(warped.name()))
					executeAutoAbility(fa, card, true);
	}

	/**
	 * Fires "warp counter removed" field abilities on all P1 field cards whose
	 * {@code triggerCard} matches the card whose counter was just decremented.
	 */
	private void triggerAutoAbilitiesForWarpCounterRemoved(CardData target) {
		List<CardData> all = new ArrayList<>();
		List<GameState.WarpEntry> warpZone = gameState.getP1WarpZone();
		all.addAll(p1ForwardCards);
		for (CardData c : p1BackupCards) if (c != null) all.add(c);
		for (GameState.WarpEntry we : warpZone) if (we != null) all.add(we.card);
		all.addAll(p1MonsterCards);
		for (CardData card : all)
			for (AutoAbility fa : card.autoAbilities())
				if (fa.trigger().equals("warp counter removed")
						&& (fa.triggerCard().equalsIgnoreCase("any player's card") || fa.triggerCard().equalsIgnoreCase(target.name())))
					executeAutoAbility(fa, card, true);
	}

	private void triggerAutoAbilitiesForEvent(String triggerType, boolean isP1) {
		List<CardData> fwds = new ArrayList<>(isP1 ? p1ForwardCards : p2ForwardCards);
		CardData[]     bkps = isP1 ? p1BackupCards : p2BackupCards;
		List<CardData> mons = new ArrayList<>(isP1 ? p1MonsterCards : p2MonsterCards);
		for (CardData c : fwds) fireEventTriggers(c, isP1, triggerType);
		for (CardData c : bkps) if (c != null) fireEventTriggers(c, isP1, triggerType);
		for (CardData c : mons) fireEventTriggers(c, isP1, triggerType);
	}

	private void fireEventTriggers(CardData card, boolean isP1, String triggerType) {
		for (AutoAbility fa : card.autoAbilities())
			if (fa.trigger().equals(triggerType))
				executeAutoAbility(fa, card, isP1);
	}

	/**
	 * Resolves a triggered auto ability.  When the ability is optional ({@code youMay} or
	 * {@code opponentMay}), P1 is shown a Decline / OK dialog; the AI always accepts.
	 *
	 * <p>For {@code opponentMay} effects the execution context is flipped to the opponent's
	 * perspective so that "play from hand" and similar effects target the correct player.
	 */
	private void executeAutoAbility(AutoAbility fa, CardData source, boolean isP1) {
		// Damage threshold: skip if the controlling player doesn't have enough damage counters
		if (fa.damageThreshold() > 0) {
			int dmg = isP1 ? gameState.getP1DamageZone().size() : gameState.getP2DamageZone().size();
			if (dmg < fa.damageThreshold()) return;
		}

		// "only during your turn" — skip when the ability owner is not the active player
		if (fa.yourTurnOnly() && !isP1) return;

		// cast payment element condition: "if the cost to cast X was paid with CP of N or more different Elements"
		if (fa.castPaymentMinElements() > 0 && lastCastPaymentDistinctElements < fa.castPaymentMinElements()) {
			logEntry("[AutoAbility] " + source.name() + " — cast payment condition not met ("
					+ lastCastPaymentDistinctElements + " distinct element(s), needed "
					+ fa.castPaymentMinElements() + ")");
			return;
		}

		// "due to your cast" — only fires when the card entered the field by being cast from hand
		if (fa.castOnly() && !lastCardWasCast) return;

		// "due to Warp" — only fires when the card entered the field via Warp resolution
		if (fa.warpOnly() && !lastCardWarpedIn) return;

		// "only if [card] is removed from the game" — skip if that card is not in the RFP zone
		if (!fa.rfpConditionCard().isEmpty()) {
			String cond = fa.rfpConditionCard();
			boolean inRfp = gameState.getP1WarpZone().stream()
					.anyMatch(e -> e.card.name().equalsIgnoreCase(cond))
					|| gameState.getP1PermanentRfp().stream()
					.anyMatch(c -> c.name().equalsIgnoreCase(cond));
			if (!inRfp) return;
		}

		// "only once per turn" — skip if already fired this turn
		if (fa.oncePerTurn() && usedOncePerTurnAbilities
				.getOrDefault(source, java.util.Set.of()).contains(fa.effectText())) {
			logEntry("[AutoAbility] " + source.name() + " — already used this turn, skipping");
			return;
		}

		// opponentMay effects run from the opponent's context
		boolean effectIsP1 = fa.opponentMay() ? !isP1 : isP1;

		// Detect "remove N [Name] Counter(s) from [CardName]. When you do so, [effect]"
		java.util.regex.Matcher ctrM = FA_REMOVE_COUNTER_WHEN_DO_SO.matcher(fa.effectText());
		if (ctrM.find()) {
			executeCounterRemovalWhenDoSoAutoAbility(fa, source, isP1, effectIsP1, ctrM);
			return;
		}

		// Detect "pay 《X/N》. When you do so, [effect]" — requires a payment dialog before resolving.
		java.util.regex.Matcher payM = FA_PAY_WHEN_DO_SO.matcher(fa.effectText());
		if (payM.find()) {
			executePayWhenDoSoAutoAbility(fa, source, isP1, effectIsP1, payM);
			return;
		}

		// Detect "remove N [type] [without 《Keyword》] you control from the game. When you do so, [effect]"
		java.util.regex.Matcher rfM = FA_REMOVE_FIELD_WHEN_DO_SO.matcher(fa.effectText());
		if (rfM.find()) {
			executeRemoveFieldWhenDoSoAutoAbility(fa, source, isP1, effectIsP1, rfM);
			return;
		}

		// Detect "put N [Job/CardName/type] you control into the Break Zone. When you do so, [effect]"
		java.util.regex.Matcher bzM = FA_PUT_INTO_BZ_WHEN_DO_SO.matcher(fa.effectText());
		if (bzM.find()) {
			executePutIntoBzWhenDoSoAutoAbility(fa, source, isP1, effectIsP1, bzM);
			return;
		}

		// Detect "select [up to] N of the M following actions. "..." "..."..."
		java.util.regex.Matcher selM = FA_SELECT_FOLLOWING_ACTIONS.matcher(fa.effectText());
		if (selM.find()) {
			executeSelectFollowingActionsAutoAbility(fa, source, isP1, effectIsP1, selM);
			return;
		}

		Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
		if (effect == null) {
			logEntry("[AutoAbility] Unrecognized effect: " + fa.effectText());
			return;
		}

		// P1 (human) decides when: they control the card and "you may", or
		// they are the opponent of P2's "your opponent may" ability.
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"OK", "Decline"});
			if (choice != 0) {
				logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			logEntry("[AutoAbility] [AI] auto-accepts optional ability");
		}

		if (fa.oncePerTurn())
			usedOncePerTurnAbilities.computeIfAbsent(source, k -> new HashSet<>()).add(fa.effectText());

		logEntry("[AutoAbility] " + source.name() + " — " + fa.effectText());
		effect.accept(buildGameContext(effectIsP1));
	}

	private void executeCounterRemovalWhenDoSoAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, java.util.regex.Matcher m) {
		int    n           = Integer.parseInt(m.group("n"));
		String counterName = m.group("counterName").trim();
		String subEffect   = m.group("sub").trim();

		// Require enough counters to be present; skip silently if not.
		if (gameState.getCounters(source, counterName) < n) {
			logEntry("[AutoAbility] " + source.name() + " — not enough " + counterName
					+ " Counters (need " + n + ", have " + gameState.getCounters(source, counterName) + ")");
			return;
		}

		// youMay / AI decision
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"OK", "Decline"});
			if (choice != 0) {
				logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			logEntry("[AutoAbility] [AI] auto-accepts optional ability");
		}

		// Remove the counter(s)
		int removed = gameState.removeCounters(source, counterName, n);
		logEntry("[AutoAbility] " + source.name() + " — removed " + removed + " " + counterName
				+ " Counter(s)  [remaining: " + gameState.getCounters(source, counterName) + "]");

		// Execute the sub-effect
		Consumer<GameContext> effect = ActionResolver.parse(subEffect, source);
		if (effect == null) {
			logEntry("[AutoAbility] Unrecognized counter-removal sub-effect: " + subEffect);
			return;
		}
		logEntry("[AutoAbility] " + source.name() + " — when you do so: " + subEffect);
		effect.accept(buildGameContext(effectIsP1));
	}

	private void executeRemoveFieldWhenDoSoAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, java.util.regex.Matcher m) {
		int     count          = Integer.parseInt(m.group("count"));
		String  targetsRaw     = m.group("targets").toLowerCase(java.util.Locale.ROOT);
		String  rawExcludeKw   = m.group("excludekw");
		boolean withoutMulticard = "Multicard".equalsIgnoreCase(rawExcludeKw != null ? rawExcludeKw.trim() : null);
		String  control        = m.group("control").toLowerCase(java.util.Locale.ROOT);
		boolean opponentOnly   = !control.contains("you control");
		boolean selfOnly       = !opponentOnly;
		boolean inclForwards   = targetsRaw.contains("forward") || targetsRaw.contains("character");
		boolean inclBackups    = targetsRaw.contains("backup")  || targetsRaw.contains("character");
		boolean inclMonsters   = targetsRaw.contains("monster") || targetsRaw.contains("character");
		String  subEffect      = m.group("sub").trim();

		// youMay / AI decision
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"OK", "Decline"});
			if (choice != 0) {
				logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			logEntry("[AutoAbility] [AI] auto-accepts optional ability");
		}

		// Select the card(s) to remove from the field
		GameContext ctx = buildGameContext(effectIsP1);
		java.util.List<ForwardTarget> targets = ctx.selectCharacters(count, false,
				opponentOnly, selfOnly, null, null, -1, null, -1, null,
				inclForwards, inclBackups, inclMonsters, null, null, null, null, false, null, withoutMulticard);
		if (targets.isEmpty()) {
			logEntry("[AutoAbility] " + source.name() + " — no valid target for field removal");
			return;
		}

		// Rebuild ctx after selectCharacters in case field indices shifted; remove targets
		GameContext ctx2 = buildGameContext(effectIsP1);
		targets.forEach(t -> ctx2.removeTargetFromGame(t));

		// Parse and execute the sub-effect ("Its auto-ability will not trigger." is handled inside tryParsePlayFromHand)
		Consumer<GameContext> effect = ActionResolver.parse(subEffect, source);
		if (effect == null) {
			logEntry("[AutoAbility] Unrecognized sub-effect: " + subEffect);
			return;
		}
		logEntry("[AutoAbility] " + source.name() + " — when you do so: " + subEffect);
		effect.accept(buildGameContext(effectIsP1));
	}

	private void executePutIntoBzWhenDoSoAutoAbility(AutoAbility fa, CardData source,
			boolean isP1, boolean effectIsP1, java.util.regex.Matcher m) {
		int    count         = Integer.parseInt(m.group("count"));
		String jobRaw        = m.group("job");
		String cardNameRaw   = m.group("cardname");
		String typeRaw       = m.group("type");
		String subEffect     = m.group("sub").trim();

		String jobFilter      = jobRaw      != null ? jobRaw.trim()      : null;
		String cardNameFilter = cardNameRaw != null ? cardNameRaw.trim() : null;
		boolean inclForwards, inclBackups, inclMonsters;
		if (jobFilter != null || cardNameFilter != null) {
			inclForwards = inclBackups = inclMonsters = true;
		} else if (typeRaw != null) {
			String tl = typeRaw.toLowerCase(java.util.Locale.ROOT);
			inclForwards = tl.contains("forward") || tl.contains("character");
			inclBackups  = tl.contains("backup")  || tl.contains("character");
			inclMonsters = tl.contains("monster") || tl.contains("character");
		} else {
			inclForwards = inclBackups = inclMonsters = true;
		}

		// youMay / AI decision
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"OK", "Decline"});
			if (choice != 0) {
				logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			logEntry("[AutoAbility] [AI] auto-accepts optional ability");
		}

		// Select the card(s) to put into the Break Zone
		GameContext ctx = buildGameContext(effectIsP1);
		java.util.List<ForwardTarget> targets = ctx.selectCharacters(count, false,
				false, true, null, null, -1, null, -1, null,
				inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, null, null, false, null, false);
		if (targets.isEmpty()) {
			logEntry("[AutoAbility] " + source.name() + " — no eligible target to put into Break Zone, sub-effect skipped");
			return;
		}

		// Rebuild ctx after selectCharacters in case field indices shifted; break the targets
		GameContext ctx2 = buildGameContext(effectIsP1);
		targets.forEach(t -> ctx2.forceTargetToBreakZone(t));

		// Parse and execute the sub-effect
		Consumer<GameContext> effect = ActionResolver.parse(subEffect, source);
		if (effect == null) {
			logEntry("[AutoAbility] Unrecognized sub-effect: " + subEffect);
			return;
		}
		logEntry("[AutoAbility] " + source.name() + " — when you do so: " + subEffect);
		effect.accept(buildGameContext(effectIsP1));
	}

	private void executePayWhenDoSoAutoAbility(AutoAbility fa, CardData source, boolean isP1,
			boolean effectIsP1, java.util.regex.Matcher payM) {
		String costToken = payM.group(1).trim();
		String subEffect = payM.group(2).trim().replaceAll("[.!,]+$", "");

		boolean isXCost = costToken.equalsIgnoreCase("X");
		boolean isElementCost = !isXCost && ELEMENT_NAMES.stream()
				.anyMatch(e -> costToken.toLowerCase(java.util.Locale.ROOT).contains(e));
		int fixedCost;
		if (!isXCost) {
			if (isElementCost) {
				fixedCost = 1;
			} else {
				try { fixedCost = Integer.parseInt(costToken); }
				catch (NumberFormatException e) {
					// Non-numeric, non-X cost token (e.g. 《C》 for crystal) — resolve normally.
					Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
					if (effect != null) { logEntry("[AutoAbility] " + source.name() + " — " + fa.effectText()); effect.accept(buildGameContext(effectIsP1)); }
					else logEntry("[AutoAbility] Unrecognized effect: " + fa.effectText());
					return;
				}
			}
		} else { fixedCost = 0; }

		java.util.regex.Matcher maxM = FA_MAX_X.matcher(fa.effectText());
		int maxCp = isXCost ? (maxM.find() ? Integer.parseInt(maxM.group(1)) : Integer.MAX_VALUE) : fixedCost;

		// P1 gets a confirm dialog; AI auto-accepts.
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = showEffectOptionDialog(source.name() + " — " + prompt,
					"Auto Ability", new Object[]{"OK", "Decline"});
			if (choice != 0) {
				logEntry("[AutoAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			// Decline if the effect targets Forwards but the opponent has none to target.
			boolean effectNeedsForward = subEffect.toLowerCase(java.util.Locale.ROOT).contains("forward");
			if (effectNeedsForward && p1ForwardCards.isEmpty()) {
				logEntry("[AutoAbility] [AI] declines optional ability — no opponent Forwards to target");
				return;
			}
			logEntry("[AutoAbility] [AI] auto-accepts optional ability");
		}

		if (!isP1) {
			// AI pays the maximum it can (simplified — no backup state update for AI).
			int paid = maxCp == Integer.MAX_VALUE ? 1 : maxCp;
			applyPayWhenDoSoEffect(subEffect, source, paid, effectIsP1);
			return;
		}

		String finalSubEffect = subEffect;
		showAutoAbilityPaymentDialog(source.name(), fixedCost, maxCp, isP1,
				paid -> applyPayWhenDoSoEffect(finalSubEffect, source, paid, effectIsP1));
	}

	private void applyPayWhenDoSoEffect(String subEffect, CardData source, int xValue, boolean effectIsP1) {
		GameContext ctx = buildGameContext(effectIsP1);
		// "Gain 《C》 for each CP paid as X" must be resolved with the known xValue directly —
		// the generic parse chain would see xValue=0 for this pattern and give 0 crystals.
		if (ActionResolver.isGainCrystalPerX(subEffect)) {
			ctx.logEntry("Effect: Gain " + xValue + " Crystal(s) (for each CP paid as X)");
			ctx.gainCrystal(xValue);
			return;
		}
		Consumer<GameContext> effect = ActionResolver.parse(subEffect, source, xValue);
		if (effect == null) {
			logEntry("[AutoAbility] Unrecognized 'when you do so' effect: " + subEffect);
			return;
		}
		logEntry("[AutoAbility] " + source.name() + " — when you do so: " + subEffect + " (X=" + xValue + ")");
		effect.accept(ctx);
	}

	// ─── "Select N of M following actions" auto-ability ─────────────────────────

	private void executeSelectFollowingActionsAutoAbility(
			AutoAbility fa, CardData source, boolean isP1, boolean effectIsP1,
			java.util.regex.Matcher m) {

		// Optional "if condition" prefix
		String condition = m.group("condition");
		if (condition != null && !checkAutoAbilityCondition(condition.trim(), isP1)) {
			logEntry("[AutoAbility] " + source.name() + " — condition not met: " + condition);
			return;
		}

		boolean upTo       = m.group("upTo") != null;
		int     selectCount = Integer.parseInt(m.group("select"));
		int     totalCount  = Integer.parseInt(m.group("total"));

		// Extract the quoted action strings
		java.util.regex.Matcher qm = FA_QUOTED_ACTION.matcher(m.group("actions"));
		List<String> actions = new ArrayList<>();
		while (qm.find()) actions.add(qm.group(1).trim());

		if (actions.isEmpty()) {
			logEntry("[AutoAbility] " + source.name() + " — no actions found in select effect");
			return;
		}

		// youMay / opponentMay decline dialog (the select dialog itself is the interaction,
		// but we still honour an explicit "you may" decline option)
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = "Select " + (upTo ? "up to " : "") + selectCount + " of "
					+ totalCount + " actions for " + source.name() + "?";
			int choice = showEffectOptionDialog(prompt, "Auto Ability",
					new Object[]{"Choose Actions", "Decline"});
			if (choice != 0) {
				logEntry("[AutoAbility] " + source.name() + " — optional select declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			logEntry("[AutoAbility] [AI] auto-accepts select ability");
		}

		if (fa.oncePerTurn())
			usedOncePerTurnAbilities.computeIfAbsent(source, k -> new HashSet<>())
					.add(fa.effectText());

		// P1 picks interactively; AI always picks the first N actions
		List<String> chosen;
		if (isP1) {
			chosen = showSelectActionsDialog(source, actions, selectCount, upTo);
		} else {
			int take = Math.min(selectCount, actions.size());
			chosen = new ArrayList<>(actions.subList(0, take));
			logEntry("[AutoAbility] [AI] selected first " + take + " action(s)");
		}

		if (chosen == null || chosen.isEmpty()) {
			logEntry("[AutoAbility] " + source.name() + " — no actions chosen");
			return;
		}

		for (String actionText : chosen) {
			Consumer<GameContext> effect = ActionResolver.parse(actionText, source);
			if (effect == null) {
				logEntry("[AutoAbility] " + source.name() + " — unrecognized action: " + actionText);
			} else {
				logEntry("[AutoAbility] " + source.name() + " — " + actionText);
				effect.accept(buildGameContext(effectIsP1));
			}
		}
	}

	/**
	 * Shows a modal dialog for P1 to choose actions from a "select N of M" list.
	 * Uses radio buttons when exactly 1 must be chosen, checkboxes otherwise.
	 * Returns the chosen action texts, or an empty list if the dialog is dismissed.
	 */
	private List<String> showSelectActionsDialog(
			CardData source, List<String> actions, int selectCount, boolean upTo) {

		int  n             = actions.size();
		boolean singlePick = selectCount == 1 && !upTo;
		String title = source.name() + " — Select "
				+ (upTo ? "up to " : "") + selectCount + " action" + (selectCount != 1 || upTo ? "s" : "");

		JDialog dlg = new JDialog(frame, title, true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		List<String> result = new ArrayList<>();

		JPanel choicesPanel = new JPanel(new GridLayout(0, 1, 0, 6));
		choicesPanel.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

		if (singlePick) {
			// ── Radio buttons — exactly one action ──
			javax.swing.ButtonGroup group = new javax.swing.ButtonGroup();
			javax.swing.JRadioButton[] radios = new javax.swing.JRadioButton[n];
			for (int i = 0; i < n; i++) {
				javax.swing.JRadioButton rb = new javax.swing.JRadioButton(
						"<html><body style='width:340px'>" + actions.get(i) + "</body></html>");
				rb.setFont(FontLoader.loadPixelNESFont(10));
				group.add(rb);
				radios[i] = rb;
				choicesPanel.add(rb);
			}
			radios[0].setSelected(true);
			confirmBtn.addActionListener(ae -> {
				for (int i = 0; i < radios.length; i++)
					if (radios[i].isSelected()) { result.add(actions.get(i)); break; }
				dlg.dispose();
			});
		} else {
			// ── Checkboxes — up to N, or exactly N ──
			javax.swing.JCheckBox[] checks = new javax.swing.JCheckBox[n];
			JLabel countLbl = new JLabel(
					"Selected: 0 / " + selectCount + (upTo ? " (up to)" : ""),
					SwingConstants.CENTER);
			countLbl.setFont(FontLoader.loadPixelNESFont(10));

			for (int i = 0; i < n; i++) {
				javax.swing.JCheckBox cb = new javax.swing.JCheckBox(
						"<html><body style='width:340px'>" + actions.get(i) + "</body></html>");
				cb.setFont(FontLoader.loadPixelNESFont(10));
				checks[i] = cb;
				cb.addItemListener(ie -> {
					int sel = 0;
					for (javax.swing.JCheckBox c : checks) if (c.isSelected()) sel++;
					countLbl.setText("Selected: " + sel + " / " + selectCount + (upTo ? " (up to)" : ""));
					// For exact selection: disable unchecked boxes once limit is reached
					if (!upTo && sel >= selectCount) {
						for (javax.swing.JCheckBox c : checks) if (!c.isSelected()) c.setEnabled(false);
					} else {
						for (javax.swing.JCheckBox c : checks) c.setEnabled(true);
					}
					confirmBtn.setEnabled(upTo || sel == selectCount);
				});
				choicesPanel.add(cb);
			}
			confirmBtn.setEnabled(upTo); // "up to" can confirm with 0; exact needs N selected
			confirmBtn.addActionListener(ae -> {
				for (int i = 0; i < checks.length; i++)
					if (checks[i].isSelected()) result.add(actions.get(i));
				dlg.dispose();
			});

			JPanel countRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 2));
			countRow.add(countLbl);
			choicesPanel.add(countRow);
		}

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		south.add(confirmBtn);

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(choicesPanel, BorderLayout.CENTER);
		dlg.getContentPane().add(south,        BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
		return result;
	}

	/**
	 * Evaluates a simple auto-ability precondition such as
	 * "you control a Job AVALANCHE Operative Forward".
	 * Returns {@code true} when the condition is satisfied, or when the condition
	 * text is not recognised (fail-open to avoid silently blocking abilities).
	 */
	private boolean checkAutoAbilityCondition(String condition, boolean isP1) {
		String lo = condition.toLowerCase(java.util.Locale.ROOT).trim();
		if (lo.startsWith("you control a") || lo.startsWith("you control an")) {
			String spec = lo.replaceFirst("^you\\s+control\\s+an?\\s+", "").trim();
			return controlsMatchingCard(spec, isP1);
		}
		logEntry("[AutoAbility] Unrecognized condition (defaulting to true): " + condition);
		return true;
	}

	/**
	 * Returns {@code true} if the given player has at least one card on the field that matches
	 * a description such as "forward", "job avalanche operative forward", "ice backup", etc.
	 */
	private boolean controlsMatchingCard(String spec, boolean isP1) {
		// Collect all field cards for this player
		List<CardData> field = new ArrayList<>();
		field.addAll(isP1 ? p1ForwardCards : p2ForwardCards);
		for (CardData c : (isP1 ? p1BackupCards : p2BackupCards)) if (c != null) field.add(c);
		field.addAll(isP1 ? p1MonsterCards : p2MonsterCards);

		// Determine target type restriction
		String specLo = spec.toLowerCase(java.util.Locale.ROOT);
		String requiredType = null;
		if      (specLo.endsWith("forward"))   requiredType = "Forward";
		else if (specLo.endsWith("backup"))    requiredType = "Backup";
		else if (specLo.endsWith("monster"))   requiredType = "Monster";
		else if (specLo.endsWith("character")) requiredType = null; // any type matches

		// Strip the type suffix to isolate job / element qualifiers
		String qualifiers = specLo
				.replaceAll("(?i)\\s+(forward|backup|monster|character)$", "").trim();
		// Strip leading "job " keyword if present (keep the actual job name)
		String jobFilter = qualifiers.startsWith("job ")
				? qualifiers.replaceFirst("^job\\s+", "").trim()
				: (qualifiers.isEmpty() ? null : qualifiers);

		for (CardData c : field) {
			if (c == null) continue;
			if (requiredType != null && !c.type().equalsIgnoreCase(requiredType)
					&& !(requiredType.equalsIgnoreCase("Monster") && c.alsoCountsAsMonster())) continue;
			if (jobFilter != null && !c.job().toLowerCase(java.util.Locale.ROOT).contains(jobFilter)) continue;
			return true;
		}
		return false;
	}

	/**
	 * Payment dialog for a auto ability that requires CP payment.
	 * Shows backup cards (1 CP each) and hand cards to discard (2 CP each).
	 * Calls {@code onConfirm} with total CP paid after dulling backups / discarding cards.
	 */
	private void showAutoAbilityPaymentDialog(String cardName, int minCp, int maxCp,
			boolean isP1, java.util.function.IntConsumer onConfirm) {
		CardData[]     bkpCards  = playerBackupCards(isP1);
		CardState[]    bkpStates = playerBackupStates(isP1);
		String[]       bkpUrls  = playerBackupUrls(isP1);
		List<CardData> hand      = playerHand(isP1);

		String title = (maxCp == minCp)
				? cardName + " — Pay " + minCp + " CP"
				: cardName + " — Pay up to " + (maxCp == Integer.MAX_VALUE ? "any" : maxCp) + " CP";
		JDialog dlg = new JDialog(frame, title, true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		List<Integer> selectedBackups  = new ArrayList<>();
		List<Integer> selectedDiscards = new ArrayList<>();

		JLabel   cpLabel    = new JLabel();
		cpLabel.setFont(FontLoader.loadPixelNESFont(11));
		cpLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

		List<JLabel>  backupLbls  = new ArrayList<>();
		List<Integer> backupSlots = new ArrayList<>();
		List<JLabel>  discardLbls = new ArrayList<>();
		List<Integer> discardIdxs = new ArrayList<>();

		boolean[] canAddBackup  = {true};
		boolean[] canAddDiscard = {true};

		Runnable updateAll = () -> {
			int total  = selectedBackups.size() + selectedDiscards.size() * 2;
			if (minCp == maxCp) {
				// Fixed cost: mirrors showActionAbilityPaymentDialog overpayment rules.
				// Allow up to 1 extra CP if cost is odd (a 2-CP discard can't be split).
				int maxAllowed = maxCp + (maxCp % 2);
				canAddBackup[0]  = total < maxCp;
				canAddDiscard[0] = total < maxCp && total + 2 <= maxAllowed;
			} else {
				// Variable X cost: strict cap at maxCp.
				boolean atMax = maxCp != Integer.MAX_VALUE && total >= maxCp;
				canAddBackup[0]  = !atMax;
				canAddDiscard[0] = maxCp == Integer.MAX_VALUE || total + 2 <= maxCp;
			}
			confirmBtn.setEnabled(total >= minCp);

			String cap = maxCp == Integer.MAX_VALUE ? "∞" : String.valueOf(maxCp);
			cpLabel.setText("CP paid: " + total + " / " + cap
					+ (minCp > 0 ? "  (min " + minCp + ")" : ""));

			for (int i = 0; i < backupLbls.size(); i++) {
				JLabel  lbl = backupLbls.get(i);
				boolean sel = selectedBackups.contains(backupSlots.get(i));
				lbl.setBorder(sel ? createCardGlowBorder(Color.YELLOW) : BorderFactory.createLineBorder(canAddBackup[0] ? Color.GRAY : new Color(80, 80, 80), 1));
				lbl.setBackground(sel || canAddBackup[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(sel || canAddBackup[0]
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			for (int i = 0; i < discardLbls.size(); i++) {
				JLabel  lbl = discardLbls.get(i);
				boolean sel = selectedDiscards.contains(discardIdxs.get(i));
				lbl.setBorder(sel ? createCardGlowBorder(Color.YELLOW) : BorderFactory.createLineBorder(canAddDiscard[0] ? Color.GRAY : new Color(80, 80, 80), 1));
				lbl.setBackground(sel || canAddDiscard[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(sel || canAddDiscard[0]
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
		};
		updateAll.run();

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

		List<Integer> eligibleBackupSlots = new ArrayList<>();
		for (int i = 0; i < bkpCards.length; i++)
			if (bkpCards[i] != null && bkpStates[i] == CardState.ACTIVE) eligibleBackupSlots.add(i);

		if (!eligibleBackupSlots.isEmpty()) {
			JLabel hdr = new JLabel("Backups — dull for 1 CP each:");
			hdr.setFont(FontLoader.loadPixelNESFont(9)); hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); bp.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (int slot : eligibleBackupSlots) {
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H)); lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true); lbl.setBackground(Color.DARK_GRAY); lbl.setForeground(Color.WHITE);
				lbl.setFont(FontLoader.loadPixelNESFont(10)); lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
				lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				final String url = bkpUrls[slot];
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent ev) {
						if (!selectedBackups.remove(Integer.valueOf(slot)) && canAddBackup[0]) selectedBackups.add(slot);
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) showZoomAt(url); }
					@Override public void mouseExited(MouseEvent ev)  { hideZoom(); }
				});
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(url);
						return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
					}
					@Override protected void done() {
						try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
						catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();
				backupLbls.add(lbl); backupSlots.add(slot); bp.add(lbl);
			}
			center.add(hdr); center.add(bp);
		}

		if (!hand.isEmpty()) {
			JLabel discHdr = new JLabel("Hand — discard for 2 CP each:");
			discHdr.setFont(FontLoader.loadPixelNESFont(9)); discHdr.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel dp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); dp.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (int i = 0; i < hand.size(); i++) {
				final int hi = i; CardData hc = hand.get(i); boolean payable = !hc.isLightOrDark();
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H)); lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true); lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setForeground(Color.WHITE); lbl.setFont(FontLoader.loadPixelNESFont(10));
				lbl.setBorder(BorderFactory.createLineBorder(payable ? Color.GRAY : new Color(80, 80, 80), 1));
				lbl.setCursor(payable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
				final String imgUrl = hc.imageUrl();
				if (payable) {
					lbl.addMouseListener(new MouseAdapter() {
						@Override public void mousePressed(MouseEvent ev) {
							if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0]) selectedDiscards.add(hi);
							updateAll.run();
						}
						@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) showZoomAt(imgUrl); }
						@Override public void mouseExited(MouseEvent ev)  { hideZoom(); }
					});
					discardLbls.add(lbl); discardIdxs.add(hi);
				} else {
					lbl.addMouseListener(new MouseAdapter() {
						@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) showZoomAt(imgUrl); }
						@Override public void mouseExited(MouseEvent ev)  { hideZoom(); }
					});
				}
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(imgUrl);
						return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
					}
					@Override protected void done() {
						try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
						catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();
				dp.add(lbl);
			}
			center.add(discHdr); center.add(dp);
		}

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
		cancelBtn.addActionListener(ev -> {
			logEntry("[AutoAbility] " + cardName + " — payment cancelled");
			dlg.dispose();
		});
		confirmBtn.addActionListener(ev -> {
			dlg.dispose();
			for (int slot : selectedBackups) {
				bkpStates[slot] = CardState.DULL;
				playerDullBackupSlot(isP1, slot);
			}
			List<Integer> sortedDiscards = new ArrayList<>(selectedDiscards);
			sortedDiscards.sort(Collections.reverseOrder());
			for (int di : sortedDiscards) playerBreakFromHand(isP1, di);
			int paid = selectedBackups.size() + selectedDiscards.size() * 2;
			logEntry("[AutoAbility] " + cardName + " — paid " + paid + " CP");
			refreshP1HandLabel();
			refreshP1BreakLabel();
			onConfirm.accept(paid);
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonPanel.add(confirmBtn); buttonPanel.add(cancelBtn);

		JPanel topPanel = new JPanel(new BorderLayout(0, 4));
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		topPanel.add(cpLabel, BorderLayout.CENTER);

		JPanel mainPanel = new JPanel(new BorderLayout(0, 4));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
		mainPanel.add(new JScrollPane(center), BorderLayout.CENTER);
		mainPanel.add(buttonPanel,             BorderLayout.SOUTH);

		dlg.getContentPane().setLayout(new BorderLayout());
		dlg.getContentPane().add(topPanel,  BorderLayout.NORTH);
		dlg.getContentPane().add(mainPanel, BorderLayout.CENTER);
		dlg.pack(); dlg.setLocationRelativeTo(frame); dlg.setVisible(true);
	}

	private boolean canActivateHandAbility(ActionAbility ability, CardData source, boolean isP1) {
		if (ability.yourTurnOnly() && !isP1) return false;
		if (ability.oncePerTurn()
				&& usedOncePerTurnAbilities.getOrDefault(source, java.util.Set.of()).contains(ability.effectText()))
			return false;
		GameState.GamePhase p = gameState.getCurrentPhase();
		if (p != GameState.GamePhase.MAIN_1 && p != GameState.GamePhase.MAIN_2) return false;
		if (ability.crystalCost() > 0 && playerCrystals(isP1) < ability.crystalCost()) return false;
		for (BreakZoneCost bz : ability.breakZoneCosts())
			if (!bzCostSatisfied(bz, isP1)) return false;
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			if (!rfgCostSatisfied(rfg, isP1)) return false;
		for (ReturnToHandCost rth : ability.returnToHandCosts())
			if (!rfthCostSatisfied(rth, isP1)) return false;
		for (CounterCost cc : ability.counterCosts())
			if (!counterCostSatisfied(cc, source)) return false;
		return canAffordAbilityCost(ability, isP1);
	}

	/**
	 * Builds the BZ-target list for ability payment by finding the source card's
	 * current field position.  The BZ cost is always "put itself into the Break Zone",
	 * so no player selection is needed — one entry is added per cost item.
	 */
	private List<ForwardTarget> autoResolveBzTargets(CardData source, List<BreakZoneCost> bzCosts, boolean isP1) {
		if (bzCosts.isEmpty()) return List.of();
		ForwardTarget self = findSourceOnField(source, isP1);
		if (self == null) return List.of();
		List<ForwardTarget> result = new ArrayList<>();
		for (int i = 0; i < bzCosts.size(); i++) result.add(self);
		return result;
	}

	/** Finds the field position of {@code source} by object identity, or {@code null} if not found. */
	private ForwardTarget findSourceOnField(CardData source, boolean isP1) {
		if (isP1) {
			for (int i = 0; i < p1ForwardCards.size(); i++) {
				CardData top = p1ForwardPrimedTop.get(i);
				if (top == source || p1ForwardCards.get(i) == source)
					return new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD);
			}
			for (int i = 0; i < p1BackupCards.length; i++) {
				if (p1BackupCards[i] == source)
					return new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP);
			}
			for (int i = 0; i < p1MonsterCards.size(); i++) {
				if (p1MonsterCards.get(i) == source)
					return new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER);
			}
		} else {
			for (int i = 0; i < p2ForwardCards.size(); i++) {
				if (p2ForwardCards.get(i) == source)
					return new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD);
			}
			for (int i = 0; i < p2BackupCards.length; i++) {
				if (p2BackupCards[i] == source)
					return new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP);
			}
			for (int i = 0; i < p2MonsterCards.size(); i++) {
				if (p2MonsterCards.get(i) == source)
					return new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER);
			}
		}
		return null;
	}

	private boolean bzCostSatisfied(BreakZoneCost bz, boolean isP1) {
		return eligibleBzFieldCards(bz, isP1).size() >= bz.count();
	}

	/** True when {@code source} (the activating card) has enough counters to pay {@code cc}. */
	private boolean counterCostSatisfied(CounterCost cc, CardData source) {
		if (!source.name().equalsIgnoreCase(cc.cardName())) return false;
		return gameState.getCounters(source, cc.counterName()) >= cc.count();
	}

	private boolean dullForwardCostSatisfied(DullForwardCost dfc, boolean isP1) {
		List<CardData>  fwds   = isP1 ? p1ForwardCards : p2ForwardCards;
		List<CardState> states = isP1 ? p1ForwardStates : p2ForwardStates;
		for (int i = 0; i < fwds.size(); i++) {
			if (states.get(i) != CardState.ACTIVE) continue;
			if (!dfc.element().isEmpty() && !fwds.get(i).containsElement(dfc.element())) continue;
			return true;
		}
		return false;
	}

	private List<ForwardTarget> eligibleBzFieldCards(BreakZoneCost bz, boolean isP1) {
		List<ForwardTarget> result = new ArrayList<>();
		List<CardData> fwds = playerForwardCards(isP1);
		List<CardData> mons = playerMonsterCards(isP1);
		CardData[]     bkps = playerBackupCards(isP1);
		if (!bz.name().isEmpty()) {
			for (int i = 0; i < fwds.size(); i++)
				if (meetsCardNameFilter(fwds.get(i), bz.name()))
					result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
			for (int i = 0; i < mons.size(); i++)
				if (meetsCardNameFilter(mons.get(i), bz.name()))
					result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
			for (int i = 0; i < bkps.length; i++)
				if (bkps[i] != null && meetsCardNameFilter(bkps[i], bz.name()))
					result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
			return result;
		}
		String typeDesc = bz.cardType();
		String last     = typeDesc.isEmpty() ? "" : typeDesc.substring(typeDesc.lastIndexOf(' ') + 1);
		String elemFilt = typeDesc.contains(" ") ? typeDesc.substring(0, typeDesc.lastIndexOf(' ')).trim() : null;
		if (last.equalsIgnoreCase("Forward")) {
			for (int i = 0; i < fwds.size(); i++) {
				if (elemFilt != null && !fwds.get(i).containsElement(elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
			}
		} else if (last.equalsIgnoreCase("Backup")) {
			for (int i = 0; i < bkps.length; i++) {
				if (bkps[i] == null) continue;
				if (elemFilt != null && !bkps[i].containsElement(elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
			}
		} else if (last.equalsIgnoreCase("Monster")) {
			for (int i = 0; i < mons.size(); i++) {
				if (elemFilt != null && !mons.get(i).containsElement(elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
			}
			for (int i = 0; i < fwds.size(); i++) {
				if (!fwds.get(i).alsoCountsAsMonster()) continue;
				if (elemFilt != null && !fwds.get(i).containsElement(elemFilt)) continue;
				result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
			}
		}
		return result;
	}

	private boolean rfgCostSatisfied(RemoveFromGameCost rfg, boolean isP1) {
		if (rfg.count() == -1) return true; // "all" — always payable
		return switch (rfg.zone()) {
			case "DECK"       -> (isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck()).size() >= rfg.count();
			case "HAND"       -> eligibleRfgHandIndices(rfg, isP1).size() >= rfg.count();
			case "BREAK_ZONE" -> eligibleRfgBzIndices(rfg, isP1).size() >= rfg.count();
			default           -> eligibleRfgFieldTargets(rfg, isP1).size() >= rfg.count();
		};
	}

	private List<Integer> eligibleRfgHandIndices(RemoveFromGameCost rfg, boolean isP1) {
		List<CardData> hand = playerHand(isP1);
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			CardData c = hand.get(i);
			if (rfg.cardName() != null && !meetsCardNameFilter(c, rfg.cardName())) continue;
			if (rfg.element()  != null && !c.containsElement(rfg.element()))       continue;
			if (rfg.cardType() != null && !matchesDiscardType(c, rfg.cardType()))  continue;
			result.add(i);
		}
		return result;
	}

	private List<Integer> eligibleRfgBzIndices(RemoveFromGameCost rfg, boolean isP1) {
		List<CardData> bz = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < bz.size(); i++) {
			CardData c = bz.get(i);
			if (rfg.cardName() != null && !meetsCardNameFilter(c, rfg.cardName())) continue;
			if (rfg.element()  != null && !c.containsElement(rfg.element()))          continue;
			if (rfg.cardType() != null && !matchesDiscardType(c, rfg.cardType()))     continue;
			result.add(i);
		}
		return result;
	}

	private List<ForwardTarget> eligibleRfgFieldTargets(RemoveFromGameCost rfg, boolean isP1) {
		List<ForwardTarget> result = new ArrayList<>();
		List<CardData> fwds = playerForwardCards(isP1);
		List<CardData> mons = playerMonsterCards(isP1);
		CardData[]     bkps = playerBackupCards(isP1);
		for (int i = 0; i < fwds.size(); i++) {
			CardData c = fwds.get(i);
			if (!matchesRfgFieldFilter(c, rfg)) continue;
			result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
		}
		for (int i = 0; i < bkps.length; i++) {
			if (bkps[i] == null) continue;
			if (!matchesRfgFieldFilter(bkps[i], rfg)) continue;
			result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
		}
		for (int i = 0; i < mons.size(); i++) {
			if (!matchesRfgFieldFilter(mons.get(i), rfg)) continue;
			result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
		}
		return result;
	}

	private boolean matchesRfgFieldFilter(CardData c, RemoveFromGameCost rfg) {
		if (rfg.cardName()    != null && !meetsCardNameFilter(c, rfg.cardName()))     return false;
		if (rfg.element()     != null && !c.containsElement(rfg.element()))           return false;
		if (rfg.cardType()    != null && !matchesDiscardType(c, rfg.cardType()))      return false;
		if (rfg.excludeName() != null &&  c.name().equalsIgnoreCase(rfg.excludeName())) return false;
		return true;
	}

	private boolean rfthCostSatisfied(ReturnToHandCost rth, boolean isP1) {
		return eligibleRfthFieldTargets(rth, isP1).size() >= rth.count();
	}

	private List<ForwardTarget> eligibleRfthFieldTargets(ReturnToHandCost rth, boolean isP1) {
		List<ForwardTarget> result = new ArrayList<>();
		List<CardData> fwds = playerForwardCards(isP1);
		List<CardData> mons = playerMonsterCards(isP1);
		CardData[]     bkps = playerBackupCards(isP1);
		for (int i = 0; i < fwds.size(); i++)
			if (matchesRfthFilter(fwds.get(i), rth)) result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
		for (int i = 0; i < bkps.length; i++)
			if (bkps[i] != null && matchesRfthFilter(bkps[i], rth)) result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
		for (int i = 0; i < mons.size(); i++)
			if (matchesRfthFilter(mons.get(i), rth)) result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
		return result;
	}

	private boolean matchesRfthFilter(CardData c, ReturnToHandCost rth) {
		if (rth.cardName()    != null && !meetsCardNameFilter(c, rth.cardName()))       return false;
		if (rth.cardType()    != null && !matchesDiscardType(c, rth.cardType()))        return false;
		if (rth.category()    != null && !meetsCategoryFilter(c, rth.category()))       return false;
		if (rth.excludeName() != null &&  c.name().equalsIgnoreCase(rth.excludeName())) return false;
		return true;
	}

	private void executeReturnToHandCost(ReturnToHandCost rth, boolean isP1) {
		GameContext ctx = buildGameContext(isP1);
		if (rth.cardName() != null) {
			// Auto-find named card and return it
			List<ForwardTarget> eligible = eligibleRfthFieldTargets(rth, isP1);
			for (int i = 0; i < rth.count() && i < eligible.size(); i++)
				returnTargetToHand(ctx, eligible.get(i));
		} else {
			List<ForwardTarget> eligible = eligibleRfthFieldTargets(rth, isP1);
			if (eligible.isEmpty()) { logEntry("No eligible field card for return-to-hand cost."); return; }
			List<ForwardTarget> picks = showForwardSelectDialog(eligible, rth.count(), false, "Return to Hand (cost)");
			applyTargetsHighestIndexFirst(picks, t -> returnTargetToHand(ctx, t));
		}
	}

	private void returnTargetToHand(GameContext ctx, ForwardTarget t) {
		switch (t.zone()) {
			case FORWARD -> { if (t.isP1()) ctx.returnP1ForwardToHand(t.idx()); else ctx.returnP2ForwardToHand(t.idx()); }
			case BACKUP  -> { if (t.isP1()) ctx.returnP1BackupToHand(t.idx());  else ctx.returnP2BackupToHand(t.idx()); }
			case MONSTER -> { if (t.isP1()) ctx.returnP1MonsterToHand(t.idx()); else ctx.returnP2MonsterToHand(t.idx()); }
		}
	}

	private CardData fieldCardData(ForwardTarget t) {
		if (t.isP1()) return switch (t.zone()) {
			case FORWARD -> p1ForwardCards.get(t.idx());
			case BACKUP  -> p1BackupCards[t.idx()];
			case MONSTER -> p1MonsterCards.get(t.idx());
		};
		return switch (t.zone()) {
			case FORWARD -> p2ForwardCards.get(t.idx());
			case BACKUP  -> p2BackupCards[t.idx()];
			case MONSTER -> p2MonsterCards.get(t.idx());
		};
	}

	private void breakP1BackupSlot(int idx) {
		CardData c = p1BackupCards[idx];
		if (c == null) return;
		startBreakAnim(p1BackupLabels[idx]);
		logEntry(c.name() + " → Break Zone");
		addToP1BreakZone(c);
		p1BackupCards[idx]   = null;
		p1BackupUrls[idx]    = null;
		p1BackupStates[idx]  = CardState.ACTIVE;
		p1BackupFrozen[idx]  = false;
		if (p1BackupLabels[idx] != null) {
			p1BackupLabels[idx].setIcon(null);
			p1BackupLabels[idx].setText(null);
		}
		refreshP1BreakLabel();
		triggerAutoAbilitiesForLeavesField(c, true);
		triggerAutoAbilitiesForBreakZone(c, true);
	}

	private void breakP1MonsterSlot(int idx) {
		if (idx >= p1MonsterCards.size()) return;
		startBreakAnim(p1MonsterLabels.get(idx));
		CardData c = p1MonsterCards.get(idx);
		logEntry(c.name() + " → Break Zone");
		addToP1BreakZone(c);
		p1MonsterTempForwardPower.remove(c);
		p1MonsterCards.remove(idx);
		p1MonsterStates.remove(idx);
		p1MonsterFrozen.remove(idx);
		p1MonsterPlayedOnTurn.remove(idx);
		p1MonsterDamage.remove(idx);
		p1MonsterUrls.remove(idx);
		JLabel lbl = p1MonsterLabels.remove(idx);
		if (p1MonsterPanel != null) {
			p1MonsterPanel.remove(lbl);
			p1MonsterPanel.revalidate();
			p1MonsterPanel.repaint();
		}
		refreshP1BreakLabel();
		triggerAutoAbilitiesForLeavesField(c, true);
		triggerAutoAbilitiesForBreakZone(c, true);
	}

	/**
	 * Adds an action-ability section to {@code menu} for all abilities on {@code card}.
	 * Each item is enabled only when the ability is currently activatable.
	 *
	 * @param card        the card whose abilities to list
	 * @param state       current field state of the card
	 * @param playedTurn  turn the card entered the field
	 * @param applyDull   called on confirm if the ability has a Dull cost (dulls the card)
	 */
	private void addAbilityMenuItems(JPopupMenu menu, CardData card, boolean isFrozen,
			CardState state, int playedTurn, Runnable applyDull, boolean isP1) {
		List<ActionAbility> abilities = card.actionAbilities();
		if (abilities.isEmpty()) return;

		GameState.GamePhase phase = gameState.getCurrentPhase();
		boolean isMainPhase  = phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.MAIN_2;
		boolean isAttackPhase = phase == GameState.GamePhase.ATTACK;

		for (ActionAbility ability : abilities) {
			if (ability.whileCardInHand()) continue; // only usable from hand, not from the field
			boolean hasAttackRestriction = ability.whileCardAttacking() != null
					|| ability.whileCardBlocking() != null || ability.whilePartyAttacking()
					|| ability.hasBlockingTargetEffect();
			boolean phaseOk = hasAttackRestriction ? isAttackPhase : isMainPhase;
			JMenuItem item = new JMenuItem(buildAbilityMenuLabel(ability));
			item.setEnabled(phaseOk && canActivateAbility(ability, isFrozen, state, playedTurn, card, isP1));
			item.addActionListener(ae ->
					showActionAbilityPaymentDialog(ability, card, applyDull, isP1));
			menu.add(item);
		}
	}

	/**
	 * Payment dialog for an action ability.  Mirrors the Priming payment dialog
	 * but also handles Dull cost (dulls the source card) and Special cost (discards
	 * a same-name card from hand).  On successful payment calls
	 * {@link ActionResolver#resolve}.
	 */
	private void showActionAbilityPaymentDialog(ActionAbility ability, CardData source,
			Runnable applyDull, boolean isP1) {
		List<String> rawCost = ability.cpCost();
		List<BreakZoneCost> bzCosts = ability.breakZoneCosts();

		// Zero CP + no X: confirm immediately
		if (rawCost.isEmpty() && !ability.hasXCost()) {
			executeAbilityPayment(ability, source, applyDull, new ArrayList<>(), new ArrayList<>(),
					autoResolveBzTargets(source, bzCosts, isP1), isP1, 0);
			return;
		}

		new AbilityPaymentDialog(frame, ability, source,
				playerHand(isP1), playerBackupCards(isP1), playerBackupStates(isP1), playerBackupUrls(isP1),
				this::showZoomAt, this::hideZoom,
				(discards, backups, xValue) -> executeAbilityPayment(ability, source, applyDull,
						discards, backups, autoResolveBzTargets(source, bzCosts, isP1), isP1, xValue))
			.show();
	}


	/**
	 * Executes the full payment for an action ability: dulls selected backups,
	 * discards hand cards for CP, optionally dulls the source card, optionally
	 * discards a same-name card (Special), then calls {@link ActionResolver#resolve}.
	 */
	private void executeAbilityPayment(ActionAbility ability, CardData source,
			Runnable applyDull, List<Integer> discardIndices, List<Integer> backupDullIndices,
			List<ForwardTarget> bzTargets, boolean isP1, int xValue) {
		List<String> rawCost = ability.cpCost();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);

		CardData[]  bkpCards  = playerBackupCards(isP1);
		CardState[] bkpStates = playerBackupStates(isP1);
		for (int bi : backupDullIndices) {
			bkpStates[bi] = CardState.DULL;
			playerDullBackupSlot(isP1, bi);
			String cpElem = matchesAnyElement(bkpCards[bi], elems)
					? contributingElement(bkpCards[bi], elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) playerAddCp(isP1, cpElem, 1);
		}
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
			CardData discarded = playerHand(isP1).get(di);
			String cpElem = matchesAnyElement(discarded, elems)
					? contributingElement(discarded, elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) playerAddCp(isP1, cpElem, 2);
			playerBreakFromHand(isP1, di);
		}
		for (String e : elems) { playerSpendCp(isP1, e, playerCpForElem(isP1, e)); playerClearCp(isP1, e); }

		// Crystal cost
		if (ability.crystalCost() > 0) {
			playerSpendCrystals(isP1, ability.crystalCost());
			refreshCrystalDisplays();
		}

		// Mark once-per-turn ability as used for this turn
		if (ability.oncePerTurn())
			usedOncePerTurnAbilities.computeIfAbsent(source, k -> new HashSet<>()).add(ability.effectText());

		// Dull source card
		if (ability.requiresDull()) applyDull.run();

		// Special: discard first same-name card from hand
		if (ability.isSpecial()) {
			List<CardData> hand = playerHand(isP1);
			for (int i = 0; i < hand.size(); i++) {
				if (source.name().equalsIgnoreCase(hand.get(i).name())) {
					playerBreakFromHand(isP1, i);
					logEntry("Special: discarded \"" + source.name() + "\" from hand");
					break;
				}
			}
		}

		// Break-zone costs: process in reverse index order within each zone to avoid index shifting
		List<ForwardTarget> sortedBz = new ArrayList<>(bzTargets);
		sortedBz.sort((a, b) -> a.zone() == b.zone() ? Integer.compare(b.idx(), a.idx()) : 0);
		for (ForwardTarget t : sortedBz) {
			if (t.isP1()) {
				switch (t.zone()) {
					case FORWARD -> breakP1Forward(t.idx());
					case BACKUP  -> breakP1BackupSlot(t.idx());
					case MONSTER -> breakP1MonsterSlot(t.idx());
				}
			} else {
				switch (t.zone()) {
					case FORWARD -> breakP2Forward(t.idx());
					case BACKUP  -> breakP2BackupSlot(t.idx());
					case MONSTER -> breakP2MonsterSlot(t.idx());
				}
			}
		}

		// Discard costs — paid from hand, no CP generated
		for (DiscardCost dc : ability.discardCosts()) {
			Set<String> usedTypes = new HashSet<>();
			for (int pick = 0; pick < dc.count(); pick++) {
				List<CardData> hand = playerHand(isP1);
				List<Integer> eligible = new ArrayList<>();
				for (int i = 0; i < hand.size(); i++) {
					CardData c = hand.get(i);
					if (dc.cardName()  != null && !meetsCardNameFilter(c, dc.cardName())) continue;
					if (dc.element()   != null && !c.containsElement(dc.element()))          continue;
					if (dc.cardType()  != null && !matchesDiscardType(c, dc.cardType()))     continue;
					if (dc.category()  != null && !meetsCategoryFilter(c, dc.category()))    continue;
					if (dc.eachDifferentType() && usedTypes.contains(discardTypeKey(c)))     continue;
					eligible.add(i);
				}
				if (eligible.isEmpty()) { logEntry("No eligible card for discard cost."); break; }
				String[] options = eligible.stream()
						.map(i -> hand.get(i).name() + " (Cost: " + hand.get(i).cost() + ")")
						.toArray(String[]::new);
				String label = "Discard cost" + (dc.count() > 1 ? " (" + (pick + 1) + "/" + dc.count() + ")" : "");
				String choice = (String) JOptionPane.showInputDialog(frame,
						"Choose a card to discard:", label,
						JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
				if (choice == null) break;
				int listIdx = java.util.Arrays.asList(options).indexOf(choice);
				if (listIdx < 0) break;
				int handIdx = eligible.get(listIdx);
				if (dc.eachDifferentType()) usedTypes.add(discardTypeKey(hand.get(handIdx)));
				String discarded = hand.get(handIdx).name();
				playerBreakFromHand(isP1, handIdx);
				logEntry("Discard cost: \"" + discarded + "\" discarded");
			}
		}

		// Remove-from-game costs
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			executeRemoveFromGameCost(rfg, isP1);

		// Return-to-hand costs
		for (ReturnToHandCost rth : ability.returnToHandCosts())
			executeReturnToHandCost(rth, isP1);

		// Counter removal costs
		for (CounterCost cc : ability.counterCosts()) {
			int removed = gameState.removeCounters(source, cc.counterName(), cc.count());
			logEntry(source.name() + " — removed " + removed + " " + cc.counterName()
					+ " Counter(s) (cost)  [remaining: "
					+ gameState.getCounters(source, cc.counterName()) + "]");
		}

		// Dull-forward costs: player picks an active forward to dull; its power is stored for effect resolution
		lastDullForwardCostPower = 0;
		for (DullForwardCost dfc : ability.dullForwardCosts()) {
			List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
			List<CardState> states = isP1 ? p1ForwardStates : p2ForwardStates;
			List<Integer> eligible = new ArrayList<>();
			for (int i = 0; i < fwds.size(); i++) {
				if (states.get(i) != CardState.ACTIVE) continue;
				if (!dfc.element().isEmpty() && !fwds.get(i).containsElement(dfc.element())) continue;
				eligible.add(i);
			}
			if (eligible.isEmpty()) { logEntry("No eligible active Forward for Dull cost."); continue; }
			List<ForwardTarget> targets = eligible.stream()
					.map(i -> new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD)).toList();
			List<ForwardTarget> picks = showForwardSelectDialog(targets, 1, false, "Dull Forward Cost");
			if (picks.isEmpty()) continue;
			int fwdIdx = picks.get(0).idx();
			lastDullForwardCostPower = fwds.get(fwdIdx).power();
			states.set(fwdIdx, CardState.DULL);
			if (isP1) animateDullForward(fwdIdx, null); else animateDullP2Forward(fwdIdx, null);
			logEntry("Dull cost: \"" + fwds.get(fwdIdx).name() + "\" dulled (power " + lastDullForwardCostPower + ")");
		}

		// Self-mill cost
		if (ability.selfMillCost() > 0) {
			int count = ability.selfMillCost();
			java.util.Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
			int available = deck.size();
			boolean milledOut = available < count;
			if (isP1) {
				buildGameContext(true).millCards(count);
			} else {
				buildGameContext(false).opponentMillCards(count);
			}
			if (milledOut) {
				String msg = isP1 ? "P1 milled out — You Lose!" : "P2 milled out — Opponent Loses!";
				if (available > 0) {
					int animMs = ((available - 1) * 5 + CardSlideAnimator.TOTAL_FRAMES) * CardSlideAnimator.FRAME_MS;
					javax.swing.Timer t = new javax.swing.Timer(animMs, e -> triggerGameOver(msg));
					t.setRepeats(false);
					t.start();
				} else {
					triggerGameOver(msg);
				}
				return;
			}
		}

		logEntry("\"" + source.name() + "\" activated ability");

		gameState.pushStack(new StackEntry(source, ability, isP1, xValue));
		showStackWindow();
		refreshP1HandLabel();
		refreshP1BreakLabel();
	}

	private void executeRemoveFromGameCost(RemoveFromGameCost rfg, boolean isP1) {
		switch (rfg.zone()) {
			case "DECK" -> {
				java.util.Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
				for (int i = 0; i < rfg.count() && !deck.isEmpty(); i++) {
					CardData c = deck.pollFirst();
					if (isP1) gameState.addToP1PermanentRfp(c); else gameState.addToP2PermanentRfp(c);
					logEntry(c.name() + " → Removed From Game (cost)");
				}
				if (isP1) refreshP1DeckLabel(); else refreshP2DeckLabel();
			}
			case "HAND" -> {
				int target = rfg.count();
				for (int pick = 0; pick < target; pick++) {
					List<Integer> eligible = eligibleRfgHandIndices(rfg, isP1);
					if (eligible.isEmpty()) { logEntry("No eligible hand card for remove-from-game cost."); break; }
					List<CardData> hand = playerHand(isP1);
					if (eligible.size() == 1 && rfg.cardName() != null) {
						// Named card — auto-select
						CardData c = hand.get(eligible.get(0));
						hand.remove((int) eligible.get(0));
						if (isP1) gameState.addToP1PermanentRfp(c); else gameState.addToP2PermanentRfp(c);
						logEntry(c.name() + " → Removed From Game (cost)");
					} else {
						String[] options = eligible.stream()
								.map(i -> hand.get(i).name() + " (Cost: " + hand.get(i).cost() + ")")
								.toArray(String[]::new);
						String label = "Remove from game (hand)" + (target > 1 ? " (" + (pick + 1) + "/" + target + ")" : "");
						String choice = (String) JOptionPane.showInputDialog(frame,
								"Choose a card to remove from game:", label,
								JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
						if (choice == null) break;
						int listIdx = java.util.Arrays.asList(options).indexOf(choice);
						if (listIdx < 0) break;
						int handIdx = eligible.get(listIdx);
						CardData c = hand.get(handIdx);
						hand.remove(handIdx);
						if (isP1) gameState.addToP1PermanentRfp(c); else gameState.addToP2PermanentRfp(c);
						logEntry(c.name() + " → Removed From Game (cost)");
					}
				}
				refreshP1HandLabel();
			}
			case "BREAK_ZONE" -> {
				List<CardData> bz = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
				if (rfg.count() == -1) {
					// Remove all matching cards
					List<Integer> eligible = eligibleRfgBzIndices(rfg, isP1);
					for (int i = eligible.size() - 1; i >= 0; i--) {
						CardData c = bz.remove((int) eligible.get(i));
						if (isP1) gameState.addToP1PermanentRfp(c); else gameState.addToP2PermanentRfp(c);
						logEntry(c.name() + " → Removed From Game (cost)");
					}
				} else {
					for (int pick = 0; pick < rfg.count(); pick++) {
						List<Integer> eligible = eligibleRfgBzIndices(rfg, isP1);
						if (eligible.isEmpty()) { logEntry("No eligible Break Zone card for remove-from-game cost."); break; }
						if (eligible.size() == 1 && rfg.cardName() != null) {
							CardData c = bz.remove((int) eligible.get(0));
							if (isP1) gameState.addToP1PermanentRfp(c); else gameState.addToP2PermanentRfp(c);
							logEntry(c.name() + " → Removed From Game (cost)");
						} else {
							String[] options = eligible.stream().map(i -> bz.get(i).name()).toArray(String[]::new);
							String label = "Remove from game (Break Zone)" + (rfg.count() > 1 ? " (" + (pick + 1) + "/" + rfg.count() + ")" : "");
							String choice = (String) JOptionPane.showInputDialog(frame,
									"Choose a card to remove from game:", label,
									JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
							if (choice == null) break;
							int listIdx = java.util.Arrays.asList(options).indexOf(choice);
							if (listIdx < 0) break;
							int bzIdx = eligible.get(listIdx);
							CardData c = bz.remove(bzIdx);
							if (isP1) gameState.addToP1PermanentRfp(c); else gameState.addToP2PermanentRfp(c);
							logEntry(c.name() + " → Removed From Game (cost)");
						}
					}
				}
				refreshP1BreakLabel();
			}
			default -> {
				// FIELD
				GameContext ctx = buildGameContext(isP1);
				if (rfg.cardName() != null) {
					// Auto-find named card(s) and remove
					List<ForwardTarget> eligible = eligibleRfgFieldTargets(rfg, isP1);
					for (int i = 0; i < rfg.count() && i < eligible.size(); i++)
						ctx.removeTargetFromGame(eligible.get(i));
				} else {
					List<ForwardTarget> eligible = eligibleRfgFieldTargets(rfg, isP1);
					if (eligible.isEmpty()) { logEntry("No eligible field card for remove-from-game cost."); }
					else {
						List<ForwardTarget> picks = showForwardSelectDialog(eligible, rfg.count(), false, "Remove from Game (field)");
						applyTargetsHighestIndexFirst(picks, ctx::removeTargetFromGame);
					}
				}
			}
		}
	}

	/**
	 * Builds the {@link GameContext} used by {@link ActionResolver} to apply field effects.
	 * The returned instance is stateless (delegates to live MainWindow fields), so it is safe
	 * to call multiple times and share between ability resolution and summon resolution.
	 *
	 * @param isP1 {@code true} when P1 is the ability user (affects discard/draw direction)
	 */
	private GameContext buildGameContext(boolean isP1) {
		return buildGameContext(isP1, false);
	}

	private GameContext buildGameContext(boolean isP1, boolean exBurst) {
		return new GameContext() {
			@Override public void logEntry(String msg) { MainWindow.this.logEntry(msg); }
			@Override public boolean isP1() { return isP1; }

			@Override public void resetEffectProgress() { effectProgress = true; }
			@Override public void markEffectFizzled()   { effectProgress = false; }
			@Override public boolean effectMadeProgress() { return effectProgress; }

			@Override public int p1ForwardCount()                    { return p1ForwardCards.size(); }
			@Override public CardData p1Forward(int idx) {
				CardData top = p1ForwardPrimedTop.get(idx);
				return top != null ? top : p1ForwardCards.get(idx);
			}
			@Override public int       p1ForwardCurrentDamage(int idx) { return p1ForwardDamage.get(idx); }
			@Override public CardState p1ForwardState(int idx)          { return p1ForwardStates.get(idx); }
			@Override public void damageP1Forward(int idx, int amount) {
				applyDamageToForward(true, idx, amount, true, false);
			}

			@Override public int p2ForwardCount()                    { return p2ForwardCards.size(); }
			@Override public CardData p2Forward(int idx)             { return p2ForwardCards.get(idx); }
			@Override public int       p2ForwardCurrentDamage(int idx) { return p2ForwardDamage.get(idx); }
			@Override public CardState p2ForwardState(int idx)          { return p2ForwardStates.get(idx); }
			@Override public void damageP2Forward(int idx, int amount) {
				applyDamageToForward(false, idx, amount, true, false);
			}

			@Override public void damageP1ForwardUnreduced(int idx, int amount) {
				applyDamageToForward(true, idx, amount, true, true);
			}
			@Override public void damageP2ForwardUnreduced(int idx, int amount) {
				applyDamageToForward(false, idx, amount, true, true);
			}
			@Override public void damageTargetUnreduced(ForwardTarget t, int amount) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) return;
				if (t.isP1()) damageP1ForwardUnreduced(t.idx(), amount);
				else          damageP2ForwardUnreduced(t.idx(), amount);
			}

			@Override public void shieldNextIncomingDamage(ForwardTarget t) {
				CardData c = fieldCardData(t); if (c != null) nextIncomingDmgZeroSet.add(c);
			}
			@Override public void shieldNextIncomingDamageReduction(ForwardTarget t, int reduction) {
				CardData c = fieldCardData(t); if (c != null) nextIncomingDmgReduceMap.merge(c, reduction, Integer::sum);
			}
			@Override public void shieldNextAbilityIncomingDamageReduction(ForwardTarget t, int reduction) {
				CardData c = fieldCardData(t); if (c != null) nextAbilityDmgReduceMap.merge(c, reduction, Integer::sum);
			}
			@Override public void debuffIncomingDamageIncrease(ForwardTarget t, int amount) {
				CardData c = fieldCardData(t); if (c != null) incomingDmgIncreaseMap.merge(c, amount, Integer::sum);
			}
			@Override public void shieldAbilityDamage(ForwardTarget t) {
				CardData c = fieldCardData(t); if (c != null) nullifyAbilityDmgSet.add(c);
			}
			@Override public void shieldAbilityOnlyDamage(ForwardTarget t) {
				CardData c = fieldCardData(t); if (c != null) nullifyAbilityOnlyDmgSet.add(c);
			}
			@Override public void shieldNonLethal(ForwardTarget t) {
				CardData c = fieldCardData(t); if (c != null) perCardNonLethalDmgSet.add(c);
			}
			@Override public void disableOpponentDamageReduction() {
				if (isP1) p2DmgReductionDisabled = true; else p1DmgReductionDisabled = true;
			}
			@Override public void shieldNextOutgoingDamage(ForwardTarget t) {
				CardData c = fieldCardData(t); if (c != null) nextOutgoingDmgZeroSet.add(c);
			}
			@Override public void shieldActivePlayerNonLethal() {
				if (isP1) p1NonLethalProtection = true; else p2NonLethalProtection = true;
			}
			@Override public void shieldActivePlayerDamageReduction(int reduction) {
				if (isP1) p1GlobalDmgReduction += reduction; else p2GlobalDmgReduction += reduction;
			}

			@Override public void negateAllDamage(ForwardTarget t) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				if (t.isP1()) {
					int idx = t.idx();
					if (idx < 0 || idx >= p1ForwardCards.size() || p1ForwardDamage.get(idx) == 0) return;
					logEntry(p1Forward(idx).name() + " — all damage negated");
					p1ForwardDamage.set(idx, 0);
					refreshP1ForwardSlot(idx);
				} else {
					int idx = t.idx();
					if (idx < 0 || idx >= p2ForwardCards.size() || p2ForwardDamage.get(idx) == 0) return;
					logEntry("[P2] " + p2ForwardCards.get(idx).name() + " — all damage negated");
					p2ForwardDamage.set(idx, 0);
					refreshP2ForwardSlot(idx);
				}
			}

			@Override public void negateAllDamageOwnForwards() {
				List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
				List<Integer>  dmg  = isP1 ? p1ForwardDamage : p2ForwardDamage;
				for (int i = 0; i < fwds.size(); i++) {
					if (dmg.get(i) == 0) continue;
					logEntry((isP1 ? "" : "[P2] ") + fwds.get(i).name() + " — all damage negated");
					dmg.set(i, 0);
					if (isP1) refreshP1ForwardSlot(i); else refreshP2ForwardSlot(i);
				}
			}

			@Override public void shieldCannotBeChosen(ForwardTarget t, boolean bySummons, boolean byAbilities) {
				CardData c = fieldCardData(t);
				if (c == null) return;
				if (bySummons)   cannotBeChosenBySummons.add(c);
				if (byAbilities) cannotBeChosenByAbilities.add(c);
			}

			@Override public void shieldCannotBeBroken(ForwardTarget t) {
				CardData c = fieldCardData(t);
				if (c == null) return;
				cannotBeBrokenSet.add(c);
				logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " cannot be broken until end of turn");
			}

			@Override public void shieldCannotBeBrokenByNonDmg(ForwardTarget t) {
				CardData c = fieldCardData(t);
				if (c == null) return;
				cannotBeBrokenByNonDmgSet.add(c);
				logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " cannot be broken by opposing non-damage Summons or abilities until end of turn");
			}

			@Override public void shieldSourceForward(CardData source) {
				List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
				for (CardData c : fwds) {
					if (c.name().equalsIgnoreCase(source.name())) {
						cannotBeBrokenSet.add(c);
						logEntry((isP1 ? "" : "[P2] ") + c.name() + " cannot be broken until end of turn");
						return;
					}
				}
			}

			@Override public void shieldAllOwnForwards() {
				List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
				for (CardData c : fwds) {
					cannotBeBrokenSet.add(c);
					logEntry((isP1 ? "" : "[P2] ") + c.name() + " cannot be broken until end of turn");
				}
			}

			@Override public void shieldBreaktouchBattle(ForwardTarget t) {
				CardData c = fieldCardData(t);
				if (c == null) return;
				breaktouchBattleSet.add(c);
				logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " — Breaktouch (battle damage) until end of turn");
			}

			@Override public void shieldAllOwnForwardsCannotBeChosen(boolean bySummons, boolean byAbilities) {
				List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
				for (CardData c : fwds) {
					if (bySummons)   cannotBeChosenBySummons.add(c);
					if (byAbilities) cannotBeChosenByAbilities.add(c);
				}
				logEntry("Effect: all own Forwards cannot be chosen by opponent's" +
						(bySummons && byAbilities ? " Summons or abilities" : bySummons ? " Summons" : " abilities"));
			}

			@Override public void shieldNamedCardCannotBeChosen(String name, boolean bySummons, boolean byAbilities) {
				List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
				for (CardData c : fwds) {
					if (!c.name().equalsIgnoreCase(name)) continue;
					if (bySummons)   cannotBeChosenBySummons.add(c);
					if (byAbilities) cannotBeChosenByAbilities.add(c);
				}
			}

			@Override public void shieldJobForwardsCannotBeChosen(String job, String excludeName,
					boolean bySummons, boolean byAbilities) {
				List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
				for (CardData c : fwds) {
					if (!meetsJobFilter(c, job)) continue;
					if (excludeName != null && c.name().equalsIgnoreCase(excludeName)) continue;
					if (bySummons)   cannotBeChosenBySummons.add(c);
					if (byAbilities) cannotBeChosenByAbilities.add(c);
				}
			}

			@Override public void gainControlOfForward(ForwardTarget t, String condition, boolean activate) {
				// Only supported for P1 stealing from P2 in the current implementation
				if (!isP1 || t.isP1() || t.zone() != ForwardTarget.CardZone.FORWARD) return;
				stealForwardFromP2ToP1(t.idx(), condition, activate);
			}

			@Override
			public java.util.List<ForwardTarget> selectCharacters(
					int maxCount, boolean upTo, boolean opponentOnly,
					boolean selfOnly, String condition, String element,
					int costVal, String costCmp, int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
					String excludeElement, boolean withoutMulticard) {
				java.util.List<ForwardTarget> eligible = new ArrayList<>();
				// "own" = cards belonging to effect controller; "opp" = other player's cards.
				// isP1 captures the controller's perspective, so the two blocks below must
				// flip which physical side they iterate when isP1 is false (P2 controls).
				if (!opponentOnly) {
					if (isP1) {
						if (inclForwards || inclMonsters) for (int i = 0; i < p1ForwardCards.size(); i++) {
							CardData card = p1Forward(i);
							if (!inclForwards && !card.alsoCountsAsMonster()) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(card, jobFilter)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isBlockingTargetFilter(condition)
									? meetsBlockingTargetFilter(true, i, condition)
									: isEnteredThisTurnCondition(condition)
									? p1ForwardPlayedOnTurn.get(i) == gameState.getTurnNumber()
									: meetsTargetCondition(p1ForwardStates.get(i), p1ForwardDamage.get(i),
											p1AttackSelection.contains(i), false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
						}
						if (inclBackups) for (int i = 0; i < p1BackupCards.length; i++) {
							if (isBlockingTargetFilter(condition)) continue;
							if (p1BackupCards[i] == null) continue;
							if (element != null && !p1BackupCards[i].containsElement(element)) continue;
							if (!meetsCostConstraint(p1BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(p1BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(p1BackupCards[i], jobFilter)) continue;
							if (!meetsCardNameFilter(p1BackupCards[i], cardNameFilter)) continue;
							if (!meetsCategoryFilter(p1BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(p1BackupCards[i].name())) continue;
							if (withoutMulticard && p1BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(p1BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < p1MonsterCards.size(); i++) {
							if (!inclMonsters && !isP1MonsterTemporarilyForward(i)) continue;
							CardData card = p1MonsterCards.get(i);
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(card, jobFilter)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isEnteredThisTurnCondition(condition)
									? p1MonsterPlayedOnTurn.get(i) == gameState.getTurnNumber()
									: meetsTargetCondition(p1MonsterStates.get(i), 0, false, false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER));
						}
					} else {
						if (inclForwards || inclMonsters) for (int i = 0; i < p2ForwardCards.size(); i++) {
							CardData card = p2ForwardCards.get(i);
							if (!inclForwards && !card.alsoCountsAsMonster()) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(card, jobFilter)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isBlockingTargetFilter(condition)
									? meetsBlockingTargetFilter(false, i, condition)
									: isEnteredThisTurnCondition(condition)
									? p2ForwardPlayedOnTurn.get(i) == gameState.getTurnNumber()
									: meetsTargetCondition(p2ForwardStates.get(i), p2ForwardDamage.get(i),
											false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
						}
						if (inclBackups) for (int i = 0; i < p2BackupCards.length; i++) {
							if (isBlockingTargetFilter(condition)) continue;
							if (p2BackupCards[i] == null) continue;
							if (element != null && !p2BackupCards[i].containsElement(element)) continue;
							if (!meetsCostConstraint(p2BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(p2BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(p2BackupCards[i], jobFilter)) continue;
							if (!meetsCardNameFilter(p2BackupCards[i], cardNameFilter)) continue;
							if (!meetsCategoryFilter(p2BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(p2BackupCards[i].name())) continue;
							if (withoutMulticard && p2BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(p2BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < p2MonsterCards.size(); i++) {
							if (!inclMonsters && !isP2MonsterTemporarilyForward(i)) continue;
							CardData card = p2MonsterCards.get(i);
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(card, jobFilter)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isEnteredThisTurnCondition(condition)
									? p2MonsterPlayedOnTurn.get(i) == gameState.getTurnNumber()
									: meetsTargetCondition(p2MonsterStates.get(i), 0, false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER));
						}
					}
				}
				if (!selfOnly) {
					if (isP1) {
						if (inclForwards || inclMonsters) for (int i = 0; i < p2ForwardCards.size(); i++) {
							CardData card = p2ForwardCards.get(i);
							if (!inclForwards && !card.alsoCountsAsMonster()) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(card, jobFilter)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isBlockingTargetFilter(condition)
									? meetsBlockingTargetFilter(false, i, condition)
									: isEnteredThisTurnCondition(condition)
									? p2ForwardPlayedOnTurn.get(i) == gameState.getTurnNumber()
									: meetsTargetCondition(p2ForwardStates.get(i), p2ForwardDamage.get(i),
											false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
						}
						if (inclBackups) for (int i = 0; i < p2BackupCards.length; i++) {
							if (isBlockingTargetFilter(condition)) continue;
							if (p2BackupCards[i] == null) continue;
							if (element != null && !p2BackupCards[i].containsElement(element)) continue;
							if (!meetsCostConstraint(p2BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(p2BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(p2BackupCards[i], jobFilter)) continue;
							if (!meetsCardNameFilter(p2BackupCards[i], cardNameFilter)) continue;
							if (!meetsCategoryFilter(p2BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(p2BackupCards[i].name())) continue;
							if (withoutMulticard && p2BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(p2BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < p2MonsterCards.size(); i++) {
							if (!inclMonsters && !isP2MonsterTemporarilyForward(i)) continue;
							CardData card = p2MonsterCards.get(i);
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(card, jobFilter)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isEnteredThisTurnCondition(condition)
									? p2MonsterPlayedOnTurn.get(i) == gameState.getTurnNumber()
									: meetsTargetCondition(p2MonsterStates.get(i), 0, false, false, condition))
								eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER));
						}
					} else {
						// P2 is targeting P1's cards — check "cannot be chosen" protection
						Set<CardData> noChoose = currentResolutionIsSummon ? cannotBeChosenBySummons : cannotBeChosenByAbilities;
						if (inclForwards) for (int i = 0; i < p1ForwardCards.size(); i++) {
							CardData card = p1Forward(i);
							if (noChoose.contains(card)) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(card, jobFilter)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isBlockingTargetFilter(condition)
									? meetsBlockingTargetFilter(true, i, condition)
									: isEnteredThisTurnCondition(condition)
									? p1ForwardPlayedOnTurn.get(i) == gameState.getTurnNumber()
									: meetsTargetCondition(p1ForwardStates.get(i), p1ForwardDamage.get(i),
											p1AttackSelection.contains(i), false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
						}
						if (inclBackups) for (int i = 0; i < p1BackupCards.length; i++) {
							if (isBlockingTargetFilter(condition)) continue;
							if (p1BackupCards[i] == null) continue;
							if (noChoose.contains(p1BackupCards[i])) continue;
							if (element != null && !p1BackupCards[i].containsElement(element)) continue;
							if (!meetsCostConstraint(p1BackupCards[i].cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(p1BackupCards[i].power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(p1BackupCards[i], jobFilter)) continue;
							if (!meetsCardNameFilter(p1BackupCards[i], cardNameFilter)) continue;
							if (!meetsCategoryFilter(p1BackupCards[i], categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(p1BackupCards[i].name())) continue;
							if (withoutMulticard && p1BackupCards[i].multicard()) continue;
							if (meetsTargetCondition(p1BackupStates[i], 0, false, false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP));
						}
						if (inclMonsters || inclForwards) for (int i = 0; i < p1MonsterCards.size(); i++) {
							if (!inclMonsters && !isP1MonsterTemporarilyForward(i)) continue;
							CardData card = p1MonsterCards.get(i);
							if (noChoose.contains(card)) continue;
							if (element != null && !card.containsElement(element)) continue;
							if (!meetsElementExclusion(card, excludeElement)) continue;
							if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
							if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
							if (!meetsJobFilter(card, jobFilter)) continue;
							if (!meetsCardNameFilter(card, cardNameFilter)) continue;
							if (!meetsCategoryFilter(card, categoryFilter)) continue;
							if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
							if (withoutMulticard && card.multicard()) continue;
							if (isEnteredThisTurnCondition(condition)
									? p1MonsterPlayedOnTurn.get(i) == gameState.getTurnNumber()
									: meetsTargetCondition(p1MonsterStates.get(i), 0, false, false, condition))
								eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER));
						}
					}
				}
				String costLabel  = costVal  >= 0 ? " of cost "  + costVal  + (costCmp  != null ? " or " + costCmp  : "") : "";
				String powerLabel = powerVal >= 0 ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
				String targetNoun = inclForwards && !inclBackups && !inclMonsters ? "Forward"
						: inclBackups && !inclForwards && !inclMonsters ? "Backup"
						: inclMonsters && !inclForwards && !inclBackups ? "Monster"
						: "Character";
				String title = "Choose " + (upTo ? "up to " : "") + maxCount
						+ (condition != null ? " " + condition : "")
						+ (element != null ? " " + element : "")
						+ " " + targetNoun + (maxCount != 1 ? "s" : "") + costLabel + powerLabel
						+ (opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "");
				if (!isP1) {
					// AI (P2 controls the effect): auto-select rather than prompting the human.
					if (eligible.isEmpty()) return java.util.List.of();
					// For unqualified targeting, prefer opponent (P1) targets over own cards.
					java.util.List<ForwardTarget> pool = eligible;
					if (!opponentOnly && !selfOnly) {
						java.util.List<ForwardTarget> oppTargets = eligible.stream()
								.filter(ForwardTarget::isP1).toList();
						if (!oppTargets.isEmpty()) pool = oppTargets;
					}
					java.util.List<ForwardTarget> copy = new ArrayList<>(pool);
					java.util.Collections.shuffle(copy);
					java.util.List<ForwardTarget> picked = java.util.List.copyOf(copy.subList(0, Math.min(maxCount, copy.size())));
					picked.forEach(t -> {
						CardData c = switch (t.zone()) {
							case BACKUP  -> t.isP1() ? p1BackupCards[t.idx()] : p2BackupCards[t.idx()];
							case MONSTER -> t.isP1() ? p1MonsterCards.get(t.idx()) : p2MonsterCards.get(t.idx());
							default      -> t.isP1() ? p1Forward(t.idx()) : p2ForwardCards.get(t.idx());
						};
						logEntry("[AI] chose " + c.name());
					});
					return picked;
				}
				return showForwardSelectDialog(eligible, maxCount, upTo, title);
			}

			@Override public void dullP1Forward(int idx) {
				if (idx >= p1ForwardStates.size()) return;
				p1ForwardStates.set(idx, CardState.DULL);
				logEntry(p1Forward(idx).name() + " is dulled");
				animateDullForward(idx, null);
			}

			@Override public void dullP2Forward(int idx) {
				if (idx >= p2ForwardStates.size()) return;
				p2ForwardStates.set(idx, CardState.DULL);
				logEntry("[P2] " + p2ForwardCards.get(idx).name() + " is dulled");
				animateDullP2Forward(idx, null);
			}

			@Override public void freezeP1Forward(int idx) {
				if (idx >= p1ForwardStates.size()) return;
				p1ForwardFrozen.set(idx, true);
				logEntry(p1Forward(idx).name() + " is frozen");
				refreshP1ForwardSlot(idx);
			}

			@Override public void freezeP2Forward(int idx) {
				if (idx >= p2ForwardStates.size()) return;
				p2ForwardFrozen.set(idx, true);
				logEntry("[P2] " + p2ForwardCards.get(idx).name() + " is frozen");
				refreshP2ForwardSlot(idx);
			}

			@Override public void setP1ForwardCannotBlock(int idx) {
				if (idx >= 0 && idx < p1ForwardCards.size()) p1ForwardCannotBlock.add(idx);
			}
			@Override public void setP2ForwardCannotBlock(int idx) {
				if (idx >= 0 && idx < p2ForwardCards.size()) p2ForwardCannotBlock.add(idx);
			}
			@Override public void setP1ForwardCannotBeBlocked(int idx) {
				if (idx >= 0 && idx < p1ForwardCards.size()) p1ForwardCannotBeBlocked.add(idx);
			}
			@Override public void setP2ForwardCannotBeBlocked(int idx) {
				if (idx >= 0 && idx < p2ForwardCards.size()) p2ForwardCannotBeBlocked.add(idx);
			}
			@Override public void setP1ForwardCannotBeBlockedByCost(int idx, int costVal, boolean isMore) {
				if (idx >= 0 && idx < p1ForwardCards.size())
					p1ForwardCannotBeBlockedByCost.put(idx, new int[]{costVal, isMore ? 1 : 0});
			}
			@Override public void setP2ForwardCannotBeBlockedByCost(int idx, int costVal, boolean isMore) {
				if (idx >= 0 && idx < p2ForwardCards.size())
					p2ForwardCannotBeBlockedByCost.put(idx, new int[]{costVal, isMore ? 1 : 0});
			}
			@Override public boolean wasElementCpPaid(String element) {
				return element != null && lastCastPaymentElements.stream()
						.anyMatch(e -> e.equalsIgnoreCase(element));
			}
			@Override public void setP1ForwardMustBlock(int idx) {
				if (idx >= 0 && idx < p1ForwardCards.size()) p1ForwardMustBlock.add(idx);
			}
			@Override public void setP2ForwardMustBlock(int idx) {
				if (idx >= 0 && idx < p2ForwardCards.size()) p2ForwardMustBlock.add(idx);
			}
			@Override public void setP1ForwardCannotAttack(int idx) {
				if (idx >= 0 && idx < p1ForwardCards.size()) p1ForwardCannotAttack.add(idx);
			}
			@Override public void setP2ForwardCannotAttack(int idx) {
				if (idx >= 0 && idx < p2ForwardCards.size()) p2ForwardCannotAttack.add(idx);
			}
			@Override public void setP1ForwardMustAttack(int idx) {
				if (idx >= 0 && idx < p1ForwardCards.size()) p1ForwardMustAttack.add(idx);
			}
			@Override public void setP2ForwardMustAttack(int idx) {
				if (idx >= 0 && idx < p2ForwardCards.size()) p2ForwardMustAttack.add(idx);
			}
			@Override public void setP1ForwardCannotAttackOrBlockPersistent(int idx) {
				if (idx >= 0 && idx < p1ForwardCards.size()) {
					p1ForwardCannotAttackPersistent.add(idx);
					p1ForwardCannotBlockPersistent.add(idx);
				}
			}
			@Override public void setP2ForwardCannotAttackOrBlockPersistent(int idx) {
				if (idx >= 0 && idx < p2ForwardCards.size()) {
					p2ForwardCannotAttackPersistent.add(idx);
					p2ForwardCannotBlockPersistent.add(idx);
				}
			}
			@Override public void returnP1ForwardToHand(int idx) { MainWindow.this.returnP1ForwardToHand(idx); }
			@Override public void returnP2ForwardToHand(int idx) { MainWindow.this.returnP2ForwardToHand(idx); }
			@Override public boolean askTopOrBottom(String cardName) {
					if (!isP1) {
						logEntry("[AI] places " + cardName + " on top of the deck");
						return true;
					}
				Object[] options = { "Top", "Bottom" };
				int result = JOptionPane.showOptionDialog(frame,
						"Place " + cardName + " at the top or bottom of the deck?",
						"Choose Deck Position",
						JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
						null, options, options[0]);
				return result != 1;
			}
			@Override public int selectNumber(int min, int max, String prompt) {
					if (!isP1) {
						logEntry("[AI] selected " + max + " (" + prompt + ")");
						return max;
					}
				return MainWindow.this.showNumberSelectDialog(prompt, min, max);
			}
			@Override public void returnP1ForwardToDeckBottom(int idx)   { returnP1ForwardToDeck(idx, true);  }
			@Override public void returnP2ForwardToDeckBottom(int idx)   { returnP2ForwardToDeck(idx, true);  }
			@Override public void returnP1ForwardToDeckTop(int idx)      { returnP1ForwardToDeck(idx, false); }
			@Override public void returnP2ForwardToDeckTop(int idx)      { returnP2ForwardToDeck(idx, false); }
			@Override public void returnP1ForwardUnderDeckTop(int idx, int position) { MainWindow.this.returnP1ForwardUnderDeckTop(idx, position); }
			@Override public void returnP2ForwardUnderDeckTop(int idx, int position) { MainWindow.this.returnP2ForwardUnderDeckTop(idx, position); }
			@Override public void searchDeckForCard(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, boolean inclSummons,
					int costVal, String costCmp, String cardNameFilter, String jobFilter,
					String categoryFilter, String elementFilter, String excludeName, String excludeElem,
					String destination) {
				MainWindow.this.searchDeckForCard(isP1, inclForwards, inclBackups, inclMonsters, inclSummons,
						costVal, costCmp, cardNameFilter, jobFilter, categoryFilter, elementFilter, excludeName, excludeElem, destination);
			}

			@Override public void returnP1BackupToHand(int idx) { MainWindow.this.returnP1BackupToHand(idx); }
			@Override public void returnP2BackupToHand(int idx) { MainWindow.this.returnP2BackupToHand(idx); }
			@Override public void returnP1MonsterToHand(int idx) { MainWindow.this.returnP1MonsterToHand(idx); }
			@Override public void returnP2MonsterToHand(int idx) { MainWindow.this.returnP2MonsterToHand(idx); }

			@Override public boolean isP1ForwardAttacking(int idx) { return p1AttackSelection.contains(idx); }
			@Override public boolean isP2ForwardAttacking(int idx) { return false; }
			@Override public boolean isP1ForwardBlocking(int idx)  { return false; }
			@Override public boolean isP2ForwardBlocking(int idx)  { return false; }

			@Override public void breakP1Forward(int idx) { MainWindow.this.breakP1Forward(idx); }
			@Override public void breakP2Forward(int idx) { MainWindow.this.breakP2Forward(idx); }

			@Override public void removeP1ForwardFromGame(int idx) {
				if (idx >= p1ForwardCards.size()) return;
				logEntry(p1Forward(idx).name() + " → Removed From Game");
				List<CardData> bz = gameState.getP1BreakZone();
				int before = bz.size();
				MainWindow.this.breakP1Forward(idx);
				while (bz.size() > before)
					gameState.addToP1PermanentRfp(bz.remove(bz.size() - 1));
				refreshP1BreakLabel();
				refreshP1WarpZoneUI();
			}

			@Override public void removeP2ForwardFromGame(int idx) {
				if (idx >= p2ForwardCards.size()) return;
				logEntry("[P2] " + p2ForwardCards.get(idx).name() + " → Removed From Game");
				List<CardData> bz = gameState.getP2BreakZone();
				int before = bz.size();
				MainWindow.this.breakP2Forward(idx);
				while (bz.size() > before)
					gameState.addToP2PermanentRfp(bz.remove(bz.size() - 1));
				refreshP2BreakLabel();
			}

			@Override
			public java.util.List<ForwardTarget> selectCharactersFromBreakZone(
					int maxCount, boolean upTo, boolean opponentZone,
					String condition, String element, int costVal, String costCmp,
					int powerVal, String powerCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons,
					String excludeElement, boolean withoutMulticard) {
				java.util.List<CardData> bz = opponentZone
						? gameState.getP2BreakZone() : gameState.getP1BreakZone();
				java.util.List<ForwardTarget> eligible = new ArrayList<>();
				for (int i = 0; i < bz.size(); i++) {
					CardData card = bz.get(i);
					if (card.isForward()  && !inclForwards) continue;
					if (card.isBackup()   && !inclBackups)  continue;
					if (card.isMonster()  && !inclMonsters) continue;
					if (card.isSummon()   && !inclSummons)  continue;
					if (element != null && !card.containsElement(element)) continue;
					if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
					if (!meetsPowerConstraint(card.power(), powerVal, powerCmp)) continue;
					if (!meetsJobFilter(card, jobFilter)) continue;
					if (!meetsCardNameFilter(card, cardNameFilter)) continue;
					if (!meetsCategoryFilter(card, categoryFilter)) continue;
					if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
					if (withoutMulticard && card.multicard()) continue;
					ForwardTarget.CardZone cz = card.isBackup()  ? ForwardTarget.CardZone.BACKUP
					                         : card.isMonster() ? ForwardTarget.CardZone.MONSTER
					                         :                    ForwardTarget.CardZone.FORWARD;
					eligible.add(new ForwardTarget(!opponentZone, i, cz));
				}
				String costLabel  = costVal  >= 0 ? " of cost "  + costVal  + (costCmp  != null ? " or " + costCmp  : "") : "";
				String powerLabel = powerVal >= 0 ? " of power " + powerVal + (powerCmp != null ? " or " + powerCmp : "") : "";
				String title = "Choose " + (upTo ? "up to " : "") + maxCount
						+ (element != null ? " " + element : "")
						+ " Character" + (maxCount != 1 ? "s" : "") + costLabel + powerLabel
						+ " in " + (opponentZone ? "opponent's" : "your") + " Break Zone";
				if (!isP1) {
					if (eligible.isEmpty()) return java.util.List.of();
					java.util.List<ForwardTarget> copy = new ArrayList<>(eligible);
					java.util.Collections.shuffle(copy);
					java.util.List<ForwardTarget> picked =
							java.util.List.copyOf(copy.subList(0, Math.min(maxCount, copy.size())));
					picked.forEach(t -> logEntry("[AI] chose " + bz.get(t.idx()).name()));
					return picked;
				}
				return showBreakZoneSelectDialog(eligible, bz, maxCount, upTo, title);
			}

			@Override public void cancelSummonOnStack() {
				logEntry("[ActionResolver] Cancel Summon on stack — not yet implemented");
			}

			@Override public void forceTargetToBreakZone(ForwardTarget t) {
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) breakP1Forward(t.idx()); else breakP2Forward(t.idx()); }
					case BACKUP  -> { if (t.isP1()) breakP1BackupSlot(t.idx()); else breakP2BackupSlot(t.idx()); }
					case MONSTER -> { if (t.isP1()) breakP1MonsterSlot(t.idx()); else breakP2MonsterSlot(t.idx()); }
				}
			}

			@Override public void opponentMillCards(int count) {
				java.util.Deque<CardData> deck = gameState.getP2MainDeck();
				JLayeredPane lp    = frame.getRootPane().getLayeredPane();
				Point start = SwingUtilities.convertPoint(
						p2DeckLabel, p2DeckLabel.getWidth() / 2, p2DeckLabel.getHeight() / 2, lp);
				Point end   = SwingUtilities.convertPoint(
						p2BreakLabel, p2BreakLabel.getWidth() / 2, p2BreakLabel.getHeight() / 2, lp);
				BufferedImage img = CardAnimation.toARGB(
						loadCardbackImage(), CardAnimation.CARD_W, CardAnimation.CARD_H);
				int milled = 0;
				for (int i = 0; i < count && !deck.isEmpty(); i++) {
					CardData card = deck.pop();
					addToP2BreakZone(card);
					logEntry("[P2] Mill: \"" + card.name() + "\" → Break Zone");
					cardSlideAnimator.startSlide(img, start, end, i * 5);
					milled++;
				}
				if (milled > 0) {
					refreshP2DeckLabel();
					refreshP2BreakLabel();
				}
			}

			@Override public void millCards(int count) {
				java.util.Deque<CardData> deck = gameState.getP1MainDeck();
				JLayeredPane lp    = frame.getRootPane().getLayeredPane();
				Point start = SwingUtilities.convertPoint(
						p1DeckLabel, p1DeckLabel.getWidth() / 2, p1DeckLabel.getHeight() / 2, lp);
				Point end   = SwingUtilities.convertPoint(
						p1BreakLabel, p1BreakLabel.getWidth() / 2, p1BreakLabel.getHeight() / 2, lp);
				BufferedImage img = CardAnimation.toARGB(
						loadCardbackImage(), CardAnimation.CARD_W, CardAnimation.CARD_H);
				int milled = 0;
				for (int i = 0; i < count && !deck.isEmpty(); i++) {
					CardData card = deck.pop();
					addToP1BreakZone(card);
					logEntry("[P1] Mill: \"" + card.name() + "\" → Break Zone");
					cardSlideAnimator.startSlide(img, start, end, i * 5);
					milled++;
				}
				if (milled > 0) {
					refreshP1DeckLabel();
					refreshP1BreakLabel();
				}
			}

			@Override public void revealOpponentHand() {
				java.util.List<CardData> hand = gameState.getP2Hand();
				if (hand.isEmpty()) {
					logEntry("Opponent's hand is empty.");
					return;
				}
				StringBuilder sb = new StringBuilder("Opponent's hand revealed: ");
				for (int i = 0; i < hand.size(); i++) {
					if (i > 0) sb.append(", ");
					sb.append(hand.get(i).name());
				}
				logEntry(sb.toString());

				JDialog dlg = new JDialog(frame, "Opponent's Hand (" + hand.size() + " cards)", false);
				dlg.setResizable(false);
				dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

				JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
				for (CardData cd : hand) {
					JLabel lbl = new JLabel("...", SwingConstants.CENTER);
					lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
					lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
					lbl.setOpaque(true);
					lbl.setBackground(Color.DARK_GRAY);
					lbl.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
					lbl.addMouseListener(new MouseAdapter() {
						@Override public void mouseEntered(MouseEvent e) { showZoomAt(cd.imageUrl()); }
						@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
					});
					new SwingWorker<ImageIcon, Void>() {
						@Override protected ImageIcon doInBackground() throws Exception {
							Image img = ImageCache.load(cd.imageUrl());
							return img == null ? null
									: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
						}
						@Override protected void done() {
							try {
								ImageIcon icon = get();
								if (icon != null) { lbl.setIcon(icon); lbl.setText(null); }
							} catch (InterruptedException | ExecutionException ignored) {}
						}
					}.execute();

					JPanel wrapper = new JPanel(new BorderLayout(0, 4));
					wrapper.setBackground(cardsPanel.getBackground());
					JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
					nameLabel.setFont(FontLoader.loadPixelNESFont(9));
					nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
					wrapper.add(lbl,       BorderLayout.CENTER);
					wrapper.add(nameLabel, BorderLayout.SOUTH);
					cardsPanel.add(wrapper);
				}

				JScrollPane scrollPane = new JScrollPane(cardsPanel,
						JScrollPane.VERTICAL_SCROLLBAR_NEVER,
						JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
				scrollPane.setPreferredSize(new Dimension(
						Math.min(hand.size() * (CARD_W + 16) + 16, 900), CARD_H + 60));

				int[] countdown = { 10 };
				JLabel countdownLabel = new JLabel("Closing in 10...", SwingConstants.CENTER);
				countdownLabel.setFont(FontLoader.loadPixelNESFont(10));

				JButton okBtn = new JButton("OK");
				okBtn.setFont(FontLoader.loadPixelNESFont(11));
				okBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

				JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
				south.add(countdownLabel);
				south.add(okBtn);
				south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

				dlg.getContentPane().setLayout(new BorderLayout(0, 4));
				dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
				dlg.getContentPane().add(south,      BorderLayout.SOUTH);
				dlg.pack();
				dlg.setLocationRelativeTo(frame);
				dlg.setVisible(true);

				javax.swing.Timer[] timerRef = { null };
				timerRef[0] = new javax.swing.Timer(1000, null);
				timerRef[0].addActionListener(te -> {
					countdown[0]--;
					if (countdown[0] <= 0) { timerRef[0].stop(); hideZoom(); dlg.dispose(); }
					else countdownLabel.setText("Closing in " + countdown[0] + "...");
				});
				timerRef[0].start();
			}

			@Override public void revealTopDeckCard(java.util.List<RevealClause> clauses, boolean opponentDeck) {
				if (!isP1) {
					logEntry("[P2] Reveal top deck card — not yet implemented for P2");
					return;
				}
				java.util.Deque<CardData> deck = opponentDeck
						? gameState.getP2MainDeck()
						: gameState.getP1MainDeck();
				String deckLabel = opponentDeck ? "opponent's deck" : "your deck";
				if (deck.isEmpty()) {
					logEntry("Reveal: " + deckLabel + " is empty.");
					return;
				}
				CardData card = deck.pollFirst();
				logEntry("Revealed from " + deckLabel + ": " + card.name() + " (" + card.type() + ")");

				// When the only applicable clause is "castSummonFree" and the card is a Summon,
				// show Decline/OK buttons so the player can choose whether to cast it.
				boolean castFreeApplicable = card.isSummon() &&
						clauses.stream().anyMatch(c -> "castSummonFree".equals(c.cardOp()));
				boolean[] activated = {false};

				JDialog dlg = new JDialog(frame, "Reveal", true);
				dlg.setResizable(false);
				dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

				JLabel cardLabel = new JLabel("...", SwingConstants.CENTER);
				cardLabel.setPreferredSize(new Dimension(CARD_W, CARD_H));
				cardLabel.setMinimumSize(new Dimension(CARD_W, CARD_H));
				cardLabel.setOpaque(true);
				cardLabel.setBackground(Color.DARK_GRAY);
				cardLabel.setBorder(BorderFactory.createLineBorder(new Color(160, 110, 220), 1));
				cardLabel.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) { showZoomAt(card.imageUrl()); }
					@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				});
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(card.imageUrl());
						return img == null ? null
								: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
					}
					@Override protected void done() {
						try {
							ImageIcon icon = get();
							if (icon != null) { cardLabel.setIcon(icon); cardLabel.setText(null); }
						} catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();

				JPanel wrapper = new JPanel(new BorderLayout(0, 4));
				wrapper.setBorder(BorderFactory.createEmptyBorder(8, 8, 0, 8));
				JLabel nameLabel = new JLabel(card.name(), SwingConstants.CENTER);
				nameLabel.setFont(FontLoader.loadPixelNESFont(9));
				nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
				wrapper.add(cardLabel,  BorderLayout.CENTER);
				wrapper.add(nameLabel,  BorderLayout.SOUTH);

				JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
				south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
				if (castFreeApplicable) {
					JButton declineBtn = new JButton("Decline");
					declineBtn.setFont(FontLoader.loadPixelNESFont(11));
					declineBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });
					JButton okBtn = new JButton("OK");
					okBtn.setFont(FontLoader.loadPixelNESFont(11));
					okBtn.addActionListener(ae -> { activated[0] = true; hideZoom(); dlg.dispose(); });
					south.add(declineBtn);
					south.add(okBtn);
				} else {
					JButton okBtn = new JButton("OK");
					okBtn.setFont(FontLoader.loadPixelNESFont(11));
					okBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });
					south.add(okBtn);
				}

				dlg.getContentPane().setLayout(new BorderLayout(0, 4));
				dlg.getContentPane().add(wrapper, BorderLayout.CENTER);
				dlg.getContentPane().add(south,   BorderLayout.SOUTH);
				dlg.pack();
				dlg.setLocationRelativeTo(frame);
				dlg.setVisible(true); // modal — blocks until dismissed

				// Find the first matching clause and execute its action
				for (RevealClause clause : clauses) {
					if (!clause.condition().test(card)) continue;
					logEntry("Condition matched for " + card.name());
					if (clause.cardOp() != null) {
						switch (clause.cardOp()) {
							case "playOntoField" -> {
								logEntry(card.name() + " played from reveal onto field");
								if (card.isBackup())       placeCardInFirstBackupSlot(card);
								else if (card.isMonster()) placeCardInMonsterZone(card);
								else                       placeCardInForwardZone(card);
							}
							case "playOntoFieldDull" -> {
								logEntry(card.name() + " played from reveal onto field (dull)");
								if (card.isBackup()) {
									placeCardInFirstBackupSlot(card);
								} else if (card.isMonster()) {
									placeCardInMonsterZone(card);
									int idx = p1MonsterCards.size() - 1;
									p1MonsterStates.set(idx, CardState.DULL);
									refreshP1MonsterSlot(idx);
								} else {
									placeCardInForwardZone(card);
									dullP1Forward(p1ForwardCards.size() - 1);
								}
							}
							case "addToHand" -> {
								gameState.getP1Hand().add(card);
								animateCardDraw(true, 1);
								logEntry(card.name() + " added to hand from reveal");
								refreshP1HandLabel();
							}
							case "putToBreakZone" -> {
								addToP1BreakZone(card);
								logEntry(card.name() + " put into Break Zone from reveal");
								refreshP1BreakLabel();
							}
							case "castSummonFree" -> {
								if (!activated[0]) {
									logEntry(card.name() + " — free cast declined, returned to top of deck");
									deck.addFirst(card);
									if (opponentDeck) refreshP2DeckLabel(); else refreshP1DeckLabel();
									return;
								}
								logEntry(card.name() + " — cast for free from reveal");
								showSummonOnStack(card);
							}
						}
					} else {
						// Standalone effect — return card to top of appropriate deck first
						// so any subsequent draw includes it
						deck.addFirst(card);
						if (opponentDeck) refreshP2DeckLabel(); else refreshP1DeckLabel();
						clause.effect().accept(this);
					}
					if (opponentDeck) refreshP2DeckLabel(); else refreshP1DeckLabel();
					return;
				}
				// No clause matched — put card back on top
				logEntry("No condition matched — returning " + card.name() + " to top of " + deckLabel);
				deck.addFirst(card);
				if (opponentDeck) refreshP2DeckLabel(); else refreshP1DeckLabel();
			}

			@Override public void playCharacterFromHand(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, int costVal, String costCmp, int costVal2,
					String jobFilter, String cardNameFilter, String categoryFilter,
					String elementFilter, String excludeName, boolean entersDull, String excludeElement,
					boolean suppressAutoAbility) {
				java.util.List<CardData> hand = gameState.getP1Hand();
				java.util.List<Integer> eligible = new ArrayList<>();
				for (int i = 0; i < hand.size(); i++) {
					CardData card = hand.get(i);
					if (card.isForward()  && !inclForwards) continue;
					if (card.isBackup()   && !inclBackups)  continue;
					if (card.isMonster()  && !inclMonsters) continue;
					if (card.isSummon()) continue;
					boolean costOk = meetsCostConstraint(card.cost(), costVal, costCmp)
					               || (costVal2 >= 0 && card.cost() == costVal2);
					if (!costOk) continue;
					// Job+name: OR when both are set; AND otherwise
					boolean passesNameJob = (jobFilter == null && cardNameFilter == null)
						|| (jobFilter != null && cardNameFilter != null
							? meetsJobFilter(card, jobFilter) || meetsCardNameFilter(card, cardNameFilter)
							: meetsJobFilter(card, jobFilter) && meetsCardNameFilter(card, cardNameFilter));
					if (!passesNameJob) continue;
					if (!meetsCategoryFilter(card, categoryFilter)) continue;
					if (!meetsElementFilter(card, elementFilter)) continue;
					if (!meetsElementExclusion(card, excludeElement)) continue;
					if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
					eligible.add(i);
				}
				if (eligible.isEmpty()) {
					logEntry("No eligible cards in hand to play.");
					markEffectFizzled();
					return;
				}
				java.util.List<CardData> candidates = new ArrayList<>();
				for (int i : eligible) candidates.add(hand.get(i));
				int listIdx = showCardImageChooser(candidates, "Play a card onto the field", true, false);
				if (listIdx < 0) { markEffectFizzled(); return; }
				int handIdx = eligible.get(listIdx);
				CardData card = hand.remove(handIdx);
				logEntry(card.name() + " played from hand onto field" + (entersDull ? " (dull)" : "")
						+ (suppressAutoAbility ? " (no ETF auto-ability)" : ""));
				if (suppressAutoAbility) suppressAutoAbilityForNextCard = true;
				if (card.isBackup()) {
					placeCardInFirstBackupSlot(card);
				} else if (card.isMonster()) {
					placeCardInMonsterZone(card);
				} else {
					placeCardInForwardZone(card);
					if (entersDull) {
						int newIdx = p1ForwardCards.size() - 1;
						p1ForwardStates.set(newIdx, CardState.DULL);
						refreshP1ForwardSlot(newIdx);
					}
				}
				refreshP1HandLabel();
			}

			@Override public void damageTarget(ForwardTarget t, int amount) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) return;
				if (t.isP1()) damageP1Forward(t.idx(), amount);
				else          damageP2Forward(t.idx(), amount);
			}

			@Override public void gainCrystal(int count) {
				if (isP1) gameState.addP1Crystals(count);
				else      gameState.addP2Crystals(count);
				refreshCrystalDisplays();
			}

			@Override public void damageFieldForwardByName(String cardName, int amount) {
				for (int i = 0; i < p1ForwardCards.size(); i++) {
					if (p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) {
						damageP1Forward(i, amount);
						return;
					}
				}
				for (int i = 0; i < p2ForwardCards.size(); i++) {
					if (p2ForwardCards.get(i).name().equalsIgnoreCase(cardName)) {
						damageP2Forward(i, amount);
						return;
					}
				}
				logEntry("[ActionResolver] damageFieldForwardByName: \"" + cardName + "\" not found on field");
			}

			@Override public void activateTarget(ForwardTarget t) {
				switch (t.zone()) {
					case FORWARD -> {
						int i = t.idx();
						if (t.isP1()) { if (i < p1ForwardCards.size()) { p1ForwardStates.set(i, CardState.ACTIVE); logEntry(p1Forward(i).name() + " is activated"); refreshP1ForwardSlot(i); } }
						else          { if (i < p2ForwardCards.size()) { p2ForwardStates.set(i, CardState.ACTIVE); logEntry("[P2] " + p2ForwardCards.get(i).name() + " is activated"); refreshP2ForwardSlot(i); } }
					}
					case BACKUP -> {
						int i = t.idx();
						if (t.isP1()) { if (i < p1BackupCards.length && p1BackupCards[i] != null) { p1BackupStates[i] = CardState.ACTIVE; logEntry(p1BackupCards[i].name() + " is activated"); refreshP1BackupSlot(i); } }
						else          { if (i < p2BackupCards.length && p2BackupCards[i] != null) { p2BackupStates[i] = CardState.ACTIVE; logEntry("[P2] " + p2BackupCards[i].name() + " is activated"); refreshP2BackupSlot(i); } }
					}
					case MONSTER -> {
						int i = t.idx();
						if (t.isP1()) { if (i < p1MonsterCards.size()) { p1MonsterStates.set(i, CardState.ACTIVE); logEntry(p1MonsterCards.get(i).name() + " is activated"); refreshP1MonsterSlot(i); } }
						else          { if (i < p2MonsterCards.size()) { p2MonsterStates.set(i, CardState.ACTIVE); logEntry("[P2] " + p2MonsterCards.get(i).name() + " is activated"); refreshP2MonsterSlot(i); } }
					}
				}
			}

			@Override public void dullTarget(ForwardTarget t) {
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) dullP1Forward(t.idx()); else dullP2Forward(t.idx()); }
					case BACKUP  -> {
						int i = t.idx();
						if (t.isP1()) { if (i < p1BackupCards.length && p1BackupCards[i] != null) { p1BackupStates[i] = CardState.DULL; logEntry(p1BackupCards[i].name() + " is dulled"); refreshP1BackupSlot(i); } }
						else          { if (i < p2BackupCards.length && p2BackupCards[i] != null) { p2BackupStates[i] = CardState.DULL; logEntry("[P2] " + p2BackupCards[i].name() + " is dulled"); refreshP2BackupSlot(i); } }
					}
					case MONSTER -> {
						int i = t.idx();
						if (t.isP1()) { if (i < p1MonsterCards.size()) { p1MonsterStates.set(i, CardState.DULL); logEntry(p1MonsterCards.get(i).name() + " is dulled"); refreshP1MonsterSlot(i); } }
						else          { if (i < p2MonsterCards.size()) { p2MonsterStates.set(i, CardState.DULL); logEntry("[P2] " + p2MonsterCards.get(i).name() + " is dulled"); refreshP2MonsterSlot(i); } }
					}
				}
			}

			@Override public void freezeTarget(ForwardTarget t) {
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) freezeP1Forward(t.idx()); else freezeP2Forward(t.idx()); }
					case BACKUP  -> {
						int i = t.idx();
						if (t.isP1()) { if (i < p1BackupCards.length && p1BackupCards[i] != null) { p1BackupFrozen[i] = true; logEntry(p1BackupCards[i].name() + " is frozen"); refreshP1BackupSlot(i); } }
						else          { if (i < p2BackupCards.length && p2BackupCards[i] != null) { p2BackupFrozen[i] = true; logEntry("[P2] " + p2BackupCards[i].name() + " is frozen"); refreshP2BackupSlot(i); } }
					}
					case MONSTER -> {
						int i = t.idx();
						if (t.isP1()) { if (i < p1MonsterCards.size()) { p1MonsterFrozen.set(i, true); logEntry(p1MonsterCards.get(i).name() + " is frozen"); refreshP1MonsterSlot(i); } }
						else          { if (i < p2MonsterCards.size()) { p2MonsterFrozen.set(i, true); logEntry("[P2] " + p2MonsterCards.get(i).name() + " is frozen"); refreshP2MonsterSlot(i); } }
					}
				}
			}

			@Override public void dullAndFreezeTarget(ForwardTarget t) { dullTarget(t); freezeTarget(t); }

			@Override public void breakTarget(ForwardTarget t) {
				CardData breakCard = fieldCardData(t);
				if (breakCard != null && cannotBeBrokenSet.contains(breakCard)) {
					logEntry((t.isP1() ? "" : "[P2] ") + breakCard.name() + " cannot be broken (protected until end of turn)");
					return;
				}
				if (breakCard != null && cannotBeBrokenByNonDmgSet.contains(breakCard)) {
					logEntry((t.isP1() ? "" : "[P2] ") + breakCard.name() + " cannot be broken by this effect (protected from non-damage breaks until end of turn)");
					return;
				}
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) breakP1Forward(t.idx()); else breakP2Forward(t.idx()); }
					case BACKUP  -> {
						int i = t.idx();
						CardData[] cards = t.isP1() ? p1BackupCards : p2BackupCards;
						CardState[] states = t.isP1() ? p1BackupStates : p2BackupStates;
						if (i >= cards.length || cards[i] == null) return;
						CardData c = cards[i];
						String prefix = t.isP1() ? "" : "[P2] ";
						logEntry(prefix + c.name() + " is broken");
						(t.isP1() ? gameState.getP1BreakZone() : gameState.getP2BreakZone()).add(c);
						cards[i] = null; states[i] = CardState.ACTIVE;
						if (t.isP1()) {
							p1BackupUrls[i] = null;
							if (p1BackupLabels[i] != null) { p1BackupLabels[i].setIcon(null); p1BackupLabels[i].setText(null); }
							refreshP1BreakLabel();
						} else {
							p2BackupUrls[i] = null;
							if (p2BackupLabels[i] != null) { p2BackupLabels[i].setIcon(null); p2BackupLabels[i].setText(null); }
							refreshP2BreakLabel();
						}
					}
					case MONSTER -> {
						int i = t.idx();
						java.util.List<CardData> cards = t.isP1() ? p1MonsterCards : p2MonsterCards;
						if (i >= cards.size()) return;
						CardData c = cards.get(i);
						String prefix = t.isP1() ? "" : "[P2] ";
						logEntry(prefix + c.name() + " is broken");
						(t.isP1() ? gameState.getP1BreakZone() : gameState.getP2BreakZone()).add(c);
						cards.remove(i);
						(t.isP1() ? p1MonsterStates : p2MonsterStates).remove(i);
						(t.isP1() ? p1MonsterFrozen : p2MonsterFrozen).remove(i);
						(t.isP1() ? p1MonsterPlayedOnTurn : p2MonsterPlayedOnTurn).remove(i);
						(t.isP1() ? p1MonsterUrls : p2MonsterUrls).remove(i);
						JLabel lbl = (t.isP1() ? p1MonsterLabels : p2MonsterLabels).remove(i);
						JPanel panel = t.isP1() ? p1MonsterPanel : p2MonsterPanel;
						panel.remove(lbl); panel.revalidate(); panel.repaint();
						if (t.isP1()) refreshP1BreakLabel(); else refreshP2BreakLabel();
					}
				}
			}

			@Override public void removeTargetFromGame(ForwardTarget t) {
				switch (t.zone()) {
					case FORWARD -> { if (t.isP1()) removeP1ForwardFromGame(t.idx()); else removeP2ForwardFromGame(t.idx()); }
					case BACKUP  -> {
						int i = t.idx();
						CardData[] cards = t.isP1() ? p1BackupCards : p2BackupCards;
						CardState[] states = t.isP1() ? p1BackupStates : p2BackupStates;
						if (i >= cards.length || cards[i] == null) return;
						logEntry((t.isP1() ? "" : "[P2] ") + cards[i].name() + " → Removed From Game");
						if (t.isP1()) gameState.addToP1PermanentRfp(cards[i]); else gameState.addToP2PermanentRfp(cards[i]);
						cards[i] = null; states[i] = CardState.ACTIVE;
						if (t.isP1()) refreshP1BackupSlot(i); else refreshP2BackupSlot(i);
					}
					case MONSTER -> {
						int i = t.idx();
						java.util.List<CardData> cards = t.isP1() ? p1MonsterCards : p2MonsterCards;
						if (i >= cards.size()) return;
						CardData c = cards.get(i);
						logEntry((t.isP1() ? "" : "[P2] ") + c.name() + " → Removed From Game");
						if (t.isP1()) gameState.addToP1PermanentRfp(c); else gameState.addToP2PermanentRfp(c);
						cards.remove(i);
						(t.isP1() ? p1MonsterStates : p2MonsterStates).remove(i);
						(t.isP1() ? p1MonsterFrozen : p2MonsterFrozen).remove(i);
						(t.isP1() ? p1MonsterPlayedOnTurn : p2MonsterPlayedOnTurn).remove(i);
						(t.isP1() ? p1MonsterUrls : p2MonsterUrls).remove(i);
						JLabel lbl = (t.isP1() ? p1MonsterLabels : p2MonsterLabels).remove(i);
						JPanel panel = t.isP1() ? p1MonsterPanel : p2MonsterPanel;
						panel.remove(lbl); panel.revalidate(); panel.repaint();
					}
				}
			}

			@Override public void removeTopCardsOfDeckFromGame(int count) {
				java.util.Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
				for (int i = 0; i < count && !deck.isEmpty(); i++) {
					CardData c = deck.pollFirst();
					if (isP1) gameState.addToP1PermanentRfp(c); else gameState.addToP2PermanentRfp(c);
					logEntry(c.name() + " → Removed From Game (top of deck)");
				}
				if (isP1) refreshP1DeckLabel(); else refreshP2DeckLabel();
			}

			@Override public void shuffleDeck() {
				java.util.Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
				java.util.List<CardData> list = new java.util.ArrayList<>(deck);
				java.util.Collections.shuffle(list);
				deck.clear();
				deck.addAll(list);
				if (isP1) refreshP1DeckLabel(); else refreshP2DeckLabel();
				logEntry("Shuffled deck");
			}

			@Override public void playTargetOntoField(ForwardTarget t) {
				java.util.List<CardData> bz = t.isP1() ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
				if (t.idx() >= bz.size()) return;
				CardData card = bz.remove(t.idx());
				String src = t.isP1() ? "Break Zone" : "opponent's Break Zone";
				logEntry(card.name() + " played from " + src + " onto field");
				if (t.isP1()) {
					if (card.isBackup())       placeCardInFirstBackupSlot(card);
					else if (card.isMonster()) placeCardInMonsterZone(card);
					else                       placeCardInForwardZone(card);
				} else {
					if (card.isBackup())       placeP2CardInFirstBackupSlot(card);
					else if (card.isMonster()) placeP2CardInMonsterZone(card);
					else                       placeP2CardInForwardZone(card);
				}
				if (t.isP1()) refreshP1BreakLabel(); else refreshP2BreakLabel();
			}

			@Override public void addTargetToHand(ForwardTarget t) {
				java.util.List<CardData> bz = t.isP1() ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
				if (t.idx() >= bz.size()) return;
				CardData card = bz.remove(t.idx());
				gameState.getP1Hand().add(card);
				logEntry(card.name() + (t.isP1() ? " returned from Break Zone to hand" : " taken from opponent's Break Zone to hand"));
				if (t.isP1()) refreshP1BreakLabel(); else refreshP2BreakLabel();
				refreshP1HandLabel();
			}

			@Override public void boostTarget(ForwardTarget t, int amount,
					java.util.EnumSet<CardData.Trait> traits) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) return;
				if (t.isP1()) {
					int idx = t.idx();
					if (idx >= p1ForwardCards.size()) return;
					p1ForwardPowerBoost.set(idx, p1ForwardPowerBoost.get(idx) + amount);
					p1ForwardTempTraits.get(idx).addAll(traits);
					logEntry(p1Forward(idx).name() + " gains +" + amount + " power until end of turn");
					refreshP1ForwardSlot(idx);
				} else {
					int idx = t.idx();
					if (idx >= p2ForwardCards.size()) return;
					p2ForwardPowerBoost.set(idx, p2ForwardPowerBoost.get(idx) + amount);
					p2ForwardTempTraits.get(idx).addAll(traits);
					logEntry("[P2] " + p2ForwardCards.get(idx).name() + " gains +" + amount + " power until end of turn");
					refreshP2ForwardSlot(idx);
				}
			}

			@Override public void boostSourceForward(CardData source, int amount,
					java.util.EnumSet<CardData.Trait> traits) {
				for (int i = 0; i < p1ForwardCards.size(); i++) {
					if (p1ForwardCards.get(i).name().equals(source.name())) {
						p1ForwardPowerBoost.set(i, p1ForwardPowerBoost.get(i) + amount);
						p1ForwardTempTraits.get(i).addAll(traits);
						logEntry(source.name() + " gains +" + amount + " power until end of turn");
						refreshP1ForwardSlot(i);
						return;
					}
				}
			}

			@Override public void doubleSourceForwardPower(CardData source,
					java.util.EnumSet<CardData.Trait> traits) {
				for (int i = 0; i < p1ForwardCards.size(); i++) {
					if (p1ForwardCards.get(i).name().equals(source.name())) {
						int current = effectiveP1ForwardPower(i);
						p1ForwardPowerBoost.set(i, p1ForwardPowerBoost.get(i) + current);
						p1ForwardTempTraits.get(i).addAll(traits);
						logEntry(source.name() + " — power doubled to " + (current * 2) + " until end of turn");
						refreshP1ForwardSlot(i);
						return;
					}
				}
			}

			@Override public void addPendingMainPhase1Effect(Consumer<GameContext> effect) {
				pendingMainPhase1Effects.add(effect);
			}

			@Override public void setTargetPower(ForwardTarget t, int power) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				int idx = t.idx();
				if (t.isP1()) {
					if (idx >= p1ForwardCards.size()) return;
					int base = p1ForwardCards.get(idx).power();
					p1ForwardPowerReduction.set(idx, 0);
					p1ForwardPowerBoost.set(idx, power - base);
					logEntry(p1Forward(idx).name() + " power becomes " + power + " until end of turn");
					refreshP1ForwardSlot(idx);
				} else {
					if (idx >= p2ForwardCards.size()) return;
					int base = p2ForwardCards.get(idx).power();
					p2ForwardPowerReduction.set(idx, 0);
					p2ForwardPowerBoost.set(idx, power - base);
					logEntry("[P2] " + p2ForwardCards.get(idx).name() + " power becomes " + power + " until end of turn");
					refreshP2ForwardSlot(idx);
				}
			}

			@Override public void placeCounters(CardData card, String counterName, int count) {
				gameState.placeCounters(card, counterName, count);
				Map<String, Integer> all = gameState.getCountersMap(card);
				logEntry(card.name() + " — placed " + count + " " + counterName
						+ " Counter(s)  [now: " + all + "]");
			}

			@Override public int getCounters(CardData card, String counterName) {
				return gameState.getCounters(card, counterName);
			}

			@Override public void lookAtTopDeck(LookConfig config) {
				lookDialogs().show(config, isP1);
			}

			@Override public void reduceTarget(ForwardTarget t, int amount,
					java.util.EnumSet<CardData.Trait> traits) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) return;
				if (t.isP1()) {
					int idx = t.idx();
					if (idx >= p1ForwardCards.size()) return;
					p1ForwardPowerReduction.set(idx, p1ForwardPowerReduction.get(idx) + amount);
					p1ForwardRemovedTraits.get(idx).addAll(traits);
					int effPow = effectiveP1ForwardPower(idx);
					logEntry(p1Forward(idx).name() + " loses " + (amount > 0 ? amount + " power" : "")
							+ (!traits.isEmpty() ? (amount > 0 ? " and " : "") + traits : "") + " until end of turn");
					if (effPow <= 0) {
						logEntry(p1Forward(idx).name() + " reduced to 0 power → Break Zone");
						breakP1Forward(idx);
					} else {
						refreshP1ForwardSlot(idx);
					}
				} else {
					int idx = t.idx();
					if (idx >= p2ForwardCards.size()) return;
					p2ForwardPowerReduction.set(idx, p2ForwardPowerReduction.get(idx) + amount);
					p2ForwardRemovedTraits.get(idx).addAll(traits);
					int effPow = effectiveP2ForwardPower(idx);
					logEntry("[P2] " + p2ForwardCards.get(idx).name() + " loses "
							+ (amount > 0 ? amount + " power" : "")
							+ (!traits.isEmpty() ? (amount > 0 ? " and " : "") + traits : "") + " until end of turn");
					if (effPow <= 0) {
						logEntry("[P2] " + p2ForwardCards.get(idx).name() + " reduced to 0 power → Break Zone");
						breakP2Forward(idx);
					} else {
						refreshP2ForwardSlot(idx);
					}
				}
			}

			@Override public void reduceSourceForward(CardData source, int amount,
					java.util.EnumSet<CardData.Trait> traits) {
				for (int i = 0; i < p1ForwardCards.size(); i++) {
					if (p1ForwardCards.get(i).name().equals(source.name())) {
						reduceTarget(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD), amount, traits);
						return;
					}
				}
			}

			@Override public int dullForwardCostPower() { return lastDullForwardCostPower; }

			@Override public int highestP1ForwardPower() {
				int max = 0;
				for (int i = 0; i < p1ForwardCards.size(); i++)
					max = Math.max(max, effectiveP1ForwardPower(i));
				return max;
			}

			@Override public int fieldForwardPowerByName(String cardName) {
				for (int i = 0; i < p1ForwardCards.size(); i++)
					if (p1ForwardCards.get(i).name().equalsIgnoreCase(cardName))
						return effectiveP1ForwardPower(i);
				for (int i = 0; i < p2ForwardCards.size(); i++)
					if (p2ForwardCards.get(i).name().equalsIgnoreCase(cardName))
						return effectiveP2ForwardPower(i);
				for (int i = 0; i < p1MonsterCards.size(); i++)
					if (p1MonsterCards.get(i).name().equalsIgnoreCase(cardName))
						return effectiveP1MonsterPower(i);
				for (int i = 0; i < p2MonsterCards.size(); i++)
					if (p2MonsterCards.get(i).name().equalsIgnoreCase(cardName))
						return effectiveP2MonsterPower(i);
				logEntry("[ActionResolver] fieldForwardPowerByName: \"" + cardName + "\" not found on field");
				return -1;
			}

			@Override public int combatBlockerIdxForAttacker(String attackerName, boolean attackerIsP1) {
					if (attackerIsP1) {
						if (p2BlockedByAttacker != null && p2BlockedByAttacker.name().equalsIgnoreCase(attackerName))
							return p2BlockingIdx;
					} else {
						if (p1BlockedByAttacker != null && p1BlockedByAttacker.name().equalsIgnoreCase(attackerName))
							return p1BlockingIdx;
					}
					return -1;
				}

			@Override public int effectiveTargetPower(ForwardTarget t) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) return 0;
				if (t.zone() == ForwardTarget.CardZone.FORWARD)
					return t.isP1()
							? (t.idx() < p1ForwardCards.size() ? effectiveP1ForwardPower(t.idx()) : 0)
							: (t.idx() < p2ForwardCards.size() ? effectiveP2ForwardPower(t.idx()) : 0);
				return t.isP1()
						? (t.idx() < p1MonsterCards.size() ? effectiveP1MonsterPower(t.idx()) : 0)
						: (t.idx() < p2MonsterCards.size() ? effectiveP2MonsterPower(t.idx()) : 0);
			}

			@Override public void forceOpponentDiscard(int count) {
				if (isP1) {
					List<CardData> hand = gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						int idx = pickWorstHandCard0(hand);
						CardData d = gameState.breakP2FromHand(idx);
						if (d != null) logEntry("[P2] Discards " + d.name() + " (forced)");
					}
					refreshP2HandCountLabel();
					refreshP2BreakLabel();
				} else {
					showForcedDiscardDialog(count, true);
				}
			}

			@Override public void forceOpponentRandomDiscard(int count) {
				if (isP1) {
					List<CardData> hand = gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						int idx = (int) (Math.random() * gameState.getP2Hand().size());
						CardData d = gameState.breakP2FromHand(idx);
						if (d != null) logEntry("[P2] Randomly discards " + d.name());
					}
					refreshP2HandCountLabel();
					refreshP2BreakLabel();
				} else {
					List<CardData> hand = gameState.getP1Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						int idx = (int) (Math.random() * gameState.getP1Hand().size());
						CardData d = gameState.breakFromHand(idx);
						if (d != null) logEntry("[P1] Randomly discards " + d.name());
					}
					refreshP1HandLabel();
					refreshP1BreakLabel();
				}
			}

			@Override public void drawCardsForOpponent(int count) {
				if (isP1) {
					gameState.drawP2ToHand(count);
					animateCardDraw(false, count);
					refreshP2DeckLabel();
					refreshP2HandCountLabel();
				} else {
					gameState.drawToHand(count);
					animateCardDraw(true, count);
					refreshP1HandLabel();
					refreshP1DeckLabel();
				}
			}

			@Override public void forceOpponentRandomHandRfp(int count) {
				if (isP1) {
					List<CardData> hand = gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						if (hand.isEmpty()) break;
						int idx = (int) (Math.random() * hand.size());
						CardData d = hand.remove(idx);
						gameState.addToP2PermanentRfp(d);
						logEntry("[P2] Randomly removed from game: " + d.name());
					}
					refreshP2HandCountLabel();
				} else {
					List<CardData> hand = gameState.getP1Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						if (hand.isEmpty()) break;
						int idx = (int) (Math.random() * hand.size());
						CardData d = gameState.removeFromHand(idx);
						if (d != null) { gameState.addToP1PermanentRfp(d); logEntry("[P1] Randomly removed from game: " + d.name()); }
					}
					refreshP1HandLabel();
					refreshP1WarpZoneUI();
				}
			}

			@Override public void selectFromOpponentHandAndRfp(int count) {
				if (isP1) {
					showHandRfpSelectionDialog(gameState.getP2Hand(), count, false);
				} else {
					// AI picks highest-cost cards from P1's hand
					int actual = Math.min(count, gameState.getP1Hand().size());
					for (int i = 0; i < actual; i++) {
						List<CardData> hand = gameState.getP1Hand();
						if (hand.isEmpty()) break;
						int best = 0;
						for (int j = 1; j < hand.size(); j++)
							if (hand.get(j).cost() > hand.get(best).cost()) best = j;
						CardData d = gameState.removeFromHand(best);
						if (d != null) { gameState.addToP1PermanentRfp(d); logEntry("[P2 AI selects from P1 hand] " + d.name() + " removed from game"); }
					}
					refreshP1HandLabel();
					refreshP1WarpZoneUI();
				}
			}

			@Override public void forceOpponentHandRfp(int count) {
				if (isP1) {
					List<CardData> hand = gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						if (hand.isEmpty()) break;
						int idx = pickWorstHandCard0(hand);
						CardData d = hand.remove(idx);
						gameState.addToP2PermanentRfp(d);
						logEntry("[P2] Removes from game: " + d.name());
					}
					refreshP2HandCountLabel();
				} else {
					showHandRfpSelectionDialog(gameState.getP1Hand(), count, true);
				}
			}

			@Override public void removeNamedCardFromGame(String cardName) {
				// P1 forwards
				for (int i = 0; i < p1ForwardCards.size(); i++) {
					if (p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) { removeP1ForwardFromGame(i); return; }
				}
				// P1 backups
				for (int i = 0; i < p1BackupCards.length; i++) {
					if (p1BackupCards[i] != null && p1BackupCards[i].name().equalsIgnoreCase(cardName)) {
						logEntry(cardName + " → Removed From Game");
						gameState.addToP1PermanentRfp(p1BackupCards[i]);
						p1BackupCards[i] = null; p1BackupStates[i] = CardState.ACTIVE;
						refreshP1BackupSlot(i); refreshP1WarpZoneUI(); return;
					}
				}
				// P1 monsters
				for (int i = 0; i < p1MonsterCards.size(); i++) {
					if (p1MonsterCards.get(i).name().equalsIgnoreCase(cardName)) {
						removeTargetFromGame(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER)); return;
					}
				}
				// P2 forwards
				for (int i = 0; i < p2ForwardCards.size(); i++) {
					if (p2ForwardCards.get(i).name().equalsIgnoreCase(cardName)) { removeP2ForwardFromGame(i); return; }
				}
				// P2 backups
				for (int i = 0; i < p2BackupCards.length; i++) {
					if (p2BackupCards[i] != null && p2BackupCards[i].name().equalsIgnoreCase(cardName)) {
						logEntry("[P2] " + cardName + " → Removed From Game");
						gameState.addToP2PermanentRfp(p2BackupCards[i]);
						p2BackupCards[i] = null; p2BackupStates[i] = CardState.ACTIVE;
						refreshP2BackupSlot(i); return;
					}
				}
				// P2 monsters
				for (int i = 0; i < p2MonsterCards.size(); i++) {
					if (p2MonsterCards.get(i).name().equalsIgnoreCase(cardName)) {
						removeTargetFromGame(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER)); return;
					}
				}
				logEntry("[Warning] removeNamedCardFromGame: \"" + cardName + "\" not found on field");
			}

			@Override public void returnNamedCardToOwnersHand(String cardName) {
				for (int i = 0; i < p1ForwardCards.size(); i++) {
					if (p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) { returnP1ForwardToHand(i); return; }
				}
				for (int i = 0; i < p1BackupCards.length; i++) {
					if (p1BackupCards[i] != null && p1BackupCards[i].name().equalsIgnoreCase(cardName)) { returnP1BackupToHand(i); return; }
				}
				for (int i = 0; i < p1MonsterCards.size(); i++) {
					if (p1MonsterCards.get(i).name().equalsIgnoreCase(cardName)) { returnP1MonsterToHand(i); return; }
				}
				for (int i = 0; i < p2ForwardCards.size(); i++) {
					if (p2ForwardCards.get(i).name().equalsIgnoreCase(cardName)) { returnP2ForwardToHand(i); return; }
				}
				for (int i = 0; i < p2BackupCards.length; i++) {
					if (p2BackupCards[i] != null && p2BackupCards[i].name().equalsIgnoreCase(cardName)) { returnP2BackupToHand(i); return; }
				}
				for (int i = 0; i < p2MonsterCards.size(); i++) {
					if (p2MonsterCards.get(i).name().equalsIgnoreCase(cardName)) { returnP2MonsterToHand(i); return; }
				}
				logEntry("[Warning] returnNamedCardToOwnersHand: \"" + cardName + "\" not found on field");
			}

			@Override public void grantAttackOnceMore(String cardName) {
				for (int i = 0; i < p1ForwardCards.size(); i++) {
					if (p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) {
						p1ForwardCannotAttack.remove(i);
						refreshP1ForwardSlot(i);
						return;
					}
				}
				for (int i = 0; i < p1MonsterCards.size(); i++) {
					if (p1MonsterCards.get(i).name().equalsIgnoreCase(cardName)) {
						return;
					}
				}
				logEntry("[Warning] grantAttackOnceMore: \"" + cardName + "\" not found on P1's field");
			}

			@Override public void returnNamedCardToYourHand(String cardName) {
				if (currentResolutionIsSummon && currentBreaktouchSource != null
						&& currentBreaktouchSource.name().equalsIgnoreCase(cardName)) {
					pendingSummonReturnToHand = true;
					return;
				}
				for (int i = 0; i < p1ForwardCards.size(); i++) {
					if (p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) { returnP1ForwardToHand(i); return; }
				}
				for (int i = 0; i < p1BackupCards.length; i++) {
					if (p1BackupCards[i] != null && p1BackupCards[i].name().equalsIgnoreCase(cardName)) { returnP1BackupToHand(i); return; }
				}
				for (int i = 0; i < p1MonsterCards.size(); i++) {
					if (p1MonsterCards.get(i).name().equalsIgnoreCase(cardName)) { returnP1MonsterToHand(i); return; }
				}
				logEntry("[Warning] returnNamedCardToYourHand: \"" + cardName + "\" not found on field");
			}

			@Override public void removeFromBattle(String cardName) {
				for (int i = 0; i < p1ForwardCards.size(); i++) {
					if (p1ForwardCards.get(i).name().equalsIgnoreCase(cardName)) {
						escapedFromBattle.add(p1ForwardCards.get(i));
						return;
					}
				}
				for (int i = 0; i < p2ForwardCards.size(); i++) {
					if (p2ForwardCards.get(i).name().equalsIgnoreCase(cardName)) {
						escapedFromBattle.add(p2ForwardCards.get(i));
						return;
					}
				}
				logEntry("[Warning] removeFromBattle: \"" + cardName + "\" not found on field");
			}

			@Override public void takeExtraTurnThenLose() {
				logEntry("Effect: Take 1 more turn — you will lose at the end of that turn");
				p1ExtraTurnThenLose = true;
			}

			@Override public void drawCards(int count) {
				if (isP1) {
					gameState.drawToHand(count);
					animateCardDraw(true, count);
					refreshP1HandLabel();
					refreshP1DeckLabel();
				} else {
					gameState.drawP2ToHand(count);
					animateCardDraw(false, count);
					refreshP2DeckLabel();
					refreshP2HandCountLabel();
				}
			}

			@Override public void selfDiscard(int count) {
				if (isP1) {
					showForcedDiscardDialog(count, false);
				} else {
					List<CardData> hand = gameState.getP2Hand();
					int actual = Math.min(count, hand.size());
					for (int i = 0; i < actual; i++) {
						int idx = pickWorstHandCard0(hand);
						CardData d = gameState.breakP2FromHand(idx);
						if (d != null) logEntry("[P2] Discards " + d.name());
					}
					refreshP2HandCountLabel();
					refreshP2BreakLabel();
				}
			}

			@Override public void selfDiscardEntireHand() {
				if (isP1) {
					List<CardData> hand = gameState.getP1Hand();
					for (int i = hand.size() - 1; i >= 0; i--) {
						CardData d = gameState.breakFromHand(i);
						if (d != null) logEntry("Discards " + d.name());
					}
					refreshP1HandLabel();
					refreshP1BreakLabel();
				} else {
					List<CardData> hand = gameState.getP2Hand();
					for (int i = hand.size() - 1; i >= 0; i--) {
						CardData d = gameState.breakP2FromHand(i);
						if (d != null) logEntry("[P2] Discards " + d.name());
					}
					refreshP2HandCountLabel();
					refreshP2BreakLabel();
				}
			}

			@Override public void dealDamageToOpponent(int amount) {
				for (int i = 0; i < amount; i++) {
					if (isP1) p2TakeDamage(); else p1TakeDamage();
				}
			}

			@Override public void dealDamageToSelf(int amount) {
				for (int i = 0; i < amount; i++) {
					if (isP1) p1TakeDamage(); else p2TakeDamage();
				}
			}

			@Override
			public void applyMassFieldEffect(GameContext.MassAction action,
					boolean forwards, boolean backups, boolean monsters,
					boolean opponentOnly, boolean selfOnly,
					String element, int costVal, String costCmp, int excludeCostVal,
					String job, String category) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				if (touchP1) {
					if (forwards || monsters) {
						for (int i = p1ForwardCards.size() - 1; i >= 0; i--) {
							CardData c = p1Forward(i);
							if (!forwards && !c.alsoCountsAsMonster()) continue;
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (!meetsJobFilter(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							switch (action) {
								case BREAK          -> breakP1Forward(i);
								case DULL           -> dullP1Forward(i);
								case FREEZE         -> freezeP1Forward(i);
								case DULL_AND_FREEZE -> { dullP1Forward(i); freezeP1Forward(i); }
								case ACTIVATE       -> { p1ForwardStates.set(i, CardState.ACTIVE); refreshP1ForwardSlot(i); }
								case RETURN_TO_HAND -> returnP1ForwardToHand(i);
							}
						}
					}
					if (backups) {
						for (int i = 0; i < p1BackupCards.length; i++) {
							if (p1BackupCards[i] == null) continue;
							CardData c = p1BackupCards[i];
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (!meetsJobFilter(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							switch (action) {
								case BREAK -> {
									logEntry(c.name() + " is broken");
									addToP1BreakZone(c);
									p1BackupCards[i] = null;
									p1BackupStates[i] = CardState.ACTIVE;
									refreshP1BackupSlot(i);
									refreshP1BreakLabel();
								}
								case DULL           -> { p1BackupStates[i] = CardState.DULL;   logEntry(c.name() + " is dulled");          refreshP1BackupSlot(i); }
								case FREEZE         -> { p1BackupFrozen[i] = true;              logEntry(c.name() + " is frozen");          refreshP1BackupSlot(i); }
								case DULL_AND_FREEZE -> { p1BackupStates[i] = CardState.DULL; p1BackupFrozen[i] = true; logEntry(c.name() + " is dulled & frozen"); refreshP1BackupSlot(i); }
								case ACTIVATE       -> { p1BackupStates[i] = CardState.ACTIVE; logEntry(c.name() + " is activated");       refreshP1BackupSlot(i); }
								case RETURN_TO_HAND -> returnP1BackupToHand(i);
							}
						}
					}
					if (monsters) {
						for (int i = p1MonsterCards.size() - 1; i >= 0; i--) {
							CardData c = p1MonsterCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (!meetsJobFilter(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							switch (action) {
								case BREAK -> {
									logEntry(c.name() + " is broken");
									addToP1BreakZone(c);
									p1MonsterTempForwardPower.remove(c);
									p1MonsterCards.remove(i);
									p1MonsterStates.remove(i);
									p1MonsterFrozen.remove(i);
									p1MonsterPlayedOnTurn.remove(i);
									p1MonsterUrls.remove(i);
									JLabel lbl = p1MonsterLabels.remove(i);
									p1MonsterPanel.remove(lbl);
									p1MonsterPanel.revalidate();
									p1MonsterPanel.repaint();
									refreshP1BreakLabel();
								}
								case DULL           -> { p1MonsterStates.set(i, CardState.DULL);   logEntry(c.name() + " is dulled");          refreshP1MonsterSlot(i); }
								case FREEZE         -> { p1MonsterFrozen.set(i, true);              logEntry(c.name() + " is frozen");          refreshP1MonsterSlot(i); }
								case DULL_AND_FREEZE -> { p1MonsterStates.set(i, CardState.DULL); p1MonsterFrozen.set(i, true); logEntry(c.name() + " is dulled & frozen"); refreshP1MonsterSlot(i); }
								case ACTIVATE       -> { p1MonsterStates.set(i, CardState.ACTIVE); logEntry(c.name() + " is activated");       refreshP1MonsterSlot(i); }
								case RETURN_TO_HAND -> returnP1MonsterToHand(i);
							}
						}
					}
				}
				if (touchP2) {
					if (forwards || monsters) {
						for (int i = p2ForwardCards.size() - 1; i >= 0; i--) {
							CardData c = p2ForwardCards.get(i);
							if (!forwards && !c.alsoCountsAsMonster()) continue;
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (!meetsJobFilter(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							switch (action) {
								case BREAK          -> breakP2Forward(i);
								case DULL           -> dullP2Forward(i);
								case FREEZE         -> freezeP2Forward(i);
								case DULL_AND_FREEZE -> { dullP2Forward(i); freezeP2Forward(i); }
								case ACTIVATE       -> { p2ForwardStates.set(i, CardState.ACTIVE); refreshP2ForwardSlot(i); }
								case RETURN_TO_HAND -> returnP2ForwardToHand(i);
							}
						}
					}
					if (backups) {
						for (int i = 0; i < p2BackupCards.length; i++) {
							if (p2BackupCards[i] == null) continue;
							CardData c = p2BackupCards[i];
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							if (!meetsJobFilter(c, job)) continue;
							if (!meetsCategoryFilter(c, category)) continue;
							switch (action) {
								case BREAK -> {
									logEntry("[P2] " + c.name() + " is broken");
									addToP2BreakZone(c);
									p2BackupCards[i] = null;
									p2BackupStates[i] = CardState.ACTIVE;
									refreshP2BackupSlot(i);
									refreshP2BreakLabel();
								}
								case DULL           -> { p2BackupStates[i] = CardState.DULL;   logEntry("[P2] " + c.name() + " is dulled");          refreshP2BackupSlot(i); }
								case FREEZE         -> { p2BackupFrozen[i] = true;              logEntry("[P2] " + c.name() + " is frozen");          refreshP2BackupSlot(i); }
								case DULL_AND_FREEZE -> { p2BackupStates[i] = CardState.DULL; p2BackupFrozen[i] = true; logEntry("[P2] " + c.name() + " is dulled & frozen"); refreshP2BackupSlot(i); }
								case ACTIVATE       -> { p2BackupStates[i] = CardState.ACTIVE; logEntry("[P2] " + c.name() + " is activated");       refreshP2BackupSlot(i); }
								case RETURN_TO_HAND -> returnP2BackupToHand(i);
							}
						}
					}
					if (monsters) {
						for (int i = p2MonsterCards.size() - 1; i >= 0; i--) {
							CardData c = p2MonsterCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							switch (action) {
								case BREAK -> {
									logEntry("[P2] " + c.name() + " is broken");
									addToP2BreakZone(c);
									p2MonsterTempForwardPower.remove(c);
									p2MonsterCards.remove(i);
									p2MonsterStates.remove(i);
									p2MonsterFrozen.remove(i);
									p2MonsterPlayedOnTurn.remove(i);
									p2MonsterUrls.remove(i);
									JLabel lbl = p2MonsterLabels.remove(i);
									p2MonsterPanel.remove(lbl);
									p2MonsterPanel.revalidate();
									p2MonsterPanel.repaint();
									refreshP2BreakLabel();
								}
								case DULL           -> { p2MonsterStates.set(i, CardState.DULL);   logEntry("[P2] " + c.name() + " is dulled");          refreshP2MonsterSlot(i); }
								case FREEZE         -> { p2MonsterFrozen.set(i, true);              logEntry("[P2] " + c.name() + " is frozen");          refreshP2MonsterSlot(i); }
								case DULL_AND_FREEZE -> { p2MonsterStates.set(i, CardState.DULL); p2MonsterFrozen.set(i, true); logEntry("[P2] " + c.name() + " is dulled & frozen"); refreshP2MonsterSlot(i); }
								case ACTIVATE       -> { p2MonsterStates.set(i, CardState.ACTIVE); logEntry("[P2] " + c.name() + " is activated");       refreshP2MonsterSlot(i); }
								case RETURN_TO_HAND -> returnP2MonsterToHand(i);
							}
						}
					}
				}
			}

			@Override
			public void applyMassFieldPowerBoost(int amount, boolean inclForwards, boolean inclMonsters,
					boolean opponentOnly, boolean selfOnly,
					String element, int costVal, String costCmp, String category) {
				boolean touchP1 = isP1 ? !opponentOnly : !selfOnly;
				boolean touchP2 = isP1 ? !selfOnly     : !opponentOnly;
				if (touchP1) {
					if (inclForwards) {
						for (int i = 0; i < p1ForwardCards.size(); i++) {
							CardData c = p1Forward(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							p1ForwardPowerBoost.set(i, p1ForwardPowerBoost.get(i) + amount);
							logEntry(c.name() + " gains +" + amount + " power until end of turn");
							refreshP1ForwardSlot(i);
						}
					}
					if (inclMonsters) {
						for (int i = 0; i < p1MonsterCards.size(); i++) {
							CardData c = p1MonsterCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							logEntry(c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
				if (touchP2) {
					if (inclForwards) {
						for (int i = 0; i < p2ForwardCards.size(); i++) {
							CardData c = p2ForwardCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							p2ForwardPowerBoost.set(i, p2ForwardPowerBoost.get(i) + amount);
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
							refreshP2ForwardSlot(i);
						}
					}
					if (inclMonsters) {
						for (int i = 0; i < p2MonsterCards.size(); i++) {
							CardData c = p2MonsterCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (!CardFilters.meetsCategoryFilter(c, category)) continue;
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
			}

			@Override public void addEndOfTurnEffect(Consumer<GameContext> effect) {
				endOfTurnEffects.add(effect);
			}

			@Override public void addTempAttackTrigger(CardData card, Consumer<GameContext> effect) {
				Map<CardData, List<Consumer<GameContext>>> triggers
						= isP1 ? p1TempAttackTriggers : p2TempAttackTriggers;
				triggers.computeIfAbsent(card, k -> new ArrayList<>()).add(effect);
			}

			@Override public void addTempBlockTrigger(CardData card, Consumer<GameContext> effect) {
				Map<CardData, List<Consumer<GameContext>>> triggers
						= isP1 ? p1TempBlockTriggers : p2TempBlockTriggers;
				triggers.computeIfAbsent(card, k -> new ArrayList<>()).add(effect);
			}

			@Override public boolean abilityUserControlsCard(String cardName) {
				List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
				List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
				CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
				for (CardData c : fwds) if (c != null && c.name().equalsIgnoreCase(cardName)) return true;
				for (CardData c : mons) if (c != null && c.name().equalsIgnoreCase(cardName)) return true;
				for (CardData c : bkps) if (c != null && c.name().equalsIgnoreCase(cardName)) return true;
				return false;
			}

			@Override public void applyNextCastCostReduction(CostReductionModifier modifier) {
				activeCostReductions.add(modifier);
				endOfTurnEffects.add(ctx -> activeCostReductions.remove(modifier));
			}

			@Override public java.util.List<FieldAbility> getActiveFieldAbilities() {
				java.util.List<FieldAbility> active = new ArrayList<>();
				for (CardData c : p1ForwardCards) active.addAll(c.fieldAbilities());
				for (CardData c : p1MonsterCards)  active.addAll(c.fieldAbilities());
				for (CardData c : p1BackupCards)   if (c != null) active.addAll(c.fieldAbilities());
				for (CardData c : p2ForwardCards)  active.addAll(c.fieldAbilities());
				for (CardData c : p2MonsterCards)  active.addAll(c.fieldAbilities());
				for (CardData c : p2BackupCards)   if (c != null) active.addAll(c.fieldAbilities());
				return active;
			}

			@Override public int p1DamageCount() { return gameState.getP1DamageZone().size(); }

			@Override public int opponentHandSize() {
				return (isP1 ? gameState.getP2Hand() : gameState.getP1Hand()).size();
			}

			@Override public int countP1FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter) {
				return countP1FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, null);
			}

			@Override public int countP1FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter) {
				return countP1FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, null);
			}

			@Override public int countP1FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter) {
				int count = 0;
				if (inclForwards) for (CardData c : p1ForwardCards) {
					if (!meetsJobFilter(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				if (inclBackups) for (CardData c : p1BackupCards) {
					if (c == null) continue;
					if (!meetsJobFilter(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				if (inclMonsters) for (CardData c : p1MonsterCards) {
					if (!meetsJobFilter(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				return count;
			}

			@Override public int countP1BreakZoneCards(String cardNameFilter, String jobFilter) {
				int count = 0;
				for (CardData c : gameState.getP1BreakZone()) {
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsJobFilter(c, jobFilter)) continue;
					count++;
				}
				return count;
			}

			@Override public int countP2BreakZoneCards(String cardNameFilter, String jobFilter) {
				int count = 0;
				for (CardData c : gameState.getP2BreakZone()) {
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsJobFilter(c, jobFilter)) continue;
					count++;
				}
				return count;
			}

			@Override public int countP2FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter) {
				return countP2FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, null);
			}

			@Override public int countP2FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter) {
				return countP2FieldCards(inclForwards, inclBackups, inclMonsters, jobFilter, cardNameFilter, categoryFilter, null);
			}

			@Override public int countP2FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter, String categoryFilter, String elementFilter) {
				int count = 0;
				if (inclForwards) for (CardData c : p2ForwardCards) {
					if (!meetsJobFilter(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				if (inclBackups) for (CardData c : p2BackupCards) {
					if (c == null) continue;
					if (!meetsJobFilter(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				if (inclMonsters) for (CardData c : p2MonsterCards) {
					if (!meetsJobFilter(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					if (!meetsCategoryFilter(c, categoryFilter)) continue;
					if (elementFilter != null && !c.containsElement(elementFilter)) continue;
					count++;
				}
				return count;
			}

			@Override public boolean controlConditionMet(ControlCondition cond) {
				return MainWindow.this.controlConditionMet(cond, isP1);
			}

			@Override public boolean selfReceivedDamageThisTurn() {
				return isP1 ? p1ReceivedDamageThisTurn : p2ReceivedDamageThisTurn;
			}

			@Override public boolean selfHasSummonInBreakZone() {
				List<CardData> bz = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
				return bz.stream().anyMatch(CardData::isSummon);
			}

			@Override public int opponentDamageCount() {
				return (isP1 ? gameState.getP2DamageZone() : gameState.getP1DamageZone()).size();
			}

			@Override public int selfCardsCastThisTurn() { return p1CardsCastThisTurn; }

			@Override public int selfForwardCount() {
				return isP1 ? p1ForwardCards.size() : p2ForwardCards.size();
			}

			@Override public int opponentForwardCount() {
				return isP1 ? p2ForwardCards.size() : p1ForwardCards.size();
			}

			@Override public boolean isExBurst() { return exBurst; }
			@Override public boolean castWasPaidByBackupsOnly() { return lastCastWasPaidByBackupsOnly; }

			@Override public void makeMonsterTemporaryForward(CardData source, int power) {
				if (isP1) {
					int idx = p1MonsterCards.indexOf(source);
					if (idx < 0) return;
					p1MonsterTempForwardPower.put(source, power);
					endOfTurnEffects.add(ctx -> {
						p1MonsterTempForwardPower.remove(source);
						int stillIdx = p1MonsterCards.indexOf(source);
						if (stillIdx >= 0) refreshP1MonsterSlot(stillIdx);
					});
					refreshP1MonsterSlot(idx);
				} else {
					int idx = p2MonsterCards.indexOf(source);
					if (idx < 0) return;
					p2MonsterTempForwardPower.put(source, power);
					endOfTurnEffects.add(ctx -> {
						p2MonsterTempForwardPower.remove(source);
						int stillIdx = p2MonsterCards.indexOf(source);
						if (stillIdx >= 0) refreshP2MonsterSlot(stillIdx);
					});
					refreshP2MonsterSlot(idx);
				}
			}

			@Override public String selectJobFromDatabase() {
				return showJobSelectionDialog(isP1);
			}

			@Override public void grantJobUntilEndOfTurn(ForwardTarget t, String job) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD) return;
				if (t.isP1()) {
					int idx = t.idx();
					if (idx < 0 || idx >= p1ForwardCards.size()) return;
					p1ForwardTempJobs.set(idx, job);
					logEntry(p1Forward(idx).name() + " gains the Job [" + job + "] until end of turn");
				} else {
					int idx = t.idx();
					if (idx < 0 || idx >= p2ForwardCards.size()) return;
					p2ForwardTempJobs.set(idx, job);
					logEntry("[P2] " + p2ForwardCards.get(idx).name() + " gains the Job [" + job + "] until end of turn");
				}
			}
		};
	}

	/** Loads every distinct job name from the database and shows a sorted dropdown dialog. */
	private String showJobSelectionDialog(boolean interactive) {
		java.io.File dbFile = new java.io.File("shufflingway.db");
		if (!dbFile.exists()) {
			logEntry("[Job select] shufflingway.db not found");
			return null;
		}
		java.util.List<String> jobs = new java.util.ArrayList<>();
		try (java.sql.Connection conn = java.sql.DriverManager.getConnection(
				"jdbc:sqlite:" + dbFile.getAbsolutePath());
			 java.sql.Statement stmt = conn.createStatement();
			 java.sql.ResultSet rs   = stmt.executeQuery(
				"SELECT DISTINCT job_en FROM cards WHERE job_en IS NOT NULL AND job_en != '' ORDER BY job_en")) {
			while (rs.next()) jobs.add(rs.getString(1));
		} catch (Exception e) {
			logEntry("[Job select] DB error: " + e.getMessage());
		}
		if (jobs.isEmpty()) return null;
		if (!interactive) {
			String picked = jobs.get((int) (Math.random() * jobs.size()));
			logEntry("[AI] selected Job: " + picked);
			return picked;
		}
		String[] options = jobs.toArray(new String[0]);
		return (String) javax.swing.JOptionPane.showInputDialog(
				frame,
				"Select a Job:",
				"Select Job",
				javax.swing.JOptionPane.PLAIN_MESSAGE,
				null,
				options,
				options[0]);
	}

	/**
	 * Fires "At the end of each of your turns" field abilities for the controlling player.
	 * Called at the start of the END phase, before temporary-boost cleanup.
	 */
	private void fireFieldEndOfTurnAbilities(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		GameContext ctx = buildGameContext(isP1);
		int dmg = isP1 ? gameState.getP1DamageZone().size() : gameState.getP2DamageZone().size();
		for (CardData card : fwds) fireFieldEndOfTurnAbilitiesForCard(card, ctx, dmg);
		for (CardData card : bkps) if (card != null) fireFieldEndOfTurnAbilitiesForCard(card, ctx, dmg);
		for (CardData card : mons) fireFieldEndOfTurnAbilitiesForCard(card, ctx, dmg);
	}

	private void fireFieldEndOfTurnAbilitiesForCard(CardData card, GameContext ctx, int dmg) {
		for (FieldAbility fa : card.fieldAbilities()) {
			if (fa.damageThreshold() > 0 && dmg < fa.damageThreshold()) continue;
			Consumer<GameContext> effect =
					ActionResolver.tryParseEndOfEachTurnFieldAbility(fa.effectText(), card);
			if (effect != null) {
				logEntry("[Field] " + card.name() + " — end-of-turn: " + fa.effectText());
				effect.accept(ctx);
			}
		}
	}

	/** Fires all queued end-of-turn effects using a context for {@code isP1}, then clears the queue. */
	private void fireEndOfTurnEffects(boolean isP1) {
		if (endOfTurnEffects.isEmpty()) return;
		List<Consumer<GameContext>> pending = new ArrayList<>(endOfTurnEffects);
		endOfTurnEffects.clear();
		GameContext ctx = buildGameContext(isP1);
		pending.forEach(e -> e.accept(ctx));
	}

	// -------------------------------------------------------------------------
	// Damage modifier helpers
	// -------------------------------------------------------------------------

	/**
	 * Applies all incoming-damage modifiers for {@code idx} and returns the final amount.
	 * One-time shields (next-damage-zero, next-damage-reduction) are consumed here.
	 * {@code fromAbility} is true when the damage source is an effect/summon, false for combat.
	 * {@code unreduced} bypasses all reductions: one-shot shields are still consumed but
	 * their reduction is not applied; persistent shields stay up and also do not reduce.
	 */
	private int modifyIncomingDamage(boolean isP1, int idx, int rawAmount, boolean fromAbility, boolean unreduced) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		if (idx >= fwds.size()) return rawAmount;
		CardData card = fwds.get(idx);
		int amount = rawAmount;

		// Incoming damage increase (debuff) — applied regardless of reduction-disabled flag
		if (incomingDmgIncreaseMap.containsKey(card))
			amount += incomingDmgIncreaseMap.get(card);

		// Source-based nullification (these block damage by type of source, not by reducing amount)
		if (fromAbility) {
			// Nullify all ability/summon damage
			if (nullifyAbilityDmgSet.contains(card)) return 0;
			// Nullify ability-only damage (not Summons)
			if (!currentResolutionIsSummon && nullifyAbilityOnlyDmgSet.contains(card)) return 0;
			// Passive field ability: nullify Summon-only damage
			if (currentResolutionIsSummon) {
				for (FieldAbility fa : card.fieldAbilities()) {
					java.util.regex.Matcher m = FA_NULLIFY_SUMMON_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) return 0;
				}
			}
		}

		if (unreduced) {
			// Consume one-shot shields so they are spent, but do not apply any reduction.
			// Persistent shields ("until end of turn") remain in place unchanged.
			nextIncomingDmgZeroSet.remove(card);
			nextIncomingDmgReduceMap.remove(card);
			if (fromAbility) nextAbilityDmgReduceMap.remove(card);
			return amount;
		}

		// If damage reductions are disabled for this side, skip all target-side protections
		if (isP1 ? p1DmgReductionDisabled : p2DmgReductionDisabled) return amount;

		// One-time: next incoming damage = 0
		if (nextIncomingDmgZeroSet.remove(card)) return 0;

		// One-time: next incoming damage reduced by N
		if (nextIncomingDmgReduceMap.containsKey(card))
			amount = Math.max(0, amount - nextIncomingDmgReduceMap.remove(card));

		// One-time: next ability/summon damage reduced by N
		if (fromAbility && nextAbilityDmgReduceMap.containsKey(card))
			amount = Math.max(0, amount - nextAbilityDmgReduceMap.remove(card));

		// Global per-player damage reduction
		int globalRed = isP1 ? p1GlobalDmgReduction : p2GlobalDmgReduction;
		if (globalRed > 0) amount = Math.max(0, amount - globalRed);

		// Per-card non-lethal protection: damage < this card's effective power → becomes 0
		if (perCardNonLethalDmgSet.contains(card)) {
			int power = isP1 ? effectiveP1ForwardPower(idx) : effectiveP2ForwardPower(idx);
			if (amount < power) return 0;
		}

		// Global non-lethal protection: damage < forward's effective power → becomes 0
		boolean nonLethal = isP1 ? p1NonLethalProtection : p2NonLethalProtection;
		if (nonLethal) {
			int power = isP1 ? effectiveP1ForwardPower(idx) : effectiveP2ForwardPower(idx);
			if (amount < power) return 0;
		}

		return amount;
	}

	/**
	 * Applies outgoing-damage modifiers for a forward that is about to deal combat damage.
	 * Checks and consumes the one-time "next outgoing damage = 0" shield.
	 */
	private int modifyOutgoingCombatDamage(boolean isP1, int idx, int rawAmount) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		if (idx >= fwds.size()) return rawAmount;
		CardData card = fwds.get(idx);
		return nextOutgoingDmgZeroSet.remove(card) ? 0 : rawAmount;
	}

	/**
	 * Applies incoming-damage modifiers, writes the result to the damage accumulator,
	 * and breaks the forward if accumulated damage reaches its effective power.
	 */
	private void applyDamageToForward(boolean isP1, int idx, int rawAmount, boolean fromAbility, boolean unreduced) {
		List<CardData>  fwds   = isP1 ? p1ForwardCards   : p2ForwardCards;
		List<Integer>   dmgList = isP1 ? p1ForwardDamage  : p2ForwardDamage;
		if (idx >= fwds.size()) return;
		int amount = modifyIncomingDamage(isP1, idx, rawAmount, fromAbility, unreduced);
		if (amount <= 0) {
			logEntry((isP1 ? "" : "[P2] ") + fwds.get(idx).name() + " — damage blocked");
			return;
		}
		int accum  = dmgList.get(idx) + amount;
		dmgList.set(idx, accum);
		int effPow = isP1 ? effectiveP1ForwardPower(idx) : effectiveP2ForwardPower(idx);
		logEntry((isP1 ? "" : "[P2] ") + fwds.get(idx).name() + " takes " + amount + " damage"
				+ (effPow > 0 ? " (" + (effPow - accum) + " remaining)" : ""));
		if (effPow > 0 && accum >= effPow) {
			CardData fwd = fwds.get(idx);
			if (cannotBeBrokenSet.contains(fwd)) {
				logEntry((isP1 ? "" : "[P2] ") + fwd.name() + " survives lethal damage (cannot be broken — damage clears at end of turn)");
				if (isP1) refreshP1ForwardSlot(idx); else refreshP2ForwardSlot(idx);
				if (currentBreaktouchSource != null)
					fireBreaktouchForDamage(currentBreaktouchSource, currentBreaktouchSourceIsP1, isP1, idx);
			} else {
				if (isP1) breakP1Forward(idx); else breakP2Forward(idx);
			}
		} else {
			if (isP1) refreshP1ForwardSlot(idx); else refreshP2ForwardSlot(idx);
			// Fire "deals damage to forward" triggers from tracked ability source (e.g. Ramuh + Lightning Summon)
			if (currentBreaktouchSource != null)
				fireBreaktouchForDamage(currentBreaktouchSource, currentBreaktouchSourceIsP1, isP1, idx);
		}
	}

	/**
	 * Fires auto-ability "deals damage to forward" triggers for {@code source} dealing damage
	 * to the surviving forward at {@code damagedIdx}.  Returns {@code true} if the forward was broken.
	 * Handles two cases:
	 * <ul>
	 *   <li>Source card has a permanent "deals damage to forward" auto-ability (e.g. Mandragora)</li>
	 *   <li>Source is a Lightning Summon and the casting player has a card with
	 *       "lightning summon deals damage to forward" (e.g. Ramuh, Lord of Levin)</li>
	 * </ul>
	 */
	private boolean fireBreaktouchForDamage(CardData source, boolean sourceIsP1,
			boolean damagedIsP1, int damagedIdx) {
		List<CardData> damagedList = damagedIsP1 ? p1ForwardCards : p2ForwardCards;
		if (damagedIdx >= damagedList.size()) return false;
		CardData damaged = damagedList.get(damagedIdx);

		// Case 1: source card itself has "deals damage to forward" auto-ability
		for (AutoAbility fa : source.autoAbilities()) {
			if (!fa.trigger().equals("deals damage to forward")) continue;
			if (!fa.triggerCard().equalsIgnoreCase(source.name())) continue;
			logEntry((sourceIsP1 ? "" : "[P2] ") + source.name() + " — Breaktouch! "
					+ (damagedIsP1 ? "" : "[P2] ") + damaged.name() + " is broken.");
			if (damagedIsP1) breakP1Forward(damagedIdx); else breakP2Forward(damagedIdx);
			return true;
		}

		// Case 2: source is a Summon of matching element; check caster's field for the Summon trigger
		if (source.isSummon()) {
			String[] sourceElems = source.elements();
			List<CardData> casterFwds = new ArrayList<>(sourceIsP1 ? p1ForwardCards : p2ForwardCards);
			for (CardData fieldCard : casterFwds) {
				for (AutoAbility fa : fieldCard.autoAbilities()) {
					String trig = fa.trigger();
					if (!trig.endsWith(" summon deals damage to forward")) continue;
					String elemPrefix = trig.substring(0, trig.indexOf(" summon")).toLowerCase(java.util.Locale.ROOT);
					boolean elemMatch = false;
					for (String e : sourceElems) {
						if (e.toLowerCase(java.util.Locale.ROOT).equals(elemPrefix)) { elemMatch = true; break; }
					}
					if (!elemMatch) continue;
					logEntry((sourceIsP1 ? "" : "[P2] ") + fieldCard.name() + " — Breaktouch (Summon)! "
							+ (damagedIsP1 ? "" : "[P2] ") + damaged.name() + " is broken.");
					if (damagedIsP1) breakP1Forward(damagedIdx); else breakP2Forward(damagedIdx);
					return true;
				}
			}
		}
		return false;
	}

	/**
	 * Returns true when a forward at {@code (cardIsP1, cardIdx)} is the current blocker
	 * whose attacker satisfies the blocking-target filter encoded in {@code condition}.
	 */
	private boolean meetsBlockingTargetFilter(boolean cardIsP1, int cardIdx, String condition) {
		String lower = condition.toLowerCase();
		if (lower.startsWith("blocking:")) {
			String targetName = condition.substring("blocking:".length()).trim();
			return (cardIsP1  && cardIdx == p1BlockingIdx && p1BlockedByAttacker != null
					&& p1BlockedByAttacker.name().equalsIgnoreCase(targetName))
				|| (!cardIsP1 && cardIdx == p2BlockingIdx && p2BlockedByAttacker != null
					&& p2BlockedByAttacker.name().equalsIgnoreCase(targetName));
		}
		if (lower.startsWith("blocking-job:")) {
			String targetJob = condition.substring("blocking-job:".length()).trim();
			return (cardIsP1  && cardIdx == p1BlockingIdx && p1BlockedByAttacker != null
					&& p1BlockedByAttacker.job().equalsIgnoreCase(targetJob))
				|| (!cardIsP1 && cardIdx == p2BlockingIdx && p2BlockedByAttacker != null
					&& p2BlockedByAttacker.job().equalsIgnoreCase(targetJob));
		}
		return false;
	}

	private static javax.swing.border.Border createCardGlowBorder(Color color) {
		return CardAnimation.createCardGlowBorder(color);
	}

	/**
	 * Enforces the uniqueness rule for a card about to enter a side of the field.
	 * Any existing card on that side whose name (or alias) overlaps with {@code incoming}
	 * is sent directly to the Break Zone — this does NOT count as "breaking" the card,
	 * so "cannot be broken" protection is bypassed and break-zone auto-abilities do not
	 * fire.  "Leaves field" auto-abilities still fire.  Multicards are exempt.
	 *
	 * <p>Call this before adding {@code incoming} to the field so the card is not
	 * compared against itself.
	 */
	private void sendToBreakZoneByUniquenessRule(CardData incoming, boolean isP1) {
		if (incoming.multicard()) return;
		if (isP1) {
			// P1 forwards
			for (int i = p1ForwardCards.size() - 1; i >= 0; i--) {
				CardData c = p1ForwardCards.get(i);
				if (!cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] " + c.name() + " — sent to Break Zone");
				CardData top = p1ForwardPrimedTop.get(i);
				if (top != null) {
					addToP1BreakZone(c);
					addToP1BreakZone(top);
					gameState.getP1BreakZone().remove(top);
					gameState.addToP1PermanentRfp(top);
				} else {
					addToP1BreakZone(c);
				}
				p1ForwardCards.remove(i); p1ForwardUrls.remove(i);
				p1ForwardStates.remove(i); p1ForwardPlayedOnTurn.remove(i);
				p1ForwardDamage.remove(i); p1ForwardPowerBoost.remove(i);
				p1ForwardPowerReduction.remove(i); p1ForwardTempTraits.remove(i);
				p1ForwardRemovedTraits.remove(i); p1ForwardPrimedTop.remove(i);
				p1ForwardFrozen.remove(i); p1ForwardLabels.remove(i);
				shiftBlockSet(p1ForwardCannotBlock, i); shiftBlockSet(p1ForwardMustBlock, i);
				shiftBlockSet(p1ForwardCannotAttack, i); shiftBlockSet(p1ForwardMustAttack, i);
				shiftBlockSet(p1ForwardCannotAttackPersistent, i); shiftBlockSet(p1ForwardCannotBlockPersistent, i);
				stolenForwards.remove(c);
				checkAndRestoreStolenOnLeave(c.name());
				refreshP1BreakLabel();
				rebuildP1ForwardPanel();
				triggerAutoAbilitiesForLeavesField(c, true);
			}
			// P1 backups
			for (int i = 0; i < p1BackupCards.length; i++) {
				CardData c = p1BackupCards[i];
				if (c == null || !cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] " + c.name() + " — sent to Break Zone");
				addToP1BreakZone(c);
				p1BackupCards[i] = null; p1BackupStates[i] = CardState.ACTIVE;
				refreshP1BackupSlot(i); refreshP1BreakLabel();
				triggerAutoAbilitiesForLeavesField(c, true);
			}
			// P1 monsters
			for (int i = p1MonsterCards.size() - 1; i >= 0; i--) {
				CardData c = p1MonsterCards.get(i);
				if (!cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] " + c.name() + " — sent to Break Zone");
				addToP1BreakZone(c);
				p1MonsterTempForwardPower.remove(c);
				p1MonsterCards.remove(i); p1MonsterStates.remove(i);
				p1MonsterFrozen.remove(i); p1MonsterPlayedOnTurn.remove(i);
				p1MonsterDamage.remove(i);
				p1MonsterUrls.remove(i);
				JLabel lbl = p1MonsterLabels.remove(i);
				p1MonsterPanel.remove(lbl); p1MonsterPanel.revalidate(); p1MonsterPanel.repaint();
				refreshP1BreakLabel();
				triggerAutoAbilitiesForLeavesField(c, true);
			}
		} else {
			// P2 forwards
			for (int i = p2ForwardCards.size() - 1; i >= 0; i--) {
				CardData c = p2ForwardCards.get(i);
				if (!cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] [P2] " + c.name() + " — sent to Break Zone");
				addToP2BreakZone(c);
				p2ForwardCards.remove(i); p2ForwardUrls.remove(i);
				p2ForwardStates.remove(i); p2ForwardPlayedOnTurn.remove(i);
				p2ForwardDamage.remove(i); p2ForwardPowerBoost.remove(i);
				p2ForwardPowerReduction.remove(i); p2ForwardTempTraits.remove(i);
				p2ForwardRemovedTraits.remove(i); p2ForwardPrimedTop.remove(i); p2ForwardFrozen.remove(i);
				p2ForwardLabels.remove(i);
				shiftBlockSet(p2ForwardCannotBlock, i); shiftBlockSet(p2ForwardMustBlock, i);
				shiftBlockSet(p2ForwardCannotAttack, i); shiftBlockSet(p2ForwardMustAttack, i);
				shiftBlockSet(p2ForwardCannotAttackPersistent, i); shiftBlockSet(p2ForwardCannotBlockPersistent, i);
				refreshP2BreakLabel();
				rebuildP2ForwardPanel();
				triggerAutoAbilitiesForLeavesField(c, false);
			}
			// P2 backups
			for (int i = 0; i < p2BackupCards.length; i++) {
				CardData c = p2BackupCards[i];
				if (c == null || !cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] [P2] " + c.name() + " — sent to Break Zone");
				addToP2BreakZone(c);
				p2BackupCards[i] = null; p2BackupStates[i] = CardState.ACTIVE;
				refreshP2BackupSlot(i); refreshP2BreakLabel();
				triggerAutoAbilitiesForLeavesField(c, false);
			}
			// P2 monsters
			for (int i = p2MonsterCards.size() - 1; i >= 0; i--) {
				CardData c = p2MonsterCards.get(i);
				if (!cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] [P2] " + c.name() + " — sent to Break Zone");
				addToP2BreakZone(c);
				p2MonsterTempForwardPower.remove(c);
				p2MonsterCards.remove(i); p2MonsterStates.remove(i);
				p2MonsterFrozen.remove(i); p2MonsterPlayedOnTurn.remove(i);
				p2MonsterDamage.remove(i);
				p2MonsterUrls.remove(i);
				JLabel lbl = p2MonsterLabels.remove(i);
				if (p2MonsterPanel != null) { p2MonsterPanel.remove(lbl); p2MonsterPanel.revalidate(); p2MonsterPanel.repaint(); }
				refreshP2BreakLabel();
				triggerAutoAbilitiesForLeavesField(c, false);
			}
		}
	}

	/**
	 * Shows a modal dialog for P1 to assign the blocker's power as damage across the
	 * attacking party, in multiples of 1000.  The total must equal {@code blockerPower}
	 * before Confirm is enabled.
	 *
	 * @param attackerIndices indices into {@code p1ForwardCards}
	 * @param blockerPower    total damage to distribute (the blocker's effective power)
	 * @return mapping of attacker index → damage assigned; empty if dialog was dismissed
	 */
	private Map<Integer, Integer> showPartyDamageAssignmentDialog(
			List<Integer> attackerIndices, List<CardData> attackerCards, int[] effectivePowers, int blockerPower) {

		int n = attackerIndices.size();
		int[] assigned = new int[n]; // damage assigned per attacker slot, multiples of 1000

		JDialog dlg = new JDialog(frame, "Assign Blocker Damage", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		// Header
		JLabel headerLabel = new JLabel(
				"Assign " + blockerPower + " damage across the party (multiples of 1000):",
				SwingConstants.CENTER);
		headerLabel.setFont(FontLoader.loadPixelNESFont(9));
		headerLabel.setBorder(BorderFactory.createEmptyBorder(10, 12, 6, 12));

		// Remaining indicator and Confirm — created early so the update lambda can close over them
		JLabel remainLabel = new JLabel("Remaining: " + blockerPower, SwingConstants.CENTER);
		remainLabel.setFont(FontLoader.loadPixelNESFont(10));
		remainLabel.setForeground(new Color(200, 80, 80));

		boolean[] confirmed = { false };
		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setFocusPainted(false);
		confirmBtn.setEnabled(blockerPower == 0); // enabled immediately only if nothing to assign

		// Single update routine: refreshes both the remaining label and Confirm state
		final Runnable updateState = () -> {
			int total = 0;
			for (int a : assigned) total += a;
			int remaining = blockerPower - total;
			remainLabel.setText("Remaining: " + remaining);
			remainLabel.setForeground(remaining == 0 ? new Color(40, 160, 40) : new Color(200, 80, 80));
			confirmBtn.setEnabled(total == blockerPower);
		};

		confirmBtn.addActionListener(ae -> { confirmed[0] = true; dlg.dispose(); });

		// Attacker columns
		JPanel attackersPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 16, 8));
		JLabel[] valueLabels = new JLabel[n];

		for (int i = 0; i < n; i++) {
			final int slot = i;
			int idx = attackerIndices.get(i);
			CardData card = attackerCards.get(i);
			int power = effectivePowers[i];

			JPanel cardPanel = new JPanel();
			cardPanel.setLayout(new BoxLayout(cardPanel, BoxLayout.Y_AXIS));
			cardPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

			JLabel nameLabel = new JLabel(
					"<html><center>" + card.name() + "<br>(" + power + ")</center></html>",
					SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(8));
			nameLabel.setAlignmentX(0.5f);

			JLabel valueLabel = new JLabel("0", SwingConstants.CENTER);
			valueLabel.setFont(FontLoader.loadPixelNESFont(16));
			valueLabel.setPreferredSize(new Dimension(72, 40));
			valueLabel.setMinimumSize(new Dimension(72, 40));
			valueLabel.setOpaque(true);
			valueLabel.setBackground(Color.WHITE);
			valueLabel.setBorder(BorderFactory.createCompoundBorder(
					BorderFactory.createLineBorder(Color.DARK_GRAY, 2),
					BorderFactory.createEmptyBorder(2, 6, 2, 6)));
			valueLabel.setAlignmentX(0.5f);
			valueLabels[i] = valueLabel;

			JButton leftBtn = new JButton("◄");
			leftBtn.setFont(FontLoader.loadPixelNESFont(11));
			leftBtn.setFocusPainted(false);
			leftBtn.addActionListener(ae -> {
				if (assigned[slot] >= 1000) {
					assigned[slot] -= 1000;
					valueLabels[slot].setText(String.valueOf(assigned[slot]));
					updateState.run();
				}
			});

			JButton rightBtn = new JButton("►");
			rightBtn.setFont(FontLoader.loadPixelNESFont(11));
			rightBtn.setFocusPainted(false);
			rightBtn.addActionListener(ae -> {
				int total = 0;
				for (int a : assigned) total += a;
				if (total < blockerPower) {
					assigned[slot] += 1000;
					valueLabels[slot].setText(String.valueOf(assigned[slot]));
					updateState.run();
				}
			});

			JPanel arrowRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
			arrowRow.setOpaque(false);
			arrowRow.add(leftBtn);
			arrowRow.add(valueLabel);
			arrowRow.add(rightBtn);

			cardPanel.add(nameLabel);
			cardPanel.add(javax.swing.Box.createVerticalStrut(4));
			cardPanel.add(arrowRow);
			attackersPanel.add(cardPanel);
		}

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
		south.add(confirmBtn);

		JPanel bottomArea = new JPanel(new BorderLayout());
		bottomArea.add(remainLabel, BorderLayout.NORTH);
		bottomArea.add(south,       BorderLayout.CENTER);

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(headerLabel,    BorderLayout.NORTH);
		dlg.getContentPane().add(attackersPanel, BorderLayout.CENTER);
		dlg.getContentPane().add(bottomArea,     BorderLayout.SOUTH);

		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);

		if (!confirmed[0]) return Map.of();
		Map<Integer, Integer> result = new LinkedHashMap<>();
		for (int i = 0; i < n; i++)
			if (assigned[i] > 0) result.put(attackerIndices.get(i), assigned[i]);
		return result;
	}

	private int showNumberSelectDialog(String prompt, int min, int max) {
		int[] value = { min };

		JDialog dlg = new JDialog(frame, "Select a Number", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JLabel promptLabel = new JLabel(prompt, SwingConstants.CENTER);
		promptLabel.setFont(FontLoader.loadPixelNESFont(11));
		promptLabel.setBorder(BorderFactory.createEmptyBorder(10, 12, 4, 12));

		JLabel valueLabel = new JLabel(String.valueOf(value[0]), SwingConstants.CENTER);
		valueLabel.setFont(FontLoader.loadPixelNESFont(20));
		valueLabel.setPreferredSize(new Dimension(64, 48));
		valueLabel.setOpaque(true);
		valueLabel.setBackground(Color.WHITE);
		valueLabel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(Color.DARK_GRAY, 2),
				BorderFactory.createEmptyBorder(2, 8, 2, 8)));

		JButton leftBtn = new JButton("◄");
		leftBtn.setFont(FontLoader.loadPixelNESFont(14));
		leftBtn.setFocusPainted(false);
		leftBtn.addActionListener(ae -> {
			if (value[0] > min) {
				value[0]--;
				valueLabel.setText(String.valueOf(value[0]));
			}
		});

		JButton rightBtn = new JButton("►");
		rightBtn.setFont(FontLoader.loadPixelNESFont(14));
		rightBtn.setFocusPainted(false);
		rightBtn.addActionListener(ae -> {
			if (value[0] < max) {
				value[0]++;
				valueLabel.setText(String.valueOf(value[0]));
			}
		});

		JPanel pickerRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 10));
		pickerRow.add(leftBtn);
		pickerRow.add(valueLabel);
		pickerRow.add(rightBtn);

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setFocusPainted(false);
		confirmBtn.addActionListener(ae -> dlg.dispose());

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 8));
		south.add(confirmBtn);

		dlg.getContentPane().setLayout(new BorderLayout(0, 2));
		dlg.getContentPane().add(promptLabel, BorderLayout.NORTH);
		dlg.getContentPane().add(pickerRow,   BorderLayout.CENTER);
		dlg.getContentPane().add(south,       BorderLayout.SOUTH);

		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);

		return value[0];
	}

	/**
	 * Applies {@code action} to each target, highest index first, so that removing or
	 * returning a card does not shift the indices of targets not yet processed.  Zones
	 * are independent lists, so a single descending-index sort is safe across zones.
	 */
	private void applyTargetsHighestIndexFirst(java.util.List<ForwardTarget> targets, Consumer<ForwardTarget> action) {
		targets.stream()
				.sorted(Comparator.comparingInt(ForwardTarget::idx).reversed())
				.forEach(action);
	}

	/**
	 * Shows a modal dialog for P1 to pick targeted forwards from {@code eligible}.
	 * Auto-selects all when the eligible count does not exceed {@code maxCount} and
	 * {@code upTo} is false.  Returns immediately with an empty list when there are
	 * no eligible targets.
	 */
	private java.util.List<ForwardTarget> showForwardSelectDialog(
			java.util.List<ForwardTarget> eligible, int maxCount, boolean upTo, String title) {
		if (eligible.isEmpty()) { logEntry("Choose: no eligible targets"); return java.util.List.of(); }
		if (!upTo && eligible.size() <= maxCount) return java.util.List.copyOf(eligible);
		return selectFieldTargetsInPlace(eligible, maxCount, upTo, title);
	}

	/** Maps a field {@link ForwardTarget} to its on-screen card label. */
	private JLabel labelForTarget(ForwardTarget t) {
		return switch (t.zone()) {
			case FORWARD -> t.isP1() ? p1ForwardLabels.get(t.idx()) : p2ForwardLabels.get(t.idx());
			case BACKUP  -> t.isP1() ? p1BackupLabels[t.idx()]      : p2BackupLabels[t.idx()];
			case MONSTER -> t.isP1() ? p1MonsterLabels.get(t.idx()) : p2MonsterLabels.get(t.idx());
		};
	}

	/** Current {@link CardState} of a field {@link ForwardTarget}, used to glow at the right card bounds. */
	private CardState fieldTargetState(ForwardTarget t) {
		return switch (t.zone()) {
			case FORWARD -> t.isP1() ? p1ForwardStates.get(t.idx()) : p2ForwardStates.get(t.idx());
			case BACKUP  -> t.isP1() ? p1BackupStates[t.idx()]      : p2BackupStates[t.idx()];
			case MONSTER -> t.isP1() ? p1MonsterStates.get(t.idx()) : p2MonsterStates.get(t.idx());
		};
	}

	/**
	 * Border that paints the card-selection glow over the actual card rectangle within a
	 * {@code CARD_H}×{@code CARD_H} slot (matching {@link CardAnimation#renderBackupCard}),
	 * rather than the full label bounds, so the highlight hugs the art.
	 */
	private static Color pulseColor(Color base, float t) {
		float f = 0.5f + 0.5f * t;
		return new Color(
				Math.round(base.getRed()   * f),
				Math.round(base.getGreen() * f),
				Math.round(base.getBlue()  * f));
	}

	private static javax.swing.border.Border cardBoundsGlowBorder(Color color, boolean dull) {
		return new javax.swing.border.AbstractBorder() {
			@Override public void paintBorder(java.awt.Component c, java.awt.Graphics g0,
					int x, int y, int w, int h) {
				int cw = dull ? CARD_H : CARD_W;
				int ch = dull ? CARD_W : CARD_H;
				int cy = dull ? y + (CARD_H - CARD_W) : y;
				java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
				g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
						java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				int layers = 16;
				for (int layer = layers; layer >= 0; layer--) {
					float t   = (float) layer / layers;
					int   alpha = Math.round(t * t * 235);
					g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
					g.setStroke(new java.awt.BasicStroke(2.5f));
					int off = layers - layer;
					g.drawRect(x + off, cy + off, cw - 1 - 2 * off, ch - 1 - 2 * off);
				}
				g.setColor(color);
				g.setStroke(new java.awt.BasicStroke(3f));
				g.drawRect(x + 1, cy + 1, cw - 3, ch - 3);
				g.dispose();
			}
		};
	}

	/**
	 * In-place field-target selection: glows each eligible card on the board and lets the
	 * player click to toggle it.  Exact-count selections resolve as soon as {@code maxCount}
	 * cards are chosen; "up to" selections show a small Confirm / Cancel bar.  Card-zoom on
	 * hover keeps working through the cards' existing listeners.  Blocks (pumping the event
	 * queue via a secondary loop) until the choice is made, preserving the synchronous
	 * selection contract the effect resolver relies on.
	 */
	private java.util.List<ForwardTarget> selectFieldTargetsInPlace(
			java.util.List<ForwardTarget> eligible, int maxCount, boolean upTo, String title) {
		final Color GLOW_ELIGIBLE = new Color(90, 200, 255);
		final Color GLOW_PICKED   = Color.YELLOW;

		java.util.LinkedHashSet<Integer> sel = new java.util.LinkedHashSet<>();
		java.util.List<JLabel> labels = new ArrayList<>(eligible.size());
		java.util.Map<JLabel, javax.swing.border.Border> origBorders = new java.util.HashMap<>();
		java.util.List<java.awt.event.MouseListener> listeners = new ArrayList<>(eligible.size());
		java.util.List<ForwardTarget> result = new ArrayList<>();
		boolean[] dulls = new boolean[eligible.size()];
		final javax.swing.Timer[] pulseTimerRef = { null };

		java.awt.SecondaryLoop loop =
				java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
		boolean[] done = { false };

		JDialog bar = new JDialog(frame, title, false);
		bar.setUndecorated(true);             // no title bar / close box; not user-moveable
		bar.setFocusableWindowState(false);   // never steal focus, so the board stays clickable
		bar.setResizable(false);

		Runnable finish = () -> {
			if (done[0]) return;
			done[0] = true;
			if (pulseTimerRef[0] != null) pulseTimerRef[0].stop();
			for (int i = 0; i < labels.size(); i++) {
				labels.get(i).setBorder(origBorders.get(labels.get(i)));
				labels.get(i).removeMouseListener(listeners.get(i));
			}
			fieldTargetingActive = false;
			for (Integer si : sel) result.add(eligible.get(si));
			bar.dispose();
			if (loop != null) loop.exit();
		};

		fieldTargetingActive = true;
		for (int i = 0; i < eligible.size(); i++) {
			final int fi = i;
			JLabel lbl = labelForTarget(eligible.get(i));
			final boolean dull = fieldTargetState(eligible.get(i)) == CardState.DULL;
			dulls[fi] = dull;
			labels.add(lbl);
			origBorders.put(lbl, lbl.getBorder());
			lbl.setBorder(cardBoundsGlowBorder(GLOW_ELIGIBLE, dull));
			java.awt.event.MouseListener ml = new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					if (!SwingUtilities.isLeftMouseButton(e)) return;
					if (sel.contains(fi)) {
						sel.remove(fi);
					} else {
						if (sel.size() >= maxCount) return;
						sel.add(fi);
						if (!upTo && sel.size() == maxCount) { finish.run(); return; }
					}
				}
			};
			lbl.addMouseListener(ml);
			listeners.add(ml);
		}

		final float[] pulse = { 0f };
		javax.swing.Timer pulseTimer = new javax.swing.Timer(40, ev -> {
			pulse[0] += 0.12f;
			float t = (float) (0.5 + 0.5 * Math.sin(pulse[0]));
			for (int i = 0; i < labels.size(); i++) {
				Color base = sel.contains(i) ? GLOW_PICKED : GLOW_ELIGIBLE;
				labels.get(i).setBorder(cardBoundsGlowBorder(pulseColor(base, t), dulls[i]));
			}
		});
		pulseTimerRef[0] = pulseTimer;
		pulseTimer.start();

		JLabel hdr = new JLabel(title, SwingConstants.CENTER);
		hdr.setFont(FontLoader.loadPixelNESFont(11));
		hdr.setBorder(BorderFactory.createEmptyBorder(8, 12, 6, 12));

		bar.getContentPane().setLayout(new BorderLayout());
		((javax.swing.JComponent) bar.getContentPane()).setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createRaisedBevelBorder(),
				BorderFactory.createEmptyBorder(4, 6, 4, 6)));
		bar.getContentPane().add(hdr, BorderLayout.CENTER);
		if (upTo) {
			JButton confirmBtn = new JButton("Confirm");
			confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
			confirmBtn.addActionListener(ae -> finish.run());
			JButton cancelBtn = new JButton("Cancel");
			cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
			cancelBtn.addActionListener(ae -> { sel.clear(); finish.run(); });
			JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
			south.add(confirmBtn);
			south.add(cancelBtn);
			bar.getContentPane().add(south, BorderLayout.SOUTH);
		}
		bar.pack();
		// Center where the two fields meet, like the opening-hand popup.
		positionAtFieldDivider(bar);
		bar.setVisible(true);

		if (loop == null || !loop.enter()) finish.run();   // fallback if no secondary loop is available
		return result;
	}

	/** Stacks {@code opponentRow} above {@code selfRow} with section labels; omits empty rows. */
	private JPanel buildTwoRowTargetPanel(JPanel opponentRow, JPanel selfRow) {
		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
		if (opponentRow.getComponentCount() > 0) {
			JLabel lbl = new JLabel("— Opponent —", SwingConstants.CENTER);
			lbl.setFont(FontLoader.loadPixelNESFont(9));
			lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
			center.add(lbl);
			center.add(opponentRow);
		}
		if (selfRow.getComponentCount() > 0) {
			JLabel lbl = new JLabel("— Yours —", SwingConstants.CENTER);
			lbl.setFont(FontLoader.loadPixelNESFont(9));
			lbl.setAlignmentX(Component.CENTER_ALIGNMENT);
			center.add(lbl);
			center.add(selfRow);
		}
		return center;
	}

	/**
	 * Like {@link #showForwardSelectDialog} but selects from a Break Zone list
	 * rather than the field.  {@code eligible} entries carry the correct
	 * {@code isP1} flag and an index into {@code zone}.
	 */
	private java.util.List<ForwardTarget> showBreakZoneSelectDialog(
			java.util.List<ForwardTarget> eligible, java.util.List<CardData> zone,
			int maxCount, boolean upTo, String title) {
		if (eligible.isEmpty()) { logEntry("Choose: no eligible targets in break zone"); return java.util.List.of(); }
		if (!upTo && eligible.size() <= maxCount) return java.util.List.copyOf(eligible);

		JDialog dlg = new JDialog(frame, title, true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		java.util.List<ForwardTarget> chosen = new ArrayList<>();
		java.util.Set<Integer> sel = new java.util.LinkedHashSet<>();
		java.util.List<JLabel> cardLabels = new ArrayList<>(eligible.size());

		JPanel opponentRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
		JPanel selfRow     = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setEnabled(upTo);

		javax.swing.border.Border normalBorder   = BorderFactory.createLineBorder(new Color(100, 100, 100), 2);
		javax.swing.border.Border selectedBorder = BorderFactory.createLineBorder(Color.YELLOW, 3);

		for (int i = 0; i < eligible.size(); i++) {
			ForwardTarget target = eligible.get(i);
			CardData card = zone.get(target.idx());
			final int fi = i;

			JLabel imgLbl = new JLabel("...", SwingConstants.CENTER);
			imgLbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			imgLbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			imgLbl.setOpaque(true);
			imgLbl.setBackground(Color.DARK_GRAY);
			imgLbl.setBorder(normalBorder);
			cardLabels.add(imgLbl);

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(card.imageUrl());
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try {
						ImageIcon icon = get();
						if (icon != null) { imgLbl.setIcon(icon); imgLbl.setText(null); }
					} catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			MouseAdapter cardListener = new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) { showZoomAt(card.imageUrl()); }
				@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				@Override public void mouseClicked(MouseEvent e) {
					if (sel.contains(fi)) {
						sel.remove(fi);
						imgLbl.setBorder(normalBorder);
					} else {
						if (maxCount == 1) {
							for (int si : sel) cardLabels.get(si).setBorder(normalBorder);
							sel.clear();
						} else if (sel.size() >= maxCount) {
							return;
						}
						sel.add(fi);
						imgLbl.setBorder(selectedBorder);
					}
					confirmBtn.setEnabled(upTo || sel.size() == maxCount);
				}
			};
			imgLbl.addMouseListener(cardListener);

			JLabel nameLbl = new JLabel(card.name(), SwingConstants.CENTER);
			nameLbl.setFont(FontLoader.loadPixelNESFont(9));
			nameLbl.setPreferredSize(new Dimension(CARD_W, 18));
			nameLbl.addMouseListener(cardListener);

			JPanel wrapper = new JPanel(new BorderLayout(0, 2));
			wrapper.setOpaque(false);
			wrapper.add(imgLbl,  BorderLayout.CENTER);
			wrapper.add(nameLbl, BorderLayout.SOUTH);

			if (!target.isP1()) opponentRow.add(wrapper);
			else                selfRow.add(wrapper);
		}

		confirmBtn.addActionListener(ae -> {
			for (int si : sel) chosen.add(eligible.get(si));
			dlg.dispose();
		});
		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
		south.add(confirmBtn);
		if (upTo) {
			JButton skipBtn = new JButton("Skip");
			skipBtn.setFont(FontLoader.loadPixelNESFont(11));
			skipBtn.addActionListener(ae -> dlg.dispose());
			south.add(skipBtn);
		}

		JLabel hdr = new JLabel(title, SwingConstants.CENTER);
		hdr.setFont(FontLoader.loadPixelNESFont(11));
		hdr.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(hdr,                                    BorderLayout.NORTH);
		dlg.getContentPane().add(buildTwoRowTargetPanel(opponentRow, selfRow), BorderLayout.CENTER);
		dlg.getContentPane().add(south,                                  BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
		return java.util.List.copyOf(chosen);
	}

	// -------------------------------------------------------------------------

	private void showBackupContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();

		CardData card = p1BackupCards[idx];
		if (card != null) {
			addAbilityMenuItems(menu, card, p1BackupFrozen[idx], p1BackupStates[idx], p1BackupPlayedOnTurn[idx],
					() -> { p1BackupStates[idx] = CardState.DULL; animateDullBackup(idx, true); }, true);
		}

		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}


	// -------------------------------------------------------------------------
	// -------------------------------------------------------------------------
	// Helpers
	// -------------------------------------------------------------------------

	/**
	 * Builds a damage zone panel for one player.
	 * Contains 7 slots (D, A, M, A, G, E, Px) stacked vertically,
	 * each sized to hold a sideways card (CARD_H wide Ã— CARD_W tall).
	 * The color dropdown sits below the slots.
	 */
	/**
	 * @param labelStorage if non-null, the 5 created slot labels are stored here (index 0-4)
	 */
	/**
	 * Builds the Forward zone: a horizontally-scrollable row of card slots.
	 * Pass {@code true} for P1 to store a reference for dynamic card placement.
	 */
	private static final int FORWARD_ZONE_H = CARD_H * 5 / 4;

	private JScrollPane buildForwardZonePanel(boolean isP1) {
		JPanel forwardInner = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0)) {
			@Override
			public Dimension getPreferredSize() {
				int gap   = 4;
				int slots = getComponentCount();
				int width = gap + (CARD_H + gap) * slots;
				return new Dimension(Math.max(width, gap * 2), FORWARD_ZONE_H);
			}
		};
		forwardInner.setOpaque(false);
		if (isP1) p1ForwardPanel = forwardInner;
		else      p2ForwardPanel = forwardInner;

		JPanel monsterInner = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0)) {
			@Override
			public Dimension getPreferredSize() {
				int gap   = 4;
				int slots = getComponentCount();
				int width = slots > 0 ? gap + (CARD_H + gap) * slots : 0;
				return new Dimension(width, FORWARD_ZONE_H);
			}
		};
		monsterInner.setOpaque(false);
		if (isP1) p1MonsterPanel = monsterInner;
		else      p2MonsterPanel = monsterInner;

		// Monster panel sits at the bottom of the EAST area for "lower-right" appearance
		JPanel monsterContainer = new JPanel(new BorderLayout());
		monsterContainer.setOpaque(false);
		monsterContainer.add(monsterInner, BorderLayout.SOUTH);

		JPanel outer = new JPanel(new BorderLayout()) {
			@Override
			public Dimension getPreferredSize() {
				Dimension fwd = forwardInner.getPreferredSize();
				Dimension mon = monsterInner.getPreferredSize();
				return new Dimension(fwd.width + mon.width, FORWARD_ZONE_H);
			}
		};
		outer.setOpaque(false);
		outer.add(forwardInner,    BorderLayout.CENTER);
		outer.add(monsterContainer, BorderLayout.EAST);

		JScrollPane scroll = new JScrollPane(outer,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scroll.setBorder(BorderFactory.createEmptyBorder());
		scroll.setOpaque(false);
		scroll.getViewport().setOpaque(false);
		scroll.setPreferredSize(new Dimension(0, FORWARD_ZONE_H));
		return scroll;
	}

	/** Adds a Forward card to P1's forward zone and wires up the debug context menu. */
	private void placeCardInForwardZone(CardData card) {
		if (p1ForwardPanel == null) return;
		sendToBreakZoneByUniquenessRule(card, true);
		int idx = p1ForwardLabels.size();

		JLabel lbl = new JLabel("", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
		lbl.setOpaque(false);
		lbl.setForeground(Color.DARK_GRAY);
		lbl.setFont(FontLoader.loadPixelNESFont(11));
		lbl.setBorder(BorderFactory.createEmptyBorder());
		lbl.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				if (lbl.getIcon() == null) return;
				if (SwingUtilities.isLeftMouseButton(e)
						&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
					handleP1ForwardLeftClick(idx);
				} else {
					showForwardContextMenu(idx, lbl, e);
				}
			}
			@Override public void mouseEntered(MouseEvent e) {
				if (lbl.getIcon() == null) return;
				CardData top = p1ForwardPrimedTop.get(idx);
				showZoomAt(top != null ? top.imageUrl() : p1ForwardUrls.get(idx));
			}
			@Override public void mouseExited(MouseEvent e) { hideZoom(); }
		});

		p1ForwardUrls.add(card.imageUrl());
		p1ForwardCards.add(card);
		p1ForwardStates.add(card.entersFieldDull() ? CardState.DULL : CardState.ACTIVE);
		p1ForwardPlayedOnTurn.add(gameState.getTurnNumber());
		p1ForwardDamage.add(0);
		p1ForwardPowerBoost.add(0);
		p1ForwardPowerReduction.add(0);
		p1ForwardTempTraits.add(java.util.EnumSet.noneOf(CardData.Trait.class));
		p1ForwardRemovedTraits.add(java.util.EnumSet.noneOf(CardData.Trait.class));
		p1ForwardTempJobs.add(null);
		p1ForwardPrimedTop.add(null);
		p1ForwardFrozen.add(false);
		p1ForwardLabels.add(lbl);

		p1ForwardPanel.add(lbl);
		p1ForwardPanel.revalidate();
		p1ForwardPanel.repaint();

		refreshP1ForwardSlot(idx);
		if (!card.fieldPowerGrants().isEmpty()) refreshFieldGrantDependents(true);
		if (!card.fieldCostReductions().isEmpty()) refreshHandPopupIfVisible();
		triggerAutoAbilitiesForEntersField(card, true);
	}

	/** Adds a Monster card to P1's monster zone (right side of forward zone, newest leftmost). */
	private void placeCardInMonsterZone(CardData card) {
		if (p1MonsterPanel == null) return;
		sendToBreakZoneByUniquenessRule(card, true);
		int idx = p1MonsterLabels.size();

		JLabel lbl = new JLabel("", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
		lbl.setOpaque(false);
		lbl.setForeground(Color.DARK_GRAY);
		lbl.setFont(FontLoader.loadPixelNESFont(11));
		lbl.setBorder(BorderFactory.createEmptyBorder());
		lbl.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				if (lbl.getIcon() == null) return;
				if (SwingUtilities.isLeftMouseButton(e)
						&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
					handleP1MonsterLeftClick(idx);
				} else {
					showMonsterContextMenu(idx, lbl, e);
				}
			}
			@Override public void mouseEntered(MouseEvent e) {
				if (lbl.getIcon() != null) showZoomAt(p1MonsterUrls.get(idx));
			}
			@Override public void mouseExited(MouseEvent e) { hideZoom(); }
		});

		p1MonsterUrls.add(card.imageUrl());
		p1MonsterCards.add(card);
		p1MonsterStates.add(card.entersFieldDull() ? CardState.DULL : CardState.ACTIVE);
		p1MonsterPlayedOnTurn.add(gameState.getTurnNumber());
		p1MonsterFrozen.add(false);
		p1MonsterDamage.add(0);
		p1MonsterLabels.add(lbl);

		// Insert at front so newest monster appears leftmost
		p1MonsterPanel.add(lbl, 0);
		p1MonsterPanel.revalidate();
		p1MonsterPanel.repaint();

		refreshP1MonsterSlot(idx);
		// Monster entering the field may satisfy a condition for a forward's boost
		refreshAllForwardSlots();
	}

	/** Reloads and re-renders a single P1 monster slot using its stored URL and state. */
	private void refreshP1MonsterSlot(int idx) {
		String url   = p1MonsterUrls.get(idx);
		CardState state = p1MonsterStates.get(idx);
		JLabel slot  = p1MonsterLabels.get(idx);
		if (url == null) return;
		CardData card     = p1MonsterCards.get(idx);
		int power         = effectiveP1MonsterPower(idx);
		int basePower     = card.power();
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		Integer tempFwdPower = p1MonsterTempForwardPower.get(card);
		boolean canAttack = attackSubStep == 1 && isMonsterSelectableAsForward(idx);
		boolean selected  = p1MonsterAttackIdx == idx;
		int damage        = p1MonsterDamage.get(idx);
		boolean bfaActive = bfa != null && (bfa.damageThreshold() > 0
				? gameState.getP1DamageZone().size() >= bfa.damageThreshold()
				: gameState.getCurrentPlayer() == GameState.Player.P1);
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.renderBackupCard(
						CardAnimation.toARGB(raw, CARD_W, CARD_H), state, canAttack, selected, p1MonsterFrozen.get(idx));
				if (damage > 0)
					CardAnimation.renderDamageOverlay(canvas, damage);
				if (bfaActive)
					CardAnimation.renderPowerOverlayRight(canvas, bfa.power(), new Color(80, 220, 80), state);
				else if (tempFwdPower != null)
					CardAnimation.renderPowerOverlayRight(canvas, tempFwdPower, new Color(80, 220, 80), state);
				else if (power > basePower)
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(80, 220, 80), state);
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null) { slot.setIcon(icon); slot.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	/** Adds a Monster card to P2's monster zone (right side of forward zone). */
	private void placeP2CardInMonsterZone(CardData card) {
		if (p2MonsterPanel == null) return;
		int idx = p2MonsterLabels.size();

		JLabel lbl = new JLabel("", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
		lbl.setOpaque(false);
		lbl.setFont(FontLoader.loadPixelNESFont(11));
		lbl.setBorder(BorderFactory.createEmptyBorder());
		lbl.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				if (lbl.getIcon() != null && SwingUtilities.isRightMouseButton(e))
					showP2MonsterContextMenu(idx, lbl, e);
			}
			@Override public void mouseEntered(MouseEvent e) {
				if (lbl.getIcon() != null) showZoomAt(p2MonsterUrls.get(idx));
			}
			@Override public void mouseExited(MouseEvent e) { hideZoom(); }
		});

		p2MonsterUrls.add(card.imageUrl());
		p2MonsterCards.add(card);
		p2MonsterStates.add(CardState.ACTIVE);
		p2MonsterPlayedOnTurn.add(gameState.getTurnNumber());
		p2MonsterFrozen.add(false);
		p2MonsterDamage.add(0);
		p2MonsterLabels.add(lbl);

		p2MonsterPanel.add(lbl);
		p2MonsterPanel.revalidate();
		p2MonsterPanel.repaint();

		refreshP2MonsterSlot(idx);
	}

	/** Reloads and re-renders a single P2 monster slot using its stored URL and state. */
	private void refreshP2MonsterSlot(int idx) {
		String url = p2MonsterUrls.get(idx);
		CardState state = p2MonsterStates.get(idx);
		JLabel slot = p2MonsterLabels.get(idx);
		if (url == null) return;
		CardData card     = p2MonsterCards.get(idx);
		int power         = effectiveP2MonsterPower(idx);
		int basePower     = card.power();
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		Integer tempFwdPower = p2MonsterTempForwardPower.get(card);
		int damage        = p2MonsterDamage.get(idx);
		boolean bfaActive = bfa != null && (bfa.damageThreshold() > 0
				? gameState.getP2DamageZone().size() >= bfa.damageThreshold()
				: gameState.getCurrentPlayer() == GameState.Player.P2);
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.toARGB(raw, CARD_W, CARD_H);
				canvas = CardAnimation.renderBackupCard(canvas, state, false, false, p2MonsterFrozen.get(idx));
				if (damage > 0)
					CardAnimation.renderDamageOverlay(canvas, damage);
				if (bfaActive)
					CardAnimation.renderPowerOverlayRight(canvas, bfa.power(), new Color(80, 220, 80), state);
				else if (tempFwdPower != null)
					CardAnimation.renderPowerOverlayRight(canvas, tempFwdPower, new Color(80, 220, 80), state);
				else if (power > basePower)
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(80, 220, 80), state);
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null) { slot.setIcon(icon); slot.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	/** Shows a context menu for a P1 monster slot. */
	private void showMonsterContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();

		// Action abilities
		addAbilityMenuItems(menu, p1MonsterCards.get(idx), p1MonsterFrozen.get(idx),
				p1MonsterStates.get(idx), p1MonsterPlayedOnTurn.get(idx),
				() -> { p1MonsterStates.set(idx, CardState.DULL); refreshP1MonsterSlot(idx); }, true);



		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	/** Refreshes all forward and monster slots on the given player's side to reflect updated field grants. */
	private void refreshFieldGrantDependents(boolean isP1) {
		if (isP1) {
			for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
			for (int i = 0; i < p1MonsterCards.size(); i++) refreshP1MonsterSlot(i);
		} else {
			for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
			for (int i = 0; i < p2MonsterCards.size(); i++) refreshP2MonsterSlot(i);
		}
	}

	/** Reloads and re-renders a single P1 forward slot using its stored URL and state. */
	private void refreshP1ForwardSlot(int idx) {
		CardData topCard = p1ForwardPrimedTop.get(idx);
		boolean  primed  = topCard != null;
		// Primed: display and stats come from the top card
		String    url    = primed ? topCard.imageUrl() : p1ForwardUrls.get(idx);
		CardState state  = p1ForwardStates.get(idx);
		JLabel    slot   = p1ForwardLabels.get(idx);
		if (url == null) return;
		boolean hasHaste  = effectiveP1HasTrait(idx, CardData.Trait.HASTE);
		boolean canAttack = gameState.getCurrentPhase() == GameState.GamePhase.ATTACK
				&& attackSubStep == 1
				&& state == CardState.ACTIVE
				&& !p1ForwardCannotAttack.contains(idx)
				&& !p1ForwardCannotAttackPersistent.contains(idx)
				&& (hasHaste || p1ForwardPlayedOnTurn.get(idx) != gameState.getTurnNumber());
		int damage    = p1ForwardDamage.get(idx);
		int power     = effectiveP1ForwardPower(idx);
		int basePower = (topCard != null ? topCard : p1ForwardCards.get(idx)).power();
		boolean selected = p1AttackSelection.contains(idx) || p1BlockerSelection == idx;
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.renderBackupCard(CardAnimation.toARGB(raw, CARD_W, CARD_H), state, canAttack, selected, Boolean.TRUE.equals(p1ForwardFrozen.get(idx)));
				if (damage > 0) {
					CardAnimation.renderDamageOverlay(canvas, damage);
				}
				if (power > basePower) {
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(80, 220, 80), state);
				} else if (power < basePower) {
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(230, 200, 60), state);
				}
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null) { slot.setIcon(icon); slot.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	private void refreshAllForwardSlots() {
		for (int i = 0; i < p1ForwardLabels.size(); i++) refreshP1ForwardSlot(i);
		for (int i = 0; i < p1MonsterLabels.size(); i++) refreshP1MonsterSlot(i);
	}

	private boolean isForwardSelectable(int idx) {
		if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return false;
		if (attackSubStep != 1) return false;
		if (idx < 0 || idx >= p1ForwardStates.size()) return false;
		if (p1ForwardStates.get(idx) != CardState.ACTIVE) return false;
		if (p1ForwardCannotAttack.contains(idx)) return false;
		if (p1ForwardCannotAttackPersistent.contains(idx)) return false;
		return effectiveP1HasTrait(idx, CardData.Trait.HASTE)
				|| p1ForwardPlayedOnTurn.get(idx) != gameState.getTurnNumber();
	}

	private boolean p1InBlockDeclaration() {
		return pendingP2Attacker != null || pendingP2PartyIndices != null;
	}

	/** Returns true if {@code idx} is a valid P1 blocker choice during block declaration. */
	private boolean isForwardBlockSelectable(int idx) {
		if (!p1InBlockDeclaration()) return false;
		if (idx < 0 || idx >= p1ForwardStates.size()) return false;
		CardState s = p1ForwardStates.get(idx);
		if (s != CardState.ACTIVE && s != CardState.BRAVE_ATTACKED) return false;
		if (p1ForwardCannotBlock.contains(idx)) return false;
		if (p1ForwardCannotBlockPersistent.contains(idx)) return false;
		// Check attacker-side unblockability
		if (p2ForwardCannotBeBlocked.contains(pendingP2AttackerIdx)) return false;
		int[] costFilter = p2ForwardCannotBeBlockedByCost.get(pendingP2AttackerIdx);
		if (costFilter != null && blockerCostExcluded(p1ForwardCards.get(idx).cost(), costFilter)) return false;
		// If any forward must block, restrict choices to those
		if (!p1ForwardMustBlock.isEmpty() && !p1ForwardMustBlock.contains(idx)) return false;
		return true;
	}

	private void toggleAttackSelection(int idx) {
		if (!isForwardSelectable(idx)) return;
		if (p1AttackSelection.contains(idx)) {
			p1AttackSelection.remove((Integer) idx);
			refreshAttackButton();
			refreshP1ForwardSlot(idx);
			return;
		}
		if (!p1AttackSelection.isEmpty()) {
			String partyElement = p1ForwardCards.get(p1AttackSelection.get(0)).elements()[0];
			if (!p1ForwardCards.get(idx).containsElement(partyElement)) {
				logEntry("Cannot add to party — different element");
				return;
			}
		}
		p1AttackSelection.add(idx);
		refreshAttackButton();
		refreshP1ForwardSlot(idx);
	}

	/**
	 * Dispatches a left-click on a P1 forward during the attack phase.
	 * In block-declaration mode (P2 is attacking), toggles the blocker selection.
	 * In attack-declaration mode (sub-step 1), toggles the attacker selection.
	 */
	private void handleP1ForwardLeftClick(int idx) {
		if (fieldTargetingActive) return;
		if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return;
		if (p1InBlockDeclaration()) {
			toggleP1BlockerSelection(idx);
		} else {
			toggleAttackSelection(idx);
		}
	}

	/** Toggles the P1 blocker selection during block-declaration sub-step. */
	private void toggleP1BlockerSelection(int idx) {
		if (!isForwardBlockSelectable(idx)) return;
		p1BlockerSelection = (p1BlockerSelection == idx) ? -1 : idx;
		refreshAttackButton();
		refreshAllForwardSlots();
	}

	/** Called when P1 clicks the Attack/Block/Take-Damage button during block declaration. */
	private void handleP1BlockAction() {
		if (pendingP2PartyIndices != null) { handleP1PartyBlockAction(); return; }
		if (pendingP2Attacker == null) return;
		CardData attacker      = pendingP2Attacker;
		int      attackerIdx   = pendingP2AttackerIdx;
		Runnable onDone        = pendingP2BlockDone;
		int      blockerIdx    = p1BlockerSelection;
		boolean  isMonster     = pendingP2AttackerIsMonster;
		int      attackerPower = pendingP2AttackerPower;

		// Clear pending state before any callbacks to avoid re-entrancy
		pendingP2Attacker           = null;
		pendingP2AttackerIdx        = -1;
		pendingP2BlockDone          = null;
		p1BlockerSelection          = -1;
		pendingP2AttackerIsMonster  = false;
		pendingP2AttackerPower      = 0;
		refreshAttackButton();

		if (blockerIdx >= 0 && blockerIdx < p1ForwardCards.size()) {
			CardData top    = p1ForwardPrimedTop.get(blockerIdx);
			CardData blocker = (top != null) ? top : p1ForwardCards.get(blockerIdx);
			p1BlockingIdx        = blockerIdx;
			p1BlockedByAttacker  = attacker;
			triggerAutoAbilitiesForBlock(blocker, true);
			triggerAutoAbilitiesForIsBlocked(attacker, false);
			setAttackSubStep(3);
			combatPriority("Blocker Declared", false, () -> {
				if (isMonster) {
					resolveP2MonsterAttackerCombat(attacker, attackerIdx, attackerPower, blocker, blockerIdx);
				} else {
					resolveCombat(attacker, false, attackerIdx, blocker, true, blockerIdx);
				}
				p1BlockingIdx       = -1;
				p1BlockedByAttacker = null;
				setAttackSubStep(-1);
				refreshAllForwardSlots();
				onDone.run();
			});
		} else {
			p1TakeDamage(() -> {
				triggerAutoAbilitiesForDealsDamageToOpponent(attacker, false);
				setAttackSubStep(-1);
				onDone.run();
			});
		}
	}

	private void handleP1PartyBlockAction() {
		List<Integer> attackerIndices = pendingP2PartyIndices;
		int           combinedPower   = pendingP2PartyCombined;
		Runnable      onDone          = pendingP2BlockDone;
		int           blockerIdx      = p1BlockerSelection;

		pendingP2PartyIndices  = null;
		pendingP2PartyCombined = 0;
		pendingP2BlockDone     = null;
		p1BlockerSelection     = -1;
		refreshAttackButton();

		if (blockerIdx >= 0 && blockerIdx < p1ForwardCards.size()) {
			CardData top    = p1ForwardPrimedTop.get(blockerIdx);
			CardData blocker = (top != null) ? top : p1ForwardCards.get(blockerIdx);
			p1BlockingIdx = blockerIdx;
			triggerAutoAbilitiesForBlock(blocker, true);
			for (int idx : attackerIndices)
				triggerAutoAbilitiesForIsBlocked(p2ForwardCards.get(idx), false);
			setAttackSubStep(3);
			combatPriority("Blocker Declared", false, () -> {
				resolveP1BlockVsP2Party(blockerIdx, blocker, attackerIndices, combinedPower);
				p1BlockingIdx       = -1;
				p1BlockedByAttacker = null;
				setAttackSubStep(-1);
				refreshAllForwardSlots();
				onDone.run();
			});
		} else {
			p1TakeDamage();
			for (int idx : attackerIndices)
				triggerAutoAbilitiesForDealsDamageToOpponent(p2ForwardCards.get(idx), false);
			setAttackSubStep(-1);
			onDone.run();
		}
	}

	/** Sets attackSubStep and updates the phaseTracker sub-diamond. */
	private void setAttackSubStep(int step) {
		attackSubStep = step;
		if (phaseTracker != null && step >= 0
				&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
			phaseTracker.setAttackStep(step);
		}
		if (nextPhaseButton != null && step == 1) nextPhaseButton.setEnabled(false);
	}

	/** Returns true if any P1 field card has at least one action ability. */
	private boolean p1HasActivatableAbilities() {
		for (CardData c : p1ForwardCards)
			if (!c.actionAbilities().isEmpty()) return true;
		for (CardData c : p1BackupCards)
			if (c != null && !c.actionAbilities().isEmpty()) return true;
		for (CardData c : p1MonsterCards)
			if (!c.actionAbilities().isEmpty()) return true;
		for (CardData c : gameState.getP1Hand())
			if (c.isSummon()) return true;
		return false;
	}

	/** Returns true if any P2 field card has at least one action ability. */
	private boolean p2HasActivatableAbilities() {
		for (CardData c : p2ForwardCards)
			if (!c.actionAbilities().isEmpty()) return true;
		for (CardData c : p2BackupCards)
			if (c != null && !c.actionAbilities().isEmpty()) return true;
		for (CardData c : p2MonsterCards)
			if (!c.actionAbilities().isEmpty()) return true;
		for (CardData c : gameState.getP2Hand())
			if (c.isSummon()) return true;
		return false;
	}

	/**
	 * Shows a modal-ish combat priority JWindow for P1 (interactive: OK button + 8s countdown).
	 * Calls {@code onPass} when OK is clicked or the countdown expires.
	 */
	private void showCombatPriorityWindow(String label, Runnable onPass) {
		if (combatPriorityWindow != null) { combatPriorityWindow.dispose(); combatPriorityWindow = null; }
		if (combatPriorityTimer  != null) { combatPriorityTimer.stop();    combatPriorityTimer  = null; }

		javax.swing.JWindow win = new javax.swing.JWindow(frame);
		combatPriorityWindow = win;

		JPanel panel = new JPanel(new BorderLayout(6, 6));
		panel.setBackground(new Color(28, 24, 40));
		panel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(new Color(80, 180, 110), 2),
				BorderFactory.createEmptyBorder(10, 14, 10, 14)));

		JLabel header = new JLabel("COMBAT", SwingConstants.CENTER);
		header.setFont(FontLoader.loadPixelNESFont(13));
		header.setForeground(new Color(120, 230, 140));
		panel.add(header, BorderLayout.NORTH);

		JLabel stepLabel = new JLabel(label, SwingConstants.CENTER);
		stepLabel.setFont(FontLoader.loadPixelNESFont(10));
		stepLabel.setForeground(Color.WHITE);

		JLabel priorityLabel = new JLabel("Your priority", SwingConstants.CENTER);
		priorityLabel.setFont(FontLoader.loadPixelNESFont(9));
		priorityLabel.setForeground(new Color(0x4ab4ff));

		int[] countdown = { 8 };
		JLabel countdownLabel = new JLabel("OK in 8...", SwingConstants.CENTER);
		countdownLabel.setFont(FontLoader.loadPixelNESFont(9));
		countdownLabel.setForeground(Color.LIGHT_GRAY);

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));
		center.setOpaque(false);
		stepLabel.setAlignmentX(0.5f);
		priorityLabel.setAlignmentX(0.5f);
		countdownLabel.setAlignmentX(0.5f);
		center.add(stepLabel);
		center.add(javax.swing.Box.createVerticalStrut(4));
		center.add(priorityLabel);
		center.add(javax.swing.Box.createVerticalStrut(4));
		center.add(countdownLabel);
		panel.add(center, BorderLayout.CENTER);

		JButton okBtn = new JButton("OK (Pass)");
		okBtn.setFont(FontLoader.loadPixelNESFont(11));
		okBtn.setFocusPainted(false);
		Runnable proceed = () -> {
			if (combatPriorityTimer  != null) { combatPriorityTimer.stop();  combatPriorityTimer  = null; }
			if (combatPriorityWindow != null) { combatPriorityWindow.dispose(); combatPriorityWindow = null; }
			onPass.run();
		};
		okBtn.addActionListener(ae -> proceed.run());

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 4));
		south.setOpaque(false);
		south.add(okBtn);
		panel.add(south, BorderLayout.SOUTH);

		win.getContentPane().add(panel);
		win.pack();
		java.awt.Point loc = frame.getLocationOnScreen();
		win.setLocation(loc.x + (frame.getWidth() - win.getWidth()) / 2,
				loc.y + (frame.getHeight() - win.getHeight()) / 2);
		win.setVisible(true);

		combatPriorityTimer = new javax.swing.Timer(1000, null);
		combatPriorityTimer.addActionListener(e -> {
			countdown[0]--;
			if (countdown[0] <= 0) {
				proceed.run();
			} else {
				countdownLabel.setText("OK in " + countdown[0] + "...");
			}
		});
		combatPriorityTimer.start();
	}

	/**
	 * Auto-pass for the AI opponent: briefly flips the phase tracker to red (P2's priority),
	 * waits ~1.5 s, then restores blue and calls {@code onDone}.
	 */
	private void p2AutoPass(Runnable onDone) {
		if (p2AutoPassTimer != null) { p2AutoPassTimer.stop(); p2AutoPassTimer = null; }
		phaseTracker.setHasPriority(false);
		p2AutoPassTimer = new javax.swing.Timer(1500, e -> {
			((javax.swing.Timer) e.getSource()).stop();
			p2AutoPassTimer = null;
			phaseTracker.setHasPriority(true);
			onDone.run();
		});
		p2AutoPassTimer.setRepeats(false);
		p2AutoPassTimer.start();
	}

	/**
	 * Runs a full two-player priority sequence for a combat checkpoint.
	 * {@code p1IsAttacker} == true: P1 goes first (interactive window), then P2 auto-passes.
	 * {@code p1IsAttacker} == false: P2 auto-passes first, then P1 gets an interactive window
	 * (but only if P1 has activatable abilities; otherwise both auto-advance).
	 */
	private void combatPriority(String label, boolean p1IsAttacker, Runnable onBothDone) {
		if (p1IsAttacker) {
			// P1 priority first
			if (p1HasActivatableAbilities()) {
				showCombatPriorityWindow(label, () -> p2AutoPass(onBothDone));
			} else {
				p2AutoPass(onBothDone);
			}
		} else {
			// P2 priority first (auto), then P1
			p2AutoPass(() -> {
				if (p1HasActivatableAbilities()) {
					showCombatPriorityWindow(label, onBothDone);
				} else {
					onBothDone.run();
				}
			});
		}
	}

	/**
	 * After combat damage resolves, checks whether P1 has more eligible attackers.
	 * If yes, returns to sub-step 1 (Declare). If no, ends the attack phase.
	 */
	private void continueAttackPhase() {
		p1AttackSelection.clear();
		p1MonsterAttackIdx = -1;
		refreshAllForwardSlots();
		if (hasAttackableForward()) {
			setAttackSubStep(1);
			refreshAttackButton();
			logEntry("Select next attacker, or click Skip to end the Attack Phase.");
		} else {
			onNextPhase();
		}
	}

	/** Returns true when the P1 monster at {@code idx} can attack as a Forward this turn. */
	/** Returns true when the P1 monster at {@code idx} currently has the Forward type. */
	private boolean isP1MonsterTemporarilyForward(int idx) {
		if (idx < 0 || idx >= p1MonsterCards.size()) return false;
		CardData card = p1MonsterCards.get(idx);
		if (p1MonsterTempForwardPower.containsKey(card)) return true;
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		if (bfa == null) return false;
		if (bfa.damageThreshold() > 0)
			return gameState.getP1DamageZone().size() >= bfa.damageThreshold();
		return gameState.getCurrentPlayer() == GameState.Player.P1;
	}

	/** Returns true when the P2 monster at {@code idx} currently has the Forward type. */
	private boolean isP2MonsterTemporarilyForward(int idx) {
		if (idx < 0 || idx >= p2MonsterCards.size()) return false;
		CardData card = p2MonsterCards.get(idx);
		if (p2MonsterTempForwardPower.containsKey(card)) return true;
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		if (bfa == null) return false;
		if (bfa.damageThreshold() > 0)
			return gameState.getP2DamageZone().size() >= bfa.damageThreshold();
		return gameState.getCurrentPlayer() == GameState.Player.P2;
	}

	private boolean isMonsterSelectableAsForward(int idx) {
		if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return false;
		if (gameState.getCurrentPlayer() != GameState.Player.P1) return false;
		if (idx < 0 || idx >= p1MonsterStates.size()) return false;
		if (p1MonsterStates.get(idx) != CardState.ACTIVE) return false;
		CardData card = p1MonsterCards.get(idx);
		if (!p1MonsterTempForwardPower.containsKey(card)) {
			CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
			if (bfa == null) return false;
			if (bfa.damageThreshold() > 0 && gameState.getP1DamageZone().size() < bfa.damageThreshold()) return false;
		}
		return p1MonsterPlayedOnTurn.get(idx) != gameState.getTurnNumber();
	}

	/** Handles a left-click on a P1 monster slot during the attack phase. */
	private void handleP1MonsterLeftClick(int idx) {
		if (fieldTargetingActive) return;
		if (attackSubStep != 1) return;
		if (!isMonsterSelectableAsForward(idx)) return;
		if (!p1AttackSelection.isEmpty()) {
			logEntry("Deselect the Forward first before selecting a Monster attacker.");
			return;
		}
		if (p1MonsterAttackIdx == idx) {
			p1MonsterAttackIdx = -1;
		} else {
			if (p1MonsterAttackIdx >= 0) {
				int prev = p1MonsterAttackIdx;
				p1MonsterAttackIdx = -1;
				refreshP1MonsterSlot(prev);
			}
			p1MonsterAttackIdx = idx;
		}
		refreshAttackButton();
		refreshP1MonsterSlot(idx);
	}

	/**
	 * Executes a P1 attack where the attacker is a Monster that temporarily becomes a Forward.
	 * The monster dulls, triggers attack auto-abilities, then resolves combat the same way a
	 * Forward would.
	 */
	private void executeP1MonsterAttack(int monIdx) {
		CardData attacker = p1MonsterCards.get(monIdx);
		CardData.BecomeForwardAbility bfa = attacker.becomeForwardAbility();
		int attackerPower = bfa != null ? bfa.power()
				: p1MonsterTempForwardPower.getOrDefault(attacker, 0);

		p1MonsterStates.set(monIdx, CardState.DULL);
		refreshP1MonsterSlot(monIdx);
		triggerAutoAbilitiesForAttack(attacker, true);

		setAttackSubStep(2);
		refreshAttackButton();

		logEntry(attacker.name() + " attacks! (Forward — " + attackerPower + ")");
		combatPriority("Attacker Declared", true, () -> {
			int blockerIdx = p2ChooseBlocker(attackerPower, -1);
			if (blockerIdx >= 0) {
				CardData blocker = p2ForwardCards.get(blockerIdx);
				logEntry("[P2] " + blocker.name() + " blocks!");
				triggerAutoAbilitiesForBlock(blocker, false);
				triggerAutoAbilitiesForIsBlocked(attacker, true);
				p2BlockingIdx       = blockerIdx;
				p2BlockedByAttacker = attacker;
				setAttackSubStep(3);
				combatPriority("Blocker Declared", true, () -> {
					resolveMonsterAttackerCombat(attacker, monIdx, attackerPower, blocker, blockerIdx);
					p2BlockingIdx       = -1;
					p2BlockedByAttacker = null;
					continueAttackPhase();
				});
			} else {
				setAttackSubStep(3);
				p2TakeDamage(() -> {
					triggerAutoAbilitiesForDealsDamageToOpponent(attacker, true);
					continueAttackPhase();
				});
			}
		});
	}

	/**
	 * Resolves combat between a P1 monster-as-Forward attacker and a P2 Forward blocker.
	 * Damage accumulates on the monster (tracked in {@code p1MonsterDamage}) and is compared
	 * against its become-Forward power — matching existing Forward combat rules.
	 */
	private void resolveMonsterAttackerCombat(CardData attacker, int monIdx, int attackerPower,
			CardData blocker, int blockerIdx) {
		int effBlockerPow = effectiveP2ForwardPower(blockerIdx);
		logEntry(attacker.name() + " (" + attackerPower + ") vs [P2] "
				+ blocker.name() + " (" + effBlockerPow + ")");

		int curMonDmg   = p1MonsterDamage.get(monIdx);
		boolean monsterBroken = effBlockerPow > 0 && curMonDmg + effBlockerPow >= attackerPower;
		boolean blockerBroken = attackerPower > 0
				&& p2ForwardDamage.get(blockerIdx) + attackerPower >= effBlockerPow;

		if (monsterBroken) {
			breakP1MonsterSlot(monIdx);
		} else if (effBlockerPow > 0) {
			p1MonsterDamage.set(monIdx, curMonDmg + effBlockerPow);
			refreshP1MonsterSlot(monIdx);
		}

		if (blockerBroken) {
			breakP2Forward(blockerIdx);
		} else if (attackerPower > 0) {
			p2ForwardDamage.set(blockerIdx, p2ForwardDamage.get(blockerIdx) + attackerPower);
			refreshP2ForwardSlot(blockerIdx);
		}
	}

	private void resolveP2MonsterAttackerCombat(CardData attacker, int monIdx, int attackerPower,
			CardData blocker, int blockerIdx) {
		int effBlockerPow = effectiveP1ForwardPower(blockerIdx);
		logEntry("[P2] " + attacker.name() + " (" + attackerPower + ") vs "
				+ blocker.name() + " (" + effBlockerPow + ")");

		int curMonDmg     = p2MonsterDamage.get(monIdx);
		boolean monsterBroken = effBlockerPow > 0 && curMonDmg + effBlockerPow >= attackerPower;
		boolean blockerBroken = attackerPower > 0
				&& p1ForwardDamage.get(blockerIdx) + attackerPower >= effBlockerPow;

		if (monsterBroken) {
			breakP2MonsterSlot(monIdx);
		} else if (effBlockerPow > 0) {
			p2MonsterDamage.set(monIdx, curMonDmg + effBlockerPow);
			refreshP2MonsterSlot(monIdx);
		}

		if (blockerBroken) {
			breakP1Forward(blockerIdx);
		} else if (attackerPower > 0) {
			p1ForwardDamage.set(blockerIdx, p1ForwardDamage.get(blockerIdx) + attackerPower);
			refreshP1ForwardSlot(blockerIdx);
		}
	}

	/** Returns the power a P2 monster uses when attacking as a Forward. */
	private int p2MonsterForwardPower(int idx) {
		CardData card = p2MonsterCards.get(idx);
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		return bfa != null ? bfa.power() : p2MonsterTempForwardPower.getOrDefault(card, 0);
	}

	/** Returns true when the P2 monster at {@code idx} can attack as a Forward this turn. */
	private boolean p2MonsterCanAttackAsForward(int idx) {
		if (p2MonsterStates.get(idx) != CardState.ACTIVE) return false;
		if (!isP2MonsterTemporarilyForward(idx)) return false;
		return p2MonsterPlayedOnTurn.get(idx) != gameState.getTurnNumber();
	}

	private void refreshAttackButton() {
		if (attackButton == null) return;
		boolean inAttack = gameState.getCurrentPhase() == GameState.GamePhase.ATTACK;
		boolean p1Turn   = gameState.getCurrentPlayer() == GameState.Player.P1;

		if (p1InBlockDeclaration()) {
			// Block declaration mode: P1 chooses a blocker by clicking a forward
			boolean hasBlocker = p1BlockerSelection >= 0;
			attackButton.setText(hasBlocker ? "Block" : "Take Damage");
			attackButton.setEnabled(true);
		} else {
			int n = p1AttackSelection.size();
			boolean hasAnyAttacker = n > 0 || p1MonsterAttackIdx >= 0;
			attackButton.setEnabled(inAttack && p1Turn && hasAnyAttacker && attackSubStep == 1);
			attackButton.setText(n > 1 ? "Party Attack" : "Attack");
		}

		if (skipAttackButton != null)
			skipAttackButton.setEnabled(inAttack && p1Turn && attackSubStep == 1
					&& !p1InBlockDeclaration());
	}

	private void executeP1Attack(List<Integer> selection) {
		if (selection.isEmpty()) return;

		// Dull / BRAVE_ATTACKED attackers and trigger their attack auto-abilities
		for (int idx : selection) {
			if (effectiveP1HasTrait(idx, CardData.Trait.BRAVE)) {
				p1ForwardStates.set(idx, CardState.BRAVE_ATTACKED);
				refreshP1ForwardSlot(idx);
			} else {
				p1ForwardStates.set(idx, CardState.DULL);
				animateDullForward(idx, null);
			}
		}
		for (int idx : selection)
			triggerAutoAbilitiesForAttack(p1ForwardCards.get(idx), true);

		setAttackSubStep(2); // moving to block-declaration sub-step
		refreshAttackButton();

		if (selection.size() == 1) {
			int idx = selection.get(0);
			CardData attacker = p1ForwardCards.get(idx);
			logEntry(attacker.name() + " attacks!");
			// Priority window after attacker declared (P1 attacks → P1 priority first)
			combatPriority("Attacker Declared", true, () -> {
				int blockerIdx = p2ChooseBlocker(effectiveP1ForwardPower(idx), idx);
				if (blockerIdx >= 0) {
					CardData blocker = p2ForwardCards.get(blockerIdx);
					logEntry("[P2] " + blocker.name() + " blocks!");
					triggerAutoAbilitiesForBlock(blocker, false);
					triggerAutoAbilitiesForIsBlocked(attacker, true);
					p2BlockingIdx       = blockerIdx;
					p2BlockedByAttacker = attacker;
					setAttackSubStep(3);
					// Priority window after blocker declared (P1 still attacker → P1 first)
					combatPriority("Blocker Declared", true, () -> {
						resolveCombat(attacker, true, idx, blocker, false, blockerIdx);
						p2BlockingIdx       = -1;
						p2BlockedByAttacker = null;
						continueAttackPhase();
					});
				} else {
					setAttackSubStep(3);
					p2TakeDamage(() -> {
						triggerAutoAbilitiesForDealsDamageToOpponent(attacker, true);
						continueAttackPhase();
					});
				}
			});
		} else {
			int combinedPower = 0;
			StringBuilder names = new StringBuilder();
			for (int idx : selection) {
				combinedPower += effectiveP1ForwardPower(idx);
				if (names.length() > 0) names.append(", ");
				names.append(p1ForwardCards.get(idx).name());
			}
			logEntry("Party Attack! " + names + " (" + combinedPower + " combined)");
			triggerAutoAbilitiesForPartyAttack(true);
			final int fCombined = combinedPower;
			combatPriority("Party Attacker Declared", true, () ->
				p2OfferBlockParty(selection, fCombined, this::continueAttackPhase));
		}
	}

	private void p2OfferBlockParty(List<Integer> attackerIndices, int combinedPower, Runnable onDone) {
		int bestBlockerIdx = -1, bestBlockerPower = 0;
		int minAttackerPower = Integer.MAX_VALUE;
		for (int idx : attackerIndices) {
			if (idx < p1ForwardCards.size())
				minAttackerPower = Math.min(minAttackerPower,
						effectiveP1ForwardPower(idx) - p1ForwardDamage.get(idx));
		}
		for (int i = 0; i < p2ForwardStates.size(); i++) {
			if (p2ForwardStates.get(i) != CardState.ACTIVE) continue;
			int pw = effectiveP2ForwardPower(i);
			if (pw >= minAttackerPower && pw > bestBlockerPower) {
				bestBlockerPower = pw;
				bestBlockerIdx = i;
			}
		}
		if (bestBlockerIdx >= 0) {
			CardData blocker = p2ForwardCards.get(bestBlockerIdx);
			int blockerPower = effectiveP2ForwardPower(bestBlockerIdx);
			logEntry("[P2] " + blocker.name() + " blocks the party!");
			if (combinedPower >= blockerPower) breakP2Forward(bestBlockerIdx);
			applyPartyBlockerDamage(p2AiBuildDamageMap(attackerIndices, blockerPower));
			if (onDone != null) onDone.run();
		} else {
			p2TakeDamage(onDone);
		}
	}

	/** Builds the AI's optimal damage assignment for a party-attack block. */
	private Map<Integer, Integer> p2AiBuildDamageMap(List<Integer> attackerIndices, int blockerPower) {
		List<int[]> targets = new ArrayList<>();
		for (int idx : attackerIndices) {
			if (idx < p1ForwardCards.size()) {
				int hp = effectiveP1ForwardPower(idx) - p1ForwardDamage.get(idx);
				targets.add(new int[]{ idx, hp });
			}
		}
		if (targets.isEmpty()) return Map.of();
		targets.sort((a, b) -> Integer.compare(a[1], b[1]));
		Map<Integer, Integer> damageMap = new LinkedHashMap<>();
		int remaining = blockerPower;
		for (int[] t : targets) {
			if (remaining <= 0) break;
			int idx = t[0], hp = t[1];
			int dmg = Math.min(remaining, roundToThousand(hp));
			damageMap.put(idx, dmg);
			remaining -= dmg;
		}
		if (remaining > 0)
			damageMap.merge(targets.get(targets.size() - 1)[0], remaining, Integer::sum);
		return damageMap;
	}

	/** Applies a party-block damage map: logs, updates p1ForwardDamage, and breaks lethal targets. */
	private void applyPartyBlockerDamage(Map<Integer, Integer> damageMap) {
		if (damageMap.isEmpty()) return;
		for (Map.Entry<Integer, Integer> entry : damageMap.entrySet()) {
			int idx = entry.getKey(), dmg = entry.getValue();
			if (idx >= p1ForwardCards.size()) continue;
			p1ForwardDamage.set(idx, p1ForwardDamage.get(idx) + dmg);
			logEntry("[P2] Deals " + dmg + " damage to " + p1ForwardCards.get(idx).name());
		}
		List<Integer> toBreak = new ArrayList<>();
		for (int idx : damageMap.keySet()) {
			if (idx < p1ForwardCards.size()
					&& p1ForwardDamage.get(idx) >= effectiveP1ForwardPower(idx))
				toBreak.add(idx);
		}
		toBreak.sort(Collections.reverseOrder());
		for (int idx : toBreak) breakP1Forward(idx);
		for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
	}

	/** P1 blocks a P2 party attack: combined power hits the blocker; P1 assigns blocker power back. */
	private void resolveP1BlockVsP2Party(int blockerIdx, CardData blocker,
			List<Integer> attackerIndices, int combinedPower) {
		int blockerPower = effectiveP1ForwardPower(blockerIdx);
		logEntry("[P2] Party deals " + combinedPower + " damage to " + blocker.name());
		if (combinedPower >= blockerPower) breakP1Forward(blockerIdx);

		List<CardData> attackerCards = new ArrayList<>();
		int[] effectivePowers = new int[attackerIndices.size()];
		for (int i = 0; i < attackerIndices.size(); i++) {
			int idx = attackerIndices.get(i);
			attackerCards.add(p2ForwardCards.get(idx));
			effectivePowers[i] = effectiveP2ForwardPower(idx);
		}
		Map<Integer, Integer> damageMap = showPartyDamageAssignmentDialog(
				attackerIndices, attackerCards, effectivePowers, blockerPower);
		if (damageMap.isEmpty()) damageMap = p2AiBuildDamageMap(attackerIndices, blockerPower);
		applyP2PartyAttackerDamage(damageMap);
	}

	/** Applies a damage map onto P2 party attackers; breaks those that reach lethal. */
	private void applyP2PartyAttackerDamage(Map<Integer, Integer> damageMap) {
		if (damageMap.isEmpty()) return;
		for (Map.Entry<Integer, Integer> entry : damageMap.entrySet()) {
			int idx = entry.getKey(), dmg = entry.getValue();
			if (idx >= p2ForwardCards.size()) continue;
			p2ForwardDamage.set(idx, p2ForwardDamage.get(idx) + dmg);
			logEntry("Deals " + dmg + " damage to " + p2ForwardCards.get(idx).name());
		}
		List<Integer> toBreak = new ArrayList<>();
		for (int idx : damageMap.keySet()) {
			if (idx < p2ForwardCards.size()
					&& p2ForwardDamage.get(idx) >= effectiveP2ForwardPower(idx))
				toBreak.add(idx);
		}
		toBreak.sort(Collections.reverseOrder());
		for (int idx : toBreak) breakP2Forward(idx);
		for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
	}

	private static int roundToThousand(int value) {
		return ((value + 999) / 1000) * 1000;
	}

	/** Returns the index of the least-valuable card in {@code hand} (lowest cost; backups before forwards). */
	private static int pickWorstHandCard0(List<CardData> hand) {
		int worstIdx = 0, worstScore = Integer.MAX_VALUE;
		for (int i = 0; i < hand.size(); i++) {
			CardData c = hand.get(i);
			int score = c.cost() + (c.isForward() ? 10 : 0);
			if (score < worstScore) { worstScore = score; worstIdx = i; }
		}
		return worstIdx;
	}

	private boolean hasBackAttackInHand() {
		return gameState.getP1Hand().stream()
				.anyMatch(c -> c.hasTrait(CardData.Trait.BACK_ATTACK));
	}

	private boolean hasAttackableForward() {
		int turn = gameState.getTurnNumber();
		for (int i = 0; i < p1ForwardStates.size(); i++) {
			if (p1ForwardStates.get(i) == CardState.ACTIVE
					&& !p1ForwardCannotAttack.contains(i)
					&& !p1ForwardCannotAttackPersistent.contains(i)
					&& !Boolean.TRUE.equals(p1ForwardFrozen.get(i))
					&& (effectiveP1HasTrait(i, CardData.Trait.HASTE)
					    || p1ForwardPlayedOnTurn.get(i) != turn))
				return true;
		}
		for (int i = 0; i < p1MonsterStates.size(); i++) {
			if (p1MonsterStates.get(i) != CardState.ACTIVE) continue;
			if (Boolean.TRUE.equals(p1MonsterFrozen.get(i))) continue;
			if (p1MonsterPlayedOnTurn.get(i) == turn) continue;
			CardData.BecomeForwardAbility bfa = p1MonsterCards.get(i).becomeForwardAbility();
			if (bfa == null) continue;
			if (bfa.damageThreshold() > 0 && gameState.getP1DamageZone().size() < bfa.damageThreshold()) continue;
			return true;
		}
		return false;
	}

	/** Shows a context menu for a P1 forward slot. */
	private void showForwardContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();

		// Action abilities (use effective card — top card when primed)
		CardData effectiveFwd = p1ForwardPrimedTop.get(idx) != null
				? p1ForwardPrimedTop.get(idx) : p1ForwardCards.get(idx);
		addAbilityMenuItems(menu, effectiveFwd, p1ForwardFrozen.get(idx),
				p1ForwardStates.get(idx), p1ForwardPlayedOnTurn.get(idx),
				() -> { p1ForwardStates.set(idx, CardState.DULL); animateDullForward(idx, null); }, true);

		// Prime — visible whenever the forward has the Priming trait
		CardData fwd = p1ForwardCards.get(idx);
		if (fwd.hasPriming()) {
			boolean alreadyPrimed = p1ForwardPrimedTop.get(idx) != null;
			GameState.GamePhase phase = gameState.getCurrentPhase();
			boolean isMainPhase = phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.MAIN_2;
			JMenuItem primeItem = new JMenuItem("Prime (" + fwd.primingTarget() + ")");
			primeItem.setEnabled(isMainPhase && !alreadyPrimed && canAffordPrimingCost(fwd)
					&& !primingTargetOnField(fwd.primingTarget()));
			primeItem.addActionListener(ae -> showPrimingPaymentDialog(fwd, idx));
			menu.add(primeItem);
		}

		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	private void showP2BackupContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();
		CardData card = p2BackupCards[idx];
		if (card != null) {
			addAbilityMenuItems(menu, card, p2BackupFrozen[idx], p2BackupStates[idx], 0,
					() -> { p2BackupStates[idx] = CardState.DULL; animateDullP2Backup(idx, true); }, false);
		}
		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	private void showP2MonsterContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();
		addAbilityMenuItems(menu, p2MonsterCards.get(idx), p2MonsterFrozen.get(idx),
				p2MonsterStates.get(idx), p2MonsterPlayedOnTurn.get(idx),
				() -> { p2MonsterStates.set(idx, CardState.DULL); refreshP2MonsterSlot(idx); }, false);
		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	private void showP2ForwardContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();
		CardData fwd         = p2ForwardCards.get(idx);
		CardData effectiveFwd = p2ForwardPrimedTop.get(idx) != null ? p2ForwardPrimedTop.get(idx) : fwd;
		addAbilityMenuItems(menu, effectiveFwd, p2ForwardFrozen.get(idx),
				p2ForwardStates.get(idx), p2ForwardPlayedOnTurn.get(idx),
				() -> { p2ForwardStates.set(idx, CardState.DULL); refreshP2ForwardSlot(idx); }, false);

		if (fwd.hasPriming()) {
			boolean alreadyPrimed = p2ForwardPrimedTop.get(idx) != null;
			JMenuItem primeItem = new JMenuItem("Prime (" + fwd.primingTarget() + ")");
			primeItem.setEnabled(!alreadyPrimed && !primingTargetOnField(fwd.primingTarget()));
			primeItem.addActionListener(ae -> applyP2PrimedCard(fwd, idx));
			menu.add(primeItem);
		}

		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	/** Searches P2's deck for the priming target and sets it as the top card of the primed forward. */
	private void applyP2PrimedCard(CardData primingCard, int slotIdx) {
		String target = primingCard.primingTarget();
		List<CardData> matches = gameState.findMatchingNamesInP2MainDeck(target);
		if (matches.isEmpty()) {
			logEntry("[P2] Priming: \"" + target + "\" not found in deck");
			return;
		}
		CardData chosen = matches.get(0);
		gameState.removeFromP2MainDeck(chosen);
		p2ForwardPrimedTop.set(slotIdx, chosen);
		logEntry("[P2] Primed: \"" + primingCard.name() + "\" topped with \"" + chosen.name() + "\"");
		refreshP2ForwardSlot(slotIdx);
	}

	/**
	 * Returns true if {@code targetName} is already present on either player's field
	 * (as a base forward or a primed top card), which would violate the uniqueness rule
	 * if priming were performed.
	 */
	private boolean primingTargetOnField(String targetName) {
		for (int i = 0; i < p1ForwardCards.size(); i++) {
			if (p1ForwardCards.get(i).name().equalsIgnoreCase(targetName)) return true;
			CardData top = p1ForwardPrimedTop.get(i);
			if (top != null && top.name().equalsIgnoreCase(targetName)) return true;
		}
		for (int i = 0; i < p2ForwardCards.size(); i++) {
			if (p2ForwardCards.get(i).name().equalsIgnoreCase(targetName)) return true;
			CardData top = p2ForwardPrimedTop.get(i);
			if (top != null && top.name().equalsIgnoreCase(targetName)) return true;
		}
		return false;
	}

	/** Returns true if the player can afford the Priming cost of {@code card} (card is on the field, not in hand). */
	private boolean canAffordPrimingCost(CardData card) {
		List<String> cost = card.primingCost();
		if (cost.isEmpty()) return true;

		boolean hasGeneric = cost.contains("");
		LinkedHashMap<String, Integer> needed = new LinkedHashMap<>();
		for (String e : cost) if (!e.isEmpty()) needed.merge(e, 1, Integer::sum);
		String[] elems = needed.keySet().toArray(String[]::new);
		int total = cost.size();

		boolean[] hasSrc = new boolean[elems.length];
		int available = 0;

		for (int ei = 0; ei < elems.length; ei++) {
			int b = gameState.getP1CpForElement(elems[ei]);
			available += b;
			if (b > 0) hasSrc[ei] = true;
		}
		if (hasGeneric) {
			available += gameState.getP1CpByElement().values().stream().mapToInt(Integer::intValue).sum();
			for (int ei = 0; ei < elems.length; ei++) available -= gameState.getP1CpForElement(elems[ei]);
		}
		for (int i = 0; i < p1BackupCards.length; i++) {
			if (p1BackupCards[i] == null || p1BackupStates[i] != CardState.ACTIVE) continue;
			boolean matched = false;
			for (int ei = 0; ei < elems.length; ei++) {
				if (p1BackupCards[i].containsElement(elems[ei])) { available++; hasSrc[ei] = true; matched = true; break; }
			}
			if (!matched && hasGeneric) available++;
		}
		List<CardData> hand = gameState.getP1Hand();
		for (CardData h : hand) {
			if (h.isLightOrDark()) continue;
			available += 2;
			for (int ei = 0; ei < elems.length; ei++) if (h.containsElement(elems[ei])) hasSrc[ei] = true;
		}
		for (boolean s : hasSrc) if (!s) return false;
		return available >= total;
	}

	/**
	 * Payment dialog for the Priming ability cost. On confirm, searches the
	 * main deck for the target card and places it on top of the priming forward.
	 */
	private void showPrimingPaymentDialog(CardData card, int slotIdx) {
		List<String> rawCost = card.primingCost();
		long genericNeeded = rawCost.stream().filter(String::isEmpty).count();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
		String[] elems   = costByElem.keySet().toArray(String[]::new);
		int totalCost    = rawCost.size();

		// If cost is empty, no dialog needed — go straight to execution
		if (totalCost == 0) {
			executePriming(card, slotIdx, new ArrayList<>(), new ArrayList<>());
			return;
		}

		JDialog dlg = new JDialog(frame, "Prime: " + card.name(), true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		List<CardData> hand = gameState.getP1Hand();

		Map<String, Integer> bankCpByElem = new LinkedHashMap<>(costByElem);
		for (String k : bankCpByElem.keySet()) bankCpByElem.put(k, 0);

		List<Integer> selectedBackups  = new ArrayList<>();
		List<Integer> selectedDiscards = new ArrayList<>();

		List<Integer> eligibleBackupSlots = new ArrayList<>();
		for (int i = 0; i < p1BackupCards.length; i++) {
			if (p1BackupCards[i] != null && p1BackupStates[i] == CardState.ACTIVE
					&& (genericNeeded > 0 || matchesAnyElement(p1BackupCards[i], elems)))
				eligibleBackupSlots.add(i);
		}

		JLabel cpLabel = new JLabel();
		cpLabel.setFont(FontLoader.loadPixelNESFont(11));
		cpLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JButton confirmBtn = new JButton("Confirm (Prime)");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

		List<JLabel>   backupLbls  = new ArrayList<>();
		List<Integer>  backupSlots = new ArrayList<>();
		List<JLabel>   discardLbls = new ArrayList<>();
		List<Integer>  discardIdxs = new ArrayList<>();

		boolean[] canAddDiscard = {false};
		Runnable updateAll = () -> {
			Map<String, Integer> cpByElem = new LinkedHashMap<>(bankCpByElem);
			int extraCp = 0;
			for (int slot : selectedBackups) {
				if (matchesAnyElement(p1BackupCards[slot], elems))
					cpByElem.merge(contributingElement(p1BackupCards[slot], elems, cpByElem, costByElem), 1, Integer::sum);
				else extraCp++;
			}
			for (int idx : selectedDiscards) {
				if (matchesAnyElement(hand.get(idx), elems))
					cpByElem.merge(contributingElement(hand.get(idx), elems, cpByElem, costByElem), 2, Integer::sum);
				else extraCp += 2;
			}
			int total      = cpByElem.values().stream().mapToInt(Integer::intValue).sum() + extraCp;
			int unsatisfied = (int) java.util.stream.IntStream.range(0, elems.length)
					.filter(ei -> cpByElem.getOrDefault(elems[ei], 0) < costByElem.get(elems[ei])).count();
			int maxAllowed  = totalCost + elems.length + (totalCost % 2);
			boolean canAddBackup = total < totalCost;
			canAddDiscard[0] = (total + 2 <= maxAllowed) && (total < totalCost || unsatisfied > 0);
			boolean satisfied = cpByElem.entrySet().stream()
					.allMatch(en -> en.getValue() >= costByElem.getOrDefault(en.getKey(), 0));
			confirmBtn.setEnabled(total >= totalCost && satisfied);

			StringBuilder sb = new StringBuilder("Prime CP: " + total + " / " + totalCost + "  (");
			boolean first = true;
			for (String en : elems) {
				if (!first) sb.append(", ");
				sb.append(en).append(": ").append(cpByElem.getOrDefault(en, 0)).append("/").append(costByElem.get(en));
				first = false;
			}
			if (genericNeeded > 0) {
				if (!first) sb.append(", ");
				sb.append("any: ").append(Math.min(extraCp, (int) genericNeeded)).append("/").append((int) genericNeeded);
			}
			if (first) sb.append("free");
			sb.append(")");
			cpLabel.setText(sb.toString());

			for (int i = 0; i < backupLbls.size(); i++) {
				JLabel lbl = backupLbls.get(i); boolean sel = selectedBackups.contains(backupSlots.get(i));
				lbl.setBorder(sel ? createCardGlowBorder(Color.YELLOW) : BorderFactory.createLineBorder(canAddBackup ? Color.GRAY : new Color(80,80,80), 1));
				lbl.setBackground(sel || canAddBackup ? Color.DARK_GRAY : new Color(50,50,50));
				lbl.setCursor(sel || canAddBackup ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			for (int i = 0; i < discardLbls.size(); i++) {
				JLabel lbl = discardLbls.get(i); boolean sel = selectedDiscards.contains(discardIdxs.get(i));
				lbl.setBorder(sel ? createCardGlowBorder(Color.YELLOW) : BorderFactory.createLineBorder(canAddDiscard[0] ? Color.GRAY : new Color(80,80,80), 1));
				lbl.setBackground(sel || canAddDiscard[0] ? Color.DARK_GRAY : new Color(50,50,50));
				lbl.setCursor(sel || canAddDiscard[0] ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
		};
		updateAll.run();

		JPanel centerPanel = new JPanel();
		centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

		if (!eligibleBackupSlots.isEmpty()) {
			JLabel hdr = new JLabel("Backups — dull for 1 CP each:");
			hdr.setFont(FontLoader.loadPixelNESFont(9)); hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel bp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); bp.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (int slot : eligibleBackupSlots) {
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H)); lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true); lbl.setBackground(Color.DARK_GRAY); lbl.setForeground(Color.WHITE);
				lbl.setFont(FontLoader.loadPixelNESFont(10)); lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
				lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				final String url = p1BackupUrls[slot];
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent ev) {
						int tot = bankCpByElem.values().stream().mapToInt(Integer::intValue).sum() + selectedBackups.size() + selectedDiscards.size() * 2;
						if (selectedBackups.remove(Integer.valueOf(slot))) { /* deselect */ } else if (tot < totalCost) selectedBackups.add(slot);
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) showZoomAt(url); }
					@Override public void mouseExited(MouseEvent ev)  { hideZoom(); }
				});
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(url);
						return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
					}
					@Override protected void done() {
						try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
						catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();
				backupLbls.add(lbl); backupSlots.add(slot); bp.add(lbl);
			}
			centerPanel.add(hdr); centerPanel.add(bp);
		}

		JLabel discardHdr = new JLabel("Hand — discard for 2 CP each:");
		discardHdr.setFont(FontLoader.loadPixelNESFont(9)); discardHdr.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel dp = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6)); dp.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (int i = 0; i < hand.size(); i++) {
			final int hi = i; CardData hc = hand.get(i); boolean payable = !hc.isLightOrDark();
			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H)); lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true); lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50,50,50));
			lbl.setForeground(Color.WHITE); lbl.setFont(FontLoader.loadPixelNESFont(10));
			lbl.setBorder(BorderFactory.createLineBorder(payable ? Color.GRAY : new Color(80,80,80), 1));
			lbl.setCursor(payable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			final String imgUrl = hc.imageUrl();
			if (payable) {
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent ev) {
						if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0]) selectedDiscards.add(hi);
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) showZoomAt(imgUrl); }
					@Override public void mouseExited(MouseEvent ev)  { hideZoom(); }
				});
				discardLbls.add(lbl); discardIdxs.add(hi);
			} else {
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent ev) { if (lbl.getIcon() != null) showZoomAt(imgUrl); }
					@Override public void mouseExited(MouseEvent ev)  { hideZoom(); }
				});
			}
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(imgUrl);
					return img == null ? null : new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();
			dp.add(lbl);
		}
		centerPanel.add(discardHdr); centerPanel.add(dp);

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
		cancelBtn.addActionListener(ev -> dlg.dispose());
		confirmBtn.addActionListener(ev -> {
			dlg.dispose();
			executePriming(card, slotIdx, new ArrayList<>(selectedDiscards), new ArrayList<>(selectedBackups));
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonPanel.add(confirmBtn); buttonPanel.add(cancelBtn);

		StringBuilder costDesc = new StringBuilder();
		boolean f = true;
		for (Map.Entry<String, Integer> en : costByElem.entrySet()) {
			if (!f) costDesc.append(" + ");
			costDesc.append(en.getValue()).append(" ").append(en.getKey()).append(" CP"); f = false;
		}
		if (genericNeeded > 0) { if (!f) costDesc.append(" + "); costDesc.append((int) genericNeeded).append(" any CP"); }
		JLabel titleLabel = new JLabel(
				"Priming cost for: " + card.name() + "  (" + (costDesc.length() > 0 ? costDesc : "free") + ")",
				SwingConstants.CENTER);
		titleLabel.setFont(FontLoader.loadPixelNESFont(11));

		JPanel topPanel = new JPanel(new BorderLayout(0, 4));
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		topPanel.add(titleLabel, BorderLayout.NORTH); topPanel.add(cpLabel, BorderLayout.CENTER);

		JPanel mainPanel = new JPanel(new BorderLayout(0, 4));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
		mainPanel.add(new JScrollPane(centerPanel), BorderLayout.CENTER);
		mainPanel.add(buttonPanel, BorderLayout.SOUTH);

		dlg.getContentPane().setLayout(new BorderLayout());
		dlg.getContentPane().add(topPanel, BorderLayout.NORTH);
		dlg.getContentPane().add(mainPanel, BorderLayout.CENTER);
		dlg.pack(); dlg.setLocationRelativeTo(frame); dlg.setVisible(true);
	}

	/**
	 * Pays the Priming cost, searches the main deck for the target card, and if
	 * found places it as the top card of the primed forward.  The deck is shuffled
	 * after the search regardless of whether the card was found.
	 */
	private void executePriming(CardData card, int slotIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices) {
		List<String> rawCost = card.primingCost();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);

		// Pay cost
		for (int bi : backupDullIndices) {
			p1BackupStates[bi] = CardState.DULL;
			animateDullBackup(bi, true);
			String cpElem = matchesAnyElement(p1BackupCards[bi], elems)
					? contributingElement(p1BackupCards[bi], elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) gameState.addP1Cp(cpElem, 1);
		}
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
			CardData discarded = gameState.getP1Hand().get(di);
			String cpElem = matchesAnyElement(discarded, elems)
					? contributingElement(discarded, elems) : (elems.length > 0 ? elems[0] : "");
			if (!cpElem.isEmpty()) gameState.addP1Cp(cpElem, 2);
			gameState.breakFromHand(di);
		}
		for (String e : elems) { gameState.spendP1Cp(e, gameState.getP1CpForElement(e)); gameState.clearP1Cp(e); }

		// Search deck — find all versions of the target card
		String target = card.primingTarget();
		List<CardData> matches = gameState.findMatchingNamesInP1MainDeck(target);

		if (matches.isEmpty()) {
			shuffleP1MainDeck();
			logEntry("Priming: \"" + target + "\" not found in deck — no card placed");
			refreshP1HandLabel();
			refreshP1BreakLabel();
		} else if (matches.size() == 1) {
			gameState.removeFromP1MainDeck(matches.get(0));
			shuffleP1MainDeck();
			applyPrimedCard(matches.get(0), card, slotIdx);
			refreshP1HandLabel();
			refreshP1BreakLabel();
		} else {
			// Multiple printings found — let the player choose; shuffle and refresh happen inside the dialog
			showPrimingVersionSelectDialog(matches, card, slotIdx);
		}
	}

	/** Shuffles P1's main deck in-place and refreshes the deck label. */
	private void shuffleP1MainDeck() {
		List<CardData> list = new ArrayList<>(gameState.getP1MainDeck());
		Collections.shuffle(list);
		gameState.getP1MainDeck().clear();
		gameState.getP1MainDeck().addAll(list);
		refreshP1DeckLabel();
	}

	/** Places {@code chosen} as the primed top card on {@code slotIdx} and logs the action. */
	private void applyPrimedCard(CardData chosen, CardData primingCard, int slotIdx) {
		p1ForwardPrimedTop.set(slotIdx, chosen);
		logEntry("Primed: \"" + primingCard.name() + "\" topped with \"" + chosen.name() + "\"");
		refreshP1ForwardSlot(slotIdx);
	}

	/**
	 * Shows a modal dialog letting the player pick which version of the priming
	 * target to pull from the deck when multiple printings are present.
	 * Closing without a choice auto-selects the first match.
	 */
	private void showPrimingVersionSelectDialog(List<CardData> matches, CardData primingCard, int slotIdx) {
		JDialog dlg = new JDialog(frame,
				"Choose version: " + primingCard.primingTarget() + " (" + matches.size() + " found)", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		// Tracks the player's selection; default to first match so closing = auto-pick
		CardData[] selection = {matches.get(0)};

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 12));

		for (CardData candidate : matches) {
			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 2));
			lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() != null) showZoomAt(candidate.imageUrl());
					lbl.setBorder(createCardGlowBorder(Color.YELLOW));
				}
				@Override public void mouseExited(MouseEvent e) {
					hideZoom();
					lbl.setBorder(selection[0].equals(candidate)
							? createCardGlowBorder(Color.YELLOW)
							: BorderFactory.createLineBorder(Color.GRAY, 1));
				}
				@Override public void mousePressed(MouseEvent e) {
					selection[0] = candidate;
					dlg.dispose();
				}
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(candidate.imageUrl());
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(candidate.name(), SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			wrapper.add(lbl, BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		JLabel hint = new JLabel("Click a card to select it", SwingConstants.CENTER);
		hint.setFont(FontLoader.loadPixelNESFont(9));

		dlg.getContentPane().setLayout(new BorderLayout(0, 6));
		dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
		dlg.getContentPane().add(hint, BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true); // blocks until a card is clicked (dlg.dispose())

		// Execution resumes here after dialog closes
		gameState.removeFromP1MainDeck(selection[0]);
		shuffleP1MainDeck();
		applyPrimedCard(selection[0], primingCard, slotIdx);
		refreshP1HandLabel();
		refreshP1BreakLabel();
	}

	private JPanel buildBackupZonePanel(JLabel[] labelStorage) {
		JPanel slotsPanel = new JPanel(new GridLayout(1, 5, 2, 0));
		slotsPanel.setOpaque(false);
		for (int i = 0; i < 5; i++) {
			JLabel slot = new JLabel();
			slot.setFont(FontLoader.loadPixelNESFont(11));
			slot.setBorder(BorderFactory.createEmptyBorder());
			slot.setOpaque(false);
			slot.setPreferredSize(new Dimension(CARD_H, CARD_H));
			slot.setMinimumSize(new Dimension(CARD_H, CARD_H));
			if (labelStorage != null) labelStorage[i] = slot;
			slotsPanel.add(slot);
		}
		return slotsPanel;
	}

	private JPanel buildDamageZonePanel(String playerLabel, JComboBox<String> colorBox) {
		boolean isP1 = "P1".equals(playerLabel);

		// Inner panel: 7 mini-card slots stacked vertically.
		// For P1: shows card thumbnails and handles EX burst overlay.
		// For P2: shows plain letters (D-A-M-A-G-E-P2), same as before.
		JPanel slotsPanel;

		if (isP1) {
			slotsPanel = new JPanel(new GridLayout(7, 1, 2, 2)) {
				@Override public void setBackground(Color c) { /* paintComponent owns background */ }
				@Override protected void paintComponent(Graphics g) {
					g.setColor(Color.DARK_GRAY);
					g.fillRect(0, 0, getWidth(), getHeight());
				}
			};
			slotsPanel.setOpaque(true);

			String[] slotLetters = { "D", "A", "M", "A", "G", "E", "P1" };
			for (int i = 0; i < 7; i++) {
				final String letter = slotLetters[i];
				JPanel slot = new JPanel() {
					@Override public void setBackground(Color c) { /* paintComponent owns background */ }
					@Override protected void paintComponent(Graphics g) {
						Image img = (Image) getClientProperty("cardImg");
						Graphics2D g2 = (Graphics2D) g.create();
						g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
						g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
						g2.setColor(img != null ? Color.RED : Color.DARK_GRAY);
						g2.fillRect(0, 0, getWidth(), getHeight());
						if (img != null) {
							int iw = img.getWidth(null), ih = img.getHeight(null);
							if (iw > 0 && ih > 0) {
								int cardAreaW = getWidth() / 2;
								double scale = Math.min((double) cardAreaW / iw, (double) getHeight() / ih);
								int dw = (int)(iw * scale), dh = (int)(ih * scale);
								int dy = (getHeight() - dh) / 2;
								g2.drawImage(img, 0, dy, dw, dy + dh, 0, 0, iw, ih, null);
							}
						}
						g2.setFont(FontLoader.loadPixelNESFont(14));
						g2.setColor(Color.WHITE);
						FontMetrics fm = g2.getFontMetrics();
						int tx = (getWidth() - fm.stringWidth(letter)) / 2;
						int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
						g2.drawString(letter, tx, ty);
						if (getClientProperty("isExBurst") == Boolean.TRUE) {
							g2.setFont(FontLoader.loadPixelNESFont(9));
							FontMetrics exFm = g2.getFontMetrics();
							int exW = exFm.stringWidth("EX");
							int exX = getWidth() - exW - 3;
							int exY = exFm.getAscent() + 2;
							g2.setColor(Color.BLACK);
							g2.drawString("EX", exX + 1, exY + 1);
							g2.setColor(Color.YELLOW);
							g2.drawString("EX", exX, exY);
						}
						g2.dispose();
					}
				};
				slot.setOpaque(true);
				slot.setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80), 1));
				slotsPanel.add(slot);
				p1DamageSlots[i] = slot;
			}

			slotsPanel.addMouseListener(new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					if (javax.swing.SwingUtilities.isRightMouseButton(e)) {
						JPopupMenu menu = new JPopupMenu();
						boolean ex = slotsPanel.getClientProperty("exBurst") == Boolean.TRUE;
						if (ex) {
							JMenuItem clearEx = new JMenuItem("Dismiss EX");
							clearEx.addActionListener(ae -> {
								slotsPanel.putClientProperty("exBurst", Boolean.FALSE);
								for (JPanel s : p1DamageSlots) { if (s != null) s.repaint(); }
								slotsPanel.repaint();
							});
							menu.add(clearEx);
						}
						if (menu.getComponentCount() > 0) menu.show(slotsPanel, e.getX(), e.getY());
					} else {
						if (!gameState.getP1DamageZone().isEmpty()) showDamageZoneDialog();
					}
				}
			});

			p1DamageSlotPanel = slotsPanel;

		} else {
			// P2: mirrored damage slots — card on right, letter centred, EX in upper-left
			String[] letters = { "D", "A", "M", "A", "G", "E", playerLabel };
			slotsPanel = new JPanel(new GridLayout(7, 1, 2, 2)) {
				@Override public void setBackground(Color c) { /* paintComponent owns background */ }
				@Override protected void paintComponent(Graphics g) {
					g.setColor(Color.DARK_GRAY);
					g.fillRect(0, 0, getWidth(), getHeight());
				}
			};
			slotsPanel.setOpaque(true);
			for (int i = 0; i < letters.length; i++) {
				final String letter = letters[i];
				JPanel slot = new JPanel() {
					@Override public void setBackground(Color c) { /* paintComponent owns background */ }
					@Override protected void paintComponent(Graphics g) {
						Image img = (Image) getClientProperty("cardImg");
						Graphics2D g2 = (Graphics2D) g.create();
						g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
						g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
						g2.setColor(img != null ? Color.RED : Color.DARK_GRAY);
						g2.fillRect(0, 0, getWidth(), getHeight());
						if (img != null) {
							int iw = img.getWidth(null), ih = img.getHeight(null);
							if (iw > 0 && ih > 0) {
								int cardAreaW = getWidth() / 2;
								double scale = Math.min((double) cardAreaW / iw, (double) getHeight() / ih);
								int dw = (int)(iw * scale), dh = (int)(ih * scale);
								int dy = (getHeight() - dh) / 2;
								int dx = getWidth() - dw;
								g2.drawImage(img, dx, dy, dx + dw, dy + dh, 0, 0, iw, ih, null);
							}
						}
						g2.setFont(FontLoader.loadPixelNESFont(14));
						g2.setColor(Color.WHITE);
						FontMetrics fm = g2.getFontMetrics();
						int tx = (getWidth() - fm.stringWidth(letter)) / 2;
						int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
						g2.drawString(letter, tx, ty);
						if (getClientProperty("isExBurst") == Boolean.TRUE) {
							g2.setFont(FontLoader.loadPixelNESFont(9));
							FontMetrics exFm = g2.getFontMetrics();
							int exY = exFm.getAscent() + 2;
							g2.setColor(Color.BLACK);
							g2.drawString("EX", 4, exY + 1);
							g2.setColor(Color.YELLOW);
							g2.drawString("EX", 3, exY);
						}
						g2.dispose();
					}
				};
				slot.setOpaque(true);
				slot.setBorder(BorderFactory.createLineBorder(new Color(80, 80, 80), 1));
				slotsPanel.add(slot);
				p2DamageSlots[i] = slot;
			}
			slotsPanel.addMouseListener(new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					if (!gameState.getP2DamageZone().isEmpty()) showP2DamageZoneDialog();
				}
			});
		}

		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setPreferredSize(new Dimension(CARD_W, CARD_H * 2));
		panel.add(slotsPanel, BorderLayout.CENTER);
		panel.add(colorBox,   BorderLayout.SOUTH);
		return panel;
	}

	private Image loadCardbackImage() {
		String customPath = AppSettings.getCustomCardbackPath();
		if (!customPath.isEmpty()) {
			File f = new File(customPath);
			if (f.exists()) {
				try {
					BufferedImage img = ImageIO.read(f);
					if (img != null) return img;
					System.err.println("Failed to decode custom cardback (unsupported format?): " + customPath);
				} catch (IOException e) {
					System.err.println("Error loading custom cardback: " + customPath + " — " + e.getMessage());
				}
			} else {
				System.err.println("Custom cardback file not found: " + customPath);
			}
		}
		return new ImageIcon(getClass().getResource("/resources/cardback/default.jpg")).getImage();
	}

	private LookAtDeckDialogs lookDialogs() {
		if (lookDialogsInstance == null)
			lookDialogsInstance = new LookAtDeckDialogs(frame, gameState,
				new LookAtDeckDialogs.Callbacks(
					this::logEntry,
					this::showZoomAt, this::hideZoom,
					this::refreshP1DeckLabel, this::refreshP2DeckLabel,
					this::refreshP1HandLabel, this::refreshP2HandCountLabel,
					this::refreshP1BreakLabel, this::refreshP2BreakLabel,
					this::loadCardbackImage));
		return lookDialogsInstance;
	}

	private ImageIcon scaledCardbackWithCount(Dimension size, int count) {
		Image base = loadCardbackImage();
		BufferedImage buf = new BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB);
		Graphics2D g = buf.createGraphics();
		g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
		g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
		g.drawImage(base, 0, 0, size.width, size.height, null);
		String text = String.valueOf(count);
		g.setFont(FontLoader.loadPixelNESFont(12));
		int textW = g.getFontMetrics().stringWidth(text);
		int textH = g.getFontMetrics().getAscent();
		int x = size.width - textW - 4;
		int y = textH + 4;
		g.setColor(Color.BLACK);
		g.drawString(text, x + 1, y + 1);
		g.setColor(Color.WHITE);
		g.drawString(text, x, y);
		g.dispose();
		return new ImageIcon(buf);
	}

	private JComboBox<String> buildColorDropdown() {
		String[] items = new String[ElementColor.values().length + 1];
		items[0] = "Default";
		for (int i = 0; i < ElementColor.values().length; i++)
			items[i + 1] = ElementColor.values()[i].name().charAt(0)
					+ ElementColor.values()[i].name().substring(1).toLowerCase();
		JComboBox<String> box = new JComboBox<>(items);
		box.setFocusable(false);
		return box;
	}

	private void applyElementColor(String selection, JPanel... panels) {
		Color bg = "Default".equals(selection)
				? UIManager.getColor("Panel.background")
				: ElementColor.fromName(selection).color;
		for (JPanel panel : panels) {
			setPanelBackground(panel, bg);
			panel.repaint();
		}
	}

	private void setPanelBackground(JPanel panel, Color color) {
		panel.setBackground(color);
		for (Component c : panel.getComponents()) {
			if (c instanceof JPanel jPanel) {
				setPanelBackground(jPanel, color);
			}
		}
	}

	/** Reads current crystal counts from game state and repaints both badges. */
	private void refreshCrystalDisplays() {
		if (p1CrystalDisplay != null) p1CrystalDisplay.setCount(gameState.getP1Crystals());
		if (p2CrystalDisplay != null) p2CrystalDisplay.setCount(gameState.getP2Crystals());
	}

	// -------------------------------------------------------------------------
	// P2 rendering helpers
	// -------------------------------------------------------------------------

	private boolean p2HasAvailableBackupSlot() {
		for (int i = 0; i < p2BackupCards.length; i++) {
			if (p2BackupCards[i] == null) return true;
		}
		return false;
	}

	private void placeP2CardInForwardZone(CardData card) {
		if (p2ForwardPanel == null) return;
		int idx = p2ForwardLabels.size();

		JLabel lbl = new JLabel("", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
		lbl.setOpaque(false);
		lbl.setFont(FontLoader.loadPixelNESFont(11));
		lbl.setBorder(BorderFactory.createEmptyBorder());
		lbl.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				if (lbl.getIcon() != null && SwingUtilities.isRightMouseButton(e))
					showP2ForwardContextMenu(idx, lbl, e);
			}
			@Override public void mouseEntered(MouseEvent e) {
				if (lbl.getIcon() == null) return;
				CardData top = p2ForwardPrimedTop.get(idx);
				showZoomAt(top != null ? top.imageUrl() : p2ForwardUrls.get(idx));
			}
			@Override public void mouseExited(MouseEvent e) { hideZoom(); }
		});

		p2ForwardUrls.add(card.imageUrl());
		p2ForwardCards.add(card);
		p2ForwardStates.add(CardState.ACTIVE);
		p2ForwardPlayedOnTurn.add(gameState.getTurnNumber());
		p2ForwardDamage.add(0);
		p2ForwardPowerBoost.add(0);
		p2ForwardPowerReduction.add(0);
		p2ForwardTempTraits.add(java.util.EnumSet.noneOf(CardData.Trait.class));
		p2ForwardRemovedTraits.add(java.util.EnumSet.noneOf(CardData.Trait.class));
		p2ForwardTempJobs.add(null);
		p2ForwardPrimedTop.add(null);
		p2ForwardFrozen.add(false);
		p2ForwardLabels.add(lbl);

		p2ForwardPanel.add(lbl);
		p2ForwardPanel.revalidate();
		p2ForwardPanel.repaint();

		refreshP2ForwardSlot(idx);
		if (!card.fieldPowerGrants().isEmpty()) refreshFieldGrantDependents(false);
		if (!card.fieldCostReductions().isEmpty()) refreshHandPopupIfVisible();
		triggerAutoAbilitiesForEntersField(card, false);
	}

	private void placeP2CardInFirstBackupSlot(CardData card) {
		for (int i = 0; i < p2BackupCards.length; i++) {
			if (p2BackupCards[i] != null) continue;
			p2BackupUrls[i]   = card.imageUrl();
			p2BackupCards[i]  = card;
			p2BackupStates[i] = CardState.DULL;
			refreshP2BackupSlot(i);
			triggerAutoAbilitiesForEntersField(card, false);
			return;
		}
	}

	private void refreshP2BackupSlot(int idx) {
		String url    = p2BackupUrls[idx];
		JLabel slot   = p2BackupLabels[idx];
		CardState state = p2BackupStates[idx];
		if (slot == null) return;
		if (url == null) { slot.setIcon(null); slot.setText(null); return; }
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				return new ImageIcon(CardAnimation.renderBackupCard(CardAnimation.toARGB(raw, CARD_W, CARD_H), state, false, false, p2BackupFrozen[idx]));
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null && p2BackupUrls[idx] != null) { slot.setIcon(icon); slot.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	private void refreshP2ForwardSlot(int idx) {
		CardData topCard = p2ForwardPrimedTop.get(idx);
		String url      = topCard != null ? topCard.imageUrl() : p2ForwardUrls.get(idx);
		CardState state = p2ForwardStates.get(idx);
		JLabel slot     = p2ForwardLabels.get(idx);
		if (url == null) return;
		int damage    = p2ForwardDamage.get(idx);
		int power     = effectiveP2ForwardPower(idx);
		int basePower = (topCard != null ? topCard : p2ForwardCards.get(idx)).power();
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.renderBackupCard(CardAnimation.toARGB(raw, CARD_W, CARD_H), state, false, false, p2ForwardFrozen.get(idx));
				if (damage > 0) {
					CardAnimation.renderDamageOverlay(canvas, damage);
				}
				if (power > basePower) {
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(80, 220, 80), state);
				} else if (power < basePower) {
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(230, 200, 60), state);
				}
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null) { slot.setIcon(icon); slot.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	private void refreshAllP2ForwardSlots() {
		for (int i = 0; i < p2ForwardLabels.size(); i++) refreshP2ForwardSlot(i);
	}

	// -------------------------------------------------------------------------
	// Computer player (P2 AI)
	// -------------------------------------------------------------------------

	private class ComputerPlayer {
		private static final int PAUSE_MS = 500;

		/** Schedules {@code r} to run after {@link #PAUSE_MS} ms on the EDT. */
		private void step(Runnable r) {
			javax.swing.Timer t = new javax.swing.Timer(PAUSE_MS, e -> {
				if (!gameState.isP1GameOver()) r.run();
			});
			t.setRepeats(false);
			t.start();
		}

		/** Entry point: called when P2's ACTIVE phase begins. */
		void runTurn() {
			step(this::doActivePhase);
		}

		// ── Active Phase ─────────────────────────────────────────────────────

		private void doActivePhase() {
			p2ReceivedDamageThisTurn = false;
			int activated = 0, thawed = 0;

			// Pass 1: activate DULL/BRAVE_ATTACKED cards; frozen cards are skipped
			for (int i = 0; i < p2BackupStates.length; i++) {
				if (p2BackupCards[i] == null) continue;
				if (p2BackupStates[i] == CardState.DULL && !p2BackupFrozen[i]) {
					p2BackupStates[i] = CardState.ACTIVE;  refreshP2BackupSlot(i); activated++;
				}
			}
			for (int i = 0; i < p2ForwardStates.size(); i++) {
				p2ForwardDamage.set(i, 0);
				CardState fs = p2ForwardStates.get(i);
				if ((fs == CardState.DULL || fs == CardState.BRAVE_ATTACKED) && !p2ForwardFrozen.get(i)) {
					p2ForwardStates.set(i, CardState.ACTIVE); animateActivateP2Forward(i); activated++;
				} else {
					refreshP2ForwardSlot(i);
				}
			}
			for (int i = 0; i < p2MonsterStates.size(); i++) {
				CardState ms = p2MonsterStates.get(i);
				if ((ms == CardState.DULL || ms == CardState.BRAVE_ATTACKED) && !p2MonsterFrozen.get(i)) {
					p2MonsterStates.set(i, CardState.ACTIVE); refreshP2MonsterSlot(i); activated++;
				} else {
					refreshP2MonsterSlot(i);
				}
			}

			// Pass 2: remove freeze — card state is unchanged, only the frozen flag is cleared
			for (int i = 0; i < p2BackupStates.length; i++) {
				if (p2BackupCards[i] == null) continue;
				if (p2BackupFrozen[i]) { p2BackupFrozen[i] = false; refreshP2BackupSlot(i); thawed++; }
			}
			for (int i = 0; i < p2ForwardStates.size(); i++) {
				if (p2ForwardFrozen.get(i)) { p2ForwardFrozen.set(i, false); refreshP2ForwardSlot(i); thawed++; }
			}
			for (int i = 0; i < p2MonsterStates.size(); i++) {
				if (p2MonsterFrozen.get(i)) { p2MonsterFrozen.set(i, false); refreshP2MonsterSlot(i); thawed++; }
			}
			StringBuilder msg = new StringBuilder("Turn " + gameState.getTurnNumber() + " — P2 Active Phase");
			if (activated > 0) msg.append(" (").append(activated).append(" activated");
			if (thawed > 0)    msg.append(activated > 0 ? ", " : " (").append(thawed).append(" thawed");
			if (activated > 0 || thawed > 0) msg.append(")");
			logEntry(msg.toString());

			gameState.advancePhase(); // ACTIVE → DRAW
			refreshPhaseTracker();
			step(this::doDrawPhase);
		}

		// ── Draw Phase ───────────────────────────────────────────────────────

		private void doDrawPhase() {
			int drawCount = gameState.getTurnNumber() == 1 ? 1 : 2;
			List<CardData> drawn = gameState.drawP2ToHand(drawCount);
			animateCardDraw(false, drawn.size());
			refreshP2DeckLabel();
			refreshP2HandCountLabel();
			if (drawn.size() < drawCount) {
				triggerGameOver("P2 milled out — You Win!");
				return;
			}
			logEntry("[P2] Draw Phase — Drew " + drawn.size() + " card(s) (hand: " + gameState.getP2Hand().size() + ")");
			gameState.advancePhase(); // DRAW → MAIN_1
			refreshPhaseTracker();
			logEntry("[P2] Main Phase 1");
			step(() -> doMainPhase(() -> {
				gameState.advancePhase(); // MAIN_1 → ATTACK
				refreshPhaseTracker();
				boolean canAttack = false;
				for (int i = 0; i < p2ForwardStates.size(); i++) {
					if (p2ForwardCanAttack(i)) { canAttack = true; break; }
				}
				if (!canAttack) {
					for (int i = 0; i < p2MonsterStates.size(); i++) {
						if (p2MonsterCanAttackAsForward(i)) { canAttack = true; break; }
					}
				}
				if (!canAttack) {
					logEntry("[P2] Attack Phase — No attackers, skipping");
					gameState.advancePhase(); // ATTACK → MAIN_2
					refreshPhaseTracker();
					logEntry("[P2] Main Phase 2");
					step(() -> doMainPhase(this::doEndPhase));
				} else {
					logEntry("[P2] Attack Phase");
					refreshAllP2ForwardSlots();
					step(() -> doAttackPhase(() -> {
						gameState.advancePhase(); // ATTACK → MAIN_2
						refreshPhaseTracker();
						logEntry("[P2] Main Phase 2");
						step(() -> doMainPhase(this::doEndPhase));
					}));
				}
			}));
		}

		// ── Main Phase (shared for Main 1 and Main 2) ────────────────────────

		private void doMainPhase(Runnable onDone) {
			if (gameState.isP1GameOver()) return;

			// Try LB plays first
			int[] lbPlan = findLbPlayPlan();
			if (lbPlan != null) {
				int castIdx = lbPlan[0];
				CardData card = gameState.getP2LbDeck().get(castIdx);
				p2SpentLbIndices.add(castIdx);
				for (int i = 1; i < lbPlan.length; i++) p2SpentLbIndices.add(lbPlan[i]);
				String element = card.elements()[0];
				gameState.spendP2Cp(element, Math.min(card.cost(), gameState.getP2CpForElement(element)));
				refreshP2LimitButton();
				logEntry("[P2] Plays LB \"" + card.name() + "\"");
				lastCardWasCast = true;
				if (card.isForward())      placeP2CardInForwardZone(card);
				else if (card.isBackup())  placeP2CardInFirstBackupSlot(card);
				else if (card.isMonster()) placeP2CardInMonsterZone(card);
				lastCardWasCast = false;
				step(() -> doMainPhase(onDone));
				return;
			}

			int[] plan = findPlayPlan();
			if (plan == null) { onDone.run(); return; }

			int cardIdx = plan[0];
			List<Integer> discards = new ArrayList<>();
			for (int i = 1; i < plan.length; i++) discards.add(plan[i]);
			discards.sort(Collections.reverseOrder());

			// Peek at the card being played (pre-discard index is still valid here)
			String[] playElems = gameState.getP2Hand().get(cardIdx).elements();
			int[] accCp = new int[playElems.length];
			for (int ei = 0; ei < playElems.length; ei++)
				accCp[ei] = gameState.getP2CpForElement(playElems[ei]);

			// Discard for CP, attributing each card's CP to the most-needed element
			for (int di : discards) {
				CardData d = gameState.breakP2FromHand(di);
				if (d != null) {
					int assignEi = p2BestDiscardElement(d, playElems, accCp);
					accCp[assignEi] += 2;
					gameState.addP2Cp(playElems[assignEi], 2);
					logEntry("[P2] Discards " + d.name() + " for CP");
				}
			}
			refreshP2BreakLabel();

			// Adjust card index for removed cards
			int adjustedIdx = cardIdx;
			for (int di : discards) {
				if (di < cardIdx) adjustedIdx--;
			}

			CardData toPlay = gameState.removeP2FromHand(adjustedIdx);
			refreshP2HandCountLabel();
			if (toPlay != null) {
				// Spend 1 CP from each required element, then cover remainder
				String[] elems = toPlay.elements();
				int remaining = toPlay.cost();
				if (elems.length > 1) {
					for (String e : elems) { gameState.spendP2Cp(e, 1); remaining--; }
				}
				for (String e : elems) {
					if (remaining <= 0) break;
					int avail = gameState.getP2CpForElement(e);
					int toSpend = Math.min(remaining, avail);
					if (toSpend > 0) { gameState.spendP2Cp(e, toSpend); remaining -= toSpend; }
				}
				for (String e : elems) { gameState.clearP2Cp(e); }
				logEntry("[P2] Plays " + toPlay.name());
				lastCardWasCast = true;
				if (toPlay.isForward())      placeP2CardInForwardZone(toPlay);
				else if (toPlay.isBackup())  placeP2CardInFirstBackupSlot(toPlay);
				else if (toPlay.isMonster()) placeP2CardInMonsterZone(toPlay);
				lastCardWasCast = false;
			}
			step(() -> doMainPhase(onDone));
		}

		// ── Attack Phase ─────────────────────────────────────────────────────

		/**
		 * Returns a list of P2 forward indices to party-attack with, or null if a party
		 * attack offers no advantage. A party attack is chosen when the combined power of
		 * 2-3 forwards can break a P1 forward that no single P2 forward could kill alone.
		 */
		private List<Integer> p2ChoosePartyAttack() {
			List<Integer> attackable = new ArrayList<>();
			for (int i = 0; i < p2ForwardStates.size(); i++)
				if (p2ForwardCanAttack(i)) attackable.add(i);
			if (attackable.size() < 2) return null;

			for (int p1 = 0; p1 < p1ForwardStates.size(); p1++) {
				CardState s = p1ForwardStates.get(p1);
				if (s != CardState.ACTIVE && s != CardState.BRAVE_ATTACKED) continue;
				int p1Hp = effectiveP1ForwardPower(p1) - p1ForwardDamage.get(p1);

				boolean canKillAlone = false;
				for (int i : attackable)
					if (effectiveP2ForwardPower(i) >= p1Hp) { canKillAlone = true; break; }
				if (canKillAlone) continue;

				// Try pairs
				for (int a = 0; a < attackable.size(); a++) {
					for (int b = a + 1; b < attackable.size(); b++) {
						if (effectiveP2ForwardPower(attackable.get(a))
								+ effectiveP2ForwardPower(attackable.get(b)) >= p1Hp)
							return List.of(attackable.get(a), attackable.get(b));
					}
				}
				// Try triples
				for (int a = 0; a < attackable.size(); a++) {
					for (int b = a + 1; b < attackable.size(); b++) {
						for (int c = b + 1; c < attackable.size(); c++) {
							if (effectiveP2ForwardPower(attackable.get(a))
									+ effectiveP2ForwardPower(attackable.get(b))
									+ effectiveP2ForwardPower(attackable.get(c)) >= p1Hp)
								return List.of(attackable.get(a), attackable.get(b), attackable.get(c));
						}
					}
				}
			}
			return null;
		}

		private void executeP2PartyAttack(List<Integer> partyIndices, Runnable onDone) {
			int combinedPower = 0;
			StringBuilder names = new StringBuilder();
			for (int idx : partyIndices) {
				if (effectiveP2HasTrait(idx, CardData.Trait.BRAVE)) {
					p2ForwardStates.set(idx, CardState.BRAVE_ATTACKED);
					refreshP2ForwardSlot(idx);
				} else {
					p2ForwardStates.set(idx, CardState.DULL);
					animateDullP2Forward(idx, null);
				}
				combinedPower += effectiveP2ForwardPower(idx);
				if (names.length() > 0) names.append(", ");
				names.append(p2ForwardCards.get(idx).name());
			}
			logEntry("[P2] Party Attack! " + names + " (" + combinedPower + " combined)");
			for (int idx : partyIndices)
				triggerAutoAbilitiesForAttack(p2ForwardCards.get(idx), false);
			triggerAutoAbilitiesForPartyAttack(false);
			final int fCombined = combinedPower;
			initP1BlockDeclarationVsParty(partyIndices, fCombined, onDone);
		}


		private void doAttackPhase(Runnable onDone) {
			if (gameState.isP1GameOver()) return;
			pendingP2AttackerIsMonster = false;
			pendingP2AttackerPower     = 0;

			List<Integer> party = p2ChoosePartyAttack();
			if (party != null) {
				executeP2PartyAttack(party, () -> {
					if (!gameState.isP1GameOver()) step(() -> doAttackPhase(onDone));
				});
				return;
			}

			for (int i = 0; i < p2ForwardStates.size(); i++) {
				if (!p2ForwardCanAttack(i)) continue;
				CardData attacker = p2ForwardCards.get(i);
				logEntry("[P2] " + attacker.name() + " attacks!");
				if (effectiveP2HasTrait(i, CardData.Trait.BRAVE)) {
					p2ForwardStates.set(i, CardState.BRAVE_ATTACKED);
					refreshP2ForwardSlot(i);
				} else {
					p2ForwardStates.set(i, CardState.DULL);
					animateDullP2Forward(i, null);
				}
				triggerAutoAbilitiesForAttack(attacker, false);
				final int fi = i;
				initP1BlockDeclaration(attacker, fi, () -> {
					if (!gameState.isP1GameOver()) step(() -> doAttackPhase(onDone));
				});
				return;
			}
			for (int i = 0; i < p2MonsterStates.size(); i++) {
				if (!p2MonsterCanAttackAsForward(i)) continue;
				CardData attacker = p2MonsterCards.get(i);
				int power = p2MonsterForwardPower(i);
				p2MonsterStates.set(i, CardState.DULL);
				refreshP2MonsterSlot(i);
				triggerAutoAbilitiesForAttack(attacker, false);
				logEntry("[P2] " + attacker.name() + " attacks! (Forward — " + power + ")");
				pendingP2AttackerIsMonster = true;
				pendingP2AttackerPower     = power;
				final int mi = i;
				initP1BlockDeclaration(attacker, mi, () -> {
					if (!gameState.isP1GameOver()) step(() -> doAttackPhase(onDone));
				});
				return;
			}
			onDone.run();
		}

		// ── End Phase ────────────────────────────────────────────────────────

		private void doEndPhase() {
			List<CardData> hand = gameState.getP2Hand();
			while (hand.size() > 5) {
				int idx = pickWorstHandCard(hand);
				CardData d = gameState.discardP2FromHand(idx);
				if (d != null) logEntry("[P2] End Phase — discards " + d.name());
			}
			refreshP2BreakLabel();
			refreshP2HandCountLabel();
			fireEndOfTurnEffects(false);
			for (int i = 0; i < p2ForwardDamage.size(); i++) p2ForwardDamage.set(i, 0);
			for (int i = 0; i < p2ForwardPowerBoost.size(); i++) p2ForwardPowerBoost.set(i, 0);
			for (int i = 0; i < p2ForwardPowerReduction.size(); i++) p2ForwardPowerReduction.set(i, 0);
			p2ForwardTempTraits.forEach(java.util.EnumSet::clear);
			p2ForwardRemovedTraits.forEach(java.util.EnumSet::clear);
			for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
			for (int i = 0; i < p1ForwardDamage.size(); i++) p1ForwardDamage.set(i, 0);
			for (int i = 0; i < p1ForwardPowerBoost.size(); i++) p1ForwardPowerBoost.set(i, 0);
			for (int i = 0; i < p1ForwardPowerReduction.size(); i++) p1ForwardPowerReduction.set(i, 0);
			p1ForwardTempTraits.forEach(java.util.EnumSet::clear);
			p1ForwardRemovedTraits.forEach(java.util.EnumSet::clear);
			for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
			p1ForwardCannotBeBlocked.clear();       p2ForwardCannotBeBlocked.clear();
			p1ForwardCannotBeBlockedByCost.clear(); p2ForwardCannotBeBlockedByCost.clear();
			p1ForwardCannotBlock.clear();           p2ForwardCannotBlock.clear();
			p1ForwardMustBlock.clear();             p2ForwardMustBlock.clear();
			p1ForwardCannotAttack.clear();          p2ForwardCannotAttack.clear();
			p1ForwardMustAttack.clear();            p2ForwardMustAttack.clear();
			p2ForwardCannotAttackPersistent.clear(); p2ForwardCannotBlockPersistent.clear();
			p1TempAttackTriggers.clear();           p2TempAttackTriggers.clear();
			p1TempBlockTriggers.clear();            p2TempBlockTriggers.clear();
			nextIncomingDmgZeroSet.clear();   nextIncomingDmgReduceMap.clear();   nextAbilityDmgReduceMap.clear();
			incomingDmgIncreaseMap.clear();   nullifyAbilityDmgSet.clear();
			nullifyAbilityOnlyDmgSet.clear(); perCardNonLethalDmgSet.clear();
			nextOutgoingDmgZeroSet.clear();
			p1NonLethalProtection = false;    p2NonLethalProtection = false;
			p1DmgReductionDisabled = false;   p2DmgReductionDisabled = false;
			p1GlobalDmgReduction  = 0;        p2GlobalDmgReduction  = 0;
			gameState.advancePhase(); // MAIN_2 → END
			refreshPhaseTracker();
			logEntry("[P2] End Phase");
			gameState.advancePhase(); // END → ACTIVE (switches to P1, increments turn)
			refreshPhaseTracker();
			step(this::startP1Turn);  // startP1Turn expects phase == ACTIVE
		}

		// ── P1 turn start (Active + Draw, then hand control back to player) ──

		private void startP1Turn() {
			p1ReceivedDamageThisTurn = false;
			p1CardsCastThisTurn = 0;
			for (int i = 0; i < p1MonsterCards.size(); i++) refreshP1MonsterSlot(i);
			for (int i = 0; i < p2MonsterCards.size(); i++) refreshP2MonsterSlot(i);
			int activated = 0, thawed = 0;

			// Pass 1: activate DULL/BRAVE_ATTACKED cards; frozen cards are skipped
			for (int i = 0; i < p1BackupStates.length; i++) {
				if (p1BackupStates[i] == CardState.DULL && !p1BackupFrozen[i]) {
					p1BackupStates[i] = CardState.ACTIVE; refreshP1BackupSlot(i); activated++;
				}
			}
			for (int i = 0; i < p1ForwardStates.size(); i++) {
				CardState fs = p1ForwardStates.get(i);
				if ((fs == CardState.DULL || fs == CardState.BRAVE_ATTACKED) && !p1ForwardFrozen.get(i)) {
					p1ForwardStates.set(i, CardState.ACTIVE); animateActivateForward(i); activated++;
				}
			}

			// Pass 2: remove freeze — card state is unchanged, only the frozen flag is cleared
			for (int i = 0; i < p1BackupStates.length; i++) {
				if (p1BackupFrozen[i]) { p1BackupFrozen[i] = false; refreshP1BackupSlot(i); thawed++; }
			}
			for (int i = 0; i < p1ForwardStates.size(); i++) {
				if (p1ForwardFrozen.get(i)) { p1ForwardFrozen.set(i, false); refreshP1ForwardSlot(i); thawed++; }
			}
			StringBuilder msg = new StringBuilder("Turn " + gameState.getTurnNumber() + " — Active Phase");
			if (activated > 0) msg.append(" (").append(activated).append(" activated");
			if (thawed > 0)    msg.append(activated > 0 ? ", " : " (").append(thawed).append(" thawed");
			if (activated > 0 || thawed > 0) msg.append(")");
			logEntry(msg.toString());

			gameState.advancePhase(); // ACTIVE → DRAW
			refreshPhaseTracker();

			List<CardData> drawn = gameState.drawToHand(2);
			animateCardDraw(true, drawn.size());
			refreshP1HandLabel();
			refreshP1DeckLabel();
			if (drawn.size() < 2) {
				triggerGameOver("Milled Out - You Lose!");
				return;
			}
			logEntry("Draw Phase — Drew " + drawn.size() + " card(s)");
			gameState.advancePhase(); // DRAW → MAIN_1
			refreshPhaseTracker();
			logEntry("Main Phase 1");
			processWarpCounters();
			nextPhaseButton.setEnabled(true);
		}

		// ── Helpers ──────────────────────────────────────────────────────────

		private boolean p2ForwardCanAttack(int idx) {
			if (p2ForwardCannotAttack.contains(idx)) return false;
			if (p2ForwardCannotAttackPersistent.contains(idx)) return false;
			return p2ForwardStates.get(idx) == CardState.ACTIVE
				&& (effectiveP2HasTrait(idx, CardData.Trait.HASTE)
					|| p2ForwardPlayedOnTurn.get(idx) != gameState.getTurnNumber());
		}

		private int pickWorstHandCard(List<CardData> hand) { return MainWindow.pickWorstHandCard0(hand); }

		/**
		 * Finds the best card P2 can play from hand, along with the minimum
		 * discards needed to afford it.
		 *
		 * @return {@code int[]} where {@code [0]} is the hand index of the card to
		 *         play and {@code [1..n]} are hand indices to discard first (sorted
		 *         ascending), or {@code null} if nothing is playable.
		 */
		/** Returns [castIdx, paymentIdxâ€¦] if any unspent LB card is affordable, else null. */
		private int[] findLbPlayPlan() {
			List<CardData> lbDeck = gameState.getP2LbDeck();
			boolean p2HasLD = hasLightOrDarkOnField(false);
			for (int i = 0; i < lbDeck.size(); i++) {
				if (p2SpentLbIndices.contains(i)) continue;
				CardData card = lbDeck.get(i);
				if (card.isSummon()) continue; // skip summons — no simple board placement
				if (!card.multicard() && p2HasCharacterNameOnField(card.name())) continue;
				if (card.isLightOrDark() && p2HasLD) continue;
				if (card.isBackup() && !p2HasAvailableBackupSlot()) continue;
				// Count unspent LB cards available as payment (excluding this card)
				List<Integer> available = new ArrayList<>();
				for (int j = 0; j < lbDeck.size(); j++) {
					if (j != i && !p2SpentLbIndices.contains(j)) available.add(j);
				}
				if (available.size() < card.lbCost()) continue;
				// Check CP
				String element = card.elements()[0];
				if (gameState.getP2CpForElement(element) < card.cost()) continue;
				// Build result: [castIdx, paymentâ€¦]
				int[] result = new int[1 + card.lbCost()];
				result[0] = i;
				for (int k = 0; k < card.lbCost(); k++) result[k + 1] = available.get(k);
				return result;
			}
			return null;
		}

		private int[] findPlayPlan() {
			List<CardData> hand = gameState.getP2Hand();
			if (hand.isEmpty()) return null;

			// Candidates: forwards (highest cost first), then backups (highest cost first)
			// Skip non-Multicard characters whose name is already on P2's field or backups.
			List<Integer> candidates = new ArrayList<>();
			boolean p2HasLD = hasLightOrDarkOnField(false);
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				if (!c.isForward()) continue;
				if (!c.multicard() && p2HasCharacterNameOnField(c.name())) continue;
				if (c.isLightOrDark() && p2HasLD) continue;
				candidates.add(i);
			}
			candidates.sort((a, b) -> hand.get(b).cost() - hand.get(a).cost());
			List<Integer> backupCands = new ArrayList<>();
			for (int i = 0; i < hand.size(); i++) {
				CardData c = hand.get(i);
				if (!c.isBackup() || !p2HasAvailableBackupSlot()) continue;
				if (!c.multicard() && p2HasCharacterNameOnField(c.name())) continue;
				if (c.isLightOrDark() && p2HasLD) continue;
				backupCands.add(i);
			}
			backupCands.sort((a, b) -> hand.get(b).cost() - hand.get(a).cost());
			candidates.addAll(backupCands);

			for (int cardIdx : candidates) {
				CardData card   = hand.get(cardIdx);
				String[] elems  = card.elements();

				// Current CP per element this card requires
				int[] simCp = new int[elems.length];
				for (int ei = 0; ei < elems.length; ei++)
					simCp[ei] = gameState.getP2CpForElement(elems[ei]);

				if (p2CanAfford(card.cost(), elems, simCp)) return new int[]{ cardIdx };

				// Cheapest non-Light/Dark cards that match at least one required element
				List<Integer> discardable = new ArrayList<>();
				for (int i = 0; i < hand.size(); i++) {
					if (i == cardIdx) continue;
					CardData c = hand.get(i);
					if (c.isLightOrDark()) continue;
					for (String e : elems) {
						if (c.containsElement(e)) { discardable.add(i); break; }
					}
				}
				discardable.sort((a, b) -> hand.get(a).cost() - hand.get(b).cost());

				// Greedily assign each discard to the most-needed element
				List<Integer> chosen = new ArrayList<>();
				for (int di : discardable) {
					int assignEi = p2BestDiscardElement(hand.get(di), elems, simCp);
					simCp[assignEi] += 2;
					chosen.add(di);
					if (p2CanAfford(card.cost(), elems, simCp)) {
						int[] result = new int[1 + chosen.size()];
						result[0] = cardIdx;
						for (int k = 0; k < chosen.size(); k++) result[k + 1] = chosen.get(k);
						return result;
					}
				}
			}
			return null;
		}

		/** Returns true when {@code cpByElemIdx} satisfies the cost and per-element minimums. */
		private static boolean p2CanAfford(int cost, String[] elems, int[] cpByElemIdx) {
			int total = 0;
			for (int ei = 0; ei < elems.length; ei++) {
				if (elems.length > 1 && cpByElemIdx[ei] < 1) return false;
				total += cpByElemIdx[ei];
			}
			return total >= cost;
		}

		/**
		 * Returns the index into {@code elems} that {@code dc} should contribute its CP to,
		 * preferring elements that still need their per-element minimum of 1 CP.
		 */
		private static int p2BestDiscardElement(CardData dc, String[] elems, int[] simCp) {
			int bestEi = -1;
			int maxPriority = Integer.MIN_VALUE;
			for (int ei = 0; ei < elems.length; ei++) {
				if (!dc.containsElement(elems[ei])) continue;
				// Deficit below minimum gets positive priority; surplus gets negative
				int priority = simCp[ei] < 1 ? (1 - simCp[ei]) : -simCp[ei];
				if (priority > maxPriority) { maxPriority = priority; bestEi = ei; }
			}
			return bestEi >= 0 ? bestEi : 0;
		}
	}

}
