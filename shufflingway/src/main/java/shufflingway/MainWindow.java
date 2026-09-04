package shufflingway;

import java.awt.AlphaComposite;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Container;
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
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import javax.imageio.ImageIO;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.ImageIcon;
import javax.swing.JButton;
import javax.swing.JComponent;
import javax.swing.JDialog;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JLayeredPane;
import javax.swing.JMenuBar;
import javax.swing.JMenuItem;
import javax.swing.JOptionPane;
import javax.swing.JPanel;
import javax.swing.JPopupMenu;
import javax.swing.JScrollBar;
import javax.swing.JScrollPane;
import javax.swing.JSeparator;
import javax.swing.JSpinner;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.JWindow;
import javax.swing.SpinnerNumberModel;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
import javax.swing.ToolTipManager;
import javax.swing.UIManager;
import javax.swing.WindowConstants;
import javax.swing.border.BevelBorder;
import javax.swing.border.SoftBevelBorder;
import javax.swing.event.PopupMenuEvent;
import javax.swing.event.PopupMenuListener;

import org.json.JSONArray;
import org.json.JSONObject;

import scraper.DeckDatabase;
import scraper.DeckDatabase.DeckCardDetail;
import static shufflingway.CardFilters.cardNamesOverlap;
import static shufflingway.CardFilters.matchesAltBzType;
import static shufflingway.CardFilters.matchesDiscardType;
import static shufflingway.CardFilters.meetsCardNameFilter;
import static shufflingway.CardFilters.meetsCategoryFilter;
import static shufflingway.CardFilters.meetsCostConstraint;
import static shufflingway.CardFilters.meetsElementFilter;
import static shufflingway.CardFilters.meetsJobFilter;
import static shufflingway.CpPaymentUtils.contributingElement;
import static shufflingway.CpPaymentUtils.matchesAnyElement;
import shufflingway.dialog.AltCostPaymentDialog;
import shufflingway.dialog.BreakZoneDialog;
import shufflingway.dialog.DivideIntoGroupsDialog;
import shufflingway.dialog.ExtraCostBzSelectDialog;
import shufflingway.dialog.HandPickDialog;
import shufflingway.dialog.LbDialog;
import shufflingway.dialog.LbPaymentDialog;
import shufflingway.dialog.RemovedFromPlayDialog;
import shufflingway.dialog.StandardPaymentDialog;
import shufflingway.dialog.WarpPaymentDialog;
import shufflingway.graphics.BoardEdgeFadePanel;
import shufflingway.graphics.CardAnimation;
import static shufflingway.graphics.CardAnimation.CARD_H;
import static shufflingway.graphics.CardAnimation.CARD_W;
import shufflingway.graphics.CardBreakAnimator;
import shufflingway.graphics.CardRfpAnimator;
import shufflingway.graphics.CardSlideAnimator;
import shufflingway.graphics.CrystalDisplay;
import shufflingway.graphics.GradientPanel;
import shufflingway.graphics.GrayscaleLabel;
import shufflingway.graphics.HandFanOverlapLayout;
import shufflingway.graphics.HandFanPanel;
import shufflingway.graphics.PlayerHandFanPanel;
import shufflingway.graphics.ShieldIcon;
import shufflingway.graphics.TraitTab;
import shufflingway.graphics.TriangleIcon;
import shufflingway.menu.DebugMenu;
import shufflingway.menu.FileMenu;
import shufflingway.menu.HelpMenu;
import shufflingway.menu.MultiplayerMenu;
import shufflingway.net.ActionType;
import shufflingway.net.ChoiceKind;
import shufflingway.net.GameAction;
import shufflingway.net.GameConnection;
import shufflingway.net.MatchSetup;

public class MainWindow {

	JFrame frame;
	shufflingway.dialog.CardPickerDialog cardPickerDialog;

	final AutoAbilityTriggers autoAbilityTriggers = new AutoAbilityTriggers(this);
	/** Cost and affordability rules; MainWindow keeps thin delegators to these. */
	final CostCalculator      costs               = new CostCalculator(this);
	/** Damage resolution rules; MainWindow keeps thin delegators to these. */
	final DamageResolver      damageResolver      = new DamageResolver(this);
	final Priming             priming             = new Priming(this);
	/** Sequences card arrivals on the field: animation, then the card, then its auto abilities. */
	final FieldEntryAnimator  fieldEntryAnimator  = new FieldEntryAnimator(this);

	// Side info panel dimensions.
	private static final int    SIDE_MARGIN    = 4;                   // px between card and panel edge
	private static final double PREVIEW_SCALE  = 0.8;
	private static final int    RESIZE_HANDLE_W = 5;                 // draggable sidebar divider width
	/**
	 * The pixel size every card image is stored at. Every one of the images cached in the card
	 * database is 429x600, so the panel's sizing does not have to wait to find out.
	 *
	 * <p>These are what make the resize limits identical before and after a game starts. They used
	 * to be guessed from {@code 3 * CARD_W}, which is a UI-scaled board measurement and has nothing
	 * to do with the stored image: at a 1.29 scale that guess allowed the panel out to 544px, where
	 * the real limit once a card had been previewed was 433. {@link #sizePreviewPanel} still
	 * measures the first image that actually loads, so a future change of image source corrects
	 * itself rather than being wrong for good.
	 */
	private static final int NATIVE_CARD_W = 429;
	private static final int NATIVE_CARD_H = 600;
	private int sidePanelW = defaultSidePanelW();
	private int previewH   = (int)((sidePanelW - SIDE_MARGIN) * (double) NATIVE_CARD_H / NATIVE_CARD_W);
	private boolean previewSized = false;
	private int nativeImgW   = 0;   // native card image dimensions (set on first hover)
	private int nativeImgH   = 0;
	/**
	 * Clamp bounds for dragging the divider. Seeded from the same {@code 3 * CARD_W} estimate of
	 * the native image width that {@link #sidePanelW} above uses, so the divider is draggable from
	 * the moment the window opens; {@link #sizePreviewPanel} replaces them with the real card's
	 * measurements once one has been previewed.
	 *
	 * <p>They used to start at 0 and be set only on that first hover, which meant the divider did
	 * nothing at all until a game was under way and a card had been hovered.
	 */
	private int minSidePanelW = (int)(NATIVE_CARD_W * 0.75) + SIDE_MARGIN;
	private int maxSidePanelW = NATIVE_CARD_W + SIDE_MARGIN;

	/**
	 * The resolution this session is running at, read once at construction.
	 *
	 * <p>Not re-read later: Preferences applies a resolution change on the next launch, so from the
	 * moment the player picks a new one {@code AppSettings.getResolution()} names a resolution this
	 * window is not showing. Saving the panel width under that on the way out would file this
	 * session's width against the wrong screen size.
	 */
	private final String sessionResolution = AppSettings.getResolution();

	/** The panel width to open at when this resolution has no saved preference. */
	private static int defaultSidePanelW() {
		return (int)(NATIVE_CARD_W * PREVIEW_SCALE) + SIDE_MARGIN;
	}

	// P1 zone labels that change during gameplay
	JLabel p1DeckLabel;
	JLabel p2DeckLabel;
	private CrystalDisplay p1CrystalDisplay;
	private CrystalDisplay p2CrystalDisplay;
	private JButton p1LimitButton;
	private JButton p2LimitButton;
	JLabel p1BreakLabel;
	JLabel p2BreakLabel;
	private HandFanPanel p2HandFan;
	/** P1's own hand, fanned face up along the bottom edge of the board. */
	PlayerHandFanPanel p1HandFan;
	/** "Cards you can play from outside your hand", parked at the left end of the fan strip. */
	private JButton playableCardsButton;
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
	// Non-null for the duration of a networked game; null for a game against the AI.
	private MatchSetup matchSetup;
	// Opening-deal digests, ours and the opponent's, compared once both exist.
	private String localDealChecksum;
	private String remoteDealChecksum;
	// Turn 1 waits for both players to settle an opening hand.
	private boolean localHandKept;
	private boolean remoteHandKept;
	// A desync cascades, so only the first one raises a dialog.
	private boolean desyncReported;
	// Side info panel (card preview + Next button + game log)
	private JPanel        sidePanel;
	private JPanel        sideWrapper;        // contains resizeHandle + sidePanel
	private JPanel        resizeHandle;       // draggable divider between board and sidebar
	private JPanel        cardPreviewPanel;   // custom-painted card preview
	private BufferedImage previewImage;       // current card to draw (null = empty)
	private float         previewAlpha  = 0f; // 0 = transparent, 1 = fully opaque
	private Timer fadeTimer;      // drives fade-in / fade-out animation
	CardSlideAnimator cardSlideAnimator;
	private CardBreakAnimator breakAnimator;
	CardRfpAnimator           rfpAnimator;
	/** Non-null when the next startBreakAnim call should slide to the break zone instead of slashing. */
	JLabel pendingCostBreakDestLabel;
	/** When true, the next startBreakAnim call is suppressed (e.g. RFP goes through breakP*Forward but needs no animation). */
	boolean suppressNextBreakAnim;
	// In-flight discard animations: each pending slide hides one top-of-break-zone card from the
	// break label until its slide lands. The counter is incremented when a discard animation
	// starts and decremented when the corresponding swing Timer fires. refreshP*BreakLabel skips
	// the trailing N cards while N > 0.
	private int p1BreakAnimHide = 0;
	private int p2BreakAnimHide = 0;
	// Horizontal separator where the P1 and P2 fields meet (anchor for centered effect prompts)
	private JSeparator fieldDivider;
	// Player field/board panels — retained so the Preferences field-color dropdowns can retint them
	private GradientPanel p1Board;
	private GradientPanel p2Board;
	private JPanel p1ZonesPanel;
	private JPanel p2ZonesPanel;
	// Opening hand confirmation popup
	private JWindow openingHandPopup;
	// Hand hover popover (deck zone mouseover)
	// Stack overlay (shown while any entry is on the resolution stack)
	private JWindow               summonStackWindow;
	private Timer     stackCountdownTimer;
	private int                   stackWindowGeneration = 0;

	// --- Game state ---
	final GameState gameState   = new GameState();
	private LookAtDeckDialogs lookDialogsInstance;
	// UI-only state (not owned by GameState)
	JLabel[]    p1BackupLabels = new JLabel[5];
	String[]    p1BackupUrls   = new String[5];
	CardData[]  p1BackupCards  = new CardData[5];
	CardState[] p1BackupStates = new CardState[5];

	private final List<JLabel>    p1ForwardLabels      = new ArrayList<>();
	private final List<String>    p1ForwardUrls;
	final List<CardData>  p1ForwardCards       = new ArrayList<>();
	final List<CardState> p1ForwardStates      = new ArrayList<>();
	final List<Integer>   p1ForwardPlayedOnTurn = new ArrayList<>();
	final List<Integer>   p1ForwardDamage       = new ArrayList<>();
	/** Top card of a Primed stack; {@code null} at each index means not primed. */
	final List<CardData>  p1ForwardPrimedTop   = new ArrayList<>();
	final List<CardData>  p2ForwardPrimedTop   = new ArrayList<>();
	/** Per-slot frozen flags — independent of CardState (a card may be Dulled AND frozen). */
	final List<Boolean>   p1ForwardFrozen      = new ArrayList<>();
	final List<Boolean>   p2ForwardFrozen      = new ArrayList<>();
	final List<Integer>                           p1ForwardPowerBoost     = new ArrayList<>();
	final List<Integer>                           p2ForwardPowerBoost     = new ArrayList<>();
	final List<Integer>                           p1ForwardPowerReduction = new ArrayList<>();
	final List<Integer>                           p2ForwardPowerReduction = new ArrayList<>();
	final List<EnumSet<CardData.Trait>> p1ForwardTempTraits    = new ArrayList<>();
	final List<EnumSet<CardData.Trait>> p2ForwardTempTraits    = new ArrayList<>();
	final List<EnumSet<CardData.Trait>> p1ForwardRemovedTraits = new ArrayList<>();
	final List<EnumSet<CardData.Trait>> p2ForwardRemovedTraits = new ArrayList<>();
	/** Temporary job granted to P1/P2 Forwards until end of turn; {@code null} = no override. */
	final List<String> p1ForwardTempJobs = new ArrayList<>();
	final List<String> p2ForwardTempJobs = new ArrayList<>();
	/**
	 * Per-turn combat restrictions and compulsions, keyed by card instance.
	 *
	 * <p>These were slot indices into the Forward row until 14-064R Kitone, which chooses a
	 * <em>Character</em> and so can land on a Backup or a Monster — neither of which has a
	 * Forward-row index to key on, and both of which can still end up attacking or blocking once
	 * something turns them into a Forward for the turn. Card identity is what all three rows have
	 * in common, so it is what the restriction hangs on.
	 *
	 * <p>Two consequences of the change are worth knowing. Indices no longer have to be renumbered
	 * when a Forward leaves the row — the {@code shiftBlockSet}/{@code shiftBlockMap} pair that
	 * did that, and the "wrong card is flagged" bugs it existed to prevent, are both gone with the
	 * last index-keyed combat collection. In exchange, a card that
	 * leaves the field has to be dropped explicitly, or a restriction would follow the instance
	 * back in when it is replayed from the Break Zone — see {@link #clearCombatRestrictionsFor}.
	 *
	 * <p>The P1/P2 split is kept because it is load-bearing for the persistent pair: each side's
	 * is cleared at its own controller's end phase, not at every end phase.
	 */
	final Set<CardData> p1CannotBlock = Collections.newSetFromMap(new IdentityHashMap<>());
	final Set<CardData> p2CannotBlock = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Characters that must be chosen as a blocker this turn if they are eligible. */
	final Set<CardData> p1MustBlock   = Collections.newSetFromMap(new IdentityHashMap<>());
	final Set<CardData> p2MustBlock   = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Characters that may not attack for the remainder of this turn. */
	final Set<CardData> p1CannotAttack = Collections.newSetFromMap(new IdentityHashMap<>());
	final Set<CardData> p2CannotAttack = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Characters that must attack this turn if they are eligible. */
	final Set<CardData> p1MustAttack   = Collections.newSetFromMap(new IdentityHashMap<>());
	final Set<CardData> p2MustAttack   = Collections.newSetFromMap(new IdentityHashMap<>());
	/** Characters restricted from attacking until the end of their owner's turn (survives one end-phase). */
	final Set<CardData> p1CannotAttackPersistent = Collections.newSetFromMap(new IdentityHashMap<>());
	final Set<CardData> p2CannotAttackPersistent = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Characters shut out of action abilities for the remainder of this turn — 14-064R Kitone.
	 *
	 * <p>Keyed by card instance rather than by row index because the effect that fills it chooses a
	 * Character, so the restriction has to survive on Backups and Monsters too, where there is no
	 * Forward-row index to key on. Cleared with the other end-of-turn restriction sets.
	 */
	final Set<CardData> cannotUseActionAbilitiesThisTurn = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Attack declarations each Character has made this turn, keyed by card instance.
	 *
	 * <p>Only an active Character may declare an attack, so this is what stops one attacking again
	 * once it is back to active — whether Brave kept it active or an effect re-activated it. A
	 * permission ("can attack twice", "can attack once more this turn") raises the allowance this
	 * is compared against; see {@link #hasAttackRemaining}.
	 *
	 * <p>Keyed by instance rather than slot index because a Monster or Backup that temporarily
	 * became a Forward can be handed a multi-attack permission too (Chelinka 11-049R grants to
	 * "1 Forward"), and because an instance key needs no re-indexing when a slot is vacated.
	 */
	final Map<CardData, Integer> attacksMadeThisTurn = new IdentityHashMap<>();
	/** One-shot extra attacks granted this turn by "[X] can attack once more this turn.", by instance. */
	final Map<CardData, Integer> extraAttacksThisTurn = new IdentityHashMap<>();
	/** Cards granted a multi-attack permission until end of turn, instance to permitted count. */
	final Map<CardData, Integer> grantedMaxAttacks = new IdentityHashMap<>();
	/**
	 * Field abilities handed to a card until end of turn by a "[Self] gains '&lt;ability&gt;' until the
	 * end of the turn" effect (e.g. Caius 18-108H's damage doubler), keyed by instance. Read through
	 * {@link #effectiveFieldAbilities} so a granted ability behaves exactly like a printed one.
	 */
	final Map<CardData, List<FieldAbility>> grantedFieldAbilities = new IdentityHashMap<>();
	/**
	 * Auto abilities handed to a card by a grant that explicitly outlasts the turn — "[Self] gains
	 * '&lt;ability&gt;' (This effect does not end at the end of the turn.)", the priming payoff on
	 * Odin (XVI) 29-118L / 24-112L — keyed by instance. Read through
	 * {@link #effectiveAutoAbilities} so a granted trigger fires exactly like a printed one.
	 *
	 * <p>Deliberately <em>not</em> cleared with the end-of-turn grants: that wording is the whole
	 * point of the ability. It is dropped when the card leaves the field, since a Character that
	 * leaves loses everything granted to it and comes back as a new object anyway.
	 */
	final Map<CardData, List<AutoAbility>> grantedAutoAbilities = new IdentityHashMap<>();
	/** Cards granted a multi-attack permission by an effect that outlasts the turn, instance to count. */
	final Map<CardData, Integer> permanentMaxAttacks = new IdentityHashMap<>();
	/**
	 * Field abilities handed to a card by a grant that outlasts the turn — the permanent twin of
	 * {@link #grantedFieldAbilities}, read through the same {@link #effectiveFieldAbilities} view.
	 *
	 * <p>A separate map rather than a flag on the other one: {@link #grantedFieldAbilities} is
	 * emptied wholesale at the turn boundary, which is exactly what these grants must survive.
	 * Like {@link #grantedAutoAbilities} it is dropped only when the card leaves the field.
	 */
	final Map<CardData, List<FieldAbility>> permanentFieldAbilities = new IdentityHashMap<>();
	/**
	 * Cards shielded from the opponent's Summons / abilities by a grant that outlasts the turn —
	 * the permanent twin of {@link #cannotBeChosenBySummons} / {@link #cannotBeChosenByAbilities}
	 * (Young Excenmille 23-100L). Separate sets for the same reason as
	 * {@link #permanentFieldAbilities}: the turn-scoped pair is emptied at the turn boundary.
	 *
	 * <p>Keyed by identity, like the rest of the permanent-grant family, so the shield belongs to
	 * the instance that earned it rather than to every copy of the card.
	 */
	final Set<CardData> permanentCannotBeChosenBySummons   = Collections.newSetFromMap(new IdentityHashMap<>());
	final Set<CardData> permanentCannotBeChosenByAbilities = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Cards under a permanent "[Self] must attack once per turn if possible" compulsion
	 * (Roche 29-076H). Unlike {@link #p1MustAttack}, which is a one-turn instruction cleared at the
	 * turn boundary, this re-arms every turn and so is never cleared there —
	 * {@link #attacksMadeThisTurn} is what makes it "once per turn".
	 */
	final Set<CardData> permanentMustAttackOncePerTurn = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Power added by an effect that outlasts the turn, and so is not zeroed by the end phase the
	 * way {@link #p1ForwardPowerBoost} is. Keyed by card identity because the boost belongs to the
	 * instance on the field, not to every copy of the card.
	 */
	final Map<CardData, Integer> permanentPowerBoost = new IdentityHashMap<>();
	/** Traits granted by an effect that outlasts the turn — the counterpart of {@link #p1ForwardTempTraits}. */
	final Map<CardData, EnumSet<CardData.Trait>> permanentTraits = new IdentityHashMap<>();
	/** Shared empty lookup default, so the trait reads do not allocate an EnumSet per call. */
	private static final EnumSet<CardData.Trait> NO_TRAITS = EnumSet.noneOf(CardData.Trait.class);
	/** Characters restricted from blocking until the end of their owner's turn (survives one end-phase). */
	final Set<CardData> p1CannotBlockPersistent  = Collections.newSetFromMap(new IdentityHashMap<>());
	final Set<CardData> p2CannotBlockPersistent  = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Attackers that cannot be blocked this turn, and attackers that cannot be blocked by a
	 * Character whose cost matches the filter {@code {costVal, 1=isMore/0=isLess}}.
	 *
	 * <p>Keyed by card instance for the same reasons as the defender-side sets above: the grant
	 * belongs to the Character, which may be attacking from the Monster or Backup row, and an
	 * instance key needs no renumbering when the Forward row shifts. Cleared on departure by
	 * {@link #clearCombatRestrictionsFor} and at the turn boundary.
	 */
	final Set<CardData>          p1CannotBeBlocked       = Collections.newSetFromMap(new IdentityHashMap<>());
	final Set<CardData>          p2CannotBeBlocked       = Collections.newSetFromMap(new IdentityHashMap<>());
	final Map<CardData, int[]>   p1CannotBeBlockedByCost = new IdentityHashMap<>();
	final Map<CardData, int[]>   p2CannotBeBlockedByCost = new IdentityHashMap<>();
	// The power twin, granted rather than printed (Iris 12-117R). Kept apart from the cost maps
	// because the two thresholds are read against different things — the blocker's cost, and its
	// effective power, which moves during the block.
	final Map<CardData, int[]>   p1CannotBeBlockedByPower = new IdentityHashMap<>();
	final Map<CardData, int[]>   p2CannotBeBlockedByPower = new IdentityHashMap<>();
	final boolean[]       p1BackupFrozen       = new boolean[5];
	final boolean[]       p2BackupFrozen       = new boolean[5];
	final List<Boolean>   p1MonsterFrozen      = new ArrayList<>();
	private JPanel p1ForwardPanel;

	/** Turn number on which each backup slot was last filled (0 = empty/unknown). */
	private final int[] p1BackupPlayedOnTurn = new int[5];

	// State for Backups temporarily acting as Forwards (e.g. 17-012R). Keyed by CardData.
	final Map<CardData, Integer> p1BackupTempForwardPower = new HashMap<>();
	final Map<CardData, Integer> p2BackupTempForwardPower = new HashMap<>();
	/**
	 * The Backups above whose promotion outlasts the turn — 17-128L Maria's "(This effect does not
	 * end at the end of the turn.)".
	 *
	 * <p>They share the two power maps rather than living in maps of their own, because everything
	 * that reads a Backup acting as a Forward reads those; what differs is only the sweep. So the
	 * membership is kept here and {@link #clearBackupForwardState} skips these keys.
	 *
	 * <p>Identity, like {@link #lostAbilitiesCards}: {@link CardData} is a record, and two copies of
	 * one card must not share an entry.
	 */
	final Set<CardData> backupPermanentForwards = Collections.newSetFromMap(new IdentityHashMap<>());
	final Map<CardData, List<ActionAbility>> p1TempGrantedAbilities = new HashMap<>();
	final Map<CardData, List<ActionAbility>> p2TempGrantedAbilities = new HashMap<>();
	final Map<CardData, Integer> p1BackupForwardBoost     = new HashMap<>();
	final Map<CardData, Integer> p2BackupForwardBoost     = new HashMap<>();
	final Map<CardData, EnumSet<CardData.Trait>> p1BackupTempTraits = new HashMap<>();
	final Map<CardData, EnumSet<CardData.Trait>> p2BackupTempTraits = new HashMap<>();
	final Map<CardData, Integer> p1BackupForwardDamage    = new HashMap<>();
	final Map<CardData, Integer> p2BackupForwardDamage    = new HashMap<>();
	int p1BackupAttackIdx = -1;
	private int p2BackupAttackIdx = -1;

	final List<JLabel>   p1MonsterLabels      = new ArrayList<>();
	final List<String>   p1MonsterUrls        = new ArrayList<>();
	final List<CardData> p1MonsterCards       = new ArrayList<>();
	final List<CardState> p1MonsterStates      = new ArrayList<>();
	final List<Integer>  p1MonsterPlayedOnTurn = new ArrayList<>();
	final List<Integer>  p1MonsterDamage       = new ArrayList<>();
	private int                  p1MonsterAttackIdx    = -1;
	final Map<CardData, Integer> p1MonsterTempForwardPower = new HashMap<>();
	final Map<CardData, Integer> p1MonsterPowerBoost = new HashMap<>();
	final Map<CardData, EnumSet<CardData.Trait>> p1MonsterTempTraits = new HashMap<>();
	JPanel p1MonsterPanel;

	final List<Boolean>   p2MonsterFrozen       = new ArrayList<>();
	final List<JLabel>    p2MonsterLabels        = new ArrayList<>();
	final List<String>    p2MonsterUrls          = new ArrayList<>();
	final List<CardData>  p2MonsterCards         = new ArrayList<>();
	final List<CardState> p2MonsterStates        = new ArrayList<>();
	final List<Integer>   p2MonsterPlayedOnTurn  = new ArrayList<>();
	final List<Integer>   p2MonsterDamage        = new ArrayList<>();
	final Map<CardData, Integer> p2MonsterTempForwardPower = new HashMap<>();
	final Map<CardData, Integer> p2MonsterPowerBoost = new HashMap<>();
	final Map<CardData, EnumSet<CardData.Trait>> p2MonsterTempTraits = new HashMap<>();
	JPanel p2MonsterPanel;

	int      p2DamageCount = 0;
	private JPanel[] p2DamageSlots = new JPanel[7];

	// P2 field state (managed by ComputerPlayer)
	final JLabel[]     p2BackupLabels        = new JLabel[5];
	final String[]     p2BackupUrls          = new String[5];
	final CardData[]   p2BackupCards         = new CardData[5];
	final CardState[]  p2BackupStates        = new CardState[5];
	private JPanel             p2ForwardPanel;
	private final List<JLabel>    p2ForwardLabels       = new ArrayList<>();
	private final List<String>    p2ForwardUrls         = new ArrayList<>();
	final List<CardData>  p2ForwardCards        = new ArrayList<>();
	final List<CardState> p2ForwardStates       = new ArrayList<>();
	final List<Integer>   p2ForwardPlayedOnTurn = new ArrayList<>();
	final List<Integer>   p2ForwardDamage       = new ArrayList<>();
	/** Drives every P2 decision — the local AI, or a remote human in a networked game. */
	OpponentController    opponent;
	/** The mechanical turn steps both opponent drivers share. */
	private final TurnPhases turnPhases = new TurnPhases(this);

	TurnPhases turnPhases() { return turnPhases; }

	final Set<Integer> spentLbIndices   = new HashSet<>();
	final Set<Integer> p2SpentLbIndices = new HashSet<>();

	// Damage zone UI
	private JPanel     p1DamageSlotPanel;
	private JPanel[]   p1DamageSlots = new JPanel[7];
	ShieldIcon         p1ShieldIcon;
	ShieldIcon         p2ShieldIcon;

	// Next-phase button and its glow animation
	JButton              nextPhaseButton;
	private Timer    glowTimer;
	private final float[]        glowAngle = { 0f };

	// Phase tracker strip
	private PhaseTracker         phaseTracker;

	// Attack button and selection state for party attacks
	private JButton              attackButton;
	private JButton              skipAttackButton;
	final List<Integer>  p1AttackSelection = new ArrayList<>();
	/**
	 * The cards P1 has actually declared as attackers for the combat in progress — set when the
	 * attack is declared and cleared when it resolves. {@link #p1AttackSelection} is emptied at
	 * declaration time, so this is what "while [card] is attacking" abilities must consult while
	 * P1 holds priority after declaring.
	 */
	final List<CardData> p1DeclaredAttackers = new ArrayList<>();
	/** Same as {@link #p1DeclaredAttackers}, for the attack P2 has declared against P1. */
	final List<CardData> p2DeclaredAttackers = new ArrayList<>();
	int                  p1BlockingIdx     = -1;

	// In-place field targeting: while active, the normal field-card click handlers
	// (attack selection, context menus) are suppressed so clicks pick effect targets.
	private boolean fieldTargetingActive = false;

	// Temporary attack triggers registered by action abilities (cleared at end of turn)
	final Map<CardData, List<Consumer<GameContext>>> p1TempAttackTriggers = new LinkedHashMap<>();
	final Map<CardData, List<Consumer<GameContext>>> p2TempAttackTriggers = new LinkedHashMap<>();
	final Map<CardData, List<Consumer<GameContext>>> p1TempBlockTriggers  = new LinkedHashMap<>();
	final Map<CardData, List<Consumer<GameContext>>> p2TempBlockTriggers  = new LinkedHashMap<>();
	/**
	 * The other half of a granted "blocks or is blocked" trigger (4-142R Malboro), kept apart from
	 * the block maps above because the two events fire from different places.
	 */
	final Map<CardData, List<Consumer<GameContext>>> p1TempIsBlockedTriggers = new LinkedHashMap<>();
	final Map<CardData, List<Consumer<GameContext>>> p2TempIsBlockedTriggers = new LinkedHashMap<>();

	// Attack phase sub-step (0=Prep, 1=Declare, 2=Block, 3=Damage; -1=not in attack phase)
	int attackSubStep = -1;

	// Non-modal P2-attack pending state: set while P1 is interactively declaring a blocker
	CardData pendingP2Attacker        = null;   // package-private: tests open the block step with it
	int      pendingP2AttackerIdx     = -1;     // …and name which Forward is attacking
	private Runnable pendingP2BlockDone       = null;
	boolean  pendingP2AttackerIsMonster = false;
	boolean  pendingP2AttackerIsBackup  = false;
	int      pendingP2AttackerPower     = 0;
	private int           p1BlockerSelection      = -1;   // index of forward P1 clicked to block with
	private int           p1BlockerMonsterIdx     = -1;   // P1 monster acting as Forward chosen to block
	private int           p1BlockerBackupIdx      = -1;   // P1 backup acting as Forward chosen to block
	List<Integer>         pendingP2PartyIndices   = null; // set while P1 declares blocker vs P2 party
	private int           pendingP2PartyCombined  = 0;

	// Blocking-target tracking: set between "Blocker Declared" and resolveCombat so that
	// "Choose 1 Forward blocking [Name/Job]" effects can identify the blocking forward.
	CardData p1BlockedByAttacker  = null; // P2 attacker that p1BlockingIdx is blocking
	int      p2BlockingIdx        = -1;   // P2 forward blocking a P1 attacker
	CardData p2BlockedByAttacker  = null; // P1 attacker that p2BlockingIdx is blocking

	// Power of the Forward dulled as "Dull N active Forward" ability cost; set during payment.
	int      lastDullForwardCostPower = 0;
	// How many dull cards the last mass ACTIVATE sweep actually activated; read back by an effect
	// whose payoff counts them ("When 4 or more dull Characters are activated by this effect" —
	// 19-102L Refia). Reset by every applyMassFieldEffect call, whatever its action.
	int      lastMassActivateCount    = 0;
	// Power of the Forward put into the Break Zone as an ability cost; set during payment.
	int      lastBzCostForwardPower   = 0;
	// Set by an EX burst suppression clause; cleared at the start of each new ability context.
	boolean  suppressExBurstsThisAbility = false;

	/**
	 * Set while resolving an effect whose "choose" targets only benefit from it, so an AI
	 * controller aims an unqualified selection at its own cards instead of the opponent's.
	 * Consumed by the next auto-selection and reset with each new ability context.
	 */
	boolean  aiPrefersOwnTargets = false;

	boolean  effectProgress = true;

	private Timer         p2AutoPassTimer;
	/** Non-null while P1 holds priority during P2's main phase; callback advances to the next phase. */
	private Runnable      p1PriorityInP2MainOnDone = null;
	/**
	 * Non-null while P1 holds priority at a combat checkpoint on their own turn (currently: right
	 * after declaring an attacker). P1 may cast Summons or use action abilities; clicking Next
	 * runs this callback, which passes priority to P2 and continues the combat step.
	 */
	private Runnable      p1CombatPriorityOnPass = null;

	// Damage-shield / damage-modifier state (keyed by CardData identity; cleared at end of turn)
	final Set<CardData>          nextIncomingDmgZeroSet        = new HashSet<>();
	/**
	 * The unspent twin of {@link #nextIncomingDmgZeroSet}: 5-081C Cockatrice zeroes <em>every</em>
	 * damage the chosen Forward is dealt for the rest of the turn, not the next one only, so this
	 * set is read without being removed from and is emptied with the rest at the end of the turn.
	 */
	final Set<CardData>          allIncomingDmgZeroThisTurnSet = new HashSet<>();
	/**
	 * The source-scoped twin of {@link #nextIncomingDmgZeroSet} — Auron 22-001R shields "the next
	 * damage dealt to it <em>by your opponent's Summons or abilities</em>", so combat damage neither
	 * consumes the shield nor is stopped by it.
	 *
	 * <p>Separate from the unqualified set rather than a flag on it: the two differ in what spends
	 * them, and a shield spent by the wrong kind of damage is a shield that was never there.
	 */
	final Set<CardData>          nextOppEffectDmgZeroSet       = new HashSet<>();
	final Map<CardData, CardData> nextIncomingDmgRedirectMap   = new HashMap<>();
	final Map<CardData, Integer> nextIncomingDmgReduceMap      = new HashMap<>();
	/**
	 * The bill attached to a one-shot reduction in {@link #nextIncomingDmgReduceMap} — Cecil
	 * 9-109H's "reduce it by 4000 instead and deal Cecil 4000 damage". Keyed by the shielded card;
	 * removed in step with the reduction it belongs to.
	 */
	final Map<CardData, ShieldKickback> nextIncomingDmgReduceKickbackMap = new HashMap<>();
	/**
	 * Kickbacks whose shields have just been spent, waiting for the damage that spent them to
	 * finish resolving. Drained by {@link #fireShieldKickbacks()} at the end of
	 * {@code applyDamageToForward}: dealing them any earlier could break a Forward and shift the
	 * indices that call is still working with.
	 */
	final List<ShieldKickback> pendingShieldKickbacks = new ArrayList<>();
	/** What a damage-reduction shield costs the card that lent it, and which side that card is on. */
	record ShieldKickback(boolean bearerIsP1, CardData bearer, int damage) {}
	final Map<CardData, Integer> nextAbilityDmgReduceMap       = new HashMap<>();
	final Map<CardData, Integer> incomingDmgIncreaseMap   = new HashMap<>();
	int globalForwardIncomingDmgIncrease = 0; // flat increase applied to ALL Forwards' incoming damage this turn
	boolean allForwardsCannotBeBlockedByHigherCostThisTurn = false;
	final Set<CardData>          nullifyAbilityDmgSet     = new HashSet<>();
	final Set<CardData>          nullifyAbilityOnlyDmgSet = new HashSet<>();
	/**
	 * The mirror of {@link #nullifyAbilityOnlyDmgSet}: damage from a Summon becomes 0, damage from
	 * any other ability does not. 6-125R Leviathan's third option says "by a Summon" and stops
	 * there, where its B-047 reprint says "by a Summon or an ability" and takes the wider set.
	 */
	final Set<CardData>          nullifySummonOnlyDmgSet  = new HashSet<>();
	final Set<CardData>          nextOutgoingDmgZeroSet      = new HashSet<>();
	/**
	 * The unspent twin of {@link #nextOutgoingDmgZeroSet}, and the outgoing mirror of
	 * {@link #allIncomingDmgZeroThisTurnSet}: 23-024R Shiva zeroes <em>every</em> damage the
	 * chosen Forward deals for the rest of the turn — "to a Forward or a player", so combat and
	 * its own abilities alike — rather than the next one only. Read without being removed from.
	 *
	 * <p>Identity-keyed, for the reason {@link #damageZeroedSourcesThisTurn} gives: {@code CardData}
	 * is a record, so a second printing of the same card is {@code equals} to the one that was
	 * chosen and a value-keyed set would shield it too.
	 */
	final Set<CardData>          allOutgoingDmgZeroThisTurnSet =
			Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * The narrow member of that family: only the damage this card deals to a <em>Forward</em>, and
	 * only when it is not battle damage. 17-027R Shiva's second option — "if it deals damage other
	 * than battle damage to a Forward this turn, the damage becomes 0 instead" — which leaves both
	 * its combat damage and its damage to a player alone, where 23-024R Shiva stops everything.
	 *
	 * <p>Identity-keyed for the reason {@link #allOutgoingDmgZeroThisTurnSet} gives.
	 */
	final Set<CardData>          abilityDmgToForwardZeroedThisTurnSet =
			Collections.newSetFromMap(new IdentityHashMap<>());
	final Map<CardData, Integer> outgoingDmgMultiplierMap    = new IdentityHashMap<>();
	final Map<CardData, Integer> outgoingDmgFlatBoostMap     = new IdentityHashMap<>();
	final Set<CardData>          nextOutgoingDmgDoublerSet   = new HashSet<>();
	final Map<CardData, Integer> perCardIncomingDmgMultiplierMap = new IdentityHashMap<>();
	// Set by resolveCombat before modifyIncomingDamage so field abilities can inspect the attacker's traits
	CardData currentBattleAttacker      = null;
	boolean  currentBattleAttackerIsP1  = false;
	int      currentBattleAttackerIdx   = -1;
	// Zone of the current battle attacker so trait checks work when it is a Monster/Backup acting as a Forward
	ForwardTarget.CardZone currentBattleAttackerZone = ForwardTarget.CardZone.FORWARD;
	final Set<CardData>          perCardNonLethalDmgSet      = new HashSet<>();
	// Power and name of the card most recently discarded as part of resolving an ability.
	int      lastDiscardedForwardPower    = 0;
	String   lastDiscardedCardName        = null;
	// Card most recently discarded by an effect (not a cost), for "If the discarded card is …" conditionals.
	CardData lastDiscardedCard            = null;
	// Card most recently discarded as a cost payment (for element-conditional branch effects).
	CardData lastDiscardedCostCard        = null;
	// Cost/power of the Forward most recently removed from the game by a "remove it from the game" effect.
	int     lastRemovedFromGameCardCost  = 0;
	int     lastRemovedFromGameCardPower = 0;

	/** Per-player turn-scoped rules state. Prefer {@link #turn(boolean)} over these directly. */
	final PlayerTurnState p1Turn = new PlayerTurnState();
	final PlayerTurnState p2Turn = new PlayerTurnState();

	/** The turn-scoped state of the given player. */
	PlayerTurnState turn(boolean isP1) {
		return isP1 ? p1Turn : p2Turn;
	}

	/** The turn-scoped state of the given player's opponent. */
	PlayerTurnState oppTurn(boolean isP1) {
		return isP1 ? p2Turn : p1Turn;
	}

	// CP bank accessors, so rules that apply to either player can be written once.
	void addCp(boolean isP1, String element, int amount) {
		if (isP1) gameState.addP1Cp(element, amount); else gameState.addP2Cp(element, amount);
	}

	boolean spendCp(boolean isP1, String element, int amount) {
		return isP1 ? gameState.spendP1Cp(element, amount) : gameState.spendP2Cp(element, amount);
	}

	void clearCp(boolean isP1, String element) {
		if (isP1) gameState.clearP1Cp(element); else gameState.clearP2Cp(element);
	}

	int cpForElement(boolean isP1, String element) {
		return isP1 ? gameState.getP1CpForElement(element) : gameState.getP2CpForElement(element);
	}

	/** End-of-turn effects queued this turn; fired at the beginning of the END phase. */
	final List<Consumer<GameContext>> endOfTurnEffects = new ArrayList<>();

	/** Effects to fire at the end of P1's turn (scheduled by P2 or by "end of opponent's turn" effects). */
	final List<Consumer<GameContext>> scheduledForP1EndTurn = new ArrayList<>();
	/** Effects to fire at the end of P2's turn (scheduled by P1 or by "end of opponent's turn" effects). */
	final List<Consumer<GameContext>> scheduledForP2EndTurn = new ArrayList<>();

	/**
	 * Cards whose printed abilities are currently suppressed: those an effect stripped until end of
	 * turn ("lose all abilities"), plus those a standing field ability is suppressing right now —
	 * Gentiana 11-033R's "The dull Forwards opponent controls lose their abilities."
	 *
	 * <p>Membership is therefore part stored and part derived. That is what lets the standing form
	 * be honoured at all 121 places that ask this question without any of them learning about it,
	 * and it is why the derived half must be a live query: a Forward covered by Gentiana gets its
	 * abilities back the moment it activates, with no event to hang a removal on.
	 *
	 * <p>Only ever asked ({@code contains}) and written ({@code add}/{@code remove}/{@code clear});
	 * never iterated or sized, which is what makes a partly-derived membership safe here.
	 */
	final Set<CardData> lostAbilitiesCards = new LostAbilitiesSet();

	/**
	 * Victim to warden for "As long as [Self] is on the field, it loses all its abilities"
	 * (25-035L Aerith, 20-116R Meliadoul) — the third half of {@link #lostAbilitiesCards}, and
	 * derived for the same reason as the second: the silence ends when the warden leaves, and
	 * there is no event to hang that restoration on.
	 *
	 * <p>Identity on both sides. The warden is the printing that resolved the trigger, so an
	 * opposing card of the same name neither sustains the silence nor lifts it; the victim is a
	 * specific card, so a second copy of it is untouched.
	 */
	final Map<CardData, CardData> abilitiesStrippedWhileWardenOnField = new IdentityHashMap<>();

	/**
	 * One grant made "As long as [warden] is on the field, it gains ..." -- 16-066R Heretical
	 * Knight Garland and 15-125R Lunafreya -- recorded as what it actually added on top of what the
	 * grantee already had.
	 *
	 * <p>The grant itself is applied into the ordinary outlasts-the-turn stores, so every reader
	 * that already honours a permanent boost, trait or shield honours this one with no change. What
	 * this record adds is the means to take it back again: {@code power}, {@code traits} and the two
	 * shield flags hold the delta this grant contributed, so revoking removes exactly that and a
	 * boost the card held from some other source survives the warden's departure.
	 *
	 * <p>Identity on both sides, like {@link #abilitiesStrippedWhileWardenOnField}: the warden is the
	 * printing that resolved the trigger, so an opposing card of the same name neither sustains the
	 * grant nor ends it.
	 */
	record WardenHeldGrant(CardData warden, CardData grantee, int power,
			EnumSet<CardData.Trait> traits, boolean shieldFromSummons, boolean shieldFromAbilities) {}

	/**
	 * Every warden-held grant currently standing. A list rather than a map keyed by grantee because
	 * two wardens can hold grants on the same Character, and each has to be revoked on its own
	 * warden's departure.
	 *
	 * <p>Walked only when a card leaves the field, and guarded on empty there, so the ordinary game
	 * -- in which this is never populated -- pays nothing for it.
	 */
	final List<WardenHeldGrant> wardenHeldGrants = new ArrayList<>();

	/**
	 * Backing store for {@link #lostAbilitiesCards}: an identity set of the cards an effect has
	 * stripped, with {@code contains} widened to include the standing suppressions.
	 *
	 * <p>Identity, not {@code equals}: {@link CardData} is a record, so two copies of one card
	 * would otherwise share an entry and one copy's restoration would return the other's abilities.
	 */
	private final class LostAbilitiesSet extends java.util.AbstractSet<CardData> {
		private final Set<CardData> stripped = Collections.newSetFromMap(new IdentityHashMap<>());

		@Override public boolean add(CardData c)                { return stripped.add(c); }
		@Override public boolean remove(Object c)               { return stripped.remove(c); }
		@Override public void    clear()                        { stripped.clear(); }
		@Override public java.util.Iterator<CardData> iterator(){ return stripped.iterator(); }
		@Override public int     size()                         { return stripped.size(); }

		@Override public boolean contains(Object o) {
			if (stripped.contains(o)) return true;
			if (!(o instanceof CardData c)) return false;
			return silencedByWardenOnField(c) || abilitiesSuppressedByOpposingField(c);
		}

		/**
		 * The stored half alone. {@link #abilitiesSuppressedByOpposingField} asks this rather than
		 * {@code contains} when deciding whether a suppressor is itself silenced — going through
		 * {@code contains} would recurse straight back into here.
		 */
		boolean strippedByEffect(CardData c) { return stripped.contains(c); }
	}

	/**
	 * Whether {@code card} is under an Aerith-style standing silence whose warden is still on the
	 * field.
	 *
	 * <p>The entry is dropped as soon as it is found stale, so a warden that has left cannot
	 * silence anything again by coming back later: the two are different cards once the first has
	 * left, and only the pairing made while it was out should ever have been honoured.
	 *
	 * <p>Guarded on an empty map first, because {@link #lostAbilitiesCards} is asked on hot paths
	 * and no ordinary game ever puts an entry here.
	 */
	private boolean silencedByWardenOnField(CardData card) {
		if (abilitiesStrippedWhileWardenOnField.isEmpty()) return false;
		CardData warden = abilitiesStrippedWhileWardenOnField.get(card);
		if (warden == null) return false;
		if (identityIndexOf(fieldCards(true), warden) >= 0
				|| identityIndexOf(fieldCards(false), warden) >= 0) return true;
		abilitiesStrippedWhileWardenOnField.remove(card);
		return false;
	}

	/**
	 * The stored half of {@link #lostAbilitiesCards} — whether an <em>effect</em> has silenced
	 * {@code card}, without asking whether the opposing field is silencing it.
	 *
	 * <p>For readers that {@link #abilitiesSuppressedByOpposingField} itself reaches, which is
	 * anything it consults through {@link #effectiveFieldAbilities}: asking full membership from
	 * there recurses back into this set, exactly as it would for the suppressor check inside it.
	 */
	private boolean abilitiesStrippedByEffect(CardData card) {
		return ((LostAbilitiesSet) lostAbilitiesCards).strippedByEffect(card);
	}

	/**
	 * Whether {@code card} is currently having its abilities suppressed by a standing field ability
	 * on the opposing side — Gentiana 11-033R.
	 *
	 * <p>The structural test comes first and is deliberately cheap: the only standing suppression in
	 * the corpus names <em>dull Forwards</em>, so anything active, and anything that is not a
	 * Forward, is answered without touching a field. This matters because
	 * {@link #lostAbilitiesCards} is consulted on hot paths.
	 *
	 * <p>A suppressor that an <em>effect</em> has silenced prints nothing and so suppresses nothing.
	 * That check reads the stored half directly: asking the full membership would recurse. The
	 * consequence is that two facing Gentianas do not silence each other, which is also the answer
	 * that terminates.
	 */
	private boolean abilitiesSuppressedByOpposingField(CardData card) {
		if (card == null) return false;
		Boolean side = dullForwardSideOf(card);
		if (side == null) return false;
		LostAbilitiesSet set = (LostAbilitiesSet) lostAbilitiesCards;
		for (CardData src : fieldCards(!side)) {
			if (src == null || set.strippedByEffect(src)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(src))
				if (AutoAbilityTriggers.FA_OPP_DULL_FORWARDS_LOSE_ABILITIES
						.matcher(fa.effectText().trim()).matches()) return true;
		}
		return false;
	}

	/**
	 * The side controlling {@code card} when it is a Forward standing dull, or {@code null} when it
	 * is active, is not a Forward, or is not on the field at all.
	 */
	private Boolean dullForwardSideOf(CardData card) {
		for (int i = 0; i < p1ForwardCards.size(); i++)
			if (p1ForwardCards.get(i) == card)
				return i < p1ForwardStates.size() && p1ForwardStates.get(i) == CardState.DULL
						? Boolean.TRUE : null;
		for (int i = 0; i < p2ForwardCards.size(); i++)
			if (p2ForwardCards.get(i) == card)
				return i < p2ForwardStates.size() && p2ForwardStates.get(i) == CardState.DULL
						? Boolean.FALSE : null;
		return null;
	}

	/**
	 * Cards <em>granted</em> "EX Bursts of cards put into the Damage Zone due to [this card] cannot
	 * be used" until end of turn (Shadow Lord 12-071R), mapped to the highest card cost they
	 * suppress ({@link Integer#MAX_VALUE} = any cost).  Printed versions of the same ability are
	 * not listed here — {@link #exBurstSuppressedBy} reads those off the source's field abilities.
	 * Unlike {@link #suppressExBurstsThisAbility}, which covers a single ability resolution, this
	 * follows the source card, so its combat damage is covered too.
	 */
	final Map<CardData, Integer> exBurstSuppressingSources = new IdentityHashMap<>();

	/**
	 * Card credited with the single point of player damage currently being dealt, or {@code null}
	 * when the damage has no card source.  Consumed by {@link #p1TakeDamage}/{@link #p2TakeDamage};
	 * callers set it immediately before each point.
	 */
	private CardData playerDamageSource = null;

	/**
	 * Replacement base powers from "[Name]'s power becomes N" effects, keyed by card identity.
	 * Unlike {@link #p1ForwardPowerBoost}/{@link #p1ForwardPowerReduction}, this substitutes the
	 * card's printed power, so temporary boosts and reductions still apply on top of it.
	 * Entries are removed by an end-of-turn effect queued when the override is set.
	 */
	final Map<CardData, Integer> basePowerOverrides = new IdentityHashMap<>();

	final FieldGrantCalculator fieldGrantCalculator = new FieldGrantCalculator(this);

	/** Active "next cast costs N less" modifiers; consumed on first matching cast, or cleared at EOT. */
	final List<CostReductionModifier> activeCostReductions = new ArrayList<>();

	/**
	 * Cards moved to the RFP zone while paying the most recent ability's remove-from-game costs.
	 * Cleared each time an ability's costs are paid; read by "you can cast [X] removed by this
	 * ability's cost" followups (Sephiroth) to register those exact card instances as castable.
	 */
	final List<CardData> lastRfgCostCards = new ArrayList<>();

	// Doublecast (Yuna): "When you cast a Summon this turn, you may cast 1 Summon from your hand
	// with a cost inferior to that of the Summon you cast without paying its cost."
	// While active for a side, hand Summons with printed cost lower than the printed cost of the
	// last Summon that side cast this turn cast free. The threshold updates on EVERY Summon cast
	// (free or paid), so successively lower-cost Summons can chain for free. Cleared at EOT.
	boolean p1DoublecastFreeSummons = false, p2DoublecastFreeSummons = false;
	/** Printed cost of the last Summon cast while Doublecast is active; -1 = none cast yet. */
	int p1DoublecastLastSummonCost = -1, p2DoublecastLastSummonCost = -1;

	/**
	 * Cards P1 is permitted to cast from outside their hand (Break Zone or RFP zone) under a
	 * "cast it as though you owned it" effect.  The {@link PlayableEntry} value carries the
	 * source zone, cost reduction, any-element/free-cast flags, post-cast disposition, and
	 * duration.  Identity-keyed so duplicate-named copies don't alias.  "This turn" entries are
	 * cleared at end of turn; "during this game" / "at any time" entries persist.
	 */
	final IdentityHashMap<CardData, PlayableEntry> bzPlayableP1 = new IdentityHashMap<>();
	/** P2 equivalent of {@link #bzPlayableP1}. */
	final IdentityHashMap<CardData, PlayableEntry> bzPlayableP2 = new IdentityHashMap<>();
	/** Cards registered in bzPlayableP1/P2 specifically by the "cast Forwards from BZ" field ability
	 *  (used to remove them when that FA becomes inactive). */
	private final Set<CardData> bzForwardFaP1 = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<CardData> bzForwardFaP2 = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Cards registered in bzPlayableP1/P2 by a "you can cast [what] removed by [self]'s abilities"
	 * permission, each mapped to the card whose ability removed it.
	 *
	 * <p>One map for every printing of that shape — Setzer 21-031H and Rinoa 21-038R today — rather
	 * than a register per card: they differ only in what they open and whether they cap it, and both
	 * answers live on the permission itself. The remover is the value because the cap belongs to it:
	 * spending Setzer's one cast for the turn must not close Rinoa's.
	 */
	private final IdentityHashMap<CardData, CardData> removedPlayableSourceP1 = new IdentityHashMap<>();
	private final IdentityHashMap<CardData, CardData> removedPlayableSourceP2 = new IdentityHashMap<>();
	/** Cards registered in bzPlayableP1/P2 by their own "You can cast [self] from your Break Zone"
	 *  ability (used to remove them when they leave the Break Zone). */
	private final Set<CardData> bzSelfCastFaP1 = Collections.newSetFromMap(new IdentityHashMap<>());
	private final Set<CardData> bzSelfCastFaP2 = Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * Borrowed Summons that, once they resolve, must be removed from the game instead of going to
	 * the Break Zone ("remove that Summon from the game after use" — Krile 12-061L, Nanaa Mihgo 22-048H).
	 * Identity-keyed; consumed when the Summon's resolution would otherwise send it to the Break Zone.
	 */
	final Set<CardData> rfgAfterUseSummons = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * Summons free-cast from hand under a "return to hand after use" clause.
	 * After resolving, the Summon returns to its caster's hand instead of the Break Zone.
	 */
	final Set<CardData> returnToHandAfterUseSummons = Collections.newSetFromMap(new IdentityHashMap<>());

	/** Effects deferred until the start of P1's next Main Phase 1. */
	final List<Consumer<GameContext>> pendingMainPhase1Effects = new ArrayList<>();

	/** Tracks once-per-turn ability uses this turn; keyed by card instance identity, value is set of effectText strings used. */
	final IdentityHashMap<CardData, Set<String>> usedOncePerTurnAbilities = new IdentityHashMap<>();

	/** Special abilities activated this turn (either player), in activation order, for Gogo's "Mimic". Cleared each turn. */
	final List<UsedSpecialAbility> specialAbilitiesUsedThisTurn = new ArrayList<>();

	/** Forwards that cannot be selected as targets by the opponent's Summons this turn. */
	final Set<CardData> cannotBeChosenBySummons        = new HashSet<>();
	/** Forwards that cannot be selected as targets by the opponent's abilities this turn. */
	final Set<CardData> cannotBeChosenByAbilities      = new HashSet<>();
	/** Forwards that cannot be selected as targets by either player's Summons this turn. */
	final Set<CardData> cannotBeChosenBySummonsAnyone  = new HashSet<>();
	/**
	 * The ability half of the shield above — "cannot be chosen by a Summon or an ability this turn"
	 * (2-065L Balthier). Symmetric like its twin, and for the same reason: the sentence names no
	 * player, so neither may choose the card.
	 */
	final Set<CardData> cannotBeChosenByAbilitiesAnyone = new HashSet<>();
	/** Maps a card to an element: that card cannot be chosen by Summons/abilities of that element this turn. */
	final Map<CardData, String> cannotBeChosenByElement = new HashMap<>();
	/** Maps a card to an element: damage dealt to that card by Summons/abilities of that element becomes 0 this turn. */
	final Map<CardData, String> nullifyElementDamageMap = new HashMap<>();
	/** Maps a card to an element: damage dealt to that card by abilities (not Summons) of that element becomes 0 this turn. */
	final Map<CardData, String> nullifyElementDamageAbilityOnlyMap = new HashMap<>();
	/** Cards marked (by a targeted ability) to be removed from the game instead of put into the Break Zone, if that happens from the field this turn. */
	final Set<CardData> rfgInsteadOfBzThisTurn = new HashSet<>();
	/**
	 * Which cards dealt damage to which Forward this turn, for the "a Forward damaged by [X] …
	 * on the same turn" printings (Susano, Lord of the Revel 14-011H).
	 *
	 * <p>Keyed by identity for the same reason {@link #rfgInsteadOfBzThisTurn} is: the mark rides
	 * one card instance, and two copies of a card on the field are two separate damagers. Emptied
	 * at end of turn, which is what "the same turn" means, and per card in
	 * {@link #clearCombatRestrictionsFor} — a Forward that left the field and came back is a new
	 * object and carries none of the old damage.
	 */
	final Map<CardData, Set<CardData>> damagedBySourcesThisTurn = new IdentityHashMap<>();
	/** One pending "draw when the marked card leaves the field for the Break Zone" trigger. */
	record PendingBzDraw(boolean drawerIsP1, int count) {}
	/**
	 * Cards marked (by a targeted ability) to make a player draw when they are put from the field
	 * into the Break Zone this turn — Brynhildr 15-014H. Keyed by card because the mark rides the
	 * specific card instance, and holding a list keeps two marks on the same card from cancelling.
	 */
	final Map<CardData, List<PendingBzDraw>> drawOnFieldToBzThisTurn = new HashMap<>();
	/**
	 * Cards marked so that when they leave the field this turn, the cards listed against them are
	 * put into the Break Zone — 7-055R Chocobo, which lends a Forward +3000 power and follows it
	 * into the Break Zone if that Forward goes.
	 *
	 * <p>Keyed by the marked card and holding a list, like the draw marks above, so two Chocobos
	 * lending to the same Forward both pay. Distinct from that map in when it fires: this one
	 * hangs off <em>leaving the field</em> by any route, not only the Break Zone one, so returning
	 * the borrower to hand or removing it from the game collects the debt just the same.
	 */
	final Map<CardData, List<CardData>> putIntoBzWhenLeavesFieldThisTurn = new IdentityHashMap<>();
	/**
	 * Sources whose damage becomes 0 for the rest of this turn — 29-012H Neon's Runic, which picks
	 * a Summon or auto-ability off the Stack and blanks the damage it was going to deal.
	 *
	 * <p>Identity-keyed: the mark belongs to the one card whose effect was chosen, not to every
	 * copy sharing its name. A replacement rather than a reduction, so it is read before any
	 * multiplier or "cannot be reduced" flag — 0 is 0 however the damage was going to be scaled.
	 */
	final Set<CardData> damageZeroedSourcesThisTurn =
			Collections.newSetFromMap(new IdentityHashMap<>());

	/**
	 * True when {@code source}'s <em>ability</em> damage becomes 0 for the rest of this turn, from
	 * either of the two marks that say so: 29-012H Neon's Runic blanks the one Stack entry it
	 * chose, and 23-024R Shiva blanks everything the Forward it chose deals. Both are replacements
	 * rather than reductions, so every ability-damage path asks this before scaling anything.
	 *
	 * <p>Only the Shiva mark reaches combat, which has no ability source to ask about — those
	 * paths read {@link #allOutgoingDmgZeroThisTurnSet} directly.
	 */
	boolean sourceDamageIsZeroedThisTurn(CardData source) {
		return source != null
				&& (damageZeroedSourcesThisTurn.contains(source)
				 || allOutgoingDmgZeroThisTurnSet.contains(source));
	}

	/**
	 * The same question for ability damage aimed at a <em>Forward</em>, which one more mark answers:
	 * 17-027R Shiva blanks what its target deals to a Forward and nothing else, so it is read here
	 * and not on the path to a player.
	 */
	boolean sourceDamageToForwardIsZeroedThisTurn(CardData source) {
		return sourceDamageIsZeroedThisTurn(source)
				|| (source != null && abilityDmgToForwardZeroedThisTurnSet.contains(source));
	}
	/**
	 * Card names a player may not cast, against how many end-of-turn boundaries the ban still has
	 * to survive — 19-101R Leviathan bounces a Forward and bars every copy of it "until the end of
	 * the next turn". Keyed by lower-cased name, because the ban is on the printing and not on the
	 * copy that was bounced.
	 *
	 * <p>A countdown rather than a turn-scoped flag, because this is the one effect in the corpus
	 * that outlives the turn it is set in: two boundaries from the cast, so it covers the rest of
	 * this turn and the whole of the next. {@link TurnPhases} decrements it and drops what expires.
	 */
	final Map<String, Integer> p1CastNameBans = new HashMap<>();
	final Map<String, Integer> p2CastNameBans = new HashMap<>();

	/** Bars {@code cardName} from {@code isP1}'s casts for the rest of this turn and all of the next. */
	void barCastName(String cardName, boolean isP1) {
		if (cardName == null || cardName.isBlank()) return;
		(isP1 ? p1CastNameBans : p2CastNameBans)
				.merge(cardName.toLowerCase(Locale.ROOT), 2, Math::max);
		logEntry((isP1 ? "" : "[P2] ") + "cannot cast " + cardName
				+ " until the end of the next turn");
	}

	/** Whether {@code isP1} is currently barred from casting cards named {@code cardName}. */
	boolean castNameBanned(String cardName, boolean isP1) {
		return cardName != null
				&& (isP1 ? p1CastNameBans : p2CastNameBans).containsKey(cardName.toLowerCase(Locale.ROOT));
	}

	/** Ages both ban maps by one turn boundary, dropping the entries that have run out. */
	void ageCastNameBans() {
		for (Map<String, Integer> bans : List.of(p1CastNameBans, p2CastNameBans))
			bans.entrySet().removeIf(e -> {
				e.setValue(e.getValue() - 1);
				return e.getValue() <= 0;
			});
	}

	/** Maps a card to a permanent element override (Kam'lanaut ability); persists across turns. */
	final Map<CardData, String> elementOverrideMap      = new HashMap<>();
	/** Maps a card to a permanently-granted extra job (e.g. Bartz ability); persists across turns. */
	final Map<CardData, String> permanentExtraJobMap    = new HashMap<>();
	/** Forwards that have Breaktouch (battle damage) until end of turn. */
	final Set<CardData> breaktouchBattleSet       = new HashSet<>();
	/**
	 * Forwards holding "When this Forward is dealt damage, break this Forward." until end of turn
	 * — Vallaide 22-020R's grant, and the copy Hades 16-079H hands out while it is a Forward.
	 *
	 * <p>Keyed by card rather than by slot, like {@link #breaktouchBattleSet} beside it, so the
	 * grant follows the Forward through row compaction. Cleared with it at end of turn.
	 */
	final Set<CardData> breakWhenDealtDamageSet   = new HashSet<>();
	/** Cards that have escaped from the current Battle via an Escape ability — combat is skipped for their pairing. */
	final Set<CardData> escapedFromBattle         = new HashSet<>();
	/** Cards that deal no damage in the battle they are currently in (Vincent 2-078R). */
	final Set<CardData> dealsNoCombatDamageSet    = new HashSet<>();
	/** Cards to break once the battle they are in finishes, whether or not it broke them (Vincent 2-078R). */
	final Set<CardData> breakAfterCombatSet       = new HashSet<>();
	/** The Summon card currently resolving (from the stack or as an EX Burst); null otherwise. */
	CardData currentSummonSource    = null;
	/** {@code true} if {@link #currentSummonSource} belongs to P1. */
	boolean  currentSummonSourceIsP1 = false;
	/** {@code true} when the summon currently resolving was cast with its extra cost paid. */
	boolean currentSummonPaidExtraCost = false;
	/** Power of the Forward removed by the extra cost (Titan); 0 otherwise. */
	int currentExtraCostRemovedCardPower = 0;
	/** Power of the Forward revealed from hand to pay the resolving ability's reveal cost (Rinoa 18-097R); 0 otherwise. */
	int currentRevealedForwardPower = 0;
	/** BZ cards to remove from the game once the current extra-cost CP payment is confirmed. */
	private List<CardData> pendingExtraCostBzRemovals = null;
	/** Hand cards to discard as extra cost once the current CP payment is confirmed (Fenrir). */
	private List<CardData> pendingExtraCostHandDiscards = null;
	/** X value chosen for a 《X》 extra cost payment (Valefor). */
	private int pendingExtraCostXValue = 0;
	/** Extra CP to add to the payment dialog when the extra cost is 《X》 CP (Valefor). */
	private int pendingExtraCostExtraCp = 0;
	/** Fixed CP elements pending for a CP_FIXED extra cost (e.g. "Wind" + 2 generic, Samurai); null when not confirmed. */
	private List<String> pendingExtraCostCpElements = null;
	/**
	 * Crystals pending for a CRYSTAL extra cost (Bahamut SIN 28-087H); 0 when none is pending.
	 * Spent in {@code executePlay} alongside the other extra-cost payments rather than when the
	 * player confirms, so cancelling out of the CP dialog that follows leaves them unspent.
	 */
	private int pendingExtraCostCrystals = 0;
	/** Cost of the card discarded as the hand-discard extra cost; 0 if not applicable. */
	int currentExtraCostDiscardedCardCost = 0;
	/** The source card of the action ability currently resolving off the stack (null otherwise). */
	CardData currentAbilitySource       = null;
	/** {@code true} if {@link #currentAbilitySource} belongs to P1. */
	boolean currentAbilitySourceIsP1 = false;
	/**
	 * {@code true} while the ability resolving from {@link #currentAbilitySource} is a <em>special</em>
	 * ability. Read by {@code DamageResolver} for Ghis 2-126R, whose shield covers ability damage
	 * "other than special abilities" — the one printing that draws the line between the two kinds.
	 *
	 * <p>Kept in step with {@link #currentAbilitySource} at every assignment, including the nested
	 * save-and-restore sites that stand a passive's carrier up as the source: those are auto and
	 * field abilities, never specials, and leaving the flag set through one would have zeroed damage
	 * the shield does not cover.
	 */
	boolean currentAbilityIsSpecial = false;
	/** Set to {@code true} while a Summon effect is resolving so {@link #selectCharacters} applies the correct protection set. */
	boolean currentResolutionIsSummon = false;
	/**
	 * True while a Summon's effect runs from the Stack, where the "[Summon] Resolving …" line has
	 * already printed the whole effect. Read by {@code GameContext.logChooseHeader} so a choose
	 * effect does not restate it a line later.
	 *
	 * <p>Narrower than {@link #currentResolutionIsSummon}, deliberately: that flag is also set on
	 * the Summon paths that print no "Resolving" line at all, where suppressing the header would
	 * leave the choose with nothing said about it.
	 */
	boolean summonEffectTextAlreadyLogged = false;
	/** Set to {@code true} by {@code returnNamedCardToYourHand} when the Summon itself is being returned to hand. */
	boolean pendingSummonReturnToHand = false;
	/** Stack entries whose effect has been cancelled by Y'shtola or similar; checked and consumed at resolution. */
	final Set<StackEntry> cancelledStackEntries = Collections.newSetFromMap(new IdentityHashMap<>());
	/**
	 * One-shot flag set by {@code GameContextImpl.cancelChosenSelectionUnlessOpponentPays} when a
	 * "chosen by opponent's summon or ability" auto-ability's Dull-style CP tax goes unpaid; read
	 * immediately afterward by {@code GameContextImpl.selectCharacters} to empty out the selection
	 * it's about to return, so the outer Summon/ability effect sees no targets.
	 */
	boolean lastChosenSelectionCancelled = false;
	/** True while {@link #resolveTopOfStack} or EX Burst execution is running; suppresses {@link #showStackWindowIfNeeded}. */
	private boolean isResolvingStack      = false;
	/** Outstanding player choices and queued triggers; keeps {@link #isBoardSettled} false until they clear. */
	final TurnFlowGate turnFlowGate       = new TurnFlowGate();
	/** True while P1 has clicked "Respond" in the stack window and not yet cast or passed. */
	private boolean p1IsRespondingToStack = false;
	/**
	 * How many of the next cards to reach the field have their enter-the-field auto-abilities
	 * suppressed — "Its auto-ability will not trigger" (22-058H Qator Bashtar, 20-045C Botanist),
	 * "Their auto-abilities will not trigger" (1-135L Golbez).
	 *
	 * <p>A count rather than a flag because Golbez plays up to four cards under one such sentence,
	 * and each of them has to arrive silent. Decremented once per entry, so a run of suppressed
	 * arrivals cannot leak the suppression onto a card that follows them.
	 */
	int suppressAutoAbilityForNextCards = 0;

	/**
	 * Forwards currently stolen by P1 from P2, mapped to their restoration condition:
	 * {@code "permanent"}, {@code "endOfTurn"}, or {@code "whileCardOnField:Name"}.
	 */
	private final IdentityHashMap<CardData, String> stolenForwards = new IdentityHashMap<>();

	/**
	 * Necron: cards removed from the game "for as long as [watcher] is on the field", mapped to
	 * the specific watcher card instance (identity — two copies of the watcher track their own
	 * exiles). When the watcher leaves the field the exiled card re-enters its owner's field
	 * ({@link #returnTempExiledOnLeave}); entries deleted early (Necron's action ability puts
	 * the card into the Break Zone) never return. Owner side is resolved via the identity map.
	 */
	final IdentityHashMap<CardData, CardData> tempExiledCards = new IdentityHashMap<>();
	/**
	 * Cards each source removed from the game with its own effect, keyed by that source card
	 * (identity — two copies track their own). Lets a later ability refer back to them as "cards
	 * removed by the previous effect" (Libroarian 8-084R). Entries are dropped once the last card
	 * has been retrieved.
	 */
	final IdentityHashMap<CardData, List<CardData>> cardsRemovedBySource = new IdentityHashMap<>();
	/** Distinct element types used to pay the most recent card's CP cost; checked by castPaymentMinElements conditions. */
	int lastCastPaymentDistinctElements = 0;
	/** Specific element types used to pay the most recent card's CP cost; checked by castPaymentElement conditions. */
	final Set<String> lastCastPaymentElements = new HashSet<>();
	/**
	 * The card {@link #lastCastPaymentElements} was recorded for — identity, so two copies of one
	 * printing are told apart. Read by {@code GameContext.castPaymentDistinctElementsFor}, which a
	 * gate asking what its own arrival cost has to go through: nothing clears the payment record
	 * when a card reaches the field without being paid for, so without the owner on record such a
	 * card inherits the previous cast's payment.
	 */
	CardData lastCastPaymentCard = null;
	/** Actual source-card element types used during payment (not mapped to the played card's element). */
	final Set<String> lastCastActualPaymentElements = new HashSet<>();
	/** True if the most recently cast card was paid entirely by dulling Backups (no hand discards). */
	boolean lastCastWasPaidByBackupsOnly = false;
	/**
	 * The Backups actually dulled to produce CP for the most recent cast, in payment order. Read by
	 * {@code GameContext.castCpOnlyFromBackups} for the gates that ask <em>which</em> Backups paid
	 * (7-092C Thancred's "only produced by Category XIV Backups"), which
	 * {@link #lastCastWasPaidByBackupsOnly} alone cannot answer.
	 */
	final List<CardData> lastCastPaymentBackups = new ArrayList<>();
	/**
	 * The card whose departure fired the "put into the Break Zone" trigger now resolving, or
	 * {@code null} outside one — Lunafreya 8-132L's "play the Forward placed in the Break Zone onto
	 * the field dull" is the effect that reads it.
	 *
	 * <p>Set and restored around each triggered resolution by {@code AutoAbilityTriggers}, so a
	 * break happening inside another break's effect cannot leave the wrong card standing here.
	 */
	CardData triggeringBrokenCard = null;
	/**
	 * The size of the single damage instance whose "is dealt damage" triggers are resolving, or
	 * {@code 0} outside one. Shantotto 4-083L's "deal the same amount of damage to all the Forwards
	 * other than Shantotto" is the effect that reads it: the amount is not in the card's text, only
	 * in the event, so it is held here the way {@link #triggeringBrokenCard} holds its card.
	 *
	 * <p>Set and restored around each dispatch by {@code AutoAbilityTriggers}, so a damage dealt
	 * while one is resolving cannot leave its own amount behind for the outer one to read.
	 */
	int lastDealtDamageAmount = 0;
	/**
	 * The card whose arrival on the field is firing the watcher currently resolving, or {@code null}
	 * outside one. Held here for the reason {@link #triggeringBrokenCard} is: the effect reads it
	 * through a {@code Consumer} that has no room for a second card, and Noctis 18-139S needs both
	 * the arriving Forward <em>and</em> the one its ability chooses.
	 *
	 * <p>Distinct from the preloaded-target route the pronoun effects use. That route hands the
	 * entering card over <em>as</em> the target, which is right for "dull it and Freeze it" and
	 * wrong here — the target of Noctis's sentence is the Forward the player picks, and the
	 * arriving one is only the source of the damage.
	 *
	 * <p>Set and restored around each dispatch, so a nested arrival cannot leave its own card
	 * behind for the outer one to read.
	 */
	CardData triggeringEnteredCard = null;
	/** True while a card is being placed as a direct result of being cast from hand; gates castOnly field abilities. */
	boolean lastCardWasCast = false;
	/** True while a card is entering the field via Warp resolution; gates warpOnly field abilities. */
	boolean lastCardWarpedIn = false;

	/** Set when "Take 1 more turn; lose at the end of that turn" fires. */
	boolean p1ExtraTurnThenLose = false;

	public static void main(String[] args) {
		AppLogger.init();
		UiScale.init();
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
		cardPickerDialog = new shufflingway.dialog.CardPickerDialog(frame, this::showZoomAt, this::hideZoom);
		frame.getContentPane().setBackground(Color.LIGHT_GRAY);
		frame.setBounds(0, 0, UiScale.windowWidth(), UiScale.windowHeight());
		frame.setLocationRelativeTo(null);
		frame.setResizable(false);
		frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
		frame.addWindowListener(new java.awt.event.WindowAdapter() {
			@Override public void windowClosing(java.awt.event.WindowEvent e) {
				AppSettings.setSidePanelWidth(sessionResolution, sidePanelW);
				AppSettings.save();
			}
		});
		frame.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		frame.getContentPane().setLayout(new BorderLayout());

		// --- Menu Bar ---
		JMenuBar menuBar = new JMenuBar();
		frame.setJMenuBar(menuBar);
		menuBar.add(new FileMenu(frame, (p1Id, p2Id) -> startGame(p1Id, p2Id),
				() -> applySidePanelSide(AppSettings.getSidePanelSide()),
				this::applyBoardColor));
		multiplayerMenu = new MultiplayerMenu(frame,
				setup -> {
					SwingUtilities.invokeLater(() -> {
						chatInput.setEnabled(true);
						chatSendBtn.setEnabled(true);
						startMultiplayerGame(setup);
					});
				},
				reason -> SwingUtilities.invokeLater(() -> {
					chatInput.setEnabled(false);
					chatSendBtn.setEnabled(false);
					onOpponentDisconnected(reason);
				}),
				action -> {
					if (action.type() == ActionType.CHAT) {
						String msg = action.payload().optString("msg", "");
						if (!msg.isEmpty()) logEntry("[Opponent] " + msg);
					} else if (action.type() == ActionType.STATE_CHECKSUM) {
						onRemoteChecksum(action.payload());
					} else if (opponent instanceof RemoteOpponent remote) {
						// Everything else is the opponent playing; they own its interpretation.
						if (!remote.onActionReceived(action))
							logEntry("[Net] Ignored unsupported action: " + action.type());
					}
				});
		menuBar.add(multiplayerMenu);
		menuBar.add(new HelpMenu(frame));

		if (AppSettings.isDebugEnabled()) {
			DebugUtility debug = new DebugUtility(this);
			menuBar.add(new DebugMenu(debug::spawnOnField, debug::addToHand, debug::addToBreakZone,
					debug::addRemoveCounters, debug::activateDullCards, debug::setDamageAndCrystals));
		}

		Dimension cardSize = new Dimension(CARD_W, CARD_H);

		// --- P2 Zones (top of screen) ---
		p2RemoveLabel = new GrayscaleLabel("");

		int CORNER_BAR_H = UiScale.scale(28);
		int LIMIT_W      = (CARD_W * 2) / 3;   // 2/3 of deck width
		int REMOVE_W     = CARD_W - LIMIT_W;    // 1/3 of deck width (RFP button)
		int CRYSTAL_W    = CARD_W - (CARD_W * 3) / 4; // 1/4 of deck width

		p2LimitButton = new JButton("LIMIT");
		p2LimitButton.setToolTipText("Player 2 LB Deck");
		p2LimitButton.setFont(FontLoader.loadPixelFont(10));
		p2LimitButton.setMargin(new Insets(0, 0, 0, 0));
		p2LimitButton.setBackground(new Color(212, 175, 55));
		p2LimitButton.setForeground(Color.BLACK);
		p2LimitButton.setOpaque(true);
		p2LimitButton.setBorderPainted(false);
		p2LimitButton.setFocusPainted(false);
		p2LimitButton.setPreferredSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		p2LimitButton.setMinimumSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		p2LimitButton.setMaximumSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		p2LimitButton.addActionListener(e -> showP2LbViewerDialog());

		p2BreakLabel = new JLabel("BREAK");
		p2BreakLabel.setToolTipText("Player 2 Break Zone");
		p2BreakLabel.setHorizontalAlignment(SwingConstants.CENTER);
		p2BreakLabel.setFont(FontLoader.loadPixelFont(18));
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
		p2DeckLabel.setFont(FontLoader.loadPixelFont(18));
		p2DeckLabel.setToolTipText("Player 2 Deck");
		p2DeckLabel.setHorizontalAlignment(SwingConstants.CENTER);
		p2DeckLabel.setBorder(new SoftBevelBorder(BevelBorder.LOWERED, null, null, null, null));
		p2DeckLabel.setBackground(Color.DARK_GRAY);
		p2DeckLabel.setForeground(Color.WHITE);
		p2DeckLabel.setOpaque(true);

		p2RemoveButton = new JButton("RFP");
		p2RemoveButton.setToolTipText("Player 2 Removed From Play");
		p2RemoveButton.setFont(FontLoader.loadPixelFont(10));
		p2RemoveButton.setMargin(new Insets(0, 0, 0, 0));
		p2RemoveButton.setBackground(new Color(30, 30, 30));
		p2RemoveButton.setForeground(Color.LIGHT_GRAY);
		p2RemoveButton.setOpaque(true);
		p2RemoveButton.setBorderPainted(false);
		p2RemoveButton.setFocusPainted(false);
		p2RemoveButton.setEnabled(false);
		p2RemoveButton.setPreferredSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p2RemoveButton.setMinimumSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p2RemoveButton.setMaximumSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p2RemoveButton.addActionListener(e -> showRemovedFromPlayDialog("P2"));

		JPanel p2BottomBar = new JPanel(new GridBagLayout());
		p2BottomBar.setPreferredSize(new Dimension(CARD_W, CORNER_BAR_H));
		p2BottomBar.setMinimumSize(new Dimension(CARD_W, CORNER_BAR_H));
		{
			GridBagConstraints bbc = new GridBagConstraints();
			bbc.fill = GridBagConstraints.BOTH; bbc.weighty = 1.0; bbc.gridy = 0;
			bbc.gridx = 0; bbc.weightx = 2.0 / 3.0; p2BottomBar.add(p2LimitButton, bbc);
			bbc.gridx = 1; bbc.weightx = 1.0 / 3.0; p2BottomBar.add(p2RemoveButton, bbc);
		}

		p2DeckLabel.setPreferredSize(cardSize);
		p2DeckLabel.setMinimumSize(cardSize);

		p2CrystalDisplay = new CrystalDisplay(0);
		p2CrystalDisplay.setPreferredSize(new Dimension(CRYSTAL_W, CrystalDisplay.CRYSTAL_H));
		p2CrystalDisplay.setMinimumSize(new Dimension(CRYSTAL_W, CrystalDisplay.CRYSTAL_H));
		p2CrystalDisplay.setMaximumSize(new Dimension(CRYSTAL_W, CrystalDisplay.CRYSTAL_H));

		JPanel p2CornerPanel = new JPanel(new BorderLayout(0, 0));
		p2CornerPanel.add(p2BreakLabel, BorderLayout.NORTH);
		p2CornerPanel.add(p2DeckLabel,  BorderLayout.CENTER);
		p2CornerPanel.add(p2BottomBar,  BorderLayout.SOUTH);

		// The crystal badge is pinned WEST rather than dropped straight into the wrapper's SOUTH:
		// CrystalDisplay centres a fixed-size hexagon within getWidth(), so stretching it across the
		// full column would slide the hexagon to the column's centre. P1 mirrors this with EAST.
		JPanel p2CrystalRow = new JPanel(new BorderLayout(0, 0));
		p2CrystalRow.setOpaque(false);
		p2CrystalRow.add(p2CrystalDisplay, BorderLayout.WEST);

		JPanel p2CornerWrapper = new JPanel(new BorderLayout(0, 2));
		p2CornerWrapper.setOpaque(false);
		p2CornerWrapper.add(p2CornerPanel,  BorderLayout.CENTER);
		p2CornerWrapper.add(p2CrystalRow,   BorderLayout.SOUTH);

		JPanel p2DamagePanel = buildDamageZonePanel("P2");

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

		p2HandFan = new HandFanPanel(false, this::loadCardbackImage);

		// The fan claims the top of the band, pushing the backups down away from the screen edge.
		// Its height is paid for out of the forward zone's spare seating — see buildForwardZonePanel.
		JPanel p2TopRow = new JPanel(new BorderLayout());
		p2TopRow.add(p2HandFan,       BorderLayout.NORTH);
		p2TopRow.add(p2BackupWrapper, BorderLayout.CENTER);

		JPanel p2MainArea = new JPanel(new BorderLayout(0, 4));
		// Transparent so p2ZonesPanel's board fade shows through the middle column — the Forward
		// zone's scroll pane and inner panels are already non-opaque, so nothing else covers it.
		p2MainArea.setOpaque(false);
		p2MainArea.add(p2TopRow,      BorderLayout.NORTH);
		p2MainArea.add(p2ForwardZone, BorderLayout.SOUTH);

		// The board gradient starts inside P2's own zone rather than in the thin strip below it.
		// BOARD_FADE_H is bounded by where the side columns turn transparent — the crystal row for
		// the corner, the bottom of the damage stack — or their solid backgrounds would step across
		// the fade. It comfortably clears the Forward row's scrollbar, which is the point.
		p2ZonesPanel = new BoardEdgeFadePanel(true, BOARD_FADE_H);
		p2ZonesPanel.setLayout(new GridBagLayout());
		{
			GridBagConstraints z = new GridBagConstraints();
			z.gridy = 0; z.fill = GridBagConstraints.NONE; z.anchor = GridBagConstraints.NORTH; z.weightx = 0;
			z.gridx = 0; p2ZonesPanel.add(p2CornerWrapper, z);
			z.gridx = 2; p2ZonesPanel.add(p2DamagePanel, z);
			z.gridx = 1; z.fill = GridBagConstraints.BOTH; z.weightx = 1.0; z.weighty = 1.0;
			p2ZonesPanel.add(p2MainArea, z);
		}

		// --- P1 Zones (bottom of screen) ---
		JPanel p1DamagePanel = buildDamageZonePanel("P1");

		// P1 deck label — interactive
		p1DeckLabel = new JLabel("DECK");
		p1DeckLabel.setFont(FontLoader.loadPixelFont(18));
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
		p1BreakLabel.setFont(FontLoader.loadPixelFont(18));
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

		// P1 limit button — gold, 2/3 of card width
		p1LimitButton = new JButton("LIMIT");
		p1LimitButton.setToolTipText("Player 1 LB Deck");
		p1LimitButton.setFont(FontLoader.loadPixelFont(10));
		p1LimitButton.setMargin(new Insets(0, 0, 0, 0));
		p1LimitButton.setBackground(new Color(212, 175, 55));
		p1LimitButton.setForeground(Color.BLACK);
		p1LimitButton.setOpaque(true);
		p1LimitButton.setBorderPainted(false);
		p1LimitButton.setFocusPainted(false);
		p1LimitButton.setPreferredSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		p1LimitButton.setMinimumSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		p1LimitButton.setMaximumSize(new Dimension(LIMIT_W, CORNER_BAR_H));
		p1LimitButton.addActionListener(e -> {
			GameState.GamePhase phase = gameState.getCurrentPhase();
			boolean isMainPhase = phase == GameState.GamePhase.MAIN_1
					|| phase == GameState.GamePhase.MAIN_2;
			if (!gameState.getP1LbDeck().isEmpty() && isMainPhase && !gameState.isP1GameOver()) showLbDialog();
		});

		p1RemoveLabel = new GrayscaleLabel("");

		p1RemoveButton = new JButton("RFP");
		p1RemoveButton.setToolTipText("Player 1 Removed From Play");
		p1RemoveButton.setFont(FontLoader.loadPixelFont(10));
		p1RemoveButton.setMargin(new Insets(0, 0, 0, 0));
		p1RemoveButton.setBackground(new Color(30, 30, 30));
		p1RemoveButton.setForeground(Color.LIGHT_GRAY);
		p1RemoveButton.setOpaque(true);
		p1RemoveButton.setBorderPainted(false);
		p1RemoveButton.setFocusPainted(false);
		p1RemoveButton.setEnabled(false);
		p1RemoveButton.setPreferredSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p1RemoveButton.setMinimumSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p1RemoveButton.setMaximumSize(new Dimension(REMOVE_W, CORNER_BAR_H));
		p1RemoveButton.addActionListener(e -> showRemovedFromPlayDialog("P1"));

		p1CrystalDisplay = new CrystalDisplay(0);
		p1CrystalDisplay.setPreferredSize(new Dimension(CRYSTAL_W, CrystalDisplay.CRYSTAL_H));
		p1CrystalDisplay.setMinimumSize(new Dimension(CRYSTAL_W, CrystalDisplay.CRYSTAL_H));
		p1CrystalDisplay.setMaximumSize(new Dimension(CRYSTAL_W, CrystalDisplay.CRYSTAL_H));

		// Crystal sits above the full bar, pinned to the right to align with the RFP button
		JPanel p1CrystalRow = new JPanel(new BorderLayout(0, 0));
		p1CrystalRow.setOpaque(false);
		p1CrystalRow.add(p1CrystalDisplay, BorderLayout.EAST);

		// Restore the limit button's original height constraint
		p1LimitButton.setMaximumSize(new Dimension(LIMIT_W, CORNER_BAR_H));

		// Restore the original two-button top bar
		JPanel p1TopBar = new JPanel(new GridBagLayout());
		p1TopBar.setPreferredSize(new Dimension(CARD_W, CORNER_BAR_H));
		p1TopBar.setMinimumSize(new Dimension(CARD_W, CORNER_BAR_H));
		{
			GridBagConstraints tbc = new GridBagConstraints();
			tbc.fill = GridBagConstraints.BOTH; tbc.weighty = 1.0; tbc.gridy = 0;
			tbc.gridx = 0; tbc.weightx = 2.0 / 3.0; p1TopBar.add(p1LimitButton, tbc);
			tbc.gridx = 1; tbc.weightx = 1.0 / 3.0; p1TopBar.add(p1RemoveButton, tbc);
		}

		// Wrapper: crystal row above, top bar below
		JPanel p1NorthWrapper = new JPanel(new BorderLayout(0, 0));
		p1NorthWrapper.setOpaque(false);
		p1NorthWrapper.add(p1CrystalRow, BorderLayout.NORTH);
		p1NorthWrapper.add(p1TopBar,     BorderLayout.SOUTH);

		p1DeckLabel.setPreferredSize(cardSize);
		p1DeckLabel.setMinimumSize(cardSize);

		JPanel p1CornerPanel = new JPanel(new BorderLayout(0, 0));
		// Transparent so the board fade reaches behind the crystal row. P2's crystal row sits
		// outside its corner panel and is already clear of it; P1's is nested inside this one, so
		// without this the corner would cut a step across the fade. The deck and Break Zone labels
		// below are opaque and cover the rest, so nothing else changes.
		p1CornerPanel.setOpaque(false);
		p1CornerPanel.add(p1NorthWrapper, BorderLayout.NORTH);
		p1CornerPanel.add(p1DeckLabel,    BorderLayout.CENTER);
		p1CornerPanel.add(p1BreakLabel,   BorderLayout.SOUTH);

		JPanel p1BackupSlots = buildBackupZonePanel(p1BackupLabels);
		for (int i = 0; i < p1BackupLabels.length; i++) {
			final int backupIdx = i;
			p1BackupLabels[i].addMouseListener(new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					if (p1BackupLabels[backupIdx].getIcon() == null) return;
					if (SwingUtilities.isLeftMouseButton(e)
							&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK
							&& (isBackupSelectableAsForward(backupIdx) || isBackupBlockSelectable(backupIdx))) {
						handleP1BackupLeftClick(backupIdx);
					} else {
						showBackupContextMenu(backupIdx, p1BackupLabels[backupIdx], e);
					}
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
		// The ► this used to spell out as &#9658; has no glyph on macOS, so the pointer is drawn
		// rather than typed. Icon below the text reproduces the old <center>Next<br>►</center>.
		nextPhaseButton = new JButton("Next");
		nextPhaseButton.setFont(FontLoader.loadPixelFont(14));
		nextPhaseButton.setIcon(new TriangleIcon(
				TriangleIcon.Direction.RIGHT, UiScale.scale(9), UiScale.scale(11)));
		nextPhaseButton.setHorizontalTextPosition(SwingConstants.CENTER);
		nextPhaseButton.setVerticalTextPosition(SwingConstants.TOP);
		nextPhaseButton.setIconTextGap(UiScale.scale(3));
		nextPhaseButton.setEnabled(false);
		nextPhaseButton.setFocusPainted(false);
		nextPhaseButton.addActionListener(e -> onNextPhase());

		// Pulsing glow border — runs continuously, only paints when enabled
		glowTimer = new Timer(40, e -> {
			if (nextPhaseButton == null || !nextPhaseButton.isEnabled()) return;
			glowAngle[0] += 0.09f;
			float t = (float)(0.5 + 0.5 * Math.sin(glowAngle[0]));
			int r = (int)(180 + t * 75);   // 180-255
			int g = (int)(110 + t * 80);   // 110-190
			nextPhaseButton.setBorder(BorderFactory.createLineBorder(
					new Color(Math.min(r, 255), Math.min(g, 255), 20), 3, true));
		});
		glowTimer.start();

		// P1's hand, in the room reserved for it at the bottom edge — the mirror of P2's fan at the
		// top, but face up and clickable, so it is a different component. See PlayerHandFanPanel.
		p1HandFan = new PlayerHandFanPanel(
				this::handCardState, this::onHandCardHover, this::onHandCardPressed);

		playableCardsButton = buildPlayableCardsButton();

		// The button sits beside the fan rather than inside it: it is about cards that are *not* in
		// hand, and the fan paints a hand. Left end, clear of a centred fan's widest spread, and
		// hidden outright when there is nothing to play.
		JPanel p1BottomRow = new JPanel(
				new HandFanOverlapLayout(p1BackupWrapper, p1HandFan, playableCardsButton));
		p1BottomRow.setOpaque(false);
		p1BottomRow.add(p1BackupWrapper);
		p1BottomRow.add(playableCardsButton);
		p1BottomRow.add(p1HandFan);
		// Painted first among siblings means painted last on screen: a risen card crosses the backup
		// row above it, and the button stays clear of both.
		p1BottomRow.setComponentZOrder(p1HandFan, 0);
		p1BottomRow.setComponentZOrder(playableCardsButton, 1);

		JPanel p1MainArea = new JPanel(new BorderLayout(0, 4));
		// Transparent so p1ZonesPanel's board fade shows through the middle column, as on P2.
		p1MainArea.setOpaque(false);
		p1MainArea.add(p1ForwardZone,  BorderLayout.NORTH);
		p1MainArea.add(p1BottomRow,    BorderLayout.SOUTH);

		// Damage panel on the left, hand slot flush against its right edge at the bottom
		JPanel p1LeftGroup = new JPanel(new GridBagLayout());
		GridBagConstraints lgbc = new GridBagConstraints();
		lgbc.gridx = 0; lgbc.gridy = 0;
		lgbc.fill = GridBagConstraints.BOTH;
		lgbc.weighty = 1.0;
		p1LeftGroup.add(p1DamagePanel, lgbc);

		// Mirror of P2: the board gradient begins inside P1's own zone, fading up from the element
		// colour to the neutral tone at the centre-facing (top) edge.
		p1ZonesPanel = new BoardEdgeFadePanel(false, BOARD_FADE_H);
		p1ZonesPanel.setLayout(new GridBagLayout());
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
		p2Board = new GradientPanel(true);
		p1Board = new GradientPanel(false);

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


		// Apply the saved field colors at startup. The dropdowns that change these live in
		// Preferences (see applyBoardColor, wired through FileMenu → PreferencesDialog).
		applyBoardColor(false, AppSettings.getP2BoardColor());
		applyBoardColor(true,  AppSettings.getP1BoardColor());

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
		attackButton.setFont(FontLoader.loadPixelFont(12));
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
			} else if (p1BackupAttackIdx >= 0) {
				int bIdx = p1BackupAttackIdx;
				p1BackupAttackIdx = -1;
				refreshAttackButton();
				executeP1BackupAttack(bIdx);
			}
		});

		// Skip button — ends the attack phase without declaring another attacker
		skipAttackButton = new JButton("Skip");
		skipAttackButton.setFont(FontLoader.loadPixelFont(12));
		skipAttackButton.setEnabled(false);
		skipAttackButton.setFocusPainted(false);
		skipAttackButton.addActionListener(e -> {
			if (attackSubStep == 1
					&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK
					&& gameState.getCurrentPlayer() == GameState.Player.P1
					&& !p1AttackDeclarationInFlight()) {
				onNextPhase();
			}
		});

		// Next-phase button, centered below the preview
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
			conn.send(GameAction.of(ActionType.CHAT, new JSONObject().put("msg", text)));
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

		// No hand zone here any more: P1's hand is on the board, fanned along the bottom edge. The
		// strip this used to occupy goes to the log, which is what the space was always competing with.
		sidePanel = new JPanel(new BorderLayout());
		sidePanel.setPreferredSize(new Dimension(sidePanelW, 0));
		sidePanel.add(sideNorth,    BorderLayout.NORTH);
		sidePanel.add(logWithChat,  BorderLayout.CENTER);

		// Draggable divider between game board and side panel.
		// When the UI is scaled (smaller screen), resizing is disabled because
		// growing the preview pushes the game log and hand zone off-screen.
		resizeHandle = new JPanel();
		resizeHandle.setPreferredSize(new Dimension(RESIZE_HANDLE_W, 0));
		resizeHandle.setBackground(Color.LIGHT_GRAY);
		if (UiScale.factor >= 1.0) {
			MouseAdapter sideResizer = new MouseAdapter() {
				private int pressScreenX;
				private int pressW;
				@Override public void mousePressed(MouseEvent e) {
					pressScreenX = e.getXOnScreen();
					pressW = sidePanel.getWidth();
				}
				@Override public void mouseDragged(MouseEvent e) {
					// No guard on a loaded card image: the clamp bounds are seeded at construction,
					// and setSidePanelWidth already falls back to the stored card's aspect ratio
					// for the preview height while nativeImgH is still unmeasured.
					int dx = e.getXOnScreen() - pressScreenX;
					boolean right = "right".equals(AppSettings.getSidePanelSide());
					int newW = right ? pressW - dx : pressW + dx;
					setSidePanelWidth(clampSidePanelW(newW));
				}
			};
			resizeHandle.addMouseListener(sideResizer);
			resizeHandle.addMouseMotionListener(sideResizer);
		}

		// --- Main game area (wraps both player zones + board so the side panel
		//     spans the full frame height rather than just the center strip) ---
		JPanel mainArea = new JPanel(new BorderLayout());
		mainArea.add(p2ZonesPanel, BorderLayout.NORTH);
		mainArea.add(southPanel,   BorderLayout.SOUTH);
		mainArea.add(gameBoard,    BorderLayout.CENTER);

		// --- Assemble ---
		frame.getContentPane().add(mainArea, BorderLayout.CENTER);
		applySidePanelSide(AppSettings.getSidePanelSide());
		// Open at the width the player left it at, rather than waiting for the first card preview
		// to apply it. Clamped against the estimated bounds above; sizePreviewPanel re-clamps once
		// the real ones are known.
		setSidePanelWidth(clampSidePanelW(
				AppSettings.getSidePanelWidth(sessionResolution, defaultSidePanelW())));
		// Open at the width the player left it at, rather than waiting for the first card preview
		// to apply it. Clamped against the estimated bounds above; sizePreviewPanel re-clamps once
		// the real ones are known.

		// When the chosen resolution is taller than the scaled 16:9 board, split the leftover
		// height into equal letterbox bars in the free NORTH and SOUTH regions, centring the play
		// area. BorderLayout keeps the side panel (EAST/WEST) and board (CENTER) at their designed
		// size; only these bars take the surplus.
		int letterboxVertical = UiScale.letterboxVertical();
		if (letterboxVertical > 0) {
			int topBar    = letterboxVertical / 2;
			int bottomBar = letterboxVertical - topBar;
			frame.getContentPane().add(makeLetterboxBar(topBar),    BorderLayout.NORTH);
			frame.getContentPane().add(makeLetterboxBar(bottomBar), BorderLayout.SOUTH);
		}

		cardSlideAnimator = CardSlideAnimator.install(frame);
		breakAnimator     = CardBreakAnimator.install(frame);
		rfpAnimator       = CardRfpAnimator.install(frame);
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
				UiScale.factor < 1.0 ? Cursor.DEFAULT_CURSOR
				                     : (right ? Cursor.W_RESIZE_CURSOR : Cursor.E_RESIZE_CURSOR)));
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

	/** Builds a solid black letterbox bar of the given height for centering the play area. */
	private static JPanel makeLetterboxBar(int height) {
		JPanel bar = new JPanel();
		bar.setBackground(Color.BLACK);
		bar.setPreferredSize(new Dimension(0, height));
		return bar;
	}

	// -------------------------------------------------------------------------
	// Game startup
	// -------------------------------------------------------------------------

	private void startGame(int deckId, int p2DeckId) {
		matchSetup = null;              // a local game against the AI
		resetForNewGame();
		applyTurnPillNames();
		loadCpuGameDecks(deckId, p2DeckId);
	}

	/**
	 * Starts a networked game from the parameters the lobby agreed on.
	 *
	 * <p>Both clients run this against the same {@link MatchSetup} and each seats itself as P1,
	 * so the two boards come out as mirror images. Deck order is identical on both sides because
	 * the shuffles are driven by seeded streams keyed to the deck's owner — see
	 * {@link MatchSetup#hostDeckRandom()} — and {@link MatchChecksum} verifies that immediately.
	 */
	void startMultiplayerGame(MatchSetup setup) {
		matchSetup         = setup;
		localDealChecksum  = null;
		remoteDealChecksum = null;
		localHandKept      = false;
		remoteHandKept     = false;
		desyncReported     = false;
		resetForNewGame();
		applyTurnPillNames();
		loadMultiplayerDecks(setup);
	}

	/**
	 * Labels the turn pill with the two usernames for a networked match, and with nothing at all
	 * for a local game against the AI — the pill then falls back to "YOUR TURN" / "OPPONENT'S
	 * TURN". Either name may be blank when that player set no username; the pill handles each
	 * side independently, so a named player still gets named against an anonymous one.
	 */
	private void applyTurnPillNames() {
		if (phaseTracker == null) return;
		if (matchSetup == null) phaseTracker.setPlayerNames(null, null);
		else phaseTracker.setPlayerNames(AppSettings.getUsername(), matchSetup.remoteUsername());
	}

	/** Tears down any in-progress game and clears every piece of per-game state. */
	void resetForNewGame() {
		// --- Tear down any in-progress game before resetting state ---
		// Stop timers first so they cannot fire callbacks after state is cleared.
		stackWindowGeneration++;
		if (stackCountdownTimer  != null) { stackCountdownTimer.stop();    stackCountdownTimer  = null; }
		if (p2AutoPassTimer      != null) { p2AutoPassTimer.stop();         p2AutoPassTimer      = null; }
		// Dispose any floating windows.
		if (summonStackWindow    != null) { summonStackWindow.dispose();    summonStackWindow    = null; }
		if (openingHandPopup     != null) { openingHandPopup.dispose();     openingHandPopup     = null; }
		// Reset stack-resolution flags so new abilities can reach the stack window.
		isResolvingStack         = false;
		turnFlowGate.reset();
		currentResolutionIsSummon = false;
		pendingSummonReturnToHand = false;
		currentSummonSource      = null;
		currentAbilitySource     = null;
		currentAbilityIsSpecial  = false;
		lastChosenSelectionCancelled = false;
		suppressAutoAbilityForNextCards = 0;
		// Per-game callback/priority state.
		p1PriorityInP2MainOnDone = null;
		p1CombatPriorityOnPass   = null;
		// Per-game / per-turn collections that are not covered by gameState.reset() or clearUIZones().
		cancelledStackEntries.clear();
		usedOncePerTurnAbilities.clear();
		specialAbilitiesUsedThisTurn.clear();
		elementOverrideMap.clear();
		permanentExtraJobMap.clear();
		stolenForwards.clear();
		tempExiledCards.clear();
		cardsRemovedBySource.clear();
		cannotBeChosenBySummons.clear();
		cannotBeChosenByAbilities.clear();
		cannotBeChosenBySummonsAnyone.clear();
		cannotBeChosenByAbilitiesAnyone.clear();
		cannotBeChosenByElement.clear();
		p1TempAttackTriggers.clear();
		p2TempAttackTriggers.clear();
		p1TempBlockTriggers.clear();
		p2TempBlockTriggers.clear();
		p1TempIsBlockedTriggers.clear();
		p2TempIsBlockedTriggers.clear();
		p1CannotBlock.clear();
		p2CannotBlock.clear();
		cannotUseActionAbilitiesThisTurn.clear();
		// Per-turn tracking flags.
		p1Turn.receivedDamageThisTurn = false;
		p2Turn.receivedDamageThisTurn = false;
		if (p1Turn.nextDamageZero && p1ShieldIcon != null) p1ShieldIcon.triggerFade();
		if (p2Turn.nextDamageZero && p2ShieldIcon != null) p2ShieldIcon.triggerFade();
		p1Turn.nextDamageZero = false;
		p2Turn.nextDamageZero = false;
		p1Turn.nextDamageZeroRedirectName = null; p2Turn.nextDamageZeroRedirectName = null;
		p1Turn.nextDamageZeroRedirectDmg = 0;     p2Turn.nextDamageZeroRedirectDmg = 0;
		p1Turn.forwardPutToBZThisTurn = false;
		p2Turn.forwardPutToBZThisTurn = false;
		p1Turn.putToBzFromFieldThisTurn.clear();
		p2Turn.putToBzFromFieldThisTurn.clear();
		p1Turn.castRemovedUsedThisTurn.clear();
		p2Turn.castRemovedUsedThisTurn.clear();
		p1Turn.partyAnyElementThisTurn = false;
		p2Turn.partyAnyElementThisTurn = false;
		lastCardWasCast   = false;
		lastCardWarpedIn  = false;
		triggeringBrokenCard = null;
		triggeringBrokenCard = null;
		triggeringBrokenCard = null;

		gameState.reset();
		endOfTurnEffects.clear();
		scheduledForP1EndTurn.clear();
		scheduledForP2EndTurn.clear();
		pendingMainPhase1Effects.clear();
		activeCostReductions.clear();
		lostAbilitiesCards.clear();
		abilitiesStrippedWhileWardenOnField.clear();
		wardenHeldGrants.clear();
		exBurstSuppressingSources.clear();
		playerDamageSource = null;
		basePowerOverrides.clear();
		permanentPowerBoost.clear();
		permanentTraits.clear();
		bzPlayableP1.clear();
		bzPlayableP2.clear();
		bzForwardFaP1.clear();
		bzForwardFaP2.clear();
		bzSelfCastFaP1.clear();
		bzSelfCastFaP2.clear();
		rfgAfterUseSummons.clear();
		returnToHandAfterUseSummons.clear();
		if (opponent != null) opponent.cancel();
		opponent = createOpponent();
		clearUIZones();
		clearPendingCombatState();
		if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
		if (gameLog != null) gameLog.setText("");
		logEntry("Game Start");
		refreshP1HandLabel();
	}

	/**
	 * Clears the combat state a new game would otherwise inherit from the game it replaced.
	 *
	 * <p>{@link #clearUIZones()} empties the board, but the block-declaration step does not live on
	 * the board: while P2 attacks it is {@link #pendingP2Attacker} or {@link #pendingP2PartyIndices}
	 * that makes {@code p1InBlockDeclaration()} true, and that is what holds the Attack button at
	 * "Take Damage" and enabled. Carried across a New Game the button stayed live over an empty
	 * board, and pressing it ran the interrupted attack's handler against Forward indices whose row
	 * had just been cleared — {@code p2ForwardCards.get(0)} on an empty list.
	 *
	 * <p>{@code pendingP2BlockDone} is a continuation into the previous game's combat loop, so it is
	 * dropped rather than run: the battle it would finish no longer has a board.
	 */
	private void clearPendingCombatState() {
		setAttackSubStep(-1);
		pendingP2Attacker          = null;
		pendingP2AttackerIdx       = -1;
		pendingP2AttackerIsMonster = false;
		pendingP2AttackerIsBackup  = false;
		pendingP2AttackerPower     = 0;
		pendingP2PartyIndices      = null;
		pendingP2PartyCombined     = 0;
		pendingP2BlockDone         = null;
		p1BlockerSelection         = -1;
		p1BlockerMonsterIdx        = -1;
		p1BlockerBackupIdx         = -1;
		p1BlockingIdx              = -1;
		p1BlockedByAttacker        = null;
		p2BlockingIdx              = -1;
		p2BlockedByAttacker        = null;
		refreshAttackButton();
	}

	/** Loads both decks from the local deck database, for a game against the AI. */
	private void loadCpuGameDecks(int deckId, int p2DeckId) {
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
					CardData cd = buildCardData(card);
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
					CardData cd = buildCardData(card);
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

	/**
	 * Loads a networked game's decks: the local player's from the local deck database, the
	 * opponent's by resolving the serials the lobby received. Both sides run this and both
	 * resolve the same two decks; the shuffle streams then put them in the same order.
	 */
	private void loadMultiplayerDecks(MatchSetup setup) {
		new SwingWorker<Void, Void>() {
			List<DeckCardDetail> localCards;
			List<DeckCardDetail> remoteCards;
			String               unknownSerial;

			@Override
			protected Void doInBackground() throws Exception {
				try (DeckDatabase db = new DeckDatabase()) {
					localCards  = db.getDeckCardsDetailed(setup.localDeckId());
					remoteCards = new ArrayList<>(setup.remoteSerials().size());
					for (String serial : setup.remoteSerials()) {
						DeckCardDetail detail = db.getCardDetailBySerial(serial);
						// The lobby's card-database checksum should make this unreachable.
						if (detail == null) { unknownSerial = serial; return null; }
						remoteCards.add(detail);
					}
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
				if (unknownSerial != null) {
					JOptionPane.showMessageDialog(frame,
							"Opponent's deck contains a card this client does not have: " + unknownSerial,
							"Multiplayer", JOptionPane.ERROR_MESSAGE);
					return;
				}

				List<CardData> main = new ArrayList<>();
				List<CardData> lb   = new ArrayList<>();
				for (DeckCardDetail card : localCards) {
					CardData cd = buildCardData(card);
					if (card.isLb()) lb.add(cd);
					else             main.add(cd);
				}
				gameState.initializeDeck(main, lb, setup.localDeckRandom());

				List<CardData> p2Main = new ArrayList<>();
				List<CardData> p2Lb   = new ArrayList<>();
				for (DeckCardDetail card : remoteCards) {
					CardData cd = buildCardData(card);
					if (card.isLb()) p2Lb.add(cd);
					else             p2Main.add(cd);
				}
				gameState.initializeP2MainDeck(p2Main, setup.remoteDeckRandom());
				gameState.initializeP2LbDeck(p2Lb);

				// Both decks are shuffled and untouched — the one moment the two clients can be
				// compared card-for-card. Do it before anything draws.
				sendOpeningDealChecksum();

				gameState.drawP2OpeningHand();

				refreshP1DeckLabel();
				refreshP1LimitLabel();
				refreshP2DeckLabel();
				refreshP2HandCountLabel();
				refreshP2LimitButton();
				logEntry("Opponent's deck: " + setup.remoteDeckName());
				drawOpeningHand();
			}
		}.execute();
	}

	/**
	 * Digests the freshly dealt game, sends it to the opponent, and compares against theirs.
	 * A mismatch means the two clients are already playing different games.
	 */
	private void sendOpeningDealChecksum() {
		if (matchSetup == null) return;
		localDealChecksum = MatchChecksum.ofOpeningDeal(
				gameState, matchSetup.localIsHost(), matchSetup.hostGoesFirst());
		GameConnection conn = multiplayerMenu == null ? null : multiplayerMenu.getActiveConnection();
		if (conn != null) {
			conn.send(GameAction.of(ActionType.STATE_CHECKSUM, new JSONObject()
					.put("label", "openingDeal")
					.put("checksum", localDealChecksum)));
		}
		compareDealChecksums();
	}

	/** Reports the opening-deal comparison once both halves have arrived. */
	private void compareDealChecksums() {
		if (localDealChecksum == null || remoteDealChecksum == null) return;
		if (localDealChecksum.equals(remoteDealChecksum)) {
			logEntry("[Sync] Opening deal matches the opponent's client.");
		} else {
			reportDesync("the two clients dealt different games — this usually means the decks "
					+ "or card databases differ between the two installations");
		}
	}

	/**
	 * Reports that the two clients no longer agree on the game state, once per game.
	 *
	 * <p>Says so loudly rather than limping on: past this point the two players are looking at
	 * different games, and every later symptom would be a confusing consequence of this one
	 * cause. Only the first report raises a dialog — a desync tends to cascade.
	 */
	void reportDesync(String detail) {
		logEntry("[Sync] DESYNC — " + detail);
		if (desyncReported) return;
		desyncReported = true;
		JOptionPane.showMessageDialog(frame,
				"The two clients no longer agree on the game state, so play has diverged:\n\n"
						+ detail + "\n\nThis game can no longer be played out.",
				"Multiplayer desync", JOptionPane.ERROR_MESSAGE);
	}

	/**
	 * Advances the local player's phase and tells the opponent, so their copy of this board
	 * follows. Every phase transition on the local player's own turn goes through here.
	 */
	GameState.GamePhase advanceLocalPhase() {
		GameState.GamePhase entered = gameState.advancePhase();
		sendToOpponent(RemoteOpponent.phaseAdvanceAction(entered, gameState.getTurnNumber(), false));
		return entered;
	}

	/**
	 * As {@link #advanceLocalPhase()} for an extra turn. Flagged on the wire because it wraps
	 * END to ACTIVE <em>without</em> passing the turn — the opponent's client has to make the
	 * same distinction or the two would disagree about whose turn it is.
	 */
	GameState.GamePhase advanceLocalPhaseExtraTurn() {
		GameState.GamePhase entered = gameState.advancePhaseExtraTurn();
		sendToOpponent(RemoteOpponent.phaseAdvanceAction(entered, gameState.getTurnNumber(), true));
		return entered;
	}

	/** Sends an action to the remote player; a no-op in a game against the AI. */
	private void sendToOpponent(GameAction action) {
		if (opponent instanceof RemoteOpponent remote) remote.send(action);
	}

	/**
	 * Publishes a board digest at the start of the local player's turn.
	 *
	 * <p>Sent after the phase advances that precede it, so TCP ordering puts it in the
	 * opponent's hands only once they have applied those advances — both clients are then at
	 * the same point in the game and their digests are comparable.
	 */
	void sendTurnStartChecksum() {
		if (matchSetup == null) return;
		int turn = gameState.getTurnNumber();
		sendToOpponent(GameAction.of(ActionType.STATE_CHECKSUM, new JSONObject()
				.put("label", TURN_START_CHECKSUM + turn)
				.put("checksum", MatchChecksum.ofTurnStart(this, matchSetup.localIsHost(), turn))));
	}

	private static final String TURN_START_CHECKSUM = "turnStart:";
	private static final String COMBAT_CHECKSUM     = "combat:";

	/**
	 * How many battles this client has finished. Both clients count the same battles — each
	 * declaration is replicated, and each produces exactly one boundary per client — so the number
	 * is what pairs the two digests for the same combat.
	 */
	private int combatsResolved = 0;

	/**
	 * Combat digests waiting for their counterpart, keyed by battle number — this client's under
	 * {@link #localCombatChecksums}, the opponent's under {@link #remoteCombatChecksums}. A battle
	 * is dropped from both the moment the two are compared.
	 *
	 * <p>Both are needed because either side can arrive first, and a digest describes the board as
	 * it stood at that battle. Recomputing it later would hash a board that has since moved on.
	 */
	private final Map<Integer, String> localCombatChecksums  = new HashMap<>();
	private final Map<Integer, String> remoteCombatChecksums = new HashMap<>();

	/**
	 * Publishes a board digest at the end of a battle, and compares it with the opponent's for the
	 * same battle as soon as both exist.
	 *
	 * <p>Unlike the turn-start digest, this one cannot be compared on arrival. The two clients
	 * finish a battle at genuinely different moments: the defender resolves as soon as its player
	 * declares, while the attacker only starts resolving when the BLOCK lands and then waits out a
	 * priority round before damage. Comparing whatever state happened to be on the board when the
	 * message arrived would report a desync on every single battle. So each side records its own
	 * digest under the battle number and the comparison happens when the second of the two shows
	 * up, whichever that is.
	 */
	void sendCombatChecksum() {
		if (matchSetup == null) return;
		int battle = ++combatsResolved;
		String local = MatchChecksum.ofCombat(this, matchSetup.localIsHost(), battle);
		localCombatChecksums.put(battle, local);
		sendToOpponent(GameAction.of(ActionType.STATE_CHECKSUM, new JSONObject()
				.put("label", COMBAT_CHECKSUM + battle)
				.put("checksum", local)));
		matchCombatChecksums(battle);
	}

	/** Compares the two digests for {@code battle} once both are in, and forgets it either way. */
	private void matchCombatChecksums(int battle) {
		String local  = localCombatChecksums.get(battle);
		String remote = remoteCombatChecksums.get(battle);
		if (local == null || remote == null) return;
		localCombatChecksums.remove(battle);
		remoteCombatChecksums.remove(battle);
		if (!local.equals(remote))
			reportDesync("board state differs from the opponent's after battle " + battle
					+ " of turn " + gameState.getTurnNumber());
	}

	/** Handles an inbound digest, whose label says which moment in the game it describes. */
	private void onRemoteChecksum(JSONObject payload) {
		String label  = payload.optString("label", "");
		String remote = payload.optString("checksum", "");
		if (label.startsWith(TURN_START_CHECKSUM)) {
			if (matchSetup == null) return;
			int turn = gameState.getTurnNumber();
			String local = MatchChecksum.ofTurnStart(this, matchSetup.localIsHost(), turn);
			if (!local.equals(remote))
				reportDesync("board state differs from the opponent's at the start of turn " + turn);
			return;
		}
		if (label.startsWith(COMBAT_CHECKSUM)) {
			if (matchSetup == null) return;
			// Held rather than judged until this client has finished the same battle — until then
			// the two boards are legitimately different.
			int battle = Integer.parseInt(label.substring(COMBAT_CHECKSUM.length()));
			remoteCombatChecksums.put(battle, remote);
			matchCombatChecksums(battle);
			return;
		}
		remoteDealChecksum = remote;
		compareDealChecksums();
	}

	/**
	 * Expresses {@code arranged} as positions in {@code original}, so a hand order can be sent
	 * as a permutation rather than as cards. Matched by identity: the deck holds a distinct
	 * {@link CardData} per copy, and two copies of one card are equal but not interchangeable
	 * here.
	 */
	private static List<Integer> permutationOf(List<CardData> original, List<CardData> arranged) {
		List<Integer> order = new ArrayList<>(arranged.size());
		boolean[] taken = new boolean[original.size()];
		for (CardData card : arranged) {
			for (int i = 0; i < original.size(); i++) {
				if (!taken[i] && original.get(i) == card) { order.add(i); taken[i] = true; break; }
			}
		}
		return order;
	}

	/** The opponent has settled their opening hand; turn 1 waits for both players. */
	void noteRemoteHandKept() {
		remoteHandKept = true;
		beginFirstTurnWhenBothKept();
	}

	/**
	 * Starts turn 1 once both players have kept an opening hand.
	 *
	 * <p>Both halves must be in before either client moves: a player who kept quickly would
	 * otherwise start advancing phases against an opponent whose game had not begun, and the
	 * phase messages would arrive with nothing to apply them to.
	 */
	private void beginFirstTurnWhenBothKept() {
		if (!localHandKept || !remoteHandKept || gameState.getCurrentPhase() != null) return;
		boolean p1GoesFirst = matchSetup.localGoesFirst();
		gameState.startFirstTurn(p1GoesFirst ? GameState.Player.P1 : GameState.Player.P2);
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
			opponent.runTurn();
		}
	}

	/**
	 * Builds a fully-parsed {@link CardData} from a {@link DeckCardDetail} row. Shared by the
	 * P1/P2 deck-loading loops and the debug card-spawn tooling so the parse wiring stays in one place.
	 */
	static CardData buildCardData(DeckCardDetail card) {
		String tx = card.textEn();
		return new CardData(card.imageUrl(), card.name(), card.element(),
				card.cost(), card.power(), card.type(), card.isLb(), card.lbCost(), card.exBurst(),
				card.multicard(), CardData.parseTraits(tx, card.name()),
				CardData.parseWarpValue(tx), CardData.parseWarpCost(tx),
				CardData.parsePrimingTarget(tx), CardData.parsePrimingCost(tx),
				CardData.parseActionAbilities(tx), CardData.parseAutoAbilities(tx),
				CardData.parseFieldAbilities(tx, card.type()),
				CardData.parseIfControlBoosts(tx, card.type()),
				CardData.parseFieldPowerGrants(tx, card.type(), card.name()),
				CardData.parseScalingSelfPowerBoosts(tx, card.type(), card.name()),
				CardData.parseFieldCostReductions(tx, card.type()),
				CardData.parseSelfCostModifiers(tx),
				CardData.parseFieldPrimingAnyElements(tx, card.type()),
				CardData.parseFieldPartyAnyElements(tx, card.type()),
				CardData.parseWarpCostAnyElement(tx),
				CardData.parseCanFormPartyAnyElement(tx),
				CardData.parseFieldCannotBeBlockedByCost(tx, card.name()),
				CardData.parseCannotBeBlockedByHigherPower(tx, card.name()),
				CardData.parseCannotBlockAtAll(tx, card.name()),
				CardData.parseCannotBlockHigherPower(tx, card.name()),
				CardData.parseCannotBlockParty(tx, card.name()),
				CardData.parseCannotAttackOrBlock(tx, card.name()),
				CardData.parseMaxAttacksPerTurn(tx, card.name()),
				card.job(), card.category1(), card.category2(), tx);
	}

	/** Loads and builds a {@link CardData} for {@code serial} from the card DB, or {@code null} if unknown. */
	CardData buildCardDataFromSerial(String serial) {
		try (DeckDatabase db = new DeckDatabase()) {
			DeckCardDetail detail = db.getCardDetailBySerial(serial);
			return detail == null ? null : buildCardData(detail);
		} catch (java.sql.SQLException e) {
			JOptionPane.showMessageDialog(frame, "Error loading card " + serial + ":\n" + e.getMessage(),
					"Debug Spawn", JOptionPane.ERROR_MESSAGE);
			return null;
		}
	}

	// -------------------------------------------------------------------------
	// Debug: spawn cards onto the CPU (P2) to reproduce CPU behavior on demand
	// -------------------------------------------------------------------------

	/** True once a game has started (the deck has been dealt). Guards debug spawns that need a live board. */
	boolean gameInProgress() {
		return gameState != null && gameState.getCurrentPhase() != null;
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
		showOpeningHandPopup(drawn,
				!gameState.isP1MulliganUsed() || AppSettings.isDebugUnlimitedMulligan());
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
			lbl.setFont(FontLoader.loadPixelFont(10));
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

						Icon tmpIcon = cardLabels[idx].getIcon();
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
		instructions.setFont(FontLoader.loadPixelFont(10));

		// ── Buttons ──────────────────────────────────────────────────────────
		JButton keepBtn = new JButton(mulliganAvailable ? "Keep Hand" : "Take Hand");
		keepBtn.setFont(FontLoader.loadPixelFont(11));
		keepBtn.addActionListener(e -> {
			hideZoom();
			openingHandPopup.dispose();
			openingHandPopup = null;
			if (mulliganAvailable) logEntry("Kept opening hand");
			List<Integer> order = permutationOf(cards, handOrder);
			gameState.keepHand(handOrder);
			if (matchSetup != null) {
				// The opponent's client holds this hand as P2's; tell it the order we settled on
				// so both sides address the same card by the same index from here on.
				sendToOpponent(GameAction.of(ActionType.KEEP_HAND,
						new JSONObject().put("order", new JSONArray(order))));
				localHandKept = true;
				beginFirstTurnWhenBothKept();
				return;
			}
			boolean p1GoesFirst = AppSettings.isDebugAlwaysWinCoinFlip() || Math.random() < 0.5;
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
				opponent.runTurn();
			}
		});

		JButton mulliganBtn = new JButton("Mulligan");
		mulliganBtn.setFont(FontLoader.loadPixelFont(11));
		mulliganBtn.setEnabled(mulliganAvailable);
		mulliganBtn.setToolTipText(mulliganAvailable
				? "Put these cards on the bottom (in this order) and draw 5 new cards"
				: "Mulligan already used");
		mulliganBtn.addActionListener(e -> {
			hideZoom();
			logEntry("Took mulligan");
			// A mulligan reorders the deck, so the opponent's copy of it has to follow.
			if (matchSetup != null) {
				sendToOpponent(GameAction.of(ActionType.MULLIGAN, new JSONObject()
						.put("bottomOrder", new JSONArray(permutationOf(cards, handOrder)))));
			}
			// handOrder is the player's chosen bottom-of-deck order
			List<CardData> newCards = gameState.mulligan(new ArrayList<>(handOrder));
			refreshP1DeckLabel();
			showOpeningHandPopup(newCards, AppSettings.isDebugUnlimitedMulligan());
		});

		JPanel buttonsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonsPanel.add(keepBtn);
		buttonsPanel.add(mulliganBtn);

		// ── Assemble ─────────────────────────────────────────────────────────
		JLabel titleLabel = new JLabel("Opening Hand", SwingConstants.CENTER);
		titleLabel.setFont(FontLoader.loadPixelFont(14));

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

		// Center on screen
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
	int showEffectOptionDialog(String message, String title, Object[] options) {
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

	void refreshP1HandLabel() {
		refreshHandFan();
	}

	/**
	 * Hands P1's fan the cards it should be drawing, and updates the borrowed-cast button.
	 *
	 * <p>Only the contents: what each card costs and whether it can be cast is asked for on every
	 * paint through {@link #handCardState(int)}, so this is needed when the hand gains or loses a
	 * card and not merely when the answers move.
	 */
	private void refreshHandFan() {
		if (p1HandFan == null) return;
		List<String> urls = new ArrayList<>();
		for (CardData card : gameState.getP1Hand()) urls.add(card.imageUrl());
		p1HandFan.setCards(urls);

		if (playableCardsButton != null) {
			int borrowable = bzPlayableP1.size();
			playableCardsButton.setText("PLAYABLE  " + borrowable);
			playableCardsButton.setVisible(borrowable > 0);
		}
	}

	/**
	 * What the fan should say about the card at {@code handIdx} right now: its printed cost, what
	 * casting it would actually cost, and whether it can be cast at all.
	 *
	 * <p>Reached from the fan's paint, which is what keeps it honest — every input here moves
	 * without the hand changing, and the popover this replaced got the same freshness for free by
	 * being rebuilt each time it opened.
	 */
	private PlayerHandFanPanel.State handCardState(int handIdx) {
		List<CardData> hand = gameState.getP1Hand();
		if (handIdx < 0 || handIdx >= hand.size()) return PlayerHandFanPanel.State.UNKNOWN;
		CardData card = hand.get(handIdx);
		return new PlayerHandFanPanel.State(
				card.cost(), effectiveCastCost(card), canCastFromHand(card, handIdx));
	}

	/**
	 * Whether the card at {@code handIdx} can be cast from hand right now — timing window, name and
	 * Light/Dark conflicts, affordability, a free Backup slot, cast restrictions and the per-turn
	 * cast limit.
	 *
	 * <p>One method because two things ask: the fan rings a castable card in blue, and the card's
	 * own menu enables or greys its Play item. They were the same expression written twice, and a
	 * fan that says yes over a menu that says no is worse than either answer alone.
	 */
	private boolean canCastFromHand(CardData card, int handIdx) {
		boolean isCharacter = card.isForward() || card.isBackup() || card.isMonster();
		boolean nameConflict = isCharacter && !card.multicard()
				&& hasCharacterNameOnField(card.name()) && !isMultiNameExceptionActive(card.name(), true);
		boolean lightDarkConflict = isCharacter && isLightDarkConflict(card);
		return castTimingWindowOpen(card) && !nameConflict && !lightDarkConflict
				&& canAffordCard(card, handIdx)
				&& (!card.isBackup() || hasAvailableBackupSlot())
				&& castRestrictionMet(card)
				&& !summonCastBlocked(card, true)
				&& !p1CastLimitReached();
	}

	/** The gold "PLAYABLE n" control that opens {@link #showPlayableCardsDialog()}. */
	private JButton buildPlayableCardsButton() {
		final Color goldText = new Color(212, 175, 55);
		final Color goldEdge = new Color(150, 120, 50);
		final Color baseBg   = new Color(34, 30, 22);
		final Color hoverBg  = new Color(58, 50, 32);

		JButton btn = new JButton("PLAYABLE  0");
		btn.setToolTipText("Cards you can play from outside your hand");
		btn.setFont(FontLoader.loadPixelFont(9));
		btn.setForeground(goldText);
		btn.setBackground(baseBg);
		btn.setOpaque(true);
		btn.setFocusPainted(false);
		btn.setFocusable(false);
		btn.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(goldEdge, 1),
				BorderFactory.createEmptyBorder(3, 10, 3, 10)));
		btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
		btn.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent e) {
				btn.setBackground(hoverBg);
				btn.setBorder(BorderFactory.createCompoundBorder(
						BorderFactory.createLineBorder(goldText, 1),
						BorderFactory.createEmptyBorder(3, 10, 3, 10)));
			}
			@Override public void mouseExited(MouseEvent e) {
				btn.setBackground(baseBg);
				btn.setBorder(BorderFactory.createCompoundBorder(
						BorderFactory.createLineBorder(goldEdge, 1),
						BorderFactory.createEmptyBorder(3, 10, 3, 10)));
			}
		});
		btn.addActionListener(e -> showPlayableCardsDialog());
		btn.setVisible(false);
		return btn;
	}

	/** Rebuilds the fan so the "Playable Cards" button reflects the current borrowed-cast registry. */
	void refreshPlayableCardsButton() { refreshHandFan(); }

	/**
	 * Shows every card P1 may currently cast from outside their hand (the {@link #bzPlayableP1}
	 * registry — Break-Zone and removed-from-game borrowed casts).  Clicking one opens the standard
	 * payment dialog at the entry's effective (reduced/free) cost, with any-element payment when granted.
	 */
	private void showPlayableCardsDialog() {
		List<Map.Entry<CardData, PlayableEntry>> entries = new ArrayList<>(bzPlayableP1.entrySet());
		if (entries.isEmpty()) return;

		JDialog dlg = new JDialog(frame, "Playable Cards (" + entries.size() + ")", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
		for (Map.Entry<CardData, PlayableEntry> en : entries) {
			final CardData cd = en.getKey();
			final PlayableEntry pe = en.getValue();
			final int cost = pe.effectiveCost(cd);

			// Apply the same play legality the hand-cast path enforces (uniqueness, Light/Dark, backup slot).
			boolean isCharacter   = cd.isForward() || cd.isBackup() || cd.isMonster();
			boolean nameConflict  = isCharacter && !cd.multicard() && hasCharacterNameOnField(cd.name()) && !isMultiNameExceptionActive(cd.name(), true);
			boolean ldConflict    = isCharacter && isLightDarkConflict(cd);
			boolean noSlot        = cd.isBackup() && !hasAvailableBackupSlot();
			boolean summonBlocked = cd.isSummon() && summonCastingProhibited();
			boolean noTarget      = !summonBlocked && !summonHasCastTarget(cd, true);
			final boolean legal   = !nameConflict && !ldConflict && !noSlot && !summonBlocked && !noTarget && !p1CastLimitReached();
			final String reason   = nameConflict ? "Name conflict" : ldConflict ? "Light/Dark"
					: noSlot ? "No slot" : summonBlocked ? "Summons blocked"
					: noTarget ? "No target" : null;

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(legal ? Color.LIGHT_GRAY : new Color(180, 60, 60), legal ? 1 : 2));
			lbl.setCursor(Cursor.getPredefinedCursor(legal ? Cursor.HAND_CURSOR : Cursor.DEFAULT_CURSOR));
			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) showZoomAt(cd.imageUrl()); }
				@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				@Override public void mousePressed(MouseEvent e) {
					if (!SwingUtilities.isLeftMouseButton(e)) return;
					if (!legal) {
						JOptionPane.showMessageDialog(dlg,
								"Cannot play \"" + cd.name() + "\" right now: " + reason + ".",
								"Cannot Play", JOptionPane.WARNING_MESSAGE);
						return;
					}
					hideZoom();
					dlg.dispose();
					showBzPlayPaymentDialog(cd, cost);
				}
			});
			final int delta = cd.cost() - cost;
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					if (img == null) return null;
					BufferedImage bi = CardAnimation.toARGB(img, CARD_W, CARD_H);
					// Bake the effective (reduced) cost into the top-left, matching the hand popup.
					if (delta != 0) {
						Graphics2D g2 = bi.createGraphics();
						g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
						String text = String.valueOf(cost);
						g2.setFont(FontLoader.loadPixelFont(15));
						FontMetrics fm = g2.getFontMetrics();
						int x = 8, y = fm.getAscent() + 7;
						g2.setColor(Color.BLACK);
						g2.drawString(text, x + 1, y + 1);
						g2.drawString(text, x + 2, y + 1);
						g2.drawString(text, x + 1, y + 2);
						g2.drawString(text, x + 2, y + 2);
						g2.setColor(delta > 0 ? new Color(0x44EE44) : new Color(0xFF8844));
						g2.drawString(text, x, y);
						g2.dispose();
					}
					return new ImageIcon(bi);
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			String tag = !legal ? reason : pe.freeCast() ? "Free" : pe.anyElement() ? "Any Element" : null;
			JLabel info = new JLabel(cd.name() + (tag != null ? "  [" + tag + "]" : ""), SwingConstants.CENTER);
			info.setFont(FontLoader.loadPixelFont(9));
			info.setForeground(legal ? Color.WHITE : new Color(230, 120, 120));
			info.setPreferredSize(new Dimension(CARD_W, 18));

			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());
			wrapper.add(lbl,  BorderLayout.CENTER);
			wrapper.add(info, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		JScrollPane scrollPane = new JScrollPane(cardsPanel,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER, JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(
				Math.min(entries.size() * (CARD_W + 16) + 16, 900), CARD_H + 60));
		dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	/**
	 * Registers {@code card} as castable by P1 from outside hand under {@code entry}.  The card must
	 * already have been moved into its source zone (Break Zone or removed-from-game).  "This turn"
	 * entries are auto-removed at end of turn; "during this game" / "at any time" entries persist.
	 *
	 * <p>Cards printing "You cannot cast [Name]." are never registered — the prohibition applies to
	 * every zone a cast could be declared from, not just the hand.
	 */
	void registerBorrowedPlayable(boolean casterIsP1, CardData card, PlayableEntry entry) {
		if (card.castProhibited()) return;
		IdentityHashMap<CardData, PlayableEntry> reg = casterIsP1 ? bzPlayableP1 : bzPlayableP2;
		reg.put(card, entry);
		if (entry.expiresThisTurn()) endOfTurnEffects.add(ctx -> reg.remove(card));
		refreshP1WarpZoneUI();
		refreshP2WarpZoneUI();
		refreshPlayableCardsButton();
	}


	void refreshP1DeckLabel() {
		int count = gameState.getP1MainDeck().size();
		if (count == 0) {
			p1DeckLabel.setIcon(null);
			p1DeckLabel.setText("DECK");
		} else {
			p1DeckLabel.setIcon(scaledCardbackWithCount(new Dimension(CARD_W, CARD_H), count));
			p1DeckLabel.setText(null);
		}
	}

	void refreshP2DeckLabel() {
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

	/**
	 * Refreshes P2's fanned hand — one card back per card held, plus the count tooltip. Named for
	 * the label it used to drive; kept that way because ~80 call sites across six files reference it.
	 */
	void refreshP2HandCountLabel() {
		if (p2HandFan == null) return;
		p2HandFan.setCount(gameState.getP2Hand().size());
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
	 *   <li>MAIN_1  → ATTACK : passes priority to P2 (auto-pass), then enters Attack</li>
	 *   <li>ATTACK  → MAIN_2 : nothing automatic</li>
	 *   <li>MAIN_2  → END    : passes priority to P2 (auto-pass), then runs end-of-turn</li>
	 *   <li>END     → ACTIVE : increment turn, immediately activate cards</li>
	 * </ul>
	 */
	void onNextPhase() {
		if (gameState.isP1GameOver()) return;
		// A field-target selection is outstanding. Its secondary loop leaves the UI live, and turn
		// flow may have re-enabled the Next button underneath it, so ignore the click rather than
		// advancing the phase or passing priority on top of an unresolved choice.
		if (fieldTargetingActive) return;
		// P1 holds priority at a combat checkpoint — on either player's turn, since P1 also responds
		// to P2's attacks. Checked before the P2-turn branch, which returns early.
		if (p1CombatPriorityOnPass != null) {
			if (gameState.getStack().isEmpty()) passP1CombatPriority();
			return;
		}
		if (gameState.getCurrentPlayer() == GameState.Player.P2) {
			// P1 pressing Next Phase during P2's turn = passing priority back
			if (p1PriorityInP2MainOnDone != null && gameState.getStack().isEmpty()) {
				Runnable callback = p1PriorityInP2MainOnDone;
				p1PriorityInP2MainOnDone = null;
				if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
				logEntry("[Priority] P1 passes — advancing phase.");
				callback.run();
			}
			return;
		}
		GameState.GamePhase current = gameState.getCurrentPhase();
		if (current == null) return;

		switch (current) {
			case ACTIVE ->  {
				// Advance first so getTurnNumber() still reflects the current turn
				advanceLocalPhase();   // ACTIVE → DRAW
				refreshPhaseTracker();
				int drawCount = gameState.getTurnNumber() == 1 ? 1 : 2;
				List<CardData> drawn = drawP1Cards(drawCount);
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
                            advanceLocalPhase();   // DRAW → MAIN_1
                            refreshPhaseTracker();
                            logEntry("Main Phase 1");
                            processWarpCounters(true);
                            if (!pendingMainPhase1Effects.isEmpty()) {
                                List<Consumer<GameContext>> pending = new ArrayList<>(pendingMainPhase1Effects);
                                pendingMainPhase1Effects.clear();
                                GameContext ctx = buildGameContext(true);
                                pending.forEach(e -> e.accept(ctx));
                            }
                            autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase1(true);
                            autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase1EachTurn();
                            autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfOppMainPhase1(false);
                            syncBzForwardPlayables(true);
            }

			case MAIN_1 -> {
                            p1AttackSelection.clear();
                            p1DeclaredAttackers.clear();
                            p1MonsterAttackIdx = -1;
                            logEntry("[Priority] P1 passes — P2 may respond.");
                            if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
                            offerPhasePriority(() -> {
                                advanceLocalPhase();   // MAIN_1 → ATTACK
                                logEntry("Attack Phase");
                                autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhase(true);
                                autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhaseEachTurn(true);
                                autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfOppAttackPhase(true);
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
                            });
            }

			case ATTACK -> {
                            if (p2AutoPassTimer      != null) { p2AutoPassTimer.stop();         p2AutoPassTimer      = null; }

                            if (attackSubStep == 0) {
                                logEntry("[Priority] P1 passes — P2 may respond.");
                                if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
                                offerPhasePriority(() -> {
                                    setAttackSubStep(1);
                                    refreshPhaseTracker();
                                    refreshAttackButton();
                                    refreshAllForwardSlots();
                                    logEntry("Declare an attacker, or click Skip to end the Attack Phase.");
                                });
                                return;
                            }

                            // A Forward under a must-attack compulsion has to be sent in before the
                            // phase can be left. This is the only enforcement point for that rule:
                            // p1MustAttack was written and re-indexed but never read, so
                            // "it must attack this turn if possible" had no effect at all until now.
                            int mustAttackIdx = p1ForwardCompelledToAttackIdx();
                            if (mustAttackIdx >= 0) {
                                showEffectOptionDialog(p1ForwardCards.get(mustAttackIdx).name()
                                        + " must attack this turn if possible.",
                                        "Must Attack", new Object[]{"OK"});
                                return;
                            }

                            // ATTACK → MAIN_2 (all attacks finished or skipped)
                            p1AttackSelection.clear();
                            p1DeclaredAttackers.clear();
                            attackSubStep = -1;
                            if (skipAttackButton != null) skipAttackButton.setEnabled(false);
                            if (nextPhaseButton != null) nextPhaseButton.setEnabled(true);
                            refreshAttackButton();
                            advanceLocalPhase();   // ATTACK → MAIN_2
                            refreshPhaseTracker();
                            // The attack phase is over, so the exhausted-attacker glow comes off
                            // with it — on both boards, since the same phase ended for both.
                            refreshCombatGlows();
                            logEntry("Main Phase 2");
                            autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfMainPhase2(true);
                            syncBzForwardPlayables(true);
			}

			case MAIN_2 -> {
                            logEntry("[Priority] P1 passes — P2 may respond.");
                            if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
                            offerPhasePriority(() -> {
                                advanceLocalPhase();   // MAIN_2 → END
                                refreshPhaseTracker();
                                logEntry("End Phase");
                                autoAbilityTriggers.triggerAutoAbilitiesForEndOfYourTurn(true);
                                autoAbilityTriggers.triggerAutoAbilitiesForEndOfEachPlayersTurn();
                                autoAbilityTriggers.triggerAutoAbilitiesForEndOfOpponentTurn(false);
                                fireEndOfTurnEffects(true);
                                for (int i = 0; i < p1ForwardDamage.size(); i++) p1ForwardDamage.set(i, 0);
                                for (int i = 0; i < p1ForwardPowerBoost.size(); i++) p1ForwardPowerBoost.set(i, 0);
                                for (int i = 0; i < p1ForwardPowerReduction.size(); i++) p1ForwardPowerReduction.set(i, 0);
                                p1ForwardTempTraits.forEach(EnumSet::clear);
                                p1ForwardRemovedTraits.forEach(EnumSet::clear);
                                Collections.fill(p1ForwardTempJobs, null);
                                for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
                                for (int i = 0; i < p2ForwardDamage.size(); i++) p2ForwardDamage.set(i, 0);
                                for (int i = 0; i < p2ForwardPowerBoost.size(); i++) p2ForwardPowerBoost.set(i, 0);
                                for (int i = 0; i < p2ForwardPowerReduction.size(); i++) p2ForwardPowerReduction.set(i, 0);
                                p2ForwardTempTraits.forEach(EnumSet::clear);
                                p2ForwardRemovedTraits.forEach(EnumSet::clear);
                                Collections.fill(p2ForwardTempJobs, null);
                                p1MonsterPowerBoost.clear(); p2MonsterPowerBoost.clear();
                                p1MonsterTempTraits.clear(); p2MonsterTempTraits.clear();
                                for (int i = 0; i < p1MonsterCards.size(); i++) refreshP1MonsterSlot(i);
                                for (int i = 0; i < p2MonsterCards.size(); i++) refreshP2MonsterSlot(i);
                                clearBackupForwardState();
                                p1CannotBeBlocked.clear();              p2CannotBeBlocked.clear();
                                p1CannotBeBlockedByCost.clear();        p2CannotBeBlockedByCost.clear();
                                p1CannotBeBlockedByPower.clear();       p2CannotBeBlockedByPower.clear();
                                p1CannotBlock.clear();                  p2CannotBlock.clear();
                                p1MustBlock.clear();                    p2MustBlock.clear();
                                p1CannotAttack.clear();                 p2CannotAttack.clear();
                                p1MustAttack.clear();                   p2MustAttack.clear();
                                p1CannotAttackPersistent.clear();       p1CannotBlockPersistent.clear();
                                cannotUseActionAbilitiesThisTurn.clear();
                                attacksMadeThisTurn.clear();            extraAttacksThisTurn.clear();
                                grantedFieldAbilities.clear();          grantedMaxAttacks.clear();
                                p1TempAttackTriggers.clear();           p2TempAttackTriggers.clear();
                                p1TempBlockTriggers.clear();            p2TempBlockTriggers.clear();
                                p1TempIsBlockedTriggers.clear();        p2TempIsBlockedTriggers.clear();
                                nextIncomingDmgZeroSet.clear();   allIncomingDmgZeroThisTurnSet.clear();   nextOppEffectDmgZeroSet.clear();   nextIncomingDmgRedirectMap.clear();   nextIncomingDmgReduceMap.clear();   nextAbilityDmgReduceMap.clear();
                                nextIncomingDmgReduceKickbackMap.clear();  pendingShieldKickbacks.clear();
                                incomingDmgIncreaseMap.clear();   globalForwardIncomingDmgIncrease = 0;   nullifyAbilityDmgSet.clear();
                                p1Turn.nullifyAbilityDmgFilters.clear(); p2Turn.nullifyAbilityDmgFilters.clear();
                                p1DoublecastFreeSummons = false;  p2DoublecastFreeSummons = false;
                                p1DoublecastLastSummonCost = -1;  p2DoublecastLastSummonCost = -1;
                                allForwardsCannotBeBlockedByHigherCostThisTurn = false;
                                p1Turn.fwdBoostSuppressedThisTurn = false; p2Turn.fwdBoostSuppressedThisTurn = false;
                                nullifyAbilityOnlyDmgSet.clear(); nullifySummonOnlyDmgSet.clear(); perCardNonLethalDmgSet.clear();
                                nextOutgoingDmgZeroSet.clear();    allOutgoingDmgZeroThisTurnSet.clear();    abilityDmgToForwardZeroedThisTurnSet.clear();    outgoingDmgMultiplierMap.clear();
                                nextOutgoingDmgDoublerSet.clear(); outgoingDmgFlatBoostMap.clear();
                                perCardIncomingDmgMultiplierMap.clear();
                                p1Turn.forwardIncomingDmgMult = 1;      p2Turn.forwardIncomingDmgMult = 1;
                                p1Turn.abilityOutgoingDmgMult = 1;      p2Turn.abilityOutgoingDmgMult = 1;
                                cannotBeChosenBySummons.clear();  cannotBeChosenByAbilities.clear();  cannotBeChosenBySummonsAnyone.clear();  cannotBeChosenByAbilitiesAnyone.clear();  cannotBeChosenByElement.clear();  nullifyElementDamageMap.clear();  nullifyElementDamageAbilityOnlyMap.clear();  rfgInsteadOfBzThisTurn.clear();  drawOnFieldToBzThisTurn.clear();  putIntoBzWhenLeavesFieldThisTurn.clear();  damageZeroedSourcesThisTurn.clear();  damagedBySourcesThisTurn.clear();
                                breaktouchBattleSet.clear();   breakWhenDealtDamageSet.clear();
                                p1Turn.nonLethalProtection = false;    p2Turn.nonLethalProtection = false;
                                p1Turn.dmgReductionDisabled = false;   p2Turn.dmgReductionDisabled = false;
                                p1Turn.forwardCannotBlockInferiorPower = false; p2Turn.forwardCannotBlockInferiorPower = false;
                                p1Turn.globalDmgReduction  = 0;        p2Turn.globalDmgReduction  = 0;
                                p2Turn.attackDeclarationLimit = Integer.MAX_VALUE; p2Turn.attackDeclarationsThisTurn = 0;
                                p1Turn.attackDeclarationLimit = Integer.MAX_VALUE;       p1Turn.attackDeclarationsThisTurn = 0;
                                p1Turn.cannotSearchThisTurn = false; p2Turn.cannotSearchThisTurn = false;
                                p1Turn.cannotCastThisTurn = false;   p2Turn.cannotCastThisTurn = false;
                                p1Turn.oppFieldEntryBecomesRfg = false; p2Turn.oppFieldEntryBecomesRfg = false;
                                // attacksMadeThisTurn was just emptied, so the exhausted-attacker
                                // glow comes off with it.
                                refreshCombatGlows();
                                // An end-of-turn trigger may still be arriving/resolving (a card
                                // returning from the RFG zone and its abilities) — the turn may not
                                // advance until the player has resolved it and the stack is empty.
                                runWhenBoardSettled(() -> {
                                    showEndPhaseDiscardDialog();
                                    onNextPhase();         // END → ACTIVE (auto-advance)
                                });
                            });
            }

			case END ->  {
				// P1's turn is over: their cast allowance refreshes for the turn now beginning,
				// which they may spend on Summons and Back Attack Characters while holding
				// priority.  Done before control is handed over, on both branches below.
				p1Turn.resetCastTracking();
				if (p1ExtraTurnThenLose) {
					p1ExtraTurnThenLose = false;
					logEntry("Extra Turn — P1 takes one additional turn");
					advanceLocalPhaseExtraTurn(); // END → ACTIVE, same player
					refreshPhaseTracker();
					nextPhaseButton.setEnabled(true);
					endOfTurnEffects.add(ctx -> triggerGameOver("Extra Turn ended — You Lose!"));
					onNextPhase(); // begin ACTIVE → DRAW automatically
				} else {
					// END → ACTIVE: increments turn number and switches to P2
					advanceLocalPhase();
					refreshPhaseTracker();
					for (int i = 0; i < p1MonsterCards.size(); i++) refreshP1MonsterSlot(i);
					for (int i = 0; i < p2MonsterCards.size(); i++) refreshP2MonsterSlot(i);
					nextPhaseButton.setEnabled(false);
					opponent.runTurn();
				}
				usedOncePerTurnAbilities.clear();
				specialAbilitiesUsedThisTurn.clear();
			}
		}
	}

	void refreshPhaseTracker() {
		if (phaseTracker == null || gameState.getCurrentPhase() == null) return;
		boolean isP1Turn = gameState.getCurrentPlayer() == GameState.Player.P1;
		phaseTracker.setState(
			PhaseTracker.PHASES[gameState.getCurrentPhase().ordinal()],
			gameState.getTurnNumber(),
			isP1Turn
		);
		phaseTracker.setHasPriority(isP1Turn);
		if (gameState.getCurrentPhase() == GameState.GamePhase.ATTACK && attackSubStep >= 0)
			phaseTracker.setAttackStep(attackSubStep);
		// The phase is the biggest single swing in what may be cast — nothing during Draw, the
		// whole hand in Main Phase 1 — and this is the one hook every phase change passes through.
		refreshHandCardStates();
	}

	/** Appends a timestamped entry to the game log. */
	void logEntry(String text) {
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
		p1DeclaredAttackers.clear();
		p2DeclaredAttackers.clear();
		Arrays.fill(p1BackupPlayedOnTurn, 0);
		Arrays.fill(p1BackupFrozen, false);
		lastCastPaymentDistinctElements = 0;
		lastCastPaymentElements.clear();
		lastCastPaymentCard = null;
		lastCastPaymentBackups.clear();
		lastCastWasPaidByBackupsOnly = false;

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
		p1MonsterPowerBoost.clear();
		p1MonsterTempTraits.clear();
		// Ahead of the sweep, which otherwise holds these keys back: outlasting the turn does not
		// mean outlasting the game, and this is the zone teardown, not an end of turn.
		backupPermanentForwards.clear();
		clearBackupForwardState();

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
		p2MonsterPowerBoost.clear();
		p2MonsterTempTraits.clear();
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
		Arrays.fill(p2BackupFrozen, false);

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
			p1LimitButton.setText("LIMIT");
			p1LimitButton.setForeground(new Color(80, 65, 20));
		} else {
			p1LimitButton.setText("LIMIT - " + playable);
			p1LimitButton.setForeground(Color.BLACK);
		}
	}


	void refreshP2LimitButton() {
		if (p2LimitButton == null) return;
		int total    = gameState.getP2LbDeck().size();
		int playable = total - p2SpentLbIndices.size();
		if (total == 0) {
			p2LimitButton.setText("LIMIT");
			p2LimitButton.setForeground(new Color(80, 65, 20));
		} else {
			p2LimitButton.setText("LIMIT - " + playable);
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

		JPanel cardsPanel = new JPanel(new GridLayout(0, 4, 8, 8));
		cardsPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

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
			nameLabel.setFont(FontLoader.loadPixelFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
			wrapper.add(lbl,       BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		JButton closeBtn = new JButton("Close");
		closeBtn.setFont(FontLoader.loadPixelFont(11));
		closeBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		south.add(closeBtn);
		south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
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
			p2RemoveButton.setEnabled(!gameState.getP2WarpZone().isEmpty()
					|| !gameState.getP2PermanentRfp().isEmpty()
					|| (p2RemoveLabel != null && p2RemoveLabel.getUrl() != null));
	}

	/** Updates the P1 RFP label to show the most recently added removed card (warp or permanent). */
	void refreshP1WarpZoneUI() { refreshWarpZoneUI(true); }

	/** Updates the P2 RFP label to show the most recently added removed card (warp or permanent). */
	void refreshP2WarpZoneUI() { refreshWarpZoneUI(false); }

	private void refreshWarpZoneUI(boolean isP1) {
		List<GameState.WarpEntry> zone = isP1
				? gameState.getP1WarpZone() : gameState.getP2WarpZone();
		List<CardData>            perm = isP1
				? gameState.getP1PermanentRfp() : gameState.getP2PermanentRfp();
		GrayscaleLabel            label = isP1 ? p1RemoveLabel : p2RemoveLabel;
		if (label == null) return;
		if (zone.isEmpty() && perm.isEmpty()) {
			label.setIcon(null);
			label.setUrl(null);
			refreshRemoveButtons();
			return;
		}
		// Prefer the last-added permanent RFP card for the label; fall back to last warp card
		// A face-down card the local seat does not own shows its back here, and carries no url:
		// this label is the one part of the zone that is on screen without being asked for, so a
		// hidden card reaching it would give the whole removal away at a glance.
		boolean hideTop = !perm.isEmpty() && isFaceDownToLocalSeat(perm.get(perm.size() - 1), isP1);
		String url = hideTop ? null
				: !perm.isEmpty()
				? perm.get(perm.size() - 1).imageUrl()
				: zone.get(zone.size() - 1).card.imageUrl();
		label.setUrl(url);
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image img = url == null ? loadCardbackImage() : ImageCache.load(url);
				return img == null ? null
						: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
			}
			@Override protected void done() {
				try { ImageIcon ic = get(); if (ic != null) { label.setIcon(ic); } }
				catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
		refreshRemoveButtons();
	}

	/**
	 * Whether {@code card} sits in the RFG zone face down to the player at this screen -- Aemo
	 * 23-022R. P1 is the local seat, so a face-down card is hidden exactly when it is not P1's own:
	 * its owner may look at it at any time, and no one else may look at all.
	 *
	 * <p>{@code zoneIsP1} is the zone being rendered rather than the card's owner, but the two
	 * agree -- cards are removed into their own owner's zone -- and the zone is what the callers
	 * already have in hand.
	 */
	boolean isFaceDownToLocalSeat(CardData card, boolean zoneIsP1) {
		return !zoneIsP1 && gameState.isFaceDownInRfp(card);
	}

	/**
	 * Decrements Warp counters on every card in the active player's warp zone at the start
	 * of their Main Phase 1.  Cards whose counter hits 0 are pushed onto the Stack as
	 * auto-abilities and resolved to the field.
	 */
	void processWarpCounters(boolean isP1) {
		List<GameState.WarpEntry> zone = isP1
				? gameState.getP1WarpZone() : gameState.getP2WarpZone();
		if (zone.isEmpty()) return;

		String tag = isP1 ? "Warp: " : "[P2] Warp: ";

		// Push warp-resolve entries FIRST so they sit below the counter-removed auto-ability
		// triggers on the stack; triggers resolve first, then the card enters the field.
		boolean anyResolving = false;
		for (GameState.WarpEntry entry : zone) {
			if (entry.counters - 1 <= 0) {
				gameState.pushStack(StackEntry.forWarpResolve(entry.card, isP1));
				anyResolving = true;
			}
		}

		// Fire counter-removed triggers; these are pushed on top and resolve before the warp-resolve entries.
		for (GameState.WarpEntry entry : zone) {
			int before = entry.counters;
			int after  = before - 1;
			logEntry(tag + "\"" + entry.card.name() + "\" counter " + before + " → " + after
					+ (after <= 0 ? " (resolving!)" : ""));
			autoAbilityTriggers.triggerAutoAbilitiesForWarpCounterRemoved(entry.card, isP1);
		}

		// Decrement all counters; cards that hit 0 are removed from the warp zone here.
		// resolveWarpCard for those cards is now deferred to the warp-resolve stack entries.
		if (isP1) gameState.tickP1WarpCounters(); else gameState.tickP2WarpCounters();

		if (anyResolving) {
			if (isP1) refreshP1BreakLabel(); else refreshP2BreakLabel();
		}
		if (isP1) refreshP1WarpZoneUI(); else refreshP2WarpZoneUI();
	}

	/** Places a card that just had its last Warp counter removed onto the field. */
	void resolveWarpCard(CardData card, boolean isP1) {
		String tag = isP1 ? "Warp: " : "[P2] Warp: ";
		logEntry(tag + "\"" + card.name() + "\" enters play");
		fieldEntryAnimator.placeWithAnim(card, isP1, FieldEntryAnimator.Style.WARP_IN, () -> {
			lastCardWarpedIn = true;
			try {
				if (card.isForward()) {
					if (isP1) { placeCardInForwardZone(card);   p1Turn.forwardEnteredViaWarpThisTurn = true; }
					else       { placeP2CardInForwardZone(card); p2Turn.forwardEnteredViaWarpThisTurn = true; }
				} else if (card.isBackup()) {
					if (isP1) {
						if (hasAvailableBackupSlot()) placeCardInFirstBackupSlot(card);
						else { addToBreakZone(card); logEntry("  No backup slot — \"" + card.name() + "\" → Break Zone"); }
					} else {
						if (hasAvailableP2BackupSlot()) placeP2CardInFirstBackupSlot(card);
						else { addToBreakZone(card); logEntry("  No backup slot — \"" + card.name() + "\" → Break Zone"); }
					}
				} else if (card.isMonster()) {
					if (isP1) placeCardInMonsterZone(card);
					else      placeP2CardInMonsterZone(card);
				}
			} finally {
				lastCardWarpedIn = false;
			}
		});
	}

	/** Shows the "Removed from Play" dialog for the specified player. */
	private void showRemovedFromPlayDialog(String player) {
		boolean isP1 = "P1".equals(player);
		List<GameState.WarpEntry> warpZone = isP1 ? gameState.getP1WarpZone() : gameState.getP2WarpZone();
		List<CardData>            permZone = isP1 ? gameState.getP1PermanentRfp() : gameState.getP2PermanentRfp();
		RemovedFromPlayDialog.show(frame, warpZone, permZone, player,
				card -> isFaceDownToLocalSeat(card, isP1), this::loadCardbackImage,
				this::showZoomAt, this::hideZoom);
	}

	private void showBreakZoneDialog() { showBreakZoneDialog(gameState.getP1BreakZone(), "P1 Break Zone", true); }
	private void showP2BreakZoneDialog() { showBreakZoneDialog(gameState.getP2BreakZone(), "P2 Break Zone", false); }

	private void showBreakZoneDialog(List<CardData> zone, String title, boolean isP1) {
		BreakZoneDialog.show(frame, zone, title, isP1, new BreakZoneDialog.Callbacks() {
			public boolean hasBzAbility(CardData card) {
				return isP1 && card.actionAbilities().stream()
						.anyMatch(a -> a.breakZoneOnly() != null && autoAbilityTriggers.canActivateBzAbility(a, card, true));
			}
			public boolean hasBzPlay(CardData card) {
				return isP1 && bzPlayableP1.containsKey(card)
						&& bzPlayableP1.get(card).source() == PlayableEntry.SourceZone.BREAK_ZONE
						&& !summonCastBlocked(card, true)
						&& !p1CastLimitReached();
			}
			public boolean canAffordBzPlay(CardData card) {
				int cost = bzPlayCost(card);
				if (cost <= 0) return true;
				int backupCp = 0;
				for (int i = 0; i < p1BackupCards.length; i++) {
					if (p1BackupCards[i] != null && p1BackupStates[i] == CardState.ACTIVE && !p1BackupFrozen[i])
						backupCp++;
				}
				return backupCp + gameState.getP1Hand().size() * 2 >= cost;
			}
			public int bzPlayCost(CardData card) {
				return hasBzPlay(card) ? bzPlayableP1.get(card).effectiveCost(card) : -1;
			}
			public boolean isAbilityEnabled(ActionAbility ability, CardData card) {
				return autoAbilityTriggers.canActivateBzAbility(ability, card, true);
			}
			public String abilityMenuHtml(ActionAbility ability) { return buildAbilityMenuLabelHtml(ability); }
			public String abilityMenuText(ActionAbility ability) { return buildAbilityMenuLabel(ability); }
			public void onBzPlay(CardData card, int cost)        { showBzPlayPaymentDialog(card, cost); }
			public void onBzAbility(ActionAbility ability, CardData card) {
				autoAbilityTriggers.showBzAbilityPaymentDialog(ability, card, true);
			}
			public void onZoom(String url) { showZoomAt(url); }
			public void onZoomHide()       { hideZoom(); }
		});
	}

	CardData chooseSummonFromBzDialog(List<CardData> candidates, String element) {
		return BreakZoneDialog.choose(frame, candidates,
				"Choose 1 " + element + " Summon from Break Zone", this::showZoomAt, this::hideZoom);
	}

	CardData chooseCardFromBzDialog(List<CardData> candidates, String title) {
		return BreakZoneDialog.choose(frame, candidates, title, this::showZoomAt, this::hideZoom);
	}


	void triggerGameOver(String reason) {
		gameState.setP1GameOver(true);
		logEntry(reason);
		if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
	}

	/**
	 * Scans the player's backup and forward zones for a card whose field ability reads
	 * "If you receive damage while [cardName] is active, dull [cardName]. The damage becomes 0 instead."
	 *
	 * @param requireActive when {@code true} only {@link CardState#ACTIVE} slots are considered;
	 *                      when {@code false} any non-null slot qualifies.
	 * @return {@code int[]{zone, index}} — zone 0 = backup, zone 1 = forward — or {@code null} if not found.
	 */
	private int[] findPlayerShieldAbilityCard(boolean isP1, boolean requireActive) {
		CardData[] backups = isP1 ? p1BackupCards : p2BackupCards;
		CardState[] bStates = isP1 ? p1BackupStates : p2BackupStates;
		for (int i = 0; i < backups.length; i++) {
			CardData c = backups[i];
			if (c == null) continue;
			if (requireActive && bStates[i] != CardState.ACTIVE) continue;
			for (FieldAbility fa : c.fieldAbilities()) {
				java.util.regex.Matcher m =
					AutoAbilityTriggers.FA_RECV_PLAYER_DAMAGE_ACTIVE_DULL_ZERO.matcher(fa.effectText());
				if (m.find()) return new int[]{0, i};
			}
		}
		List<CardData>  fwds   = isP1 ? p1ForwardCards  : p2ForwardCards;
		List<CardState> fStates = isP1 ? p1ForwardStates : p2ForwardStates;
		for (int i = 0; i < fwds.size(); i++) {
			CardData c = fwds.get(i);
			if (c == null) continue;
			if (requireActive && fStates.get(i) != CardState.ACTIVE) continue;
			for (FieldAbility fa : c.fieldAbilities()) {
				java.util.regex.Matcher m =
					AutoAbilityTriggers.FA_RECV_PLAYER_DAMAGE_ACTIVE_DULL_ZERO.matcher(fa.effectText());
				if (m.find()) return new int[]{1, i};
			}
		}
		return null;
	}

	/** Calls {@link #findPlayerShieldAbilityCard(boolean, boolean)} requiring {@link CardState#ACTIVE}. */
	private int[] findPlayerShieldAbilityCard(boolean isP1) {
		return findPlayerShieldAbilityCard(isP1, true);
	}

	/** Shows or hides the damage-zone shield icon based on whether an active shield-ability backup exists. */
	void refreshPlayerDamageShieldIcon(boolean isP1) {
		ShieldIcon icon = isP1 ? p1ShieldIcon : p2ShieldIcon;
		if (icon == null || icon.isShattering()) return;
		// Re-run doLayout so the shield is positioned over the correct (next undamaged) slot.
		Container parent = icon.getParent();
		if (parent != null) parent.revalidate();
		if (findPlayerShieldAbilityCard(isP1) != null) {
			icon.reset();
		} else {
			// Suppress premature triggerFade when the shield card is a forward mid-dull-animation:
			// the auto-abilities fired on "becomes dull" can trigger refreshes before the animation
			// visually commits, causing the fade to run and complete inside the p2AutoPass window.
			// The explicit triggerShieldFadeForForward call at animation completion handles it instead.
			int[] dulled = findPlayerShieldAbilityCard(isP1, false);
			if (dulled != null && dulled[0] == 1) { // forward zone
				List<CardState> fStates = isP1 ? p1ForwardStates : p2ForwardStates;
				if (fStates.get(dulled[1]) == CardState.DULL) return;
			}
			icon.triggerFade();
		}
	}

	/**
	 * Deals the kickbacks owed by damage-reduction shields spent during the damage that has just
	 * resolved — Cecil 9-109H taking 4000 for the Forward he shielded.
	 *
	 * <p>Drained rather than dealt at the moment the shield is consumed: that happens inside the
	 * incoming-damage calculation, which is arithmetic, and this is damage that can break a Forward
	 * and renumber the zone the caller is still indexing into.
	 *
	 * <p>A bearer that has left the field owes nothing — the damage has nowhere to land.
	 */
	void fireShieldKickbacks() {
		if (pendingShieldKickbacks.isEmpty()) return;
		List<ShieldKickback> owed = new ArrayList<>(pendingShieldKickbacks);
		pendingShieldKickbacks.clear();
		for (ShieldKickback k : owed) {
			List<CardData> fwds = k.bearerIsP1() ? p1ForwardCards : p2ForwardCards;
			int idx = identityIndexOf(fwds, k.bearer());
			if (idx < 0) {
				logEntry(k.bearer().name() + " is no longer on the field — takes no damage for the shield it lent");
				continue;
			}
			logEntry((k.bearerIsP1() ? "" : "[P2] ") + k.bearer().name()
					+ " takes " + k.damage() + " damage for the shield it lent");
			applyDamageToForward(k.bearerIsP1(), idx, k.damage(), true, false);
		}
	}

	/** Triggers the damage-shield fade after the dull animation for forward {@code idx} completes. */
	private void triggerShieldFadeForForward(boolean isP1, int idx) {
		ShieldIcon si = isP1 ? p1ShieldIcon : p2ShieldIcon;
		if (si == null || si.isShattering()) return;
		int[] shld = findPlayerShieldAbilityCard(isP1, false);
		if (shld != null && shld[0] == 1 && shld[1] == idx) si.triggerFade();
	}

	/**
	 * Auron: consumes the redirect companion of a just-consumed next-player-damage shield,
	 * dealing the recorded damage to the named Forward on the shield owner's field. No-op when
	 * the shield had no redirect attached.
	 */
	private void consumeNextDamageZeroRedirect(boolean isP1) {
		String name = turn(isP1).nextDamageZeroRedirectName;
		int    dmg  = turn(isP1).nextDamageZeroRedirectDmg;
		if (isP1) { p1Turn.nextDamageZeroRedirectName = null; p1Turn.nextDamageZeroRedirectDmg = 0; }
		else      { p2Turn.nextDamageZeroRedirectName = null; p2Turn.nextDamageZeroRedirectDmg = 0; }
		if (name == null || dmg <= 0) return;
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		for (int i = 0; i < fwds.size(); i++) {
			if (fwds.get(i).name().equalsIgnoreCase(name)) {
				logEntry((isP1 ? "" : "[P2] ") + name + " takes " + dmg + " damage instead");
				buildGameContext(isP1).damageTarget(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD), dmg);
				return;
			}
		}
		logEntry(name + " is not on the field — redirected damage not dealt");
	}

	/**
	 * Credits the next single point of player damage to {@code source}, so a source-scoped EX Burst
	 * suppression can be applied when the damage card is revealed.  Set immediately before each
	 * {@link #p1TakeDamage}/{@link #p2TakeDamage} call; each call consumes it.
	 */
	void setPlayerDamageSource(CardData source) { playerDamageSource = source; }

	/**
	 * Returns the first Forward among {@code attackerIndices} carrying any source-scoped EX Burst
	 * suppression, or {@code null} if none does.  An unblocked party deals its single point of
	 * damage collectively, so one suppressing member is enough to credit the damage to it.
	 */
	CardData partyExBurstSuppressor(List<Integer> attackerIndices, boolean attackersAreP1) {
		List<CardData> fwds = attackersAreP1 ? p1ForwardCards : p2ForwardCards;
		for (int idx : attackerIndices) {
			if (idx < 0 || idx >= fwds.size()) continue;
			if (exBurstSuppressionCostCap(fwds.get(idx)) != null) return fwds.get(idx);
		}
		return null;
	}

	/**
	 * Consumes the pending damage source and returns it, so the take-damage methods can evaluate
	 * suppression against the card they are about to reveal.
	 *
	 * <p>Consumed at the top of those methods rather than inside their reveal timers: the timer
	 * fires long after the caller has moved on and set the source for the next point.
	 */
	CardData consumePlayerDamageSource() {
		CardData source = playerDamageSource;
		playerDamageSource = null;
		return source;
	}

	/**
	 * Returns the highest card cost whose EX Burst {@code source} suppresses, or {@code null} when
	 * it carries no such ability.  Covers both the until-end-of-turn grant (Shadow Lord 12-071R)
	 * and the printed field ability (Exdeath 1-122H, Arborous Simulacrum 2-118C, Shadow Lord B-007).
	 */
	private Integer exBurstSuppressionCostCap(CardData source) {
		if (source == null) return null;
		Integer granted = exBurstSuppressingSources.get(source);
		if (granted != null) return granted;
		if (lostAbilitiesCards.contains(source)) return null;
		for (FieldAbility fa : source.fieldAbilities()) {
			Integer cap = ActionResolver.exBurstSuppressionMaxCost(fa.effectText(), source.name());
			if (cap != null) return cap;
		}
		return null;
	}

	/**
	 * True when {@code revealed} may not use its EX Burst because the damage that put it into the
	 * Damage Zone was credited to {@code source}.  Arborous Simulacrum 2-118C only suppresses
	 * cards of cost 2 or less, so the revealed card's cost is part of the test.
	 */
	boolean exBurstSuppressedBy(CardData source, CardData revealed) {
		if (source == null || revealed == null) return false;
		Integer cap = exBurstSuppressionCostCap(source);
		if (cap == null || revealed.cost() > cap) return false;
		logEntry(revealed.name() + " — EX Burst cannot be used (damage dealt by " + source.name() + ")");
		return true;
	}

	void p1TakeDamage() { p1TakeDamage(null); }

	void p1TakeDamage(Runnable onDone) {
		final CardData dmgSource       = consumePlayerDamageSource();
		final boolean  abilitySuppress = suppressExBurstsThisAbility;
		if (gameState.isP1GameOver()) { if (onDone != null) onDone.run(); return; }
		if (p1Turn.nextDamageZero) {
			p1Turn.nextDamageZero = false;
			logEntry("P1 damage negated (shield active).");
			if (p1ShieldIcon != null) p1ShieldIcon.triggerShatter();
			consumeNextDamageZeroRedirect(true);
			if (onDone != null) onDone.run();
			return;
		}
		int[] shieldCard = findPlayerShieldAbilityCard(true);
		if (shieldCard != null) {
			int zone = shieldCard[0], i = shieldCard[1];
			if (p1ShieldIcon != null) p1ShieldIcon.triggerShatter();
			if (zone == 0) {
				logEntry(p1BackupCards[i].name() + " dulled — P1 damage negated.");
				p1BackupStates[i] = CardState.DULL;
				animateDullBackup(i, true);
				refreshP1BackupSlot(i);
			} else {
				logEntry(p1ForwardCards.get(i).name() + " dulled — P1 damage negated.");
				p1ForwardStates.set(i, CardState.DULL);
				animateDullForward(i, null);
				autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(p1ForwardCards.get(i), true);
			}
			if (onDone != null) onDone.run();
			return;
		}
		p1Turn.receivedDamageThisTurn = true;
		CardData drawn = gameState.drawToDamageZone();
		if (drawn == null) {
			triggerGameOver("P1 milled out — You Lose!");
			return;
		}
		int idx = gameState.getP1DamageZone().size() - 1;
		boolean isEx = drawn.exBurst();

		refreshP1DeckLabel();
		logEntry("P1 takes 1 damage — " + drawn.name() + (isEx ? " [EX BURST!]" : ""));
		autoAbilityTriggers.triggerAutoAbilitiesForDamageZone(true);
		autoAbilityTriggers.triggerAutoAbilitiesForEitherPlayerReceivesDamage();
		autoAbilityTriggers.triggerAutoAbilitiesForYouReceiveDamage(true);
		fireFieldSelfDamagePointsAbilities(true);
		animateCardToDamage(true, idx);

		int animDelay = CardSlideAnimator.TOTAL_FRAMES * CardSlideAnimator.FRAME_MS;
		// An EX Burst on the drawn card does not reach the stack until this reveal fires, so without
		// marking it pending the board looks settled and turn flow runs past a burst still owed.
		turnFlowGate.beginPendingTrigger();
		Timer revealTimer = new Timer(animDelay, e -> {
			try {
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
				if (isEx && !abilitySuppress && !exBurstSuppressedBy(dmgSource, drawn))
					autoAbilityTriggers.triggerExBurst(drawn, true);
				if (onDone != null) onDone.run();
			} finally {
				turnFlowGate.endPendingTrigger();
			}
		});
		revealTimer.setRepeats(false);
		revealTimer.start();
	}

	void p2TakeDamage() { p2TakeDamage(null); }

	void p2TakeDamage(Runnable onDone) {
		final CardData dmgSource       = consumePlayerDamageSource();
		final boolean  abilitySuppress = suppressExBurstsThisAbility;
		if (p2Turn.nextDamageZero) {
			p2Turn.nextDamageZero = false;
			logEntry("[P2] damage negated (shield active).");
			if (p2ShieldIcon != null) p2ShieldIcon.triggerShatter();
			consumeNextDamageZeroRedirect(false);
			if (onDone != null) onDone.run();
			return;
		}
		int[] p2ShieldCard = findPlayerShieldAbilityCard(false);
		if (p2ShieldCard != null) {
			int zone = p2ShieldCard[0], i = p2ShieldCard[1];
			if (p2ShieldIcon != null) p2ShieldIcon.triggerShatter();
			if (zone == 0) {
				logEntry("[P2] " + p2BackupCards[i].name() + " dulled — P2 damage negated.");
				p2BackupStates[i] = CardState.DULL;
				refreshP2BackupSlot(i);
			} else {
				logEntry("[P2] " + p2ForwardCards.get(i).name() + " dulled — P2 damage negated.");
				CardState p2ShieldFwdBefore = p2ForwardStates.get(i);
				p2ForwardStates.set(i, CardState.DULL);
				animateDullP2Forward(i, null);
				if (p2ShieldFwdBefore == CardState.ACTIVE)
					autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(p2ForwardCards.get(i), false);
			}
			if (onDone != null) onDone.run();
			return;
		}
		p2Turn.receivedDamageThisTurn = true;
		CardData drawn = gameState.drawToP2DamageZone();
		if (drawn == null) {
			// Deck is empty — P2 cannot flip a card into their Damage Zone, so they lose immediately
			triggerGameOver("Player 2 milled out - You Win!");
			return;
		}
		p2DamageCount++;
		boolean isEx = drawn != null && drawn.exBurst();
		String cardInfo = drawn != null ? " — " + drawn.name() + (isEx ? " [EX BURST!]" : "") : "";
		logEntry("P2 takes 1 damage (" + p2DamageCount + "/7)" + cardInfo);
		autoAbilityTriggers.triggerAutoAbilitiesForDamageZone(false);
		autoAbilityTriggers.triggerAutoAbilitiesForEitherPlayerReceivesDamage();
		autoAbilityTriggers.triggerAutoAbilitiesForYouReceiveDamage(false);
		fireFieldSelfDamagePointsAbilities(false);

		int slotIdx = p2DamageCount - 1;
		if (drawn != null) animateCardToDamage(false, slotIdx);

		refreshP2DeckLabel();

		int animDelay = CardSlideAnimator.TOTAL_FRAMES * CardSlideAnimator.FRAME_MS;
		// See p1TakeDamage: the EX Burst is owed but unstacked until this reveal fires.
		turnFlowGate.beginPendingTrigger();
		Timer revealTimer = new Timer(animDelay, e -> {
			try {
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
				if (isEx && drawn != null && !abilitySuppress && !exBurstSuppressedBy(dmgSource, drawn))
					autoAbilityTriggers.triggerExBurst(drawn, false);
				if (onDone != null) onDone.run();
			} finally {
				turnFlowGate.endPendingTrigger();
			}
		});
		revealTimer.setRepeats(false);
		revealTimer.start();
	}

	// -------------------------------------------------------------------------
	// Combat: breaking forwards
	// -------------------------------------------------------------------------

	/**
	 * Drops every piece of slot-indexed state for the P1 Forward leaving position {@code idx}: each
	 * parallel per-slot list loses its entry, and each index-keyed set/map is re-indexed so entries
	 * above the hole move down with the Forwards they describe.
	 *
	 * <p><strong>Every</strong> path that removes a P1 Forward must call this — a combat break, a
	 * bounce to hand or deck, the uniqueness rule, a transfer to the other side.  These collections
	 * are only meaningful as a set: a path that updates some but not others leaves the survivors'
	 * indexes pointing at the wrong Forward, which surfaces as the wrong card being flagged unable
	 * to attack or block, or as a second attack granted to a Forward that never attacked.  Keeping
	 * the list in one place is the point — six hand-maintained copies had already drifted apart.
	 *
	 * <p>Callers rebuild the panel themselves afterwards, since some of them place the card
	 * elsewhere first and want a single relayout.
	 */
	private void removeP1ForwardSlotState(int idx) {
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
		// Every combat restriction, attacker- and defender-side, is keyed by card instance and so
		// needs no renumbering here; clearCombatRestrictionsFor drops them when the card departs.
	}

	/** P2's counterpart to {@link #removeP1ForwardSlotState(int)}; the same contract applies. */
	private void removeP2ForwardSlotState(int idx) {
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
		// See removeP1ForwardSlotState: the combat-restriction sets are keyed by instance.
	}

	void breakP1Forward(int idx) { p1ForwardToBreakZone(idx, true); }

	/**
	 * Puts P1's Forward at {@code idx} into the Break Zone <em>without</em> breaking it — the cost
	 * wording "put a total of 3 Forwards or Monsters you control into the Break Zone" (Kefka
	 * 4-080L).
	 *
	 * <p>A put is not a break; it is only the move from one zone to the other. Everything that
	 * watches a card <em>leave the field</em> still fires, because it did: the "put into break
	 * zone" and "leaves the field" auto-abilities, {@code forwardsLeftFieldThisTurn}, and the
	 * {@code forwardPutToBZThisTurn} flag Nox Suzaku 15-130H reads — that one is worded "put from
	 * the field into the Break Zone" precisely because it covers both routes. What a put must not
	 * feed are the trackers that answer "was a Forward <em>broken</em> this turn"
	 * ({@code turnOpponentFwdBroken} and the broken job/element/category sets), and that is the
	 * whole of the difference below.
	 */
	void putP1ForwardIntoBreakZone(int idx) { p1ForwardToBreakZone(idx, false); }

	/** Shared body of {@link #breakP1Forward} and {@link #putP1ForwardIntoBreakZone}. */
	private void p1ForwardToBreakZone(int idx, boolean isBreak) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		startBreakAnim(p1ForwardLabels.get(idx));
		CardData card    = p1ForwardCards.get(idx);
		boolean  hadGrants      = !card.fieldPowerGrants().isEmpty();
		boolean  hadCostReduces = !card.fieldCostReductions().isEmpty() || p1HandHasSelfCostModifiers();
		CardData topCard = p1ForwardPrimedTop.get(idx);
		Set<CardData> partySnapshot = Collections.emptySet();
		if (p1AttackSelection.contains(idx)) {
			partySnapshot = new HashSet<>();
			for (int i : p1AttackSelection) {
				if (i >= 0 && i < p1ForwardCards.size()) partySnapshot.add(p1ForwardCards.get(i));
			}
		}

		if (topCard != null) {
			// Primed: both cards move to break zone, then top card is immediately RFP'd
			addToBreakZone(card, true);
			addToBreakZone(topCard);
			logEntry(card.name() + " + " + topCard.name() + " → Break Zone (Primed)");
			gameState.getP1BreakZone().remove(topCard);
			gameState.addToPermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Play");
		} else {
			addToBreakZone(card, true);
			logEntry(card.name() + " → Break Zone");
		}

		removeP1ForwardSlotState(idx);

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
				lbl.setFont(FontLoader.loadPixelFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						if (SwingUtilities.isLeftMouseButton(e)
								&& p1ForwardClickSelectsCombat()) {
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
		if (hadCostReduces) refreshHandCardStates();
		if (isBreak) {
			p2Turn.turnOpponentFwdBroken = true;
			for (String j : card.jobs()) p1Turn.brokenJobsThisTurn.add(j.toLowerCase());
			if (card.element() != null && !card.element().isBlank()) p1Turn.brokenElementsThisTurn.add(card.element().toLowerCase());
			if (card.category1() != null && !card.category1().isBlank()) p1Turn.brokenCategoriesThisTurn.add(card.category1().toLowerCase());
			if (card.category2() != null && !card.category2().isBlank()) p1Turn.brokenCategoriesThisTurn.add(card.category2().toLowerCase());
		}
		if (gameState.getCurrentPlayer() == GameState.Player.P1) p1Turn.forwardsLeftFieldThisTurn++;
		else p2Turn.forwardsLeftFieldThisTurn++;
		p1Turn.forwardPutToBZThisTurn = true;
		// If the broken card was itself stolen from P2, drop its tracking entry
		stolenForwards.remove(card);
		// Restore any forwards that were conditioned on this card remaining on the field
		checkAndRestoreStolenOnLeave(card.name());

		syncBzForwardPlayables(true);
		refreshP1BreakLabel();
		if (topCard != null) refreshP1WarpZoneUI();
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, true);
		autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(card, true, partySnapshot);
	}

	/** Removes P2's forward at {@code idx} from the field and sends it to P2's Break Zone. */
	void breakP2Forward(int idx) { p2ForwardToBreakZone(idx, true); }

	/** P2's side of {@link #putP1ForwardIntoBreakZone} — a put, not a break. */
	void putP2ForwardIntoBreakZone(int idx) { p2ForwardToBreakZone(idx, false); }

	/** Shared body of {@link #breakP2Forward} and {@link #putP2ForwardIntoBreakZone}. */
	private void p2ForwardToBreakZone(int idx, boolean isBreak) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		startBreakAnim(p2ForwardLabels.get(idx));
		CardData card    = p2ForwardCards.get(idx);
		boolean hadGrants      = !card.fieldPowerGrants().isEmpty();
		boolean hadCostReduces = !card.fieldCostReductions().isEmpty() || p1HandHasSelfCostModifiers();
		CardData topCard = p2ForwardPrimedTop.get(idx);
		Set<CardData> partySnapshot = Collections.emptySet();
		if (pendingP2PartyIndices != null && pendingP2PartyIndices.contains(idx)) {
			partySnapshot = new HashSet<>();
			for (int i : pendingP2PartyIndices) {
				if (i >= 0 && i < p2ForwardCards.size()) partySnapshot.add(p2ForwardCards.get(i));
			}
		}

		if (topCard != null) {
			addToBreakZone(card, true);
			addToBreakZone(topCard);
			logEntry("[P2] " + card.name() + " + " + topCard.name() + " → Break Zone (Primed)");
			gameState.getP2BreakZone().remove(topCard);
			gameState.addToPermanentRfp(topCard);
			logEntry("[P2] " + topCard.name() + " → Removed From Play");
		} else {
			addToBreakZone(card, true);
			logEntry("[P2] " + card.name() + " → Break Zone");
		}

		removeP2ForwardSlotState(idx);

		if (p2ForwardPanel != null) {
			p2ForwardPanel.removeAll();
			p2ForwardLabels.clear();
			for (int i = 0; i < p2ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setFont(FontLoader.loadPixelFont(11));
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
		if (hadCostReduces) refreshHandCardStates();
		if (isBreak) {
			p1Turn.turnOpponentFwdBroken = true;
			for (String j : card.jobs()) p2Turn.brokenJobsThisTurn.add(j.toLowerCase());
			if (card.element() != null && !card.element().isBlank()) p2Turn.brokenElementsThisTurn.add(card.element().toLowerCase());
			if (card.category1() != null && !card.category1().isBlank()) p2Turn.brokenCategoriesThisTurn.add(card.category1().toLowerCase());
			if (card.category2() != null && !card.category2().isBlank()) p2Turn.brokenCategoriesThisTurn.add(card.category2().toLowerCase());
		}
		if (gameState.getCurrentPlayer() == GameState.Player.P1) p1Turn.forwardsLeftFieldThisTurn++;
		else p2Turn.forwardsLeftFieldThisTurn++;
		p2Turn.forwardPutToBZThisTurn = true;
		syncBzForwardPlayables(false);
		refreshP2BreakLabel();
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, false);
		autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(card, false, partySnapshot);
		if (topCard != null) autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(topCard, false, Collections.emptySet());
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
			lbl.setFont(FontLoader.loadPixelFont(11));
			lbl.setBorder(BorderFactory.createEmptyBorder());
			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					if (lbl.getIcon() == null) return;
					if (SwingUtilities.isLeftMouseButton(e)
							&& p1ForwardClickSelectsCombat()) {
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
			lbl.setFont(FontLoader.loadPixelFont(11));
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

	/** Identity (not {@code equals}) index of {@code card} in {@code list}; two copies of a card are distinct. */
	static int identityIndexOf(List<CardData> list, CardData card) {
		for (int i = 0; i < list.size(); i++) if (list.get(i) == card) return i;
		return -1;
	}

	/**
	 * Strips the Forward slot at {@code idx} out of {@code isP1}'s parallel per-slot lists and
	 * rebuilds that side's panel. The card goes nowhere — callers place it themselves — so no zone
	 * change happens and no auto-ability fires.
	 */
	private void removeForwardSlotForTransfer(boolean isP1, int idx) {
		if (isP1) {
			removeP1ForwardSlotState(idx);
			rebuildP1ForwardPanel();
		} else {
			removeP2ForwardSlotState(idx);
			rebuildP2ForwardPanel();
		}
	}

	/**
	 * Hands {@code card} across the table: it leaves whichever field it is on and joins the other
	 * player's Forward zone, keeping its accumulated damage and its state (unless {@code forceState}
	 * overrides it). Control changes are not zone changes, so no ETF, leave-field or break
	 * auto-abilities fire — the one exception is the uniqueness rule, where a non-multicard meeting
	 * another copy of itself sends both to their owners' Break Zones instead.
	 *
	 * <p>The direction is implied by where the card currently sits, which is what every caller
	 * wants: stealing takes it from the opponent, giving hands it over, and restoring sends a
	 * stolen card back the way it came.
	 *
	 * @return {@code true} if the card changed sides
	 */
	private boolean transferForwardControl(CardData card, CardState forceState) {
		int  idx    = identityIndexOf(p1ForwardCards, card);
		boolean fromP1 = idx >= 0;
		if (!fromP1) {
			idx = identityIndexOf(p2ForwardCards, card);
			if (idx < 0) {
				logEntry(card.name() + " — not currently a Forward on the field, cannot transfer control");
				return false;
			}
		}

		// Uniqueness rule: a non-multicard cannot coexist with another copy of itself.
		List<CardData> destCards = fromP1 ? p2ForwardCards : p1ForwardCards;
		if (!card.multicard()) {
			for (int i = 0; i < destCards.size(); i++) {
				if (destCards.get(i).name().equalsIgnoreCase(card.name())) {
					logEntry(card.name() + " — uniqueness rule: both copies sent to their owner's Break Zone");
					// Break the arriving copy first so the resident copy's index stays valid.
					if (fromP1) { breakP1Forward(idx); breakP2Forward(i); }
					else        { breakP2Forward(idx); breakP1Forward(i); }
					return false;
				}
			}
		}

		int       savedDmg   = (fromP1 ? p1ForwardDamage : p2ForwardDamage).get(idx);
		CardState savedState = (fromP1 ? p1ForwardStates : p2ForwardStates).get(idx);
		removeForwardSlotForTransfer(fromP1, idx);
		CardState arrivalState = forceState != null ? forceState : savedState;
		if (fromP1) addStolenForwardToP2Field(card, savedDmg, arrivalState);
		else        addStolenForwardToP1Field(card, savedDmg, arrivalState);
		return true;
	}

	/**
	 * Moves the Forward at {@code victimIdx} on the opponent's field to {@code thiefIsP1}'s field
	 * and registers the restoration condition in {@link #stolenForwards}. Works in either
	 * direction — {@code thiefIsP1} names the player gaining control.
	 */
	void stealForwardControl(boolean thiefIsP1, int victimIdx, String condition, boolean activate) {
		List<CardData> victimCards = thiefIsP1 ? p2ForwardCards : p1ForwardCards;
		if (victimIdx < 0 || victimIdx >= victimCards.size()) return;
		CardData card = victimCards.get(victimIdx);

		if (!transferForwardControl(card, activate ? CardState.ACTIVE : null)) return;

		String condLabel = condition.equals("permanent") ? " (permanent)"
				: condition.equals("endOfTurn") ? " (until EOT)"
				: " (while " + condition.substring("whileCardOnField:".length()) + " on field)";
		logEntry(card.name() + " — control stolen by " + (thiefIsP1 ? "P1" : "P2") + condLabel);

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
		lbl.setFont(FontLoader.loadPixelFont(11));
		lbl.setBorder(BorderFactory.createEmptyBorder());
		lbl.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				if (lbl.getIcon() == null) return;
				if (SwingUtilities.isLeftMouseButton(e)
						&& p1ForwardClickSelectsCombat()) {
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
		p1ForwardTempTraits.add(EnumSet.noneOf(CardData.Trait.class));
		p1ForwardRemovedTraits.add(EnumSet.noneOf(CardData.Trait.class));
		p1ForwardTempJobs.add(null);
		p1ForwardPrimedTop.add(null);
		p1ForwardFrozen.add(false);
		p1ForwardLabels.add(lbl);

		p1ForwardPanel.add(lbl);
		p1ForwardPanel.revalidate();
		p1ForwardPanel.repaint();
		refreshP1ForwardSlot(idx);
		if (!card.fieldPowerGrants().isEmpty()) refreshFieldGrantDependents(true);
		if (!card.fieldCostReductions().isEmpty() || p1HandHasSelfCostModifiers()) refreshHandCardStates();
	}

	/** Adds {@code card} to P2's forward zone with the given damage and state; does NOT fire ETF. */
	private void addStolenForwardToP2Field(CardData card, int damage, CardState state) {
		if (p2ForwardPanel == null) return;
		int idx = p2ForwardLabels.size();

		JLabel lbl = new JLabel("", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
		lbl.setOpaque(false);
		lbl.setFont(FontLoader.loadPixelFont(11));
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
		p2ForwardStates.add(state);
		p2ForwardPlayedOnTurn.add(gameState.getTurnNumber());
		p2ForwardDamage.add(damage);
		p2ForwardPowerBoost.add(0);
		p2ForwardPowerReduction.add(0);
		p2ForwardTempTraits.add(EnumSet.noneOf(CardData.Trait.class));
		p2ForwardRemovedTraits.add(EnumSet.noneOf(CardData.Trait.class));
		p2ForwardTempJobs.add(null);
		p2ForwardPrimedTop.add(null);
		p2ForwardFrozen.add(false);
		p2ForwardLabels.add(lbl);

		p2ForwardPanel.add(lbl);
		p2ForwardPanel.revalidate();
		p2ForwardPanel.repaint();
		refreshP2ForwardSlot(idx);
		if (!card.fieldPowerGrants().isEmpty()) refreshFieldGrantDependents(false);
		if (!card.fieldCostReductions().isEmpty() || p1HandHasSelfCostModifiers()) refreshHandCardStates();
	}

	/**
	 * Hands a stolen forward back to the player it was taken from, keeping its current damage and
	 * state. If the card has already left the field, this is a no-op except for a log entry.
	 */
	private void restoreStolenForward(CardData card) {
		boolean onP1 = identityIndexOf(p1ForwardCards, card) >= 0;
		boolean onP2 = identityIndexOf(p2ForwardCards, card) >= 0;
		if (!onP1 && !onP2) {
			logEntry(card.name() + " — already left field, control restored implicitly");
			return;
		}
		if (transferForwardControl(card, null))
			logEntry(card.name() + " — control returned to " + (onP1 ? "P2" : "P1"));
	}

	/**
	 * Permanently moves {@code source} (currently a Forward on either side's field) to the
	 * opposing player's forward zone — the reverse direction of {@link #stealForwardFromP2ToP1}.
	 * Used for "your opponent gains control of [CardName]" auto-abilities (e.g. Leon). Preserves
	 * accumulated damage and current state; no ETF or leave/break auto-abilities fire, except
	 * when the uniqueness rule sends both copies to their owners' Break Zones instead.
	 * No-op (with a log entry) if {@code source} is not currently a Forward on either field.
	 */
	void giveForwardControlToOpponent(CardData source) {
		boolean wasP1 = identityIndexOf(p1ForwardCards, source) >= 0;
		if (transferForwardControl(source, null))
			logEntry(source.name() + " — control given to opponent (" + (wasP1 ? "P2" : "P1") + ")");
	}

	/**
	 * Hands {@code source} to its controller's opponent, whichever row it stands in.
	 *
	 * <p>The Forward row's transfer is the older of the two and logs its own failure, so the row
	 * is settled here rather than by trying one and falling through to the other.
	 */
	void giveControlToOpponent(CardData source) {
		if (identityIndexOf(p1ForwardCards, source) >= 0 || identityIndexOf(p2ForwardCards, source) >= 0)
			giveForwardControlToOpponent(source);
		else
			giveBackupControlToOpponent(source);
	}

	/**
	 * The Backup row's {@link #giveForwardControlToOpponent} — Leslie 16-084R, who offers herself
	 * to the opponent at the end of each of her controller's turns.
	 *
	 * <p>A control change is not a zone change, so the card keeps its state and its temporary
	 * grants; what moves is which side's row it stands in. The grant maps are per side, so the
	 * entries are carried across rather than left behind pointing at a card that is no longer
	 * there.
	 *
	 * <p>Fails, with a log entry and no move, when the opponent's five Backup slots are full:
	 * there is nowhere for the card to stand, and a transfer that dropped it would be a silent
	 * removal from the game.
	 *
	 * @return whether control actually changed
	 */
	boolean giveBackupControlToOpponent(CardData source) {
		int fromIdx = identityIndexOfSlot(p1BackupCards, source);
		boolean fromP1 = fromIdx >= 0;
		if (!fromP1) {
			fromIdx = identityIndexOfSlot(p2BackupCards, source);
			if (fromIdx < 0) {
				logEntry(source.name() + " — not currently a Backup on the field, cannot transfer control");
				return false;
			}
		}
		CardData[]  toCards  = fromP1 ? p2BackupCards  : p1BackupCards;
		int toIdx = -1;
		for (int i = 0; i < toCards.length; i++) if (toCards[i] == null) { toIdx = i; break; }
		if (toIdx < 0) {
			logEntry(source.name() + " — opponent's Backup slots are full, control cannot be given");
			return false;
		}

		String[]    fromUrls   = fromP1 ? p1BackupUrls   : p2BackupUrls;
		CardData[]  fromCards  = fromP1 ? p1BackupCards  : p2BackupCards;
		CardState[] fromStates = fromP1 ? p1BackupStates : p2BackupStates;
		boolean[]   fromFrozen = fromP1 ? p1BackupFrozen : p2BackupFrozen;
		String[]    toUrls     = fromP1 ? p2BackupUrls   : p1BackupUrls;
		CardState[] toStates   = fromP1 ? p2BackupStates : p1BackupStates;
		boolean[]   toFrozen   = fromP1 ? p2BackupFrozen : p1BackupFrozen;
		JLabel[]    fromLabels = fromP1 ? p1BackupLabels : p2BackupLabels;

		toUrls[toIdx]   = fromUrls[fromIdx];
		toCards[toIdx]  = source;
		toStates[toIdx] = fromStates[fromIdx];
		toFrozen[toIdx] = fromFrozen[fromIdx];

		fromUrls[fromIdx]   = null;
		fromCards[fromIdx]  = null;
		fromStates[fromIdx] = CardState.ACTIVE;
		fromFrozen[fromIdx] = false;
		if (fromLabels[fromIdx] != null) {
			fromLabels[fromIdx].setIcon(null);
			fromLabels[fromIdx].setText(null);
		}
		if (fromP1 && p1BackupAttackIdx == fromIdx) p1BackupAttackIdx = -1;

		moveBackupGrants(source, fromP1);
		if (fromP1) { refreshP1BackupSlot(fromIdx); refreshP2BackupSlot(toIdx); }
		else        { refreshP2BackupSlot(fromIdx); refreshP1BackupSlot(toIdx); }
		logEntry(source.name() + " — control given to opponent (" + (fromP1 ? "P2" : "P1") + ")");
		// Checked after the move, like every other arrival: the rule is about what the receiving
		// player now controls, and both copies go if they already had one.
		sendToBreakZoneByUniquenessRule(source, !fromP1);
		return true;
	}

	/** {@link #identityIndexOf} over a slot array, whose empty slots are null. */
	private static int identityIndexOfSlot(CardData[] slots, CardData card) {
		for (int i = 0; i < slots.length; i++) if (slots[i] == card) return i;
		return -1;
	}

	/** Moves {@code card}'s Backup-row grant entries from {@code fromP1}'s maps to the other side's. */
	private void moveBackupGrants(CardData card, boolean fromP1) {
		moveGrant(fromP1 ? p1BackupTempForwardPower : p2BackupTempForwardPower,
		          fromP1 ? p2BackupTempForwardPower : p1BackupTempForwardPower, card);
		moveGrant(fromP1 ? p1BackupForwardBoost : p2BackupForwardBoost,
		          fromP1 ? p2BackupForwardBoost : p1BackupForwardBoost, card);
		moveGrant(fromP1 ? p1BackupForwardDamage : p2BackupForwardDamage,
		          fromP1 ? p2BackupForwardDamage : p1BackupForwardDamage, card);
		moveGrant(fromP1 ? p1BackupTempTraits : p2BackupTempTraits,
		          fromP1 ? p2BackupTempTraits : p1BackupTempTraits, card);
	}

	private static <V> void moveGrant(Map<CardData, V> from, Map<CardData, V> to, CardData card) {
		V v = from.remove(card);
		if (v != null) to.put(card, v);
	}

	/**
	 * Necron: when {@code departing} leaves the field, any cards it removed "for as long as
	 * [departing] is on the field" re-enter their owner's field. Entries already moved to the
	 * Break Zone by the watcher's action ability were deleted from the map and stay put.
	 * Called from {@link AutoAbilityTriggers#triggerAutoAbilitiesForLeavesField} so every
	 * leave-field path is covered.
	 */
	/**
	 * Drops {@code departing} from every per-turn combat restriction and compulsion.
	 *
	 * <p>Called from {@link AutoAbilityTriggers#triggerAutoAbilitiesForLeavesField} alongside the
	 * other per-card teardown, so every leave-field path is covered whatever row the card sat in.
	 *
	 * <p>This is what keeps instance keying honest. The same {@link CardData} goes to the Break
	 * Zone and comes back when it is replayed, so without this a Forward restricted this turn,
	 * broken, and replayed from the Break Zone would return still restricted — where the rules
	 * treat a card changing zones as a new object with none of its old state.
	 */
	void clearCombatRestrictionsFor(CardData departing) {
		p1CannotBlock.remove(departing);            p2CannotBlock.remove(departing);
		p1MustBlock.remove(departing);              p2MustBlock.remove(departing);
		p1CannotAttack.remove(departing);           p2CannotAttack.remove(departing);
		p1MustAttack.remove(departing);             p2MustAttack.remove(departing);
		p1CannotAttackPersistent.remove(departing); p2CannotAttackPersistent.remove(departing);
		p1CannotBlockPersistent.remove(departing);  p2CannotBlockPersistent.remove(departing);
		p1CannotBeBlocked.remove(departing);        p2CannotBeBlocked.remove(departing);
		p1CannotBeBlockedByCost.remove(departing);  p2CannotBeBlockedByCost.remove(departing);
		p1CannotBeBlockedByPower.remove(departing); p2CannotBeBlockedByPower.remove(departing);
		cannotUseActionAbilitiesThisTurn.remove(departing);
	}

	/**
	 * Records that {@code source} dealt damage to {@code damaged} this turn, so the
	 * "[a Forward] damaged by [source] …" printings can find it when the damaged card leaves the
	 * field. Called at every point damage actually lands, ahead of the break check, because the
	 * blow that records the source is usually the one that kills.
	 */
	void recordDamagedBy(CardData damaged, CardData source) {
		if (damaged == null || source == null || damaged == source) return;
		damagedBySourcesThisTurn
				.computeIfAbsent(damaged, k -> Collections.newSetFromMap(new IdentityHashMap<>()))
				.add(source);
	}

	/** Whether {@code source} is recorded as having damaged {@code damaged} this turn. */
	boolean wasDamagedBy(CardData damaged, CardData source) {
		Set<CardData> sources = damagedBySourcesThisTurn.get(damaged);
		return sources != null && sources.contains(source);
	}

	/**
	 * Forgets every damage {@code card} took or dealt before now. Called as a card is seated on the
	 * field: a card that changed zones is a new object, and neither the damage its previous
	 * incarnation took this turn nor the damage it dealt is its own.
	 *
	 * <p>Arrival is the moment for this, not departure. Unlike the combat restrictions
	 * {@link #clearCombatRestrictionsFor} drops on the way out, this record exists precisely to be
	 * read <em>as</em> a card leaves the field — both by {@code addToBreakZone}'s remove-from-game
	 * replacement and by the break-zone triggers that fire after it — so tearing it down on the way
	 * out would delete it a step before its only readers run.
	 */
	void forgetDamageRecordFor(CardData card) {
		damagedBySourcesThisTurn.remove(card);
		for (Set<CardData> sources : damagedBySourcesThisTurn.values()) sources.remove(card);
	}

	/**
	 * Whether some card still on the field damaged {@code card} this turn and carries
	 * "If a Forward damaged by [self] is put from the field into the Break Zone on the same turn,
	 * remove it from the game instead." (Susano, Lord of the Revel 14-011H).
	 *
	 * <p>The damager has to still be on the field: the replacement is a continuous ability, and a
	 * Susano that has already left takes it with him. Read through
	 * {@link #effectiveFieldAbilities}-free {@code hasDamagedBySelfFieldToBzRfg} on the printed
	 * abilities, with the usual lost-abilities suppression applied here.
	 */
	private boolean damagerRemovesFromGameInstead(CardData card) {
		Set<CardData> sources = damagedBySourcesThisTurn.get(card);
		if (sources == null || sources.isEmpty()) return false;
		for (CardData src : sources) {
			if (lostAbilitiesCards.contains(src)) continue;
			if (!cardIsOnField(src)) continue;
			if (AutoAbilityTriggers.hasDamagedBySelfFieldToBzRfg(src)) return true;
		}
		return false;
	}

	/** Whether {@code card} currently occupies a Forward, Backup or Monster slot on either field. */
	private boolean cardIsOnField(CardData card) {
		return identityIndexOf(p1ForwardCards, card) >= 0 || identityIndexOf(p2ForwardCards, card) >= 0
			|| identityIndexOf(p1MonsterCards, card) >= 0 || identityIndexOf(p2MonsterCards, card) >= 0
			|| identityIndexOf(Arrays.asList(p1BackupCards), card) >= 0
			|| identityIndexOf(Arrays.asList(p2BackupCards), card) >= 0;
	}

	/**
	 * Withdraws every "As long as [departing] is on the field, it gains ..." grant {@code departing}
	 * was sustaining, as it leaves the field.
	 *
	 * <p>Only the delta each grant contributed is taken back -- see {@link WardenHeldGrant} -- so a
	 * Forward that also holds a permanent boost or shield from elsewhere keeps it. Power is exact
	 * either way, being additive; the trait and shield stores are sets, so a second source that
	 * granted the same trait after this one is the case the subtraction cannot tell apart, and it
	 * loses the trait a turn early. No printed pair does that today.
	 *
	 * <p>The caller re-runs the slot refresh and the break-rule sweep straight after, which is what
	 * settles a Forward the withdrawn power has left at or below its accumulated damage.
	 */
	void revokeWardenHeldGrantsOnLeave(CardData departing) {
		if (wardenHeldGrants.isEmpty()) return;
		List<WardenHeldGrant> ended = new ArrayList<>();
		for (WardenHeldGrant g : wardenHeldGrants) if (g.warden() == departing) ended.add(g);
		wardenHeldGrants.removeAll(ended);
		for (WardenHeldGrant g : ended) {
			CardData grantee = g.grantee();
			if (g.power() != 0) {
				int left = permanentPowerBoost.getOrDefault(grantee, 0) - g.power();
				if (left == 0) permanentPowerBoost.remove(grantee);
				else           permanentPowerBoost.put(grantee, left);
			}
			EnumSet<CardData.Trait> held = permanentTraits.get(grantee);
			if (held != null && !g.traits().isEmpty()) {
				held.removeAll(g.traits());
				if (held.isEmpty()) permanentTraits.remove(grantee);
			}
			if (g.shieldFromSummons())   permanentCannotBeChosenBySummons.remove(grantee);
			if (g.shieldFromAbilities()) permanentCannotBeChosenByAbilities.remove(grantee);
			logEntry(grantee.name() + " loses what " + departing.name() + " granted it -- "
					+ departing.name() + " left the field");
		}
	}

	void returnTempExiledOnLeave(CardData departing) {
		if (tempExiledCards.isEmpty()) return;
		List<CardData> toReturn = new ArrayList<>();
		for (Map.Entry<CardData, CardData> e : tempExiledCards.entrySet())
			if (e.getValue() == departing) toReturn.add(e.getKey());
		for (CardData c : toReturn) {
			tempExiledCards.remove(c);
			if (!gameState.removeFromPermanentRfp(c)) continue;
			boolean ownerP1 = Boolean.TRUE.equals(gameState.getIdentity().get(c));
			logEntry(c.name() + " re-enters the field — " + departing.name() + " left the field");
			placeFromRfgWithAnim(c, ownerP1,
					() -> { if (ownerP1) placeCardInForwardZone(c); else placeP2CardInForwardZone(c); });
		}
	}

	/** Checks if any stolen forward had {@code leavingCardName} as its on-field condition and restores them. */
	private void checkAndRestoreStolenOnLeave(String leavingCardName) {
		String condKey = "whileCardOnField:" + leavingCardName;
		List<CardData> toRestore = new ArrayList<>();
		for (Map.Entry<CardData, String> e : stolenForwards.entrySet())
			if (e.getValue().equalsIgnoreCase(condKey)) toRestore.add(e.getKey());
		for (CardData c : toRestore) {
			stolenForwards.remove(c);
			restoreStolenForward(c);
		}
	}

	// -------------------------------------------------------------------------
	// Bounce: field cards back to the deck
	// -------------------------------------------------------------------------

	void returnP1ForwardToDeck(int idx, boolean toBottom) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		CardData card    = p1ForwardCards.get(idx);
		CardData topCard = p1ForwardPrimedTop.get(idx);
		String   pos     = toBottom ? "bottom" : "top";

		boolean player1 = gameState.getIdentity().get(card);
		Deque<CardData> zone = player1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();

		if (topCard != null) {
			gameState.addToPermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Play");
		}
		// One insertion only — the addLast/addFirst below is the real one. An unconditional
		// zone.add(card) used to run here as well, which put a second copy of every bounced
		// Forward into the deck.
		if (toBottom) zone.addLast(card);
		else          zone.addFirst(card);
		logEntry(card.name() + " → " + pos + " of deck");

		removeP1ForwardSlotState(idx);

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
				lbl.setFont(FontLoader.loadPixelFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						if (SwingUtilities.isLeftMouseButton(e)
								&& p1ForwardClickSelectsCombat()) {
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
		if (player1) refreshP1DeckLabel(); else refreshP2DeckLabel();
		if (topCard != null) refreshP1WarpZoneUI();
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, true);
	}

	void returnP2ForwardToDeck(int idx, boolean toBottom) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		CardData card    = p2ForwardCards.get(idx);
		CardData topCard = p2ForwardPrimedTop.get(idx);
		String   pos     = toBottom ? "bottom" : "top";

		boolean player1 = gameState.getIdentity().get(card);
		Deque<CardData> zone = player1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();

		if (topCard != null) {
			gameState.addToPermanentRfp(topCard);
			logEntry("[P2] " + topCard.name() + " → Removed From Play");
		}
		// One insertion only — see returnP1ForwardToDeck.
		if (toBottom) zone.addLast(card);
		else          zone.addFirst(card);
		logEntry("[P2] " + card.name() + " → " + pos + " of deck");

		removeP2ForwardSlotState(idx);

		if (p2ForwardPanel != null) {
			p2ForwardPanel.removeAll();
			p2ForwardLabels.clear();
			for (int i = 0; i < p2ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setFont(FontLoader.loadPixelFont(11));
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
		if (player1) refreshP1DeckLabel(); else refreshP2DeckLabel();
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, false);
	}

	void returnP1ForwardUnderDeckTop(int idx, int position) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		CardData card    = p1ForwardCards.get(idx);
		CardData topCard = p1ForwardPrimedTop.get(idx);

		boolean player1 = gameState.getIdentity().get(card);
		Deque<CardData> zone = player1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
		zone.add(card);

		if (topCard != null) {
			gameState.addToPermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Play");
		}
		Deque<CardData> deck = gameState.getP1MainDeck();
		List<CardData> preserved = new ArrayList<>();
		for (int i = 0; i < position && !deck.isEmpty(); i++) preserved.add(deck.pollFirst());
		deck.addFirst(card);
		for (int i = preserved.size() - 1; i >= 0; i--) deck.addFirst(preserved.get(i));
		logEntry(card.name() + " → under top " + position + " card(s) of deck");

		removeP1ForwardSlotState(idx);

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
				lbl.setFont(FontLoader.loadPixelFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						if (SwingUtilities.isLeftMouseButton(e)
								&& p1ForwardClickSelectsCombat()) {
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
		if (player1) refreshP1DeckLabel(); else refreshP2DeckLabel();
		if (topCard != null) refreshP1WarpZoneUI();
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, true);
	}

	void returnP2ForwardUnderDeckTop(int idx, int position) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		CardData card    = p2ForwardCards.get(idx);
		CardData topCard = p2ForwardPrimedTop.get(idx);

		boolean player1 = gameState.getIdentity().get(card);
		Deque<CardData> zone = player1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
		zone.add(card);

		if (topCard != null) {
			gameState.addToPermanentRfp(topCard);
			logEntry("[P2] " + topCard.name() + " → Removed From Play");
		}
		Deque<CardData> deck = gameState.getP2MainDeck();
		List<CardData> preserved = new ArrayList<>();
		for (int i = 0; i < position && !deck.isEmpty(); i++) preserved.add(deck.pollFirst());
		deck.addFirst(card);
		for (int i = preserved.size() - 1; i >= 0; i--) deck.addFirst(preserved.get(i));
		logEntry("[P2] " + card.name() + " → under top " + position + " card(s) of deck");

		removeP2ForwardSlotState(idx);

		if (p2ForwardPanel != null) {
			p2ForwardPanel.removeAll();
			p2ForwardLabels.clear();
			for (int i = 0; i < p2ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setFont(FontLoader.loadPixelFont(11));
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
		if (player1) refreshP1DeckLabel(); else refreshP2DeckLabel();
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, false);
	}

	// -------------------------------------------------------------------------
	// Deck search
	// -------------------------------------------------------------------------

	/**
	 * True while a search wants its identity filters met together rather than as alternatives —
	 * "Card Name Cecil with Job Paladin". Set for the length of one call by
	 * {@link #searchDeckForNamedCardWithJob} and read by {@link #searchDeckForCardImpl}, an
	 * instance field rather than a parameter so the sixteen-argument search signature and its
	 * thirty-odd call sites stay as they are: only one printing in the corpus needs the flag.
	 */
	private boolean searchIdentityConjunctive = false;

	/**
	 * The constraint a search puts on which cards may be taken <em>together</em> — "with different
	 * names" (23-008H Zidane, 18-138S Glauca, 22-067L Nacht), "each of a different Element"
	 * (1-135L Golbez). An instance field for the same reason {@link #searchIdentityConjunctive} is
	 * one: the sixteen-argument search signature and its thirty-odd call sites stay as they are.
	 */
	private PickGate searchPickGate = PickGate.ANY;

	/**
	 * Set while every card a search puts onto the field must arrive with its enter-the-field
	 * auto-ability suppressed — "Their auto-abilities will not trigger" (1-135L Golbez). An
	 * instance field for the same reason the two above are.
	 */
	private boolean searchSuppressAutoAbilities = false;

	/**
	 * Searches with a selection constraint and/or the arrivals silenced: every match is still
	 * offered, but a card that collides with a standing pick cannot be taken, and each card that
	 * reaches the field does so without firing its enter-the-field ability.
	 *
	 * <p>Both fields are cleared in a finally block, so a dialog the player dismisses cannot leave
	 * either set for the next search.
	 */
	boolean searchDeckForCardWithRiders(boolean isP1,
		boolean inclForwards, boolean inclBackups, boolean inclMonsters, boolean inclSummons,
		int costVal, String costCmp, String cardNameFilter, String jobFilter,
		String categoryFilter, String elementFilter, String excludeName, String excludeElem,
		String destination, int count, boolean entersDull, CardData.Trait requireTrait,
		PickGate gate, boolean suppressAutoAbilities) {
		searchPickGate = gate == null ? PickGate.ANY : gate;
		searchSuppressAutoAbilities = suppressAutoAbilities;
		try {
			return searchDeckForCard(isP1, inclForwards, inclBackups, inclMonsters, inclSummons,
				costVal, costCmp, cardNameFilter, jobFilter, categoryFilter, elementFilter,
				excludeName, excludeElem, destination, count, entersDull, requireTrait);
		} finally {
			searchPickGate = PickGate.ANY;
			searchSuppressAutoAbilities = false;
		}
	}

	/**
	 * Searches for the card that satisfies <em>both</em> identity filters — the Cecil that carries
	 * Job Paladin, not any Cecil and not any Paladin.
	 *
	 * <p>Runs the ordinary search with {@link #searchIdentityConjunctive} set, rather than
	 * gathering the matches here, so the search-blocked check, the searched-the-deck triggers and
	 * the destination handling all stay in one place. Cleared in a finally block, so a dialog the
	 * player dismisses cannot leave it set for the next search.
	 */
	boolean searchDeckForNamedCardWithJob(boolean isP1,
			boolean inclForwards, boolean inclBackups,
			boolean inclMonsters, boolean inclSummons,
			int costVal, String costCmp, String cardNameFilter, String jobFilter,
			String elementFilter, String excludeName, String excludeElem,
			String destination, int count, boolean entersDull, CardData.Trait requireTrait) {
		searchIdentityConjunctive = true;
		try {
			return searchDeckForCard(isP1, inclForwards, inclBackups, inclMonsters, inclSummons,
					costVal, costCmp, cardNameFilter, jobFilter, null, elementFilter,
					excludeName, excludeElem, destination, count, entersDull, requireTrait);
		} finally {
			searchIdentityConjunctive = false;
		}
	}

	/**
	 * @return whether the search actually moved a card — false when searching is blocked, when the
	 *         deck holds no match, or when the player looked and picked nothing. Callers that only
	 *         search may ignore it; "search … <b>and</b> remove it from the game. If you do so, …"
	 *         (29-117H Ark) branches on it, and a decline has to take the "If not" branch.
	 */
	boolean searchDeckForCard(boolean isP1,
			boolean inclForwards, boolean inclBackups,
			boolean inclMonsters, boolean inclSummons,
			int costVal, String costCmp, String cardNameFilter, String jobFilter,
			String categoryFilter, String elementFilter, String excludeName, String excludeElem,
			String destination, int count, boolean entersDull, CardData.Trait requireTrait) {
		if (turn(isP1).cannotSearchThisTurn) {
			// No search took place, so nothing that watches for one should fire.
			logEntry("Search blocked — opponent cannot search this turn");
			return false;
		}
		// The Character whose ability is searching, when there is one — a Summon or a game action
		// searching leaves this null, and "a Character opponent controls searches" must not fire.
		CardData searcher = (currentAbilitySource != null && currentAbilitySourceIsP1 == isP1)
				? currentAbilitySource : null;
		try {
			return searchDeckForCardImpl(isP1, inclForwards, inclBackups, inclMonsters, inclSummons,
					costVal, costCmp, cardNameFilter, jobFilter, categoryFilter, elementFilter,
					excludeName, excludeElem, destination, count, entersDull, requireTrait);
		} finally {
			// Fires on the act of searching, not on finding something: the deck was looked
			// through either way, which is the event opponents' abilities react to.
			autoAbilityTriggers.triggerAutoAbilitiesForSearch(searcher, isP1);
		}
	}

	/**
	 * Whether a card sitting in a deck carries {@code trait}, for the "search for 1 … with
	 * &lt;Keyword&gt;" filter.
	 *
	 * <p>Reads the printing only. Nothing on the field can be granting a trait to a card still in
	 * the deck, so the effective view has nothing to add here, and Warp is asked its own way
	 * because it is stored as a value rather than in the trait set.
	 */
	private static boolean deckCardHasTrait(CardData c, CardData.Trait trait) {
		return trait == CardData.Trait.WARP ? c.hasWarp() : c.getTraits().contains(trait);
	}

	/** @return whether a card was found, chosen, and moved to {@code destination}. */
	private boolean searchDeckForCardImpl(boolean isP1,
			boolean inclForwards, boolean inclBackups,
			boolean inclMonsters, boolean inclSummons,
			int costVal, String costCmp, String cardNameFilter, String jobFilter,
			String categoryFilter, String elementFilter, String excludeName, String excludeElem,
			String destination, int count, boolean entersDull, CardData.Trait requireTrait) {
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
			if (requireTrait != null && !deckCardHasTrait(c, requireTrait)) continue;
			if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
			// Job, Card Name and Category identify a card three different ways. Stated together
			// they are usually alternatives: "Category FFL Forwards or Job Warrior of Light
			// Forwards" (12-099R Sarah) wants either. Alone, each is a plain requirement.
			//
			// One printed phrase means them together instead — "Card Name Cecil with Job Paladin"
			// (20-075L, 28-032H; 4-054L Onion Knight with Job Sage), which wants the one Cecil that
			// is a Paladin rather than any Cecil or any Paladin. Only the text can tell the two
			// readings apart, so the parser says which by way of searchIdentityConjunctive.
			int idFilters = (jobFilter      != null ? 1 : 0)
			              + (cardNameFilter != null ? 1 : 0)
			              + (categoryFilter != null ? 1 : 0);
			boolean passesIdentity = idFilters <= 1 || searchIdentityConjunctive
				? meetsJobFilterEffective(c, jobFilter)
					&& meetsCardNameFilter(c, cardNameFilter)
					&& meetsCategoryFilter(c, categoryFilter)
				: (jobFilter      != null && meetsJobFilterEffective(c, jobFilter))
					|| (cardNameFilter != null && meetsCardNameFilter(c, cardNameFilter))
					|| (categoryFilter != null && meetsCategoryFilter(c, categoryFilter));
			if (!passesIdentity) continue;
			if (!meetsElementFilter(c, elementFilter)) continue;
			if (excludeName != null && meetsCardNameFilter(c, excludeName)) continue;
			if (excludeElem != null) {
				boolean excluded = false;
				// "other than Light and Dark" excludes both, exactly as "Light or Dark" does —
				// the conjunction is in the English, not in the filter.
				for (String ee : excludeElem.split("(?i)\\s+(?:and|or)\\s+"))
					if (c.containsElement(ee.trim())) { excluded = true; break; }
				if (excluded) continue;
			}
			matches.add(c);
		}
		if (matches.isEmpty()) {
			shuffleDeck(isP1);
			logEntry("Search: no matching card found in deck");
			return false;
		}
		List<CardData> chosen = new ArrayList<>();
		if (!isP1) {
			for (int i = 0; i < count && !matches.isEmpty(); i++) {
				List<CardData> copy = new ArrayList<>(matches);
				Collections.shuffle(copy);
				CardData pick = copy.get(0);
				// A selection rider ("with different names", "each of a different Element") binds
				// the AI too: it takes the first card its standing picks still leave legal, and
				// stops when none do. A hand it could not legally have chosen is not one the rules
				// let it take.
				if (searchPickGate != PickGate.ANY) {
					CardData legal = null;
					for (CardData c : copy) if (searchPickGate.allows(chosen, c)) { legal = c; break; }
					if (legal == null) break;
					pick = legal;
				}
				logEntry("[AI] chose " + pick.name());
				matches.remove(pick);
				deck.remove(pick);
				chosen.add(pick);
			}
		} else if (count > 1) {
			List<CardData> picks = cardPickerDialog.pickMultiFromDeckSearch(
				matches, count, searchPickGate);
			for (CardData pick : picks) {
				gameState.removeFromP1MainDeck(pick);
				chosen.add(pick);
			}
		} else {
			CardData pick = cardPickerDialog.pickFromDeckSearch(matches);
			if (pick != null) {
				gameState.removeFromP1MainDeck(pick);
				chosen.add(pick);
			}
		}
		shuffleDeck(isP1);
		if (chosen.isEmpty()) {
			logEntry("Search: no card selected");
			return false;
		}
		// Charged before the first placement and counted down per arrival, so every card this
		// search puts onto the field is silent — "Their auto-abilities will not trigger" names all
		// of them, and a one-shot flag would silence only the first.
		if (searchSuppressAutoAbilities && "field".equals(destination))
			suppressAutoAbilityForNextCards = chosen.size();
		for (CardData card : chosen) {
			switch (destination) {
				case "hand" -> {
					playerHand(isP1).add(card);
					logEntry((isP1 ? "" : "[P2] ") + card.name() + " → hand (search)");
					if (isP1) refreshP1HandLabel(); else refreshP2HandCountLabel();
					animateCardDraw(isP1, 1);
				}
				case "field" -> {
					logEntry((isP1 ? "" : "[P2] ") + card.name() + " → field (search)" + (entersDull ? " dull" : ""));
					if (isP1) {
						if (card.isBackup())       placeCardInFirstBackupSlot(card);
						else if (card.isMonster()) placeCardInMonsterZone(card);
						else {
							placeCardInForwardZone(card);
							if (entersDull) {
								int newIdx = p1ForwardCards.size() - 1;
								p1ForwardStates.set(newIdx, CardState.DULL);
								refreshP1ForwardSlot(newIdx);
							}
						}
					} else {
						if (card.isBackup())       placeP2CardInFirstBackupSlot(card);
						else if (card.isMonster()) placeP2CardInMonsterZone(card);
						else                       placeP2CardInForwardZone(card);
					}
				}
				case "deckTop" -> {
					deck.addFirst(card);
					logEntry((isP1 ? "" : "[P2] ") + card.name() + " → top of deck (search)");
					if (isP1) refreshP1DeckLabel(); else refreshP2DeckLabel();
				}
				case "underTop" -> {
					if (deck.isEmpty()) {
						deck.addFirst(card);
					} else {
						CardData top = deck.pollFirst();
						deck.addFirst(card);
						deck.addFirst(top);
					}
					logEntry((isP1 ? "" : "[P2] ") + card.name() + " → under top card of deck (search)");
					if (isP1) refreshP1DeckLabel(); else refreshP2DeckLabel();
				}
				case "breakZone" -> {
					if (isP1) { addToBreakZone(card); refreshP1BreakLabel(); }
					else      { addToBreakZone(card); refreshP2BreakLabel(); }
					logEntry((isP1 ? "" : "[P2] ") + card.name() + " → Break Zone (search)");
				}
				// The card never reached the field, so nothing "leaves the field" and no
				// leaves-field trigger fires — it goes straight from the deck to the RFG zone.
				case "removedFromGame" -> {
					gameState.addToPermanentRfp(card);
					logEntry((isP1 ? "" : "[P2] ") + card.name() + " → Removed From Game (search)");
					if (isP1) refreshP1WarpZoneUI(); else refreshP2WarpZoneUI();
				}
			}
		}
		return true;
	}

	void shuffleDeck(boolean isP1) {
		Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
		List<CardData> list = new ArrayList<>(deck);
		Collections.shuffle(list);
		deck.clear();
		deck.addAll(list);
		if (isP1) refreshP1DeckLabel(); else refreshP2DeckLabel();
	}

	void searchDeckJobAndTypeDontShareElements(boolean isP1, String jobFilter, String typeName) {
		if (turn(isP1).cannotSearchThisTurn) {
			logEntry("Search blocked — opponent cannot search this turn");
			return;
		}
		Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
		List<CardData> pool1 = new ArrayList<>();  // Job [jobFilter]
		List<CardData> pool2 = new ArrayList<>();  // [typeName] type cards
		for (CardData c : deck) {
			if (meetsJobFilterEffective(c, jobFilter)) pool1.add(c);
			boolean typeMatch = switch (typeName.toLowerCase(java.util.Locale.ROOT)) {
				case "summon", "summons"       -> c.isSummon();
				case "forward", "forwards"     -> c.isForward();
				case "backup", "backups"       -> c.isBackup();
				case "monster", "monsters"     -> c.isMonster();
				case "character", "characters" -> c.isForward() || c.isBackup() || c.isMonster();
				default -> false;
			};
			if (typeMatch) pool2.add(c);
		}
		shuffleDeck(isP1);
		if (pool1.isEmpty() && pool2.isEmpty()) {
			logEntry("Search: no eligible cards found");
			return;
		}
		CardData[] picks = { null, null };
		if (!isP1) {
			// AI: try to find a non-sharing pair, otherwise take one card
			if (!pool1.isEmpty() && !pool2.isEmpty()) {
				outer:
				for (CardData c1 : pool1) {
					for (CardData c2 : pool2) {
						if (!shufflingway.dialog.CardPickerDialog.dualSearchSharesElement(c1, c2)) { picks[0] = c1; picks[1] = c2; break outer; }
					}
				}
				if (picks[0] == null) picks[0] = pool1.get(0);
			} else if (!pool1.isEmpty()) {
				picks[0] = pool1.get(0);
			} else {
				picks[1] = pool2.get(0);
			}
		} else {
			picks = cardPickerDialog.pickDualSearch(pool1, pool2, "Job " + jobFilter, typeName);
		}
		if (picks == null) return;
		for (CardData pick : picks) {
			if (pick == null) continue;
			if (isP1) gameState.removeFromP1MainDeck(pick);
			else      deck.remove(pick);
			playerHand(isP1).add(pick);
			logEntry((isP1 ? "" : "[P2] ") + pick.name() + " → hand (search)");
			if (isP1) refreshP1HandLabel(); else refreshP2HandCountLabel();
			animateCardDraw(isP1, 1);
		}
	}

	void searchDeckElementOrCategoryCharsDifferentCost(boolean isP1, String element, String category) {
		if (turn(isP1).cannotSearchThisTurn) {
			logEntry("Search blocked — opponent cannot search this turn");
			return;
		}
		Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
		// Combined pool: element Characters ∪ category Characters (insertion-ordered, no duplicates)
		java.util.LinkedHashSet<CardData> poolSet = new java.util.LinkedHashSet<>();
		for (CardData c : deck) {
			if (!c.isForward() && !c.isBackup() && !c.isMonster()) continue;
			if (c.containsElement(element) || meetsCategoryFilter(c, category)) poolSet.add(c);
		}
		List<CardData> pool = new ArrayList<>(poolSet);
		shuffleDeck(isP1);

		if (pool.isEmpty()) {
			logEntry("Search: no eligible cards found");
			return;
		}

		List<CardData> chosen = new ArrayList<>();
		if (!isP1) {
			// AI: prefer a different-cost pair, otherwise take the single best card
			CardData first = null, second = null;
			outer:
			for (CardData a : pool)
				for (CardData b : pool)
					if (a != b && a.cost() != b.cost()) { first = a; second = b; break outer; }
			if (first != null) { chosen.add(first); chosen.add(second); }
			else                chosen.add(pool.get(0));
		} else {
			List<CardData> picks = cardPickerDialog.pickTwoFromDeckSearchDifferentCost(pool);
			chosen.addAll(picks);
		}

		for (CardData card : chosen) {
			if (isP1) gameState.removeFromP1MainDeck(card);
			else      deck.remove(card);
			playerHand(isP1).add(card);
			logEntry((isP1 ? "" : "[P2] ") + card.name() + " → hand (search)");
			if (isP1) refreshP1HandLabel(); else refreshP2HandCountLabel();
			animateCardDraw(isP1, 1);
		}
	}

	void searchDeckNElementSummonsDifferentCost(boolean isP1, int count, String element) {
		if (turn(isP1).cannotSearchThisTurn) {
			logEntry("Search blocked — opponent cannot search this turn");
			return;
		}
		Deque<CardData> deck = isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck();
		List<CardData> pool = new ArrayList<>();
		for (CardData c : deck) {
			if (c.isSummon() && c.containsElement(element)) pool.add(c);
		}
		shuffleDeck(isP1);
		if (pool.isEmpty()) {
			logEntry("Search: no " + element + " Summons found");
			return;
		}
		List<CardData> chosen = new ArrayList<>();
		if (!isP1) {
			// AI: prefer a different-cost pair, otherwise take the single best card
			CardData first = null, second = null;
			outer:
			for (CardData a : pool)
				for (CardData b : pool)
					if (a != b && a.cost() != b.cost()) { first = a; second = b; break outer; }
			if (first != null) { chosen.add(first); if (count > 1) chosen.add(second); }
			else chosen.add(pool.get(0));
		} else {
			List<CardData> picks = cardPickerDialog.pickTwoFromDeckSearchDifferentCost(pool);
			chosen.addAll(picks);
		}
		for (CardData card : chosen) {
			if (isP1) gameState.removeFromP1MainDeck(card);
			else      deck.remove(card);
			playerHand(isP1).add(card);
			logEntry((isP1 ? "" : "[P2] ") + card.name() + " → hand (search)");
			if (isP1) refreshP1HandLabel(); else refreshP2HandCountLabel();
			animateCardDraw(isP1, 1);
		}
	}

	int showCardImageChooser(List<CardData> cards, String title, boolean allowCancel) {
		return cardPickerDialog.pickCardImage(cards, title, allowCancel);
	}

	int showCardImageChooser(List<CardData> cards, String title, boolean allowCancel, boolean showCost) {
		return cardPickerDialog.pickCardImage(cards, title, allowCancel, showCost);
	}

	int showCardImageChooser(List<CardData> cards, String title, boolean allowCancel,
			java.util.function.ToIntFunction<CardData> costFn) {
		return cardPickerDialog.pickCardImage(cards, title, allowCancel, costFn);
	}

	List<Integer> showCardMultiImageChooser(List<CardData> cards, String title, int count,
			boolean eachDifferentType, boolean showCost) {
		return cardPickerDialog.pickMultiCardImage(cards, title, count, eachDifferentType, showCost);
	}

	// -------------------------------------------------------------------------
	// Bounce: field cards back to hand
	// -------------------------------------------------------------------------

	void returnP1ForwardToHand(int idx) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		CardData card    = p1ForwardCards.get(idx);
		boolean hadGrants = !card.fieldPowerGrants().isEmpty();
		CardData topCard = p1ForwardPrimedTop.get(idx);
		boolean player1 = gameState.getIdentity().get(card);
		List<CardData> zone = player1 ? gameState.getP1Hand() : gameState.getP2Hand();
		// Before the slot is torn down: the slide reads its start point off the live label.
		animateCardReturnToHand(idx < p1ForwardLabels.size() ? p1ForwardLabels.get(idx) : null,
				card, player1);
		if (topCard != null) {
			gameState.addToPermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Play");
		}
		zone.add(card);
		logEntry(card.name() + " → returned to hand");

		removeP1ForwardSlotState(idx);

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
				lbl.setFont(FontLoader.loadPixelFont(11));
				lbl.setBorder(BorderFactory.createEmptyBorder());
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (lbl.getIcon() == null) return;
						if (SwingUtilities.isLeftMouseButton(e)
								&& p1ForwardClickSelectsCombat()) {
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
		if (player1) refreshP1HandLabel(); else refreshP2HandCountLabel();
		if (topCard != null) refreshP1WarpZoneUI();
		if (gameState.getCurrentPlayer() == GameState.Player.P1) p1Turn.forwardsLeftFieldThisTurn++;
		else p2Turn.forwardsLeftFieldThisTurn++;
		p2Turn.turnOpponentCharReturnedToHand = true;
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, true);
	}

	void returnP2ForwardToHand(int idx) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		CardData card    = p2ForwardCards.get(idx);
		boolean hadGrants = !card.fieldPowerGrants().isEmpty();
		CardData topCard = p2ForwardPrimedTop.get(idx);
		boolean player1 = gameState.getIdentity().get(card);
		List<CardData> zone = player1 ? gameState.getP1Hand() : gameState.getP2Hand();
		// Before the slot is torn down: the slide reads its start point off the live label.
		animateCardReturnToHand(idx < p2ForwardLabels.size() ? p2ForwardLabels.get(idx) : null,
				card, player1);
		if (topCard != null) {
			gameState.addToPermanentRfp(topCard);
			logEntry("[P2] " + topCard.name() + " → Removed From Play");
		}
		zone.add(card);
		logEntry("[P2] " + card.name() + " → returned to hand");

		removeP2ForwardSlotState(idx);

		if (p2ForwardPanel != null) {
			p2ForwardPanel.removeAll();
			p2ForwardLabels.clear();
			for (int i = 0; i < p2ForwardCards.size(); i++) {
				final int fi = i;
				JLabel lbl = new JLabel("", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
				lbl.setOpaque(false);
				lbl.setFont(FontLoader.loadPixelFont(11));
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
		if (player1) refreshP1HandLabel(); else refreshP2HandCountLabel();
		if (gameState.getCurrentPlayer() == GameState.Player.P1) p1Turn.forwardsLeftFieldThisTurn++;
		else p2Turn.forwardsLeftFieldThisTurn++;
		p1Turn.turnOpponentCharReturnedToHand = true;
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, false);
	}

	void returnP1BackupToHand(int idx) {
		if (idx < 0 || idx >= p1BackupCards.length || p1BackupCards[idx] == null) return;
		CardData c = p1BackupCards[idx];

		boolean player1 = gameState.getIdentity().get(c);
		List<CardData> zone = player1 ? gameState.getP1Hand() : gameState.getP2Hand();
		// Before the slot is torn down: the slide reads its start point off the live label.
		animateCardReturnToHand(p1BackupLabels[idx], c, player1);
		zone.add(c);

		logEntry(c.name() + " → returned to hand");
		p1BackupTempForwardPower.remove(c); p1BackupForwardBoost.remove(c);
		p1BackupTempTraits.remove(c);       p1BackupForwardDamage.remove(c);
		if (p1BackupAttackIdx == idx) p1BackupAttackIdx = -1;
		p1BackupCards[idx]  = null;
		p1BackupUrls[idx]   = null;
		p1BackupStates[idx] = CardState.ACTIVE;
		p1BackupFrozen[idx] = false;
		if (p1BackupLabels[idx] != null) { p1BackupLabels[idx].setIcon(null); p1BackupLabels[idx].setText(null); }
		if (player1) refreshP1HandLabel(); else refreshP2HandCountLabel();
		p2Turn.turnOpponentCharReturnedToHand = true;
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, true);
	}

	void returnP2BackupToHand(int idx) {
		if (idx < 0 || idx >= p2BackupCards.length || p2BackupCards[idx] == null) return;
		CardData c = p2BackupCards[idx];

		boolean player1 = gameState.getIdentity().get(c);
		List<CardData> zone = player1 ? gameState.getP1Hand() : gameState.getP2Hand();
		// Before the slot is torn down: the slide reads its start point off the live label.
		animateCardReturnToHand(p2BackupLabels[idx], c, player1);
		zone.add(c);

		logEntry("[P2] " + c.name() + " → returned to hand");
		p2BackupTempForwardPower.remove(c); p2BackupForwardBoost.remove(c);
		p2BackupTempTraits.remove(c);       p2BackupForwardDamage.remove(c);
		if (p2BackupAttackIdx == idx) p2BackupAttackIdx = -1;
		p2BackupCards[idx]  = null;
		p2BackupUrls[idx]   = null;
		p2BackupStates[idx] = CardState.ACTIVE;
		p2BackupFrozen[idx] = false;
		if (p2BackupLabels[idx] != null) { p2BackupLabels[idx].setIcon(null); p2BackupLabels[idx].setText(null); }
		if (player1) refreshP1HandLabel(); else refreshP2HandCountLabel();
		p1Turn.turnOpponentCharReturnedToHand = true;
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
	}

	void returnP1MonsterToHand(int idx) {
		if (idx < 0 || idx >= p1MonsterCards.size()) return;
		CardData c = p1MonsterCards.get(idx);

		boolean player1 = gameState.getIdentity().get(c);
		List<CardData> zone = player1 ? gameState.getP1Hand() : gameState.getP2Hand();
		// Before the slot is torn down: the slide reads its start point off the live label.
		animateCardReturnToHand(idx < p1MonsterLabels.size() ? p1MonsterLabels.get(idx) : null,
				c, player1);
		zone.add(c);

		logEntry(c.name() + " → returned to hand");
		p1MonsterTempForwardPower.remove(c);
		p1MonsterCards.remove(idx);
		p1MonsterStates.remove(idx);
		p1MonsterFrozen.remove(idx);
		p1MonsterPlayedOnTurn.remove(idx);
		p1MonsterUrls.remove(idx);
		JLabel lbl = p1MonsterLabels.remove(idx);
		if (p1MonsterPanel != null) { p1MonsterPanel.remove(lbl); p1MonsterPanel.revalidate(); p1MonsterPanel.repaint(); }
		if (player1) refreshP1HandLabel(); else refreshP2HandCountLabel();
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, true);
	}

	void returnP2MonsterToHand(int idx) {
		if (idx < 0 || idx >= p2MonsterCards.size()) return;
		CardData c = p2MonsterCards.get(idx);

		boolean player1 = gameState.getIdentity().get(c);
		List<CardData> zone = player1 ? gameState.getP1Hand() : gameState.getP2Hand();
		// Before the slot is torn down: the slide reads its start point off the live label.
		animateCardReturnToHand(idx < p2MonsterLabels.size() ? p2MonsterLabels.get(idx) : null,
				c, player1);
		zone.add(c);

		logEntry("[P2] " + c.name() + " → returned to hand");
		p2MonsterTempForwardPower.remove(c);
		p2MonsterCards.remove(idx);
		p2MonsterStates.remove(idx);
		p2MonsterFrozen.remove(idx);
		p2MonsterPlayedOnTurn.remove(idx);
		p2MonsterUrls.remove(idx);
		JLabel lbl = p2MonsterLabels.remove(idx);
		if (p2MonsterPanel != null) { p2MonsterPanel.remove(lbl); p2MonsterPanel.revalidate(); p2MonsterPanel.repaint(); }
		if (player1) refreshP1HandLabel(); else refreshP2HandCountLabel();
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
	}

	// -------------------------------------------------------------------------
	// Effective power and traits after field grants
	// -------------------------------------------------------------------------

	int effectiveP1ForwardPower(int idx) {
		CardData top  = p1ForwardPrimedTop.get(idx);
		CardData card = p1ForwardCards.get(idx);
		int printed = top != null ? top.power() : card.power();
		int base = basePowerOverrides.getOrDefault(card, fieldGrantBasePower(card, true, printed));
		return base + p1ForwardPowerBoost.get(idx) - p1ForwardPowerReduction.get(idx)
				+ permanentPowerBoost.getOrDefault(card, 0)
				+ computeConditionalBoostForTarget(card, true);
	}

	int effectiveP2ForwardPower(int idx) {
		CardData card = p2ForwardCards.get(idx);
		return basePowerOverrides.getOrDefault(card, fieldGrantBasePower(card, false, card.power()))
				+ p2ForwardPowerBoost.get(idx) - p2ForwardPowerReduction.get(idx)
				+ permanentPowerBoost.getOrDefault(card, 0)
				+ computeConditionalBoostForTarget(card, false);
	}

	/**
	 * The base power {@code target} has on {@code isP1}'s side once a continuous field grant replaces
	 * its printed value ("The power of the Job Pirate Forwards and Card Name Viking Forwards other
	 * than Faris you control becomes 8000." — Faris 21-114L), or {@code printed} when none does.
	 *
	 * <p>Read at power-query time rather than written into {@link #basePowerOverrides}, so a Pirate
	 * entering the field picks the value up at once and loses it the moment Faris leaves.
	 *
	 * <p>Callers give {@link #basePowerOverrides} precedence: that map holds one-shot replacements an
	 * effect applied at a definite moment ("its power becomes 1000 until the end of the turn"), and
	 * those almost always resolve after the continuous grant was already in place, which is the
	 * order the rules would settle by timestamp. The engine keeps no timestamps, so the reverse
	 * order — a Faris arriving after the one-shot — is resolved the same way rather than correctly.
	 */
	int fieldGrantBasePower(CardData target, boolean isP1, int printed) {
		int base = printed;
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : fwds) base = applyFieldGrantBasePower(src, target, base);
		for (CardData src : bkps) if (src != null) base = applyFieldGrantBasePower(src, target, base);
		for (CardData src : mons) base = applyFieldGrantBasePower(src, target, base);
		return base;
	}

	/** {@code src}'s base-power replacement for {@code target}, or {@code base} when it grants none. */
	private int applyFieldGrantBasePower(CardData src, CardData target, int base) {
		if (lostAbilitiesCards.contains(src)) return base;
		for (FieldPowerGrant fpg : src.fieldPowerGrants())
			if (fpg.basePowerSet() > 0 && !fpg.affectsOpponent()
					&& fpg.appliesToCard(target, jobsStripped(target)))
				return fpg.basePowerSet();
		return base;
	}

	boolean effectiveP1HasTrait(int idx, CardData.Trait trait) {
		if (p1ForwardRemovedTraits.get(idx).contains(trait)) return false;
		CardData card = p1ForwardCards.get(idx);
		if (lostAbilitiesCards.contains(card)) return false;
		boolean granted = p1ForwardTempTraits.get(idx).contains(trait)
		           || permanentTraits.getOrDefault(card, NO_TRAITS).contains(trait)
		           || fieldGrantCalculator.computeConditionalTraitsForTarget(card, true).contains(trait);
		boolean has = card.hasTrait(trait) || (granted && !cannotGain(card, trait));
		return has && !(trait == CardData.Trait.HASTE && fieldGrantCalculator.isHasteSuppressedFor(true));
	}

	boolean effectiveP2HasTrait(int idx, CardData.Trait trait) {
		if (p2ForwardRemovedTraits.get(idx).contains(trait)) return false;
		CardData card = p2ForwardCards.get(idx);
		if (lostAbilitiesCards.contains(card)) return false;
		boolean granted = p2ForwardTempTraits.get(idx).contains(trait)
		           || permanentTraits.getOrDefault(card, NO_TRAITS).contains(trait)
		           || fieldGrantCalculator.computeConditionalTraitsForTarget(card, false).contains(trait);
		boolean has = card.hasTrait(trait) || (granted && !cannotGain(card, trait));
		return has && !(trait == CardData.Trait.HASTE && fieldGrantCalculator.isHasteSuppressedFor(false));
	}

	/**
	 * Whether {@code card} prints "[self] cannot gain [trait]" (Ravana, Savior of the Gnath
	 * 14-087L). Only the granted sources are tested against this: a trait printed on the card was
	 * never gained, so the restriction has nothing to say about it.
	 */
	private boolean cannotGain(CardData card, CardData.Trait trait) {
		return !lostAbilitiesCards.contains(card) && card.cannotGainTraits().contains(trait);
	}

	private boolean effectiveHasTrait(boolean isP1, int idx, CardData.Trait trait) {
		return isP1 ? effectiveP1HasTrait(idx, trait) : effectiveP2HasTrait(idx, trait);
	}

	/**
	 * Whether {@code card} has {@code trait} right now, wherever it stands on {@code isP1}'s field —
	 * for callers that hold a card but no row and index.
	 *
	 * <p>Falls back to the printed trait when the card is not on the field, which is the honest
	 * answer for something being asked about from hand or the Break Zone: nothing on the board is
	 * granting or stripping traits for it.
	 */
	boolean effectiveCardHasTrait(CardData card, boolean isP1, CardData.Trait trait) {
		ForwardTarget slot = findFieldSlot(card, isP1);
		if (slot == null) return card.hasTrait(trait);
		return switch (slot.zone()) {
			case FORWARD -> effectiveHasTrait(isP1, slot.idx(), trait);
			case BACKUP  -> effectiveBackupHasTrait(isP1, slot.idx(), trait);
			case MONSTER -> effectiveMonsterHasTrait(isP1, slot.idx(), trait);
			// findFieldSlot only ever reports the three field rows.
			case BREAK_ZONE -> card.hasTrait(trait);
		};
	}

	/**
	 * Returns the traits the forward at {@code idx} should show a {@link TraitTab} for, in a
	 * stable display order. Only traits {@link TraitTab#hasGlyph} can draw are considered, and
	 * each is resolved through {@code effectiveHasTrait} so granted, temporary and suppressed
	 * traits all show correctly.
	 */
	private List<CardData.Trait> visibleTraitTabs(boolean isP1, int idx) {
		List<CardData.Trait> out = new ArrayList<>();
		for (CardData.Trait t : CardData.Trait.values())
			if (TraitTab.hasGlyph(t) && effectiveHasTrait(isP1, idx, t)) out.add(t);
		return out;
	}

	/**
	 * Whether the Forward at {@code idx} has actually been primed — a card pulled from the deck and
	 * stacked on top of it — as opposed to merely printing the Priming trait.
	 *
	 * <p>The distinction is what the Priming trait tab renders: the tab appears as soon as a card
	 * can prime, and lights up once it has. The base card stays in {@code pNForwardCards} either
	 * way, which is why the trait itself never stops reporting true.
	 */
	boolean isPrimedForward(boolean isP1, int idx) {
		List<CardData> tops = isP1 ? p1ForwardPrimedTop : p2ForwardPrimedTop;
		return idx >= 0 && idx < tops.size() && tops.get(idx) != null;
	}

	/** Monster equivalent of {@link #visibleTraitTabs}, resolved through {@code effectiveMonsterHasTrait}. */
	private List<CardData.Trait> visibleMonsterTraitTabs(boolean isP1, int idx) {
		List<CardData.Trait> out = new ArrayList<>();
		for (CardData.Trait t : CardData.Trait.values())
			if (TraitTab.hasGlyph(t) && effectiveMonsterHasTrait(isP1, idx, t)) out.add(t);
		return out;
	}

	/** True when the monster at {@code idx} has {@code trait} innately or granted while acting as a Forward. */
	boolean effectiveMonsterHasTrait(boolean isP1, int idx, CardData.Trait trait) {
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		if (idx < 0 || idx >= mons.size()) return false;
		CardData card = mons.get(idx);
		if (card.hasTrait(trait)) return true;
		EnumSet<CardData.Trait> granted = (isP1 ? p1MonsterTempTraits : p2MonsterTempTraits).get(card);
		return granted != null && granted.contains(trait);
	}

	/**
	 * Where {@code card} stands on {@code isP1}'s field right now, or {@code null} when it is no
	 * longer on it.
	 *
	 * <p>By identity, not by equality: {@code CardData} is a record, so two copies of the same
	 * printing are equal to one another, and only the instance that was actually declared is the
	 * one this battle is about. A Forward slot is matched on its primed top card as well as on the
	 * card underneath, because the top card is the one that acts.
	 *
	 * @param preferIdx a slot to check first — the index the caller recorded earlier. Cheap, and it
	 *                  keeps the answer stable for the overwhelmingly common case where nothing
	 *                  moved; pass {@code -1} when there is no such hint.
	 */
	ForwardTarget currentFieldTargetOf(CardData card, boolean isP1, int preferIdx) {
		if (card == null) return null;
		List<CardData> fwds  = isP1 ? p1ForwardCards     : p2ForwardCards;
		List<CardData> tops  = isP1 ? p1ForwardPrimedTop : p2ForwardPrimedTop;
		if (preferIdx >= 0 && preferIdx < fwds.size() && forwardSlotHolds(fwds, tops, preferIdx, card))
			return new ForwardTarget(isP1, preferIdx, ForwardTarget.CardZone.FORWARD);
		for (int i = 0; i < fwds.size(); i++)
			if (forwardSlotHolds(fwds, tops, i, card))
				return new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD);
		CardData[] bkps = isP1 ? p1BackupCards : p2BackupCards;
		for (int i = 0; i < bkps.length; i++)
			if (bkps[i] == card) return new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP);
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (int i = 0; i < mons.size(); i++)
			if (mons.get(i) == card) return new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER);
		return null;
	}

	/** Whether Forward slot {@code idx} is {@code card}, either as the slot's card or its primed top. */
	private static boolean forwardSlotHolds(List<CardData> fwds, List<CardData> tops, int idx, CardData card) {
		return fwds.get(idx) == card || (idx < tops.size() && tops.get(idx) == card);
	}

	// -------------------------------------------------------------------------
	// Combat resolution
	// -------------------------------------------------------------------------

	/**
	 * Resolves combat between an attacker and a blocker.
	 * A forward breaks when the opponent's power equals or exceeds its own power.
	 * First Strike: if one side has it and the other doesn't, that side strikes first;
	 * if the strike kills the opponent, the survivor takes no damage.
	 */
	void resolveCombat(CardData attacker, boolean attackerIsP1, int declaredAttackerIdx,
			CardData blocker, boolean blockerIsP1, int declaredBlockerIdx) {
		if (escapedFromBattle.contains(attacker)) {
			logEntry(attacker.name() + " escaped from the Battle — combat skipped");
			return;
		}
		if (escapedFromBattle.contains(blocker)) {
			logEntry(blocker.name() + " escaped from the Battle — combat skipped");
			return;
		}
		// The two indices were recorded when the block was declared, and everything between then
		// and now can move them: the "when blocked" trigger resolves in between — 16-011L Squall's
		// deals 4000 to every Forward the opponent controls — and so does a full priority round in
		// which either player may cast. Breaking a Forward compacts its row, so a stale index either
		// addresses the wrong card or, once the row is shorter than it was, addresses nothing at
		// all. That was an IndexOutOfBoundsException out of the First Strike check, which left the
		// Attack Phase stalled on damage resolution with priority still held.
		ForwardTarget atkNow = currentFieldTargetOf(attacker, attackerIsP1, declaredAttackerIdx);
		ForwardTarget blkNow = currentFieldTargetOf(blocker,  blockerIsP1,  declaredBlockerIdx);
		// A combatant that has left the field is out of the battle, and a blocked attacker deals no
		// damage to the player, so there is nothing left to resolve either way.
		if (atkNow == null || atkNow.zone() != ForwardTarget.CardZone.FORWARD) {
			logEntry(attacker.name() + " left the field before damage — combat skipped");
			return;
		}
		if (blkNow == null || blkNow.zone() != ForwardTarget.CardZone.FORWARD) {
			logEntry(blocker.name() + " left the field before damage — combat skipped");
			return;
		}
		final int attackerIdx = atkNow.idx();
		final int blockerIdx  = blkNow.idx();

		boolean attackerFirst = effectiveHasTrait(attackerIsP1, attackerIdx, CardData.Trait.FIRST_STRIKE)
				&& !effectiveHasTrait(blockerIsP1, blockerIdx, CardData.Trait.FIRST_STRIKE);
		boolean blockerFirst = effectiveHasTrait(blockerIsP1, blockerIdx, CardData.Trait.FIRST_STRIKE)
				&& !effectiveHasTrait(attackerIsP1, attackerIdx, CardData.Trait.FIRST_STRIKE);

		int effAttackerPow = attackerIsP1 ? effectiveP1ForwardPower(attackerIdx) : effectiveP2ForwardPower(attackerIdx);
		int effBlockerPow  = blockerIsP1  ? effectiveP1ForwardPower(blockerIdx)  : effectiveP2ForwardPower(blockerIdx);
		logEntry((attackerIsP1 ? "" : "[P2] ") + attacker.name() + " (" + effAttackerPow + ")"
				+ " vs " + (blockerIsP1 ? "" : "[P2] ") + blocker.name() + " (" + effBlockerPow + ")");

		// Compute actual damage each side deals after outgoing and incoming modifiers.
		// currentBattleAttacker is set so modifyIncomingDamage can inspect the opposing Forward's traits.
		int rawDmgToBlocker  = modifyOutgoingCombatDamage(attackerIsP1, attackerIdx, effAttackerPow, blocker);
		currentBattleAttacker = attacker; currentBattleAttackerIsP1 = attackerIsP1; currentBattleAttackerIdx = attackerIdx;
		int dmgToBlocker     = modifyIncomingDamage(blockerIsP1,  blockerIdx,  rawDmgToBlocker,  false, false);
		int rawDmgToAttacker = modifyOutgoingCombatDamage(blockerIsP1, blockerIdx, effBlockerPow, attacker);
		currentBattleAttacker = blocker;  currentBattleAttackerIsP1 = blockerIsP1;  currentBattleAttackerIdx = blockerIdx;
		int dmgToAttacker    = modifyIncomingDamage(attackerIsP1, attackerIdx, rawDmgToAttacker, false, false);
		currentBattleAttacker = null;

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

		// "Is dealt damage" triggers watch battle damage as much as ability damage (28-043R Gi
		// Nattak, 18-012L Faris and the Forwards she watches). Fired here, ahead of the break
		// below, for the same reason applyDamageToForward fires ahead of its own break check: the
		// trigger is on being dealt damage, not on surviving it, so a Forward broken by this blow
		// still triggers. Combat damage is dealt simultaneously, so both sides fire together, and
		// a side whose damage First Strike zeroed above was dealt none and does not fire.
		if (dmgToAttacker > 0) autoAbilityTriggers.fireIsDealtDamageTriggers(attacker, attackerIsP1, dmgToAttacker);
		if (dmgToBlocker  > 0) autoAbilityTriggers.fireIsDealtDamageTriggers(blocker,  blockerIsP1, dmgToBlocker);

		// Recorded here for the same reason the triggers fire here: before the break below, so a
		// Forward killed by this blow still counts as damaged by whoever struck it.
		if (dmgToAttacker > 0) recordDamagedBy(attacker, blocker);
		if (dmgToBlocker  > 0) recordDamagedBy(blocker,  attacker);

		// First Strike is already fully accounted for above: the side that strikes first has had the
		// return damage zeroed when its blow was lethal.  A surviving Forward still takes the damage
		// it was dealt, so these branches must not re-test attackerFirst/blockerFirst — doing so
		// dropped the damage entirely whenever the first strike failed to break its target.
		if (attackerBroken) {
			if (attackerIsP1) breakP1Forward(attackerIdx);
			else              breakP2Forward(attackerIdx);
		} else if (dmgToAttacker > 0) {
			List<Integer> dmgList = attackerIsP1 ? p1ForwardDamage : p2ForwardDamage;
			dmgList.set(attackerIdx, dmgList.get(attackerIdx) + dmgToAttacker);
			if (attackerIsP1) refreshP1ForwardSlot(attackerIdx); else refreshP2ForwardSlot(attackerIdx);
		}
		if (blockerBroken) {
			if (blockerIsP1) breakP1Forward(blockerIdx);
			else             breakP2Forward(blockerIdx);
		} else if (dmgToBlocker > 0) {
			List<Integer> dmgList = blockerIsP1 ? p1ForwardDamage : p2ForwardDamage;
			dmgList.set(blockerIdx, dmgList.get(blockerIdx) + dmgToBlocker);
			if (blockerIsP1) refreshP1ForwardSlot(blockerIdx); else refreshP2ForwardSlot(blockerIdx);
		}

		// "When this Forward is dealt damage, break this Forward." — temporary EOT grant on the
		// Forward that took the blow (Vallaide 22-020R). Ahead of Breaktouch below because it is
		// the damaged card's own ability rather than the striker's, and a combatant the main
		// damage already broke needs neither.
		if (!attackerBroken && breakOnDealtDamageGrant(attackerIsP1, ForwardTarget.CardZone.FORWARD,
				attackerIdx, attacker, dmgToAttacker)) attackerBroken = true;
		if (!blockerBroken && breakOnDealtDamageGrant(blockerIsP1, ForwardTarget.CardZone.FORWARD,
				blockerIdx, blocker, dmgToBlocker)) blockerBroken = true;

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
			if (fireBreaktouchForDamage(attacker, attackerIsP1, blockerIsP1, blockerIdx, dmgToBlocker)) blockerBroken = true;
		}
		if (dmgToAttacker > 0 && !attackerBroken) {
			if (fireBreaktouchForDamage(blocker, blockerIsP1, attackerIsP1, attackerIdx, dmgToAttacker)) attackerBroken = true;
		}

		if (!attackerBroken && !blockerBroken) {
			logEntry("Both forwards survive combat");
		}
	}

	// -------------------------------------------------------------------------
	// Attack and block legality, compulsions
	// -------------------------------------------------------------------------

	/**
	 * P2 AI: returns the index of the best P2 blocker against {@code attacker},
	 * or -1 if P2 declines to block.
	 * Strategy: block with the highest-power active forward that can survive (power >= attacker) or trade evenly.
	 */
	/** True when a P2 monster acting as a Forward may be declared as a blocker. */
	boolean p2MonsterCanBlockAsForward(int idx) {
		if (idx < 0 || idx >= p2MonsterStates.size()) return false;
		if (p2MonsterStates.get(idx) != CardState.ACTIVE) return false;
		if (Boolean.TRUE.equals(p2MonsterFrozen.get(idx))) return false;
		CardData card = p2MonsterCards.get(idx);
		if (p2CannotBlock.contains(card) || p2CannotBlockPersistent.contains(card)) return false;
		// The printed restrictions were being checked on P1's side of this row but not here, so a
		// P2 Monster with "cannot block" could still block. Same list as isMonsterBlockSelectable.
		if (card.cannotBlockAtAll() || card.cannotAttackOrBlock()) return false;
		if (blockBarredByFieldCostLock(card)) return false;
		if (isFieldAbilityCannotAttackOrBlock(card, false)) return false;
		return isP2MonsterTemporarilyForward(idx);
	}

	/** True when a P2 backup acting as a Forward may be declared as a blocker. */
	boolean p2BackupCanBlockAsForward(int idx) {
		if (idx < 0 || idx >= p2BackupCards.length || p2BackupCards[idx] == null) return false;
		if (p2BackupStates[idx] != CardState.ACTIVE) return false;
		if (p2BackupFrozen[idx]) return false;
		CardData card = p2BackupCards[idx];
		if (p2CannotBlock.contains(card) || p2CannotBlockPersistent.contains(card)) return false;
		// As in p2MonsterCanBlockAsForward: the printed restrictions were only being read on P1's
		// side of this row.
		if (card.cannotBlockAtAll() || card.cannotAttackOrBlock()) return false;
		if (blockBarredByFieldCostLock(card)) return false;
		if (isFieldAbilityCannotAttackOrBlock(card, false)) return false;
		return isP2BackupTemporarilyForward(idx);
	}

	/**
	 * Checks all cost-filter sources (dynamic, intrinsic, conditional ICB) for a P1 attacker.
	 *
	 * <p>Takes the attacking card rather than a Forward-row index, so it reads the same for an
	 * attacker acting as a Forward from the Monster or Backup row.
	 */
	boolean p1AttackerCostFiltersExclude(CardData attCard, int blockerCost) {
		if (attCard == null) return false;
		if (allForwardsCannotBeBlockedByHigherCostThisTurn
				&& blockerCost > attCard.cost()) return true;
		int[] dyn = p1CannotBeBlockedByCost.get(attCard);
		if (dyn != null && blockerCostExcluded(blockerCost, dyn)) return true;
		int[] intr = attCard.fieldCannotBeBlockedByCost();
		if (intr != null && blockerCostExcluded(blockerCost, intr)) return true;
		for (CardData src : p1ForwardCards)
			for (IfControlBoost icb : src.ifControlBoosts())
				if (icb.cannotBeBlockedByCost() != null && icb.appliesToCard(attCard, jobsStripped(attCard))
						&& icbConditionsMet(icb, true)
						&& blockerCostExcluded(blockerCost, icb.cannotBeBlockedByCost()))
					return true;
		for (CardData bkp : p1BackupCards)
			if (bkp != null)
				for (IfControlBoost icb : bkp.ifControlBoosts())
					if (icb.cannotBeBlockedByCost() != null && icb.appliesToCard(attCard, jobsStripped(attCard))
							&& icbConditionsMet(icb, true)
							&& blockerCostExcluded(blockerCost, icb.cannotBeBlockedByCost()))
						return true;
		for (CardData mon : p1MonsterCards)
			for (IfControlBoost icb : mon.ifControlBoosts())
				if (icb.cannotBeBlockedByCost() != null && icb.appliesToCard(attCard, jobsStripped(attCard))
						&& icbConditionsMet(icb, true)
						&& blockerCostExcluded(blockerCost, icb.cannotBeBlockedByCost()))
					return true;
		return false;
	}

	/**
	 * True if {@code attacker} carries a "Opponent must block [name] if possible" field ability.
	 * Read through {@link #effectiveFieldAbilities} so a granted copy compels a block exactly as a
	 * printed one does — the same rule {@link #forwardCompelledToBlock} follows for its own text.
	 */
	boolean attackerMustBeBlocked(CardData attacker) {
		if (attacker == null) return false;
		for (FieldAbility fa : effectiveFieldAbilities(attacker)) {
			Matcher m = AutoAbilityTriggers.FA_OPPONENT_MUST_BLOCK.matcher(fa.effectText());
			if (m.find() && m.group("cardname").trim().equalsIgnoreCase(attacker.name())) return true;
		}
		return false;
	}

	/**
	 * True when any card on either field compels {@code defenderIsP1}'s Forwards to block —
	 * General Leo 15-021R ("The Forwards you control"), Jack Garland 24-079L ("…opponent controls")
	 * and Layle 16-083H ("All Forwards"), which differ only in whose Forwards they name.
	 *
	 * <p>"you control" / "opponent controls" are read relative to the side the printing card sits on,
	 * so the same sentence binds a different player depending on who controls it.
	 */
	boolean forwardsMustBlock(boolean defenderIsP1) {
		return fieldWideCompulsionBinds(AutoAbilityTriggers.FA_FIELD_FORWARDS_MUST_BLOCK, defenderIsP1);
	}

	/**
	 * The attack-side counterpart of {@link #forwardsMustBlock}: true when any card on either field
	 * compels {@code attackerIsP1}'s Forwards to attack once per turn — Layle 16-083H
	 * ("All Forwards") and Jack Garland 24-079L ("The Forwards opponent controls").
	 */
	boolean forwardsMustAttack(boolean attackerIsP1) {
		return fieldWideCompulsionBinds(AutoAbilityTriggers.FA_FIELD_FORWARDS_MUST_ATTACK, attackerIsP1);
	}

	/**
	 * True when any card on either field carries {@code compulsion} — a field ability naming a whole
	 * side of Forwards — in a way that binds {@code boundIsP1}.
	 *
	 * <p>"you control" and "opponent controls" are read relative to the side the printing card sits
	 * on, so the same sentence binds a different player depending on who controls it; a form naming
	 * no controller ("All Forwards") binds both. The pattern must expose a {@code scope} group,
	 * absent for that uncontrolled form.
	 */
	private boolean fieldWideCompulsionBinds(Pattern compulsion, boolean boundIsP1) {
		for (boolean srcIsP1 : new boolean[] { true, false }) {
			List<CardData> zone = new ArrayList<>(srcIsP1 ? p1ForwardCards : p2ForwardCards);
			for (CardData b : srcIsP1 ? p1BackupCards : p2BackupCards) if (b != null) zone.add(b);
			zone.addAll(srcIsP1 ? p1MonsterCards : p2MonsterCards);
			for (CardData src : zone) {
				if (lostAbilitiesCards.contains(src)) continue;
				for (FieldAbility fa : effectiveFieldAbilities(src)) {
					Matcher m = compulsion.matcher(fa.effectText());
					if (!m.find()) continue;
					String scope = m.group("scope");
					boolean binds = scope == null                                   // "All Forwards"
							|| (scope.toLowerCase().startsWith("you") ? srcIsP1 == boundIsP1
							                                          : srcIsP1 != boundIsP1);
					if (binds) return true;
				}
			}
		}
		return false;
	}

	/**
	 * True when {@code fwd} carries its own standing "must attack once per turn if possible"
	 * ability — Berserker 15-078C and 3-091C, Umaro 17-022H, Reddas 2-072C. The printed counterpart
	 * of {@link #permanentMustAttackOncePerTurn}, which holds the same compulsion when an effect
	 * grants it (Roche 29-076H), and satisfied the same way: one attack settles it for the turn.
	 */
	boolean selfMustAttackOncePerTurn(CardData fwd) {
		if (fwd == null || lostAbilitiesCards.contains(fwd)) return false;
		for (FieldAbility fa : effectiveFieldAbilities(fwd)) {
			Matcher m = AutoAbilityTriggers.FA_SELF_MUST_ATTACK.matcher(fa.effectText());
			if (m.find() && m.group("card").trim().equalsIgnoreCase(fwd.name())) return true;
		}
		return false;
	}

	/**
	 * True when {@code defenderIsP1} may not decline the block — either {@code attacker} carries
	 * "Opponent must block [it] if possible", or a field-wide compulsion names the defender's
	 * Forwards. The two reach the same decision from opposite sides of the field, and every caller
	 * wants the disjunction.
	 */
	boolean blockIsCompelled(CardData attacker, boolean defenderIsP1) {
		return attackerMustBeBlocked(attacker) || forwardsMustBlock(defenderIsP1);
	}

	/**
	 * True if {@code blocker} carries a "This Forward must block [attacker] if possible" ability
	 * naming {@code attacker}. Read through {@link #effectiveFieldAbilities} because the only
	 * current source, Dio 26-075C, grants the text until end of turn rather than printing it.
	 *
	 * <p>The compulsion is attacker-specific — unlike {@link #p1MustBlock}, which restricts
	 * the blocker choice against everything that attacks — so both arguments matter.
	 *
	 * <p>Also true for a standing self-named compulsion ("Ricard must block if possible." 6-103H,
	 * "If possible, Cecil must block." 2-129L), which names no attacker and so binds against every
	 * one. It rides this method rather than the turn-scoped index set because everything downstream
	 * — the blocker-selectable checks, the two human validators, the AI's blocker pick — already
	 * consults it, and because only this path checks that the compelled Forward can actually block
	 * before restricting the choice, which is what "if possible" asks for.
	 */
	boolean forwardCompelledToBlock(CardData blocker, CardData attacker) {
		if (blocker == null || attacker == null) return false;
		if (lostAbilitiesCards.contains(blocker)) return false;
		for (FieldAbility fa : effectiveFieldAbilities(blocker)) {
			Matcher m = AutoAbilityTriggers.FA_THIS_FORWARD_MUST_BLOCK_NAMED.matcher(fa.effectText());
			if (m.find() && m.group("cardname").trim().equalsIgnoreCase(attacker.name())) return true;
			Matcher s = AutoAbilityTriggers.FA_SELF_MUST_BLOCK.matcher(fa.effectText());
			if (s.find() && s.group("card").trim().equalsIgnoreCase(blocker.name())) return true;
		}
		return false;
	}

	/**
	 * True when {@code card} compels the opposing player's Summons ({@code bySummon = true}) or
	 * abilities to choose it while it is a legal target — the targeting counterpart of
	 * {@link #attackerMustBeBlocked}.
	 *
	 * <p>Read through {@link #effectiveFieldAbilities} so a granted copy taunts exactly as a printed
	 * one does: Ricard 17-062C hands this ability out through a conditional grant rather than
	 * printing it outright.
	 */
	boolean mustBeChosenByOpponent(CardData card, boolean bySummon) {
		if (card == null || lostAbilitiesCards.contains(card)) return false;
		for (FieldAbility fa : effectiveFieldAbilities(card)) {
			Matcher m = AutoAbilityTriggers.FA_OPPONENT_MUST_CHOOSE.matcher(fa.effectText());
			if (!m.find() || !m.group("cardname").trim().equalsIgnoreCase(card.name())) continue;
			// The abilities-only printing (Angeal 28-060R) leaves Summons free to choose elsewhere.
			if (!bySummon || m.group("summons") != null) return true;
		}
		return false;
	}

	/**
	 * True when {@code card} carries "If [card] deals damage or is dealt damage while dull, the
	 * damage becomes 0 instead" (Cagnazzo 2-124H) <em>and</em> is dull right now. Both halves of the
	 * ability are gated on the same state, so the incoming, outgoing and player-damage paths all
	 * consult this one check.
	 *
	 * <p>The state is read at the moment damage would apply rather than cached: Cagnazzo's own
	 * "When Cagnazzo blocks, dull Cagnazzo" fires during the battle it then nullifies.
	 */
	boolean damageZeroedWhileDull(CardData card) {
		if (card == null || lostAbilitiesCards.contains(card)) return false;
		boolean carries = false;
		for (FieldAbility fa : effectiveFieldAbilities(card)) {
			Matcher m = AutoAbilityTriggers.FA_DAMAGE_ZERO_WHILE_DULL.matcher(fa.effectText());
			if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) { carries = true; break; }
		}
		if (!carries) return false;
		for (boolean side : new boolean[] { true, false })
			if (findFieldSlot(card, side) != null) return fieldSlotState(card, side) == CardState.DULL;
		return false;
	}

	/**
	 * The P1 Forward index compelled to block {@code attacker} and legally able to, or {@code -1}.
	 * "If possible" is what makes the second half necessary: a compelled Forward that is dull, or
	 * that the attacker's own restrictions exclude, lifts the compulsion instead of deadlocking
	 * the block step. Recursion is avoided by taking the eligibility check apart from
	 * {@link #isForwardBlockSelectable}, which consults this method.
	 */
	private int p1ForwardCompelledToBlockIdx(CardData attacker) {
		if (attacker == null) return -1;
		for (int i = 0; i < p1ForwardCards.size(); i++)
			if (forwardCompelledToBlock(p1ForwardCards.get(i), attacker) && p1ForwardBlockEligible(i))
				return i;
		return -1;
	}

	/**
	 * {@link #p1ForwardCompelledToBlockIdx} against whatever P2 is currently attacking with. A
	 * party is one attack made by several Forwards, so a compulsion naming any member of it
	 * applies — the same reading {@link #attackerMustBeBlocked} already takes for party attacks.
	 */
	private int p1ForwardCompelledToBlockIdxForPendingAttack() {
		if (pendingP2PartyIndices != null) {
			for (int ai : pendingP2PartyIndices) {
				if (ai < 0 || ai >= p2ForwardCards.size()) continue;
				int idx = p1ForwardCompelledToBlockIdx(p2ForwardCards.get(ai));
				if (idx >= 0) return idx;
			}
			return -1;
		}
		return p1ForwardCompelledToBlockIdx(pendingP2Attacker);
	}

	private static boolean blockerCostExcluded(int blockerCost, int[] costFilter) {
		return costFilter[1] == 1 ? blockerCost >= costFilter[0] : blockerCost <= costFilter[0];
	}

	// -------------------------------------------------------------------------
	// Block declaration - local seat and remote replay
	// -------------------------------------------------------------------------

	/**
	 * Called when P2 attacks: sets up interactive block declaration so P1 can click
	 * a forward on the field (or click "Take Damage") instead of using a modal dialog.
	 * {@code onDone} is called asynchronously after combat or damage resolves.
	 */
	void initP1BlockDeclaration(CardData attacker, int attackerIdx, Runnable onDone) {
		// P2's attacker stays "attacking" for the whole combat — including the blocker-declared
		// priority checkpoint — so it is cleared by finish, which every exit path below runs.
		p2DeclaredAttackers.clear();
		p2DeclaredAttackers.add(attacker);
		refreshCombatGlows();   // the attacker turns red before P1 is asked for a blocker
		// Every exit path below runs finish, which makes it this side's single combat boundary —
		// the counterpart to continueAttackPhase on the client that declared the attack.
		Runnable finish = () -> {
			p2DeclaredAttackers.clear();
			refreshCombatGlows();
			resolvePostCombatBreaks();
			sendCombatChecksum();
			onDone.run();
		};

		int displayPow = (pendingP2AttackerIsMonster || pendingP2AttackerIsBackup)
				? pendingP2AttackerPower : effectiveP2ForwardPower(attackerIdx);

		// Combat holds on Declare Attackers while both players pass on the declaration; only then
		// does the block step open and P1 get to choose a blocker.
		setAttackSubStep(1);
		refreshPhaseTracker();
		combatPriorityRound(false, "[P2] " + attacker.name() + " (" + displayPow + ") attacks!", () -> {
			if (survivingDeclaredAttackers(false).isEmpty()) {
				logEntry("No attackers remain — Declare Blockers skipped.");
				setAttackSubStep(-1);
				finish.run();
				return;
			}
			setAttackSubStep(2);
			refreshPhaseTracker();

			if (!hasEligibleP1Blocker()) {
				// Nothing can block, so there is no choice to make — but the block step still passes
				// through a priority round of its own before damage.
				logEntry("No eligible blockers.");
				combatPriorityRound(false, null, () -> {
					setAttackSubStep(3);
					dealCombatDamageToOpponent(attacker, false, () -> {
						autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, false);
						setAttackSubStep(-1);
						finish.run();
					});
				});
				return;
			}

			pendingP2Attacker    = attacker;
			pendingP2AttackerIdx = attackerIdx;
			pendingP2BlockDone   = finish;
			p1BlockerSelection   = -1;
			p1BlockerMonsterIdx  = -1;
			p1BlockerBackupIdx   = -1;

			refreshAttackButton();
			refreshAllForwardSlots();
			logEntry("Select a blocker or click 'Take Damage'.");
		});
	}

	// ── Remote combat: replaying the opponent's attack, and waiting on their block ──

	/**
	 * True while a local attack has been declared and the remote player has not yet answered it.
	 *
	 * <p>Combat is mid-flight but nothing is animating and the Stack is empty, so without this the
	 * board reads as settled and {@link #runWhenBoardSettled} would hand priority on while the
	 * attack is still unanswered.
	 */
	private boolean awaitingRemoteBlock = false;

	/** @see #awaitingRemoteBlock */
	void setAwaitingRemoteBlock(boolean waiting) {
		awaitingRemoteBlock = waiting;
		refreshAttackButton();
		if (skipAttackButton != null && waiting) skipAttackButton.setEnabled(false);
	}

	/** The card the opponent declared an attack with, or {@code null} if that slot is empty here. */
	CardData remoteAttackerAt(ForwardTarget.CardZone zone, int idx) {
		return fieldCardDataOrNull(new ForwardTarget(false, idx, zone));
	}

	/** The effective total power of an attack the opponent declared, as this client computes it. */
	int remoteAttackPower(ForwardTarget.CardZone zone, List<Integer> indices) {
		int total = 0;
		for (int idx : indices) {
			total += switch (zone) {
				case MONSTER -> p2MonsterForwardPower(idx);
				case BACKUP  -> p2BackupForwardPower(idx);
				default      -> effectiveP2ForwardPower(idx);
			};
		}
		return total;
	}

	/**
	 * Replays an attack the remote player declared, as though the AI had declared it.
	 *
	 * <p>Deliberately a mirror of {@code ComputerPlayer.doAttackPhaseInner}'s per-zone bodies: only
	 * the <em>choice</em> of attacker crosses the wire, so everything downstream of the choice —
	 * dulling, becomes-dull and attack triggers, the party bookkeeping, and handing the local
	 * player their block declaration — has to run here exactly as it does for a local AI attack.
	 * Both clients then resolve the same combat from the same state.
	 *
	 * <p>The completion callback is empty on purpose. When the AI attacks it drives its own next
	 * declaration from there; a remote attacker instead sends the next ATTACK, or advances the
	 * phase, of its own accord.
	 */
	void replayRemoteAttack(ForwardTarget.CardZone zone, List<Integer> indices) {
		p2Turn.attackDeclarationsThisTurn++;
		pendingP2AttackerIsMonster = zone == ForwardTarget.CardZone.MONSTER;
		pendingP2AttackerIsBackup  = zone == ForwardTarget.CardZone.BACKUP;
		pendingP2AttackerPower     = remoteAttackPower(zone, indices);
		Runnable onDone = () -> refreshAllForwardSlots();

		if (indices.size() > 1) {
			replayRemotePartyAttack(indices, onDone);
			return;
		}
		int idx = indices.get(0);

		switch (zone) {
			case MONSTER -> {
				CardData attacker = p2MonsterCards.get(idx);
				if (!effectiveMonsterHasTrait(false, idx, CardData.Trait.BRAVE)) {
					p2MonsterStates.set(idx, CardState.DULL);
					animateDullP2Monster(idx);
				}
				recordAttackDeclared(attacker);
				autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, false);
				logEntry("[P2] " + attacker.name() + " attacks! (Forward — " + pendingP2AttackerPower + ")");
				initP1BlockDeclaration(attacker, idx, onDone);
			}
			case BACKUP -> {
				CardData attacker = p2BackupCards[idx];
				if (!effectiveBackupHasTrait(false, idx, CardData.Trait.BRAVE)) {
					p2BackupStates[idx] = CardState.DULL;
					animateDullP2Backup(idx, true);
				}
				recordAttackDeclared(attacker);
				autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, false);
				logEntry("[P2] " + attacker.name() + " attacks! (Forward — " + pendingP2AttackerPower + ")");
				initP1BlockDeclaration(attacker, idx, onDone);
			}
			default -> {
				CardData attacker = p2ForwardPrimedTop.get(idx) != null
						? p2ForwardPrimedTop.get(idx) : p2ForwardCards.get(idx);
				logEntry("[P2] " + attacker.name() + " attacks!");
				CardState before = p2ForwardStates.get(idx);
				if (!effectiveP2HasTrait(idx, CardData.Trait.BRAVE)) {
					p2ForwardStates.set(idx, CardState.DULL);
					animateDullP2Forward(idx, null);
					if (before == CardState.ACTIVE)
						autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(p2ForwardCards.get(idx), false);
				}
				recordAttackDeclared(attacker);
				autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, false);
				initP1BlockDeclaration(attacker, idx, onDone);
			}
		}
	}

	/** The party arm of {@link #replayRemoteAttack}; mirrors {@code ComputerPlayer.executeP2PartyAttack}. */
	private void replayRemotePartyAttack(List<Integer> partyIndices, Runnable onDone) {
		int combinedPower = 0;
		StringBuilder names = new StringBuilder();
		for (int idx : partyIndices) {
			if (!effectiveP2HasTrait(idx, CardData.Trait.BRAVE)) {
				CardState before = p2ForwardStates.get(idx);
				p2ForwardStates.set(idx, CardState.DULL);
				animateDullP2Forward(idx, null);
				if (before == CardState.ACTIVE)
					autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(p2ForwardCards.get(idx), false);
			}
			recordAttackDeclared(effectiveP2Forward(idx));
			combinedPower += effectiveP2ForwardPower(idx);
			if (names.length() > 0) names.append(", ");
			names.append(p2ForwardCards.get(idx).name());
		}
		logEntry("[P2] Party Attack! " + names + " (" + combinedPower + " combined)");
		p2Turn.formedPartyThisTurn = true;
		for (int idx : partyIndices)
			autoAbilityTriggers.triggerAutoAbilitiesForAttack(
					p2ForwardPrimedTop.get(idx) != null ? p2ForwardPrimedTop.get(idx) : p2ForwardCards.get(idx), false);
		autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(false,
				partyIndices.stream().map(p2ForwardCards::get).collect(Collectors.toList()));
		initP1BlockDeclarationVsParty(partyIndices, combinedPower, onDone);
	}

	/** True when P1 controls at least one Forward that is allowed to block right now. */
	private boolean hasEligibleP1Blocker() {
		for (int i = 0; i < p1ForwardStates.size(); i++) {
			CardState s = p1ForwardStates.get(i);
			CardData  c = p1ForwardCards.get(i);
			if (s == CardState.ACTIVE
					&& !p1CannotBlock.contains(c)
					&& !p1CannotBlockPersistent.contains(c)) return true;
		}
		return false;
	}

	void initP1BlockDeclarationVsParty(List<Integer> attackerIndices, int combinedPower, Runnable onDone) {
		p2DeclaredAttackers.clear();
		for (int idx : attackerIndices)
			if (idx < p2ForwardCards.size()) p2DeclaredAttackers.add(effectiveP2Forward(idx));
		refreshCombatGlows();   // the whole party turns red, so the blocker choice reads at a glance
		// Every exit path below runs finish, which makes it this side's single combat boundary —
		// the counterpart to continueAttackPhase on the client that declared the attack.
		Runnable finish = () -> {
			p2DeclaredAttackers.clear();
			refreshCombatGlows();
			resolvePostCombatBreaks();
			sendCombatChecksum();
			onDone.run();
		};

		StringBuilder names = new StringBuilder();
		for (int idx : attackerIndices) {
			if (names.length() > 0) names.append(", ");
			names.append(p2ForwardCards.get(idx).name());
		}
		setAttackSubStep(1);
		refreshPhaseTracker();
		combatPriorityRound(false, "[P2] Party Attack: " + names + " (" + combinedPower + " combined)!", () -> {
			if (survivingDeclaredAttackers(false).isEmpty()) {
				logEntry("No attackers remain — Declare Blockers skipped.");
				setAttackSubStep(-1);
				finish.run();
				return;
			}
			setAttackSubStep(2);
			refreshPhaseTracker();

			if (!hasEligibleP1Blocker()) {
				logEntry("No eligible blockers.");
				combatPriorityRound(false, null, () -> {
					setAttackSubStep(3);
					setPlayerDamageSource(partyExBurstSuppressor(attackerIndices, false));
					p1TakeDamage();
					for (int idx : attackerIndices)
						autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(p2ForwardCards.get(idx), false);
					setAttackSubStep(-1);
					finish.run();
				});
				return;
			}

			pendingP2PartyIndices  = new ArrayList<>(attackerIndices);
			pendingP2PartyCombined = combinedPower;
			pendingP2BlockDone     = finish;
			p1BlockerSelection     = -1;
			p1BlockerMonsterIdx    = -1;
			p1BlockerBackupIdx     = -1;

			refreshAttackButton();
			refreshAllForwardSlots();
			logEntry("Select a blocker or click 'Take Damage'.");
		});
	}


	// -------------------------------------------------------------------------
	// Damage zone, Limit Break and forced-discard dialogs
	// -------------------------------------------------------------------------

	/**
	 * Previews the damage-zone card sitting in slot {@code slotIdx} in the side panel.
	 * No-op for the empty slots below the current damage count.
	 */
	private void previewDamageZoneCard(boolean isP1, int slotIdx) {
		List<CardData> dz = isP1 ? gameState.getP1DamageZone() : gameState.getP2DamageZone();
		if (slotIdx < dz.size()) showZoomAt(dz.get(slotIdx).imageUrl());
	}

	/**
	 * Offers "Dismiss EX" on a right-click anywhere on P1's damage-zone stack while an EX Burst is
	 * pending.  {@code invoker} is the component the popup is anchored to (the slot under the
	 * cursor, or the stack itself).
	 */
	private void showP1DamageZoneContextMenu(JPanel slotsPanel, JPanel invoker, MouseEvent e) {
		if (!SwingUtilities.isRightMouseButton(e)) return;
		if (slotsPanel.getClientProperty("exBurst") != Boolean.TRUE) return;
		JPopupMenu menu = new JPopupMenu();
		JMenuItem clearEx = new JMenuItem("Dismiss EX");
		clearEx.addActionListener(ae -> {
			slotsPanel.putClientProperty("exBurst", Boolean.FALSE);
			for (JPanel s : p1DamageSlots) { if (s != null) s.repaint(); }
			slotsPanel.repaint();
		});
		menu.add(clearEx);
		menu.show(invoker, e.getX(), e.getY());
	}

	private void showLbDialog() {
		LbDialog.show(frame, gameState.getP1LbDeck(), new LbDialog.Callbacks() {
			public boolean isSpent(int idx) { return spentLbIndices.contains(idx); }
			public boolean isNameBlocked(CardData card) {
				return !castRestrictionMet(card)
						|| ((card.isForward() || card.isBackup() || card.isMonster())
							&& ((!card.multicard() && hasCharacterNameOnField(card.name()) && !isMultiNameExceptionActive(card.name(), true))
								|| isLightDarkConflict(card)));
			}
			public int effectiveCastCost(CardData card) { return MainWindow.this.effectiveCastCost(card); }
			public void onConfirm(CardData cast, int castIdx, Set<Integer> paymentSet) {
				if (MainWindow.this.effectiveCastCost(cast) <= 0) {
					spentLbIndices.add(castIdx);
					spentLbIndices.addAll(paymentSet);
					logEntry("Cast LB \"" + cast.name() + "\"");
					executeLbPlay(cast, Collections.emptyList(), Collections.emptyList());
				} else {
					showLbCpPaymentDialog(cast, castIdx, new HashSet<>(paymentSet));
				}
			}
			public void onZoom(String url) { showZoomAt(url); }
			public void onZoomHide()       { hideZoom(); }
		});
	}

	private void showEndPhaseDiscardDialog() {
		List<CardData> hand = gameState.getP1Hand();
		if (hand.size() <= 5) return;
		int mustDiscard = hand.size() - 5;
		HandPickDialog.showEndPhaseDiscard(frame, hand, mustDiscard, this::showZoomAt, this::hideZoom, selected -> {
			// Replicated because it changes which card sits at which hand index, and every later
			// play addresses the hand by index.
			sendToOpponent(RemoteOpponent.discardAction(selected));
			selected.sort(Collections.reverseOrder());
			for (int di : selected) playerBreakFromHand(true, di);
			logEntry("Discarded " + selected.size() + " card(s) — hand reduced to 5");
			refreshP1HandLabel();
			refreshP1BreakLabel();
		});
	}

	/**
	 * Shows a modal dialog letting P1 choose exactly {@code count} cards
	 * (or fewer if hand is smaller) to discard to the Break Zone.  No CP is generated.
	 * Called when P2 activates a "Your opponent discards N cards" ability.
	 */
	void showForcedDiscardDialog(int count, boolean forcedByOpponent) {
		List<CardData> hand = gameState.getP1Hand();
		int mustDiscard = Math.min(count, hand.size());
		if (mustDiscard == 0) return;
		HandPickDialog.showForcedDiscard(frame, hand, mustDiscard, this::showZoomAt, this::hideZoom, selected -> {
			selected.sort(Collections.reverseOrder());
			for (int di : selected) {
				CardData d = playerBreakFromHand(true, di);
				if (d != null) {
					logEntry("Discards " + d.name() + (forcedByOpponent ? " (forced by opponent)" : ""));
					lastDiscardedCard = d;
					lastDiscardedCardName = d.name();
				}
			}
			if (!selected.isEmpty()) p1Turn.discardedByEffectThisTurn = true;
			refreshP1HandLabel();
			refreshP1BreakLabel();
		});
	}

	/**
	 * Shows a modal dialog letting P1 choose exactly 1 card of the given type to discard.
	 * Returns true if a card was discarded, false if no eligible cards (no dialog shown).
	 * No "Pass" button — player already committed by accepting the "you may?" prompt.
	 */
	boolean showDiscardByTypeDialog(String cardType) {
		List<CardData> hand = gameState.getP1Hand();
		List<Integer> eligible = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (matchesDiscardType(hand.get(i), cardType)) eligible.add(i);
		}
		if (eligible.isEmpty()) return false;
		return HandPickDialog.showDiscardByType(frame, hand, eligible, cardType,
				this::showZoomAt, this::hideZoom, idx -> {
					CardData d = playerBreakFromHand(true, idx);
					if (d != null) {
						logEntry("Discards " + d.name());
						p1Turn.discardedByEffectThisTurn = true;
						lastDiscardedCardName = d.name();
						if (d.isForward()) lastDiscardedForwardPower = d.power();
					}
					refreshP1HandLabel();
					refreshP1BreakLabel();
				});
	}

	boolean showDiscardByJobDialog(String jobName) {
		List<CardData> hand = gameState.getP1Hand();
		List<Integer> eligible = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (CardFilters.meetsJobFilter(hand.get(i), jobName)) eligible.add(i);
		}
		if (eligible.isEmpty()) return false;
		return HandPickDialog.showDiscardByType(frame, hand, eligible, "Job " + jobName,
				this::showZoomAt, this::hideZoom, idx -> {
					CardData d = playerBreakFromHand(true, idx);
					if (d != null) {
						logEntry("Discards " + d.name());
						p1Turn.discardedByEffectThisTurn = true;
						lastDiscardedCardName = d.name();
						if (d.isForward()) lastDiscardedForwardPower = d.power();
					}
					refreshP1HandLabel();
					refreshP1BreakLabel();
				});
	}

	boolean showDiscardByElementDialog(String element) {
		List<CardData> hand = gameState.getP1Hand();
		List<Integer> eligible = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (hand.get(i).containsElement(element)) eligible.add(i);
		}
		if (eligible.isEmpty()) return false;
		return HandPickDialog.showDiscardByType(frame, hand, eligible, element + " card",
				this::showZoomAt, this::hideZoom, idx -> {
					CardData d = playerBreakFromHand(true, idx);
					if (d != null) {
						logEntry("Discards " + d.name());
						p1Turn.discardedByEffectThisTurn = true;
						lastDiscardedCardName = d.name();
						if (d.isForward()) lastDiscardedForwardPower = d.power();
					}
					refreshP1HandLabel();
					refreshP1BreakLabel();
				});
	}

	/**
	 * Lets P1 optionally reveal 1 card of {@code element} from hand (card stays in hand).
	 * Returns {@code true} if the player revealed one, {@code false} if they passed or had no eligible cards.
	 */
	boolean showRevealByElementFromHandDialog(String element) {
		List<CardData> hand = gameState.getP1Hand();
		List<Integer> eligible = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (hand.get(i).containsElement(element)) eligible.add(i);
		}
		if (eligible.isEmpty()) return false;
		boolean revealed = HandPickDialog.showRevealByElement(frame, hand, eligible, element,
				this::showZoomAt, this::hideZoom);
		if (revealed) logEntry("Reveals a " + element + " card from hand");
		return revealed;
	}

	/**
	 * Shows a picker for P1 to choose 1 EX Burst card from {@code eligible} (cards already
	 * filtered from the Damage Zone). Returns the chosen card, or {@code null} if Pass is clicked.
	 */
	CardData showPickExBurstFromDamageZoneDialog(List<CardData> eligible) {
		return cardPickerDialog.pickExBurst(eligible);
	}

	/**
	 * Shows the player a single-select dialog over {@code cards} and returns the chosen
	 * index, or {@code -1} if the user cancels (when {@code cancelable} is true) or the
	 * list is empty. The user must pick exactly one card to confirm.
	 */
	int showPickOneCardDialog(String title, String prompt, List<CardData> cards,
	                          String confirmLabel, boolean cancelable) {
		return cardPickerDialog.pickOne(title, prompt, cards, confirmLabel, cancelable);
	}

	/** Repaints damage-zone slot visuals from {@code gameState} after non-draw mutations. */
	void refreshDamageZoneSlots(boolean isP1) {
		List<CardData> dz   = isP1 ? gameState.getP1DamageZone() : gameState.getP2DamageZone();
		JPanel[]      slots = isP1 ? p1DamageSlots               : p2DamageSlots;
		for (int i = 0; i < slots.length; i++) {
			JPanel slot = slots[i];
			if (slot == null) continue;
			if (i < dz.size()) {
				CardData cd = dz.get(i);
				slot.putClientProperty("isExBurst", cd.exBurst() ? Boolean.TRUE : Boolean.FALSE);
				final JPanel fSlot = slot;
				final String url   = cd.imageUrl();
				new SwingWorker<Image, Void>() {
					@Override protected Image doInBackground() throws Exception { return ImageCache.load(url); }
					@Override protected void done() {
						try {
							Image img = get();
							if (img != null) { fSlot.putClientProperty("cardImg", img); fSlot.repaint(); }
						} catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();
			} else {
				slot.putClientProperty("cardImg",  null);
				slot.putClientProperty("isExBurst", null);
				slot.repaint();
			}
		}
	}

	// -------------------------------------------------------------------------
	// Hand selection, hand reveal, remote-choice plumbing
	// -------------------------------------------------------------------------

	void showPlaceToBottomOfDeckDialog(int count) {
		showPlaceToBottomOfDeckDialog(count, false);
	}

	/**
	 * Lets P1 place cards from their hand at the bottom of their deck.
	 *
	 * @param upTo when {@code true} P1 may place any number from 0 to {@code count}; otherwise
	 *             exactly {@code count} (capped by hand size) must be placed
	 * @return how many cards were actually placed
	 */
	int showPlaceToBottomOfDeckDialog(int count, boolean upTo) {
		List<CardData> hand = gameState.getP1Hand();
		int mustPlace = Math.min(count, hand.size());
		if (mustPlace == 0) return 0;
		int[] placed = { 0 };
		HandPickDialog.showPlaceToBottom(frame, hand, mustPlace, upTo, this::showZoomAt, this::hideZoom, selected -> {
			selected.sort(Collections.reverseOrder());
			for (int pi : selected) {
				CardData d = gameState.getP1Hand().remove(pi);
				gameState.getP1MainDeck().addLast(d);
				logEntry("Places " + d.name() + " at bottom of deck");
			}
			placed[0] = selected.size();
			refreshP1HandLabel();
			refreshP1DeckLabel();
		});
		return placed[0];
	}

	/**
	 * Shows a modal dialog letting P1 select {@code count} cards from {@code targetHand}
	 * to remove from the game permanently.
	 * If {@code rfpIsP1}, the cards go to P1's permanent RFP zone (P1 removing from own hand);
	 * otherwise they go to P2's (P1 selecting from P2's revealed hand).
	 */
	void showHandRfpSelectionDialog(List<CardData> targetHand, int count, boolean rfpIsP1) {
		int mustSelect = Math.min(count, targetHand.size());
		if (mustSelect == 0) return;
		HandPickDialog.showHandRfp(frame, targetHand, mustSelect, this::showZoomAt, this::hideZoom, selected -> {
			selected.sort(Collections.reverseOrder());
			for (int ri : selected) {
				if (ri < targetHand.size()) {
					CardData d = targetHand.remove(ri);
					gameState.addToPermanentRfp(d);
					if (rfpIsP1) {
						logEntry("Removed from game: " + d.name());
					} else {
						logEntry("[P2] Removed from game (selected by P1): " + d.name());
					}
				}
			}
			if (rfpIsP1) { refreshP1HandLabel(); refreshP1WarpZoneUI(); }
			else          { refreshP2HandCountLabel(); }
		});
	}

	/**
	 * Shows {@code choices} — already narrowed by the caller to the cards that may legally be
	 * picked — and lets P1 select {@code count} of them.  Returns the selected cards so the
	 * caller can apply whatever effect the ability calls for; this method moves nothing itself.
	 * {@code verbPhrase} and {@code buttonLabel} word the dialog (see
	 * {@link HandPickDialog#showHandSelect}).
	 */
	List<CardData> showHandSelectionDialog(List<CardData> choices, int count,
	                                        String verbPhrase, String buttonLabel) {
		int mustSelect = Math.min(count, choices.size());
		if (mustSelect == 0) return List.of();
		List<CardData> picked = new ArrayList<>();
		HandPickDialog.showHandSelect(frame, choices, mustSelect, verbPhrase, buttonLabel,
				this::showZoomAt, this::hideZoom,
				selected -> { for (int ri : selected) if (ri < choices.size()) picked.add(choices.get(ri)); });
		return picked;
	}

	/**
	 * Puts one question to one seat and returns the answer.
	 *
	 * <p>All three ways a seat can be answered live here, so an effect describes its question once
	 * rather than branching on who is sitting there.  The local player answers in a dialog and the
	 * answer is transmitted — the opponent's client is parked waiting for it, and forgetting to
	 * send is what hangs a game.  A remote player's answer is waited for and checked before it is
	 * acted on.  The AI's is computed.
	 *
	 * <p>Only the answer crosses the wire, never what it caused.  Both clients then run the same
	 * rules over the same answer, which is what keeps them in step.
	 *
	 * @see PlayerChoice for what a question is made of and why the seat is not the player
	 */
	List<Integer> decide(PlayerChoice choice) {
		if (choice.chooserIsP1()) {
			List<Integer> answer = choice.localAnswer().get();
			if (opponent instanceof RemoteOpponent remote)
				remote.send(RemoteOpponent.choiceAction(choice.kind(), answer));
			return answer;
		}
		if (opponent instanceof RemoteOpponent remote) return remote.awaitAnswer(choice);
		return choice.cpuAnswer().get();
	}

	/**
	 * The player at seat {@code chooserIsP1} picks 1 of {@code eligible}, all of which sit on their
	 * own side of the board — "each player selects 1 Forward", and the effects that make a player
	 * break something of their own.  Returns {@code null} when nothing was chosen.
	 *
	 * <p>Deliberately not {@link #showForwardSelectDialog}, which auto-picks when only one card is
	 * eligible.  The card text says the player selects, so the choice stays explicit even with a
	 * single Forward on the field — Brute Bomber standing alone is still selected, not assigned.
	 * The auto-pick would also be a decision this client made and never transmitted, which the
	 * other client has no way to reproduce.
	 *
	 * @param title      names the choice on the selection bar, for the player making it
	 * @param waitPrompt names it for the player waiting on it
	 * @param cpuPick    the AI's answer; may return {@code null} to decline
	 */
	ForwardTarget selectOwnFieldTarget(boolean chooserIsP1, List<ForwardTarget> eligible,
	                                   String title, String waitPrompt,
	                                   Supplier<ForwardTarget> cpuPick) {
		List<ForwardTarget> picks = selectOwnFieldTargets(chooserIsP1, eligible, 1, false,
				title, waitPrompt, () -> {
					ForwardTarget pick = cpuPick.get();
					return pick == null ? List.of() : List.of(pick);
				});
		return picks.isEmpty() ? null : picks.get(0);
	}

	/**
	 * The many-card form of {@link #selectOwnFieldTarget}: the player at seat {@code chooserIsP1}
	 * picks up to {@code count} of {@code eligible}, all on their own side of the board.  Returns
	 * the picks in the order they were made, empty when nothing was chosen.
	 *
	 * @param count      the most the chooser may pick
	 * @param upTo       {@code true} lets them confirm with fewer than {@code count}
	 * @param cpuPick    the AI's answer; may return an empty list to pick nothing
	 */
	List<ForwardTarget> selectOwnFieldTargets(boolean chooserIsP1, List<ForwardTarget> eligible,
	                                          int count, boolean upTo,
	                                          String title, String waitPrompt,
	                                          Supplier<List<ForwardTarget>> cpuPick) {
		if (eligible.isEmpty()) return List.of();
		List<Integer> answer = decide(PlayerChoice.by(chooserIsP1, ChoiceKind.OWN_FIELD_CARD)
				.prompting(waitPrompt)
				.locally(() -> selectFieldTargetsInPlace(eligible, count, upTo, title)
						.stream().map(ForwardTarget::choiceCode).toList())
				.byCpu(() -> cpuPick.get().stream().map(ForwardTarget::choiceCode).toList())
				// The chooser packed their own side; from here that side is the opponent's.
				.arrivingAs(ForwardTarget::flipChoiceSide)
				// Size as well as membership: "up to 2" is a bound the sender could exceed, and a
				// third pick arriving unchecked would spare a Forward the effect must take.
				.legalWhen(codes -> codes.size() <= count && codes.stream().allMatch(code -> {
					ForwardTarget t = ForwardTarget.fromChoiceCode(code);
					return t != null && eligible.contains(t);
				}), "no such card of theirs is eligible here"));
		List<ForwardTarget> out = new ArrayList<>(answer.size());
		for (int code : answer) {
			ForwardTarget t = ForwardTarget.fromChoiceCode(code);
			if (t != null) out.add(t);
		}
		return out;
	}

	/**
	 * The player at seat {@code revealerIsP1} reveals {@code count} cards <em>of their own
	 * choosing</em> from hand; returns the hand indices they showed, ascending.
	 *
	 * <p>Indices rather than cards because the answer may have to cross the wire, and both clients
	 * hold the same hand in the same order — the same convention a replayed play follows.
	 *
	 * <p>A hand of {@code count} cards or fewer makes the choice forced, so nobody is asked and the
	 * whole hand is shown.  That case is derived independently on each client rather than
	 * transmitted: with no decision in it, both arrive at the same answer.
	 */
	/**
	 * True when an ability's 《S》 cost and its reveal cost are after the same hand card, so one of
	 * the cards matching the reveal is already spoken for and must not be counted twice.
	 *
	 * <p>Only when the 《S》 has to come out of hand: a Crystal payment (Glaciela Wezette 17-113L)
	 * or a counter waiver (Wakka 16-138S) leaves the hand untouched, and the same-named copy is
	 * then free to be revealed.  Rinoa 18-097R is the printing that raises the question — her
	 * Angelo Cannon is a Special whose other cost wants a Forward, and a second Rinoa in hand is
	 * both.
	 */
	private boolean revealAndSpecialCostCompeteForOneCard(ActionAbility ability, CardData source, boolean isP1) {
		if (!ability.isSpecial() || ability.revealCost() == null) return false;
		if (canPaySpecialCostWithCrystal(source, isP1)) return false;
		if (specialCostCounterWaiver(source, isP1) != null) return false;
		for (CardData c : playerHand(isP1))
			if (c.name().equalsIgnoreCase(source.name()) && ability.revealCost().matches(c)) return true;
		return false;
	}

	/**
	 * Pays an ability's {@link RevealCost} and returns the highest power among the cards shown —
	 * the figure Rinoa 18-097R's Angelo Cannon deals as damage.  Returns 0 when nothing was shown.
	 *
	 * <p>Revealed cards stay in hand: the cost spends information, not cards.  That is what makes
	 * the CPU's pick the opposite of a discard's — {@link #revealHandCards} shows its cheapest
	 * because those are the ones it can bear to be seen holding, while here the effect scales with
	 * what was shown, so it shows its strongest.
	 *
	 * <p>Answers as hand indices, and the "no decision in it" case is derived on each client rather
	 * than transmitted, both for the reasons {@link #revealHandCards} documents.
	 */
	int payRevealCost(RevealCost cost, boolean isP1) {
		if (cost == null) return 0;
		List<CardData> hand = playerHand(isP1);
		List<Integer> eligible = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) if (cost.matches(hand.get(i))) eligible.add(i);
		if (eligible.isEmpty()) return 0;

		List<Integer> shown = eligible.size() <= cost.count() ? eligible
				: decide(PlayerChoice.by(isP1, ChoiceKind.REVEAL_HAND)
					.prompting("Waiting for your opponent to reveal " + cost.count()
							+ " card(s) from their hand...")
					.locally(() -> {
						List<CardData> pool = new ArrayList<>();
						for (int i : eligible) pool.add(hand.get(i));
						List<Integer> out = new ArrayList<>();
						for (CardData c : showHandSelectionDialog(pool, cost.count(),
								"reveal to your opponent", "Reveal")) {
							int i = handIndexByIdentity(hand, c);
							if (i >= 0) out.add(i);
						}
						Collections.sort(out);
						return out;
					})
					.byCpu(() -> {
						List<Integer> ranked = new ArrayList<>(eligible);
						ranked.sort((a, b) -> hand.get(b).power() - hand.get(a).power());
						List<Integer> out = new ArrayList<>(
								ranked.subList(0, Math.min(cost.count(), ranked.size())));
						Collections.sort(out);
						return out;
					})
					.legalWhen(sel -> sel.size() <= cost.count() && eligible.containsAll(sel),
							"only a matching card in hand can pay a reveal cost"));

		if (shown.isEmpty()) return 0;
		int best = 0;
		StringBuilder names = new StringBuilder();
		for (int i : shown) {
			CardData c = hand.get(i);
			best = Math.max(best, c.power());
			if (names.length() > 0) names.append(", ");
			names.append(c.name());
		}
		// A revealed card is public, so it is named in the log whoever revealed it.
		logEntry((isP1 ? "" : "[P2] ") + "Revealed " + names + " from hand (cost)");
		return best;
	}

	List<Integer> revealHandCards(boolean revealerIsP1, int count) {
		List<CardData> hand = revealerIsP1 ? gameState.getP1Hand() : gameState.getP2Hand();
		if (hand.isEmpty()) return List.of();
		if (hand.size() <= count) {
			List<Integer> all = new ArrayList<>();
			for (int i = 0; i < hand.size(); i++) all.add(i);
			return all;
		}
		return decide(PlayerChoice.by(revealerIsP1, ChoiceKind.REVEAL_HAND)
				.prompting("Waiting for your opponent to reveal " + count
						+ " cards from their hand...")
				.locally(() -> {
					List<CardData> picked = showHandSelectionDialog(new ArrayList<>(hand), count,
							"reveal to your opponent", "Reveal");
					List<Integer> revealed = new ArrayList<>();
					for (CardData c : picked) {
						int i = handIndexByIdentity(hand, c);
						if (i >= 0) revealed.add(i);
					}
					Collections.sort(revealed);
					return revealed;
				})
				.byCpu(() -> {
					// The CPU shows its least valuable cards — the low-cost-first heuristic a
					// forced discard uses, applied repeatedly to a shrinking copy so the picks
					// stay distinct.
					List<CardData> pool   = new ArrayList<>(hand);
					List<Integer>  origin = new ArrayList<>();
					for (int i = 0; i < hand.size(); i++) origin.add(i);
					List<Integer> revealed = new ArrayList<>();
					for (int n = 0; n < count && !pool.isEmpty(); n++) {
						int worst = pickWorstHandCard0(pool);
						revealed.add(origin.remove(worst));
						pool.remove(worst);
					}
					Collections.sort(revealed);
					return revealed;
				})
				.legalWhen(shown -> shown.stream().allMatch(i -> i >= 0 && i < hand.size()),
						"their hand holds " + hand.size() + " cards here"));
	}

	/**
	 * The player at seat {@code selectorIsP1} picks 1 of the cards their opponent just revealed.
	 * {@code revealedIndices} indexes the opponent's hand; the return is one of those indices, or
	 * -1 when nothing was selected.
	 */
	int selectRevealedHandCard(boolean selectorIsP1, List<Integer> revealedIndices) {
		List<CardData> oppHand = selectorIsP1 ? gameState.getP2Hand() : gameState.getP1Hand();
		List<CardData> revealed = new ArrayList<>();
		List<Integer>  usable   = new ArrayList<>();
		for (int i : revealedIndices) {
			if (i < 0 || i >= oppHand.size()) continue;
			revealed.add(oppHand.get(i));
			usable.add(i);
		}
		if (revealed.isEmpty()) return -1;
		List<Integer> answer = decide(PlayerChoice.by(selectorIsP1, ChoiceKind.SELECT_REVEALED)
				.prompting("Waiting for your opponent to select a card to discard...")
				.locally(() -> {
					List<CardData> picked = showHandSelectionDialog(revealed, 1,
							"discard from the revealed cards", "Discard");
					if (picked.isEmpty()) return List.of();
					int rel = handIndexByIdentity(revealed, picked.get(0));
					return rel < 0 ? List.of() : List.of(usable.get(rel));
				})
				.byCpu(() -> {
					// The CPU takes the most expensive card it was shown.
					int best = usable.get(0);
					for (int i : usable) if (oppHand.get(i).cost() > oppHand.get(best).cost()) best = i;
					return List.of(best);
				})
				.legalWhen(usable::containsAll, "it was not among the cards revealed to them"));
		return answer.isEmpty() ? -1 : answer.get(0);
	}

	/**
	 * The player at seat {@code dividerIsP1} splits {@code forwards} — the Forward row belonging to
	 * their <em>opponent</em> — into {@code groupCount} groups. Returns the group each Forward was
	 * put in, one entry per card in the order given; empty when the answer could not be acted on.
	 *
	 * <p>Positions rather than slot codes, and so nothing to flip on arrival: both clients hold that
	 * row in the same order, and a group number means the same thing whichever side asked.
	 *
	 * <p>A group may be empty and that is the point of the card, so no answer is rejected for
	 * leaving one out. What is rejected is an answer that does not cover the row exactly once —
	 * {@link DivideIntoGroupsDialog#showDivide} will not submit until every Forward is placed.
	 */
	List<Integer> divideForwardsIntoGroups(boolean dividerIsP1, List<CardData> forwards, int groupCount) {
		if (forwards.isEmpty() || groupCount <= 0) return List.of();
		return decide(PlayerChoice.by(dividerIsP1, ChoiceKind.DIVIDE_GROUPS)
				.prompting("Waiting for your opponent to divide your Forwards into "
						+ groupCount + " groups...")
				.locally(() -> DivideIntoGroupsDialog.showDivide(
						frame, forwards, groupCount, this::showZoomAt, this::hideZoom))
				.byCpu(() -> cpuDivideIntoGroups(forwards, groupCount))
				.legalWhen(a -> isGroupAssignment(a, forwards.size(), groupCount),
						"that row holds " + forwards.size() + " Forwards here"));
	}

	/**
	 * The AI's split: the Forwards dealt out strongest first, one per group in rotation.
	 *
	 * <p>Whoever divides does not choose what survives, so the aim is not a good group but a board
	 * with no good group left on it — every option as close to equally poor as the row allows.
	 * Round-robin off a strength-sorted row is the standard way to reach that, and it keeps the
	 * biggest Forwards apart, which is what makes each remaining choice a small one.
	 */
	private List<Integer> cpuDivideIntoGroups(List<CardData> forwards, int groupCount) {
		List<Integer> order = new ArrayList<>();
		for (int i = 0; i < forwards.size(); i++) order.add(i);
		order.sort((a, b) -> forwards.get(b).power() - forwards.get(a).power());
		List<Integer> assignment = new ArrayList<>(java.util.Collections.nCopies(forwards.size(), 0));
		for (int n = 0; n < order.size(); n++) assignment.set(order.get(n), n % groupCount);
		return assignment;
	}

	/**
	 * The player at seat {@code selectorIsP1} — the one whose Forwards these are — keeps one of the
	 * groups {@code assignment} describes. Returns its number, or {@code -1} when no answer could be
	 * acted on, in which case the caller must break nothing rather than guess.
	 */
	int selectGroupToKeep(boolean selectorIsP1, List<CardData> forwards,
			List<Integer> assignment, int groupCount) {
		List<Integer> answer = decide(PlayerChoice.by(selectorIsP1, ChoiceKind.SELECT_GROUP)
				.prompting("Waiting for your opponent to choose which group to keep...")
				.locally(() -> List.of(DivideIntoGroupsDialog.showSelect(
						frame, forwards, assignment, groupCount, this::showZoomAt, this::hideZoom)))
				.byCpu(() -> List.of(cpuBestGroup(forwards, assignment, groupCount)))
				.legalWhen(a -> a.size() == 1 && a.get(0) >= 0 && a.get(0) < groupCount,
						"there are only " + groupCount + " groups to keep"));
		return answer.isEmpty() ? -1 : answer.get(0);
	}

	/**
	 * The group the AI keeps: the one worth most in total power, and on a tie the one holding more
	 * Forwards. Power rather than count because the choice is about what stays on the board, and a
	 * single large Forward routinely outweighs two small ones.
	 */
	private int cpuBestGroup(List<CardData> forwards, List<Integer> assignment, int groupCount) {
		int best = 0;
		int bestPower = -1;
		int bestCount = -1;
		for (int g = 0; g < groupCount; g++) {
			int power = 0;
			int count = 0;
			for (int i = 0; i < forwards.size() && i < assignment.size(); i++) {
				if (assignment.get(i) != g) continue;
				power += forwards.get(i).power();
				count++;
			}
			if (power > bestPower || (power == bestPower && count > bestCount)) {
				best = g; bestPower = power; bestCount = count;
			}
		}
		return best;
	}

	/**
	 * Whether {@code assignment} describes a division of a row of {@code size} Forwards into
	 * {@code groupCount} groups: one group number per Forward, every one of them a real group.
	 *
	 * <p>Asked of a local answer as readily as a remote one. {@code decide} runs its legality
	 * test over what arrives from another client only, and the divide dialog hands back a
	 * holding-row marker for anything left unplaced — a marker that belongs to no group and so
	 * would read as "not the group being kept", taking the whole row with it.
	 */
	static boolean isGroupAssignment(List<Integer> assignment, int size, int groupCount) {
		return assignment.size() == size
				&& assignment.stream().allMatch(g -> g != null && g >= 0 && g < groupCount);
	}

	/**
	 * Puts every Forward on {@code ownerIsP1}'s row into the Break Zone except those in group
	 * {@code kept} — the last step of Kefka 15-071H, once both players have answered.
	 * {@code assignment} holds one group number per Forward, in slot order.
	 *
	 * <p>Highest slot first: the row closes up behind each card that leaves, so taking a lower
	 * index first would shift every card still waiting. Removal goes through {@code ctx} rather
	 * than straight to the board so the shields that answer to a put-into-the-Break-Zone effect
	 * still get their say — the division named the whole row, but a card protected from the
	 * opponent's effects is protected from this one too.
	 *
	 * <p>An assignment that does not describe the row is ignored outright, and nothing is
	 * removed: a division nobody made is not a reason to clear a board.
	 */
	void putUnkeptForwardGroupsIntoBreakZone(GameContext ctx, boolean ownerIsP1,
			List<Integer> assignment, int groupCount, int kept) {
		List<CardData> row = ownerIsP1 ? p1ForwardCards : p2ForwardCards;
		if (!isGroupAssignment(assignment, row.size(), groupCount)) return;
		for (int i = row.size() - 1; i >= 0; i--) {
			if (assignment.get(i) == kept) continue;
			ctx.forceTargetToBreakZone(new ForwardTarget(ownerIsP1, i, ForwardTarget.CardZone.FORWARD));
		}
	}

	/** Position of {@code card} in {@code list} by identity, or -1. Two copies are distinct here. */
	private static int handIndexByIdentity(List<CardData> list, CardData card) {
		for (int i = 0; i < list.size(); i++) if (list.get(i) == card) return i;
		return -1;
	}

	/**
	 * Builds — but does not show — the modal dialog that parks this client while the opponent
	 * makes a choice only they can make.
	 *
	 * <p>Modal on purpose.  {@code setVisible(true)} starts a nested event loop, and inbound
	 * actions already arrive on the EDT, so the answer this dialog is waiting for can still be
	 * delivered while it is up — which is what disposes it.  It carries no buttons: there is
	 * nothing for this player to decide, and dismissing it early would resume a rule mid-effect.
	 */
	JDialog buildWaitingForOpponentDialog(String prompt) {
		JDialog dialog = new JDialog(frame, "Waiting for opponent", true);
		dialog.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
		JLabel label = new JLabel(prompt, SwingConstants.CENTER);
		label.setBorder(BorderFactory.createEmptyBorder(
				UiScale.scale(24), UiScale.scale(32), UiScale.scale(24), UiScale.scale(32)));
		dialog.add(label);
		dialog.pack();
		dialog.setLocationRelativeTo(frame);
		return dialog;
	}

	/**
	 * Shows P2's revealed hand and lets P1 optionally select 1 card to remove from the game.
	 * Returns the selected card, or {@code null} if P1 clicked Skip.
	 */
	CardData showRevealHandOptPickDialog(List<CardData> hand) {
		return cardPickerDialog.pickOptional(hand);
	}

	/**
	 * Shows a dialog letting P1 choose which Summons from {@code summons} to reveal.
	 * Any count (including 0) is valid. Returns the selected cards.
	 * Hint text communicates the thresholds: 0 → source breaks; minForBonus+ → bonus effect.
	 */
	/** The conditional shape — revealing none breaks the source, {@code minForBonus} buys the extra effect. */
	List<CardData> showRevealSummonsFromHandDialog(List<CardData> summons, String sourceName, int minForBonus) {
		return cardPickerDialog.pickRevealSummons(summons, sourceName,
				"Reveal 0 : " + sourceName + " breaks. Reveal " + minForBonus + "+ : bonus effect.",
				"Reveal 0 (" + sourceName + " breaks)");
	}

	/** The scaled shape — the number revealed is how many targets the follow-up effect gets. */
	List<CardData> showRevealSummonsFromHandDialog(List<CardData> summons, String sourceName, String hint) {
		return cardPickerDialog.pickRevealSummons(summons, sourceName, hint, "Reveal 0 (no effect)");
	}

	// -------------------------------------------------------------------------
	// Async image loading helpers
	// -------------------------------------------------------------------------

	/**
	 * Loads the card image for {@code url} at its native resolution and
	 * displays it in the side-panel preview.  The first time this is called
	 * the side panel is resized to exactly fit the card plus {@link #SIDE_MARGIN}.
	 */
	void showZoomAt(String url) {
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
	void hideZoom() {
		startFadeOut();
	}

	/** Fades the preview in from transparent to opaque (~120 ms). */
	private void startFadeIn() {
		if (fadeTimer != null) fadeTimer.stop();
		previewAlpha = 0f;
		cardPreviewPanel.repaint();
		fadeTimer = new Timer(16, e -> {
			previewAlpha = Math.min(1f, previewAlpha + 0.15f);
			cardPreviewPanel.repaint();
			if (previewAlpha >= 1f) ((Timer) e.getSource()).stop();
		});
		fadeTimer.start();
	}

	/** Fades the preview out to transparent (~120 ms), then clears the image. */
	private void startFadeOut() {
		if (fadeTimer != null) fadeTimer.stop();
		if (cardPreviewPanel == null) { previewImage = null; return; }
		fadeTimer = new Timer(16, e -> {
			previewAlpha = Math.max(0f, previewAlpha - 0.15f);
			cardPreviewPanel.repaint();
			if (previewAlpha <= 0f) {
				((Timer) e.getSource()).stop();
				previewImage = null;
				cardPreviewPanel.repaint();
			}
		});
		fadeTimer.start();
	}

	/**
	 * On the first call, replaces the estimated card-image dimensions and resize bounds with the
	 * real ones, now that a card has actually been previewed. Subsequent calls are no-ops.
	 *
	 * <p>Does not move the panel. The width comes from the player's saved preference at build time
	 * or from wherever they have since dragged the divider, and a game starting is not a reason to
	 * change it. The bounds it recomputes are the same ones {@link #NATIVE_CARD_W} already seeded,
	 * so the clamp below is a no-op for as long as that constant matches the stored images; it is
	 * kept for the case where it stops matching, which is the only way the width could find itself
	 * out of range.
	 */
	private void sizePreviewPanel(int imgW, int imgH) {
		if (previewSized) return;
		previewSized  = true;
		nativeImgW    = imgW;
		nativeImgH    = imgH;
		minSidePanelW = (int)(imgW * 0.75) + SIDE_MARGIN;
		maxSidePanelW = imgW + SIDE_MARGIN;
		// Re-run regardless of whether the clamp moves it: previewH is derived from nativeImgH,
		// which was an estimate until a moment ago.
		setSidePanelWidth(clampSidePanelW(sidePanelW));
	}

	/** {@code w} brought inside the current resize bounds. */
	private int clampSidePanelW(int w) {
		return Math.max(minSidePanelW, Math.min(maxSidePanelW, w));
	}

	private void setSidePanelWidth(int w) {
		sidePanelW = w;
		previewH = (int)((w - SIDE_MARGIN) * (nativeImgH > 0
				? (double) nativeImgH / nativeImgW
				: (double) NATIVE_CARD_H / NATIVE_CARD_W));
		cardPreviewPanel.setPreferredSize(new Dimension(w, previewH));
		cardPreviewPanel.setMinimumSize  (new Dimension(w, previewH));
		cardPreviewPanel.setMaximumSize  (new Dimension(w, previewH));
		sidePanel.setPreferredSize(new Dimension(w, 0));
		if (sideWrapper != null)
			sideWrapper.setPreferredSize(new Dimension(w + RESIZE_HANDLE_W, 0));
		// Revalidated on the wrapper rather than the frame, which is what actually re-runs the
		// content pane's BorderLayout.
		//
		// setPreferredSize does not invalidate the component it is called on -- it sets the field
		// and fires a property change, nothing more. So after the assignments above nothing in the
		// tree is marked invalid, and both frame.validate() and frame.revalidate() return without
		// laying anything out. The new preferred width then sat unused until something else forced
		// a layout pass, which is why dragging the divider appeared to work only once a game was
		// running: the board revalidates constantly for its own reasons, and the pending width came
		// along for the ride. revalidate() on a JComponent invalidates it first, then validates the
		// nearest validate root, so the wrapper's new width is honoured immediately.
		Component target = sideWrapper != null ? sideWrapper : frame.getContentPane();
		target.revalidate();
		frame.repaint();
	}

	// -------------------------------------------------------------------------
	// Hand card zoom / popup helpers
	// -------------------------------------------------------------------------

	/**
	 * Previews the hovered hand card in the side panel, and clears the preview when the pointer
	 * leaves the fan. The fan reports index -1 for "no card", which is why this takes an index
	 * rather than a card.
	 */
	private void onHandCardHover(int handIdx) {
		List<CardData> hand = gameState.getP1Hand();
		if (handIdx < 0 || handIdx >= hand.size()) { hideZoom(); return; }
		showHandCardZoom(hand.get(handIdx).imageUrl());
	}

	/** Opens the card's menu where it was clicked in the fan. */
	private void onHandCardPressed(int handIdx, MouseEvent e) {
		List<CardData> hand = gameState.getP1Hand();
		if (handIdx < 0 || handIdx >= hand.size()) return;
		onHandCardClicked(handIdx, hand.get(handIdx), p1HandFan, e);
	}

	/**
	 * Says that something feeding a card's cost or castability has moved, so the fan should ask
	 * again. The answers themselves come from {@link #handCardState(int)} during the repaint.
	 *
	 * <p>Package-private for {@code GameContextImpl}: an effect that changes what may be cast
	 * (Vayne 28-117H bars casting outright) has to say so, and nothing on the resolution path
	 * repaints the fan on its own.
	 */
	void refreshHandCardStates() {
		if (p1HandFan != null) p1HandFan.repaint();
	}

	/**
	 * The menu of everything a card in hand can do: play it, play it by each alternative route it
	 * offers, or use an ability it has while held.
	 *
	 * <p>{@code over} is the component the menu hangs off — the fan — and {@code e} carries the
	 * point within it that was clicked, so the menu opens on the card rather than at its corner.
	 */
	private void onHandCardClicked(int handIdx, CardData card, JComponent over, MouseEvent e) {
		if (gameState.isP1GameOver()) return;
		// The menu takes the pointer off the fan at once; without this the card drops mid-decision.
		if (p1HandFan != null) p1HandFan.setHoverFrozen(true);

		JPopupMenu menu = new JPopupMenu();

		JMenuItem playItem = new JMenuItem("Play");
		boolean canPlaySpecialAction = castTimingWindowOpen(card);
		boolean isCharacter = card.isForward() || card.isBackup() || card.isMonster();
		boolean nameConflict = isCharacter && !card.multicard() && hasCharacterNameOnField(card.name()) && !isMultiNameExceptionActive(card.name(), true);
		boolean lightDarkConflict = isCharacter && isLightDarkConflict(card);
		playItem.setEnabled(canCastFromHand(card, handIdx));
		playItem.addActionListener(ae -> {
			hideZoom();
			showPaymentDialog(card, handIdx);
		});
		menu.add(playItem);

		if (card.hasWarp()) {
			JMenuItem warpItem = new JMenuItem("Play (Warp " + card.warpValue() + ")");
			warpItem.setEnabled(canPlaySpecialAction && canAffordWarpCost(card, handIdx) && castRestrictionMet(card)
					&& !summonCastBlocked(card, true) && !p1CastLimitReached());
			warpItem.addActionListener(ae -> {
				hideZoom();
				showWarpPaymentDialog(card, handIdx);
			});
			menu.add(warpItem);
		}

		ExtraCost ec = card.extraCost();
		// Extra costs were originally summon-only; CP_FIXED (e.g. "pay 《Wind》《2》 as an extra
		// cost") also appears on Forward/Character "enters the field" abilities (e.g. Samurai).
		if (ec != null && (card.isSummon() || ec.type() == ExtraCost.Type.CP_FIXED)) {
			JMenuItem ecItem = new JMenuItem("Play (Extra Cost: " + ec.description() + ")");
			ecItem.setEnabled(canPlaySpecialAction && !summonCastBlocked(card, true)
					&& canAffordCard(card, handIdx) && canAffordExtraCost(card, handIdx, ec) && !p1CastLimitReached());
			ecItem.addActionListener(ae -> {
				hideZoom();
				showExtraCostPlayDialog(card, handIdx, ec);
			});
			menu.add(ecItem);
		}

		List<DullForwardCost> altDull = card.altDullCosts();
		CardData.AltPutToBzCost altBz = card.altPutToBzCost();
		CardData.AltPutToBzReduction altBzReduce = card.altPutToBzReduction();
		if (card.altCrystalCost() > 0 || card.altCpCost() > 0 || card.altFieldRemoval() != null
				|| !altDull.isEmpty() || altBz != null || altBzReduce != null) {
			int ac = card.altCrystalCost();
			List<String> altElems = card.altCpElements();
			CardData.AltFieldRemoval afr = card.altFieldRemoval();
			String bzReduceStr = altBzReduce == null ? ""
					: "put " + describeAltPutToBzReduction(altBzReduce) + " to BZ"
					  + (altBz == null && altDull.isEmpty() && altElems.isEmpty() && afr == null ? "" : " + ");
			String bzStr = altBz == null ? ""
					: "put " + describeAltPutToBzCost(altBz) + " to BZ"
					  + (altDull.isEmpty() && altElems.isEmpty() && afr == null ? "" : " + ");
			String dullStr = altDull.isEmpty() ? ""
					: "dull " + altDull.stream().map(MainWindow::describeAltDullClause)
							.collect(Collectors.joining(" + "))
					  + (altElems.isEmpty() && afr == null ? "" : " + ");
			String removalStr = afr == null ? ""
					: "remove " + afr.count() + " " + afr.element() + " " + afr.type()
					  + (altElems.isEmpty() ? "" : " + ");
			String crystalStr = ac > 0 ? "《C》".repeat(ac) : "";
			String cpStr = altElems.isEmpty() ? "" : (ac > 0 ? " + " : "") + altElems.stream()
					.collect(Collectors.groupingBy(elem -> elem.isEmpty() ? "generic" : elem, LinkedHashMap::new, Collectors.counting()))
					.entrySet().stream().map(en -> (en.getKey().equals("generic") ? en.getValue() + " CP" : en.getValue() + " " + en.getKey() + " CP")).collect(Collectors.joining(" + "));
			List<String> cond = card.altConditionCardNames();
			String condStr = cond.isEmpty() ? "" : " [req: " + String.join("/", cond) + "]";
			String altLabel = "Play (Alt: " + bzReduceStr + bzStr + dullStr + removalStr + crystalStr
					+ cpStr + condStr + ")";
			JMenuItem altItem = new JMenuItem(altLabel);
			altItem.setEnabled(canPlaySpecialAction && !nameConflict && !lightDarkConflict
					&& canAffordAltCost(card, handIdx)
					&& (!card.isBackup() || hasAvailableBackupSlot()) && castRestrictionMet(card)
					&& !summonCastBlocked(card, true) && !p1CastLimitReached());
			altItem.addActionListener(ae -> {
				hideZoom();
				showAltCostPlayDialog(card, handIdx);
			});
			menu.add(altItem);
		}

		for (FieldDiscardCastEntry grant : findDiscardCastGrants(card, true)) {
			JMenuItem dItem = new JMenuItem("Play (Discard " + grant.count() + " Job " + grant.job() + ")");
			dItem.setEnabled(canPlaySpecialAction && !nameConflict && !lightDarkConflict
					&& hasEligibleJobInHand(grant.job(), handIdx, grant.count())
					&& (!card.isBackup() || hasAvailableBackupSlot()) && castRestrictionMet(card)
					&& !summonCastBlocked(card, true) && !p1CastLimitReached());
			dItem.addActionListener(ae -> {
				hideZoom();
				showFieldDiscardCastDialog(card, handIdx, grant);
			});
			menu.add(dItem);
		}

		for (ActionAbility ability : card.actionAbilities()) {
			if (!ability.whileCardInHand()) continue;
			boolean abilityEnabled = autoAbilityTriggers.canActivateHandAbility(ability, card, true);
			String abilityHtml = buildAbilityMenuLabelHtml(ability);
			JMenuItem item = new JMenuItem(abilityEnabled
					? "<html>Use: " + abilityHtml.substring("<html>".length())
					: "Use: " + buildAbilityMenuLabel(ability));
			item.setEnabled(abilityEnabled);
			item.addActionListener(ae -> {
				hideZoom();
				autoAbilityTriggers.showActionAbilityPaymentDialog(ability, card, () -> {}, true);
			});
			menu.add(item);
		}

		menu.addPopupMenuListener(new PopupMenuListener() {
			@Override public void popupMenuWillBecomeVisible(PopupMenuEvent e) {}
			@Override public void popupMenuCanceled(PopupMenuEvent e) { releaseHandHover(); }
			@Override public void popupMenuWillBecomeInvisible(PopupMenuEvent e) { releaseHandHover(); }
		});

		menu.show(over, e.getX(), e.getY());
	}

	/**
	 * Lets the fan follow the pointer again once a card menu has closed.
	 *
	 * <p>Deferred to the end of the event queue because the menu is still on screen while it is
	 * becoming invisible, and a hover re-read taken then finds the menu rather than the fan.
	 */
	private void releaseHandHover() {
		if (p1HandFan == null) return;
		SwingUtilities.invokeLater(() -> p1HandFan.setHoverFrozen(false));
	}

	/** Shows a preview of a hand card in the side panel. */
	private void showHandCardZoom(String url) {
		showZoomAt(url);
	}

	void addToBreakZone(CardData card) { addToBreakZone(card, false); }

	void addToBreakZone(CardData card, boolean fromField)
	{
		boolean player1 = gameState.getIdentity().get(card);

		// FA1: "If a card is put into your Break Zone in any situation, remove it from the game instead."
		if (playerHasBzToRfgAnySituation(player1)) {
			gameState.addToPermanentRfp(card);
			logEntry((player1 ? "" : "[P2] ") + card.name() + " → Removed From Game instead of Break Zone");
			if (player1) refreshP1WarpZoneUI(); else refreshP2WarpZoneUI();
			return;
		}

		if (fromField) {
			// Targeted marker: "If it is put from the field into the Break Zone this turn, remove it
			// from the game instead." (e.g. Jet Bahamut) — set on this specific card instance by an
			// action ability, independent of any player's field abilities.
			if (rfgInsteadOfBzThisTurn.remove(card)) {
				gameState.addToPermanentRfp(card);
				logEntry((player1 ? "" : "[P2] ") + card.name() + " → Removed From Game instead of Break Zone (marked this turn)");
				if (player1) refreshP1WarpZoneUI(); else refreshP2WarpZoneUI();
				return;
			}

			// FA3: "If a damaged Forward opponent controls is put from the field into the Break Zone, remove it from the game instead."
			if (card.isForward() && getCardFieldDamage(card) > 0
					&& playerHasBzToRfgOppDamagedForwardFromField(!player1)) {
				gameState.addToPermanentRfp(card);
				logEntry((player1 ? "" : "[P2] ") + card.name() + " → Removed From Game instead of Break Zone");
				if (player1) refreshP1WarpZoneUI(); else refreshP2WarpZoneUI();
				return;
			}

			// FA4: "If a Forward damaged by [self] is put from the field into the Break Zone on the
			// same turn, remove it from the game instead." (Susano, Lord of the Revel 14-011H).
			// Narrower than FA3 — it asks who dealt the damage, not merely whether there is any —
			// so it is checked after it: where both apply the outcome is identical anyway.
			if (card.isForward() && damagerRemovesFromGameInstead(card)) {
				gameState.addToPermanentRfp(card);
				logEntry((player1 ? "" : "[P2] ") + card.name() + " → Removed From Game instead of Break Zone");
				if (player1) refreshP1WarpZoneUI(); else refreshP2WarpZoneUI();
				return;
			}

			// FA2: "If a Character is put from the field into the Break Zone, you may remove it from the game instead."
			if (!card.isSummon()) {
				if (playerHasBzToRfgCharacterFromField(true)) {
					int choice = showEffectOptionDialog(
							"Remove \"" + card.name() + "\" from the game instead of the Break Zone?",
							"Field Ability", new Object[]{"Remove from Game", "Break Zone"});
					if (choice == 0) {
						gameState.addToPermanentRfp(card);
						logEntry((player1 ? "" : "[P2] ") + card.name() + " → Removed From Game instead of Break Zone");
						if (player1) refreshP1WarpZoneUI(); else refreshP2WarpZoneUI();
						return;
					}
				}
				if (playerHasBzToRfgCharacterFromField(false)) {
					int choice = showEffectOptionDialog(
							"[P2] Remove \"" + card.name() + "\" from the game instead of the Break Zone?",
							"[P2] Field Ability", new Object[]{"Remove from Game", "Break Zone"});
					if (choice == 0) {
						gameState.addToPermanentRfp(card);
						logEntry((player1 ? "" : "[P2] ") + card.name() + " → Removed From Game instead of Break Zone");
						if (player1) refreshP1WarpZoneUI(); else refreshP2WarpZoneUI();
						return;
					}
				}
			}
		}

		List<CardData> zone = player1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
		zone.add(card);
		// Recorded here rather than at the three field-to-Break-Zone callers, because this is the
		// one place every route through them converges — and the one place that knows the card
		// actually arrived, since the RFG redirects above return before reaching it.
		if (fromField) turn(player1).putToBzFromFieldThisTurn.add(card);
		// An LB card only passes through the Break Zone: it is put there — so everything watching
		// "put from the field into the Break Zone" fires — and then moves straight on to the LB
		// deck face up, where it has sat all along marked spent (spentLbIndices). Without the log
		// line the card reads as having vanished, which is what the zone list looks like.
		//
		// Removed by index rather than by value: CardData is a record, so remove(Object) would take
		// the first equal element, not necessarily the one just appended.
		if (card.isLb()) {
			zone.remove(zone.size() - 1);
			logEntry((player1 ? "" : "[P2] ") + card.name()
					+ " is an LB card — it returns to the LB deck face up rather than staying in the Break Zone");
		}
		if (player1) refreshP1BreakLabel(); else refreshP2BreakLabel();
		syncBzForwardPlayables(player1);
		if (fromField) fireFieldToBzDrawTriggers(card);
	}

	/**
	 * Resolves any "When it is put from the field into the Break Zone this turn, draw N card(s)"
	 * marks on {@code card} (Brynhildr 15-014H). Called once the card has actually reached the
	 * Break Zone, so the RFG redirects earlier in {@link #addToBreakZone} pre-empt the trigger —
	 * a card removed from the game was never put into the Break Zone. The mark is consumed on the
	 * way through: the card can only leave the field for the Break Zone once.
	 */
	private void fireFieldToBzDrawTriggers(CardData card) {
		List<PendingBzDraw> pending = drawOnFieldToBzThisTurn.remove(card);
		if (pending == null) return;
		for (PendingBzDraw p : pending) {
			logEntry((p.drawerIsP1() ? "" : "[P2] ") + card.name()
					+ " was put from the field into the Break Zone — draw " + p.count());
			drawCardsForPlayer(p.drawerIsP1(), p.count());
		}
	}

	/**
	 * Draws {@code count} cards for the given player, animating and refreshing the affected labels.
	 * A mandatory draw the deck cannot satisfy loses the game for the drawer; "draw up to N" effects
	 * protect the player by requesting a {@code count} no larger than their deck.
	 */
	void drawCardsForPlayer(boolean isP1, int count) {
		if (isP1) {
			int drew = drawP1Cards(count).size();
			animateCardDraw(true, drew);
			refreshP1HandLabel();
			refreshP1DeckLabel();
			if (drew < count) triggerGameOver("Milled Out - You Lose!");
		} else {
			int drew = drawP2Cards(count).size();
			animateCardDraw(false, drew);
			refreshP2DeckLabel();
			refreshP2HandCountLabel();
			if (drew < count) triggerGameOver("P2 milled out — You Win!");
		}
	}

	private boolean playerHasBzToRfgAnySituation(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasBzToRfgAnySituation(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasBzToRfgAnySituation(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasBzToRfgAnySituation(c)) return true;
		return false;
	}

	private boolean playerHasBzToRfgCharacterFromField(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasCharacterFieldToBzMayRfg(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasCharacterFieldToBzMayRfg(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasCharacterFieldToBzMayRfg(c)) return true;
		return false;
	}

	private boolean playerHasBzToRfgOppDamagedForwardFromField(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppDamagedForwardFieldToBzRfg(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppDamagedForwardFieldToBzRfg(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppDamagedForwardFieldToBzRfg(c)) return true;
		return false;
	}

	private int getCardFieldDamage(CardData card) {
		int idx = p1ForwardCards.indexOf(card);
		if (idx >= 0) return p1ForwardDamage.get(idx);
		idx = p2ForwardCards.indexOf(card);
		if (idx >= 0) return p2ForwardDamage.get(idx);
		Integer d = p1BackupForwardDamage.get(card);
		if (d != null) return d;
		d = p2BackupForwardDamage.get(card);
		return d != null ? d : 0;
	}

	void refreshP1BreakLabel() {
		List<CardData> zone = gameState.getP1BreakZone();
		int topIdx = zone.size() - 1 - p1BreakAnimHide;
		if (topIdx < 0) {
			p1BreakLabel.setIcon(null);
			p1BreakLabel.setFont(FontLoader.loadPixelFont(18));
			p1BreakLabel.setText("BREAK");
			return;
		}
		String url = zone.get(topIdx).imageUrl();
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image img = ImageCache.load(url);
				return img == null ? null
						: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon == null) return;
					// Discard stale result if the zone no longer has this card on top
					List<CardData> current = gameState.getP1BreakZone();
					int curTop = current.size() - 1 - p1BreakAnimHide;
					if (curTop >= 0 && url.equals(current.get(curTop).imageUrl())) {
						p1BreakLabel.setIcon(icon);
						p1BreakLabel.setText(null);
					}
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	void refreshP2BreakLabel() {
		List<CardData> zone = gameState.getP2BreakZone();
		int topIdx = zone.size() - 1 - p2BreakAnimHide;
		if (topIdx < 0) {
			p2BreakLabel.setIcon(null);
			p2BreakLabel.setFont(FontLoader.loadPixelFont(18));
			p2BreakLabel.setText("BREAK");
			return;
		}
		String url = zone.get(topIdx).imageUrl();
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image img = ImageCache.load(url);
				return img == null ? null
						: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon == null) return;
					// Discard stale result if the zone no longer has this card on top
					List<CardData> current = gameState.getP2BreakZone();
					int curTop = current.size() - 1 - p2BreakAnimHide;
					if (curTop >= 0 && url.equals(current.get(curTop).imageUrl())) {
						p2BreakLabel.setIcon(icon);
						p2BreakLabel.setText(null);
					}
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
	void animateCardDraw(boolean isP1, int count) {
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

	void animateMillOneCard(boolean isP1) {
		JLabel       deck = isP1 ? p1DeckLabel : p2DeckLabel;
		JLabel       brk  = isP1 ? p1BreakLabel : p2BreakLabel;
		JLayeredPane lp   = frame.getRootPane().getLayeredPane();
		Point start = SwingUtilities.convertPoint(deck, deck.getWidth() / 2, deck.getHeight() / 2, lp);
		Point end   = SwingUtilities.convertPoint(brk,  brk.getWidth()  / 2, brk.getHeight()  / 2, lp);
		BufferedImage img = CardAnimation.toARGB(
				loadCardbackImage(), CardAnimation.CARD_W, CardAnimation.CARD_H);
		cardSlideAnimator.startSlide(img, start, end, 0);
	}

	/**
	 * Triggers a card-slide animation from the player's hand area (off-screen
	 * bottom-center for P1, off-screen top-center for P2) toward the Break Zone.
	 * Visually mirrors {@link #animateCardDraw} but ends at the Break Zone label
	 * and uses the card's face image (discards are face-up).
	 */
	void animateCardDiscard(boolean isP1, CardData card) {
		if (card == null) return;
		JLabel       brk = isP1 ? p1BreakLabel : p2BreakLabel;
		if (brk == null) return;
		JLayeredPane lp  = frame.getRootPane().getLayeredPane();

		int   cx    = lp.getWidth() / 2;
		Point start = isP1
				? new Point(cx, lp.getHeight() + CardAnimation.CARD_H)
				: new Point(cx, -CardAnimation.CARD_H);
		Point end   = SwingUtilities.convertPoint(
				brk, brk.getWidth() / 2, brk.getHeight() / 2, lp);

		BufferedImage img;
		try {
			Image face = ImageCache.load(card.imageUrl());
			if (face == null) return;
			img = CardAnimation.toARGB(face, CardAnimation.CARD_W, CardAnimation.CARD_H);
		} catch (java.io.IOException ignored) {
			return;
		}
		// Hide this card from the break label until the slide lands.
		if (isP1) p1BreakAnimHide++; else p2BreakAnimHide++;
		if (isP1) refreshP1BreakLabel(); else refreshP2BreakLabel();
		cardSlideAnimator.startSlide(img, start, end, 0);
		int delayMs = CardSlideAnimator.TOTAL_FRAMES * CardSlideAnimator.FRAME_MS;
		Timer reveal = new Timer(delayMs, e -> {
			if (isP1) p1BreakAnimHide = Math.max(0, p1BreakAnimHide - 1);
			else      p2BreakAnimHide = Math.max(0, p2BreakAnimHide - 1);
			if (isP1) refreshP1BreakLabel(); else refreshP2BreakLabel();
		});
		reveal.setRepeats(false);
		reveal.start();
	}

	/**
	 * Slides {@code card}'s face from the board slot it is leaving toward its owner's hand — the
	 * off-screen bottom-centre for P1, top-centre for P2, the same off-board points
	 * {@link #animateCardDraw} and {@link #animateCardDiscard} use to stand for a hand.
	 *
	 * <p>The reverse of {@link #animateCardDiscard}, and wanted for the reverse reason: a card
	 * bounced off the field was vanishing from the board between two repaints, with nothing to say
	 * where it had gone. The opponent's Leviathan returning a Forward is the case that shows it.
	 *
	 * <p><b>Call before the slot is cleared.</b> The start point is read off the live label, and a
	 * slot already emptied has no position to read. Does nothing when there is no UI to animate in
	 * — a headless {@code MainWindow} under test never installs the animator.
	 *
	 * @param fromSlot  the label the card currently occupies
	 * @param card      the card being returned, for its face image
	 * @param ownerIsP1 which hand it is going to, which is its owner's rather than its controller's
	 */
	void animateCardReturnToHand(JLabel fromSlot, CardData card, boolean ownerIsP1) {
		if (card == null || fromSlot == null || frame == null || cardSlideAnimator == null) return;
		if (!fromSlot.isShowing()) return;
		JLayeredPane lp = frame.getRootPane().getLayeredPane();

		Point start = SwingUtilities.convertPoint(
				fromSlot, fromSlot.getWidth() / 2, fromSlot.getHeight() / 2, lp);
		int   cx  = lp.getWidth() / 2;
		Point end = ownerIsP1
				? new Point(cx, lp.getHeight() + CardAnimation.CARD_H)
				: new Point(cx, -CardAnimation.CARD_H);

		BufferedImage img;
		try {
			Image face = ImageCache.load(card.imageUrl());
			if (face == null) return;
			img = CardAnimation.toARGB(face, CardAnimation.CARD_W, CardAnimation.CARD_H);
		} catch (java.io.IOException ignored) {
			return;
		}
		cardSlideAnimator.startSlide(img, start, end, 0);
	}

	void startRfpAnim(int forwardIdx, boolean isP1) {
		List<JLabel> labels = isP1 ? p1ForwardLabels : p2ForwardLabels;
		if (forwardIdx < 0 || forwardIdx >= labels.size()) return;
		JLabel label = labels.get(forwardIdx);
		if (label == null) return;
		Icon icon = label.getIcon();
		if (!(icon instanceof ImageIcon ii)) return;
		JLayeredPane lp = frame.getRootPane().getLayeredPane();
		Point center = SwingUtilities.convertPoint(label, label.getWidth() / 2, label.getHeight() / 2, lp);
		java.awt.image.BufferedImage img = CardAnimation.toARGB(ii.getImage(), ii.getIconWidth(), ii.getIconHeight());
		rfpAnimator.startRfp(img, center);
	}

	// -------------------------------------------------------------------------
	// Field arrivals — animation first, then the card, then its auto abilities
	// -------------------------------------------------------------------------

	/**
	 * Places a card that is coming back out of the removed-from-game zone (Kadaj, Emet-Selch,
	 * Necron's returning cards, casts made from the RFG zone) and bursts it onto the field with the
	 * reversed-RFP animation. {@code placement} runs right away so game state is never left stale;
	 * see {@link FieldEntryAnimator} for what the animation holds back.
	 */
	void placeFromRfgWithAnim(CardData card, boolean isP1, Runnable placement) {
		fieldEntryAnimator.placeWithAnim(card, isP1, FieldEntryAnimator.Style.RFG_RETURN, placement);
	}

	/** Locates {@code card} on {@code isP1}'s field by identity; {@code null} when it is not there. */
	ForwardTarget findFieldSlot(CardData card, boolean isP1) {
		int fIdx = identityIndexOf(isP1 ? p1ForwardCards : p2ForwardCards, card);
		if (fIdx >= 0) return new ForwardTarget(isP1, fIdx, ForwardTarget.CardZone.FORWARD);
		CardData[] backups = isP1 ? p1BackupCards : p2BackupCards;
		for (int i = 0; i < backups.length; i++)
			if (backups[i] == card) return new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP);
		int mIdx = identityIndexOf(isP1 ? p1MonsterCards : p2MonsterCards, card);
		if (mIdx >= 0) return new ForwardTarget(isP1, mIdx, ForwardTarget.CardZone.MONSTER);
		return null;
	}

	/** The slot label {@code card} occupies on {@code isP1}'s field, or {@code null}. */
	JLabel findFieldSlotLabel(CardData card, boolean isP1) {
		ForwardTarget slot = findFieldSlot(card, isP1);
		if (slot == null) return null;
		switch (slot.zone()) {
			case FORWARD -> {
				List<JLabel> labels = isP1 ? p1ForwardLabels : p2ForwardLabels;
				return slot.idx() < labels.size() ? labels.get(slot.idx()) : null;
			}
			case BACKUP -> {
                            return (isP1 ? p1BackupLabels : p2BackupLabels)[slot.idx()];
                }
			case MONSTER -> {
				List<JLabel> labels = isP1 ? p1MonsterLabels : p2MonsterLabels;
				return slot.idx() < labels.size() ? labels.get(slot.idx()) : null;
			}
			default -> {
                            return null;
                }
		}
	}

	/** The display state of the slot {@code card} occupies, {@code ACTIVE} when it has none. */
	CardState fieldSlotState(CardData card, boolean isP1) {
		ForwardTarget slot = findFieldSlot(card, isP1);
		if (slot == null) return CardState.ACTIVE;
		return switch (slot.zone()) {
			case FORWARD -> (isP1 ? p1ForwardStates : p2ForwardStates).get(slot.idx());
			case BACKUP  -> (isP1 ? p1BackupStates  : p2BackupStates)[slot.idx()];
			case MONSTER -> (isP1 ? p1MonsterStates : p2MonsterStates).get(slot.idx());
			default      -> CardState.ACTIVE;
		};
	}

	/** Re-renders whichever slot {@code card} occupies on {@code isP1}'s field; no-op if it has none. */
	void refreshFieldSlotFor(CardData card, boolean isP1) {
		ForwardTarget slot = findFieldSlot(card, isP1);
		if (slot == null) return;
		switch (slot.zone()) {
			case FORWARD -> { if (isP1) refreshP1ForwardSlot(slot.idx()); else refreshP2ForwardSlot(slot.idx()); }
			case BACKUP  -> { if (isP1) refreshP1BackupSlot(slot.idx());  else refreshP2BackupSlot(slot.idx()); }
			case MONSTER -> { if (isP1) refreshP1MonsterSlot(slot.idx()); else refreshP2MonsterSlot(slot.idx()); }
			default      -> { }
		}
	}

	void startBreakAnim(JLabel label) {
		if (suppressNextBreakAnim) { suppressNextBreakAnim = false; return; }
		if (label == null) return;
		Icon icon = label.getIcon();
		if (!(icon instanceof ImageIcon ii)) return;
		JLayeredPane lp = frame.getRootPane().getLayeredPane();
		if (pendingCostBreakDestLabel != null) {
			JLabel dest = pendingCostBreakDestLabel;
			pendingCostBreakDestLabel = null;
			boolean destIsP1 = dest == p1BreakLabel;
			Point start = SwingUtilities.convertPoint(label, label.getWidth() / 2, label.getHeight() / 2, lp);
			Point end   = SwingUtilities.convertPoint(dest,  dest.getWidth()  / 2, dest.getHeight()  / 2, lp);
			java.awt.image.BufferedImage img = CardAnimation.toARGB(ii.getImage(), ii.getIconWidth(), ii.getIconHeight());
			if (destIsP1) p1BreakAnimHide++; else p2BreakAnimHide++;
			if (destIsP1) refreshP1BreakLabel(); else refreshP2BreakLabel();
			cardSlideAnimator.startSlide(img, start, end, 0);
			int delayMs = CardSlideAnimator.TOTAL_FRAMES * CardSlideAnimator.FRAME_MS;
			Timer reveal = new Timer(delayMs, e -> {
				if (destIsP1) p1BreakAnimHide = Math.max(0, p1BreakAnimHide - 1);
				else          p2BreakAnimHide = Math.max(0, p2BreakAnimHide - 1);
				if (destIsP1) refreshP1BreakLabel(); else refreshP2BreakLabel();
			});
			reveal.setRepeats(false);
			reveal.start();
			return;
		}
		Point         center = SwingUtilities.convertPoint(
				label, label.getWidth() / 2, label.getHeight() / 2, lp);
		java.awt.image.BufferedImage img = CardAnimation.toARGB(
				ii.getImage(), ii.getIconWidth(), ii.getIconHeight());
		breakAnimator.startBreak(img, center);
	}

	private void animateUniquenessSlide(JLabel cardLabel, boolean isP1) {
		if (cardLabel == null) return;
		Icon icon = cardLabel.getIcon();
		if (!(icon instanceof ImageIcon ii)) return;
		JLayeredPane lp   = frame.getRootPane().getLayeredPane();
		JLabel       dest = isP1 ? p1BreakLabel : p2BreakLabel;
		Point start = SwingUtilities.convertPoint(cardLabel, cardLabel.getWidth() / 2, cardLabel.getHeight() / 2, lp);
		Point end   = SwingUtilities.convertPoint(dest, dest.getWidth() / 2, dest.getHeight() / 2, lp);
		java.awt.image.BufferedImage img = CardAnimation.toARGB(ii.getImage(), ii.getIconWidth(), ii.getIconHeight());
		cardSlideAnimator.startSlide(img, start, end, 0);
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

	/** @see CostCalculator#effectiveCastCost */
	int effectiveCastCost(CardData card) { return costs.effectiveCastCost(card); }

	/**
	 * Doublecast (Yuna): records the printed cost of a Summon just cast by {@code isP1} so that
	 * lower-cost hand Summons cast free for the rest of the turn. No-op while Doublecast is
	 * inactive for that side. Call from every path that registers a Summon cast.
	 */
	void noteDoublecastSummonCast(boolean isP1, CardData card) {
		if (!(isP1 ? p1DoublecastFreeSummons : p2DoublecastFreeSummons)) return;
		if (isP1) p1DoublecastLastSummonCost = card.cost();
		else      p2DoublecastLastSummonCost = card.cost();
		if (card.cost() > 0)
			logEntry((isP1 ? "" : "[P2] ") + "Doublecast — Summons of cost "
					+ (card.cost() - 1) + " or less now cast free");
		refreshHandCardStates();
	}

	List<CardData> drawP1Cards(int count) {
		List<CardData> drawn = gameState.drawToHand(count);
		p1Turn.cardsDrawnThisTurn += drawn.size();
		return drawn;
	}

	List<CardData> drawP2Cards(int count) {
		List<CardData> drawn = gameState.drawP2ToHand(count);
		p2Turn.cardsDrawnThisTurn += drawn.size();
		return drawn;
	}

	/** @see CostCalculator#p1HandHasSelfCostModifiers */
	private boolean p1HandHasSelfCostModifiers() { return costs.p1HandHasSelfCostModifiers(); }

	/** @see CostCalculator#computeSelfCostUnits */
	private int computeSelfCostUnits(SelfCostModifier mod, boolean isP1) { return costs.computeSelfCostUnits(mod, isP1); }

	int applyFieldReductions(int cost, CardData card, boolean isP1) {
		for (int s = 0; s < 2; s++) {
			boolean sIsP1 = s == 0;
			List<CardData> fwds = sIsP1 ? p1ForwardCards : p2ForwardCards;
			CardData[]     bkps = sIsP1 ? p1BackupCards  : p2BackupCards;
			List<CardData> mons = sIsP1 ? p1MonsterCards : p2MonsterCards;
			for (CardData src : fwds)               cost = applyFieldReductionsFrom(src, cost, card, isP1, sIsP1);
			for (CardData bkp : bkps) if (bkp != null)  cost = applyFieldReductionsFrom(bkp, cost, card, isP1, sIsP1);
			for (CardData src : mons)               cost = applyFieldReductionsFrom(src, cost, card, isP1, sIsP1);
		}
		return cost;
	}

	private int applyFieldReductionsFrom(CardData src, int cost, CardData card, boolean isP1, boolean srcIsP1) {
		for (FieldCostReduction fcr : src.fieldCostReductions()) {
			if (fcr.amountPerUnit() == 0) continue;
			if (fcr.opponentOnly() && srcIsP1 == isP1) continue;  // skip if opponent-only and caster is the owner
			if (!fcr.opponentOnly() && fcr.ownerOnly() && srcIsP1 != isP1) continue;
			if (!fieldCostModifierLive(fcr, srcIsP1)) continue;
			if (lostAbilitiesCards.contains(src)) continue;
			if (!fcr.matchesCard(card)) continue;
			int units = fcr.scalingJobFilter() != null
					? countForwardsWithJob(fcr.scalingJobFilter(), isP1) : 1;
			cost = fcr.apply(cost, units);
		}
		return cost;
	}

	/**
	 * A "Damage N --" cost modifier is only live while the player who controls the card printing it
	 * holds at least N damage counters. The threshold is read against that player, not the caster:
	 * Garnet 28-098H discounts her own Summons off her own damage, and Ultimecia 18-105H taxes the
	 * opponent off Ultimecia's controller's damage.
	 */
	private boolean fieldCostModifierLive(FieldCostReduction fcr, boolean srcIsP1) {
		if (fcr.damageThreshold() <= 0) return true;
		int dmgInZone = srcIsP1 ? gameState.getP1DamageZone().size() : gameState.getP2DamageZone().size();
		return dmgInZone >= fcr.damageThreshold();
	}

	private int countForwardsWithJob(String job, boolean isP1) {
		int count = 0;
		for (CardData fwd : (isP1 ? p1ForwardCards : p2ForwardCards))
			if (fwd.hasJob(job)) count++;
		return count;
	}

	/**
	 * Returns true if the player can theoretically afford to play {@code card}
	 * by combining existing CP with potential discards from hand.
	 * {@code excludeHandIdx} is the index of the card being played (not available
	 * for discard).
	 */
	/**
	 * Returns {@code true} if any card currently on the field has a
	 * "Players cannot cast Summons." field ability — prohibiting Summon casting for both players.
	 */
	boolean summonCastingProhibited() {
		for (CardData c : p1ForwardCards) if (c != null && ActionResolver.hasPlayerCannotCastSummonsFieldAbility(c)) return true;
		for (CardData c : p1BackupCards)  if (c != null && ActionResolver.hasPlayerCannotCastSummonsFieldAbility(c)) return true;
		for (CardData c : p2ForwardCards) if (c != null && ActionResolver.hasPlayerCannotCastSummonsFieldAbility(c)) return true;
		for (CardData c : p2BackupCards)  if (c != null && ActionResolver.hasPlayerCannotCastSummonsFieldAbility(c)) return true;
		return false;
	}

	/**
	 * Whether {@code isP1} could choose everything {@code card}'s text opens by demanding — the
	 * rule that a Summon whose effect begins "Choose 1 Forward." cannot be cast while there is no
	 * Forward to choose.
	 *
	 * <p>Characters are not held to this: a Forward may enter with an auto-ability that finds
	 * nothing to fire at, because the ability triggers on entering rather than gating it. A Summon
	 * chooses as it is cast, so a board that answers nothing makes the cast itself illegal.
	 *
	 * <p>Only the choice as the cast would make it is checked. A target that leaves in response —
	 * broken, returned to hand, made unchooseable — leaves the Summon on the Stack to fizzle when
	 * it resolves; the cast was legal when it happened and nothing here revisits that.
	 *
	 * <p>Four places can answer a choice, and which one a Summon names is the first thing decided:
	 *
	 * <ul>
	 *   <li>the <b>Stack</b> — "Choose 1 auto-ability. Cancel its effect."
	 *   <li>the caster's <b>Damage Zone</b> — empty until they have been dealt damage
	 *   <li>a <b>Break Zone</b> — <em>which</em> card is picked waits for resolution, since the
	 *       zone moves in between, but whether one is there is answerable now
	 *   <li>the <b>field</b>, which is everything else
	 * </ul>
	 *
	 * <p>Each pool is the one its own choice prompt would be built from —
	 * {@link GameContextImpl#eligibleCharacters} and its Break Zone twin, and for the Stack the
	 * filter {@code canActivateAbility} gates an ability's activation on. So a Forward shielded by
	 * "cannot be chosen by Summons", or a Break Zone closed by Kalmia, counts against the cast
	 * exactly as it would count against the pick.
	 *
	 * <p>The character pools read the resolution fields to know whose Summon is choosing, so they
	 * are set for the length of the question and put back after: this runs from menu paint and
	 * from the AI's planning, both of them outside any resolution.
	 */
	boolean summonHasCastTarget(CardData card, boolean isP1) {
		if (card == null || !card.isSummon()) return true;
		String effect = card.summonEffect();

		Predicate<StackEntry> onStack = ActionResolver.mandatoryCastStackChoice(effect, isP1);
		if (onStack != null) return gameState.getStack().stream().anyMatch(onStack);

		if (ActionResolver.mandatoryCastNeedsOwnDamageZoneCard(effect))
			return !(isP1 ? gameState.getP1DamageZone() : gameState.getP2DamageZone()).isEmpty();

		TargetSpec spec = ActionResolver.mandatoryCastTargetSpec(effect, card);
		if (spec == null) return true;
		boolean  savedIsSummon = currentResolutionIsSummon;
		CardData savedSource   = currentSummonSource;
		boolean  savedSourceP1 = currentSummonSourceIsP1;
		currentResolutionIsSummon = true;
		currentSummonSource       = card;
		currentSummonSourceIsP1   = isP1;
		try {
			GameContextImpl ctx = new GameContextImpl(this, isP1, false);
			return !(spec.zone() != null
					? ctx.eligibleCharactersFromBreakZone(spec)
					: ctx.eligibleCharacters(spec)).isEmpty();
		} finally {
			currentResolutionIsSummon = savedIsSummon;
			currentSummonSource       = savedSource;
			currentSummonSourceIsP1   = savedSourceP1;
		}
	}

	/**
	 * The two gates a Summon passes that no other card type does: the field-wide "Players cannot
	 * cast Summons." prohibition, and having something to choose. Non-Summons are never blocked.
	 *
	 * <p>One method because every cast route has to ask both — the hand menu's Play item and each
	 * of its alternative-cost siblings, the Break Zone's borrowed casts, and the AI's planner. They
	 * were the prohibition written out eight times, and a route that grew the second check while
	 * another kept only the first is a rule the player meets or dodges depending on which menu they
	 * opened.
	 */
	boolean summonCastBlocked(CardData card, boolean isP1) {
		return card != null && card.isSummon()
				&& (summonCastingProhibited() || !summonHasCastTarget(card, isP1));
	}

	private boolean playerHasCastForwardsFromBz(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasCastForwardsFromBz(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasCastForwardsFromBz(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasCastForwardsFromBz(c)) return true;
		return false;
	}

	/**
	 * Registers any Forwards in {@code isP1}'s Break Zone as castable this turn when the
	 * "You can cast Forwards from your Break Zone" field ability is active.
	 * Skips cards already registered (to avoid overwriting better-cost entries).
	 */
	void syncBzForwardPlayables(boolean isP1) {
		syncBzSelfCastPlayables(isP1);
		syncRfgRemovedPlayables(isP1);
		IdentityHashMap<CardData, PlayableEntry> reg = isP1 ? bzPlayableP1 : bzPlayableP2;
		Set<CardData> faSet = isP1 ? bzForwardFaP1 : bzForwardFaP2;
		if (!playerHasCastForwardsFromBz(isP1)) {
			if (!faSet.isEmpty()) {
				faSet.forEach(reg::remove);
				faSet.clear();
				refreshPlayableCardsButton();
			}
			return;
		}
		List<CardData> bz = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
		boolean added = false;
		for (CardData card : bz) {
			if (!card.isForward()) continue;
			if (card.castProhibited()) continue;
			if (faSet.contains(card) || reg.containsKey(card)) continue;
			reg.put(card, new PlayableEntry(PlayableEntry.SourceZone.BREAK_ZONE, 0, false, false, false, false));
			faSet.add(card);
			added = true;
		}
		if (added) refreshPlayableCardsButton();
	}

	/**
	 * Registers the cards {@code isP1}'s field is currently opening from the removed-from-game zone
	 * — every "you can cast [what] removed by [self]'s abilities at any time you could normally cast
	 * [it]" permission on that side at once (Setzer 21-031H, Rinoa 21-038R).
	 *
	 * <p>Re-evaluated rather than registered at removal time, for the reason
	 * {@link #syncBzForwardPlayables} is: the permission is a field ability, so it has to lapse when
	 * its card leaves the field or loses its abilities, and the pile it opens is whatever
	 * {@link #cardsRemovedBySource} currently records against a card still standing.
	 *
	 * <p>A once-per-turn printing is enforced by withholding <em>its own</em> registrations for the
	 * rest of the turn, rather than by counting at the cast: everything that offers a borrowed card
	 * to a player reads the registration, so a limit applied here covers the menu, the playable-cards
	 * list and the CPU at once. Per remover, so one card's spent cast leaves another's alone.
	 */
	void syncRfgRemovedPlayables(boolean isP1) {
		IdentityHashMap<CardData, PlayableEntry> reg = isP1 ? bzPlayableP1 : bzPlayableP2;
		IdentityHashMap<CardData, CardData> owners = isP1 ? removedPlayableSourceP1 : removedPlayableSourceP2;
		List<CardData> rfg  = isP1 ? gameState.getP1PermanentRfp() : gameState.getP2PermanentRfp();
		boolean changed = false;

		// Everything currently offered, so a card cast, returned, or whose remover has gone is
		// dropped before the re-scan puts back only what still qualifies.
		if (!owners.isEmpty()) {
			owners.keySet().forEach(reg::remove);
			owners.clear();
			changed = true;
		}
		for (CardData remover : fieldCards(isP1)) {
			if (remover == null || lostAbilitiesCards.contains(remover)) continue;
			AutoAbilityTriggers.CastRemovedPermission perm =
					AutoAbilityTriggers.castRemovedPermission(remover);
			if (perm == null) continue;
			if (perm.oncePerTurn() && turn(isP1).castRemovedUsedThisTurn.contains(remover)) continue;
			List<CardData> removed = cardsRemovedBySource.get(remover);
			if (removed == null) continue;
			for (CardData card : removed) {
				if (!rfg.contains(card) || card.castProhibited() || reg.containsKey(card)) continue;
				if (!perm.admits(card)) continue;
				reg.put(card, new PlayableEntry(PlayableEntry.SourceZone.RFP, 0, false, false, false, false));
				owners.put(card, remover);
				changed = true;
			}
		}
		if (changed) refreshPlayableCardsButton();
	}

	/**
	 * Registers any card in {@code isP1}'s Break Zone that carries its own "You can cast [self]
	 * from your Break Zone" ability (e.g. Zenos) as castable from the Break Zone, and prunes
	 * entries for cards that have since left the Break Zone.  Unlike {@link #syncBzForwardPlayables}
	 * this is independent of any field card — the granting ability lives on the Break Zone card itself.
	 */
	void syncBzSelfCastPlayables(boolean isP1) {
		IdentityHashMap<CardData, PlayableEntry> reg = isP1 ? bzPlayableP1 : bzPlayableP2;
		Set<CardData> faSet = isP1 ? bzSelfCastFaP1 : bzSelfCastFaP2;
		List<CardData> bz   = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
		boolean changed = false;
		// Prune entries whose card has left the Break Zone (cast, removed, etc.).
		for (java.util.Iterator<CardData> it = faSet.iterator(); it.hasNext(); ) {
			CardData card = it.next();
			if (!bz.contains(card)) { reg.remove(card); it.remove(); changed = true; }
		}
		// Register Break Zone cards whose own ability lets them be cast from there.
		for (CardData card : bz) {
			if (faSet.contains(card) || reg.containsKey(card)) continue;
			if (card.castProhibited()) continue;
			if (!AutoAbilityTriggers.canCastSelfFromBz(card)) continue;
			reg.put(card, new PlayableEntry(PlayableEntry.SourceZone.BREAK_ZONE, 0, false, false, false, false));
			faSet.add(card);
			changed = true;
		}
		if (changed) refreshPlayableCardsButton();
	}

	/**
	 * Returns {@code true} if P1 may not cast right now — either barred outright for the turn
	 * (Vayne 28-117H) or already at a field ability's two-card cap.
	 */
	boolean p1CastLimitReached() {
		// Ahead of the count, because it is a ban rather than a cap: nothing has to have been cast
		// for it to apply.
		if (p1Turn.cannotCastThisTurn) return true;
		if (p1Turn.cardsCastThisTurn < 2) return false;
		for (CardData c : p1ForwardCards) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasSelfCastLimit(c)) return true;
		for (CardData c : p1BackupCards)  if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasSelfCastLimit(c)) return true;
		for (CardData c : p1MonsterCards) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasSelfCastLimit(c)) return true;
		return anyCastLimitBothReached();
	}

	/** The mirror of {@link #p1CastLimitReached} for P2. */
	boolean p2CastLimitReached() {
		if (p2Turn.cannotCastThisTurn) return true;
		if (p2Turn.cardsCastThisTurn < 2) return false;
		for (CardData c : p2ForwardCards) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasSelfCastLimit(c)) return true;
		for (CardData c : p2BackupCards)  if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasSelfCastLimit(c)) return true;
		for (CardData c : p2MonsterCards) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasSelfCastLimit(c)) return true;
		return anyCastLimitBothReached();
	}

	private boolean anyCastLimitBothReached() {
		for (CardData c : p1ForwardCards) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasBothCastLimit(c)) return true;
		for (CardData c : p1BackupCards)  if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasBothCastLimit(c)) return true;
		for (CardData c : p1MonsterCards) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasBothCastLimit(c)) return true;
		for (CardData c : p2ForwardCards) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasBothCastLimit(c)) return true;
		for (CardData c : p2BackupCards)  if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasBothCastLimit(c)) return true;
		for (CardData c : p2MonsterCards) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasBothCastLimit(c)) return true;
		return false;
	}

	// -------------------------------------------------------------------------
	// Protections against opponent effects
	// -------------------------------------------------------------------------

	/**
	 * Returns {@code true} when player {@code targetIsP1} controls a field card with
	 * "Characters you control cannot be returned to their owner's hand by your opponent's
	 * Summons or abilities." — every character that player controls is protected while it remains.
	 */
	boolean charactersProtectedFromOppReturnToHand(boolean targetIsP1) {
		List<CardData> fwds = targetIsP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = targetIsP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = targetIsP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && ActionResolver.hasCharactersCannotBeReturnedFieldAbility(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && ActionResolver.hasCharactersCannotBeReturnedFieldAbility(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && ActionResolver.hasCharactersCannotBeReturnedFieldAbility(c)) return true;
		return false;
	}

	/**
	 * Returns {@code true} when the opposing player (of player {@code targetIsP1}) controls a field card
	 * with "The power of Forwards opponent controls cannot be increased by Summons or abilities."
	 * Used to suppress positive power boosts applied to player {@code targetIsP1}'s Forwards.
	 */
	boolean oppForwardPowerBoostSuppressedFor(boolean targetIsP1) {
		// The suppressing player is the opponent of the player whose Forwards would be boosted.
		List<CardData> fwds = targetIsP1 ? p2ForwardCards : p1ForwardCards;
		CardData[]     bkps = targetIsP1 ? p2BackupCards  : p1BackupCards;
		List<CardData> mons = targetIsP1 ? p2MonsterCards : p1MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppForwardPowerBoostSuppression(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppForwardPowerBoostSuppression(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppForwardPowerBoostSuppression(c)) return true;
		// Meltigemini 8-128R names no controller, so it binds both sides and is searched on both —
		// including the row of the player whose Forwards are being boosted, which the scan above
		// deliberately skips.
		if (allForwardPowerBoostSuppressed()) return true;
		if (targetIsP1 && p1Turn.fwdBoostSuppressedThisTurn) return true;
		if (!targetIsP1 && p2Turn.fwdBoostSuppressedThisTurn) return true;
		return false;
	}

	/**
	 * Whether anybody on the table is printing "The power of Forwards cannot be increased by Summons
	 * or abilities." — Meltigemini 8-128R, which suppresses the boost wherever the Forward sits and
	 * whoever is applying it, its own controller included.
	 */
	private boolean allForwardPowerBoostSuppressed() {
		for (boolean side : new boolean[]{true, false})
			for (CardData c : fieldCards(side))
				if (c != null && !lostAbilitiesCards.contains(c)
						&& AutoAbilityTriggers.hasAllForwardPowerBoostSuppression(c)) return true;
		return false;
	}

	/**
	 * Returns {@code true} when the forward-controlling player cannot increase those Forwards' power
	 * via their own Summons or abilities (i.e., self-boost only is suppressed).
	 * Checked in addition to {@link #oppForwardPowerBoostSuppressedFor} when the booster IS the
	 * same player as the forward controller.
	 */
	boolean oppForwardSelfBoostSuppressedFor(boolean targetIsP1) {
		List<CardData> fwds = targetIsP1 ? p2ForwardCards : p1ForwardCards;
		CardData[]     bkps = targetIsP1 ? p2BackupCards  : p1BackupCards;
		List<CardData> mons = targetIsP1 ? p2MonsterCards : p1MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppForwardSelfBoostSuppression(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppForwardSelfBoostSuppression(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppForwardSelfBoostSuppression(c)) return true;
		return false;
	}

	/**
	 * Whether {@code card}, sitting in {@code ownerIsP1}'s Break Zone, is shielded from being
	 * removed from the game by the opposing player's Summons or abilities.
	 *
	 * <p>Two printings answer this, and the wider one subsumes the narrower: Lenna 18-100L and
	 * Ultimecia 22-073L shield every card in the zone, Terra 23-011L shields only its Summons.
	 *
	 * <p>Opponent-scoped, like its {@link #bzCardsProtectedFromOppChoice} neighbour — the zone's
	 * owner may still remove their own cards, which is what keeps their own recursion working.
	 * Callers are responsible for establishing that the remover is the opponent.
	 */
	boolean bzCardProtectedFromOppRfg(CardData card, boolean ownerIsP1) {
		for (CardData c : fieldCards(ownerIsP1)) {
			if (lostAbilitiesCards.contains(c)) continue;
			if (ActionResolver.hasBzCardRfgProtection(c)) return true;
			if (card != null && card.isSummon() && ActionResolver.hasBzSummonRfgProtection(c)) return true;
		}
		return false;
	}

	/**
	 * Returns {@code true} if the given player has a field card that protects their Break Zone
	 * Summons from being removed from the game by the opponent's Summons or abilities.
	 *
	 * <p>The Summon-shaped question {@link #bzCardProtectedFromOppRfg} answers per card, for the
	 * one caller that is filtering a candidate list rather than blocking a single removal.
	 */
	boolean bzSummonsProtectedFromOppRfg(boolean ownerIsP1) {
		for (CardData c : fieldCards(ownerIsP1)) {
			if (lostAbilitiesCards.contains(c)) continue;
			if (ActionResolver.hasBzSummonRfgProtection(c)) return true;
			if (ActionResolver.hasBzCardRfgProtection(c)) return true;
		}
		return false;
	}

	/**
	 * Returns {@code true} when {@code ownerIsP1} controls a field card shielding every card in
	 * their Break Zone from being chosen by the opposing player's Summons or abilities
	 * (Kalmia 18-090R).
	 *
	 * <p>Opponent-scoped: the zone's owner may still choose their own Break Zone cards, which is
	 * what keeps their own recursion working. It shields against being <em>chosen</em>, so an
	 * effect that sweeps the whole zone without picking anything is unaffected.
	 */
	boolean bzCardsProtectedFromOppChoice(boolean ownerIsP1) {
		List<CardData> fwds = ownerIsP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = ownerIsP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = ownerIsP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && ActionResolver.hasBzCardChoiceProtection(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && ActionResolver.hasBzCardChoiceProtection(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && ActionResolver.hasBzCardChoiceProtection(c)) return true;
		return false;
	}

	/**
	 * Returns {@code true} when {@code isP1}'s Forwards may not use action abilities right now —
	 * Sin 14-045H, "During your opponent's turn, the Forwards opponent controls cannot use action
	 * abilities."
	 *
	 * <p>The lock is doubly scoped and both halves point at the same player, the one who does
	 * <em>not</em> control Sin: it binds that player's Forwards, and only while the turn is
	 * theirs. Sin's own controller is never affected, and neither player is affected on Sin's
	 * controller's turn.
	 */
	boolean forwardActionAbilitiesLockedFor(boolean isP1) {
		GameState.Player self = isP1 ? GameState.Player.P1 : GameState.Player.P2;
		if (gameState.getCurrentPlayer() != self) return false;
		List<CardData> fwds = isP1 ? p2ForwardCards : p1ForwardCards;
		CardData[]     bkps = isP1 ? p2BackupCards  : p1BackupCards;
		List<CardData> mons = isP1 ? p2MonsterCards : p1MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppForwardsActionAbilityLock(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppForwardsActionAbilityLock(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppForwardsActionAbilityLock(c)) return true;
		return false;
	}

	/**
	 * Returns {@code true} when the Characters {@code isP1} controls may not use special or action
	 * abilities at all — The Emperor 2-147L, "The Characters opponent controls cannot use special or
	 * action abilities."
	 *
	 * <p>The wide twin of {@link #forwardActionAbilitiesLockedFor}: no turn gate, no ability-kind
	 * exemption, and every Character rather than the Forward row alone. Only the side scoping is
	 * shared — the lock is read off the opposing field, so its own controller is never bound by it.
	 */
	boolean characterAbilitiesLockedFor(boolean isP1) {
		List<CardData> fwds = isP1 ? p2ForwardCards : p1ForwardCards;
		CardData[]     bkps = isP1 ? p2BackupCards  : p1BackupCards;
		List<CardData> mons = isP1 ? p2MonsterCards : p1MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppCharacterAbilityLock(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppCharacterAbilityLock(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppCharacterAbilityLock(c)) return true;
		return false;
	}

	/**
	 * Returns {@code true} when the Summon {@code casterIsP1} is casting right now has its effect
	 * cancelled by the opposing board — The Fiend 20-114L, "During each turn, when your opponent
	 * casts a Summon for the first time in that turn, cancel its effect."
	 *
	 * <p>Call sites must have already counted the cast: "the first time in that turn" is read off
	 * {@link PlayerTurnState#summonsCastThisTurn}, which {@link #pushSummonOnStack} increments as the
	 * entry goes on the Stack.
	 */
	boolean castSummonIsCancelledByOpponent(boolean casterIsP1) {
		if (turn(casterIsP1).summonsCastThisTurn != 1) return false;
		List<CardData> fwds = casterIsP1 ? p2ForwardCards : p1ForwardCards;
		CardData[]     bkps = casterIsP1 ? p2BackupCards  : p1BackupCards;
		List<CardData> mons = casterIsP1 ? p2MonsterCards : p1MonsterCards;
		for (CardData c : fwds) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppFirstSummonCancel(c)) return true;
		for (CardData c : bkps) if (c != null && !lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppFirstSummonCancel(c)) return true;
		for (CardData c : mons) if (!lostAbilitiesCards.contains(c) && AutoAbilityTriggers.hasOppFirstSummonCancel(c)) return true;
		return false;
	}

	/**
	 * Returns {@code true} when {@code source} may pay {@code ability}'s 《Dull》 cost on the turn it
	 * arrived — because it has Haste, or because something on its side grants the dull-cost half of
	 * Haste to it (Cherukiki 19-109H, Zangan 26-070H).
	 *
	 * <p>The grant is asked here rather than folded into {@code Trait.HASTE} because it is only the
	 * dull-cost half: a Forward under it still may not attack the turn it enters. Which kinds of
	 * ability it reaches is part of the grant — Cherukiki's covers action abilities alone, Zangan's
	 * covers Special Abilities too.
	 */
	boolean canPayDullCostWhileSummoningSick(CardData source, ActionAbility ability, boolean isP1) {
		if (effectiveCardHasTrait(source, isP1, CardData.Trait.HASTE)) return true;
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : fwds) if (grantsDullCostHaste(src, source, ability)) return true;
		for (CardData src : bkps) if (src != null && grantsDullCostHaste(src, source, ability)) return true;
		for (CardData src : mons) if (grantsDullCostHaste(src, source, ability)) return true;
		return false;
	}

	/** Whether {@code src}'s field abilities let {@code target} pay {@code ability}'s dull cost early. */
	private boolean grantsDullCostHaste(CardData src, CardData target, ActionAbility ability) {
		if (lostAbilitiesCards.contains(src)) return false;
		for (FieldAbility fa : effectiveFieldAbilities(src)) {
			AutoAbilityTriggers.DullCostHasteGrant g =
					AutoAbilityTriggers.parseDullCostHasteGrant(fa.effectText());
			if (g == null || !g.coversAbility(ability)) continue;
			// "Zangan and …" — by identity, since a card naming itself means that copy.
			if (src == target && g.selfName() != null
					&& g.selfName().equalsIgnoreCase(src.name())) return true;
			if (g.coversCard(target, jobsStripped(target))) return true;
		}
		return false;
	}

	/**
	 * True when {@code card} is on the board and currently counts as a Forward, whichever side it
	 * is on — for callers that have a card but no seat, such as the resolving source of an ability.
	 *
	 * <p>Reads the board rather than the printed type, so a Monster or Backup an effect has turned
	 * into a Forward answers yes for as long as that lasts.
	 */
	boolean sourceIsActingForward(CardData card) {
		Boolean side = fieldSideOf(card);
		return side != null && isFieldForward(card, side);
	}

	/**
	 * True when {@code card} currently counts as a Forward on {@code isP1}'s field — the Forward
	 * row, plus any Backup or Monster an effect has temporarily made into one.
	 */
	boolean isFieldForward(CardData card, boolean isP1) {
		ForwardTarget slot = findFieldSlot(card, isP1);
		if (slot == null) return false;
		return switch (slot.zone()) {
			case FORWARD -> true;
			case BACKUP  -> isP1 ? isP1BackupTemporarilyForward(slot.idx())  : isP2BackupTemporarilyForward(slot.idx());
			case MONSTER -> isP1 ? isP1MonsterTemporarilyForward(slot.idx()) : isP2MonsterTemporarilyForward(slot.idx());
			default      -> false;
		};
	}

	// -------------------------------------------------------------------------
	// Cast restrictions and granted CP
	// -------------------------------------------------------------------------

	/** @see CostCalculator#castRestrictionMet */
	boolean castRestrictionMet(CardData card) { return costs.castRestrictionMet(card); }

	/** @see CostCalculator#castRestrictionMet */
	boolean castRestrictionMet(CardData card, boolean isP1) { return costs.castRestrictionMet(card, isP1); }

	/** Returns true if any on-field card grants {@code backup} any-element CP. */
	boolean isGrantedAnyElementCp(CardData backup) {
		for (CardData b : p1BackupCards) {
			if (b != null) {
				BackupCpGrant grant = b.backupCpGrant();
				if (grant != null && grant.isAnyElementGrant() && grant.appliesTo(backup, jobsStripped(backup))) return true;
			}
		}
		for (CardData fwd : p1ForwardCards) {
			BackupCpGrant grant = fwd.backupCpGrant();
			if (grant != null && grant.isAnyElementGrant() && grant.appliesTo(backup, jobsStripped(backup))) return true;
		}
		return false;
	}

	/** Returns the union of specific elements granted to {@code backup} by field cards (empty = no specific grant). */
	List<String> getGrantedSpecificElementsCp(CardData backup) {
		List<String> result = null;
		for (CardData b : p1BackupCards) {
			if (b != null) {
				BackupCpGrant grant = b.backupCpGrant();
				if (grant != null && !grant.isAnyElementGrant() && grant.appliesTo(backup, jobsStripped(backup))) {
					if (result == null) result = new ArrayList<>();
					for (String e : grant.grantedElements()) if (!result.contains(e)) result.add(e);
				}
			}
		}
		for (CardData fwd : p1ForwardCards) {
			BackupCpGrant grant = fwd.backupCpGrant();
			if (grant != null && !grant.isAnyElementGrant() && grant.appliesTo(backup, jobsStripped(backup))) {
				if (result == null) result = new ArrayList<>();
				for (String e : grant.grantedElements()) if (!result.contains(e)) result.add(e);
			}
		}
		return result != null ? result : List.of();
	}

	/** @see CostCalculator#canAffordCard */
	boolean canAffordCard(CardData card, int excludeHandIdx) { return costs.canAffordCard(card, excludeHandIdx); }

	/** @see CostCalculator#canAffordCard */
	private boolean canAffordCard(CardData card, int excludeHandIdx, String[] extraRequiredElems, int extraGenericCost) { return costs.canAffordCard(card, excludeHandIdx, extraRequiredElems, extraGenericCost); }

	/** @see CostCalculator#canAffordExtraCost */
	private boolean canAffordExtraCost(CardData card, int handIdx, ExtraCost ec) { return costs.canAffordExtraCost(card, handIdx, ec); }

	// -------------------------------------------------------------------------
	// Extra and alternative cost payment
	// -------------------------------------------------------------------------

	/**
	 * Opens the appropriate extra-cost selection dialog (BZ removal, hand discard, or X spinner),
	 * then chains into the normal CP payment dialog.
	 */
	private void showExtraCostPlayDialog(CardData card, int handIdx, ExtraCost ec) {
		switch (ec.type()) {
			case BZ_REMOVE -> {
				List<CardData> eligible = gameState.getP1BreakZone().stream()
						.filter(ec::matches).collect(Collectors.toList());
				new ExtraCostBzSelectDialog(frame, card, ec, eligible,
						this::showZoomAt, this::hideZoom,
						selectedCards -> {
							pendingExtraCostBzRemovals = selectedCards;
							showPaymentDialog(card, handIdx);
						}).show();
			}
			case DISCARD_HAND -> {
				// Build hand list excluding the card being cast so it cannot be discarded as its own extra cost.
				List<CardData> handChoices = new ArrayList<>(gameState.getP1Hand());
				handChoices.remove(card);
				new ExtraCostBzSelectDialog(frame, card, ec, handChoices,
						this::showZoomAt, this::hideZoom,
						selectedCards -> {
							pendingExtraCostHandDiscards = selectedCards;
							showPaymentDialog(card, handIdx);
						}).show();
			}
			case CP_X -> {
				int maxX = gameState.getP1Hand().size() + 8; // generous upper bound
				JSpinner spinner = new JSpinner(new SpinnerNumberModel(0, 0, maxX, 1));
				int result = JOptionPane.showConfirmDialog(frame,
						new Object[]{"Pay how much extra CP (X)?", spinner},
						"Extra Cost: " + card.name(), JOptionPane.OK_CANCEL_OPTION);
				if (result != JOptionPane.OK_OPTION) return;
				int x = (int) spinner.getValue();
				pendingExtraCostXValue = x;
				pendingExtraCostExtraCp = x;
				showPaymentDialog(card, handIdx);
			}
			case CP_FIXED -> {
				// Fixed, non-negotiable amount — just confirm, no selection needed.
				int result = JOptionPane.showConfirmDialog(frame,
						"Pay " + ec.description() + " to cast " + card.name() + " with its extra cost?",
						"Extra Cost: " + card.name(), JOptionPane.OK_CANCEL_OPTION);
				if (result != JOptionPane.OK_OPTION) return;
				pendingExtraCostCpElements = ec.cpElements();
				showPaymentDialog(card, handIdx);
			}
			case CRYSTAL -> {
				// Crystals are not CP, so the payment dialog below never sees them: confirm here,
				// then pay the printed cost as usual and spend the Crystals with it.
				int result = JOptionPane.showConfirmDialog(frame,
						"Pay " + ec.description() + " to cast " + card.name() + " with its extra cost?",
						"Extra Cost: " + card.name(), JOptionPane.OK_CANCEL_OPTION);
				if (result != JOptionPane.OK_OPTION) return;
				pendingExtraCostCrystals = ec.count();
				showPaymentDialog(card, handIdx);
			}
		}
	}

	/** @see CostCalculator#canAffordAltCost */
	private boolean canAffordAltCost(CardData card, int handIdx) { return costs.canAffordAltCost(card, handIdx); }

	/** @see CostCalculator#canAffordWarpCost */
	private boolean canAffordWarpCost(CardData card, int handIdx) { return costs.canAffordWarpCost(card, handIdx); }

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

		// The card text pays this cost before the CP cost, so the Backup is chosen up front. It is
		// only reserved here: cancelling the payment must leave the board untouched, so the actual
		// removal waits until payment is confirmed below.
		CardData.AltFieldRemoval fieldRemoval = card.altFieldRemoval();
		final List<Integer> removalSlots;
		if (fieldRemoval != null) {
			removalSlots = selectAltFieldRemoval(card, fieldRemoval);
			if (removalSlots == null) return;
		} else {
			removalSlots = List.of();
		}

		// Reserved on the same terms as the removal above: the Forwards are picked now and only
		// dulled once the rest of the cost is covered, so cancelling leaves the board untouched.
		List<DullForwardCost> altDull = card.altDullCosts();
		final List<Integer> dullIdxs;
		if (!altDull.isEmpty()) {
			dullIdxs = selectAltDullForwards(card, altDull);
			if (dullIdxs == null) return;
		} else {
			dullIdxs = List.of();
		}

		// Reserved like the two above, and for the same reason.
		CardData.AltPutToBzCost putToBz = card.altPutToBzCost();
		final List<ForwardTarget> bzPayment;
		if (putToBz != null) {
			bzPayment = selectAltPutToBz(card, putToBz);
			if (bzPayment == null) return;
		} else {
			bzPayment = List.of();
		}

		// And the same again for the reduction form, which hands a card over to knock CP off the
		// printed cost rather than to buy the play outright (Kain 9-084H).
		CardData.AltPutToBzReduction putToBzReduce = card.altPutToBzReduction();
		final List<ForwardTarget> bzReducePayment;
		if (putToBzReduce != null) {
			bzReducePayment = selectAltPutToBzReduction(card, putToBzReduce);
			if (bzReducePayment == null) return;
		} else {
			bzReducePayment = List.of();
		}

		// Picking the cards is itself the confirmation, so a cost made up entirely of dulling or of
		// handing cards over goes straight to the play rather than asking again with an empty price.
		// Kefka 4-080L reaches this with no CP left to pay at all: its sentence buys the play
		// outright rather than reducing a cost.
		if (altElemsList.isEmpty() && altC == 0
				&& (!dullIdxs.isEmpty() || !bzPayment.isEmpty() || !bzReducePayment.isEmpty())) {
			executeAltDull(dullIdxs);
			executeAltFieldRemoval(removalSlots);
			executeAltPutToBz(bzPayment);
			executeAltPutToBz(bzReducePayment);
			executePlay(card, handIdx, Collections.emptyList(), Collections.emptyList(), Map.of());
			executeAltFollowup(followupText, card);
			return;
		}

		if (altElemsList.isEmpty()) {
			int choice = JOptionPane.showOptionDialog(frame,
					card.name() + " — Pay " + "《C》".repeat(altC) + (altCp > 0 ? " + " + altCp + " CP" : "") + " to cast?",
					"Alternate Cost",
					JOptionPane.DEFAULT_OPTION, JOptionPane.PLAIN_MESSAGE, null,
					new Object[]{"Confirm", "Cancel"}, "Confirm");
			if (choice != 0) return;
			if (altC > 0) { playerSpendCrystals(true, altC); refreshCrystalDisplays(); }
			executeAltDull(dullIdxs);
			executeAltFieldRemoval(removalSlots);
			executeAltPutToBz(bzReducePayment);
			executePlay(card, handIdx, Collections.emptyList(), Collections.emptyList(), Map.of());
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

		// A reserved Backup is being handed over, so it cannot also be dulled for CP. Hiding it
		// from the payment dialog is what keeps the deferred removal from letting it be spent twice.
		//
		// The reduction form needs the same guard and needs it more: Kain 9-084H asks for an
		// *active* Lightning Backup, which is exactly a Backup that could otherwise be dulled to
		// pay the very CP its removal reduced.
		CardData[]  payBackups = cpPayableBackupCards(true);
		CardState[] payStates  = playerBackupStates(true);
		String[]    payUrls    = playerBackupUrls(true);
		List<Integer> reservedBackupSlots = new ArrayList<>(removalSlots);
		for (ForwardTarget t : bzReducePayment)
			if (t.zone() == ForwardTarget.CardZone.BACKUP) reservedBackupSlots.add(t.idx());
		if (!reservedBackupSlots.isEmpty()) {
			payBackups = payBackups.clone();
			payStates  = payStates.clone();
			payUrls    = payUrls.clone();
			for (int slot : reservedBackupSlots) payBackups[slot] = null;
		}

		new AltCostPaymentDialog(frame, card, handIdx, altCp, genericNeeded, elems, costByElem,
				backupOnly, gameState.getP1Hand(), payBackups, payStates,
				payUrls, this::showZoomAt, this::hideZoom,
				lightDarkDiscardGrants(true),
				(discards, backups, breaks) -> {
					if (altC > 0) { playerSpendCrystals(true, altC); refreshCrystalDisplays(); }
					executeAltDull(dullIdxs);
					executeAltFieldRemoval(removalSlots);
					executeAltPutToBz(bzReducePayment);
					executeAltBzRemovals(bzRemovals);
					executePlay(card, handIdx, discards, backups, Map.of(), breaks);
					executeAltFollowup(followupText, card);
				}, breakForCpBackupSlots(true)).show();
	}


	/**
	 * P1 backup slot indices that could pay {@code removal} — the cards the player may hand over
	 * for the alternate cast cost.
	 *
	 * <p>Only Backups are offered. Every card printed with this cost removes a Backup, and the
	 * other types would each need their own removal path; returning nothing for them leaves the
	 * alternate cost simply unavailable rather than half-working.
	 */
	List<Integer> altFieldRemovalCandidates(CardData.AltFieldRemoval removal) {
		List<Integer> out = new ArrayList<>();
		if (removal == null || !removal.type().toLowerCase(Locale.ROOT).startsWith("backup")) return out;
		for (int i = 0; i < p1BackupCards.length; i++)
			if (p1BackupCards[i] != null && effectiveContainsElement(p1BackupCards[i], removal.element())) out.add(i);
		return out;
	}

	/**
	 * Asks the player which Backups to hand over for {@code removal}, returning the chosen slot
	 * indices, or {@code null} if they cancelled. Nothing is removed here — the cards are only
	 * reserved, and {@link #executeAltFieldRemoval} takes them once payment is confirmed.
	 */
	private List<Integer> selectAltFieldRemoval(CardData card, CardData.AltFieldRemoval removal) {
		List<Integer> candidates = altFieldRemovalCandidates(removal);
		List<Integer> chosen = new ArrayList<>();
		for (int n = 0; n < removal.count(); n++) {
			List<Integer> remaining = new ArrayList<>(candidates);
			remaining.removeAll(chosen);
			if (remaining.isEmpty()) return null;
			List<CardData> options = remaining.stream().map(i -> p1BackupCards[i]).toList();
			String title = card.name() + " — remove " + removal.element() + " " + removal.type()
					+ " from the game" + (removal.count() > 1 ? " (" + (n + 1) + " of " + removal.count() + ")" : "");
			int pick = cardPickerDialog.pickCardImage(options, title, true);
			if (pick < 0) return null;
			chosen.add(remaining.get(pick));
		}
		return chosen;
	}

	/**
	 * P1 Forward indices that satisfy {@code costs}, one distinct Forward per required slot, or
	 * {@code null} when no such assignment exists.
	 *
	 * <p>The requirements have to be solved together rather than one at a time. Nine 13-123L needs
	 * one Fire and one Lightning Class Zero Cadet, and a single Fire/Lightning Cadet matches both
	 * clauses while only being dullable once — checking each clause on its own would call that
	 * payable.
	 *
	 * @param excluded Forward indices already committed elsewhere in the same payment
	 */
	private List<Integer> altDullAssignment(List<DullForwardCost> costs, Collection<Integer> excluded) {
		List<DullForwardCost> slots = new ArrayList<>();
		for (DullForwardCost dfc : costs)
			for (int i = 0; i < dfc.count(); i++) slots.add(dfc);
		List<Integer> assigned = new ArrayList<>();
		return assignAltDullSlots(slots, 0, new HashSet<>(excluded), assigned) ? assigned : null;
	}

	/** Backtracking search behind {@link #altDullAssignment}; the slot count is never more than a few. */
	private boolean assignAltDullSlots(List<DullForwardCost> slots, int slotIdx,
			Set<Integer> used, List<Integer> assigned) {
		if (slotIdx == slots.size()) return true;
		for (int i = 0; i < p1ForwardCards.size(); i++) {
			if (used.contains(i)) continue;
			if (p1ForwardStates.get(i) != CardState.ACTIVE) continue;
			if (!autoAbilityTriggers.dullForwardCostMatches(slots.get(slotIdx), p1ForwardCards.get(i))) continue;
			used.add(i);
			assigned.add(i);
			if (assignAltDullSlots(slots, slotIdx + 1, used, assigned)) return true;
			used.remove(i);
			assigned.remove(assigned.size() - 1);
		}
		return false;
	}

	/** True when P1 controls the active Forwards {@code card}'s dull alternate cost calls for. */
	boolean canPayAltDullCost(CardData card) {
		List<DullForwardCost> costs = card.altDullCosts();
		if (costs.isEmpty()) return true;
		if (card.altDullYourTurnOnly() && gameState.getCurrentPlayer() != GameState.Player.P1) return false;
		return altDullAssignment(costs, List.of()) != null;
	}

	/**
	 * Asks the player which Forwards to dull for {@code costs}, one clause at a time, returning the
	 * chosen indices or {@code null} if they cancelled. Nothing is dulled here — the Forwards are
	 * only reserved, and {@link #executeAltDull} takes them once the whole cost is covered.
	 *
	 * <p>Each clause only offers Forwards that leave the remaining clauses payable, so the player
	 * cannot spend the one card a later clause depends on.
	 */
	private List<Integer> selectAltDullForwards(CardData card, List<DullForwardCost> costs) {
		List<Integer> chosen = new ArrayList<>();
		for (int c = 0; c < costs.size(); c++) {
			DullForwardCost dfc = costs.get(c);
			List<DullForwardCost> later = costs.subList(c + 1, costs.size());
			for (int n = 0; n < dfc.count(); n++) {
				List<ForwardTarget> eligible = new ArrayList<>();
				for (int i = 0; i < p1ForwardCards.size(); i++) {
					if (chosen.contains(i)) continue;
					if (p1ForwardStates.get(i) != CardState.ACTIVE) continue;
					if (!autoAbilityTriggers.dullForwardCostMatches(dfc, p1ForwardCards.get(i))) continue;
					Set<Integer> committed = new HashSet<>(chosen);
					committed.add(i);
					List<DullForwardCost> rest = new ArrayList<>(later);
					if (n + 1 < dfc.count())
						rest.add(0, new DullForwardCost(dfc.count() - n - 1, dfc.condition(), dfc.element(),
								dfc.cardName(), dfc.job(), dfc.category(), dfc.cardType(), dfc.orCardName()));
					if (altDullAssignment(rest, committed) == null) continue;
					eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
				}
				if (eligible.isEmpty()) return null;
				List<ForwardTarget> picks = showForwardSelectDialog(eligible, 1, false,
						card.name() + " — Alt Cost: dull " + describeAltDullClause(dfc));
				if (picks.size() < 1) return null;
				chosen.add(picks.get(0).idx());
			}
		}
		return chosen;
	}

	/**
	 * The field cards that may pay {@code cost} — Kefka 4-080L's "a total of 3 Forwards or Monsters
	 * you control".
	 *
	 * <p>"a total of" pools the rows rather than requiring a count from each, so this returns one
	 * list across every permitted row and the caller takes any {@code count} of them.
	 */
	List<ForwardTarget> altPutToBzCandidates(CardData.AltPutToBzCost cost, boolean isP1) {
		List<ForwardTarget> out = new ArrayList<>();
		if (cost.inclForwards()) {
			List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
			for (int i = 0; i < fwds.size(); i++) out.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
		}
		if (cost.inclMonsters()) {
			List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
			for (int i = 0; i < mons.size(); i++) out.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
		}
		if (cost.inclBackups()) {
			CardData[] bkps = isP1 ? p1BackupCards : p2BackupCards;
			for (int i = 0; i < bkps.length; i++)
				if (bkps[i] != null) out.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.BACKUP));
		}
		return out;
	}

	/**
	 * Reserves the cards P1 hands over for a put-into-Break-Zone alternate cost, or {@code null}
	 * when there are too few or the player cancels.
	 *
	 * <p>Reserved rather than paid, on the same terms as the dull and removal costs above:
	 * cancelling the play must leave the board exactly as it was.
	 */
	private List<ForwardTarget> selectAltPutToBz(CardData card, CardData.AltPutToBzCost cost) {
		List<ForwardTarget> eligible = altPutToBzCandidates(cost, true);
		if (eligible.size() < cost.count()) return null;
		List<ForwardTarget> picks = showForwardSelectDialog(eligible, cost.count(), false,
				card.name() + " — Alt Cost: put " + describeAltPutToBzCost(cost) + " into the Break Zone");
		if (picks == null || picks.size() < cost.count()) return null;
		return picks;
	}

	/**
	 * Puts the cards reserved by {@link #selectAltPutToBz} into the Break Zone.
	 *
	 * <p>Reverse index order within each zone, because every removal shifts the slots above it —
	 * the same ordering {@code executeAbilityPayment} uses for an ability's Break Zone costs.
	 *
	 * <p>A put, not a break, so the Forwards go through {@link #putP1ForwardIntoBreakZone}. The
	 * Monster and Backup calls need no such counterpart: neither records anything break-specific,
	 * so what they already do <em>is</em> the put.
	 */
	private void executeAltPutToBz(List<ForwardTarget> targets) {
		if (targets == null || targets.isEmpty()) return;
		List<ForwardTarget> sorted = new ArrayList<>(targets);
		sorted.sort((a, b) -> a.zone() == b.zone() ? Integer.compare(b.idx(), a.idx()) : 0);
		for (ForwardTarget t : sorted) {
			pendingCostBreakDestLabel = p1BreakLabel;
			String name = switch (t.zone()) {
				case FORWARD -> t.idx() < p1ForwardCards.size() ? p1ForwardCards.get(t.idx()).name() : "?";
				case MONSTER -> t.idx() < p1MonsterCards.size() ? p1MonsterCards.get(t.idx()).name() : "?";
				default      -> p1BackupCards[t.idx()] != null ? p1BackupCards[t.idx()].name() : "?";
			};
			switch (t.zone()) {
				case FORWARD -> putP1ForwardIntoBreakZone(t.idx());
				case MONSTER -> autoAbilityTriggers.breakP1MonsterSlot(t.idx());
				default      -> autoAbilityTriggers.breakP1BackupSlot(t.idx());
			}
			logEntry("Alt cost: \"" + name + "\" put into the Break Zone");
		}
	}

	/**
	 * The field cards that may pay {@code cost} — Kain 9-084H's "1 active Lightning Backup you
	 * control", handed to the Break Zone to knock 5 off Kain's play cost.
	 *
	 * <p>Every row the printing permits is walked, not the Backups alone: the type is what the text
	 * says it is, and a Backup-only lookup would silently answer "unaffordable" the day a Forward
	 * form is printed.
	 */
	List<ForwardTarget> altPutToBzReductionCandidates(CardData.AltPutToBzReduction cost) {
		String type = cost.type().toLowerCase(Locale.ROOT);
		boolean fwd = type.startsWith("forward") || type.startsWith("character");
		boolean bkp = type.startsWith("backup")  || type.startsWith("character");
		boolean mon = type.startsWith("monster") || type.startsWith("character");
		List<ForwardTarget> out = new ArrayList<>();
		if (fwd)
			for (int i = 0; i < p1ForwardCards.size(); i++)
				if (altPutToBzReductionMatches(cost, p1ForwardCards.get(i), p1ForwardStates.get(i)))
					out.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
		if (mon)
			for (int i = 0; i < p1MonsterCards.size(); i++)
				if (altPutToBzReductionMatches(cost, p1MonsterCards.get(i), p1MonsterStates.get(i)))
					out.add(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER));
		if (bkp)
			for (int i = 0; i < p1BackupCards.length; i++)
				if (p1BackupCards[i] != null
						&& altPutToBzReductionMatches(cost, p1BackupCards[i], p1BackupStates[i]))
					out.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP));
		return out;
	}

	/** Whether one field card satisfies the element and state filters {@code cost} prints. */
	private boolean altPutToBzReductionMatches(CardData.AltPutToBzReduction cost, CardData card,
			CardState state) {
		if (cost.activeOnly() && state != CardState.ACTIVE) return false;
		return cost.element() == null || effectiveContainsElement(card, cost.element());
	}

	/**
	 * Reserves the cards P1 hands over for a play-cost reduction, or {@code null} when there are
	 * too few or the player cancels. Reserved rather than paid, on the same terms as its
	 * neighbours: cancelling the play must leave the board exactly as it was.
	 */
	private List<ForwardTarget> selectAltPutToBzReduction(CardData card,
			CardData.AltPutToBzReduction cost) {
		List<ForwardTarget> eligible = altPutToBzReductionCandidates(cost);
		if (eligible.size() < cost.count()) return null;
		List<ForwardTarget> picks = showForwardSelectDialog(eligible, cost.count(), false,
				card.name() + " — Alt Cost: put " + describeAltPutToBzReduction(cost)
				+ " into the Break Zone");
		if (picks == null || picks.size() < cost.count()) return null;
		return picks;
	}

	/** "1 active Lightning Backup", as printed, for a menu label or dialog title. */
	private static String describeAltPutToBzReduction(CardData.AltPutToBzReduction cost) {
		return cost.count() + (cost.activeOnly() ? " active " : " ")
				+ (cost.element() != null ? cost.element() + " " : "") + cost.type();
	}

	/** "3 Forwards or Monsters", as printed, for a menu label or dialog title. */
	private static String describeAltPutToBzCost(CardData.AltPutToBzCost cost) {
		String types = cost.inclBackups() ? "Characters"
				: cost.inclForwards() && cost.inclMonsters() ? "Forwards or Monsters"
				: cost.inclForwards() ? "Forwards" : "Monsters";
		return cost.count() + " " + types;
	}

	/** Dulls the Forwards reserved by {@link #selectAltDullForwards}. */
	private void executeAltDull(List<Integer> forwardIdxs) {
		if (forwardIdxs == null || forwardIdxs.isEmpty()) return;
		for (int idx : forwardIdxs) {
			p1ForwardStates.set(idx, CardState.DULL);
			animateDullForward(idx, null);
			logEntry("Alt cost: \"" + p1ForwardCards.get(idx).name() + "\" dulled");
		}
	}

	/** "1 active Fire Job Class Zero Cadet Forward", as printed, for a menu label or dialog title. */
	private static String describeAltDullClause(DullForwardCost dfc) {
		StringBuilder sb = new StringBuilder().append(dfc.count()).append(" active ");
		if (dfc.element()  != null) sb.append(dfc.element()).append(' ');
		if (dfc.category() != null) sb.append("Category ").append(dfc.category()).append(' ');
		if (dfc.job()      != null) sb.append(dfc.job()).append(' ');
		return sb.append(dfc.count() == 1 ? "Forward" : "Forwards").toString();
	}

	/** Removes the Backups reserved by {@link #selectAltFieldRemoval} from the game. */
	private void executeAltFieldRemoval(List<Integer> slots) {
		if (slots == null || slots.isEmpty()) return;
		GameContext ctx = buildGameContext(true);
		for (int slot : slots)
			ctx.removeTargetFromGame(new ForwardTarget(true, slot, ForwardTarget.CardZone.BACKUP));
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
					gameState.addToPermanentRfp(c);
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
				gameState.getP1Hand(), cpPayableBackupCards(true), p1BackupStates, p1BackupUrls,
				p1ForwardCards,
				this::showZoomAt, this::hideZoom,
				lightDarkDiscardGrants(true), warpCostAnyElement(true),
				(discards, backups, overrides, breaks) ->
						executeWarpPlay(card, handIdx, discards, backups, overrides, breaks),
				this::gainedElementsForPayment, breakForCpBackupSlots(true), this::jobsStripped)
			.show();
	}


	// -------------------------------------------------------------------------
	// Warp play
	// -------------------------------------------------------------------------

	/**
	 * Pays the Warp alternate cost (dulls backups, discards hand cards), removes the card
	 * from hand, and places it in the Removed-From-Play zone with Warp counters.
	 */
	private void executeWarpPlay(CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			Map<Integer, String> elementOverrides, Map<Integer, String> backupBreaks) {
		List<String> rawCost = card.warpCost();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);
		Set<String> warpCpToClear = new java.util.LinkedHashSet<>(Arrays.asList(elems));

		for (int bi : backupDullIndices) {
			p1BackupStates[bi] = CardState.DULL;
			animateDullBackup(bi, true);
			String cpElem = elementOverrides.containsKey(bi)
					? elementOverrides.get(bi)
					: matchesAnyElement(p1BackupCards[bi], elems)
					? contributingElement(p1BackupCards[bi], elems) : elems[0];
			gameState.addP1Cp(cpElem, 1);
		}
		// Break-for-CP payments (Sherlotta 8-053H), after the dull step so a Backup paying both
		// ways is still on the field for it. Its Element joins the clear set below, so CP the Warp
		// cost did not need is not left in the bank.
		warpCpToClear.addAll(breakBackupsForCp(true, backupBreaks).keySet());
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
			CardData discarded = gameState.getP1Hand().get(di);
			String cpElem = matchesAnyElement(discarded, elems)
					? contributingElement(discarded, elems) : elems[0];
			gameState.addP1Cp(cpElem, 2);
			playerBreakFromHand(true,di);
			if (di < cardHandIdx) cardHandIdx--;
		}
		for (String e : warpCpToClear) {
			gameState.spendP1Cp(e, gameState.getP1CpForElement(e));
			gameState.clearP1Cp(e);
		}
		gameState.removeFromHand(cardHandIdx);

		gameState.addToP1WarpZone(card, card.warpValue());
		logEntry("Played \"" + card.name() + "\" via Warp — " + card.warpValue()
				+ " counter" + (card.warpValue() != 1 ? "s" : "") + " → Removed From Play");
		autoAbilityTriggers.triggerAutoAbilitiesForWarpPlaced(card, true);
		refreshP1HandLabel();
		refreshP1BreakLabel();
		refreshP1WarpZoneUI();
	}

	/**
	 * P2 equivalent of {@link #executeWarpPlay}: pays the Warp alternate cost (dulls P2
	 * backups, breaks P2 hand cards), removes the card from P2's hand, and places it in
	 * P2's Removed-From-Play zone with Warp counters.  Caller is responsible for choosing
	 * which backups/hand cards satisfy the cost — {@code ComputerPlayer.p2PlanWarpPayment}
	 * does, and is the only caller.
	 *
	 * <p>Unlike {@link #executeWarpPlay} this takes no break-for-CP payments (Sherlotta 8-053H).
	 * The planner never produces one, so the parameter would be dead; wiring that route would
	 * mean adding it here and to the planner together.
	 */
	void executeP2WarpPlay(CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			Map<Integer, String> elementOverrides) {
		List<String> rawCost = card.warpCost();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);

		for (int bi : backupDullIndices) {
			p2BackupStates[bi] = CardState.DULL;
			refreshP2BackupSlot(bi);
			String cpElem = elementOverrides.containsKey(bi)
					? elementOverrides.get(bi)
					: matchesAnyElement(p2BackupCards[bi], elems)
					? contributingElement(p2BackupCards[bi], elems) : elems[0];
			gameState.addP2Cp(cpElem, 1);
		}
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
			CardData discarded = gameState.getP2Hand().get(di);
			String cpElem = matchesAnyElement(discarded, elems)
					? contributingElement(discarded, elems) : elems[0];
			gameState.addP2Cp(cpElem, 2);
			playerBreakFromHand(false, di);
			if (di < cardHandIdx) cardHandIdx--;
		}
		for (String e : elems) {
			gameState.spendP2Cp(e, gameState.getP2CpForElement(e));
			gameState.clearP2Cp(e);
		}
		gameState.removeP2FromHand(cardHandIdx);

		gameState.addToP2WarpZone(card, card.warpValue());
		logEntry("[P2] Played \"" + card.name() + "\" via Warp — " + card.warpValue()
				+ " counter" + (card.warpValue() != 1 ? "s" : "") + " → Removed From Play");
		autoAbilityTriggers.triggerAutoAbilitiesForWarpPlaced(card, false);
		refreshP2HandCountLabel();
		refreshP2BreakLabel();
		refreshP2WarpZoneUI();
	}

	// -------------------------------------------------------------------------
	// Uniqueness and Light/Dark conflict rules
	// -------------------------------------------------------------------------

	/** Whether P1 already has a Character of this name in play. */
	boolean hasCharacterNameOnField(String name) {
		for (CardData c : p1ForwardCards)
			if (name.equalsIgnoreCase(c.name())) return true;
		for (CardData c : p1MonsterCards)
			if (name.equalsIgnoreCase(c.name())) return true;
		for (CardData c : p1BackupCards)
			if (c != null && name.equalsIgnoreCase(c.name())) return true;
		return false;
	}

	boolean p2HasCharacterNameOnField(String name) {
		for (CardData c : p2ForwardCards)
			if (name.equalsIgnoreCase(c.name())) return true;
		for (CardData c : p2BackupCards)
			if (c != null && name.equalsIgnoreCase(c.name())) return true;
		return false;
	}

	/**
	 * Returns true if playing {@code card} would violate the Light/Dark field restriction,
	 * accounting for self-exception and field-grant exceptions.
	 *
	 * <p>Same-element conflicts (e.g. Dark vs Dark) are suppressed when either the card
	 * carries a self-exception or a card already on P1's field grants multi-play for that
	 * element.  Cross-element conflicts (Dark vs Light) are never suppressed.
	 */
	private boolean isLightDarkConflict(CardData card) {
		if (!card.isLightOrDark()) return false;
		for (String elem : card.elements()) {
			if (!"Light".equalsIgnoreCase(elem) && !"Dark".equalsIgnoreCase(elem)) continue;
			String crossElem = "Dark".equalsIgnoreCase(elem) ? "Light" : "Dark";
			// Cross-element always conflicts
			if (hasSpecificElementOnField(crossElem)) return true;
			// Same-element conflicts unless an exception is active
			if (hasSpecificElementOnField(elem) && !isLightDarkExceptionActive(elem, card)) return true;
		}
		return false;
	}

	/**
	 * Returns true if a "You can play 2 or more Card Name X" exception is active on {@code isP1}'s
	 * field for the given card name, allowing the name-uniqueness rule to be bypassed.
	 *
	 * <p>The grant is a permission its own controller holds, so it is read from that player's
	 * zones. This used to scan P1's field whoever was asking, which the uniqueness rule process
	 * papered over by only consulting it for P1 at all — leaving P2's second copy broken by a rule
	 * a card on their own field says does not apply to them.
	 */
	private boolean isMultiNameExceptionActive(String cardName, boolean isP1) {
		for (CardData c : (isP1 ? p1ForwardCards : p2ForwardCards))
			if (cardName.equalsIgnoreCase(c.grantsMultiNamePlay())) return true;
		for (CardData c : (isP1 ? p1MonsterCards : p2MonsterCards))
			if (cardName.equalsIgnoreCase(c.grantsMultiNamePlay())) return true;
		for (CardData c : (isP1 ? p1BackupCards : p2BackupCards))
			if (c != null && cardName.equalsIgnoreCase(c.grantsMultiNamePlay())) return true;
		return false;
	}

	/** Returns true if P1's field contains at least one character with the given element. */
	private boolean hasSpecificElementOnField(String element) {
		for (CardData c : p1ForwardCards)
			for (String e : c.elements()) if (element.equalsIgnoreCase(e)) return true;
		for (CardData c : p1MonsterCards)
			for (String e : c.elements()) if (element.equalsIgnoreCase(e)) return true;
		for (CardData c : p1BackupCards)
			if (c != null) for (String e : c.elements()) if (element.equalsIgnoreCase(e)) return true;
		return false;
	}

	/** Returns true if a same-element multi-play exception is active for {@code element}. */
	private boolean isLightDarkExceptionActive(String element, CardData cardBeingPlayed) {
		if (element.equalsIgnoreCase(cardBeingPlayed.selfLightDarkPlayException())) return true;
		for (CardData c : p1ForwardCards) if (element.equalsIgnoreCase(c.grantsMultiLightDarkPlay())) return true;
		for (CardData c : p1MonsterCards) if (element.equalsIgnoreCase(c.grantsMultiLightDarkPlay())) return true;
		for (CardData c : p1BackupCards)  if (c != null && element.equalsIgnoreCase(c.grantsMultiLightDarkPlay())) return true;
		return false;
	}

	/** P2 counterpart of {@link #isLightDarkConflict(CardData)} — checks P2's field. */
	boolean isP2LightDarkConflict(CardData card) {
		if (!card.isLightOrDark()) return false;
		for (String elem : card.elements()) {
			if (!"Light".equalsIgnoreCase(elem) && !"Dark".equalsIgnoreCase(elem)) continue;
			String crossElem = "Dark".equalsIgnoreCase(elem) ? "Light" : "Dark";
			if (hasSpecificElementOnP2Field(crossElem)) return true;
			if (hasSpecificElementOnP2Field(elem) && !isP2LightDarkExceptionActive(elem, card)) return true;
		}
		return false;
	}

	/** Returns true if P2's field contains at least one character with the given element. */
	private boolean hasSpecificElementOnP2Field(String element) {
		for (CardData c : p2ForwardCards)
			for (String e : c.elements()) if (element.equalsIgnoreCase(e)) return true;
		for (CardData c : p2MonsterCards)
			for (String e : c.elements()) if (element.equalsIgnoreCase(e)) return true;
		for (CardData c : p2BackupCards)
			if (c != null) for (String e : c.elements()) if (element.equalsIgnoreCase(e)) return true;
		return false;
	}

	/** Returns true if a same-element multi-play exception is active on P2's field for {@code element}. */
	private boolean isP2LightDarkExceptionActive(String element, CardData cardBeingPlayed) {
		if (element.equalsIgnoreCase(cardBeingPlayed.selfLightDarkPlayException())) return true;
		for (CardData c : p2ForwardCards) if (element.equalsIgnoreCase(c.grantsMultiLightDarkPlay())) return true;
		for (CardData c : p2MonsterCards) if (element.equalsIgnoreCase(c.grantsMultiLightDarkPlay())) return true;
		for (CardData c : p2BackupCards)  if (c != null && element.equalsIgnoreCase(c.grantsMultiLightDarkPlay())) return true;
		return false;
	}

	/** Returns true if any Light or Dark character is on the given player's field. */
	boolean hasLightOrDarkOnField(boolean isP1) {
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

	/**
	 * Returns the union of Light/Dark elements the given player may currently discard from
	 * hand to produce CP, granted by "You can discard [Light and Dark|Dark] Element cards
	 * from your hand to produce CP" field abilities on their controlled cards.
	 */
	Set<String> lightDarkDiscardGrants(boolean isP1) {
		java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
		for (CardData c : playerForwardCards(isP1))
			if (!lostAbilitiesCards.contains(c)) out.addAll(c.grantsLightDarkDiscardCp());
		for (CardData c : playerBackupCards(isP1))
			if (c != null && !lostAbilitiesCards.contains(c)) out.addAll(c.grantsLightDarkDiscardCp());
		for (CardData c : playerMonsterCards(isP1))
			if (!lostAbilitiesCards.contains(c)) out.addAll(c.grantsLightDarkDiscardCp());
		return out;
	}

	/** @see CostCalculator#warpCostAnyElement */
	boolean warpCostAnyElement(boolean isP1) { return costs.warpCostAnyElement(isP1); }

	boolean hasAvailableBackupSlot() {
		if (p1BackupLabels == null) return false;
		for (JLabel slot : p1BackupLabels) {
			if (slot != null && slot.getIcon() == null) return true;
		}
		return false;
	}

	private boolean hasAvailableP2BackupSlot() {
		for (CardData slot : p2BackupCards) {
			if (slot == null) return true;
		}
		return false;
	}

	// -------------------------------------------------------------------------
	// Payment dialogs and field-granted discard casts
	// -------------------------------------------------------------------------

	/**
	 * Opens a modal payment dialog where the player selects backups to dull (1 CP each)
	 * and/or hand cards to discard (2 CP each) to cover the cost of {@code card}.
	 *
	 * Constraints enforced:
	 *   - A player may produce any amount of CP when paying a cost, so neither backups nor
	 *     discards are capped at the cost; CP produced beyond it is simply wasted.
	 *   - A multi-element card still needs at least 1 CP of each of its elements.
	 */
	/**
	 * Opens the standard payment dialog for a card being cast from P1's Break Zone (via
	 * "Choose 1 Summon in your Break Zone, you can cast it this turn" effects).  Reuses
	 * {@link StandardPaymentDialog} with {@code handIdx = -1} so no hand card is excluded
	 * from the discard list, and routes the confirm callback to {@link #executePlayFromBzP1}.
	 */
	private void showBzPlayPaymentDialog(CardData card, int reducedCost) {
		if (reducedCost <= 0) {
			executePlayFromBzP1(card, List.of(), List.of(), Map.of());
			return;
		}
		PlayableEntry entry = bzPlayableP1.get(card);
		boolean anyElement = isAnyElementCast(card) || (entry != null && entry.anyElement());
		new StandardPaymentDialog(frame, card, -1, reducedCost,
				gameState.getP1Hand(), cpPayableBackupCards(true), p1BackupStates, p1BackupUrls,
				this::showZoomAt, this::hideZoom,
				new ArrayList<>(p1ForwardCards),
				(discards, backups, overrides, breaks) ->
						executePlayFromBzP1(card, discards, backups, overrides, breaks),
				anyElement, null, lightDarkDiscardGrants(true), this::gainedElementsForPayment,
				breakForCpBackupSlots(true), this::jobsStripped)
			.show();
	}

	void showPaymentDialog(CardData card, int handIdx) {
		int extraGeneric = pendingExtraCostCpElements == null ? 0
				: (int) pendingExtraCostCpElements.stream().filter(String::isEmpty).count();
		int cost = effectiveCastCost(card) + pendingExtraCostExtraCp + extraGeneric;
		pendingExtraCostExtraCp = 0;
		if (cost <= 0) {
			executePlay(card, handIdx, List.of(), List.of(), Map.of());
			return;
		}
		String[] extraElems = pendingExtraCostCpElements == null ? null
				: pendingExtraCostCpElements.stream().filter(e -> !e.isEmpty()).distinct().toArray(String[]::new);
		new StandardPaymentDialog(frame, card, handIdx, cost,
				gameState.getP1Hand(), cpPayableBackupCards(true), p1BackupStates, p1BackupUrls,
				this::showZoomAt, this::hideZoom,
				new ArrayList<>(p1ForwardCards),
				(discards, backups, overrides, breaks) ->
						executePlay(card, handIdx, discards, backups, overrides, breaks),
				isAnyElementCast(card), extraElems, lightDarkDiscardGrants(true),
				this::gainedElementsForPayment, breakForCpBackupSlots(true), this::jobsStripped)
			.show();
	}

	/**
	 * Pays the break half of a CP payment: puts each named Backup into the Break Zone and banks the
	 * CP it produces — Sherlotta 8-053H, "you may put Sherlotta into the Break Zone to produce 1 CP
	 * of any Element in order to pay a CP cost."
	 *
	 * <p>Run from inside the play it pays for, after the Backups being dulled have been dulled and
	 * before the cost is spent. The order matters both ways: a Backup selected for <em>both</em>
	 * payments must still be on the field when the dull step reads it, and its CP must be in the
	 * bank — and in the caller's accumulator, so the clear step reaches an off-Element CP the cast
	 * did not need — by the time the cost is taken. Slots are broken high-index first so a break
	 * cannot shift a slot another entry still names, the same reason the discard step removes from
	 * hand in reverse-index order.
	 *
	 * <p>Mirrored across both players for the same reason {@code executePlay} is: a networked
	 * opponent replays this exact code against their own zones.
	 *
	 * @return the CP produced, Element to amount; empty when nothing was broken
	 */
	Map<String, Integer> breakBackupsForCp(boolean isP1, Map<Integer, String> breakElements) {
		Map<String, Integer> produced = new LinkedHashMap<>();
		if (breakElements == null || breakElements.isEmpty()) return produced;
		Map<Integer, Integer> eligible = breakForCpBackupSlots(isP1);
		List<Integer> slots = new ArrayList<>(breakElements.keySet());
		slots.sort(Comparator.reverseOrder());
		CardData[] bkps = isP1 ? p1BackupCards : p2BackupCards;
		for (int slot : slots) {
			if (slot < 0 || slot >= bkps.length || bkps[slot] == null) continue;
			String cpElem = breakElements.get(slot);
			int amount = eligible.getOrDefault(slot, 1);
			String name = bkps[slot].name();
			if (isP1) autoAbilityTriggers.breakP1BackupSlot(slot); else breakP2BackupSlot(slot);
			addCp(isP1, cpElem, amount);
			produced.merge(cpElem, amount, Integer::sum);
			if (cpElem != null && !cpElem.isEmpty()) lastCastActualPaymentElements.add(cpElem);
			logEntry((isP1 ? "" : "[P2] ") + name + " broken for " + amount + " " + cpElem + " CP");
		}
		return produced;
	}

	/** Carries a field-granted "discard N Job X to cast [CardName]" alt cost entry. */
	record FieldDiscardCastEntry(int count, String job) {}

	/**
	 * Discard-cast entries that would let {@code isP1} play {@code card} by discarding instead of
	 * paying its cost.
	 *
	 * <p>Two sources, because the sentence appears in both places. A card on the field can grant it
	 * to a card named in its text, and a card can print it about itself — which is what both corpus
	 * printings do (False Hero 18-087C, King 9-010R). The self case has to be read off {@code card}
	 * directly: it is sitting in hand while this runs, so the field scan cannot see it, and the
	 * cost parsed but was never offered.
	 */
	List<FieldDiscardCastEntry> findDiscardCastGrants(CardData card, boolean isP1) {
		List<FieldDiscardCastEntry> result = new ArrayList<>();
		addDiscardCastGrants(card, card.name(), result);
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : fwds)                   addDiscardCastGrants(src, card.name(), result);
		for (CardData bkp : bkps) if (bkp != null) addDiscardCastGrants(bkp, card.name(), result);
		for (CardData src : mons)                   addDiscardCastGrants(src, card.name(), result);
		return result;
	}

	private void addDiscardCastGrants(CardData src, String targetCardName, List<FieldDiscardCastEntry> out) {
		if (lostAbilitiesCards.contains(src)) return;
		for (FieldAbility fa : src.fieldAbilities()) {
			Matcher m = AutoAbilityTriggers.FA_DISCARD_JOB_TO_CAST.matcher(fa.effectText());
			if (!m.find()) continue;
			String target = AutoAbilityTriggers.discardJobToCastTarget(m);
			if (target != null && target.equalsIgnoreCase(targetCardName))
				out.add(new FieldDiscardCastEntry(Integer.parseInt(m.group("count")), m.group("job").trim()));
		}
	}

	/** Returns true if P1's hand has at least {@code count} cards matching Job {@code job}, excluding {@code excludeIdx}. */
	private boolean hasEligibleJobInHand(String job, int excludeIdx, int count) {
		List<CardData> hand = gameState.getP1Hand();
		int found = 0;
		for (int i = 0; i < hand.size(); i++) {
			if (i != excludeIdx && CardFilters.meetsJobFilter(hand.get(i), job)) found++;
		}
		return found >= count;
	}

	/** Shows the field-granted discard-cast dialog for P1: pick {@code grant.count()} Job cards to discard, then cast free. */
	private void showFieldDiscardCastDialog(CardData card, int handIdx, FieldDiscardCastEntry grant) {
		List<CardData> hand = gameState.getP1Hand();
		List<Integer> eligible = new ArrayList<>();
		for (int i = 0; i < hand.size(); i++) {
			if (i != handIdx && CardFilters.meetsJobFilter(hand.get(i), grant.job())) eligible.add(i);
		}
		if (eligible.isEmpty()) { logEntry("No eligible Job " + grant.job() + " in hand"); return; }
		HandPickDialog.showDiscardByType(frame, hand, eligible, "Job " + grant.job(),
				this::showZoomAt, this::hideZoom, discardIdx -> {
					CardData d = playerBreakFromHand(true, discardIdx);
					if (d != null) {
						logEntry("Discards " + d.name() + " (alt cost — casting " + card.name() + " for free)");
						p1Turn.discardedByEffectThisTurn = true;
					}
					refreshP1HandLabel();
					refreshP1BreakLabel();
					int adjustedHandIdx = discardIdx < handIdx ? handIdx - 1 : handIdx;
					executePlay(card, adjustedHandIdx, Collections.emptyList(), Collections.emptyList(), Map.of());
				});
	}

	/** Returns true if any field card grants any-element payment for {@code card}. */
	private boolean isAnyElementCast(CardData card) {
		// The card's own printing comes first: Tifa 11-071L carries the permission on the card being
		// played, which the board walk below cannot see — it is still in hand, not on the field.
		if (selfGrantsAnyElement(card)) return true;
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

	/**
	 * True when {@code card}'s own "…and can be paid with CP of any Element" self-cost modifier is
	 * live right now. The permission shares its condition with the reduction printed alongside it,
	 * so it is switched on by the same unit count {@link CostCalculator#effectiveCastCost} reads —
	 * a Tifa played while no Cloud is on the field is neither discounted nor freely payable.
	 */
	boolean selfGrantsAnyElement(CardData card) {
		for (SelfCostModifier mod : card.selfCostModifiers())
			if (mod.anyElement() && computeSelfCostUnits(mod, true) > 0) return true;
		return false;
	}

	private boolean srcGrantsAnyElement(CardData src, CardData card, boolean srcIsP1) {
		for (FieldCostReduction fcr : src.fieldCostReductions()) {
			if (!fcr.anyElement()) continue;
			if (fcr.ownerOnly() && !srcIsP1) continue;
			if (!fieldCostModifierLive(fcr, srcIsP1)) continue;
			if (!fcr.matchesCard(card)) continue;
			if (fcr.bzConditionJob() != null) {
				List<CardData> bz = srcIsP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
				String job = fcr.bzConditionJob();
				if (bz.stream().noneMatch(c -> c.hasJob(job))) continue;
			}
			return true;
		}
		return false;
	}

	// -------------------------------------------------------------------------
	// Playing a card - from hand and from the Break Zone
	// -------------------------------------------------------------------------

	/**
	 * The turn-scoped cast bookkeeping every casting path records, and the point the ordinal cast
	 * triggers fire from — "During each turn, when you cast the second card you've cast, …"
	 * (Shikaree G 15-051C, Atomos 16-043H) and Rosa 14-057H's "…this turn" spelling of it.
	 *
	 * <p>One method rather than the same four lines at each call site, because the trigger has to
	 * see every cast to fire on the right one: a path that recorded the cast without firing would
	 * leave the counter and the trigger out of step for the rest of the turn.
	 *
	 * <p>The Summon-counting sibling (Belgemine 24-052L) is not fired here. It is measured against
	 * {@link PlayerTurnState#summonsCastThisTurn}, which {@link #pushSummonOnStack} owns — a Summon
	 * is cast here but reaches the Stack there, and that is the moment its printings speak about.
	 */
	void noteCardCast(CardData card, boolean isP1) {
		PlayerTurnState playerTurn = turn(isP1);
		playerTurn.cardsCastThisTurn++;
		for (String j : card.jobs()) playerTurn.castJobsThisTurn.add(j.toLowerCase());
		playerTurn.castNamesThisTurn.add(card.name().toLowerCase());
		playerTurn.castCountByNameThisTurn.merge(card.name().toLowerCase(), 1, Integer::sum);
		autoAbilityTriggers.triggerAutoAbilitiesForNthCardCast(isP1, playerTurn.cardsCastThisTurn);
	}

	/**
	 * The local player's play, which a networked opponent also has to see.
	 *
	 * <p>The play runs before the action is sent, because a Summon chooses its targets while it
	 * goes on the Stack and those choices have to travel with it — the other client must not make
	 * them a second time. Nothing else in {@code executePlay} emits a network action, so no
	 * message can overtake this one by running first.
	 */
	private void executePlay(CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			Map<Integer, String> backupElementOverrides) {
		executePlay(card, cardHandIdx, discardIndices, backupDullIndices, backupElementOverrides,
				Map.of());
	}

	/**
	 * @param backupBreaks Backups to put into the Break Zone for CP as part of this payment, slot
	 *                     to the Element each produces (Sherlotta 8-053H). Travels to the other
	 *                     client with the play, so both spend the same payment.
	 */
	private void executePlay(CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			Map<Integer, String> backupElementOverrides, Map<Integer, String> backupBreaks) {
		lastSummonPreTargets = null;
		executePlay(true, card, cardHandIdx, discardIndices, backupDullIndices,
				backupElementOverrides, null, false, backupBreaks);
		sendToOpponent(RemoteOpponent.playCardAction(card, cardHandIdx, discardIndices,
				backupDullIndices, backupElementOverrides, lastSummonPreTargets, backupBreaks));
	}

	/**
	 * Executes the play: dulls selected backups, discards payment cards (high-index
	 * first to preserve indices), adds the generated CP to the bank, spends the cost,
	 * removes the played card from hand, and places it in the appropriate zone.
	 *
	 * <p>Parameterised by player so a networked opponent's play runs this exact code against
	 * P2's zones. The alternative — a hand-written P2 mirror — would be two implementations of
	 * one rule, and the moment they drifted the two clients would disagree about the board.
	 * Every input here is an index or a slot, so the same arguments produce the same result on
	 * both clients.
	 */
	void executePlay(boolean isP1, CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			Map<Integer, String> backupElementOverrides) {
		executePlay(isP1, card, cardHandIdx, discardIndices, backupDullIndices,
				backupElementOverrides, null, false, Map.of());
	}

	/**
	 * @param replayedSummonTargets the targets a Summon already chose on the other client
	 * @param targetsAreReplayed    {@code true} when {@code replayedSummonTargets} is the choice
	 *                              made by this play's owner and must be used as-is; {@code false}
	 *                              when this client owns the play and chooses now
	 */
	void executePlay(boolean isP1, CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			Map<Integer, String> backupElementOverrides,
			List<ForwardTarget> replayedSummonTargets, boolean targetsAreReplayed) {
		executePlay(isP1, card, cardHandIdx, discardIndices, backupDullIndices,
				backupElementOverrides, replayedSummonTargets, targetsAreReplayed, Map.of());
	}

	/**
	 * @param backupBreaks Backups put into the Break Zone for CP as part of this payment, slot to
	 *                     the Element each produces (Sherlotta 8-053H). Spent after the dull step
	 *                     and before the cost, so a Backup selected for both payments is still on
	 *                     the field when the dull step reads it.
	 */
	void executePlay(boolean isP1, CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			Map<Integer, String> backupElementOverrides,
			List<ForwardTarget> replayedSummonTargets, boolean targetsAreReplayed,
			Map<Integer, String> backupBreaks) {
		String[] elems = card.elements();
		boolean  isLD  = card.isLightOrDark();
		CardData[]     backupCards = isP1 ? p1BackupCards  : p2BackupCards;
		CardState[]    backupStates= isP1 ? p1BackupStates : p2BackupStates;
		List<CardData> hand        = isP1 ? gameState.getP1Hand() : gameState.getP2Hand();
		Map<String, Integer> execCostByElem = new LinkedHashMap<>();
		if (!isLD) for (String e : elems) execCostByElem.put(e, 1);
		Map<String, Integer> execCpAccum = new LinkedHashMap<>();
		lastCastActualPaymentElements.clear();
		lastCastPaymentBackups.clear();

		// Backups: sort by fewest element matches first for optimal assignment.
		List<Integer> sortedBackups = new ArrayList<>(backupDullIndices);
		if (!isLD) sortedBackups.sort(Comparator.comparingInt(s ->
				(int) Arrays.stream(elems)
						.filter(e -> effectiveContainsElement(backupCards[s], e)).count()));
		for (int bi : sortedBackups) {
			lastCastPaymentBackups.add(backupCards[bi]);
			backupStates[bi] = CardState.DULL;
			if (isP1) animateDullBackup(bi, true); else animateDullP2Backup(bi, true);
			String cpElem;
			if (backupElementOverrides.containsKey(bi)) {
				cpElem = backupElementOverrides.get(bi);
			} else if (isLD) {
				cpElem = backupCards[bi].elements()[0];
			} else {
				cpElem = contributingElement(backupCards[bi], elems, execCpAccum, execCostByElem);
			}
			addCp(isP1, cpElem, 1);
			execCpAccum.merge(cpElem, 1, Integer::sum);
			String actualElem = backupElementOverrides.containsKey(bi)
					? backupElementOverrides.get(bi) : backupCards[bi].elements()[0];
			if (!actualElem.isEmpty()) lastCastActualPaymentElements.add(actualElem);
		}

		// Break-for-CP payments (Sherlotta 8-053H), after the dull step so a Backup paying both
		// ways is still on the field for it, and before the cost so its CP is banked and accounted.
		breakBackupsForCp(isP1, backupBreaks).forEach((e, n) -> execCpAccum.merge(e, n, Integer::sum));

		// Discards: pre-compute optimal element assignments (fewer matches first),
		// then remove from hand in reverse-index order to avoid index shifting.
		List<Integer> assignOrder = new ArrayList<>(discardIndices);
		if (!isLD) assignOrder.sort(Comparator.comparingInt(i ->
				(int) Arrays.stream(elems)
						.filter(e -> hand.get(i).containsElement(e)).count()));
		Map<Integer, String> cpAssignments = new LinkedHashMap<>();
		for (int i : assignOrder) {
			CardData d = hand.get(i);
			String cpElem = isLD ? d.elements()[0]
					: contributingElement(d, elems, execCpAccum, execCostByElem);
			cpAssignments.put(i, cpElem);
			execCpAccum.merge(cpElem, 2, Integer::sum);
			String actualElem = d.elements()[0];
			if (!actualElem.isEmpty()) lastCastActualPaymentElements.add(actualElem);
		}
		List<Integer> discardRemovalOrder = new ArrayList<>(discardIndices);
		discardRemovalOrder.sort(Collections.reverseOrder());
		for (int di : discardRemovalOrder) {
			addCp(isP1, cpAssignments.get(di), 2);
			playerBreakFromHand(isP1, di);
			if (di < cardHandIdx) cardHandIdx--;
		}
		// Clear all CP generated during payment — includes off-element CP from L/D card discards
		// (e.g. discarding Fire Ifrits to pay for a Light card generates Fire CP that must be cleared)
		Set<String> cpToClear = new java.util.LinkedHashSet<>(Arrays.asList(elems));
		cpToClear.addAll(execCpAccum.keySet());
		for (String e : cpToClear) {
			spendCp(isP1, e, cpForElement(isP1, e));
			clearCp(isP1, e);
		}
		// Record distinct element types used for payment (checked by castPaymentMinElements field abilities)
		lastCastPaymentDistinctElements = (int) execCpAccum.keySet().stream()
				.filter(e -> !e.isEmpty()).distinct().count();
		lastCastPaymentElements.clear();
		execCpAccum.keySet().stream().filter(e -> !e.isEmpty()).forEach(lastCastPaymentElements::add);
		lastCastPaymentCard = card;
		lastCastWasPaidByBackupsOnly = discardIndices.isEmpty() && !backupDullIndices.isEmpty();
		if (isP1) { gameState.removeFromHand(cardHandIdx);   refreshP1HandLabel(); }
		else      { gameState.removeP2FromHand(cardHandIdx); refreshP2HandCountLabel(); }
		activeCostReductions.removeIf(m -> m.consumeOnUse() && m.matches(card));
		PlayerTurnState playerTurn = turn(isP1);
		noteCardCast(card, isP1);
		if (card.isSummon()) {
			playerTurn.summonCastThisTurn = true;
			noteDoublecastSummonCast(isP1, card);
			if (isP1) refreshHandCardStates();
		}
		logEntry((isP1 ? "Played \"" : "[P2] Played \"") + card.name() + "\"");

		// Process pending extra cost payments (set by showExtraCostPlayDialog). These come from
		// the local player's own payment dialogs, so a replayed opponent play never has any.
		boolean paidExtraCost = false;
		int extraCostRemovedPower = 0;
		int extraCostXVal = 0;
		if (isP1 && pendingExtraCostBzRemovals != null) {
			paidExtraCost = true;
			List<CardData> removed = pendingExtraCostBzRemovals;
			pendingExtraCostBzRemovals = null;
			if (!removed.isEmpty()) {
				extraCostRemovedPower = removed.get(0).power();
				for (CardData rm : removed) {
					gameState.getP1BreakZone().remove(rm);
					gameState.addToPermanentRfp(rm);
					logEntry("Extra Cost: \"" + rm.name() + "\" removed from game");
				}
			}
		}
		if (isP1 && pendingExtraCostHandDiscards != null) {
			paidExtraCost = true;
			List<CardData> discards = pendingExtraCostHandDiscards;
			pendingExtraCostHandDiscards = null;
			for (CardData dc : discards) {
				gameState.getP1Hand().remove(dc);
				gameState.getP1BreakZone().add(dc);
				currentExtraCostDiscardedCardCost = dc.cost();
				logEntry("Extra Cost: discarded \"" + dc.name() + "\" (cost " + dc.cost() + ")");
			}
			refreshP1HandLabel();
		}
		if (isP1 && pendingExtraCostXValue > 0) {
			paidExtraCost = true;
			extraCostXVal = pendingExtraCostXValue;
			pendingExtraCostXValue = 0;
			logEntry("Extra Cost: paid 《" + extraCostXVal + "》 extra CP");
		}
		if (isP1 && pendingExtraCostCpElements != null) {
			paidExtraCost = true;
			logEntry("Extra Cost: paid " + ExtraCost.cpFixed(pendingExtraCostCpElements).description());
			pendingExtraCostCpElements = null;
		}
		if (isP1 && pendingExtraCostCrystals > 0) {
			int crystals = pendingExtraCostCrystals;
			pendingExtraCostCrystals = 0;
			// Guarded rather than assumed: the Crystal count can have moved between opening the
			// menu and confirming the payment, and an unaffordable surcharge simply goes unpaid.
			if (playerCrystals(true) >= crystals) {
				paidExtraCost = true;
				playerSpendCrystals(true, crystals);
				refreshCrystalDisplays();
				logEntry("Extra Cost: paid " + ExtraCost.crystals(crystals).description());
			} else {
				logEntry("Extra Cost: not enough Crystals — surcharge unpaid");
			}
		}

		lastCardWasCast = true;
		if (card.isBackup()) {
			if (isP1) placeCardInFirstBackupSlot(card); else placeP2CardInFirstBackupSlot(card);
		} else if (card.isForward()) {
			if (isP1) placeCardInForwardZone(card, paidExtraCost); else placeP2CardInForwardZone(card);
		} else if (card.isMonster()) {
			if (isP1) placeCardInMonsterZone(card); else placeP2CardInMonsterZone(card);
		} else if (card.isSummon()) {
			showSummonOnStack(card, isP1, extraCostRemovedPower, extraCostXVal, paidExtraCost,
					replayedSummonTargets, targetsAreReplayed);
		}
		lastCardWasCast = false;

		if (isP1) refreshP1BreakLabel(); else refreshP2BreakLabel();
	}

	/**
	 * Cast variant for cards being played from the Break Zone (not hand) under a
	 * "Choose 1 [Element] Summon in your Break Zone, you can cast it this turn" effect.
	 * Mirrors {@link #executePlay} but pulls the source from the Break Zone, has no
	 * source-hand-index to skip past in discard accounting, and consumes the BZ-playable
	 * registration so the card can't be replayed for free.
	 */
	private void executePlayFromBzP1(CardData card,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			Map<Integer, String> backupElementOverrides) {
		executePlayFromBzP1(card, discardIndices, backupDullIndices, backupElementOverrides, Map.of());
	}

	/**
	 * @param backupBreaks Backups put into the Break Zone for CP as part of this payment, slot to
	 *                     the Element each produces (Sherlotta 8-053H) — spent in the same window
	 *                     {@code executePlay} spends them in.
	 */
	private void executePlayFromBzP1(CardData card,
			List<Integer> discardIndices, List<Integer> backupDullIndices,
			Map<Integer, String> backupElementOverrides, Map<Integer, String> backupBreaks) {
		String[] elems = card.elements();
		boolean  isLD  = card.isLightOrDark();
		Map<String, Integer> execCostByElem = new LinkedHashMap<>();
		if (!isLD) for (String e : elems) execCostByElem.put(e, 1);
		Map<String, Integer> execCpAccum = new LinkedHashMap<>();
		lastCastActualPaymentElements.clear();
		lastCastPaymentBackups.clear();

		List<Integer> sortedBackups = new ArrayList<>(backupDullIndices);
		if (!isLD) sortedBackups.sort(Comparator.comparingInt(s ->
				(int) Arrays.stream(elems)
						.filter(e -> effectiveContainsElement(p1BackupCards[s], e)).count()));
		for (int bi : sortedBackups) {
			lastCastPaymentBackups.add(p1BackupCards[bi]);
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
			String actualElem = backupElementOverrides.containsKey(bi)
					? backupElementOverrides.get(bi) : p1BackupCards[bi].elements()[0];
			if (!actualElem.isEmpty()) lastCastActualPaymentElements.add(actualElem);
		}

		// Break-for-CP payments (Sherlotta 8-053H), in the same window executePlay spends them in.
		breakBackupsForCp(true, backupBreaks).forEach((e, n) -> execCpAccum.merge(e, n, Integer::sum));

		List<Integer> assignOrder = new ArrayList<>(discardIndices);
		if (!isLD) assignOrder.sort(Comparator.comparingInt(i ->
				(int) Arrays.stream(elems)
						.filter(e -> gameState.getP1Hand().get(i).containsElement(e)).count()));
		Map<Integer, String> cpAssignments = new LinkedHashMap<>();
		for (int i : assignOrder) {
			CardData d = gameState.getP1Hand().get(i);
			String cpElem = isLD ? d.elements()[0]
					: contributingElement(d, elems, execCpAccum, execCostByElem);
			cpAssignments.put(i, cpElem);
			execCpAccum.merge(cpElem, 2, Integer::sum);
			String actualElem = d.elements()[0];
			if (!actualElem.isEmpty()) lastCastActualPaymentElements.add(actualElem);
		}
		List<Integer> discardRemovalOrder = new ArrayList<>(discardIndices);
		discardRemovalOrder.sort(Collections.reverseOrder());
		for (int di : discardRemovalOrder) {
			gameState.addP1Cp(cpAssignments.get(di), 2);
			playerBreakFromHand(true, di);
		}
		Set<String> cpToClear = new java.util.LinkedHashSet<>(Arrays.asList(elems));
		cpToClear.addAll(execCpAccum.keySet());
		for (String e : cpToClear) {
			gameState.spendP1Cp(e, gameState.getP1CpForElement(e));
			gameState.clearP1Cp(e);
		}
		lastCastPaymentDistinctElements = (int) execCpAccum.keySet().stream()
				.filter(e -> !e.isEmpty()).distinct().count();
		lastCastPaymentElements.clear();
		execCpAccum.keySet().stream().filter(e -> !e.isEmpty()).forEach(lastCastPaymentElements::add);
		lastCastPaymentCard = card;
		lastCastWasPaidByBackupsOnly = discardIndices.isEmpty() && !backupDullIndices.isEmpty();

		// Remove the borrowed card from its source zone (by identity — duplicate-named copies may exist).
		PlayableEntry borrowEntry = bzPlayableP1.get(card);
		String sourceLabel = removeBorrowedSourceCard(card, borrowEntry);
		boolean fromRfg = BORROW_SOURCE_RFG.equals(sourceLabel);
		bzPlayableP1.remove(card);
		bzForwardFaP1.remove(card);
		bzSelfCastFaP1.remove(card);
		// The permission that opened this card is spent for the turn if its printing says so;
		// recorded against the remover, which is what syncRfgRemovedPlayables asks about. Noted for
		// every such cast, so the sync alone decides which printings the note actually binds.
		CardData removedBy = removedPlayableSourceP1.remove(card);
		if (removedBy != null) p1Turn.castRemovedUsedThisTurn.add(removedBy);
		refreshP1BreakLabel();
		refreshP1WarpZoneUI();
		refreshP2WarpZoneUI();
		refreshPlayableCardsButton();

		activeCostReductions.removeIf(m -> m.consumeOnUse() && m.matches(card));
		noteCardCast(card, true);
		if (card.isSummon()) {
			p1Turn.summonCastThisTurn = true;
			noteDoublecastSummonCast(true, card);
			refreshHandCardStates();
		}
		logEntry("Played \"" + card.name() + "\" from " + sourceLabel);

		// Summons cast under a "remove from the game after use" clause go to the owner's RFP zone
		// instead of the Break Zone once they resolve (Krile 12-061L, Nanaa Mihgo 22-048H).
		if (card.isSummon() && borrowEntry != null && borrowEntry.rfgAfterUse())
			rfgAfterUseSummons.add(card);

		// Borrowed casts are NOT cast from hand: leave lastCardWasCast false so "due to your cast"
		// (castOnly) abilities are skipped and "enters other than from your hand" abilities fire.
		lastCardWasCast = false;
		Runnable placement = () -> {
			if (card.isBackup())       placeCardInFirstBackupSlot(card);
			else if (card.isForward()) placeCardInForwardZone(card);
			else if (card.isMonster()) placeCardInMonsterZone(card);
			else if (card.isSummon())  showSummonOnStack(card, true);
		};
		// Cards coming out of the RFG zone animate in before they are drawn and before their
		// enter-the-field abilities fire; everything else is placed outright.
		if (fromRfg) placeFromRfgWithAnim(card, true, placement);
		else         placement.run();
		lastCardWasCast = false;
	}

	/** Source label {@link #removeBorrowedSourceCard} returns for a card taken out of an RFG zone. */
	private static final String BORROW_SOURCE_RFG = "Removed From Game";

	/**
	 * Removes a borrowed {@code card} from whichever zone it currently sits in, per {@code entry}'s
	 * source.  RFP-sourced cards may live in either player's removed-from-game zone; Break-Zone-sourced
	 * cards may live in either player's Break Zone (Krile/Shantotto draw from "either" Break Zone).
	 * Returns a human-readable source label for logging.
	 */
	private String removeBorrowedSourceCard(CardData card, PlayableEntry entry) {
		if (entry != null && entry.source() == PlayableEntry.SourceZone.RFP) {
			if (gameState.removeFromPermanentRfp(card))
				return BORROW_SOURCE_RFG;
		}
		List<CardData> p1bz = gameState.getP1BreakZone();
		for (int i = 0; i < p1bz.size(); i++) if (p1bz.get(i) == card) { p1bz.remove(i); return "Break Zone"; }
		List<CardData> p2bz = gameState.getP2BreakZone();
		for (int i = 0; i < p2bz.size(); i++) if (p2bz.get(i) == card) { p2bz.remove(i); return "Break Zone"; }
		// Fallback: also sweep RFP zones if the entry was missing/mislabeled.
		if (gameState.removeFromPermanentRfp(card))
			return BORROW_SOURCE_RFG;
		return "outside hand";
	}

	/**
	 * Dulls every backup in {@code dullBackupIndices} and credits 1 CP per backup, using the
	 * pre-computed element assignment from {@code backupElementAssignments}.  Then discards
	 * each hand card in {@code discardIndices} (high-index-first to preserve indices) and
	 * credits 2 CP each via {@code discardElementAssignments}.  Shared by P2's hand-cast and
	 * BZ-cast paths so backup-dulling behaves identically.
	 */
	void payP2CostViaBackupsAndDiscards(List<Integer> dullBackupIndices,
			Map<Integer, String> backupElementAssignments,
			List<Integer> discardIndices,
			Map<Integer, String> discardElementAssignments) {
		for (int bi : dullBackupIndices) {
			p2BackupStates[bi] = CardState.DULL;
			animateDullP2Backup(bi, true);
			String cpElem = backupElementAssignments.get(bi);
			gameState.addP2Cp(cpElem, 1);
			logEntry("[P2] Dulls " + p2BackupCards[bi].name() + " for CP");
		}
		List<Integer> sorted = new ArrayList<>(discardIndices);
		sorted.sort(Collections.reverseOrder());
		for (int di : sorted) {
			CardData d = gameState.getP2Hand().get(di);
			String cpElem = discardElementAssignments.get(di);
			playerBreakFromHand(false, di);
			gameState.addP2Cp(cpElem, 2);
			logEntry("[P2] Discards " + d.name() + " for CP");
		}
		refreshP2BreakLabel();
		refreshP2HandCountLabel();
	}

	/**
	 * P2 equivalent of {@link #executePlayFromBzP1}: pays a reduced cost from P2's dulled
	 * backups and hand discards, removes the source from P2's Break Zone, and places the
	 * card into the appropriate zone.  Caller is responsible for choosing the discard and
	 * backup-dull plans such that the resulting P2 CP covers {@code reducedCost} with
	 * per-element minimums satisfied.
	 */
	void executePlayFromBzP2(CardData card, PlayableEntry entry, int reducedCost,
			List<Integer> discardIndices, Map<Integer, String> discardElementAssignments,
			List<Integer> dullBackupIndices, Map<Integer, String> backupElementAssignments) {
		String[] elems = card.elements();
		boolean freeCast   = entry != null && entry.freeCast();
		boolean anyElement = entry != null && entry.anyElement();

		payP2CostViaBackupsAndDiscards(
				dullBackupIndices, backupElementAssignments,
				discardIndices,    discardElementAssignments);

		if (freeCast) {
			// "without paying the cost" — clear any CP generated for payment and spend nothing.
			for (String e : ActionResolverPatterns.ELEMENT_NAMES) gameState.clearP2Cp(e);
		} else if (anyElement) {
			// Cost may be paid using CP of any Element — drain across all elements, no per-element minimum.
			int remaining = reducedCost;
			for (String e : ActionResolverPatterns.ELEMENT_NAMES) {
				if (remaining <= 0) break;
				int toSpend = Math.min(remaining, gameState.getP2CpForElement(e));
				if (toSpend > 0) { gameState.spendP2Cp(e, toSpend); remaining -= toSpend; }
			}
			for (String e : ActionResolverPatterns.ELEMENT_NAMES) gameState.clearP2Cp(e);
		} else {
			// Pay reducedCost: per-element minimum first if multi-element, then drain CP.
			int remaining = reducedCost;
			if (elems.length > 1) {
				for (String e : elems) { gameState.spendP2Cp(e, 1); remaining--; }
			}
			for (String e : elems) {
				if (remaining <= 0) break;
				int avail = gameState.getP2CpForElement(e);
				int toSpend = Math.min(remaining, avail);
				if (toSpend > 0) { gameState.spendP2Cp(e, toSpend); remaining -= toSpend; }
			}
			for (String e : elems) gameState.clearP2Cp(e);
		}

		// Remove the borrowed card from its source zone (Break Zone or removed-from-game, either player).
		PlayableEntry borrowEntry = bzPlayableP2.get(card);
		String sourceLabel = removeBorrowedSourceCard(card, borrowEntry);
		boolean fromRfg = BORROW_SOURCE_RFG.equals(sourceLabel);
		bzPlayableP2.remove(card);
		bzForwardFaP2.remove(card);
		bzSelfCastFaP2.remove(card);
		// P2's side of the same note P1's cast path takes: a once-per-turn permission is spent by
		// whoever uses it, and the CPU casts through this path.
		CardData p2RemovedBy = removedPlayableSourceP2.remove(card);
		if (p2RemovedBy != null) p2Turn.castRemovedUsedThisTurn.add(p2RemovedBy);
		refreshP2BreakLabel();
		refreshP1WarpZoneUI();
		refreshP2WarpZoneUI();
		refreshPlayableCardsButton();

		if (card.isSummon() && borrowEntry != null && borrowEntry.rfgAfterUse())
			rfgAfterUseSummons.add(card);

		noteCardCast(card, false);
		if (card.isSummon()) { p2Turn.summonCastThisTurn = true; noteDoublecastSummonCast(false, card); }
		logEntry("[P2] Played \"" + card.name() + "\" from " + sourceLabel);

		// Borrowed casts are NOT cast from hand: leave lastCardWasCast false so "due to your cast"
		// (castOnly) abilities are skipped and "enters other than from your hand" abilities fire.
		lastCardWasCast = false;
		Runnable placement = () -> {
			if (card.isBackup())       placeP2CardInFirstBackupSlot(card);
			else if (card.isForward()) placeP2CardInForwardZone(card);
			else if (card.isMonster()) placeP2CardInMonsterZone(card);
			else if (card.isSummon())  showSummonOnStack(card, false);
		};
		// Cards coming out of the RFG zone animate in before they are drawn and before their
		// enter-the-field abilities fire; everything else is placed outright.
		if (fromRfg) placeFromRfgWithAnim(card, false, placement);
		else         placement.run();
		lastCardWasCast = false;
	}

	// -------------------------------------------------------------------------
	// Opponent wiring and Summons on the Stack
	// -------------------------------------------------------------------------

	/**
	 * Builds the controller that will make P2's decisions for the game being started:
	 * the remote human when the lobby handed us a {@link MatchSetup}, the local AI otherwise.
	 */
	private OpponentController createOpponent() {
		GameConnection conn = multiplayerMenu == null ? null : multiplayerMenu.getActiveConnection();
		if (matchSetup != null && conn != null) return new RemoteOpponent(this, conn, matchSetup);
		return new ComputerPlayer(this);
	}

	/** True when P2 is the built-in computer player rather than a remote human. */
	boolean isP2Cpu() {
		// Falls back to the connection state for the window between construction and the first
		// startGame(), when no controller exists yet.
		return opponent == null
				? multiplayerMenu == null || multiplayerMenu.getActiveConnection() == null
				: opponent.isCpu();
	}

	/**
	 * Targets chosen by the most recent {@link #showSummonOnStack} that picked any — read by the
	 * local player's play so they can travel to the other client with the PLAY_CARD action.
	 */
	List<ForwardTarget> lastSummonPreTargets;

	/**
	 * The Summon effect text that will actually resolve, after the extra-cost transforms
	 * {@link #resolveTopOfStack} applies.  Targets are chosen when the Summon goes on the Stack,
	 * so the pre-selection has to read the same text the resolution will — otherwise a
	 * conditional clause could widen or narrow the eligible set between the two.
	 */
	private static String resolvedSummonEffectText(CardData card, boolean paidExtraCost, int xValue) {
		String text = card.summonEffect();
		if (!paidExtraCost) return ActionResolver.stripExtraCostClause(text);
		text = ActionResolver.applyExtraCostPaid(text);
		return xValue > 0 ? text.replace("《X》", String.valueOf(xValue)) : text;
	}

	/**
	 * Has {@code isP1} choose the Summon's targets now, as the rules require — a Summon chooses
	 * when it is cast, not when it resolves, which is what lets the opponent respond to what it
	 * is pointed at.  Returns {@code null} when the effect names no targets to choose up front.
	 */
	private List<ForwardTarget> chooseSummonTargets(CardData card, boolean isP1,
			boolean paidExtraCost, int xValue) {
		List<ForwardTarget> chosen = ActionResolver.preSelectTargets(
				resolvedSummonEffectText(card, paidExtraCost, xValue), card, xValue, buildGameContext(isP1));
		// Normalise "chose nothing" to "nothing to choose": both mean the resolution selects as
		// it always did, and keeping them distinct would make an empty selection preload an empty
		// list and silently fizzle the effect.
		return chosen == null || chosen.isEmpty() ? null : chosen;
	}

	/** Pushes a Summon onto the stack and opens the stack overlay, choosing its targets first. */
	void showSummonOnStack(CardData card, boolean isP1) {
		showSummonOnStack(card, isP1, 0, 0, false, null, false);
	}

	/** Pushes a Summon cast with extra cost onto the stack (no X value). */
	void showSummonOnStack(CardData card, boolean isP1, int extraCostRemovedCardPower) {
		showSummonOnStack(card, isP1, extraCostRemovedCardPower, 0);
	}

	/** Pushes a Summon cast with extra cost onto the stack, including an X value for 《X》 costs (e.g. Valefor). */
	void showSummonOnStack(CardData card, boolean isP1, int extraCostRemovedCardPower, int xValue) {
		showSummonOnStack(card, isP1, extraCostRemovedCardPower, xValue, true, null, false);
	}

	/**
	 * Pushes a Summon onto the Stack with the targets it has chosen.
	 *
	 * @param targetsKnown {@code true} when {@code preTargets} is authoritative — a replay of the
	 *                     other client's play, where the choice was already made by its owner and
	 *                     must not be made again here. {@code false} means choose now.
	 */
	private void showSummonOnStack(CardData card, boolean isP1, int extraCostRemovedCardPower,
			int xValue, boolean paidExtraCost, List<ForwardTarget> preTargets, boolean targetsKnown) {
		pushSummonOnStack(card, isP1, extraCostRemovedCardPower, xValue, paidExtraCost,
				preTargets, targetsKnown);
		showStackWindow();
	}

	/**
	 * The state half of {@link #showSummonOnStack} — chooses the Summon's targets and pushes the
	 * entry, without opening the Stack overlay. Split out so the rule can be exercised without a
	 * realised window.
	 *
	 * <p>The Stack depth is taken before the targets are chosen and the entry inserted back at it:
	 * choosing can trigger the opponent's "when this is chosen" auto-abilities, whose entries must
	 * end up above this Summon so they resolve first (see {@link GameState#insertStack}).
	 *
	 * <p>Every way a Summon can be cast funnels through here, which makes this the place the turn's
	 * Summon count is kept, the place The Fiend 20-114L's cancellation lands, and the place the
	 * cast-a-Summon auto-abilities fire. All three want the cast itself rather than the resolution:
	 * a cancel is only a cancel while the Summon is still on the Stack, and a trigger that reads
	 * "when … casts" goes on the Stack above the Summon and resolves first. Firing here gives that
	 * ordering for free, since {@code executeAutoAbilityImpl} inserts at the current Stack depth.
	 *
	 * <p>Marking the entry cancelled here also means the Stack overlay opens on an already-cancelled
	 * entry, which {@link #showStackWindow} resolves without a response window.
	 */
	void pushSummonOnStack(CardData card, boolean isP1, int extraCostRemovedCardPower,
			int xValue, boolean paidExtraCost, List<ForwardTarget> preTargets, boolean targetsKnown) {
		int depth = gameState.stackSize();
		List<ForwardTarget> targets = targetsKnown
				? (preTargets == null || preTargets.isEmpty() ? null : preTargets)
				: chooseSummonTargets(card, isP1, paidExtraCost, xValue);
		lastSummonPreTargets = targets;
		StackEntry entry = paidExtraCost
				? new StackEntry(card, null, null, isP1, xValue, false, targets, false, true, extraCostRemovedCardPower, 0)
				: new StackEntry(card, null, null, isP1, xValue, false, targets, false, false, 0, 0);
		gameState.insertStack(depth, entry);
		logEntry("[Stack] \"" + card.name() + "\" — Summon on the stack"
				+ (paidExtraCost ? " (Extra Cost paid)" : ""));
		turn(isP1).summonsCastThisTurn++;
		autoAbilityTriggers.triggerAutoAbilitiesForNthSummonCast(isP1, turn(isP1).summonsCastThisTurn);
		if (castSummonIsCancelledByOpponent(isP1) && cancelStackEntry(entry)) {
			logEntry((isP1 ? "" : "[P2] ") + "\"" + card.name()
					+ "\" — the turn's first Summon; its effect is cancelled");
		}
		autoAbilityTriggers.triggerAutoAbilitiesForCastSummon(isP1);
	}

	/**
	 * Escapes card text for display inside an HTML-rendered {@link JLabel}. No glyph fallback is
	 * needed here: Swing's HTML renderer substitutes a font for missing glyphs on its own, which is
	 * why the 《》 CP-cost brackets in ability text already render. Plain-text components get no such
	 * treatment — those go through {@link FontLoader#htmlWithFallback}.
	 */
	private static String escapeForHtmlLabel(String text) {
		return text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
	}

	// -------------------------------------------------------------------------
	// Stack window and stack resolution
	// -------------------------------------------------------------------------

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
	/**
	 * What {@code entry} is actually about to do, as one line of text — the card's effect with the
	 * clauses that will not apply to <em>this</em> resolution already settled.
	 *
	 * <p>Shared by the resolution log line and the stack window's summary so the two cannot
	 * disagree, and so the player reading either sees the branch that is about to run rather than
	 * the card as printed:
	 * <ul>
	 *   <li>the extra-cost clause is applied or stripped according to what was paid, which is what
	 *       {@link ActionResolver#parse} will be handed;</li>
	 *   <li>the "EX BURST" marker is dropped unless the Summon really did resolve off one, where it
	 *       is a property of the card rather than of what is happening;</li>
	 *   <li>the "If [name] results from an EX Burst, … instead." alternative is resolved the way
	 *       resolution will resolve it.</li>
	 * </ul>
	 */
	String resolvingEffectText(StackEntry entry) {
		String effectText = entry.effectText();
		if (effectText == null) return null;
		if (entry.isSummon() || entry.isExBurstEntry()) {
			if (entry.isExBurstEntry() && entry.source().extraCost() != null)
				effectText = ActionResolver.stripExtraCostClause(effectText);
			else if (entry.paidExtraCost()) {
				effectText = ActionResolver.applyExtraCostPaid(effectText);
				if (entry.xValue() > 0)
					effectText = effectText.replace("《X》", String.valueOf(entry.xValue()));
			} else {
				effectText = ActionResolver.stripExtraCostClause(effectText);
			}
			if (!entry.isExBurstEntry()) effectText = ActionResolver.stripExBurstPrefix(effectText);
		}
		return ActionResolver.resolveExBurstInstead(effectText, entry.source(), entry.isExBurstEntry());
	}

	void showStackWindow() {
		StackEntry entry = gameState.peekStack();
		if (entry == null) return;

		if (stackCountdownTimer != null) { stackCountdownTimer.stop(); stackCountdownTimer = null; }
		if (summonStackWindow   != null) { summonStackWindow.dispose(); summonStackWindow = null; }

		// P1 acted → CPU (P2) has priority → auto-resolve silently
		if (entry.isP1()) {
			resolveTopOfStack();
			return;
		}

		// Already cancelled (e.g. by Amaterasu) — no response window needed
		if (cancelledStackEntries.contains(entry)) {
			resolveTopOfStack();
			return;
		}

		// The window is positioned against the main frame, which has no location until it is on
		// screen. An engine test drives the board through a MainWindow that is never shown, and
		// every path that pushes to the Stack calls this — so bail out rather than throwing, and
		// leave the entry on the Stack where it is.
		if (!frame.isShowing()) return;

		// P2 acted → P1 (human) has priority → show interactive Respond/OK window
		final int myGeneration = ++stackWindowGeneration;

		summonStackWindow = new JWindow(frame);

		JPanel panel = new JPanel(new BorderLayout(6, 6));
		panel.setBackground(new Color(28, 24, 40));
		panel.setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createLineBorder(new Color(160, 110, 220), 2),
				BorderFactory.createEmptyBorder(10, 14, 10, 14)));

		String headerText = entry.isSummon() ? "S U M M O N" : entry.isAutoAbility() ? "A U T O" : "A C T I O N";
		JLabel header = new JLabel(headerText, SwingConstants.CENTER);
		header.setFont(FontLoader.loadPixelFont(13));
		header.setForeground(new Color(210, 170, 255));
		panel.add(header, BorderLayout.NORTH);

		JLabel cardImg = new JLabel("", SwingConstants.CENTER);
		cardImg.setPreferredSize(new Dimension(CardAnimation.CARD_W, CardAnimation.CARD_H));
		cardImg.addMouseListener(new MouseAdapter() {
			@Override public void mouseEntered(MouseEvent e) { showZoomAt(entry.source().imageUrl()); }
			@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
		});

		JLabel nameLabel = new JLabel(entry.source().name(), SwingConstants.CENTER);
		nameLabel.setFont(FontLoader.loadPixelFont(10));
		nameLabel.setForeground(Color.WHITE);
		nameLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

		JPanel textPanel = new JPanel();
		textPanel.setLayout(new BoxLayout(textPanel, BoxLayout.Y_AXIS));
		textPanel.setOpaque(false);
		textPanel.add(nameLabel);

		// Spell out what is about to resolve so the player knows what they are responding to.
		// Every entry kind, not only auto abilities: a Summon showed its name and its art and left
		// the player to remember the card. Read through resolvingEffectText so what is shown is the
		// branch that will actually run — an EX Burst alternative on a cast that is not one is not
		// what this resolution does.
		String stackEffectText = resolvingEffectText(entry);
		if (stackEffectText != null && !stackEffectText.isBlank()) {
			JLabel effectLabel = new JLabel("<html><div style='text-align:center;width:"
					+ UiScale.scale(230) + "px'>"
					+ escapeForHtmlLabel(stackEffectText) + "</div></html>",
					SwingConstants.CENTER);
			effectLabel.setFont(FontLoader.loadPixelFont(9));
			effectLabel.setForeground(new Color(205, 195, 220));
			effectLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
			effectLabel.setBorder(BorderFactory.createEmptyBorder(3, 0, 0, 0));
			textPanel.add(effectLabel);
		}

		JPanel imagePanel = new JPanel(new BorderLayout(3, 3));
		imagePanel.setOpaque(false);
		imagePanel.add(cardImg,   BorderLayout.CENTER);
		imagePanel.add(textPanel, BorderLayout.SOUTH);
		panel.add(imagePanel, BorderLayout.CENTER);

		int[] countdown = { 10 };
		JLabel countdownLabel = new JLabel("Resolving in 10...", SwingConstants.CENTER);
		countdownLabel.setFont(FontLoader.loadPixelFont(10));
		countdownLabel.setForeground(Color.LIGHT_GRAY);

		JButton okBtn      = new JButton("OK");
		JButton respondBtn = new JButton("Respond");
		okBtn.setFont(FontLoader.loadPixelFont(11));
		respondBtn.setFont(FontLoader.loadPixelFont(11));

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

		// 10-second countdown timer — but no time limit when P2 is a CPU
		if (isP2Cpu()) {
			countdownLabel.setText("Your response...");
		} else {
			stackCountdownTimer = new Timer(1000, null);
			stackCountdownTimer.addActionListener(e -> {
				if (stackWindowGeneration != myGeneration) { ((Timer) e.getSource()).stop(); return; }
				countdown[0]--;
				if (countdown[0] <= 0) {
					stackCountdownTimer.stop();
					resolveTopOfStack();
				} else {
					countdownLabel.setText("Resolving in " + countdown[0] + "...");
				}
			});
			stackCountdownTimer.start();
		}

		okBtn.addActionListener(e -> {
			if (stackWindowGeneration != myGeneration) return;
			if (stackCountdownTimer != null) stackCountdownTimer.stop();
			resolveTopOfStack();
		});

		respondBtn.addActionListener(e -> {
			if (stackWindowGeneration != myGeneration) return;
			if (stackCountdownTimer != null) stackCountdownTimer.stop();
			respondBtn.setEnabled(false);
			p1IsRespondingToStack = true;
			refreshHandCardStates();

			// No time limit on the response window when P2 is a CPU
			if (isP2Cpu()) {
				countdownLabel.setText("Responding...");
				return;
			}

			// 20-second response window
			int[] responseCountdown = { 20 };
			countdownLabel.setText("Response window: 20s...");
			Timer responseTimer = new Timer(1000, null);
			responseTimer.addActionListener(re -> {
				if (stackWindowGeneration != myGeneration) { ((Timer) re.getSource()).stop(); return; }
				responseCountdown[0]--;
				if (responseCountdown[0] <= 0) {
					((Timer) re.getSource()).stop();
					p1IsRespondingToStack = false;
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
		p1IsRespondingToStack = false;

		StackEntry entry = gameState.popStack();
		if (entry == null) return;

		if (cancelledStackEntries.remove(entry)) {
			String pfx = entry.isP1() ? "" : "[P2] ";
			logEntry(pfx + "\"" + entry.source().name() + "\" — effect cancelled");
			if (entry.isSummon()) {
				if (entry.isP1()) {
					addToBreakZone(entry.source());
					refreshP1BreakLabel();
				} else {
					addToBreakZone(entry.source());
					refreshP2BreakLabel();
				}
				logEntry("\"" + entry.source().name() + "\" → Break Zone");
			}
			if (!gameState.getStack().isEmpty()) showStackWindow();
			return;
		}

		isResolvingStack = true;
		// Re-establish the card whose event fired this entry, for the length of its resolution.
		// The trigger dispatcher sets the same field while it pushes and unwinds it immediately
		// after, so an effect naming that card back is reached long after the field has been
		// cleared; the entry carries it precisely so this can put it back.
		CardData previousTriggeringBrokenCard = triggeringBrokenCard;
		triggeringBrokenCard = entry.triggerCard();
		try {
			// The entry's own EX Burst flag reaches the effect through the context. The direct
			// damage-zone path (AutoAbilityTriggers.triggerExBurst) has always passed it; the two
			// routes that put an EX Burst on the Stack instead — a card added to hand whose Burst
			// is offered, and one picked out of the Damage Zone — did not, so "If [name] results
			// from an EX Burst, … instead." resolved as though it had not, and Ifrit 7-005C dealt
			// 7000 off its own Burst rather than 8000.
			GameContext ctx = buildGameContext(entry.isP1(), entry.isExBurstEntry());
			if (entry.isSummon()) {
				// Propagate extra cost context so GameContextImpl can expose it.
				currentSummonPaidExtraCost          = entry.paidExtraCost();
				currentExtraCostRemovedCardPower     = entry.extraCostRemovedCardPower();
				currentExtraCostDiscardedCardCost    = 0;

				String effectText = entry.effectText();
				// Strip extra cost clause from EX Burst stack entries (extra cost cannot be paid via EX Burst).
				if (entry.isExBurstEntry() && entry.source().extraCost() != null)
					effectText = ActionResolver.stripExtraCostClause(effectText);
				// Transform conditional text based on whether extra cost was paid.
				else if (entry.paidExtraCost()) {
					effectText = ActionResolver.applyExtraCostPaid(effectText);
					// Substitute 《X》 with the actual value paid (Valefor and similar).
					if (entry.xValue() > 0)
						effectText = effectText.replace("《X》", String.valueOf(entry.xValue()));
				} else {
					effectText = ActionResolver.stripExtraCostClause(effectText);
				}

				// What the line reports is the resolution, not the printing: the "EX BURST" marker
				// and the "If [name] results from an EX Burst, … instead." alternative both belong
				// on it only when the Summon really did resolve off one. Same text the stack window
				// showed a moment earlier, from the same helper, so the two cannot disagree.
				logEntry("[Summon] Resolving \"" + entry.source().name() + "\": "
						+ resolvingEffectText(entry));
				Consumer<GameContext> effect = ActionResolver.parse(effectText, entry.source(), entry.xValue());
				if (effect != null) {
					// Targets were chosen when the Summon went on the Stack, so the opponent could
					// respond to them; resolution uses that choice rather than asking again.
					if (entry.preSelectedTargets() != null) ctx.preloadTargets(entry.preSelectedTargets());
					currentResolutionIsSummon   = true;
					currentSummonSource     = entry.source();
					currentSummonSourceIsP1 = entry.isP1();
					pendingSummonReturnToHand   = false;
					// The line just printed carries the whole effect, so the choose machinery's own
					// header would restate it. Set here rather than off currentResolutionIsSummon,
					// which is also true on the summon paths that print no such line.
					summonEffectTextAlreadyLogged = true;
					try { effect.accept(ctx); } finally {
						currentResolutionIsSummon = false;
						currentSummonSource   = null;
						summonEffectTextAlreadyLogged = false;
					}
				} else logEntry("[ActionResolver] Summon effect not yet implemented: " + effectText);
				if (pendingSummonReturnToHand) {
					if (entry.isP1()) {
						gameState.getP1Hand().add(entry.source());
						refreshP1HandLabel();
					} else {
						gameState.getP2Hand().add(entry.source());
						refreshP2HandCountLabel();
					}
					logEntry("\"" + entry.source().name() + "\" → Hand");
					pendingSummonReturnToHand = false;
				} else if (returnToHandAfterUseSummons.remove(entry.source())) {
					if (entry.isP1()) {
						gameState.getP1Hand().add(entry.source());
						refreshP1HandLabel();
					} else {
						gameState.getP2Hand().add(entry.source());
						refreshP2HandCountLabel();
					}
					logEntry("\"" + entry.source().name() + "\" → Hand (after use)");
				} else if (rfgAfterUseSummons.remove(entry.source())) {
					// Borrowed Summon cast under "remove from the game after use" — never reaches the Break Zone.
					gameState.addToPermanentRfp(entry.source());
					if (entry.isP1()) {
						refreshP1WarpZoneUI();
					} else {
						refreshP2WarpZoneUI();
					}
					logEntry("\"" + entry.source().name() + "\" → Removed From Game (after use)");
				} else {
					addToBreakZone(entry.source());
					logEntry("\"" + entry.source().name() + "\" → Break Zone");
				}
			} else if (entry.isExBurstEntry()) {
				String exText = entry.effectText();
				// Reported through the same helper the Summon line uses, so the alternative this
				// resolution is actually going to take is the one named — here, the Burst half.
				logEntry("[EX Burst on Stack] Resolving \"" + entry.source().name() + "\": "
						+ resolvingEffectText(entry));
				Consumer<GameContext> effect = ActionResolver.parse(exText, entry.source());
				if (effect != null) {
					currentAbilitySource     = entry.source();
					currentAbilitySourceIsP1 = entry.isP1();
					currentAbilityIsSpecial  = false;
					try { effect.accept(ctx); } finally { currentAbilitySource = null; }
				} else {
					logEntry("[EX Burst on Stack] Effect not yet implemented: " + exText);
				}
				refreshP1HandLabel();
				refreshP1BreakLabel();
			} else if (entry.isAutoAbility()) {
				AutoAbility ab = entry.autoAbility();
				// Transform the "If you paid the extra cost, …" clause based on whether it actually
				// was paid at cast time — a no-op for abilities with no such clause. Without this,
				// the raw text's conditional gets matched loosely by unrelated patterns (e.g. a bare
				// "break it" follow-up) and the effect would fire unconditionally.
				boolean hadExtraCostClause = !ab.effectText().equals(ActionResolver.stripExtraCostClause(ab.effectText()));
				String effectText = entry.paidExtraCost()
						? ActionResolver.applyExtraCostPaid(ab.effectText())
						: ActionResolver.stripExtraCostClause(ab.effectText());
				// Resolved with the entry's own xValue, not with 0. Auto abilities have no X cost of
				// their own, so this was always 0 until the "is dealt damage" dispatcher began
				// carrying the size of the blow there — 4-083L Shantotto's "the same amount of
				// damage" is a number only the event knows, and dropping it here dealt nothing.
				Consumer<GameContext> effect = effectText.isBlank() ? null
						: ActionResolver.parse(effectText, entry.source(), entry.xValue());
				if (effect != null) {
					logEntry("[AutoAbility] Resolving \"" + entry.source().name() + "\": " + effectText);
					// As with Summons: an auto-ability chooses when it goes on the Stack.
					if (entry.preSelectedTargets() != null) ctx.preloadTargets(entry.preSelectedTargets());
					currentAbilitySource     = entry.source();
					currentAbilitySourceIsP1 = entry.isP1();
					currentAbilityIsSpecial  = false;
					try { effect.accept(ctx); } finally { currentAbilitySource = null; }
				} else if (hadExtraCostClause && !entry.paidExtraCost()) {
					// Extra cost wasn't paid, and the remaining unconditional lead-in (if any) has no
					// follow-up action of its own — e.g. "Choose 1 Forward of cost 6 or more." alone.
					// This is the expected "nothing happens" case, not a parsing failure.
					logEntry("[AutoAbility] \"" + entry.source().name() + "\" — extra cost not paid, no effect");
				} else {
					logEntry("[AutoAbility] Unrecognized effect: " + effectText);
				}
				refreshP1HandLabel();
				refreshP1BreakLabel();
			} else if (entry.isWarpResolve()) {
				// Warp card enters the field after its triggers have resolved.
				resolveWarpCard(entry.source(), entry.isP1());
				if (entry.isP1()) refreshP1BreakLabel(); else refreshP2BreakLabel();
			} else {
				currentAbilitySource     = entry.source();
				currentAbilitySourceIsP1 = entry.isP1();
				currentAbilityIsSpecial  = entry.isSpecialAbility();
				// Carried from activation, where the reveal cost was paid (Rinoa 18-097R).
				currentRevealedForwardPower = entry.revealedForwardPower();
				try {
					if (entry.preSelectedTargets() != null) ctx.preloadTargets(entry.preSelectedTargets());
					ActionResolver.resolve(entry.ability(), entry.source(), gameState, ctx, entry.xValue());
				} finally {
					currentAbilitySource    = null;
					currentAbilityIsSpecial = false;
					currentRevealedForwardPower = 0;
				}
				refreshP1HandLabel();
				refreshP1BreakLabel();
			}
		} finally {
			isResolvingStack = false;
			triggeringBrokenCard = previousTriggeringBrokenCard;
		}

		if (!gameState.getStack().isEmpty()) showStackWindow();
		else { lastDiscardedForwardPower = 0; lastDiscardedCardName = null; lastDiscardedCard = null; lastDiscardedCostCard = null; }
	}

	/** Calls {@link #showStackWindow()} only when we are not already inside a stack resolution chain. */
	void showStackWindowIfNeeded() {
		if (!isResolvingStack && !gameState.getStack().isEmpty()) showStackWindow();
	}

	/** How often {@link #runWhenBoardSettled} re-checks whether the board has settled. */
	private static final int BOARD_SETTLE_POLL_MS = 100;

	// -------------------------------------------------------------------------
	// Board-settled gating and Limit Break play
	// -------------------------------------------------------------------------

	/**
	 * True when nothing is left to resolve: no card is still arriving on the field, the stack is
	 * empty, no stack entry is mid-resolution, and {@link #turnFlowGate} reports no outstanding
	 * player choice or queued trigger.  The {@code isResolvingStack} check matters because
	 * {@link #resolveTopOfStack} pops its entry before running it, so an ability waiting on a modal
	 * dialog leaves the stack empty while the player has yet to choose.  The gate covers the two
	 * windows that check still misses: a blocking selection open outside a stack resolution, and a
	 * trigger queued behind an animation that has not reached the stack yet.
	 */
	boolean isBoardSettled() {
		return !fieldEntryAnimator.isBusy() && gameState.getStack().isEmpty() && !isResolvingStack
				&& turnFlowGate.isClear() && !anyModalDialogShowing() && !awaitingRemoteBlock;
	}

	/**
	 * True while any modal dialog is on screen.  Every blocking choice the effect resolver opens —
	 * the {@code CardPickerDialog} family, hand picks, deck looks, name selections, stack ordering,
	 * cost payment — is a modal {@code JDialog}, and showing one runs a nested event pump that keeps
	 * Swing timers firing.  Asking AWT covers all of them at once, including ones added later, which
	 * bracketing each of the ~29 call sites by hand would not.
	 *
	 * <p>Deliberately not limited to game dialogs: pausing turn flow because the player opened
	 * Preferences mid-game is harmless and self-correcting when they close it, whereas maintaining a
	 * whitelist would silently miss whatever is added next.  Complements {@link #turnFlowGate},
	 * which covers the one blocking choice that is <em>not</em> modal — the in-place field targeting
	 * in {@link #selectFieldTargetsInPlace}, whose bar is non-modal so the board stays clickable.
	 */
	private static boolean anyModalDialogShowing() {
		for (java.awt.Window w : java.awt.Window.getWindows())
			if (w instanceof java.awt.Dialog d && d.isModal() && d.isShowing()) return true;
		return false;
	}

	/**
	 * Runs {@code after} as soon as {@link #isBoardSettled()} holds — immediately when it already
	 * does, otherwise polling on the EDT.  Holds the end step open until abilities that triggered
	 * during it have been resolved (Kadaj returning at the end of the opponent's turn).
	 */
	void runWhenBoardSettled(Runnable after) {
		if (isBoardSettled()) { after.run(); return; }
		Timer poll = new Timer(BOARD_SETTLE_POLL_MS, null);
		poll.addActionListener(e -> {
			if (!isBoardSettled()) return;
			((Timer) e.getSource()).stop();
			after.run();
		});
		poll.start();
	}

	/**
	 * CP payment dialog for LB casting — mirrors showPaymentDialog but has no
	 * hand-card to exclude and calls executeLbPlay on confirm.
	 */
	private void showLbCpPaymentDialog(CardData card, int lbCastIdx, Set<Integer> pendingLbPayment) {
		new LbPaymentDialog(frame, card,
				gameState.getP1Hand(), cpPayableBackupCards(true), p1BackupStates, p1BackupUrls,
				this::showZoomAt, this::hideZoom,
				lightDarkDiscardGrants(true),
				(discards, backups, breaks) -> {
					spentLbIndices.add(lbCastIdx);
					spentLbIndices.addAll(pendingLbPayment);
					logEntry("Cast LB \"" + card.name() + "\"");
					executeLbPlay(card, discards, backups, breaks);
				}, breakForCpBackupSlots(true))
			.show();
	}


	/**
	 * Executes an LB cast: dulls selected backups, discards payment hand cards,
	 * spends CP, and places the card — without removing it from hand.
	 */
	private void executeLbPlay(CardData card, List<Integer> discardIndices,
			List<Integer> backupDullIndices) {
		executeLbPlay(card, discardIndices, backupDullIndices, Map.of());
	}

	/**
	 * @param backupBreaks Backups put into the Break Zone for CP as part of this payment, slot to
	 *                     the Element each produces (Sherlotta 8-053H)
	 */
	private void executeLbPlay(CardData card, List<Integer> discardIndices,
			List<Integer> backupDullIndices, Map<Integer, String> backupBreaks) {
		String[] elems = card.elements();
		boolean  isLD  = card.isLightOrDark();
		Map<String, Integer> lbCpAccum = new LinkedHashMap<>();
		for (int bi : backupDullIndices) {
			p1BackupStates[bi] = CardState.DULL;
			animateDullBackup(bi, true);
			String cpElem = isLD ? p1BackupCards[bi].elements()[0] : contributingElement(p1BackupCards[bi], elems);
			gameState.addP1Cp(cpElem, 1);
			lbCpAccum.merge(cpElem, 1, Integer::sum);
		}
		breakBackupsForCp(true, backupBreaks).forEach((e, n) -> lbCpAccum.merge(e, n, Integer::sum));
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
			CardData discarded = gameState.getP1Hand().get(di);
			String cpElem = isLD ? discarded.elements()[0] : contributingElement(discarded, elems);
			gameState.addP1Cp(cpElem, 2);
			lbCpAccum.merge(cpElem, 2, Integer::sum);
			playerBreakFromHand(true,di);
		}
		Set<String> lbCpToClear = new java.util.LinkedHashSet<>(Arrays.asList(elems));
		lbCpToClear.addAll(lbCpAccum.keySet());
		for (String e : lbCpToClear) {
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
	void placeCardInFirstBackupSlot(CardData card) {
		if (fieldEntryBecomesRfg(card, true)) return;
		// A card arriving on the field is a new object: it has taken no damage and dealt none.
		forgetDamageRecordFor(card);
		if (p1BackupLabels == null) return;
		for (int i = 0; i < p1BackupLabels.length; i++) {
			if (p1BackupLabels[i] == null || p1BackupLabels[i].getIcon() != null) continue;
			p1BackupUrls[i]          = card.imageUrl();
			p1BackupCards[i]         = card;
			p1BackupStates[i]        = CardState.DULL;
			p1BackupPlayedOnTurn[i]  = gameState.getTurnNumber();
			refreshP1BackupSlot(i);
			fieldEntryAnimator.fireEntersField(card, true, false);
			syncBzForwardPlayables(true);
			sendToBreakZoneByUniquenessRule(card, true);
			break;
		}
	}

	// -------------------------------------------------------------------------
	// Dull/activate animations, slot tooltips, Backup slot rendering
	// -------------------------------------------------------------------------

	/**
	 * Shared dull/activate rotation for a single card slot: rotates {@code slot}'s image between
	 * upright (0°) and dulled (90°) over 12 frames, then re-renders it via {@code refresh}.  All the
	 * per-zone {@code animateDull*}/{@code animateActivate*} methods are thin wrappers over this.
	 *
	 * @param dulling      {@code true} rotates upright→dulled (0°→90°); {@code false} the reverse
	 * @param stillPresent optional per-frame guard; when it returns {@code false} the animation
	 *                     aborts and the slot is cleared (used by backups, whose card can leave the
	 *                     field mid-animation).  Pass {@code null} for no guard.
	 * @param refresh      re-renders the slot at its final resting state
	 * @param onComplete   optional callback run after the final {@code refresh}; may be {@code null}
	 */
	private void animateCardRotation(String url, JLabel slot, boolean dulling,
			BooleanSupplier stillPresent, Runnable refresh, Runnable onComplete) {
		if (url == null || slot == null) {
			refresh.run();
			if (onComplete != null) onComplete.run();
			return;
		}
		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null : CardAnimation.toARGB(raw, CARD_W, CARD_H);
			}
			@Override protected void done() {
				BufferedImage card;
				try {
					card = get();
				} catch (InterruptedException | ExecutionException ex) {
					refresh.run();
					if (onComplete != null) onComplete.run();
					return;
				}
				if (card == null) {
					refresh.run();
					if (onComplete != null) onComplete.run();
					return;
				}
				int   totalFrames = 12;
				int[] frame       = { 0 };
				Timer timer = new Timer(16, null);
				timer.addActionListener(ae -> {
					if (stillPresent != null && !stillPresent.getAsBoolean()) {
						timer.stop();
						slot.setIcon(null);
						slot.setText(null);
						return;
					}
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
						refresh.run();
						if (onComplete != null) onComplete.run();
					}
				});
				timer.start();
			}
		}.execute();
	}

	void animateDullBackup(int idx, boolean dulling) {
		animateCardRotation(p1BackupUrls[idx], p1BackupLabels[idx], dulling,
				() -> p1BackupUrls[idx] != null, () -> refreshP1BackupSlot(idx), null);
	}


	void animateDullForward(int idx, Runnable onComplete) {
		animateCardRotation(p1ForwardUrls.get(idx), p1ForwardLabels.get(idx), true, null,
				() -> { refreshP1ForwardSlot(idx); triggerShieldFadeForForward(true, idx); }, onComplete);
	}

	void animateDullP2Forward(int idx, Runnable onComplete) {
		animateCardRotation(p2ForwardUrls.get(idx), p2ForwardLabels.get(idx), true, null,
				() -> { refreshP2ForwardSlot(idx); triggerShieldFadeForForward(false, idx); }, onComplete);
	}

	void animateActivateForward(int idx) {
		animateCardRotation(p1ForwardUrls.get(idx), p1ForwardLabels.get(idx), false, null,
				() -> refreshP1ForwardSlot(idx), null);
	}

	void animateActivateMonster(int idx) {
		animateCardRotation(p1MonsterUrls.get(idx), p1MonsterLabels.get(idx), false, null,
				() -> refreshP1MonsterSlot(idx), null);
	}

	private void animateDullMonster(int idx) {
		animateCardRotation(p1MonsterUrls.get(idx), p1MonsterLabels.get(idx), true, null,
				() -> refreshP1MonsterSlot(idx), null);
	}

	void animateActivateP2Forward(int idx) {
		animateCardRotation(p2ForwardUrls.get(idx), p2ForwardLabels.get(idx), false, null,
				() -> refreshP2ForwardSlot(idx), null);
	}

	void animateActivateP2Monster(int idx) {
		animateCardRotation(p2MonsterUrls.get(idx), p2MonsterLabels.get(idx), false, null,
				() -> refreshP2MonsterSlot(idx), null);
	}

	private static String buildCounterTooltip(Map<String, Integer> countersMap) {
		if (countersMap.isEmpty()) return null;
		StringBuilder sb = new StringBuilder("<html>");
		countersMap.forEach((name, count) -> sb.append(name).append(" ×").append(count).append("<br>"));
		sb.append("</html>");
		return sb.toString();
	}

	// Per-slot state the trait-tab tooltip needs at hover time. Held as client properties rather
	// than fields because the slots live in four parallel label lists, and the values are replaced
	// on every re-render.
	private static final String SLOT_TIP_STATE   = "shufflingway.slotTipState";    // CardState
	private static final String SLOT_TIP_TRAITS  = "shufflingway.slotTipTraits";   // List<CardData.Trait>
	private static final String SLOT_TIP_BASE    = "shufflingway.slotTipBase";     // counter tooltip, or null
	private static final String SLOT_TIP_PRIMED  = "shufflingway.slotTipPrimed";   // Boolean
	private static final String SLOT_TIP_WIRED   = "shufflingway.slotTipWired";    // listener installed?

	/**
	 * Points {@code slot}'s tooltip at whatever the pointer is over: a trait tab's meaning while
	 * the pointer is on one, and the counter list otherwise. Call from a slot's render callback in
	 * place of a bare {@code setToolTipText}.
	 *
	 * <p>The pointer-tracking listener is installed once per label and must go on <em>before</em>
	 * the {@code setToolTipText} below. {@code setToolTipText} re-registers the label with
	 * {@link ToolTipManager}, which appends its own listeners; installing ours first is what keeps
	 * it ahead of the manager's in the dispatch order, so a tooltip that is already showing sees
	 * the text for the tab the pointer just moved onto rather than the one it left.
	 */
	void applyFieldSlotTooltip(JLabel slot, CardState state,
			List<CardData.Trait> traitTabs, boolean primed, Map<String, Integer> countersMap) {
		String base = buildCounterTooltip(countersMap);
		slot.putClientProperty(SLOT_TIP_STATE,  state);
		slot.putClientProperty(SLOT_TIP_TRAITS, List.copyOf(traitTabs));
		slot.putClientProperty(SLOT_TIP_BASE,   base);
		slot.putClientProperty(SLOT_TIP_PRIMED, primed);

		if (!Boolean.TRUE.equals(slot.getClientProperty(SLOT_TIP_WIRED))) {
			slot.putClientProperty(SLOT_TIP_WIRED, Boolean.TRUE);
			MouseAdapter tracker = new MouseAdapter() {
				@Override public void mouseMoved(MouseEvent e) {
					slot.setToolTipText(fieldSlotTooltipAt(slot, e.getX(), e.getY()));
				}
				@Override public void mouseExited(MouseEvent e) {
					slot.setToolTipText((String) slot.getClientProperty(SLOT_TIP_BASE));
				}
			};
			slot.addMouseMotionListener(tracker);
			slot.addMouseListener(tracker);
		}
		// Slots re-render constantly. Resolving against the pointer's current position keeps a
		// tooltip the player is already reading from snapping back to the counter text mid-hover;
		// getMousePosition is null whenever the pointer is elsewhere or the slot is not showing.
		Point hover = slot.getMousePosition();
		slot.setToolTipText(hover != null ? fieldSlotTooltipAt(slot, hover.x, hover.y) : base);
	}

	/**
	 * The tooltip for {@code slot} at label point {@code (x, y)} — a trait tab's description when
	 * the pointer is over one, otherwise the slot's counter tooltip (possibly {@code null}).
	 *
	 * <p>The point is shifted into the tab renderer's canvas space first: the icon is a square
	 * canvas centred in the label, so hit-testing against raw label coordinates would be off by
	 * the centring offset whenever the layout gives the slot more room than the card needs.
	 */
	String fieldSlotTooltipAt(JLabel slot, int x, int y) {
		String base = (String) slot.getClientProperty(SLOT_TIP_BASE);
		Icon icon = slot.getIcon();
		@SuppressWarnings("unchecked")
		List<CardData.Trait> traits = (List<CardData.Trait>) slot.getClientProperty(SLOT_TIP_TRAITS);
		CardState state = (CardState) slot.getClientProperty(SLOT_TIP_STATE);
		if (icon == null || traits == null || traits.isEmpty() || state == null) return base;

		int cx = x - (slot.getWidth()  - icon.getIconWidth())  / 2;
		int cy = y - (slot.getHeight() - icon.getIconHeight()) / 2;
		CardData.Trait hit = TraitTab.traitAt(state, traits, cx, cy);
		if (hit == null) return base;
		boolean primed = Boolean.TRUE.equals(slot.getClientProperty(SLOT_TIP_PRIMED));
		return "<html><b>" + TraitTab.displayName(hit, primed) + "</b><br>"
				+ TraitTab.description(hit, primed) + "</html>";
	}

	/** Reloads and re-renders a single P1 backup slot using its stored URL and state. */
	void refreshP1BackupSlot(int idx) {
		String url  = p1BackupUrls[idx];
		CardState state = p1BackupStates[idx];
		JLabel slot  = p1BackupLabels[idx];
		if (slot == null) return;
		refreshPlayerDamageShieldIcon(true);
		if (url == null) { slot.setIcon(null); slot.setText(null); slot.setToolTipText(null); return; }
		if (fieldEntryAnimator.holdSlotBlank(slot, p1BackupCards[idx])) return;
		CardData card = p1BackupCards[idx];
		boolean actingForward = isP1BackupTemporarilyForward(idx);
		boolean canAttack = attackSubStep == 1 && isBackupSelectableAsForward(idx);
		boolean canBlock  = isBackupBlockSelectable(idx);
		boolean selected  = p1BackupAttackIdx == idx || p1BlockerBackupIdx == idx;
		int fwdPower = actingForward ? p1BackupForwardPower(idx) : 0;
		int damage   = card != null ? p1BackupForwardDamage.getOrDefault(card, 0) : 0;
		Map<String, Integer> countersMap = card != null ? gameState.getCountersMap(card) : Map.of();
		int totalCounters = countersMap.values().stream().mapToInt(c -> c == null ? 0 : c.intValue()).sum();
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.renderBackupCard(
						CardAnimation.toARGB(raw, CARD_W, CARD_H), state, canAttack || canBlock, selected, p1BackupFrozen[idx]);
				if (damage > 0) CardAnimation.renderDamageOverlay(canvas, damage, state);
				if (actingForward && fwdPower > 0)
					CardAnimation.renderPowerOverlayRight(canvas, fwdPower, new Color(80, 220, 80), state);
				if (!countersMap.isEmpty())
					CardAnimation.renderCounterOverlay(canvas, totalCounters, state, AppSettings.getCounterColor());
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null && p1BackupUrls[idx] != null) { slot.setIcon(icon); slot.setText(null); }
					slot.setToolTipText(buildCounterTooltip(countersMap));
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
	String buildAbilityMenuLabel(ActionAbility ability) {
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
		if (ability.revealCost() != null) {
			if (!firstCost) cost.append(", ");
			cost.append("reveal ").append(ability.revealCost().count()).append(' ')
					.append(ability.revealCost().cardType() != null
							? ability.revealCost().cardType() : "card");
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
		if (ability.minCounterRequired() > 0 && ability.minCounterType() != null) { if (!firstRestrict) restrict.append(", "); restrict.append("≥").append(ability.minCounterRequired()).append(" ").append(ability.minCounterType()).append(" Ctr"); firstRestrict = false; }
		if (ability.maxCounterAllowed() >= 0 && ability.maxCounterType() != null) { if (!firstRestrict) restrict.append(", "); restrict.append("no ").append(ability.maxCounterType()).append(" Ctr"); firstRestrict = false; }
		if (ability.requiresSelfPowerAtLeast() > 0) { if (!firstRestrict) restrict.append(", "); restrict.append("pow≥").append(ability.requiresSelfPowerAtLeast()); firstRestrict = false; }
		if (ability.yourTurnOnly())                 { if (!firstRestrict) restrict.append(", "); restrict.append("your turn");     firstRestrict = false; }
		if (ability.opponentTurnOnly())             { if (!firstRestrict) restrict.append(", "); restrict.append("opp turn");      firstRestrict = false; }
		if (ability.oncePerTurn())                  { if (!firstRestrict) restrict.append(", "); restrict.append("1/turn");        firstRestrict = false; }
		if (ability.mainPhaseOnly())                { if (!firstRestrict) restrict.append(", "); restrict.append("main phase");    firstRestrict = false; }
		if (ability.whilePartyAttacking())          { if (!firstRestrict) restrict.append(", "); restrict.append("while party atk"); firstRestrict = false; }
		else if (ability.whileCardAttacking() != null) { if (!firstRestrict) restrict.append(", "); restrict.append("while ").append(ability.whileCardAttacking()).append(" atk"); firstRestrict = false; }
		if (ability.whileCardBlocking() != null)          { if (!firstRestrict) restrict.append(", "); restrict.append("while ").append(ability.whileCardBlocking()).append(" blk"); firstRestrict = false; }
		if (ability.requiresOppDiscardedThisTurn())       { if (!firstRestrict) restrict.append(", "); restrict.append("opp discarded");    firstRestrict = false; }
		if (ability.requiresCastSummonThisTurn())         { if (!firstRestrict) restrict.append(", "); restrict.append("cast summon");       firstRestrict = false; }
		if (ability.requiresOpponentEmptyHand())          { if (!firstRestrict) restrict.append(", "); restrict.append("opp empty hand");    firstRestrict = false; }
		if (ability.maxOpponentHandSize() >= 0)           { if (!firstRestrict) restrict.append(", "); restrict.append("opp hand ≤").append(ability.maxOpponentHandSize()); firstRestrict = false; }
		if (ability.requiresSelfEmptyHand())              { if (!firstRestrict) restrict.append(", "); restrict.append("self empty hand");   firstRestrict = false; }
		if (ability.requiresElementForwardEnteredThisTurn() != null) { if (!firstRestrict) restrict.append(", "); restrict.append(ability.requiresElementForwardEnteredThisTurn()).append(" fwd entered"); firstRestrict = false; }
		if (ability.requiresNamedCardTookDamageThisTurn() != null)  { if (!firstRestrict) restrict.append(", "); restrict.append(ability.requiresNamedCardTookDamageThisTurn()).append(" took dmg");  firstRestrict = false; }
		if (ability.requiresSelfReceivedDamageThisTurn())           { if (!firstRestrict) restrict.append(", "); restrict.append("self rcvd dmg"); firstRestrict = false; }
		if (ability.requiresForwardPutToBZThisTurn())               { if (!firstRestrict) restrict.append(", "); restrict.append("own fwd to BZ"); firstRestrict = false; }
		if (ability.requiresJobPutToBZThisTurn() != null)           { if (!firstRestrict) restrict.append(", "); restrict.append("own Job ").append(ability.requiresJobPutToBZThisTurn()).append(" to BZ"); firstRestrict = false; }
		if (ability.requiresJobPutToBZThisTurn() != null)           { if (!firstRestrict) restrict.append(", "); restrict.append("own Job ").append(ability.requiresJobPutToBZThisTurn()).append(" to BZ"); firstRestrict = false; }
		if (ability.blockerForAttacker() != null)                   { if (!firstRestrict) restrict.append(", "); restrict.append("blks ").append(ability.blockerForAttacker()); firstRestrict = false; }
		if (ability.requiresOwnWarpCard())                         { if (!firstRestrict) restrict.append(", "); restrict.append("needs Warp card");  firstRestrict = false; }
		if (restrict.length() > 0) sb.append(restrict).append(" — ");

		String fx = ability.effectText();
		sb.append(fx.length() > 55 ? fx.substring(0, 52) + "..." : fx);
		return sb.toString();
	}

	/** HTML version of {@link #buildAbilityMenuLabel}: wraps the [AbilityName] in orange. */
	String buildAbilityMenuLabelHtml(ActionAbility ability) {
		String plain = buildAbilityMenuLabel(ability);
		if (ability.isSpecial() && !ability.abilityName().isEmpty()) {
			String prefix = "[" + ability.abilityName() + "] ";
			if (plain.startsWith(prefix))
				return "<html><font color='#ED930D'>[" + ability.abilityName() + "]</font> "
						+ plain.substring(prefix.length());
		}
		return "<html>" + plain;
	}

	/** @see CostCalculator#canPayOptionalCost */
	boolean canPayOptionalCost(boolean isP1, int cp, String element, int crystals) { return costs.canPayOptionalCost(isP1, cp, element, crystals); }

	/** @see CostCalculator#canAffordAbilityCost */
	boolean canAffordAbilityCost(ActionAbility ability, boolean isP1) { return costs.canAffordAbilityCost(ability, isP1); }

	/** @see CostCalculator#canAffordCpTokens */
	boolean canAffordCpTokens(List<String> cost, int total, boolean isP1) { return costs.canAffordCpTokens(cost, total, isP1); }

	/** Counts field cards (forwards + backups) with the given job, excluding any named {@code excludeName}. */
	int computeInlineReduction(String job, String excludeName, boolean isP1) {
		int count = 0;
		for (CardData fwd : playerForwardCards(isP1))
			if (fwd.hasJob(job) && (excludeName == null || !fwd.name().equalsIgnoreCase(excludeName))) count++;
		for (CardData bkp : playerBackupCards(isP1))
			if (bkp != null && bkp.hasJob(job) && (excludeName == null || !bkp.name().equalsIgnoreCase(excludeName))) count++;
		return count;
	}

	/**
	 * Returns {@code true} if the given player has at least one card named {@code name}
	 * (or {@code extraName} when non-null) in hand — needed for Special Ability payment.
	 * Pass {@code null} for {@code extraName} when no alternate name applies.
	 */
	private boolean hasSameNameInHand(String name, String extraName, boolean isP1) {
		for (CardData c : playerHand(isP1)) {
			if (name.equalsIgnoreCase(c.name())) return true;
			if (extraName != null && extraName.equalsIgnoreCase(c.name())) return true;
		}
		return false;
	}

	/**
	 * Whether the given player's Backups are currently barred from producing CP by an opposing
	 * "During your turn, the Backups opponent controls cannot produce CP." — Titan (XVI) 29-068L.
	 *
	 * <p>Read off the <em>opposing</em> field, like the Haste-suppression sentences: whoever
	 * controls the printing taxes the other player. "During your turn" is the printer's turn, so
	 * the suppression is live only while its controller holds the turn — which is exactly when the
	 * suppressed player would be paying for something at instant speed.
	 */
	boolean backupCpSuppressed(boolean isP1) {
		boolean suppressorIsP1 = !isP1;
		boolean suppressorHasTurn = suppressorIsP1
				== (gameState.getCurrentPlayer() == GameState.Player.P1);
		if (!suppressorHasTurn) return false;
		for (CardData c : fieldCards(suppressorIsP1)) {
			if (c == null || lostAbilitiesCards.contains(c)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(c))
				if (AutoAbilityTriggers.FA_OPP_BACKUPS_CANNOT_PRODUCE_CP
						.matcher(fa.effectText().trim()).matches()) return true;
		}
		return false;
	}

	/**
	 * {@link #playerBackupCards} masked for CP payment: the same array, or a copy with every slot
	 * nulled while {@link #backupCpSuppressed} holds for that player.
	 *
	 * <p>Handed to the payment dialogs in place of the raw row. All five of them already skip null
	 * slots when deciding which Backups may be clicked for CP, so one masked array suppresses the
	 * whole set without five separate gates that could drift apart. A copy, never the live row —
	 * the suppression is about paying, and the Backups are still on the field for everything else.
	 */
	CardData[] cpPayableBackupCards(boolean isP1) {
		CardData[] row = playerBackupCards(isP1);
		if (!backupCpSuppressed(isP1)) return row;
		return new CardData[row.length];
	}

	/**
	 * The counter payment that lets {@code source} use its Special ability for nothing, or
	 * {@code null} when it prints none or does not currently hold enough counters — Wakka 16-138S,
	 * "You can remove 3 Reel Counters from Wakka to use Wakka's special ability without paying the
	 * cost."
	 *
	 * <p>Re-asked on every query rather than settled once, for the reason
	 * {@link #effectiveSpecialAbilityProxy} is: the counters move during the turn, and the menu
	 * gate, the payment prompt and the payment itself must not disagree about whether the waiver is
	 * open. Read through {@link #effectiveFieldAbilities} and gated on {@code lostAbilitiesCards},
	 * so a granted copy pays the same way a printed one does and a card with no abilities pays
	 * neither.
	 *
	 * <p>{@code isP1} is unused by the count itself — the counters sit on the card — but the
	 * parameter is kept so every reader of a per-side payment reads the same way.
	 */
	CardData.SpecialCostCounterWaiver specialCostCounterWaiver(CardData source, boolean isP1) {
		if (source == null || lostAbilitiesCards.contains(source)) return null;
		for (FieldAbility fa : effectiveFieldAbilities(source)) {
			CardData.SpecialCostCounterWaiver w =
					CardData.parseSpecialCostCounterWaiver(fa.effectText(), source.name());
			if (w == null) continue;
			if (gameState.getCounters(source, w.counterName()) >= w.count()) return w;
		}
		return null;
	}

	/**
	 * {@code source}'s S-cost substitution as it stands on the current board, or {@code null} when
	 * it prints none or prints one whose board condition is not met.
	 *
	 * <p>Tifa 26-076H's is live only while its controller has a Card Name Zangan on the field, and
	 * that has to be re-asked every time rather than resolved once: the Zangan can leave between
	 * the menu opening and the payment being made. Every reader of a proxy goes through here — the
	 * menu gate in {@link #canActivateAbility}, the payment dialog, the S-cost candidate list and
	 * its chooser title — so none of them can offer a payment another would refuse.
	 *
	 * <p>Every row is searched, not just the Forwards: the name is a name, and a Zangan printed as
	 * a Backup (1-188S) satisfies "you control a Card Name Zangan" exactly as the Forward does.
	 */
	CardData.SpecialAbilityProxy effectiveSpecialAbilityProxy(CardData source, boolean isP1) {
		if (source == null) return null;
		CardData.SpecialAbilityProxy proxy = source.specialAbilityProxy();
		if (proxy == null || proxy.requiresControlCardName() == null) return proxy;
		for (CardData c : fieldCards(isP1))
			if (c != null && CardFilters.meetsCardNameFilter(c, proxy.requiresControlCardName()))
				return proxy;
		return null;
	}

	/**
	 * Whether {@code source}'s 《S》 cost may be paid with a Crystal rather than a discard — Glaciela
	 * Wezette 17-113L hands that to every Category FFBE Character its controller controls, herself
	 * included.
	 *
	 * <p>Only the permission is answered here, not the means: whether a Crystal is actually in hand
	 * to spend is the caller's question, because the two are asked at different moments (the menu
	 * gate wants both, the payment wants the permission again at commit time). Re-read per call for
	 * the reason {@link #effectiveSpecialAbilityProxy} is — the granting card can leave the field
	 * between the menu opening and the payment being made.
	 *
	 * <p>Every row is searched and every card's field abilities are read through
	 * {@link #effectiveFieldAbilities}, so a granted copy of the permission counts as a printed one
	 * does.
	 */
	boolean crystalMayPaySpecialCost(CardData source, boolean isP1) {
		if (source == null) return false;
		for (CardData c : fieldCards(isP1)) {
			if (c == null || lostAbilitiesCards.contains(c)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(c)) {
				String category = CardData.parseCrystalPaysSpecialCostCategory(fa.effectText());
				if (category != null && CardFilters.meetsCategoryFilter(source, category)) return true;
			}
		}
		return false;
	}

	/** Whether {@code source}'s 《S》 cost can be paid with a Crystal right now — permission and a Crystal to spend. */
	boolean canPaySpecialCostWithCrystal(CardData source, boolean isP1) {
		return playerCrystals(isP1) >= 1 && crystalMayPaySpecialCost(source, isP1);
	}


	// ---- Per-player data selectors used by the ability payment chain -----------

	// -------------------------------------------------------------------------
	// Seat-agnostic accessors
	// -------------------------------------------------------------------------

	List<CardData> playerHand(boolean isP1)       { return isP1 ? gameState.getP1Hand()       : gameState.getP2Hand(); }
	CardData[]     playerBackupCards(boolean isP1) { return isP1 ? p1BackupCards               : p2BackupCards; }
	CardState[]    playerBackupStates(boolean isP1){ return isP1 ? p1BackupStates              : p2BackupStates; }
	String[]       playerBackupUrls(boolean isP1)  { return isP1 ? p1BackupUrls                : p2BackupUrls; }
	List<CardData> playerForwardCards(boolean isP1){ return isP1 ? p1ForwardCards              : p2ForwardCards; }
	List<CardData> playerMonsterCards(boolean isP1){ return isP1 ? p1MonsterCards              : p2MonsterCards; }
	int  playerCrystals(boolean isP1)              { return isP1 ? gameState.getP1Crystals()   : gameState.getP2Crystals(); }
	int  playerCpForElem(boolean isP1, String e)   { return isP1 ? gameState.getP1CpForElement(e) : gameState.getP2CpForElement(e); }
	Map<String, Integer> playerCpByElem(boolean isP1) { return isP1 ? gameState.getP1CpByElement() : gameState.getP2CpByElement(); }
	void playerAddCp(boolean isP1, String e, int n)    { if (isP1) gameState.addP1Cp(e, n);   else gameState.addP2Cp(e, n); }
	void playerSpendCp(boolean isP1, String e, int n)  { if (isP1) gameState.spendP1Cp(e, n); else gameState.spendP2Cp(e, n); }
	void playerClearCp(boolean isP1, String e)         { if (isP1) gameState.clearP1Cp(e);    else gameState.clearP2Cp(e); }
	void playerSpendCrystals(boolean isP1, int n)      { if (isP1) gameState.spendP1Crystals(n); else gameState.spendP2Crystals(n); }
	CardData playerBreakFromHand(boolean isP1, int i)  {
		CardData d = isP1 ? gameState.breakFromHand(i) : gameState.breakP2FromHand(i);
		if (d != null) {
			animateCardDiscard(isP1, d);
			// "due to your Summons or abilities" — an effect is mid-resolution and the hand that
			// lost the card belongs to the other player. A discard paid as a cost or taken at the
			// end-phase hand limit has no ability resolving, so it correctly fires nothing.
			if (currentAbilitySource != null && currentAbilitySourceIsP1 != isP1)
				autoAbilityTriggers.triggerAutoAbilitiesForDiscardByEffect(d, currentAbilitySourceIsP1);
		}
		return d;
	}

	/**
	 * Announces that {@code handOwnerIsP1} moved one or more cards from a Break Zone into their
	 * hand, so their opponent's watchers (25-111H The Emperor) can react. Call once per effect
	 * rather than per card: the trigger reads "1 or more cards".
	 */
	void notifyCardsAddedToHandFromBreakZone(boolean handOwnerIsP1) {
		autoAbilityTriggers.triggerAutoAbilitiesForBreakZoneToHand(handOwnerIsP1);
	}

	// -------------------------------------------------------------------------
	// Breaking P2 Backup and Monster slots
	// -------------------------------------------------------------------------

	void playerDullBackupSlot(boolean isP1, int idx) {
		if (isP1) animateDullBackup(idx, true); else animateDullP2Backup(idx, true);
	}

	void animateDullP2Backup(int idx, boolean dulling) {
		animateCardRotation(p2BackupUrls[idx], p2BackupLabels[idx], dulling,
				() -> p2BackupUrls[idx] != null, () -> refreshP2BackupSlot(idx), null);
	}

	void animateDullP2Monster(int idx) {
		animateCardRotation(p2MonsterUrls.get(idx), p2MonsterLabels.get(idx), true, null,
				() -> refreshP2MonsterSlot(idx), null);
	}

	void breakP2BackupSlot(int idx) {
		CardData c = p2BackupCards[idx];
		if (c == null) return;
		startBreakAnim(p2BackupLabels[idx]);
		logEntry("[P2] " + c.name() + " → Break Zone");
		addToBreakZone(c, true);
		p2BackupTempForwardPower.remove(c); p2BackupForwardBoost.remove(c);
		p2BackupTempTraits.remove(c);       p2BackupForwardDamage.remove(c);
		if (p2BackupAttackIdx == idx) p2BackupAttackIdx = -1;
		p2BackupCards[idx]  = null;
		p2BackupUrls[idx]   = null;
		p2BackupStates[idx] = CardState.ACTIVE;
		p2BackupFrozen[idx] = false;
		if (p2BackupLabels[idx] != null) {
			p2BackupLabels[idx].setIcon(null);
			p2BackupLabels[idx].setText(null);
		}
		refreshP2BreakLabel();
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
		autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(c, false, Collections.emptySet());
	}

	void breakP2MonsterSlot(int idx) {
		if (idx >= p2MonsterCards.size()) return;
		startBreakAnim(p2MonsterLabels.get(idx));
		CardData c = p2MonsterCards.get(idx);
		logEntry("[P2] " + c.name() + " → Break Zone");
		addToBreakZone(c, true);
		p2MonsterTempForwardPower.remove(c);
		p2MonsterPowerBoost.remove(c);
		p2MonsterTempTraits.remove(c);
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
		autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(c, false, Collections.emptySet());
	}

	// -------------------------------------------------------------------------
	// Damage redirection
	// -------------------------------------------------------------------------

	/**
	 * The cards currently attacking on {@code isP1}'s side.
	 *
	 * <p>Either side's list is held for the whole combat — declaration, the priority checkpoints on
	 * both sides of block declaration, and damage — and emptied once that combat resolves. While P1
	 * is still picking attackers (sub-step 1) the P1 list falls back to the in-progress selection,
	 * so "while [card] is attacking" abilities can be lined up before pressing Attack as they
	 * always could.
	 */
	private List<CardData> declaredAttackers(boolean isP1) {
		if (!isP1) return p2DeclaredAttackers;
		if (!p1DeclaredAttackers.isEmpty()) return p1DeclaredAttackers;
		return p1AttackSelection.stream()
				.filter(i -> i < p1ForwardCards.size())
				.map(this::effectiveP1Forward)
				.collect(Collectors.toList());
	}

	/**
	 * The combat glow {@code card} shows on {@code isP1}'s field, or {@code null} for none.
	 *
	 * <p>Red while it is part of a declared attack. The point of it is the blocking decision, so it
	 * has to be up for the whole window in which the defender is choosing — which is exactly as
	 * long as {@link #declaredAttackers} holds the card, declaration through damage.
	 *
	 * <p>Gray once it has attacked as often as it may this turn. That is narrower than "cannot
	 * attack": a Forward played this turn or held down by an effect has not spent anything and is
	 * not marked, because the mark is about a threat that is now used up rather than one that was
	 * never available. Dulling already says as much for most attackers, but not for the ones that
	 * matter here — Brave keeps a card active, and a re-activated attacker looks untouched.
	 *
	 * <p>The two are ranked, not combined: an attacker mid-combat has usually spent its last
	 * declaration already, and while it is swinging that is not what the player needs to see.
	 *
	 * <p>Both lists are compared by identity. {@link CardData} is a record, so two copies of the
	 * same printing are equal — matching by value would light up the twin standing next to the
	 * attacker.
	 */
	Color combatGlowFor(CardData card, boolean isP1) {
		if (card == null) return null;
		for (CardData attacker : declaredAttackers(isP1))
			if (attacker == card) return CardAnimation.GLOW_ATTACKING;
		// Only while attacks are still being declared. attacksMadeThisTurn runs to end of turn, but
		// once the phase is over nobody was going to attack again anyway, so the mark stops saying
		// anything and is just clutter across both main phases.
		if (gameState.getCurrentPhase() == GameState.GamePhase.ATTACK
				&& attacksMadeThisTurn.getOrDefault(card, 0) > 0 && !hasAttackRemaining(card))
			return CardAnimation.GLOW_EXHAUSTED;
		return null;
	}

	/**
	 * Repaints every field row on both sides, for the two glows that change with combat rather than
	 * with a slot's own contents.
	 *
	 * <p>Called wherever a declared-attacker list is written or cleared. A declaration changes what
	 * the <em>other</em> player's board should be showing, and no other refresh path crosses sides:
	 * {@link #refreshAllForwardSlots} is P1's rows only.
	 */
	void refreshCombatGlows() {
		for (int i = 0; i < p1ForwardLabels.size(); i++) refreshP1ForwardSlot(i);
		for (int i = 0; i < p2ForwardLabels.size(); i++) refreshP2ForwardSlot(i);
		for (int i = 0; i < p1MonsterLabels.size(); i++) refreshP1MonsterSlot(i);
		for (int i = 0; i < p2MonsterLabels.size(); i++) refreshP2MonsterSlot(i);
	}

	/** The card acting at P1 Forward slot {@code idx} — the primed top card when one is stacked. */
	CardData effectiveP1Forward(int idx) {
		CardData top = p1ForwardPrimedTop.get(idx);
		return top != null ? top : p1ForwardCards.get(idx);
	}

	/** The card acting at P2 Forward slot {@code idx} — the primed top card when one is stacked. */
	CardData effectiveP2Forward(int idx) {
		CardData top = p2ForwardPrimedTop.get(idx);
		return top != null ? top : p2ForwardCards.get(idx);
	}

	/**
	 * The Stack entries {@code spec} may redirect, as a predicate — shared by the activation gate
	 * in {@link #canActivateAbility} and by the resolution in {@code GameContextImpl}, so the two
	 * can never disagree about what qualifies.
	 *
	 * <p>Both shapes require the entry to be choosing exactly one card ("choosing <em>only</em>
	 * …"): an entry with no stored selection has not chosen anything to redirect, and one with
	 * several is not choosing "only" anything. EX Bursts are excluded for the same reason the
	 * cancel family excludes them — they carry no stored selection to rewrite.
	 *
	 * @param userIsP1 the player activating the redirect, which is whose field "you control" means
	 */
	Predicate<StackEntry> redirectEligibility(TargetRedirect spec, CardData source, boolean userIsP1) {
		return entry -> {
			if (entry.isExBurstEntry() || entry.isWarpResolve()) return false;
			switch (spec.entryKind()) {
				case SUMMON  -> { if (!entry.isSummon()) return false; }
				case ABILITY -> { if (entry.isSummon())  return false; }
				case ANY     -> { }
			}
			List<ForwardTarget> chosen = entry.preSelectedTargets();
			if (chosen == null || chosen.size() != 1) return false;
			ForwardTarget only = chosen.get(0);
			CardData card = fieldCardDataOrNull(only);
			return switch (spec.eligibility()) {
				case SOURCE_ITSELF -> card == source;
				case OWN_FORWARD_OF_ELEMENT -> card != null
						&& only.isP1() == userIsP1
						&& only.zone() == ForwardTarget.CardZone.FORWARD
						&& effectiveContainsElement(card, spec.eligibleElement());
				// "either player controls" — on a field, so a Break Zone selection does not qualify.
				case ON_FIELD -> card != null && only.zone() != ForwardTarget.CardZone.BREAK_ZONE;
				case ANY_ZONE -> true;
			};
		};
	}

	/**
	 * The Forwards {@code userIsP1} controls that could legally become {@code entry}'s new target.
	 *
	 * <p>{@code element} narrows to one Element; {@code exclude} drops a card the text puts out of
	 * reach ("another …", i.e. not the source). When the entry being redirected belongs to the
	 * opponent, "cannot be chosen by your opponent's Summons/abilities" protection applies and
	 * those Forwards are dropped too — redirecting onto a permanent the effect could not have
	 * chosen in the first place is exactly what "must be a valid choice" rules out.
	 */
	List<ForwardTarget> redirectCandidates(StackEntry entry, boolean userIsP1,
			String element, CardData exclude) {
		List<ForwardTarget> out = new ArrayList<>();
		List<CardData> fwds = userIsP1 ? p1ForwardCards : p2ForwardCards;
		Predicate<CardData> legal = redirectLegality(entry, userIsP1, element, exclude);
		for (int i = 0; i < fwds.size(); i++) {
			ForwardTarget t = new ForwardTarget(userIsP1, i, ForwardTarget.CardZone.FORWARD);
			if (legal.test(fwds.get(i)) && targetMeetsEntrySpec(entry, t)) out.add(t);
		}
		return narrowToCompelledTargets(entry, out);
	}

	/**
	 * Every Character on either field that could legally become {@code entry}'s new target — the
	 * pool for the two effects that let you point the entry anywhere ("You may choose another
	 * Character/target") rather than at a Forward of a named Element.
	 *
	 * <p>Both sides are offered because neither Aemo nor Wicked Mask restricts the replacement to
	 * your own field. {@code exclude} drops the card the entry already chose — "<em>another</em>".
	 */
	List<ForwardTarget> redirectCandidatesAnywhere(StackEntry entry, CardData exclude) {
		List<ForwardTarget> out = new ArrayList<>();
		for (boolean sideIsP1 : new boolean[]{true, false}) {
			Predicate<CardData> legal = redirectLegality(entry, sideIsP1, null, exclude);
			List<CardData> fwds = sideIsP1 ? p1ForwardCards : p2ForwardCards;
			for (int i = 0; i < fwds.size(); i++)
				addRedirectCandidate(out, entry, legal, fwds.get(i), sideIsP1, i, ForwardTarget.CardZone.FORWARD);
			CardData[] bkps = sideIsP1 ? p1BackupCards : p2BackupCards;
			for (int i = 0; i < bkps.length; i++)
				addRedirectCandidate(out, entry, legal, bkps[i], sideIsP1, i, ForwardTarget.CardZone.BACKUP);
			List<CardData> mons = sideIsP1 ? p1MonsterCards : p2MonsterCards;
			for (int i = 0; i < mons.size(); i++)
				addRedirectCandidate(out, entry, legal, mons.get(i), sideIsP1, i, ForwardTarget.CardZone.MONSTER);
		}
		return narrowToCompelledTargets(entry, out);
	}

	/**
	 * Narrows a redirect's candidate pool to the cards a "must choose X if possible" taunt compels
	 * {@code entry} to point at, or returns it unchanged when no taunt applies.
	 *
	 * <p>The redirect counterpart of the narrowing {@code GameContextImpl.selectCharacters} does
	 * when a target is first chosen. Both are needed for the same reason immunity is checked on both
	 * paths: "The newly chosen target must be a valid choice" is printed on every effect that offers
	 * a free pick (Aemo 11-109R, Wicked Mask 20-038H, Faris 21-114L), and a compulsion consulted
	 * only at first choice is one anyone holding those can sidestep.
	 *
	 * <p>The compulsion binds the <em>entry's</em> controller, not whoever is working the redirect —
	 * it is still that Summon or ability making the choice. So Faris's controller using her {@code 《0》}
	 * to move an opponent's ability can be forced to drop it on their own taunting Forward.
	 *
	 * <p>Two things make this simpler than the first-choice narrowing. A redirect replaces a
	 * single-target entry with exactly one new target, so there is no surplus pick to leave free and
	 * no count guard is needed. And every caller excludes the entry's current target ("another …"),
	 * so a taunt card already being chosen is not in the pool to begin with — this can only ever
	 * pull a redirect <em>onto</em> a taunt, never forbid one that moves off it.
	 */
	/** Adds one slot to a redirect pool when it clears both halves of "must be a valid choice". */
	private void addRedirectCandidate(List<ForwardTarget> out, StackEntry entry,
			Predicate<CardData> legal, CardData card, boolean sideIsP1, int idx,
			ForwardTarget.CardZone zone) {
		if (!legal.test(card)) return;
		ForwardTarget t = new ForwardTarget(sideIsP1, idx, zone);
		if (targetMeetsEntrySpec(entry, t)) out.add(t);
	}

	private List<ForwardTarget> narrowToCompelledTargets(StackEntry entry, List<ForwardTarget> candidates) {
		List<ForwardTarget> compelled = candidates.stream()
				.filter(t -> t.isP1() != entry.isP1())
				.filter(t -> mustBeChosenByOpponent(fieldCardDataOrNull(t), entry.isSummon()))
				.toList();
		return compelled.isEmpty() ? candidates : compelled;
	}

	/**
	 * {@code t}'s card, or {@code null} when that slot no longer holds one.
	 *
	 * <p>{@link AutoAbilityTriggers#fieldCardData} indexes the zone lists directly and throws once
	 * a slot is gone. A Stack entry outlives the board it chose against — the card it picked can
	 * be broken, bounced or stolen before the entry resolves — so anything reading a stored target
	 * has to tolerate the index being stale.
	 */
	CardData fieldCardDataOrNull(ForwardTarget t) {
		if (t == null) return null;
		return switch (t.zone()) {
			case FORWARD -> {
				List<CardData> fwds = t.isP1() ? p1ForwardCards : p2ForwardCards;
				yield t.idx() >= 0 && t.idx() < fwds.size() ? fwds.get(t.idx()) : null;
			}
			case BACKUP -> {
				CardData[] bkps = t.isP1() ? p1BackupCards : p2BackupCards;
				yield t.idx() >= 0 && t.idx() < bkps.length ? bkps[t.idx()] : null;
			}
			case MONSTER -> {
				List<CardData> mons = t.isP1() ? p1MonsterCards : p2MonsterCards;
				yield t.idx() >= 0 && t.idx() < mons.size() ? mons.get(t.idx()) : null;
			}
			case BREAK_ZONE -> null;
		};
	}

	/** {@code source}'s own slot when {@code entry} could legally choose it, else {@code null}. */
	ForwardTarget redirectSourceSlot(StackEntry entry, CardData source, boolean sourceIsP1) {
		Predicate<CardData> legal = redirectLegality(entry, sourceIsP1, null, null);
		if (!legal.test(source)) return null;
		List<CardData> fwds = sourceIsP1 ? p1ForwardCards : p2ForwardCards;
		for (int i = 0; i < fwds.size(); i++)
			if (fwds.get(i) == source) return new ForwardTarget(sourceIsP1, i, ForwardTarget.CardZone.FORWARD);
		CardData[] bkps = sourceIsP1 ? p1BackupCards : p2BackupCards;
		for (int i = 0; i < bkps.length; i++)
			if (bkps[i] == source) return new ForwardTarget(sourceIsP1, i, ForwardTarget.CardZone.BACKUP);
		List<CardData> mons = sourceIsP1 ? p1MonsterCards : p2MonsterCards;
		for (int i = 0; i < mons.size(); i++)
			if (mons.get(i) == source) return new ForwardTarget(sourceIsP1, i, ForwardTarget.CardZone.MONSTER);
		return null;
	}

	/**
	 * Whether a card on {@code sideIsP1}'s field is something {@code entry} could legally choose.
	 *
	 * <p>"Cannot be chosen" protection is judged from the redirected entry's point of view, not
	 * the redirecting player's: what matters is whether <em>that</em> Summon or ability could have
	 * picked the card unaided. Pointing it at a permanent it could never have chosen is exactly
	 * what "the newly chosen target must be a valid choice" rules out.
	 */
	private Predicate<CardData> redirectLegality(StackEntry entry, boolean sideIsP1,
			String element, CardData exclude) {
		return c -> c != null && c != exclude
				&& (element == null || effectiveContainsElement(c, element))
				&& !isProtectedFromChoice(c, sideIsP1, entry.isP1(), entry.isSummon(), entry.source());
	}

	// -------------------------------------------------------------------------
	// Targeting legality and protection from being chosen
	// -------------------------------------------------------------------------

	/**
	 * Whether {@code t} would have been a legal choice for {@code entry}'s own effect — the second
	 * half of "The newly chosen target must be a valid choice", alongside the immunity check in
	 * {@link #redirectLegality}. Replays the constraints the effect's text imposes, so a Summon that
	 * chose "1 Forward of cost 3 or less" cannot be redirected onto a cost 7 Forward, or onto a
	 * Backup, or onto the side of the field its text never offered.
	 *
	 * <p>Returns {@code true} when the effect's text yields no {@link TargetSpec}, or one naming a
	 * Break Zone — an effect whose targeting this cannot decode, or whose targets are not field
	 * slots at all, imposes no constraint here rather than a wrongly empty one, which keeps the
	 * redirect no stricter than it was before the spec existed.
	 *
	 * <p>Mirrors the per-target checks {@code GameContextImpl.selectCharacters} runs, zone for zone.
	 * The two are the same rule read at two moments, so a divergence here shows up as a redirect
	 * landing somewhere the effect could not have chosen in the first place.
	 */
	private boolean targetMeetsEntrySpec(StackEntry entry, ForwardTarget t) {
		TargetSpec spec = ActionResolver.targetSpec(entry.effectText(), entry.source());
		// A choice naming a Break Zone constrains cards there, not the field slots a redirect moves
		// between, so it says nothing about this target either way.
		if (spec == null || spec.zone() != null) return true;
		boolean sideIsP1 = t.isP1();
		// "opponent controls" / "you control" are relative to whoever controls the effect.
		if (spec.opponentOnly() && sideIsP1 == entry.isP1()) return false;
		if (spec.selfOnly()     && sideIsP1 != entry.isP1()) return false;

		CardData card = fieldCardDataOrNull(t);
		if (card == null) return false;
		String condition = spec.condition();
		int i = t.idx();

		if (spec.element() != null && !effectiveContainsElement(card, spec.element())) return false;
		if (!CardFilters.meetsCostConstraint(card.cost(), spec.costVal(), spec.costCmp())) return false;
		if (!CardFilters.meetsPowerConstraint(card.power(), spec.powerVal(), spec.powerCmp())) return false;
		if (!meetsJobFilterEffective(card, spec.jobFilter(),
				sideIsP1 ? p1ForwardCards : p2ForwardCards)) return false;
		if (!CardFilters.meetsCardNameFilter(card, spec.cardNameFilter())) return false;
		if (!CardFilters.meetsCategoryFilter(card, spec.categoryFilter())) return false;
		if (spec.excludeName() != null && spec.excludeName().equalsIgnoreCase(card.name())) return false;
		if (spec.withoutMulticard() && card.multicard()) return false;

		switch (t.zone()) {
			case FORWARD -> {
				if (!spec.inclForwards() && !card.alsoCountsAsMonster()) return false;
				if (!CardFilters.meetsElementExclusion(card, spec.excludeElement())) return false;
				if (CardFilters.isTraitCondition(condition)
						&& !effectiveHasTrait(sideIsP1, i, CardFilters.parseTraitFromCondition(condition))) return false;
				List<CardState>  states = sideIsP1 ? p1ForwardStates : p2ForwardStates;
				List<Integer>    dmg    = sideIsP1 ? p1ForwardDamage : p2ForwardDamage;
				List<Integer>    played = sideIsP1 ? p1ForwardPlayedOnTurn : p2ForwardPlayedOnTurn;
				return CardFilters.isBlockingTargetFilter(condition)
						? meetsBlockingTargetFilter(sideIsP1, i, condition)
						: CardFilters.isEnteredThisTurnCondition(condition)
						? played.get(i) == gameState.getTurnNumber()
						: CardFilters.meetsTargetCondition(states.get(i), dmg.get(i),
								sideIsP1 && p1AttackSelection.contains(i), false, condition);
			}
			case BACKUP -> {
				if (CardFilters.isBlockingTargetFilter(condition)) return false;
				boolean asFwd = sideIsP1 ? isP1BackupTemporarilyForward(i) : isP2BackupTemporarilyForward(i);
				if (!spec.inclBackups() && !asFwd) return false;
				CardState[] states = sideIsP1 ? p1BackupStates : p2BackupStates;
				return CardFilters.meetsTargetCondition(states[i], 0, false, false, condition);
			}
			case MONSTER -> {
				boolean asFwd = sideIsP1 ? isP1MonsterTemporarilyForward(i) : isP2MonsterTemporarilyForward(i);
				if (!spec.inclMonsters() && !asFwd) return false;
				if (!CardFilters.meetsElementExclusion(card, spec.excludeElement())) return false;
				List<CardState> states = sideIsP1 ? p1MonsterStates : p2MonsterStates;
				List<Integer>   played = sideIsP1 ? p1MonsterPlayedOnTurn : p2MonsterPlayedOnTurn;
				return CardFilters.isEnteredThisTurnCondition(condition)
						? played.get(i) == gameState.getTurnNumber()
						: CardFilters.meetsTargetCondition(states.get(i), 0, false, false, condition);
			}
			default -> {
				return false; // a redirect never lands in the Break Zone
			}
		}
	}

	/**
	 * Whether {@code c}, sitting on {@code sideIsP1}'s field, is shielded from being chosen by a
	 * Summon ({@code bySummon = true}) or an ability controlled by {@code chooserIsP1}.
	 *
	 * <p>Applies the same two-scope rule as target selection: a grant that names no player
	 * ("cannot be chosen by Summons") blocks whoever is choosing, including the card's own
	 * controller, while one printed "by your opponent's …" blocks only the card's opponent.
	 * {@code chooserSource} is the resolving card, needed by the element-matched immunities;
	 * pass {@code null} when it is unknown.
	 *
	 * <p>This is the per-card form of the sets {@code GameContextImpl.selectCharacters} builds
	 * once per selection. The two must stay in agreement — an immunity honoured when a target is
	 * first chosen but not when an effect is redirected is a card that stops being protected the
	 * moment someone points a redirect at it.
	 */
	boolean isProtectedFromChoice(CardData c, boolean sideIsP1, boolean chooserIsP1,
			boolean bySummon, CardData chooserSource) {
		if (c == null) return false;
		List<String> chooserElems = chooserSource != null ? effectiveElements(chooserSource) : List.of();

		// Unqualified grants: no player named, so both are bound.
		if (bySummon && cannotBeChosenBySummonsAnyone.contains(c)) return true;
		if (!bySummon && cannotBeChosenByAbilitiesAnyone.contains(c)) return true;
		if (bySummon && ActionResolver.hasCannotBeChosenByAnySummonFieldAbility(c)) return true;
		String immuneElem = cannotBeChosenByElement.get(c);
		if (immuneElem != null && chooserElems.contains(immuneElem)) return true;
		// The printed twin of the turn-scoped shield above (Royal Ripeness 5-007H): a literal
		// Element rather than one named on resolution, and permanent rather than this turn's.
		String printedImmuneElem = ActionResolver.cannotBeChosenByElementFieldAbility(c);
		if (printedImmuneElem != null && chooserElems.contains(printedImmuneElem)) return true;
		if (ActionResolver.hasCannotBeChosenByOwnElementFieldAbility(c)) {
			String ce = effectiveElement(c);
			if (ce != null && chooserElems.contains(ce)) return true;
		}
		if (ActionResolver.hasCannotBeChosenByMultiElementForwardAbility(c)
				&& isMultiElementForwardAbilitySource(chooserSource, bySummon)) return true;
		if (icbGrantsImmunity(c.name(), sideIsP1, bySummon, false, chooserSource)) return true;

		// Opponent-scoped grants: the controller may still choose their own card.
		if (chooserIsP1 == sideIsP1) return false;
		// The printed, standing form (Terra 1-046H, Seiryu 16-049R, …). Read here rather than
		// recorded in the sets below because those hold what some effect granted; this one is a
		// property of the card's own text and lasts as long as it is on the field.
		if (ActionResolver.hasCannotBeChosenByOppFieldAbility(c, bySummon)) return true;
		if ((bySummon ? cannotBeChosenBySummons : cannotBeChosenByAbilities).contains(c)) return true;
		if ((bySummon ? permanentCannotBeChosenBySummons : permanentCannotBeChosenByAbilities).contains(c)) return true;
		return icbGrantsImmunity(c.name(), sideIsP1, bySummon, true, chooserSource);
	}

	/**
	 * Whether {@code resolvingCard}'s effect is "a Multi-Element Forward's ability" — the source
	 * Kam'lanaut 18-072C is immune to.
	 *
	 * <p>Both halves of the phrase are load-bearing. A Summon is not a Forward, so it is never
	 * caught here however many Elements it carries, and a single-Element Forward's ability is
	 * ordinary. Elements are read through {@link #effectiveElements}, so a card whose Element an
	 * effect has replaced is judged on what it is now.
	 */
	boolean isMultiElementForwardAbilitySource(CardData resolvingCard, boolean bySummon) {
		return !bySummon && resolvingCard != null && resolvingCard.isForward()
				&& effectiveElements(resolvingCard).size() > 1;
	}

	/**
	 * Whether {@code entry} is an ability its controller's field protects from being cancelled —
	 * Yoran-Oran 29-075H, who shields the abilities of their Job Mage.
	 *
	 * <p>Summons and EX Bursts are never protected by this: the sentence names the three kinds of
	 * <em>ability</em>, and an entry carrying neither an action nor an auto ability is neither.
	 *
	 * <p>Read off the entry's own controller's field, since "your Job Mage" is the shielding
	 * card's controller's. The source is judged on the Job it has now rather than the one it was
	 * printed with, and is not required to still be on the field — an ability outlives the card
	 * that used it, and the protection has to outlive it too.
	 */
	boolean stackEntryProtectedFromCancel(StackEntry entry) {
		if (entry == null || entry.source() == null) return false;
		if (entry.ability() == null && entry.autoAbility() == null) return false;
		for (CardData c : fieldCards(entry.isP1())) {
			if (lostAbilitiesCards.contains(c)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(c)) {
				Matcher m = AutoAbilityTriggers.FA_JOB_ABILITIES_CANNOT_BE_CANCELLED
						.matcher(fa.effectText().trim());
				if (m.matches() && meetsJobFilterEffective(entry.source(), m.group("job").trim()))
					return true;
			}
		}
		return false;
	}

	/**
	 * Cancels {@code entry} when it is the first auto ability an opponent's Forward has put on the
	 * Stack this turn and somebody across the table prints Bahamut (XVI) 29-115L's reply.
	 *
	 * <p>Read as the entry goes on rather than as it resolves: the sentence counts what is
	 * <em>put on</em> the Stack, so an entry that never resolves — cancelled by something else,
	 * or fizzled — has still spent the reply for the turn. The record is per carrier and cleared at
	 * every turn boundary, since "during each turn" covers both players' turns.
	 *
	 * <p>The cancel goes through {@link #cancelStackEntry}, so a protected ability (Yoran-Oran
	 * 29-075H) turns it away and the reply is not spent on it — the marker is only set once the
	 * cancellation actually took.
	 *
	 * <p>Only Forwards, and only the opponent's: an auto ability from a Backup or a Monster is
	 * left alone, as is one from a Forward on the canceller's own side.
	 */
	void cancelFirstOppForwardAuto(StackEntry entry) {
		if (entry == null || !entry.isAutoAbility() || entry.source() == null) return;
		boolean sourceIsP1 = entry.isP1();
		// "your opponent's Forward" is judged where the ability came from, which is the side that
		// controls the entry — a Forward that has since left the field triggered from one all the same.
		if (!entry.source().isForward() && !entry.source().alsoCountsAsMonster()) return;
		boolean cancellerIsP1 = !sourceIsP1;
		for (CardData c : fieldCards(cancellerIsP1)) {
			if (c == null || lostAbilitiesCards.contains(c)) continue;
			if (turn(cancellerIsP1).firstOppForwardAutoCancelledThisTurn.contains(c)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(c)) {
				if (!AutoAbilityTriggers.FA_CANCEL_FIRST_OPP_FORWARD_AUTO
						.matcher(fa.effectText().trim()).matches()) continue;
				if (!cancelStackEntry(entry)) return;
				turn(cancellerIsP1).firstOppForwardAutoCancelledThisTurn.add(c);
				logEntry((cancellerIsP1 ? "" : "[P2] ") + c.name() + " — cancelled "
						+ entry.source().name() + "'s auto ability (first this turn)");
				return;
			}
		}
	}

	/**
	 * Marks {@code entry} to be cancelled when it resolves, unless its controller's field
	 * protects it. Returns whether the cancellation took.
	 *
	 * <p>The single point every cancelling effect goes through, so a protection has one place to
	 * be honoured. The effects that let a player <em>choose</em> what to cancel also filter their
	 * candidate list with {@link #stackEntryProtectedFromCancel} beforehand, so a protected entry
	 * is never offered as a choice that would then do nothing.
	 */
	boolean cancelStackEntry(StackEntry entry) {
		if (stackEntryProtectedFromCancel(entry)) {
			logEntry(entry.source().name() + " — its ability cannot be cancelled");
			return false;
		}
		cancelledStackEntries.add(entry);
		return true;
	}

	/**
	 * Whether the card at {@code targetIsP1}'s field is shielded from leaving it by a Summon or
	 * ability {@code actingIsP1} controls — "[Name] cannot leave the field due to your opponent's
	 * Summons or abilities." (Chaos B-001 and its three siblings).
	 *
	 * <p>Opponent-scoped, so the controller's own effects still move the card, and it says nothing
	 * about combat damage or a cost its controller chooses to pay: both are causes other than an
	 * opponent's Summon or ability.
	 */
	boolean isProtectedFromLeavingField(CardData card, boolean targetIsP1, boolean actingIsP1) {
		return card != null
				&& targetIsP1 != actingIsP1
				&& !lostAbilitiesCards.contains(card)
				&& card.hasTrait(CardData.Trait.CANNOT_LEAVE_FIELD_BY_OPP);
	}

	/**
	 * Points {@code entry} at {@code newTargets}, keeping its place in the resolution order.
	 *
	 * <p>{@link StackEntry} is a record, so the entry is replaced by a copy — which means any
	 * identity-keyed state referring to the old instance has to move with it. A pending
	 * cancellation is the one such case: cancel and redirect can both be applied to the same
	 * entry before it resolves, and losing the cancellation here would quietly un-cancel it.
	 */
	void redirectStackEntryTargets(StackEntry entry, List<ForwardTarget> newTargets) {
		StackEntry updated = entry.withPreSelectedTargets(newTargets);
		if (!gameState.replaceStackEntry(entry, updated)) return;
		if (cancelledStackEntries.remove(entry)) cancelledStackEntries.add(updated);
	}

	// -------------------------------------------------------------------------
	// Ability activation legality
	// -------------------------------------------------------------------------

	/**
	 * Returns {@code true} if {@code ability} can currently be activated by the
	 * card at the given slot.
	 *
	 * @param state       current card state (ACTIVE / DULL)
	 * @param playedTurn  turn the card entered the field (0 = unknown)
	 * @param sourceName  card name, needed for special-ability hand check
	 */
	boolean canActivateAbility(ActionAbility ability, boolean isFrozen, CardState state,
			int playedTurn, CardData source, boolean isP1) {
		if (ability.breakZoneOnly() != null) return false; // only activatable from the Break Zone
		// Sin 14-045H shuts the opposing player's Forwards out of action abilities on that
		// player's own turn. Special abilities are a separate kind of ability under rule 6-1-1
		// and are left alone; so is anything the source is using from off the Forward row.
		if (!ability.isSpecial() && forwardActionAbilitiesLockedFor(isP1) && isFieldForward(source, isP1))
			return false;
		// The Emperor 2-147L shuts the opposing player's Characters out of both kinds of used
		// ability, on every turn. Scoped to the field rather than to the Forward row, since it names
		// Characters: a Backup's or Monster's ability is caught too. An ability used from hand or
		// from the Break Zone is not — the text binds Characters the opponent controls.
		if (characterAbilitiesLockedFor(isP1) && Boolean.valueOf(isP1).equals(fieldSideOf(source)))
			return false;
		// Kitone 14-064R shuts one chosen Character out of action abilities for the turn. Keyed by
		// card, so it follows the Character across rows; specials are exempt, as under Sin's lock.
		if (!ability.isSpecial() && cannotUseActionAbilitiesThisTurn.contains(source)) return false;
		if (ability.ownBreakZoneNameRequired() != null) {
			List<CardData> bz = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
			if (bz.stream().noneMatch(c -> c.name().equalsIgnoreCase(ability.ownBreakZoneNameRequired())))
				return false;
		}
		if (ability.yourTurnOnly()) {
			GameState.Player activePlayer = isP1 ? GameState.Player.P1 : GameState.Player.P2;
			if (gameState.getCurrentPlayer() != activePlayer) return false;
		}
		if (ability.opponentTurnOnly()) {
			GameState.Player activePlayer = isP1 ? GameState.Player.P1 : GameState.Player.P2;
			if (gameState.getCurrentPlayer() == activePlayer) return false;
		}
		if (ability.oncePerTurn()
				&& usedOncePerTurnAbilities.getOrDefault(source, Set.of()).contains(ability.effectText()))
			return false;
		// Effects that remove the top card(s) of your deck are illegal with too few cards to remove.
		int topDeckNeeded = ActionResolver.topDeckRemovalCount(ability.effectText());
		if (topDeckNeeded > 0
				&& (isP1 ? gameState.getP1MainDeck() : gameState.getP2MainDeck()).size() < topDeckNeeded)
			return false;
		// A cancel has nothing to do without an eligible entry on the stack, and activating it
		// anyway pays the cost — for most of this family, the source card itself — for no effect.
		Predicate<StackEntry> cancelFilter = ActionResolver.stackCancelFilter(ability.effectText(), isP1);
		if (cancelFilter != null && gameState.getStack().stream().noneMatch(cancelFilter)) return false;
		// Same reasoning for a redirect: with nothing on the stack choosing what the text names,
		// activating only pays the cost.
		TargetRedirect redirect = ActionResolver.targetRedirect(ability.effectText(), source);
		if (redirect != null
				&& gameState.getStack().stream().noneMatch(redirectEligibility(redirect, source, isP1)))
			return false;
		if (ability.mainPhaseOnly()) {
			GameState.Player activePlayer = isP1 ? GameState.Player.P1 : GameState.Player.P2;
			if (gameState.getCurrentPlayer() != activePlayer) return false;
			GameState.GamePhase p = gameState.getCurrentPhase();
			if (p != GameState.GamePhase.MAIN_1 && p != GameState.GamePhase.MAIN_2) return false;
		}
		// Attack-phase restrictions — all require the game to be in the ATTACK phase
		if (ability.whileCardAttacking() != null || ability.whileCardBlocking() != null || ability.whilePartyAttacking()) {
			if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return false;
		}
		if (ability.whileCardAttacking() != null) {
			boolean found = declaredAttackers(isP1).stream()
					.anyMatch(c -> c.name().equalsIgnoreCase(ability.whileCardAttacking()));
			if (!found) return false;
		}
		if (ability.whileCardBlocking() != null) {
			if (p1BlockingIdx < 0 || p1BlockingIdx >= p1ForwardCards.size()) return false;
			if (!p1ForwardCards.get(p1BlockingIdx).name().equalsIgnoreCase(ability.whileCardBlocking())) return false;
		}
		if (ability.whilePartyAttacking() && declaredAttackers(isP1).size() < 2) return false;
		if (ability.hasBlockingTargetEffect()) {
			if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return false;
			if (attackSubStep != 3) return false;
			boolean anyBlocking = (p1BlockingIdx >= 0 && p1BlockingIdx < p1ForwardCards.size())
					|| (p2BlockingIdx >= 0 && p2BlockingIdx < p2ForwardCards.size());
			if (!anyBlocking) return false;
		}
		if (ability.blockerForAttacker() != null) {
			if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return false;
			if (attackSubStep != 3) return false;
			String name = ability.blockerForAttacker();
			if (isP1) {
				if (p2BlockingIdx < 0 || p2BlockedByAttacker == null
						|| !p2BlockedByAttacker.name().equalsIgnoreCase(name)) return false;
			} else {
				if (p1BlockingIdx < 0 || p1BlockedByAttacker == null
						|| !p1BlockedByAttacker.name().equalsIgnoreCase(name)) return false;
			}
		}
		if (ability.requiresDull()) {
			if (state != CardState.ACTIVE) return false;
			// Summoning sickness. Haste lifts it for dull costs as well as for attacking, and the
			// check used to ignore Haste entirely — a Forward with printed Haste could not use its
			// own 《Dull》 ability on the turn it arrived. Cherukiki 19-109H and Zangan 26-070H hand
			// out this half of Haste on its own, and are read by the same query.
			if (playedTurn > 0 && gameState.getTurnNumber() - playedTurn < 2
					&& !canPayDullCostWhileSummoningSick(source, ability, isP1)) return false;
		}
		// Wakka 16-138S's Reel Counters buy the whole cost, so the question below is not "can this
		// be paid" but "is there anything left to pay" — settled once here and read twice.
		boolean costWaived = ability.isSpecial() && specialCostCounterWaiver(source, isP1) != null;
		if (ability.isSpecial()) {
			String primerName = priming.getPrimerCardName(source, isP1);
			if (!hasSameNameInHand(source.name(), primerName, isP1)) {
				CardData.SpecialAbilityProxy proxy = effectiveSpecialAbilityProxy(source, isP1);
				boolean handCanPay = proxy != null && playerHand(isP1).stream().anyMatch(proxy::meetsSubstitute);
				// A Crystal pays the 《S》 outright under Glaciela Wezette 17-113L, and Wakka
				// 16-138S's Reel Counters waive the whole cost — so an empty hand is no longer the
				// end of the question either way.
				if (!handCanPay && !canPaySpecialCostWithCrystal(source, isP1) && !costWaived) return false;
			}
		}
		if (ability.damageThreshold() > 0) {
			int dmg = isP1 ? gameState.getP1DamageZone().size() : gameState.getP2DamageZone().size();
			if (dmg < ability.damageThreshold()) return false;
		}
		if (ability.minCounterRequired() > 0 && ability.minCounterType() != null) {
			if (gameState.getCounters(source, ability.minCounterType()) < ability.minCounterRequired()) return false;
		}
		if (ability.requiresSelfPowerAtLeast() > 0) {
			// Current power, not printed: Hyoh 16-097H's 3-Lightning ability is meant to be unlocked
			// by his own 1-Lightning one, which sets his base power to 7000 from a printed 3000.
			ForwardTarget slot = findFieldSlot(source, isP1);
			if (slot == null) return false;
			if (fieldForwardPower(isP1, slot.zone(), slot.idx()) < ability.requiresSelfPowerAtLeast()) return false;
		}
		if (ability.maxCounterAllowed() >= 0 && ability.maxCounterType() != null) {
			if (gameState.getCounters(source, ability.maxCounterType()) > ability.maxCounterAllowed()) return false;
		}
		if (ability.requiresOppDiscardedThisTurn()) {
			boolean caused = turn(isP1).causedOpponentDiscardThisTurn;
			if (!caused) return false;
		}
		if (ability.requiresCastSummonThisTurn()) {
			if (!(turn(isP1).summonCastThisTurn)) return false;
		}
		if (ability.requiresOpponentEmptyHand()) {
			List<CardData> oppHand = isP1 ? gameState.getP2Hand() : gameState.getP1Hand();
			if (!oppHand.isEmpty()) return false;
		}
		if (ability.maxOpponentHandSize() >= 0) {
			List<CardData> oppHand = isP1 ? gameState.getP2Hand() : gameState.getP1Hand();
			if (oppHand.size() > ability.maxOpponentHandSize()) return false;
		}
		if (ability.requiresOwnWarpCard()) {
			List<GameState.WarpEntry> zone = isP1 ? gameState.getP1WarpZone() : gameState.getP2WarpZone();
			if (zone.isEmpty()) return false;
		}
		if (ability.revealCost() != null) {
			// Revealing costs nothing, but a cost that cannot be paid still bars the activation.
			int matching = 0;
			for (CardData c : playerHand(isP1)) if (ability.revealCost().matches(c)) matching++;
			if (revealAndSpecialCostCompeteForOneCard(ability, source, isP1)) matching--;
			if (matching < ability.revealCost().count()) return false;
		}
		if (ability.requiresSelfEmptyHand()) {
			List<CardData> selfHand = isP1 ? gameState.getP1Hand() : gameState.getP2Hand();
			if (!selfHand.isEmpty()) return false;
		}
		if (ability.requiresNamedCardTookDamageThisTurn() != null) {
			Set<String> damaged = turn(isP1).cardsTookDamageThisTurn;
			if (!damaged.contains(ability.requiresNamedCardTookDamageThisTurn())) return false;
		}
		if (ability.requiresSelfReceivedDamageThisTurn()) {
			if (!(turn(isP1).receivedDamageThisTurn)) return false;
		}
		if (ability.requiresForwardPutToBZThisTurn()) {
			if (!(turn(isP1).forwardPutToBZThisTurn)) return false;
		}
		if (ability.requiresJobPutToBZThisTurn() != null) {
			Set<String> brokenJobs = turn(isP1).brokenJobsThisTurn;
			if (!brokenJobs.contains(ability.requiresJobPutToBZThisTurn())) return false;
		}
		if (ability.requiresElementForwardEnteredThisTurn() != null) {
			Set<String> entered = turn(isP1).elementForwardsEnteredThisTurn;
			if (!entered.contains(ability.requiresElementForwardEnteredThisTurn())) return false;
		}
		if (ability.requiresSourceIsForward()) {
			boolean inMonsterZone = isP1 ? p1MonsterCards.contains(source) : p2MonsterCards.contains(source);
			if (inMonsterZone) {
				Map<CardData, Integer> tempFwdMap = isP1 ? p1MonsterTempForwardPower : p2MonsterTempForwardPower;
				if (!tempFwdMap.containsKey(source)) return false;
			}
		}
		if (ability.controlCondition() != null && !controlConditionMet(ability.controlCondition(), isP1)) return false;
		// Everything below prices a cost, and a waived ability has none — "without paying the cost"
		// covers the CP as squarely as the 《S》. The restrictions above are not costs and still bind:
		// a waived ability is no more legal to use than it was, only cheaper.
		if (costWaived) return true;
		if (ability.crystalCost() > 0 && playerCrystals(isP1) < ability.crystalCost()) return false;
		for (BreakZoneCost bz : ability.breakZoneCosts())
			if (!autoAbilityTriggers.bzCostSatisfied(bz, isP1)) return false;
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			if (!autoAbilityTriggers.rfgCostSatisfied(rfg, isP1)) return false;
		for (ReturnToHandCost rth : ability.returnToHandCosts())
			if (!autoAbilityTriggers.rfthCostSatisfied(rth, isP1)) return false;
		for (CounterCost cc : ability.counterCosts())
			if (!autoAbilityTriggers.counterCostSatisfied(cc, source)) return false;
		for (DullForwardCost dfc : ability.dullForwardCosts())
			if (!autoAbilityTriggers.dullForwardCostSatisfied(dfc, isP1)) return false;
		for (DiscardCost dc : ability.discardCosts())
			if (!autoAbilityTriggers.discardCostSatisfied(dc, isP1)) return false;
		return canAffordAbilityCost(ability, isP1);
	}

	// -------------------------------------------------------------------------
	// Control conditions, effective Element and Job
	// -------------------------------------------------------------------------

	/**
	 * Returns {@code true} when the "if you/opponent control(s) [X]" restriction on an action
	 * ability is met.  When {@code cond.opponentControls()} is true, checks the opponent's field.
	 */
	boolean controlConditionMet(ControlCondition cond, boolean isP1) {
		// A named-card-state condition ("If Dancer is dull, …") asks about one card's state rather
		// than about a pool, and the pool walk below cannot answer it: with no required name and
		// no minimum count it falls through every filter and reports true for any board at all.
		// icbConditionsMet has always diverted these before they reach here, so the hole only
		// showed when 15-046C Dancer's gate became the first caller to hand one straight over —
		// and it swept the opponent's board on turns Dancer stood active.
		if (cond.stateCardName() != null)
			return isNamedCardInState(cond.stateCardName(), cond.namedState(), isP1);
		// "Neither player controls X" is one condition over the combined board, not two conditions
		// over two boards, so the pools are merged rather than the sides being checked separately.
		if (cond.bothFields()) {
			List<CardData> fwds = new ArrayList<>(p1ForwardCards);
			fwds.addAll(p2ForwardCards);
			List<CardData> mons = new ArrayList<>(p1MonsterCards);
			mons.addAll(p2MonsterCards);
			CardData[] bkps = new CardData[p1BackupCards.length + p2BackupCards.length];
			System.arraycopy(p1BackupCards, 0, bkps, 0, p1BackupCards.length);
			System.arraycopy(p2BackupCards, 0, bkps, p1BackupCards.length, p2BackupCards.length);
			return controlConditionMetWithPools(cond, fwds, bkps, mons);
		}
		boolean checkP1 = cond.opponentControls() ? !isP1 : isP1;
		return controlConditionMetWithPools(cond,
				checkP1 ? p1ForwardCards : p2ForwardCards,
				checkP1 ? p1BackupCards  : p2BackupCards,
				checkP1 ? p1MonsterCards : p2MonsterCards);
	}

	private boolean controlConditionMetWithPools(ControlCondition cond,
			List<CardData> fwds, CardData[] bkps, List<CardData> mons) {
		// Conjunction first: each part wants a card of its own, so they are counted separately
		// against the same pools rather than folded into one filter (14-047R Choco/Mog).
		if (!cond.andConditions().isEmpty()) {
			for (ControlCondition part : cond.andConditions())
				if (!controlConditionMetWithPools(part, fwds, bkps, mons)) return false;
			return true;
		}
		if (cond.isNamedMode()) {
			for (String name : cond.requiredCardNames()) {
				boolean found = fwds.stream().anyMatch(c -> c.name().equalsIgnoreCase(name))
						|| mons.stream().anyMatch(c -> c.name().equalsIgnoreCase(name));
				if (!found) for (CardData bkp : bkps) if (bkp != null && bkp.name().equalsIgnoreCase(name)) { found = true; break; }
				if (cond.anyOf()) {
					if (found) return true;
				} else if (!found) return false;
			}
			return !cond.anyOf();
		}

		// All-have mode: EVERY controlled card of the specified type must satisfy the property filter
		if (cond.allHave()) {
			String ahType = cond.cardType() != null ? cond.cardType().toLowerCase() : null;
			List<CardData> ahPool = new ArrayList<>();
			if (ahType == null || ahType.equals("forward") || ahType.equals("character")) ahPool.addAll(fwds);
			if (ahType == null || ahType.equals("monster")  || ahType.equals("character")) ahPool.addAll(mons);
			if (ahType == null || ahType.equals("backup")   || ahType.equals("character"))
				for (CardData bkp : bkps) if (bkp != null) ahPool.add(bkp);
			if (ahPool.isEmpty()) return false;
			for (CardData card : ahPool) {
				if (cond.element() != null && !effectiveContainsElement(card, cond.element())) return false;
				if (cond.job()     != null && !meetsJobFilterEffective(card, cond.job())) return false;
			}
			return true;
		}

		// Count mode: collect field cards matching the type filter — plus the types any "and/or"
		// alternative asks for, since those may name a different type ("Forwards and/or Backups").
		List<CardData> pool = new ArrayList<>();
		if (countPoolIncludes(cond, "forward")) pool.addAll(fwds);
		if (countPoolIncludes(cond, "monster")) pool.addAll(mons);
		if (countPoolIncludes(cond, "backup")) {
			for (CardData bkp : bkps) if (bkp != null) pool.add(bkp);
		}

		int count = 0;
		for (CardData card : pool) {
			// With alternatives present the parent carries only the count — its own filters are all
			// null and would match everything — so the alternatives ARE the filter set. A card
			// satisfying several of them still counts once.
			boolean matches;
			if (cond.orAlternatives().isEmpty()) {
				matches = matchesCountFilter(cond, card);
			} else {
				matches = false;
				for (ControlCondition alt : cond.orAlternatives())
					if (matchesCountFilter(alt, card)) { matches = true; break; }
			}
			if (matches) count++;
		}
		return cond.exactCount() ? count == cond.minCount() : count >= cond.minCount();
	}

	/** True when {@code cond}'s effective filter set can be satisfied by a card from {@code zone}. */
	private static boolean countPoolIncludes(ControlCondition cond, String zone) {
		if (cond.orAlternatives().isEmpty()) return typeAdmits(cond.cardType(), zone);
		for (ControlCondition alt : cond.orAlternatives())
			if (typeAdmits(alt.cardType(), zone)) return true;
		return false;
	}

	/** True when the card-type filter {@code type} (null = any) admits cards from {@code zone}. */
	private static boolean typeAdmits(String type, String zone) {
		if (type == null) return true;
		String t = type.toLowerCase();
		return t.equals(zone) || t.equals("character");
	}

	/**
	 * True when a single card satisfies {@code cond}'s count-mode filters — including its card-type
	 * filter, which the pool alone no longer guarantees once alternatives widen it.
	 */
	private boolean matchesCountFilter(ControlCondition cond, CardData card) {
		String zone = card.isForward() ? "forward" : card.isBackup() ? "backup" : card.isMonster() ? "monster" : null;
		if (zone == null || !typeAdmits(cond.cardType(), zone)) return false;
		if (!cond.orCardNames().isEmpty()
				&& cond.orCardNames().stream().anyMatch(n -> n.equalsIgnoreCase(card.name()))) return true;
		if (cond.element()        != null && !effectiveContainsElement(card, cond.element()))        return false;
		if (cond.excludeElement() != null &&  effectiveContainsElement(card, cond.excludeElement())) return false;
		if (cond.job()            != null && !meetsJobFilterEffective(card, cond.job()))   return false;
		if (cond.category() != null && !meetsCategoryFilter(card, cond.category())) return false;
		if (cond.minPower() > 0     && card.power() < cond.minPower())         return false;
		if (cond.minCost()  > 0     && card.cost()  < cond.minCost())          return false;
		if (cond.maxCost()  > 0     && card.cost()  > cond.maxCost())          return false;
		return true;
	}

	/**
	 * Like {@link #controlConditionMet} but removes all instances of {@code exceptName} from
	 * every pool before evaluating — used for the "other than X" exclusion in
	 * {@link IfControlBoost}.
	 */
	boolean controlConditionMetExcluding(ControlCondition cond, String exceptName, boolean isP1) {
		if (exceptName.isEmpty()) return controlConditionMet(cond, isP1);
		// The same guard the unexcluded entry carries, and for the same reason: a state condition
		// names one card, so there is no pool for the exclusion to be applied to.
		if (cond.stateCardName() != null)
			return isNamedCardInState(cond.stateCardName(), cond.namedState(), isP1);
		List<CardData> fwds = new ArrayList<>(isP1 ? p1ForwardCards : p2ForwardCards);
		CardData[] srcBkps  = isP1 ? p1BackupCards : p2BackupCards;
		CardData[] bkps     = Arrays.copyOf(srcBkps, srcBkps.length);
		List<CardData> mons = new ArrayList<>(isP1 ? p1MonsterCards : p2MonsterCards);
		fwds.removeIf(c -> c.name().equalsIgnoreCase(exceptName));
		mons.removeIf(c -> c.name().equalsIgnoreCase(exceptName));
		for (int i = 0; i < bkps.length; i++)
			if (bkps[i] != null && bkps[i].name().equalsIgnoreCase(exceptName)) bkps[i] = null;
		return controlConditionMetWithPools(cond, fwds, bkps, mons);
	}

	/** Returns the effective element of {@code c}, applying any runtime override (e.g. Kam'lanaut). */
	String effectiveElement(CardData c) {
		String override = elementOverrideMap.get(c);
		if (override != null) return override;
		String[] elems = c.elements();
		return elems.length == 0 ? null : elems[0];
	}

	/**
	 * Returns the effective element list of {@code c}, substituting the override element when present
	 * and adding any Elements gained from the opposing board.
	 * Used to compare against the currently-resolving Summon/ability's elements.
	 */
	List<String> effectiveElements(CardData c) {
		String override = elementOverrideMap.get(c);
		List<String> base = (override != null) ? List.of(override) : Arrays.asList(c.elements());
		Set<String> gained = gainedOpponentCharacterElements(c);
		// Shantotto Re-099L/1-107L names its six Elements outright rather than querying the board,
		// so the set is read off the card. It joins the same union as the queried form, and a card
		// stripped of its abilities loses it exactly as it loses that one.
		Set<String> printed = selfGrantedElements(c);
		if (gained.isEmpty() && printed.isEmpty()) return base;
		// LinkedHashSet: the printed Elements keep their printed order and the gained ones follow
		// in board order, so anything that renders this list reads the same way twice running.
		Set<String> all = new LinkedHashSet<>(base);
		all.addAll(printed);
		all.addAll(gained);
		return List.copyOf(all);
	}

	/**
	 * The Elements {@code c} gains from "gains Elements of all the Characters opponent controls
	 * except Light and Dark." (Kimahri 1-103C) — the union of the Elements on the opposing
	 * Characters, minus the exclusions. Empty for every card that does not print the ability, and
	 * empty while the card is off the field, where it controls nothing and faces no opponent.
	 *
	 * <p>Recomputed per call rather than cached: the answer changes with every Character that
	 * enters or leaves the other side, so a stored copy would go stale the moment it was written.
	 */
	/**
	 * Every Element {@code c} has gained beyond its printed ones, as a list, for the payment
	 * dialogs. They are handed only the paying player's side of the board, so they cannot work the
	 * queried half out themselves — and the self-granted half (Shantotto) has to arrive by the same
	 * route, or discarding Shantotto for CP would offer only its printed Earth.
	 */
	List<String> gainedElementsForPayment(CardData c) {
		Set<String> out = new LinkedHashSet<>(selfGrantedElements(c));
		out.addAll(gainedOpponentCharacterElements(c));
		return List.copyOf(out);
	}

	/**
	 * The Elements a card grants itself outright — Shantotto's "If Shantotto is on the field,
	 * [it | Shantotto] gains Elements of Fire, Ice, Wind, Earth, Lightning and Water."
	 *
	 * <p>Read through {@link CardData#selfGainedElements}, which shares its pattern with the CP
	 * accessor that already drove this text, rather than through a second parser: "gains Elements
	 * of" is one rule, and the payment dialog and the Element tests must not be able to disagree
	 * about what it grants. Empty once an effect has stripped the card's abilities.
	 */
	private Set<String> selfGrantedElements(CardData c) {
		if (lostAbilitiesCards.contains(c)) return Set.of();
		List<String> extra = c.selfGainedElements();
		return extra.isEmpty() ? Set.of() : new LinkedHashSet<>(extra);
	}

	private Set<String> gainedOpponentCharacterElements(CardData c) {
		Set<String> except = c.gainsOpponentCharacterElementsExcept();
		if (except == null || lostAbilitiesCards.contains(c)) return Set.of();
		Boolean side = fieldSideOf(c);
		if (side == null) return Set.of();
		Set<String> gained = new LinkedHashSet<>();
		for (CardData opp : opposingCharacters(side))
			for (String e : opp.elements())
				if (except.stream().noneMatch(x -> x.equalsIgnoreCase(e))) gained.add(e);
		return gained;
	}

	/**
	 * Every card {@code isP1} has on the field: their Forwards, Backups and Monsters.
	 *
	 * <p>The list a field-ability scan should walk. Several scans built it by hand from the Forward
	 * and Backup rows alone, which silently exempted every Monster printing a field ability they
	 * read — Djinn 16-010H's damage boost did nothing at all for that reason. Two more private
	 * copies of this walk live in {@code AutoAbilityTriggers} and {@code FieldGrantCalculator};
	 * they already include Monsters, and are left where they are.
	 */
	List<CardData> fieldCards(boolean isP1) {
		List<CardData> out = new ArrayList<>(isP1 ? p1ForwardCards : p2ForwardCards);
		for (CardData b : (isP1 ? p1BackupCards : p2BackupCards)) if (b != null) out.add(b);
		out.addAll(isP1 ? p1MonsterCards : p2MonsterCards);
		return out;
	}

	/**
	 * The Special abilities {@code card} borrows from {@code isP1}'s removed-from-game zone — Clive
	 * 26-005H, "Clive gains all the special abilities of the Job Eikon you own removed from the
	 * game."
	 *
	 * <p>Recomputed per query rather than stored, for the reason {@link #syncRfgRemovedPlayables}
	 * is: the grant is a field ability over a zone that keeps moving, so it has to follow both the
	 * pile and the carrier's presence on the field. The abilities are activated against
	 * {@code card} as their source, which is what makes the 《S》 cost ask for a card named after
	 * the carrier rather than after the Eikon that printed the ability.
	 *
	 * <p>Their text is re-pointed at the carrier for the same reason, through the rewrite Gogo's
	 * Mimic uses: two Eikon specials name their own card — Odin (XVI) 24-112L's Iron Flash
	 * ("Activate Odin (XVI). Odin (XVI) can attack once more this turn.") and Garuda (XVI)
	 * 29-046L's Aerial Blast — and left as printed they would act on an Odin that is not on the
	 * field. Costs and restrictions are untouched: only the effect names the wrong card.
	 *
	 * <p>Read through {@link #effectiveFieldAbilities} so a granted copy of the sentence opens the
	 * same pile a printed one does.
	 */
	List<ActionAbility> rfgJobSpecialAbilities(CardData card, boolean isP1) {
		if (card == null || lostAbilitiesCards.contains(card)) return List.of();
		List<ActionAbility> out = new ArrayList<>();
		for (FieldAbility fa : effectiveFieldAbilities(card)) {
			String job = CardData.parseRfgJobSpecialAbilityGrant(fa.effectText(), card.name());
			if (job == null) continue;
			for (CardData removed : isP1 ? gameState.getP1PermanentRfp() : gameState.getP2PermanentRfp()) {
				if (removed == null || !CardFilters.meetsJobFilter(removed, job)) continue;
				for (ActionAbility aa : removed.actionAbilities()) {
					if (!aa.isSpecial()) continue;
					out.add(aa.withEffectText(ActionResolver.substituteSourceName(
							aa.effectText(), removed.name(), card.name())));
				}
			}
		}
		return out;
	}

	/**
	 * Backup slots on {@code isP1}'s field that may be put into the Break Zone for CP while paying
	 * a cost, mapped to how much each produces — Sherlotta 8-053H and her reprint.
	 *
	 * <p>Not filtered by {@link CardState}: breaking is a payment of its own and the printing says
	 * so in as many words ("this can be in addition to dulling Sherlotta for CP"), so a Backup
	 * already dulled for CP can still be broken for more. That is the whole difference between this
	 * list and the eligible-to-dull one the payment dialog builds beside it.
	 *
	 * <p>Read through {@link #effectiveFieldAbilities} so a granted copy of the sentence pays the
	 * same way a printed one does.
	 */
	Map<Integer, Integer> breakForCpBackupSlots(boolean isP1) {
		Map<Integer, Integer> out = new LinkedHashMap<>();
		CardData[] bkps = isP1 ? p1BackupCards : p2BackupCards;
		for (int i = 0; i < bkps.length; i++) {
			CardData b = bkps[i];
			if (b == null || lostAbilitiesCards.contains(b)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(b)) {
				int amount = CardData.parseBreakSelfForCpAmount(fa.effectText(), b.name());
				if (amount > 0) { out.put(i, amount); break; }
			}
		}
		return out;
	}

	/** Every Character on the side facing {@code isP1}: their Forwards, Backups and Monsters. */
	private List<CardData> opposingCharacters(boolean isP1) {
		List<CardData> out = new ArrayList<>(isP1 ? p2ForwardCards : p1ForwardCards);
		for (CardData b : (isP1 ? p2BackupCards : p1BackupCards)) if (b != null) out.add(b);
		out.addAll(isP1 ? p2MonsterCards : p1MonsterCards);
		return out;
	}

	/**
	 * Whether {@code c} counts as {@code elem} right now — its printed Elements, plus an override
	 * or anything gained from the opposing board. The board-aware form of
	 * {@link CardData#containsElement}, which can only see what is printed.
	 */
	boolean effectiveContainsElement(CardData c, String elem) {
		if (c == null || elem == null) return false;
		if (c.containsElement(elem)) return true;
		if (elem.contains("|")) {
			for (String e : elem.split("\\|")) if (effectiveContainsElement(c, e.trim())) return true;
			return false;
		}
		List<String> effective = effectiveElements(c);
		if ("Multi-Element".equalsIgnoreCase(elem)) return effective.size() > 1;
		return effective.stream().anyMatch(e -> e.equalsIgnoreCase(elem));
	}

	private String effectiveExtraJob(CardData card) {
		return permanentExtraJobMap.get(card);
	}

	boolean meetsJobFilterEffective(CardData card, String jobFilter) {
		if (jobFilter != null && jobsStripped(card)) return false;
		return meetsJobFilter(card, jobFilter, effectiveExtraJob(card));
	}

	/**
	 * Whether {@code card} has had its Jobs stripped for the turn — Exdeath 3-100L.
	 *
	 * <p>The board holds this, not the card, so it is handed to the field-grant records: they
	 * carry a Job filter and are asked about cards on the field, but have no way to reach here.
	 * Every one of them takes it as a parameter for that reason, the way they already take the
	 * resolved trait set.
	 */
	boolean jobsStripped(CardData card) {
		return jobsLostCards.contains(card);
	}

	boolean meetsJobFilterEffective(CardData card, String jobFilter,
			List<CardData> controlledForwards) {
		if (jobFilter != null && jobsStripped(card)) return false;
		if (meetsJobFilter(card, jobFilter, controlledForwards)) return true;
		String extra = effectiveExtraJob(card);
		if (extra == null || jobFilter == null) return false;
		for (String j : jobFilter.split("\\|"))
			if (extra.equalsIgnoreCase(j.trim())) return true;
		return false;
	}

	/**
	 * Combines a job filter and a card-name filter the way every printing that supplies both means
	 * them — as alternatives, not as a conjunction.
	 *
	 * <p>"Job Chocobo or Card Name Chocobo" (Bartz 29-052H, Billy 29-048C, and some thirty others)
	 * fills both slots, and a card satisfies exactly one of them: the cards named "Chocobo" carry
	 * the Job Standard Unit, and the Job Chocobo cards are named "Stray Chocobo", "Black Chocobo",
	 * "Lucil". ANDing the two matched nothing at all. Every parser that fills both slots reached
	 * them through an explicit "or"/"and/or" in the printed text, so both-set can be read as the
	 * union without asking the caller which it meant.
	 *
	 * <p>A filter left null is "any", so the single-filter and no-filter cases fall out of the
	 * conjunction unchanged. {@code controlledForwards} is the pool consulted for a card that has
	 * "the Jobs of the Forwards you control"; pass {@code null} where the caller has no pool.
	 */
	/**
	 * Cards whose Jobs are suppressed for the rest of the turn — Exdeath 3-100L.
	 *
	 * <p>Held by identity and cleared by an end-of-turn effect, like the ability-loss set it sits
	 * beside. Read wherever a Job is read for gameplay: a card in here has no Job to match, so a
	 * Job filter passes over it and {@link #effectiveJobs} reports it as having none.
	 */
	final Set<CardData> jobsLostCards =
			java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>());

	boolean meetsJobOrCardNameFilter(CardData card, String jobFilter, String cardNameFilter,
			List<CardData> controlledForwards) {
		boolean jobOk = controlledForwards != null
				? meetsJobFilterEffective(card, jobFilter, controlledForwards)
				: meetsJobFilterEffective(card, jobFilter);
		boolean nameOk = meetsCardNameFilter(card, cardNameFilter);
		return jobFilter != null && cardNameFilter != null ? jobOk || nameOk : jobOk && nameOk;
	}

	// -------------------------------------------------------------------------
	// Cannot-be-chosen (ICB) immunity
	// -------------------------------------------------------------------------

	/**
	 * Returns {@code true} if any {@link IfControlBoost} on the given player's field
	 * targets {@code targetName} and grants it immunity to Summons ({@code forSummon=true})
	 * or abilities ({@code forSummon=false}) while its conditions are currently met — of either
	 * scope. Targeting code wants one scope at a time; see the four-argument overload.
	 */
	boolean icbGrantsImmunity(String targetName, boolean isP1, boolean forSummon) {
		return icbGrantsImmunity(targetName, isP1, forSummon, false, null)
			|| icbGrantsImmunity(targetName, isP1, forSummon, true, null);
	}

	/**
	 * As {@link #icbGrantsImmunity(String, boolean, boolean)}, but restricted to grants of one
	 * scope. {@code opponentScoped=true} selects the "cannot be chosen by <em>your opponent's</em>
	 * Summons or abilities" grants, which block only effects controlled by the target's opponent;
	 * {@code false} selects the unqualified grants, which block whoever is choosing — including
	 * the target's own controller.
	 */
	/**
	 * As the five-argument form with no choosing card known. Every source-type filter admits an
	 * unknown source (see {@link IfControlBoost#admitsChooserSource}), so this is the widest answer
	 * — the right default for a caller asking whether a card is shielded at all rather than
	 * whether one particular effect may choose it.
	 */
	boolean icbGrantsImmunity(String targetName, boolean isP1, boolean forSummon, boolean opponentScoped) {
		return icbGrantsImmunity(targetName, isP1, forSummon, opponentScoped, null);
	}

	boolean icbGrantsImmunity(String targetName, boolean isP1, boolean forSummon,
			boolean opponentScoped, CardData chooserSource) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : fwds)          if (icbSourceGrantsImmunity(src, targetName, isP1, forSummon, opponentScoped, chooserSource)) return true;
		for (CardData bkp : bkps) if (bkp != null && icbSourceGrantsImmunity(bkp, targetName, isP1, forSummon, opponentScoped, chooserSource)) return true;
		for (CardData src : mons)          if (icbSourceGrantsImmunity(src, targetName, isP1, forSummon, opponentScoped, chooserSource)) return true;
		return false;
	}

	private boolean icbSourceGrantsImmunity(CardData src, String targetName, boolean isP1,
			boolean forSummon, boolean opponentScoped, CardData chooserSource) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (IfControlBoost icb : src.ifControlBoosts()) {
			if (forSummon ? !icb.cannotBeChosenBySummons() : !icb.cannotBeChosenByAbilities()) continue;
			if (icb.chosenImmunityOpponentOnly() != opponentScoped) continue;
			// Aerith 3-050L shields only against a Backup's abilities. Grants naming no source
			// type admit anything, so this is a no-op for every other printing.
			if (!icb.admitsChooserSource(chooserSource)) continue;
			if (!icbTargetsName(icb, targetName, isP1, fwds, bkps, mons)) continue;
			if (icbConditionsMet(icb, isP1)) return true;
		}
		return false;
	}

	/**
	 * Whether {@code icb} protects a card called {@code targetName} on {@code isP1}'s field. A
	 * filter-based boost is resolved by walking that field, so a shield handed to a set covers each
	 * member without the caller knowing anything but the name.
	 *
	 * <p>The trait half of a filter is read through {@link #fpgTargetTraits} rather than off the
	 * printed card, so "The Forwards with Brave … cannot be chosen" follows Brave that an effect
	 * granted or stripped.
	 */
	private boolean icbTargetsName(IfControlBoost icb, String targetName, boolean isP1,
			List<CardData> fwds, CardData[] bkps, List<CardData> mons) {
		if (icb.targetFilter() == null) {
			return icb.targetCardName().equalsIgnoreCase(targetName);
		}
		FieldPowerGrant filter = icb.targetFilter();
		for (CardData c : fwds)
			if (targetName.equalsIgnoreCase(c.name()) && icb.appliesToCard(c, fpgTargetTraits(filter, c, isP1), jobsStripped(c))) return true;
		for (CardData c : mons)
			if (targetName.equalsIgnoreCase(c.name()) && icb.appliesToCard(c, fpgTargetTraits(filter, c, isP1), jobsStripped(c))) return true;
		for (CardData c : bkps)
			if (c != null && targetName.equalsIgnoreCase(c.name()) && icb.appliesToCard(c, fpgTargetTraits(filter, c, isP1), jobsStripped(c))) return true;
		return false;
	}

	/** Returns {@code true} when all conditions of {@code icb} are satisfied for the given player. */
	boolean icbConditionsMet(IfControlBoost icb, boolean isP1) {
		for (ControlCondition cond : icb.conditions()) {
			if (cond.requiresCrystal()) {
				if (playerCrystals(isP1) < 1) return false;
			} else if (cond.stateCardName() != null) {
				if (!isNamedCardInState(cond.stateCardName(), cond.namedState(), isP1)) return false;
			} else {
				if (!controlConditionMetExcluding(cond, icb.exceptCardName(), isP1)) return false;
			}
		}
		if (icb.minRemovedFromGame() > 0) {
			int totalRfp = gameState.getP1PermanentRfp().size() + gameState.getP2PermanentRfp().size();
			if (totalRfp < icb.minRemovedFromGame()) return false;
		}
		if (icb.minDamageReceived() > 0) {
			List<CardData> dmgZone = isP1 ? gameState.getP1DamageZone() : gameState.getP2DamageZone();
			if (dmgZone.size() < icb.minDamageReceived()) return false;
		}
		if (icb.maxOpponentHandSize() > 0) {
			int oppHandSize = (isP1 ? gameState.getP2Hand() : gameState.getP1Hand()).size();
			if (oppHandSize > icb.maxOpponentHandSize()) return false;
		}
		if (icb.minOpponentForwards() > 0) {
			List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
			if (oppFwds.size() < icb.minOpponentForwards()) return false;
		}
		if (icb.maxOwnHandSize() > 0) {
			int ownHandSize = (isP1 ? gameState.getP1Hand() : gameState.getP2Hand()).size();
			if (ownHandSize > icb.maxOwnHandSize()) return false;
		}
		if (icb.minOwnHandSize() > 0) {
			int ownHandSize = (isP1 ? gameState.getP1Hand() : gameState.getP2Hand()).size();
			if (ownHandSize < icb.minOwnHandSize()) return false;
		}
		if (icb.minDifferentElementBackups() > 0) {
			// Distinct Elements, not distinct Backups: two Fire Backups count once, and a
			// multi-element Backup contributes each of its Elements.
			Set<String> elems = new HashSet<>();
			for (CardData b : (isP1 ? p1BackupCards : p2BackupCards))
				if (b != null) elems.addAll(effectiveElements(b));
			if (elems.size() < icb.minDifferentElementBackups()) return false;
		}
		if (icb.allBackupsDifferentElements()) {
			CardData[] bkps = isP1 ? p1BackupCards : p2BackupCards;
			Set<String> seen = new java.util.HashSet<>();
			for (CardData b : bkps)
				if (b != null && !seen.add(effectiveElement(b))) return false;
		}
		return true;
	}

	/** Returns {@code true} if any card on the opponent's field forces {@code isP1}'s Forwards to enter dull. */
	private boolean opponentForcesForwardDull(boolean isP1) {
		List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
		CardData[]     oppBkps = isP1 ? p2BackupCards  : p1BackupCards;
		List<CardData> oppMons = isP1 ? p2MonsterCards : p1MonsterCards;
		for (CardData c : oppFwds) if (c.opponentForwardsEnterFieldDull()) return true;
		for (CardData c : oppBkps) if (c != null && c.opponentForwardsEnterFieldDull()) return true;
		for (CardData c : oppMons) if (c.opponentForwardsEnterFieldDull()) return true;
		return false;
	}

	/**
	 * Returns {@code true} when {@code entering} must enter the field dull — its own printed
	 * "enters the field dull", an opponent forcing it, or a board-wide "All the Forwards other than
	 * X enter the field dull." (Ultimecia 1-152L) that does not spare it.
	 *
	 * <p>The board-wide form names no controller, so both fields are searched and its own
	 * controller's Forwards are dulled alongside the opponent's. The exception is matched by Card
	 * Name, which is what the text means: a second Ultimecia entering is spared too.
	 */
	private boolean forwardEntersFieldDull(CardData entering, boolean isP1) {
		if (entering.entersFieldDull() || opponentForcesForwardDull(isP1)) return true;
		return allForwardsForcedDullExcept(entering, true) || allForwardsForcedDullExcept(entering, false);
	}

	/** Whether any card on {@code side}'s field dulls all entering Forwards but {@code entering}. */
	private boolean allForwardsForcedDullExcept(CardData entering, boolean side) {
		List<CardData> fwds = side ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = side ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = side ? p1MonsterCards : p2MonsterCards;
		for (CardData c : fwds)                  if (forcesAllForwardsDull(c, entering)) return true;
		for (CardData c : bkps) if (c != null)   if (forcesAllForwardsDull(c, entering)) return true;
		for (CardData c : mons)                  if (forcesAllForwardsDull(c, entering)) return true;
		return false;
	}

	private boolean forcesAllForwardsDull(CardData src, CardData entering) {
		if (lostAbilitiesCards.contains(src)) return false;
		String except = src.allForwardsEnterFieldDullExcept();
		return except != null && !CardFilters.meetsCardNameFilter(entering, except);
	}

	/**
	 * Whether a card called {@code name} is on {@code isP1}'s field in {@code state} — the field
	 * side of a {@link ControlCondition} named-state condition ("If Trey is active, …",
	 * "If Queen is attacking, …").
	 *
	 * <p>Forwards and Monsters only. A Backup has a dull/active state too, but no printing in this
	 * family names one, and a Backup can never be attacking.
	 *
	 * <p>Attacking is read off the declared attackers rather than off the card's state, because a
	 * Forward is attacking for exactly as long as it is in the declared attack — dulling to attack
	 * is a consequence of it, and a Forward given Brave attacks without dulling at all.
	 */
	private boolean isNamedCardInState(String name, ControlCondition.NamedCardState state, boolean isP1) {
		if (state == ControlCondition.NamedCardState.ATTACKING) {
			for (CardData atk : isP1 ? p1DeclaredAttackers : p2DeclaredAttackers)
				if (atk != null && atk.name().equalsIgnoreCase(name)) return true;
			return false;
		}
		CardState wanted = state == ControlCondition.NamedCardState.DULL ? CardState.DULL : CardState.ACTIVE;
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		List<CardState> states = isP1 ? p1ForwardStates : p2ForwardStates;
		for (int i = 0; i < fwds.size(); i++)
			if (fwds.get(i).name().equalsIgnoreCase(name) && states.get(i) == wanted) return true;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		List<CardState> monStates = isP1 ? p1MonsterStates : p2MonsterStates;
		for (int i = 0; i < mons.size(); i++)
			if (mons.get(i).name().equalsIgnoreCase(name) && monStates.get(i) == wanted) return true;
		return false;
	}

	// -------------------------------------------------------------------------
	// Field power grants and boost contribution
	// -------------------------------------------------------------------------

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

		// Opposing-side debuffs ("The Forwards opponent controls lose N power") apply
		// to this target when the source sits across the field.
		List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
		CardData[]     oppBkps = isP1 ? p2BackupCards  : p1BackupCards;
		List<CardData> oppMons = isP1 ? p2MonsterCards : p1MonsterCards;
		for (CardData src : oppFwds) boost += opposingFieldDebuffContribution(src, target, !isP1);
		for (CardData bkp : oppBkps) if (bkp != null) boost += opposingFieldDebuffContribution(bkp, target, !isP1);
		for (CardData src : oppMons) boost += opposingFieldDebuffContribution(src, target, !isP1);
		return boost;
	}

	/** Returns {@code true} if all conditions on {@code fpg} are met for the given player. */
	boolean fpgBzConditionMet(FieldPowerGrant fpg, boolean isP1) {
		List<CardData> bz = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
		if (fpg.minBzSize() > 0 && bz.size() < fpg.minBzSize()) return false;
		if (fpg.bzFilterCardName() != null) {
			if (bz.stream().noneMatch(c -> CardFilters.meetsCardNameFilter(c, fpg.bzFilterCardName()))) return false;
		}
		if (fpg.minBzFilterCount() > 0) {
			long cnt = bz.stream()
				.filter(c -> !fpg.bzFilterFwds() || c.isForward())
				.filter(c -> fpg.bzFilterJob() == null || CardFilters.meetsJobFilter(c, fpg.bzFilterJob()))
				.count();
			if (cnt < fpg.minBzFilterCount()) return false;
		}
		if (fpg.minDistinctElements() > 0) {
			List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
			CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
			List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
			Set<String> elems = new java.util.HashSet<>();
			if (fpg.inclForwards()) for (CardData c : fwds) for (String e : c.element().split("/")) elems.add(e);
			if (fpg.inclBackups())  for (CardData c : bkps) { if (c != null) for (String e : c.element().split("/")) elems.add(e); }
			if (fpg.inclMonsters()) for (CardData c : mons) for (String e : c.element().split("/")) elems.add(e);
			if (elems.size() < fpg.minDistinctElements()) return false;
		}
		if (fpg.minDamageThreshold() > 0 || fpg.maxDamageThreshold() > 0) {
			int dmg = (isP1 ? gameState.getP1DamageZone() : gameState.getP2DamageZone()).size();
			if (fpg.minDamageThreshold() > 0 && dmg < fpg.minDamageThreshold()) return false;
			if (fpg.maxDamageThreshold() > 0 && dmg >= fpg.maxDamageThreshold()) return false;
		}
		return true;
	}

	/**
	 * The traits {@code target} has right now on {@code isP1}'s side — printed, plus temporary,
	 * permanent and Monster-side grants, minus stripped ones — for resolving a
	 * {@link FieldPowerGrant#traitFilter}. Short-circuits to the printed set when the grant has no
	 * trait filter, so the common case allocates nothing.
	 *
	 * <p>Deliberately does not consult {@link FieldGrantCalculator#computeConditionalTraitsForTarget}:
	 * this feeds field-grant evaluation, and that method walks the same grants — a grant that both
	 * filtered on a trait and granted one would recurse. The other three sources cover what the
	 * printed cards need (Ash's own {@code Dull} ability hands out Brave).
	 */
	Set<CardData.Trait> fpgTargetTraits(FieldPowerGrant fpg, CardData target, boolean isP1) {
		if (fpg.traitFilter().isEmpty()) return target.traits();
		EnumSet<CardData.Trait> out = EnumSet.noneOf(CardData.Trait.class);
		out.addAll(target.traits());
		out.addAll(permanentTraits.getOrDefault(target, NO_TRAITS));
		EnumSet<CardData.Trait> monTemp = (isP1 ? p1MonsterTempTraits : p2MonsterTempTraits).get(target);
		if (monTemp != null) out.addAll(monTemp);
		List<CardData> fwds  = isP1 ? p1ForwardCards        : p2ForwardCards;
		List<EnumSet<CardData.Trait>> temps = isP1 ? p1ForwardTempTraits    : p2ForwardTempTraits;
		List<EnumSet<CardData.Trait>> rmvd  = isP1 ? p1ForwardRemovedTraits : p2ForwardRemovedTraits;
		for (int i = 0; i < fwds.size(); i++) {
			if (fwds.get(i) != target) continue;
			if (i < temps.size()) out.addAll(temps.get(i));
			if (i < rmvd.size())  out.removeAll(rmvd.get(i));
			break;
		}
		return out;
	}

	/**
	 * Whether {@code target}, sitting on {@code isP1}'s side, is one of the Forwards currently
	 * attacking — the state filter of Lava Spider 8-022R
	 * ("The attacking Forwards you control gain +3000 power.").
	 *
	 * <p>Matched by identity, not {@code equals}, as {@link #survivingDeclaredAttackers} is:
	 * {@link CardData} is a record, so two copies of one printing compare equal and a
	 * {@code contains} test would boost the one sitting at home. That is defensive rather than
	 * load-bearing — the uniqueness rule breaks the older copy as the second is seated, so the twins
	 * never share a field — but the two attacker reads should not disagree about what they mean.
	 *
	 * <p>A primed Forward declares its attack as the top card while power is computed against the
	 * card underneath it, so a stack is matched by slot as well as by instance.
	 */
	/**
	 * Returns {@code true} when {@code card} is currently one of two or more Forwards {@code isP1}
	 * has declared as attackers — the board condition "forms a party" names.
	 *
	 * <p>Read the same way {@link #fpgPartyConditionMet} reads it, so the party-conditioned power
	 * grants and the party-conditioned damage shields agree about when a party exists.
	 */
	boolean isFormingParty(CardData card, boolean isP1) {
		return declaredAttackers(isP1).size() >= 2 && isDeclaredAttacker(card, isP1);
	}

	/** Every Forward in the party {@code isP1} currently has declared, or empty when there is none. */
	List<CardData> currentPartyMembers(boolean isP1) {
		List<CardData> declared = declaredAttackers(isP1);
		return declared.size() >= 2 ? declared : List.of();
	}

	private boolean isDeclaredAttacker(CardData target, boolean isP1) {
		List<CardData> declared = declaredAttackers(isP1);
		for (CardData atk : declared) if (atk == target) return true;
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		List<CardData> tops = isP1 ? p1ForwardPrimedTop : p2ForwardPrimedTop;
		for (int i = 0; i < fwds.size() && i < tops.size(); i++) {
			if (fwds.get(i) != target || tops.get(i) == null) continue;
			for (CardData atk : declared) if (atk == tops.get(i)) return true;
		}
		return false;
	}

	/** Sum of {@link FieldPowerGrant#powerBonus} from {@code src} for grants that target the opposing side. */
	private int opposingFieldDebuffContribution(CardData src, CardData target, boolean isP1) {
		int sum = 0;
		for (FieldPowerGrant fpg : src.fieldPowerGrants())
			// isP1 is the *source's* side here, so the target's traits are read from the other side.
			if (fpg.affectsOpponent()
					&& fpg.appliesToCard(target, fpgTargetTraits(fpg, target, !isP1), jobsStripped(target))
					&& fpgBzConditionMet(fpg, isP1)
					&& (!fpg.attackingOnly() || isDeclaredAttacker(target, !isP1)))
				sum += fpg.powerBonus();
		if (target.isForward())
			for (CounterGrant cg : src.counterGrants())
				if (cg.affectsOpponent()) sum += counterGrantPower(cg, target);
		return sum;
	}

	/**
	 * {@code grant}'s power contribution to {@code target} right now, or 0 when the counter it names
	 * is absent. A {@link CounterGrant#perCounter} grant multiplies by the count on the card
	 * (Gargas 17-045R: -2000 for each Poison Counter); every other form pays out once at
	 * {@link CounterGrant#minCount} or more — one, except for the threshold printings (Palom
	 * 23-018R needs two EXP Counters).
	 */
	private int counterGrantPower(CounterGrant grant, CardData target) {
		if (grant.powerBonus() == 0) return 0;
		int n = gameState.getCounters(target, grant.counterName());
		if (n < grant.minCount()) return 0;
		return grant.perCounter() ? grant.powerBonus() * n : grant.powerBonus();
	}

	/**
	 * Whether {@code fpg}'s turn window is open for a grant on {@code isP1}'s side — Rydia 28-072L's
	 * "During your opponent's turn" and the "during your turn" printings alike.
	 *
	 * <p>"Your" in a card's own text is its controller, so both windows are read against the side
	 * the grant is running on. A grant naming no window is always open, so this can sit in the same
	 * conjunction as the other board-state gates.
	 */
	boolean fpgTurnWindowOpen(FieldPowerGrant fpg, boolean isP1) {
		if (!fpg.yourTurnOnly() && !fpg.oppTurnOnly()) return true;
		boolean ownTurn = isP1 == (gameState.getCurrentPlayer() == GameState.Player.P1);
		return fpg.oppTurnOnly() ? !ownTurn : ownTurn;
	}

	/**
	 * Whether a {@link FieldPowerGrant#partyWithCardName} grant's party condition holds — Chocobo
	 * 2-060C's "The Forwards forming a party with Chocobo gain First Strike."
	 *
	 * <p>A party exists only while one is declared, so this reads the declared attackers rather than
	 * the board: {@code src} must be the card the text names, both it and {@code target} must be in
	 * the declared party, and that party must have two or more members — one Forward attacking alone
	 * is not forming a party with anybody, itself included.
	 *
	 * <p>Grants with no party clause pass unconditionally, so this can sit in the same conjunction
	 * as the other board-state gates.
	 */
	boolean fpgPartyConditionMet(FieldPowerGrant fpg, CardData src, CardData target, boolean isP1) {
		if (fpg.partyWithCardName() == null) return true;
		// Gippal 12-058C's unnamed form asks only that the target be in a party. The source is not
		// consulted at all: he grants Brave to the Forwards forming a party whether or not he is one
		// of them, and whether or not he is attacking.
		if (FieldPowerGrant.ANY_PARTY.equals(fpg.partyWithCardName())) return isFormingParty(target, isP1);
		if (!CardFilters.meetsCardNameFilter(src, fpg.partyWithCardName())) return false;
		if (declaredAttackers(isP1).size() < 2) return false;
		return isDeclaredAttacker(src, isP1) && isDeclaredAttacker(target, isP1);
	}

	private int fieldBoostContribution(CardData src, CardData target, boolean isP1) {
		if (lostAbilitiesCards.contains(src)) return 0;
		int boost = 0;
		for (IfControlBoost icb : src.ifControlBoosts())
			if (icb.appliesToCard(target, jobsStripped(target)) && icbConditionsMet(icb, isP1))
				boost += icb.powerBonus();
		for (FieldPowerGrant fpg : src.fieldPowerGrants())
			if (!fpg.affectsOpponent()
					&& fpg.appliesToCard(target, fpgTargetTraits(fpg, target, isP1), jobsStripped(target))
					&& fpgBzConditionMet(fpg, isP1)
					&& (!fpg.attackingOnly() || isDeclaredAttacker(target, isP1))
					&& fpgPartyConditionMet(fpg, src, target, isP1)
					&& fpgTurnWindowOpen(fpg, isP1)) {
				boost += fpg.powerBonus();
				if (fpg.exBurstDmgPerGroup() > 0) {
					List<CardData> dmg = isP1 ? gameState.getP1DamageZone() : gameState.getP2DamageZone();
					int exCount = (int) dmg.stream().filter(CardData::exBurst).count();
					boost += (exCount / fpg.exBurstDmgGroupSize()) * fpg.exBurstDmgPerGroup();
				}
			}
		// Counter-conditioned power grant ("Each Forward you control with a [X] Counter on it gains +N power.")
		if (target.isForward())
			for (CounterGrant cg : src.counterGrants())
				if (!cg.affectsOpponent()) boost += counterGrantPower(cg, target);
		if (src == target) {
			// Self-targeted power grant, gated on the FieldAbility's own "Damage N --" prefix
			// (Elle 13-088H, Charlotte 13-023R). A passive re-read per query, because the gate opens
			// and shuts as the damage zone fills.
			if (!lostAbilitiesCards.contains(src)) {
				int dmgZone = (isP1 ? gameState.getP1DamageZone() : gameState.getP2DamageZone()).size();
				for (FieldAbility fa : src.fieldAbilities()) {
					if (fa.damageThreshold() > 0 && dmgZone < fa.damageThreshold()) continue;
					// Firion 21-099H gates the same sentence on the opposing board instead of on a
					// damage count; the gate comes off here and the remainder is read as any other
					// self power grant. Null means the gate is shut.
					String grantText = oppDullCharsGrantRemainder(fa.effectText(), isP1);
					if (grantText == null) continue;
					boost += CardData.parseSelfPowerGrant(grantText, src.name());
				}
			}
			for (ScalingSelfPowerBoost ssb : src.scalingSelfPowerBoosts()) {
				int count = switch (ssb.source()) {
					case OPPONENT_FORWARDS -> isP1 ? p2ForwardCards.size() : p1ForwardCards.size();
					case OPPONENT_BACKUPS -> {
						CardData[] bkps = isP1 ? p2BackupCards : p1BackupCards;
						int n = 0;
						for (CardData b : bkps) if (b != null) n++;
						yield n;
					}
					// "Character" spans all three opposing rows, so a dull Backup counts as readily
					// as a dull Forward — which is the point of Squall 2-038H, whose own attack
					// trigger dulls the Backups he is about to be measured against.
					case OPPONENT_DULL_CHARACTERS -> {
						List<CardState> fwdSt = isP1 ? p2ForwardStates : p1ForwardStates;
						CardState[]     bkpSt = isP1 ? p2BackupStates  : p1BackupStates;
						CardData[]      bkps  = isP1 ? p2BackupCards   : p1BackupCards;
						List<CardState> monSt = isP1 ? p2MonsterStates : p1MonsterStates;
						int n = 0;
						for (CardState st : fwdSt) if (st == CardState.DULL) n++;
						for (int i = 0; i < bkps.length; i++)
							if (bkps[i] != null && bkpSt[i] == CardState.DULL) n++;
						for (CardState st : monSt) if (st == CardState.DULL) n++;
						yield n;
					}
					case OTHER_CHARACTERS_YOU_CONTROL -> {
						List<CardData>  fwds   = isP1 ? p1ForwardCards  : p2ForwardCards;
						List<CardState> fwdSt  = isP1 ? p1ForwardStates : p2ForwardStates;
						CardData[]      bkps   = isP1 ? p1BackupCards   : p2BackupCards;
						CardState[]     bkpSt  = isP1 ? p1BackupStates  : p2BackupStates;
						List<CardData>  mons   = isP1 ? p1MonsterCards  : p2MonsterCards;
						List<CardState> monSt  = isP1 ? p1MonsterStates : p2MonsterStates;
						int n = 0;
						for (int i = 0; i < fwds.size(); i++)
							if (scalingCharacterCounts(fwds.get(i), fwdSt.get(i), src, ssb)) n++;
						for (int i = 0; i < bkps.length; i++)
							if (bkps[i] != null && scalingCharacterCounts(bkps[i], bkpSt[i], src, ssb)) n++;
						for (int i = 0; i < mons.size(); i++)
							if (scalingCharacterCounts(mons.get(i), monSt.get(i), src, ssb)) n++;
						yield n;
					}
					case OTHER_FORWARDS_YOU_CONTROL -> {
						List<CardData>  fwds  = isP1 ? p1ForwardCards  : p2ForwardCards;
						List<CardState> fwdSt = isP1 ? p1ForwardStates : p2ForwardStates;
						int n = 0;
						for (int i = 0; i < fwds.size(); i++) {
							if (scalingCharacterCounts(fwds.get(i), fwdSt.get(i), src, ssb)) n++;
						}
						yield n;
					}
					case OTHER_BACKUPS_YOU_CONTROL -> {
						CardData[]  bkps  = isP1 ? p1BackupCards  : p2BackupCards;
						CardState[] bkpSt = isP1 ? p1BackupStates : p2BackupStates;
						int n = 0;
						for (int i = 0; i < bkps.length; i++) {
							if (bkps[i] != null && scalingCharacterCounts(bkps[i], bkpSt[i], src, ssb)) n++;
						}
						yield n;
					}
					case OTHER_MONSTERS_YOU_CONTROL -> {
						List<CardData>  mons  = isP1 ? p1MonsterCards  : p2MonsterCards;
						List<CardState> monSt = isP1 ? p1MonsterStates : p2MonsterStates;
						int n = 0;
						for (int i = 0; i < mons.size(); i++) {
							if (scalingCharacterCounts(mons.get(i), monSt.get(i), src, ssb)) n++;
						}
						yield n;
					}
					case DAMAGE_RECEIVED ->
						(isP1 ? gameState.getP1DamageZone() : gameState.getP2DamageZone()).size();
					case CARD_NAME_IN_BREAK_ZONE -> {
						List<CardData> bz = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
						String nameFilter = ssb.cardNameFilter();
						String jobFilter  = ssb.jobFilter();
						// Name or Job, whichever the printing names — Shinra Soldier 10-093C counts
						// both ("For each Job Shinra Soldier or Card Name Shinra Soldier…"), and a
						// Break Zone card satisfying either is one card, not two. Filters go through
						// matchesScalingFilter for that disjunction, but only once at least one is
						// set: with both null it answers "matches everything", which would count the
						// whole Break Zone.
						yield nameFilter == null && jobFilter == null ? 0 : (int) bz.stream()
								.filter(c -> matchesScalingFilter(c, jobFilter, null, nameFilter)).count();
					}
					case SUMMONS_IN_BREAK_ZONE -> {
						List<CardData> bz = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
						yield (int) bz.stream().filter(CardData::isSummon).count();
					}
					case CARDS_IN_HAND ->
						(isP1 ? gameState.getP1Hand() : gameState.getP2Hand()).size();
					case COUNTERS_ON_SELF ->
						ssb.cardNameFilter() == null ? 0 : gameState.getCounters(src, ssb.cardNameFilter());
					case CARDS_REMOVED_BY_OWN_ABILITY -> {
						List<CardData> removed = cardsRemovedBySource.get(src);
						yield removed == null ? 0 : removed.size();
					}
				};
				boost += ssb.perUnit() * (count / ssb.groupSize());
			}
		}
		return boost;
	}

	/**
	 * Eligibility check for one slot when counting "other ... you control" toward a
	 * {@link ScalingSelfPowerBoost}. Honors source-name exclusion (by name), active-state
	 * requirement, element include/exclude, and the job/category/cardName OR-disjunction.
	 */
	private boolean scalingCharacterCounts(CardData c, CardState state, CardData src, ScalingSelfPowerBoost ssb) {
		if (c == null) return false;
		if (c.name().equalsIgnoreCase(src.name())) return false;
		if (ssb.requireActive() && state != CardState.ACTIVE) return false;
		if (ssb.elementFilter() != null && !effectiveContainsElement(c, ssb.elementFilter())) return false;
		if (ssb.excludeElement() != null && effectiveContainsElement(c, ssb.excludeElement())) return false;
		if (ssb.sameJobAsSelf() && !sharesAnyJob(c, src)) return false;
		return matchesScalingFilter(c, ssb.jobFilter(), ssb.categoryFilter(), ssb.cardNameFilter());
	}

	/**
	 * True when {@code a} and {@code b} have at least one Job in common, counting Jobs named onto a
	 * card at runtime ("Bartz gains named Job") alongside its printed ones. Backs the "with the same
	 * Job as [self]" scaling filter, whose Job set is only known while the card is on the field.
	 *
	 * <p>A card with all the Jobs shares a Job with anything that has one — but not with a card that
	 * has none, since there is then no Job to share.
	 */
	private boolean sharesAnyJob(CardData a, CardData b) {
		Set<String> aJobs = effectiveJobs(a);
		Set<String> bJobs = effectiveJobs(b);
		if (a.hasAllJobs()) return b.hasAllJobs() || !bJobs.isEmpty();
		if (b.hasAllJobs()) return !aJobs.isEmpty();
		for (String j : aJobs) if (bJobs.contains(j)) return true;
		return false;
	}

	/** {@code card}'s Jobs, lower-cased for comparison, including any permanently named Job. */
	private Set<String> effectiveJobs(CardData card) {
		if (jobsLostCards.contains(card)) return Set.of();
		Set<String> jobs = new java.util.HashSet<>();
		for (String j : card.jobs()) jobs.add(j.toLowerCase(java.util.Locale.ROOT));
		String extra = permanentExtraJobMap.get(card);
		if (extra != null && !extra.isBlank()) jobs.add(extra.toLowerCase(java.util.Locale.ROOT));
		return jobs;
	}

	/**
	 * OR-disjunction filter check used by {@link #scalingCharacterCounts}.
	 * Returns {@code true} if all three filters are {@code null} (no restriction) OR if the
	 * card matches at least one of the non-null filters.
	 */
	private boolean matchesScalingFilter(CardData c, String jobFilter, String categoryFilter, String cardNameFilter) {
		if (jobFilter == null && categoryFilter == null && cardNameFilter == null) return true;
		if (jobFilter      != null && meetsJobFilterEffective(c, jobFilter))              return true;
		if (categoryFilter != null && CardFilters.meetsCategoryFilter(c, categoryFilter)) return true;
		if (cardNameFilter != null && CardFilters.meetsCardNameFilter(c, cardNameFilter)) return true;
		return false;
	}

	int effectiveP1MonsterPower(int idx) {
		CardData card = p1MonsterCards.get(idx);
		return card.power() + computeConditionalBoostForTarget(card, true) + p1MonsterPowerBoost.getOrDefault(card, 0);
	}

	int effectiveP2MonsterPower(int idx) {
		CardData card = p2MonsterCards.get(idx);
		return card.power() + computeConditionalBoostForTarget(card, false) + p2MonsterPowerBoost.getOrDefault(card, 0);
	}

	/**
	 * Power a P1 monster uses while acting as a Forward: become-Forward base + conditional/EOT
	 * boosts. A temp-map entry (an effect that made it a Forward this turn, e.g. Gau) is applied
	 * later than the printed become-Forward ability, so it takes precedence while it lasts.
	 */
	int p1MonsterForwardPower(int idx) {
		CardData card = p1MonsterCards.get(idx);
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		Integer tempPower = p1MonsterTempForwardPower.get(card);
		int base = tempPower != null ? tempPower.intValue() : (bfa != null ? bfa.power() : 0);
		return base + computeConditionalBoostForTarget(card, true) + p1MonsterPowerBoost.getOrDefault(card, 0);
	}


	// -------------------------------------------------------------------------
	// GameContext construction and field-ability firing
	// -------------------------------------------------------------------------

	/**
	 * Builds the {@link GameContext} used by {@link ActionResolver} to apply field effects.
	 * The returned instance is stateless (delegates to live MainWindow fields), so it is safe
	 * to call multiple times and share between ability resolution and summon resolution.
	 *
	 * @param isP1 {@code true} when P1 is the ability user (affects discard/draw direction)
	 */
	GameContext buildGameContext(boolean isP1) {
		return buildGameContext(isP1, false);
	}

	GameContext buildGameContext(boolean isP1, boolean exBurst) {
		suppressExBurstsThisAbility = false;
		aiPrefersOwnTargets         = false;
		return new GameContextImpl(this, isP1, exBurst);
	}

	/**
	 * Fires "At the end of each of your turns" field abilities for the controlling player.
	 * Called at the start of the END phase, before temporary-boost cleanup.
	 */
	/**
	 * Fires "if your opponent doesn't control Forwards" field abilities for a single card.
	 * Call this when {@code card} enters the field (to handle an already-empty opponent side)
	 * or from {@link #fireOppNoForwardsFieldAbilities} when scanning all of a player's field cards.
	 */
	private void fireOppNoForwardsFieldAbilitiesForCard(CardData card, boolean isP1) {
		List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
		if (!oppFwds.isEmpty()) return;
		GameContext ctx = buildGameContext(isP1);
		for (FieldAbility fa : card.fieldAbilities()) {
			Consumer<GameContext> effect =
					ActionResolverBreak.tryParseIfOppNoForwardsPutToBreakZone(fa.effectText(), card);
			if (effect != null) {
				logEntry("[Field] " + card.name() + ": " + fa.effectText());
				effect.accept(ctx);
				return;
			}
		}
	}

	/**
	 * Scans all field cards of player {@code isP1} and fires any
	 * "if your opponent doesn't control Forwards" field abilities.
	 * Called when a Forward belonging to {@code !isP1} leaves the field.
	 */
	void fireOppNoForwardsFieldAbilities(boolean isP1) {
		List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
		if (!oppFwds.isEmpty()) return;
		// Snapshot lists before firing — an effect (e.g. breaking Gogo) may modify them
		List<CardData> fwds = new ArrayList<>(isP1 ? p1ForwardCards : p2ForwardCards);
		CardData[]     bkps = isP1 ? p1BackupCards : p2BackupCards;
		List<CardData> mons = new ArrayList<>(isP1 ? p1MonsterCards : p2MonsterCards);
		for (CardData card : fwds) if (card != null) fireOppNoForwardsFieldAbilitiesForCard(card, isP1);
		for (CardData card : bkps) if (card != null) fireOppNoForwardsFieldAbilitiesForCard(card, isP1);
		for (CardData card : mons) if (card != null) fireOppNoForwardsFieldAbilitiesForCard(card, isP1);
	}

	/**
	 * Fires "If you have received N points of damage" field abilities for one player's field cards.
	 * Call whenever the player's damage zone grows.
	 */
	void fireFieldSelfDamagePointsAbilities(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		GameContext ctx = buildGameContext(isP1);
		for (CardData card : new ArrayList<>(fwds)) fireFieldSelfDamagePointsAbilitiesForCard(card, ctx);
		for (CardData card : bkps) if (card != null) fireFieldSelfDamagePointsAbilitiesForCard(card, ctx);
		for (CardData card : new ArrayList<>(mons)) fireFieldSelfDamagePointsAbilitiesForCard(card, ctx);
	}

	private void fireFieldSelfDamagePointsAbilitiesForCard(CardData card, GameContext ctx) {
		for (FieldAbility fa : card.fieldAbilities()) {
			Consumer<GameContext> effect =
					ActionResolverDamage.tryParseIfSelfDamagePointsPutToBreakZone(fa.effectText(), card);
			if (effect != null) {
				logEntry("[Field] " + card.name() + ": " + fa.effectText());
				effect.accept(ctx);
				return;
			}
		}
	}

	/**
	 * Fires the end-of-turn ability that a card on the field grants to {@code isP1}'s Forwards —
	 * Vayne 9-022L: All the Forwards opponent controls gain "At the end of your turn, if you don't
	 * pay 《1》, break this Forward."
	 *
	 * <p>The grant is continuous, so it is read off the granting card's field abilities at the
	 * moment it fires rather than stamped onto the Forwards: a Forward that arrived this turn is
	 * covered, and one that arrives after the granter leaves is not. Each Forward resolves its own
	 * copy — with three Forwards on the field the cost is asked three times, once per card, which is
	 * what having the ability three times means.
	 */
	void fireGrantedEndOfTurnForwardAbilities(boolean isP1) {
		List<CardData> forwards = new ArrayList<>(isP1 ? p1ForwardCards : p2ForwardCards);
		if (forwards.isEmpty()) return;
		for (boolean granterIsP1 : new boolean[]{ true, false }) {
			List<CardData> granters = new ArrayList<>(granterIsP1 ? p1ForwardCards : p2ForwardCards);
			granters.addAll(granterIsP1 ? p1MonsterCards : p2MonsterCards);
			for (CardData b : granterIsP1 ? p1BackupCards : p2BackupCards) if (b != null) granters.add(b);

			for (CardData granter : granters) {
				if (lostAbilitiesCards.contains(granter)) continue;
				for (FieldAbility fa : granter.fieldAbilities()) {
					ActionResolver.ForwardAbilityGrant grant =
							ActionResolverFieldAbility.tryParseForwardAbilityGrant(fa.effectText());
					if (grant == null) continue;
					boolean granteeIsP1 = grant.affectsOpponent() ? !granterIsP1 : granterIsP1;
					if (granteeIsP1 != isP1) continue;

					for (CardData fwd : forwards) {
						// Re-check each time: an earlier Forward's copy may already have broken this one.
						if (identityIndexOf(isP1 ? p1ForwardCards : p2ForwardCards, fwd) < 0) continue;
						Consumer<GameContext> effect =
								ActionResolverFieldAbility.tryParseGrantedEndOfTurnEffect(grant.abilityText(), fwd);
						if (effect == null) continue;
						logEntry((isP1 ? "" : "[P2] ") + fwd.name() + " — granted by " + granter.name()
								+ ": " + grant.abilityText());
						CardData prevSource  = currentAbilitySource;
						boolean  prevSpecial = currentAbilityIsSpecial;
						currentAbilitySource    = fwd;
						currentAbilityIsSpecial = false;
						try { effect.accept(buildGameContext(isP1)); }
						finally { currentAbilitySource = prevSource; currentAbilityIsSpecial = prevSpecial; }
					}
				}
			}
		}
	}

	/** Fires all queued end-of-turn effects using a context for {@code isP1}, then clears the queue. */
	void fireEndOfTurnEffects(boolean isP1) {
		List<Consumer<GameContext>> scheduled = isP1 ? scheduledForP1EndTurn : scheduledForP2EndTurn;
		if (endOfTurnEffects.isEmpty() && scheduled.isEmpty()) return;
		GameContext ctx = buildGameContext(isP1);
		if (!endOfTurnEffects.isEmpty()) {
			List<Consumer<GameContext>> pending = new ArrayList<>(endOfTurnEffects);
			endOfTurnEffects.clear();
			pending.forEach(e -> e.accept(ctx));
		}
		if (!scheduled.isEmpty()) {
			List<Consumer<GameContext>> pending = new ArrayList<>(scheduled);
			scheduled.clear();
			pending.forEach(e -> e.accept(ctx));
		}
	}

	// -------------------------------------------------------------------------
	// Damage modifier helpers
	// -------------------------------------------------------------------------

	static CardData.Trait traitFromName(String name) {
		return switch (name.trim().toLowerCase().replace(" ", "_").replace("-", "_")) {
			case "haste"        -> CardData.Trait.HASTE;
			case "brave"        -> CardData.Trait.BRAVE;
			case "first_strike" -> CardData.Trait.FIRST_STRIKE;
			case "back_attack"  -> CardData.Trait.BACK_ATTACK;
			case "warp"         -> CardData.Trait.WARP;
			default             -> null;
		};
	}

	/** @see DamageResolver#modifyIncomingDamage */
	private int modifyIncomingDamage(boolean isP1, int idx, int rawAmount, boolean fromAbility, boolean unreduced) { return damageResolver.modifyIncomingDamage(isP1, idx, rawAmount, fromAbility, unreduced); }

	/** @see DamageResolver#modifyIncomingDamage */
	int modifyIncomingDamage(boolean isP1, ForwardTarget.CardZone zone, int idx, int rawAmount, boolean fromAbility, boolean unreduced) { return damageResolver.modifyIncomingDamage(isP1, zone, idx, rawAmount, fromAbility, unreduced); }

	/** @see DamageResolver#applyDamageModifierMatch */
	private int applyDamageModifierMatch(Matcher fam, int amount, boolean isP1, ForwardTarget.CardZone zone, int idx, boolean fromAbility, CardData subject) { return damageResolver.applyDamageModifierMatch(fam, amount, isP1, zone, idx, fromAbility, subject); }

	/**
	 * Ability texts granted to {@code target} (a Forward on the given side) by "Each Forward you
	 * control with a [X] Counter on it gains …" grants whose counter it currently carries. Empty
	 * unless {@code target} is a Forward with at least one matching counter and a granting source
	 * is on its controller's field.
	 */
	List<String> counterGrantedAbilities(CardData target, boolean isP1) {
		if (!target.isForward()) return List.of();
		List<String> out = null;
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : fwds)                  out = addCounterGrantedAbilities(src, target, out);
		for (CardData src : bkps) if (src != null) out = addCounterGrantedAbilities(src, target, out);
		for (CardData src : mons)                  out = addCounterGrantedAbilities(src, target, out);
		return out == null ? List.of() : out;
	}

	private List<String> addCounterGrantedAbilities(CardData src, CardData target, List<String> out) {
		if (lostAbilitiesCards.contains(src)) return out;
		for (CounterGrant cg : src.counterGrants()) {
			if (cg.grantedAbilityText() == null) continue;
			// Number 24 20-036H grants only to itself, so the walk over the whole field has to skip
			// every other Forward carrying the same counter.
			if (cg.selfOnly() && src != target) continue;
			if (gameState.getCounters(target, cg.counterName()) < cg.minCount()) continue;
			if (out == null) out = new ArrayList<>();
			out.add(cg.grantedAbilityText());
		}
		return out;
	}

	/** @see DamageResolver#applyFieldWideDamageModifiers */
	private int applyFieldWideDamageModifiers(int amount, CardData damaged, boolean isP1, ForwardTarget.CardZone zone, int idx, boolean fromAbility) { return damageResolver.applyFieldWideDamageModifiers(amount, damaged, isP1, zone, idx, fromAbility); }

	/** @see DamageResolver#applyCasterSideElementSummonDamageBoosts */
	private int applyCasterSideElementSummonDamageBoosts(int amount, boolean targetIsP1) { return damageResolver.applyCasterSideElementSummonDamageBoosts(amount, targetIsP1); }

	/** @see DamageResolver#applyCasterSideElementForwardDamageBoosts */
	private int applyCasterSideElementForwardDamageBoosts(int amount, boolean targetIsP1) { return damageResolver.applyCasterSideElementForwardDamageBoosts(amount, targetIsP1); }

	/** @see DamageResolver#modifyOutgoingCombatDamage */
	int modifyOutgoingCombatDamage(boolean isP1, int idx, int rawAmount, CardData target) { return damageResolver.modifyOutgoingCombatDamage(isP1, idx, rawAmount, target); }

	/** @see DamageResolver#modifyOutgoingCombatDamage */
	int modifyOutgoingCombatDamage(boolean isP1, ForwardTarget.CardZone zone, int idx, int rawAmount, CardData target) { return damageResolver.modifyOutgoingCombatDamage(isP1, zone, idx, rawAmount, target); }

	/**
	 * Returns the CardData of the card acting as a Forward at {@code idx} in {@code zone} on the
	 * given player's side, or {@code null} if the index is out of range or the slot is empty.
	 */
	CardData fieldCombatant(boolean isP1, ForwardTarget.CardZone zone, int idx) {
		switch (zone) {
			case FORWARD -> {
				List<CardData> l = isP1 ? p1ForwardCards : p2ForwardCards;
				return idx >= 0 && idx < l.size() ? l.get(idx) : null;
			}
			case MONSTER -> {
				List<CardData> l = isP1 ? p1MonsterCards : p2MonsterCards;
				return idx >= 0 && idx < l.size() ? l.get(idx) : null;
			}
			case BACKUP -> {
				CardData[] a = isP1 ? p1BackupCards : p2BackupCards;
				return idx >= 0 && idx < a.length ? a[idx] : null;
			}
			default -> { return null; }
		}
	}

	/**
	 * Returns the flat bonus the source adds when it deals damage to a Forward via its own
	 * unconditional "If [self] deals damage to a Forward, the damage increases by N instead."
	 * field ability, gated on the source's damage-zone threshold ("Damage N --"). 0 when absent.
	 * Applies to both combat and ability damage the source deals to a Forward.
	 */
	int selfOutgoingFlatBoostVsForward(CardData source, boolean sourceIsP1) {
		if (source == null || lostAbilitiesCards.contains(source)) return 0;
		int dmgInZone = sourceIsP1 ? gameState.getP1DamageZone().size() : gameState.getP2DamageZone().size();
		int boost = 0;
		for (FieldAbility fa : source.fieldAbilities()) {
			if (fa.damageThreshold() > 0 && dmgInZone < fa.damageThreshold()) continue;
			Matcher m = AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST.matcher(fa.effectText());
			if (m.find() && m.group("card").trim().equalsIgnoreCase(source.name()))
				boost += Integer.parseInt(m.group("amount"));
		}
		return boost;
	}

	int costBasedCombatFlatAdjustments(CardData attacker, CardData target) {
		int adj = 0;
		if (!lostAbilitiesCards.contains(attacker)) {
			for (FieldAbility fa : attacker.fieldAbilities()) {
				Matcher m = AutoAbilityTriggers.FA_OUTGOING_FLAT_BOOST_VS_COST.matcher(fa.effectText());
				if (!m.find()) continue;
				if (!m.group("card").trim().equalsIgnoreCase(attacker.name())) continue;
				if (target.cost() >= Integer.parseInt(m.group("cost"))) {
					int boost = Integer.parseInt(m.group("amount"));
					adj += boost;
					logEntry(attacker.name() + " — damage +" + boost + " vs cost-" + target.cost() + " target");
				}
			}
		}
		if (!lostAbilitiesCards.contains(target)) {
			for (FieldAbility fa : target.fieldAbilities()) {
				Matcher m = AutoAbilityTriggers.FA_INCOMING_REDUCTION_VS_COST.matcher(fa.effectText());
				if (!m.find()) continue;
				if (!m.group("card").trim().equalsIgnoreCase(target.name())) continue;
				if (attacker.cost() >= Integer.parseInt(m.group("cost"))) {
					int reduction = Integer.parseInt(m.group("amount"));
					adj -= reduction;
					logEntry(target.name() + " — damage -" + reduction + " vs cost-" + attacker.cost() + " Forward");
				}
			}
		}
		return adj;
	}

	int friendlyElementForwardCombatBoost(CardData attacker, boolean isP1) {
		int boost = 0;
		// Every row, Monsters included: what is being asked is which of this player's cards print a
		// boost, and a Monster's field ability is as live as anyone else's (Djinn 16-010H).
		for (CardData source : fieldCards(isP1)) {
			// A card stripped of its abilities prints nothing, so it boosts nothing.
			if (lostAbilitiesCards.contains(source)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(source)) {
				Matcher m = AutoAbilityTriggers.FA_ELEMENT_FORWARD_DAMAGE_BOOST.matcher(fa.effectText());
				if (m.find() && AutoAbilityTriggers.elementForwardBoostCovers(m, attacker, this)) {
					int amount = Integer.parseInt(m.group("amount"));
					boost += amount;
					logEntry(source.name() + " — Forward combat damage increased by " + amount);
				}
				// The unfiltered form: every Forward you control, Tulien 21-072H.
				Matcher any = AutoAbilityTriggers.FA_FRIENDLY_FORWARD_BATTLE_DAMAGE_BOOST.matcher(fa.effectText());
				if (any.find()) {
					int amount = Integer.parseInt(any.group("amount"));
					boost += amount;
					logEntry(source.name() + " — Forward battle damage increased by " + amount);
				}
				// The Character-scoped form, Lehftia 21-020C and Iroha 8-004R by Element, Chelinka
				// 7-054L by Category, Rapha 13-082C and Papalymo 5-159S by Card Name. Its Summon arm
				// is meaningless in combat and is simply absent from the match here; the attacker is a
				// combatant and so is a Character for this purpose whatever row it came from.
				Matcher chr = AutoAbilityTriggers.FA_ELEMENT_SUMMON_OR_CHARACTER_DAMAGE_BOOST.matcher(fa.effectText());
				if (chr.matches() && AutoAbilityTriggers.characterArmCovers(chr, attacker, this)) {
					int amount = Integer.parseInt(chr.group("amount"));
					boost += amount;
					logEntry(source.name() + " — " + AutoAbilityTriggers.characterArmLabel(chr)
							+ " combat damage increased by " + amount);
				}
			}
		}
		return boost;
	}

	/**
	 * A card's printed field abilities plus any granted to it until end of turn. Every field-ability
	 * check that a grant should be able to satisfy must read this rather than
	 * {@link CardData#fieldAbilities()} directly.
	 */
	List<FieldAbility> effectiveFieldAbilities(CardData card) {
		List<FieldAbility> granted   = grantedFieldAbilities.get(card);
		List<FieldAbility> permanent = permanentFieldAbilities.get(card);
		List<FieldAbility> gated     = handSizeGatedFieldAbilities(card);
		boolean noGranted   = granted   == null || granted.isEmpty();
		boolean noPermanent = permanent == null || permanent.isEmpty();
		if (noGranted && noPermanent && gated.isEmpty()) return card.fieldAbilities();
		List<FieldAbility> all = new ArrayList<>(card.fieldAbilities());
		if (!noGranted)   all.addAll(granted);
		if (!noPermanent) all.addAll(permanent);
		all.addAll(gated);
		return all;
	}

	/**
	 * The grant a card's own hand-size gate is currently making it — Lakshmi, Lady of Bliss
	 * 14-111R's "If you have 5 or more cards in your hand, Lakshmi, Lady of Bliss gains \"If
	 * Lakshmi, Lady of Bliss is dealt damage, reduce the damage by 2000 instead.\"".
	 *
	 * <p>Read live rather than stored, for the reason {@link #damageThresholdGrantedAutoAbilities}
	 * is: this is the card's own text rather than a grant some effect made, and its condition stops
	 * holding the moment the hand is spent down. What is handed back is the grant sentence with the
	 * gate stripped — the shape an ungated printing of the same effect has (Charlotte 13-023R), so
	 * every reader of a field ability treats the two identically and nothing needs to know a gate
	 * was there.
	 *
	 * <p>Printed abilities only, never {@link #effectiveFieldAbilities}: that method calls this one.
	 * A grant whose remainder no grant parser accepts is dropped rather than published, so a text
	 * nobody can act on cannot masquerade as a live ability.
	 *
	 * <p>The silenced check is {@link #abilitiesStrippedByEffect} rather than full membership of
	 * {@link #lostAbilitiesCards}, because that membership is computed by reading the opposing
	 * field's abilities — through the very method that calls this one.
	 */
	private List<FieldAbility> handSizeGatedFieldAbilities(CardData card) {
		if (abilitiesStrippedByEffect(card)) return List.of();
		List<FieldAbility> out = null;
		for (FieldAbility fa : card.fieldAbilities()) {
			CardData.MinHandSizeGatedGrant gate =
					CardData.parseMinHandSizeGatedGrant(fa.effectText());
			if (gate == null) continue;
			Boolean side = fieldSideOf(card);
			if (side == null) return out == null ? List.of() : out;
			if ((side ? gameState.getP1Hand() : gameState.getP2Hand()).size() < gate.minCards()) continue;
			if (CardData.parseSelfGainsQuotedGrant(gate.remainder(), card.name()) == null) continue;
			if (out == null) out = new ArrayList<>();
			out.add(new FieldAbility(gate.remainder(), fa.damageThreshold()));
		}
		return out == null ? List.of() : out;
	}

	/**
	 * A card's printed auto abilities plus any granted to it by an effect that outlasts the turn.
	 * Every trigger dispatcher reads this rather than {@link CardData#autoAbilities()} directly, so
	 * a granted trigger fires on exactly the same events as a printed one.
	 */
	List<AutoAbility> effectiveAutoAbilities(CardData card) {
		List<AutoAbility> granted   = grantedAutoAbilities.get(card);
		List<AutoAbility> selfGrant = damageThresholdGrantedAutoAbilities(card);
		boolean hasGranted = granted != null && !granted.isEmpty();
		if (!hasGranted && selfGrant.isEmpty()) return card.autoAbilities();
		List<AutoAbility> all = new ArrayList<>(card.autoAbilities());
		if (hasGranted) all.addAll(granted);
		all.addAll(selfGrant);
		return all;
	}

	/**
	 * Abilities a card's own conditional field ability is currently handing it — Yumcax 18-067C's
	 * "Damage 3 -- Yumcax gains Brave and \"When Yumcax is put from the field into the Break Zone,
	 * draw 1 card.\"".
	 *
	 * <p>Read live rather than stored, because the condition can stop holding: unlike
	 * {@link #grantedAutoAbilities}, which records a grant some other effect made, this one is a
	 * property of the card's own text and has to be re-evaluated every time the abilities are asked
	 * for.
	 *
	 * <p>The damage zone consulted is the card's controller's, and falls back to its owner's when
	 * the card is no longer on the field. That fallback is what makes the grant survive its own
	 * trigger: a "put from the field into the Break Zone" ability is asked for <em>after</em> the
	 * card has left, so a field-position lookup alone would find nothing and the ability would
	 * never fire.
	 */
	private List<AutoAbility> damageThresholdGrantedAutoAbilities(CardData card) {
		if (lostAbilitiesCards.contains(card)) return List.of();
		Boolean side = fieldSideOf(card);
		if (side == null) side = gameState.getIdentity().get(card);
		if (side == null) return List.of();
		int dmg = (side ? gameState.getP1DamageZone() : gameState.getP2DamageZone()).size();
		List<AutoAbility> out = null;
		for (FieldAbility fa : card.fieldAbilities()) {
			if (fa.damageThreshold() > 0 && dmg < fa.damageThreshold()) continue;
			// Machina 15-017H gates the same kind of grant on the board instead of on damage.
			// Re-read every lookup, because the Forward count moves during the turn.
			String text = fa.effectText();
			CardData.MaxForwardsGatedGrant gate = CardData.parseMaxForwardsGatedGrant(text);
			if (gate != null) {
				if (forwardCount(side) > gate.maxForwards()) continue;
				text = gate.remainder();
			}
			CardData.SelfGainsQuotedGrant g =
					CardData.parseSelfGainsQuotedGrant(text, card.name());
			if (g == null || g.abilityTexts().isEmpty()) continue;
			if (out == null) out = new ArrayList<>();
			for (String t : g.abilityTexts()) out.addAll(CardData.parseAutoAbilities(t));
		}
		return out == null ? List.of() : out;
	}

	/** Drops everything granted to {@code card} by an outlasts-the-turn effect, as it leaves the field. */
	void clearPermanentGrants(CardData card) {
		// The card is losing the stores below wholesale, so any warden-held grant standing on it has
		// nothing left to withdraw; dropping the record here is what keeps the list from outliving
		// the grant it describes.
		wardenHeldGrants.removeIf(g -> g.grantee() == card);
		grantedAutoAbilities.remove(card);
		permanentMaxAttacks.remove(card);
		permanentPowerBoost.remove(card);
		permanentTraits.remove(card);
		permanentFieldAbilities.remove(card);
		permanentCannotBeChosenBySummons.remove(card);
		permanentCannotBeChosenByAbilities.remove(card);
		permanentMustAttackOncePerTurn.remove(card);
		// A permanent "power becomes N" lives in the same map as the end-of-turn kind, which has
		// its own removal hook; dropping the key here is what bounds the permanent one.
		basePowerOverrides.remove(card);
		// 17-128L Maria's two halves, on the same footing: both are continuous effects on this
		// copy's stay on the field and end with it, and neither registers an end-of-turn removal
		// that would otherwise bound them. Dropping the promotion's backing entries too — the
		// zone-specific paths already do it where they run, and this is the one hook every
		// departure goes through.
		backupPermanentForwards.remove(card);
		p1BackupTempForwardPower.remove(card); p2BackupTempForwardPower.remove(card);
		lostAbilitiesCards.remove(card);
	}

	/**
	 * How many attacks {@code card}'s multi-attack permission allows it in a turn — printed,
	 * granted for the turn, or granted for good — and 1 when it has none. The strongest permission
	 * wins rather than the sum: two sources of "can attack twice" still mean twice.
	 */
	int maxAttacksPerTurn(CardData card) {
		int max = card.maxAttacksPerTurn();
		Integer granted = grantedMaxAttacks.get(card);
		if (granted != null)   max = Math.max(max, granted);
		Integer permanent = permanentMaxAttacks.get(card);
		if (permanent != null) max = Math.max(max, permanent);
		max = Math.max(max, attacksFromOwnDamage(card));
		max = Math.max(max, attacksFromHandSizeGrant(card));
		max = Math.max(max, attacksFromDamageThresholdGrant(card));
		max = Math.max(max, attacksFromOppDullCharsGrant(card));
		max = Math.max(max, attacksFromNamedGrant(card));
		return max;
	}

	/**
	 * Prompto 27-068R: "The Card Name Noctis Forward you control gains Brave and \"This Forward can
	 * attack twice per turn.\"" — the only multi-attack permission in the corpus handed out by a
	 * card other than the one that attacks, so it is read off the controller's field rather than
	 * off {@code card}'s own text. The Brave granted in the same breath travels the ordinary route,
	 * through {@code FieldGrantCalculator}, off the {@link FieldPowerGrant} the same sentence
	 * produces.
	 *
	 * <p>Returns 0 when nothing on the field grants it, so it never lowers an existing permission.
	 */
	private int attacksFromNamedGrant(CardData card) {
		Boolean side = fieldSideOf(card);
		if (side == null) return 0;
		int best = 0;
		for (CardData src : fieldCards(side)) {
			if (src == null || lostAbilitiesCards.contains(src)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(src)) {
				CardData.NamedMaxAttacksGrant g = CardData.parseNamedMaxAttacksGrant(fa.effectText());
				if (g != null && CardFilters.meetsCardNameFilter(card, g.cardName()))
					best = Math.max(best, g.maxAttacks());
			}
		}
		return best;
	}

	/**
	 * Firion 21-099H: "If your opponent controls 4 or more dull Characters, Firion gains +5000
	 * power, Brave and \"Firion can attack twice in the same turn.\"" The permission is conditional
	 * on an opposing board that changes during the turn, so — like {@link #attacksFromHandSizeGrant}
	 * — it is read here rather than frozen into {@link CardData#maxAttacksPerTurn()}. The power and
	 * the Brave granted in the same breath travel their ordinary routes, through
	 * {@code fieldBoostContribution} and {@code FieldGrantCalculator}, each stripping the same gate.
	 *
	 * <p>Returns 0 when the card has no such ability, so it never lowers an existing permission.
	 */
	private int attacksFromOppDullCharsGrant(CardData card) {
		if (lostAbilitiesCards.contains(card)) return 0;
		Boolean side = fieldSideOf(card);
		if (side == null) return 0;
		int best = 0;
		for (FieldAbility fa : effectiveFieldAbilities(card)) {
			String grantText = oppDullCharsGrantRemainder(fa.effectText(), side);
			// Unchanged text carries no gate, and this reader owns only the gated printing.
			if (grantText == null || grantText.equals(fa.effectText())) continue;
			CardData.SelfGainsQuotedGrant g =
					CardData.parseSelfGainsQuotedGrant(grantText, card.name());
			if (g != null) best = Math.max(best, g.maxAttacks());
		}
		return best;
	}

	/**
	 * Gilgamesh 18-074L: "Damage 3 -- Gilgamesh gains Brave and \"Gilgamesh can attack twice in the
	 * same turn.\"" The permission is conditional on a damage count that changes during the game,
	 * so — like {@link #attacksFromHandSizeGrant} — it is read here rather than frozen into
	 * {@link CardData#maxAttacksPerTurn()}. The Brave granted in the same breath travels the
	 * ordinary route, through {@code FieldGrantCalculator}.
	 *
	 * <p>Returns 0 when the card has no such ability, so it never lowers an existing permission.
	 */
	private int attacksFromDamageThresholdGrant(CardData card) {
		if (lostAbilitiesCards.contains(card)) return 0;
		Boolean side = fieldSideOf(card);
		if (side == null) return 0;
		int dmg = (side ? gameState.getP1DamageZone() : gameState.getP2DamageZone()).size();
		int best = 0;
		for (FieldAbility fa : effectiveFieldAbilities(card)) {
			if (fa.damageThreshold() > 0 && dmg < fa.damageThreshold()) continue;
			CardData.SelfGainsQuotedGrant g =
					CardData.parseSelfGainsQuotedGrant(fa.effectText(), card.name());
			if (g != null) best = Math.max(best, g.maxAttacks());
		}
		return best;
	}

	/**
	 * Squall 16-011L: "If both you and your opponent have no cards in hand, Squall gains First
	 * Strike, Brave and \"Squall can attack twice in the same turn.\"" The permission is conditional
	 * on hand sizes that change during the turn, so — like {@link #attacksFromOwnDamage} — it is
	 * read here rather than frozen into {@link CardData#maxAttacksPerTurn()}. The traits granted in
	 * the same breath travel the ordinary route, through {@code FieldGrantCalculator}.
	 *
	 * <p>Returns 0 when the card has no such ability, so it never lowers an existing permission.
	 */
	private int attacksFromHandSizeGrant(CardData card) {
		if (lostAbilitiesCards.contains(card)) return 0;
		Boolean side = fieldSideOf(card);
		if (side == null) return 0;
		int yourHand  = (side ? gameState.getP1Hand() : gameState.getP2Hand()).size();
		int theirHand = (side ? gameState.getP2Hand() : gameState.getP1Hand()).size();
		int best = 0;
		for (FieldAbility fa : effectiveFieldAbilities(card)) {
			CardData.HandSizeSelfGrant g = CardData.parseHandSizeSelfGrant(fa.effectText(), card.name());
			if (g != null && g.conditionMet(yourHand, theirHand)) best = Math.max(best, g.maxAttacks());
		}
		return best;
	}

	/**
	 * The Forward on {@code isP1}'s side that takes {@code damaged}'s damage in its place, or
	 * {@code null} when none does — Daisy 18-060H ("If a Forward you control other than Daisy is
	 * dealt damage, the damage is dealt to Daisy instead.") and Tidus 26-112H, whose filter is a
	 * card name rather than an exclusion.
	 *
	 * <p>A stand-in never covers itself: Daisy's own text excludes her by name and Tidus is not
	 * named Yuna, but the identity check is what guarantees a redirect cannot loop back onto the
	 * card that issued it regardless of how the filter is written.
	 *
	 * <p>When two stand-ins both cover the damaged Forward the rules would let the controller pick;
	 * this takes the first found. No pair in the corpus creates the choice on one side of the field.
	 */
	CardData damageRedirectStandIn(CardData damaged, boolean isP1) {
		if (damaged == null) return null;
		for (CardData src : isP1 ? p1ForwardCards : p2ForwardCards) {
			if (src == damaged || lostAbilitiesCards.contains(src)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(src)) {
				CardData.DamageRedirectGrant g =
						CardData.parseDamageRedirectGrant(fa.effectText(), src.name());
				if (g != null && g.coversCard(damaged)) return src;
			}
		}
		return null;
	}

	/**
	 * Tidus 29-105L: "can attack as many times in the same turn as the points of damage you have
	 * received." The allowance is the controller's damage-zone size and moves during the turn, so
	 * it is read here rather than parsed into {@link CardData#maxAttacksPerTurn()}. Returns 0 when
	 * the card has no such ability, so it never lowers the permission the caller already has.
	 */
	private int attacksFromOwnDamage(CardData card) {
		if (lostAbilitiesCards.contains(card)) return 0;
		Boolean side = fieldSideOf(card);
		if (side == null) return 0;
		int damage = side ? gameState.getP1DamageZone().size() : gameState.getP2DamageZone().size();
		int best = 0;
		for (FieldAbility fa : card.fieldAbilities()) {
			if (!CardData.parseAttacksPerOwnDamage(fa.effectText(), card.name())) continue;
			if (fa.damageThreshold() > 0 && damage < fa.damageThreshold()) continue;
			best = Math.max(best, damage);
		}
		return best;
	}

	/**
	 * Which side {@code card} is on as a field card, by identity, or {@code null} when it is on
	 * neither. Identity rather than equality: {@link CardData} is a record, so two copies of the
	 * same printing — one per player — are equal but are different cards on the board.
	 *
	 * <p>All three rows are searched, not just the Forwards. Kimahri 1-103C needs it as a Backup,
	 * and the damage-threshold caller is better for it too: a Backup used to miss and fall through
	 * to the ownership map, which answers who owns the card rather than who controls it.
	 */
	/**
	 * How many dull Characters the given player's opponent controls — the board condition Firion
	 * 21-099H's grant is gated on.
	 *
	 * <p>"Characters" spans all three opposing rows: a dull Backup or Monster counts as readily as
	 * a dull Forward, which is the same reading {@code ScalingSelfPowerBoost}'s opponent-Character
	 * source already takes of the word.
	 */
	int opposingDullCharacterCount(boolean isP1) {
		boolean opp = !isP1;
		int n = 0;
		List<CardState> fwdStates = opp ? p1ForwardStates : p2ForwardStates;
		for (CardState s : fwdStates) if (s == CardState.DULL) n++;
		CardState[] bkpStates = opp ? p1BackupStates : p2BackupStates;
		CardData[]  bkpCards  = opp ? p1BackupCards  : p2BackupCards;
		for (int i = 0; i < bkpStates.length; i++)
			if (bkpCards[i] != null && bkpStates[i] == CardState.DULL) n++;
		List<CardState> monStates = opp ? p1MonsterStates : p2MonsterStates;
		for (CardState s : monStates) if (s == CardState.DULL) n++;
		return n;
	}

	/**
	 * The grant half of a {@code parseOppDullCharsGatedGrant} sentence once its board condition is
	 * known to hold, or {@code null} when {@code text} carries no such gate or the gate is shut.
	 *
	 * <p>Shared by the three readers that each take one half of Firion's grant — the power sum, the
	 * trait collector and the multi-attack permission — so none of them can disagree about whether
	 * the condition is met. Text that carries no gate is returned unchanged, so a caller can pipe
	 * every field ability through this before handing it to the parser that owns it.
	 */
	String oppDullCharsGrantRemainder(String text, boolean isP1) {
		CardData.OppDullCharsGatedGrant gate = CardData.parseOppDullCharsGatedGrant(text);
		if (gate == null) return text;
		return opposingDullCharacterCount(isP1) >= gate.minDullCharacters() ? gate.remainder() : null;
	}

	/**
	 * The Priming cost {@code card} would actually pay right now — its printed one, or the cheaper
	 * one a discount on the card itself currently allows (Dion 29-106H's "If you control 7 or more
	 * Characters, Dion can prime to pay 《Water》《1》 instead").
	 *
	 * <p>Every reader of a Priming cost goes through here — the context-menu gate, the payment
	 * dialog and the payment itself — so none of them can offer a price another would refuse.
	 * Re-read per call: the Character count moves during the turn, and a discount that lapsed
	 * between opening the menu and confirming the dialog must lapse for the payment too.
	 *
	 * <p>A card stripped of its abilities prints no discount, so it pays the printed cost.
	 */
	List<String> effectivePrimingCost(CardData card, boolean isP1) {
		if (card == null) return List.of();
		if (lostAbilitiesCards.contains(card)) return card.primingCost();
		for (FieldAbility fa : effectiveFieldAbilities(card)) {
			CardData.PrimingCostDiscount discount =
					CardData.parsePrimingCostDiscount(fa.effectText(), card.name());
			if (discount == null) continue;
			if (fieldCards(isP1).size() < discount.minCharacters()) continue;
			return discount.cost();
		}
		return card.primingCost();
	}

	/** Which side of the field {@code card} is on, or {@code null} when it is on neither. */
	Boolean fieldSideOf(CardData card) {
		for (CardData c : p1ForwardCards) if (c == card) return Boolean.TRUE;
		for (CardData c : p2ForwardCards) if (c == card) return Boolean.FALSE;
		for (CardData c : p1BackupCards)  if (c == card) return Boolean.TRUE;
		for (CardData c : p2BackupCards)  if (c == card) return Boolean.FALSE;
		for (CardData c : p1MonsterCards) if (c == card) return Boolean.TRUE;
		for (CardData c : p2MonsterCards) if (c == card) return Boolean.FALSE;
		return null;
	}

	/**
	 * Total attack declarations {@code card} may make this turn: its permission's allowance plus any
	 * one-shot "can attack once more this turn" grants, which do stack.
	 */
	int attacksAllowed(CardData card) {
		return maxAttacksPerTurn(card) + extraAttacksThisTurn.getOrDefault(card, 0);
	}

	/** True when {@code card} has an attack declaration left this turn. */
	boolean hasAttackRemaining(CardData card) {
		return attacksMadeThisTurn.getOrDefault(card, 0) < attacksAllowed(card);
	}

	/** Records one attack declaration by {@code card}. */
	void recordAttackDeclared(CardData card) {
		attacksMadeThisTurn.merge(card, 1, Integer::sum);
	}

	/** Adds one one-shot "can attack once more this turn" grant to {@code card}. */
	void grantExtraAttack(CardData card) {
		extraAttacksThisTurn.merge(card, 1, Integer::sum);
	}

	int fieldAbilityCombatOutgoingMult(CardData attacker, CardData target) {
		int mult = 1;
		// A card with no abilities doubles nothing. Asked here as well as on the ability-damage
		// path, so the two readers of one doubler cannot disagree about whether it is live.
		if (attacker == null || lostAbilitiesCards.contains(attacker)) return mult;
		// A "Damage N --" gate belongs to the printing, and this reader holds the FieldAbility that
		// carries it — read the same way DamageResolver.sourceHasOutgoingDmgToOpponentDoubler reads
		// it, so the two halves of one doubler cannot disagree about whether it is live. Kefka
		// 23-004R doubles nothing until his controller has taken 5.
		Boolean side = fieldSideOf(attacker);
		int dmg = side == null ? 0
				: (side ? gameState.getP1DamageZone() : gameState.getP2DamageZone()).size();
		for (FieldAbility fa : effectiveFieldAbilities(attacker)) {
			if (fa.damageThreshold() > 0 && dmg < fa.damageThreshold()) continue;
			Matcher m = AutoAbilityTriggers.FA_DOUBLE_DAMAGE_VS_COST_THRESHOLD.matcher(fa.effectText());
			if (m.find() && m.group("name").trim().equalsIgnoreCase(attacker.name())
					&& target.cost() >= Integer.parseInt(m.group("cost")))
				mult *= 2;
			// Kefka 23-004R prints his doubler inside a self grant, so the clause list is what is
			// scanned rather than the sentence alone.
			for (String clause : CardData.selfPassiveClauses(fa.effectText(), attacker.name())) {
				m = AutoAbilityTriggers.FA_OUTGOING_DAMAGE_DOUBLER.matcher(clause);
				if (m.find() && m.group("card").trim().equalsIgnoreCase(attacker.name())
						&& m.group("target").toLowerCase().contains("forward"))
					mult *= 2;
			}
		}
		return mult;
	}

	/** @see DamageResolver#sourceHasOutgoingDmgToOpponentDoubler */
	boolean sourceHasOutgoingDmgToOpponentDoubler(CardData attacker) { return damageResolver.sourceHasOutgoingDmgToOpponentDoubler(attacker); }

	/** @see DamageResolver#abilityDamageToOpponentOverride */
	Integer abilityDamageToOpponentOverride(CardData attacker) { return damageResolver.abilityDamageToOpponentOverride(attacker); }

	/** @see DamageResolver#combatDamagePointsToOpponent */
	int combatDamagePointsToOpponent(CardData attacker) { return damageResolver.combatDamagePointsToOpponent(attacker); }

	/** @see DamageResolver#dealCombatDamageToOpponent */
	private void dealCombatDamageToOpponent(CardData attacker, boolean attackerIsP1, Runnable afterDamage) { damageResolver.dealCombatDamageToOpponent(attacker, attackerIsP1, afterDamage); }

	/** @see DamageResolver#dealOpponentDamagePoints */
	private void dealOpponentDamagePoints(CardData attacker, boolean attackerIsP1, int remaining, Runnable afterDamage) { damageResolver.dealOpponentDamagePoints(attacker, attackerIsP1, remaining, afterDamage); }

	/** @see DamageResolver#applyDamageToMonster */
	void applyDamageToMonster(boolean isP1, int idx, int amount) { damageResolver.applyDamageToMonster(isP1, idx, amount); }

	/** @see DamageResolver#applyDamageToForward */
	void applyDamageToForward(boolean isP1, int idx, int rawAmount, boolean fromAbility, boolean unreduced) { damageResolver.applyDamageToForward(isP1, idx, rawAmount, fromAbility, unreduced); }

	/**
	 * Rule process: breaks every Forward whose effective power has dropped to 0 or less, or whose
	 * already-accumulated damage now meets or exceeds that power.
	 * <p>
	 * {@link #applyDamageToForward} only compares damage against power at the moment damage lands,
	 * so a Forward that becomes lethally damaged because its <em>power fell</em> is missed entirely.
	 * Call this after anything that can lower effective power: direct reductions, "power becomes N"
	 * overrides, and field-grant changes caused by a buffing card leaving the field.
	 */
	void enforceForwardBreakRuleProcess() {
		// Re-entrant: breaking a Forward fires its leaves-the-field triggers, which land back here.
		// Bail out and let the in-flight sweep's fixpoint loop absorb the cascade — recursing would
		// re-enter the lists while a break is still unwinding.
		if (enforcingForwardBreakRuleProcess) return;
		enforcingForwardBreakRuleProcess = true;
		try {
			// Breaking one Forward can withdraw a field power grant and push another below its
			// damage, so repeat until a full pass breaks nothing. The bound is a safety net only.
			for (int pass = 0; pass < 16; pass++) {
				// Non-short-circuiting: both sides must be swept every pass.
				boolean brokeAny = sweepForwardBreakRuleProcess(true) | sweepForwardBreakRuleProcess(false);
				if (!brokeAny) return;
			}
		} finally {
			enforcingForwardBreakRuleProcess = false;
		}
	}

	/**
	 * Breaks {@code card} if it is carrying Vallaide 22-020R's "When this Forward is dealt damage,
	 * break this Forward." grant, and reports whether it did.
	 *
	 * <p>Called from every path that deals a Forward damage — ability/Summon damage and both combat
	 * resolvers — because the grant says "is dealt damage" and does not care where the damage came
	 * from or whether it was lethal. Callers pass the amount so a blow reduced to nothing does not
	 * trigger it.
	 *
	 * <p>"Cannot be broken" saves the Forward here as it does against lethal damage: this is a
	 * break, not the rule process that removes a Forward at 0 power.
	 */
	boolean breakOnDealtDamageGrant(boolean isP1, ForwardTarget.CardZone zone, int idx,
			CardData card, int amount) {
		if (amount <= 0 || card == null || !breakWhenDealtDamageSet.contains(card)) return false;
		if (fieldForwardTrait(isP1, zone, idx, CardData.Trait.CANNOT_BE_BROKEN)) {
			logEntry((isP1 ? "" : "[P2] ") + card.name()
					+ " was dealt damage but cannot be broken");
			return false;
		}
		logEntry((isP1 ? "" : "[P2] ") + card.name()
				+ " — dealt damage, so it is broken (until end of turn)");
		breakFieldCard(isP1, zone, idx);
		return true;
	}

	/** Guards {@link #enforceForwardBreakRuleProcess} against re-entry from leaves-the-field triggers. */
	private boolean enforcingForwardBreakRuleProcess;

	/** One rule-process pass over {@code isP1}'s Forwards. Returns true if anything was broken. */
	private boolean sweepForwardBreakRuleProcess(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards  : p2ForwardCards;
		List<Integer>  dmgs = isP1 ? p1ForwardDamage : p2ForwardDamage;
		boolean brokeAny = false;
		// Walk downward so breaking a Forward cannot shift the indices still to be checked.
		for (int idx = Math.min(fwds.size(), dmgs.size()) - 1; idx >= 0; idx--) {
			int effPow = isP1 ? effectiveP1ForwardPower(idx) : effectiveP2ForwardPower(idx);
			boolean zeroPower = effPow <= 0;
			boolean lethalDmg = effPow > 0 && dmgs.get(idx) >= effPow;
			if (!zeroPower && !lethalDmg) continue;
			CardData fwd = fwds.get(idx);
			// "Cannot be broken" only saves against lethal *damage* (matching applyDamageToForward:
			// the Forward rides it out and its damage clears at end of turn). A Forward at 0 or less
			// power is removed by a rule process — it is moved to the Break Zone without being
			// "broken", so the shield does not apply. zeroPower and lethalDmg are mutually exclusive.
			if (lethalDmg && effectiveHasTrait(isP1, idx, CardData.Trait.CANNOT_BE_BROKEN)) {
				logEntry((isP1 ? "" : "[P2] ") + fwd.name() + " survives lethal damage (cannot be broken)");
				if (isP1) refreshP1ForwardSlot(idx); else refreshP2ForwardSlot(idx);
				continue;
			}
			logEntry((isP1 ? "" : "[P2] ") + fwd.name()
					+ (zeroPower ? " at 0 or less power → Break Zone (rule process)"
					             : " has " + dmgs.get(idx) + " damage vs " + effPow + " power → Break Zone"));
			pendingCostBreakDestLabel = isP1 ? p1BreakLabel : p2BreakLabel;
			if (isP1) breakP1Forward(idx); else breakP2Forward(idx);
			brokeAny = true;
		}
		return brokeAny;
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
	/** @see DamageResolver#fireBreaktouchForDamage */
	private boolean fireBreaktouchForDamage(CardData source, boolean sourceIsP1, boolean damagedIsP1, int damagedIdx, int amount) { return damageResolver.fireBreaktouchForDamage(source, sourceIsP1, damagedIsP1, damagedIdx, amount); }

	/** @see DamageResolver#fireBreaktouchForDamage */
	boolean fireBreaktouchForDamage(CardData source, boolean sourceIsP1, boolean damagedIsP1, ForwardTarget.CardZone damagedZone, int damagedIdx, int amount) { return damageResolver.fireBreaktouchForDamage(source, sourceIsP1, damagedIsP1, damagedZone, damagedIdx, amount); }

	/**
	 * Returns true when a forward at {@code (cardIsP1, cardIdx)} is the current blocker
	 * whose attacker satisfies the blocking-target filter encoded in {@code condition}.
	 */
	boolean meetsBlockingTargetFilter(boolean cardIsP1, int cardIdx, String condition) {
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
					&& p1BlockedByAttacker.hasJob(targetJob))
				|| (!cardIsP1 && cardIdx == p2BlockingIdx && p2BlockedByAttacker != null
					&& p2BlockedByAttacker.hasJob(targetJob));
		}
		return false;
	}

	static javax.swing.border.Border createCardGlowBorder(Color color) {
		return CardAnimation.createCardGlowBorder(color);
	}

	/**
	 * Enforces the uniqueness rule after {@code incoming} has entered the field.
	 * Every card on that side (including {@code incoming} itself) whose name overlaps
	 * is sent directly to the Break Zone — the rule takes both copies, it does not let
	 * the owner keep one.  This does NOT count as "breaking", so
	 * "cannot be broken" protection is bypassed and break-zone auto-abilities do not
	 * fire.  "Leaves field" auto-abilities still fire.  Multicards are exempt.
	 *
	 * <p>Call this AFTER {@code incoming} has been added to the field and its
	 * enter-the-field abilities have been queued, so ETF effects resolve first.
	 * Returns {@code true} if any conflict was found.
	 */
	private boolean sendToBreakZoneByUniquenessRule(CardData incoming, boolean isP1) {
		if (incoming.multicard()) return false;
		if (isMultiNameExceptionActive(incoming.name(), isP1)) return false;
		// Every copy goes, the arriving one included, so the conflict has to be settled up front:
		// the loops below deliberately match incoming as well, and on their own they cannot tell
		// "a second copy is on the field" from "incoming matched itself".
		if (!hasUniquenessConflict(incoming, isP1)) return false;
		if (isP1) {
			// P1 forwards
			for (int i = p1ForwardCards.size() - 1; i >= 0; i--) {
				CardData c = p1ForwardCards.get(i);
				if (!cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] " + c.name() + " — sent to Break Zone");
				animateUniquenessSlide(p1ForwardLabels.get(i), true);
				CardData top = p1ForwardPrimedTop.get(i);
				if (top != null) {
					addToBreakZone(c, true);
					addToBreakZone(top);
					gameState.getP1BreakZone().remove(top);
					gameState.addToPermanentRfp(top);
				} else {
					addToBreakZone(c, true);
				}
				removeP1ForwardSlotState(i);
				stolenForwards.remove(c);
				checkAndRestoreStolenOnLeave(c.name());
				refreshP1BreakLabel();
				rebuildP1ForwardPanel();
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, true);
			}
			// P1 backups
			for (int i = 0; i < p1BackupCards.length; i++) {
				CardData c = p1BackupCards[i];
				if (c == null || !cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] " + c.name() + " — sent to Break Zone");
				animateUniquenessSlide(p1BackupLabels[i], true);
				addToBreakZone(c, true);
				clearBackupSlotState(i, true);
				refreshP1BackupSlot(i); refreshP1BreakLabel();
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, true);
			}
			// P1 monsters
			for (int i = p1MonsterCards.size() - 1; i >= 0; i--) {
				CardData c = p1MonsterCards.get(i);
				if (!cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] " + c.name() + " — sent to Break Zone");
				addToBreakZone(c, true);
				p1MonsterTempForwardPower.remove(c);
				p1MonsterCards.remove(i); p1MonsterStates.remove(i);
				p1MonsterFrozen.remove(i); p1MonsterPlayedOnTurn.remove(i);
				p1MonsterDamage.remove(i);
				p1MonsterUrls.remove(i);
				JLabel lbl = p1MonsterLabels.remove(i);
				animateUniquenessSlide(lbl, true);
				p1MonsterPanel.remove(lbl); p1MonsterPanel.revalidate(); p1MonsterPanel.repaint();
				refreshP1BreakLabel();
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, true);
			}
		} else {
			// P2 forwards
			for (int i = p2ForwardCards.size() - 1; i >= 0; i--) {
				CardData c = p2ForwardCards.get(i);
				if (!cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] [P2] " + c.name() + " — sent to Break Zone");
				animateUniquenessSlide(p2ForwardLabels.get(i), false);
				addToBreakZone(c, true);
				removeP2ForwardSlotState(i);
				refreshP2BreakLabel();
				rebuildP2ForwardPanel();
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
			}
			// P2 backups
			for (int i = 0; i < p2BackupCards.length; i++) {
				CardData c = p2BackupCards[i];
				if (c == null || !cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] [P2] " + c.name() + " — sent to Break Zone");
				animateUniquenessSlide(p2BackupLabels[i], false);
				addToBreakZone(c, true);
				clearBackupSlotState(i, false);
				refreshP2BackupSlot(i); refreshP2BreakLabel();
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
			}
			// P2 monsters
			for (int i = p2MonsterCards.size() - 1; i >= 0; i--) {
				CardData c = p2MonsterCards.get(i);
				if (!cardNamesOverlap(incoming, c)) continue;
				logEntry("[Uniqueness] [P2] " + c.name() + " — sent to Break Zone");
				addToBreakZone(c, true);
				p2MonsterTempForwardPower.remove(c);
				p2MonsterCards.remove(i); p2MonsterStates.remove(i);
				p2MonsterFrozen.remove(i); p2MonsterPlayedOnTurn.remove(i);
				p2MonsterDamage.remove(i);
				p2MonsterUrls.remove(i);
				JLabel lbl = p2MonsterLabels.remove(i);
				animateUniquenessSlide(lbl, false);
				if (p2MonsterPanel != null) { p2MonsterPanel.remove(lbl); p2MonsterPanel.revalidate(); p2MonsterPanel.repaint(); }
				refreshP2BreakLabel();
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
			}
		}
		return true;
	}

	/**
	 * Whether {@code isP1} already has a card sharing {@code incoming}'s name, {@code incoming}
	 * itself excluded. Separate from the rule process because that process breaks every copy: once
	 * it is running it has no way to distinguish the second copy from the first.
	 */
	private boolean hasUniquenessConflict(CardData incoming, boolean isP1) {
		for (CardData c : isP1 ? p1ForwardCards : p2ForwardCards)
			if (c != incoming && cardNamesOverlap(incoming, c)) return true;
		for (CardData c : isP1 ? p1BackupCards : p2BackupCards)
			if (c != null && c != incoming && cardNamesOverlap(incoming, c)) return true;
		for (CardData c : isP1 ? p1MonsterCards : p2MonsterCards)
			if (c != incoming && cardNamesOverlap(incoming, c)) return true;
		return false;
	}

	/**
	 * Empties one side's Backup slot {@code idx} — the card, its art, its state flags and the four
	 * per-card side maps keyed on it — without implying anything about where the card went. Callers
	 * move it themselves; this is only the slot bookkeeping, so it carries no logging or triggers.
	 *
	 * <p>The art URL matters as much as the card reference: {@code refreshP1BackupSlot} treats a
	 * null URL as "slot empty" and returns early, so a slot whose card is nulled while its URL
	 * survives goes on painting the departed card indefinitely.
	 */
	private void clearBackupSlotState(int idx, boolean isP1) {
		CardData c = isP1 ? p1BackupCards[idx] : p2BackupCards[idx];
		if (isP1) {
			if (c != null) {
				p1BackupTempForwardPower.remove(c); p1BackupForwardBoost.remove(c);
				p1BackupTempTraits.remove(c);       p1BackupForwardDamage.remove(c);
			}
			if (p1BackupAttackIdx == idx) p1BackupAttackIdx = -1;
			p1BackupCards[idx]  = null;
			p1BackupUrls[idx]   = null;
			p1BackupStates[idx] = CardState.ACTIVE;
			p1BackupFrozen[idx] = false;
			if (p1BackupLabels[idx] != null) { p1BackupLabels[idx].setIcon(null); p1BackupLabels[idx].setText(null); }
		} else {
			if (c != null) {
				p2BackupTempForwardPower.remove(c); p2BackupForwardBoost.remove(c);
				p2BackupTempTraits.remove(c);       p2BackupForwardDamage.remove(c);
			}
			if (p2BackupAttackIdx == idx) p2BackupAttackIdx = -1;
			p2BackupCards[idx]  = null;
			p2BackupUrls[idx]   = null;
			p2BackupStates[idx] = CardState.ACTIVE;
			p2BackupFrozen[idx] = false;
			if (p2BackupLabels[idx] != null) { p2BackupLabels[idx].setIcon(null); p2BackupLabels[idx].setText(null); }
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
	int showNumberSelectDialog(String prompt, int min, int max) {
		return cardPickerDialog.selectNumber(prompt, min, max);
	}

	int showPowerAmountDialog(int maxAmount, String prompt) {
		return cardPickerDialog.selectPowerAmount(maxAmount, prompt);
	}

	List<Integer> showDivideDamageDialog(int damage, String prompt, List<CardData> cards) {
		return cardPickerDialog.selectDamageAmount(damage, prompt, cards);
	}

	/**
	 * Applies {@code action} to each target, highest index first, so that removing or
	 * returning a card does not shift the indices of targets not yet processed.  Zones
	 * are independent lists, so a single descending-index sort is safe across zones.
	 */
	void applyTargetsHighestIndexFirst(List<ForwardTarget> targets, Consumer<ForwardTarget> action) {
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
	List<ForwardTarget> showForwardSelectDialog(
			List<ForwardTarget> eligible, int maxCount, boolean upTo, String title) {
		if (eligible.isEmpty()) { logEntry("Choose: no eligible targets"); return List.of(); }
		if (!upTo && eligible.size() <= maxCount) return List.copyOf(eligible);
		return selectFieldTargetsInPlace(eligible, maxCount, upTo, title);
	}

	/**
	 * Like {@link #showForwardSelectDialog} but bounded by a total-cost budget rather than a
	 * count — Vincent 2-077L's Death Penalty, the one card that spends across its picks.
	 *
	 * <p>Never auto-selects the way the counted form does: an eligible board that happens to fit
	 * inside the budget is still a choice, since taking fewer Forwards is always allowed.
	 */
	List<ForwardTarget> showForwardSelectWithinTotalCostDialog(
			List<ForwardTarget> eligible, int maxTotalCost, String title) {
		if (eligible.isEmpty()) { logEntry("Choose: no eligible targets"); return List.of(); }
		return selectFieldTargetsInPlace(eligible, Integer.MAX_VALUE, true, title, maxTotalCost);
	}

	/** Maps a field {@link ForwardTarget} to its on-screen card label. */
	private JLabel labelForTarget(ForwardTarget t) {
		return switch (t.zone()) {
			case FORWARD -> t.isP1() ? p1ForwardLabels.get(t.idx()) : p2ForwardLabels.get(t.idx());
			case BACKUP  -> t.isP1() ? p1BackupLabels[t.idx()]      : p2BackupLabels[t.idx()];
			case MONSTER -> t.isP1() ? p1MonsterLabels.get(t.idx()) : p2MonsterLabels.get(t.idx());
			default      -> null;
		};
	}

	/** Current {@link CardState} of a field {@link ForwardTarget}, used to glow at the right card bounds. */
	CardState fieldTargetState(ForwardTarget t) {
		return switch (t.zone()) {
			case FORWARD -> t.isP1() ? p1ForwardStates.get(t.idx()) : p2ForwardStates.get(t.idx());
			case BACKUP  -> t.isP1() ? p1BackupStates[t.idx()]      : p2BackupStates[t.idx()];
			case MONSTER -> t.isP1() ? p1MonsterStates.get(t.idx()) : p2MonsterStates.get(t.idx());
			default      -> null;
		};
	}

	/**
	 * Printed cost of the card at a field {@link ForwardTarget}, or 0 when it names no card —
	 * what a budgeted selection spends against. Printed rather than effective on purpose: the
	 * budget wording ("with a total cost of N or less") reads the number on the card, so a cost
	 * reduction that applied while casting it does not make it cheaper to break.
	 */
	int fieldTargetCost(ForwardTarget t) {
		CardData card = autoAbilityTriggers.fieldCardData(t);
		return card == null ? 0 : card.cost();
	}

	private static Color pulseColor(Color base, float t) {
		float f = 0.5f + 0.5f * t;
		return new Color(
				Math.round(base.getRed()   * f),
				Math.round(base.getGreen() * f),
				Math.round(base.getBlue()  * f));
	}

	/**
	 * Border that paints the card-selection glow over the actual card rectangle within a
	 * {@code CARD_H}×{@code CARD_H} slot (matching {@link CardAnimation#renderBackupCard}),
	 * rather than the full label bounds, so the highlight hugs the art.
	 *
	 * <p>The two orientations anchor differently, exactly as {@code renderBackupCard} composites
	 * them: an ACTIVE card is inset by {@link CardAnimation#LEFT_GUTTER} to leave the trait tabs
	 * their strip, while a DULL card spans the full width and is pinned to the bottom instead.
	 */
	private static javax.swing.border.Border cardBoundsGlowBorder(Color color, boolean dull) {
		return new javax.swing.border.AbstractBorder() {
			@Override public void paintBorder(java.awt.Component c, java.awt.Graphics g0,
					int x, int y, int w, int h) {
				int cw = dull ? CARD_H : CARD_W;
				int ch = dull ? CARD_W : CARD_H;
				int cx = dull ? x : x + CardAnimation.LEFT_GUTTER;
				int cy = dull ? y + (CARD_H - CARD_W) : y;
				java.awt.Graphics2D g = (java.awt.Graphics2D) g0.create();
				g.setRenderingHint(java.awt.RenderingHints.KEY_ANTIALIASING,
						java.awt.RenderingHints.VALUE_ANTIALIAS_ON);
				// Field cards are clipped to a rounded silhouette — trace the same corner radius.
				int arc = (int) Math.round(Math.min(cw, ch) * CardAnimation.CORNER_RADIUS_FRACTION * 2.0);
				int layers = 16;
				for (int layer = layers; layer >= 0; layer--) {
					float t   = (float) layer / layers;
					int   alpha = Math.round(t * t * 235);
					g.setColor(new Color(color.getRed(), color.getGreen(), color.getBlue(), alpha));
					g.setStroke(new java.awt.BasicStroke(2.5f));
					int off = layers - layer;
					int layerArc = Math.max(0, arc - 2 * off);
					g.drawRoundRect(cx + off, cy + off, cw - 1 - 2 * off, ch - 1 - 2 * off, layerArc, layerArc);
				}
				g.setColor(color);
				g.setStroke(new java.awt.BasicStroke(3f));
				g.drawRoundRect(cx + 1, cy + 1, cw - 3, ch - 3, Math.max(0, arc - 2), Math.max(0, arc - 2));
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
	List<ForwardTarget> selectFieldTargetsInPlace(
			List<ForwardTarget> eligible, int maxCount, boolean upTo, String title) {
		return selectFieldTargetsInPlace(eligible, maxCount, upTo, title, -1);
	}

	/**
	 * @param maxTotalCost budget the chosen cards' printed costs must sum within, or {@code -1}
	 *                     when the selection is bounded by count alone. A budgeted selection adds
	 *                     a running total to the bar and holds Confirm shut while the picks
	 *                     overspend — the overspend is allowed on the board on purpose, so the
	 *                     player can see what a pick would cost instead of clicking a card that
	 *                     silently does nothing.
	 */
	List<ForwardTarget> selectFieldTargetsInPlace(
			List<ForwardTarget> eligible, int maxCount, boolean upTo, String title, int maxTotalCost) {
		final Color GLOW_ELIGIBLE = new Color(90, 200, 255);
		final Color GLOW_PICKED   = Color.YELLOW;

		java.util.LinkedHashSet<Integer> sel = new java.util.LinkedHashSet<>();
		List<JLabel> labels = new ArrayList<>(eligible.size());
		Map<JLabel, javax.swing.border.Border> origBorders = new HashMap<>();
		List<java.awt.event.MouseListener> listeners = new ArrayList<>(eligible.size());
		List<ForwardTarget> result = new ArrayList<>();
		boolean[] dulls = new boolean[eligible.size()];
		final Timer[] pulseTimerRef = { null };
		// Assigned once the bar exists; the listeners below are installed before it, and only a
		// budgeted selection has anything to run here.
		final Runnable[] onSelectionChanged = { () -> {} };

		java.awt.SecondaryLoop loop =
				java.awt.Toolkit.getDefaultToolkit().getSystemEventQueue().createSecondaryLoop();
		boolean[] done = { false };

		JDialog bar = new JDialog(frame, title, false);
		bar.setUndecorated(true);             // no title bar / close box; not user-moveable
		bar.setFocusableWindowState(false);   // never steal focus, so the board stays clickable
		bar.setResizable(false);

		boolean nextWasEnabled = nextPhaseButton != null && nextPhaseButton.isEnabled();

		Runnable finish = () -> {
			if (done[0]) return;
			done[0] = true;
			if (pulseTimerRef[0] != null) pulseTimerRef[0].stop();
			for (int i = 0; i < labels.size(); i++) {
				labels.get(i).setBorder(origBorders.get(labels.get(i)));
				labels.get(i).removeMouseListener(listeners.get(i));
			}
			fieldTargetingActive = false;
			turnFlowGate.endChoice();
			// The secondary loop below keeps pumping the event queue, so turn flow can run — and
			// enable the Next button — while the player is still choosing (e.g. an EX Burst target
			// chosen during P2's turn, with offerP1MainPhasePriority firing meanwhile). Restoring
			// the pre-selection snapshot would clobber that enable and strand whoever gained
			// priority with nothing to click, so only restore when nothing re-enabled it.
			if (nextPhaseButton != null && !nextPhaseButton.isEnabled())
				nextPhaseButton.setEnabled(nextWasEnabled);
			for (Integer si : sel) result.add(eligible.get(si));
			bar.dispose();
			if (loop != null) loop.exit();
		};

		fieldTargetingActive = true;
		turnFlowGate.beginChoice();   // paired in finish, which is guarded to run exactly once
		if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
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
					onSelectionChanged[0].run();
				}
			};
			lbl.addMouseListener(ml);
			listeners.add(ml);
		}

		final float[] pulse = { 0f };
		Timer pulseTimer = new Timer(40, ev -> {
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
		hdr.setFont(FontLoader.loadPixelFont(11));
		hdr.setBorder(BorderFactory.createEmptyBorder(8, 12, 6, 12));

		bar.getContentPane().setLayout(new BorderLayout());
		((javax.swing.JComponent) bar.getContentPane()).setBorder(BorderFactory.createCompoundBorder(
				BorderFactory.createRaisedBevelBorder(),
				BorderFactory.createEmptyBorder(4, 6, 4, 6)));
		bar.getContentPane().add(hdr, BorderLayout.CENTER);
		if (upTo) {
			JButton confirmBtn = new JButton("Confirm");
			confirmBtn.setFont(FontLoader.loadPixelFont(11));
			confirmBtn.addActionListener(ae -> finish.run());
			JButton cancelBtn = new JButton("Cancel");
			cancelBtn.setFont(FontLoader.loadPixelFont(11));
			cancelBtn.addActionListener(ae -> { sel.clear(); finish.run(); });
			JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 6));
			if (maxTotalCost >= 0) {
				JLabel totalLbl = new JLabel("", SwingConstants.CENTER);
				totalLbl.setFont(FontLoader.loadPixelFont(11));
				// Sized for the largest total it could ever show — every eligible card picked at
				// once. The bar is packed exactly once, before it becomes visible, so a label that
				// only fits "0" would clip the moment the running total reached two digits.
				int worstCase = 0;
				for (ForwardTarget t : eligible) worstCase += fieldTargetCost(t);
				totalLbl.setText("Total cost: " + worstCase + " / " + maxTotalCost);
				totalLbl.setPreferredSize(totalLbl.getPreferredSize());
				onSelectionChanged[0] = () -> {
					int spent = 0;
					for (Integer si : sel) spent += fieldTargetCost(eligible.get(si));
					boolean withinBudget = spent <= maxTotalCost;
					totalLbl.setText("Total cost: " + spent + " / " + maxTotalCost);
					totalLbl.setForeground(withinBudget ? Color.BLACK : Color.RED);
					confirmBtn.setEnabled(withinBudget);
				};
				onSelectionChanged[0].run();   // paints the zero-pick state before the first click
				south.add(totalLbl);
			}
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
	List<ForwardTarget> showBreakZoneSelectDialog(
			List<ForwardTarget> eligible, List<CardData> zone,
			int maxCount, boolean upTo, String title) {
		return showBreakZoneSelectDialog(eligible, zone, maxCount, upTo, title, PickGate.ANY);
	}

	/**
	 * As above, with {@code gate} refusing combinations the card text rules out — "with different
	 * names", "each of a different Element".
	 *
	 * <p>The auto-pick shortcut is conditional on the gate: taking the whole pool without asking is
	 * only right while the whole pool is a legal selection, and a gate is exactly what can make it
	 * illegal. When it does, the player picks which subset they spend.
	 */
	List<ForwardTarget> showBreakZoneSelectDialog(
			List<ForwardTarget> eligible, List<CardData> zone,
			int maxCount, boolean upTo, String title, PickGate gate) {
		if (eligible.isEmpty()) { logEntry("Choose: no eligible targets in break zone"); return List.of(); }
		if (!upTo && eligible.size() <= maxCount) {
			List<CardData> pool = new ArrayList<>(eligible.size());
			for (ForwardTarget t : eligible) pool.add(zone.get(t.idx()));
			if (gate.maxSelectable(pool, maxCount) == eligible.size()) return List.copyOf(eligible);
		}
		return cardPickerDialog.pickFromBreakZone(eligible, zone, maxCount, upTo, title, gate);
	}

	/**
	 * Like {@link #showBreakZoneSelectDialog} but presents both players' break zones as
	 * separate P1 / P2 tabs (used for "either player's Break Zone" selections).
	 */
	List<ForwardTarget> showBreakZoneSelectDialogTabbed(
			List<ForwardTarget> eligible, List<CardData> zone,
			int maxCount, boolean upTo, String title) {
		if (eligible.isEmpty()) { logEntry("Choose: no eligible targets in break zone"); return List.of(); }
		if (!upTo && eligible.size() <= maxCount) return List.copyOf(eligible);
		return cardPickerDialog.pickFromBreakZoneTabbed(eligible, zone, maxCount, upTo, title);
	}

	// -------------------------------------------------------------------------

	private void showBackupContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();

		CardData card = p1BackupCards[idx];
		if (card != null) {
			autoAbilityTriggers.addAbilityMenuItems(menu, card, p1BackupFrozen[idx], p1BackupStates[idx], p1BackupPlayedOnTurn[idx],
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

	/**
	 * How far the board gradient reaches up into a player's zone. Bounded above by the band where
	 * that zone's side columns have already ended — past the corner column's crystal row and the
	 * bottom of the damage stack — since those are opaque and would step across the fade.
	 */
	private static final int BOARD_FADE_H = CARD_H / 4;

	/** Client-property key naming the most recent render of a card slot; see {@link #markSlotRender}. */
	private static final String SLOT_RENDER_TOKEN = "shufflingway.slotRenderToken";

	// -------------------------------------------------------------------------
	// Forward and Monster zones - panels, placement, slot rendering
	// -------------------------------------------------------------------------

	private JScrollPane buildForwardZonePanel(boolean isP1) {
		// A horizontal scrollbar is laid out inside the scroll pane's height, so whatever it takes
		// comes out of the cards. It only bites P2: a scroll pane puts the bar along its bottom
		// edge, which for P1 is the far side (its spare height already lives there) but for P2 is
		// the centre-facing side, directly between its cards and the board.
		final int barH = new JScrollBar(JScrollBar.HORIZONTAL).getPreferredSize().height;

		// Both seats surrender their spare seating to the hand fan — see the seat comment below for
		// what that spare is — but keep enough for the scrollbar, so the bar overhangs the board
		// gradient instead of cropping the cards.
		final int zoneH = Math.max(CARD_H + barH, FORWARD_ZONE_H - HandFanPanel.peekHeight());

		JPanel forwardInner = new JPanel(new FlowLayout(FlowLayout.CENTER, 4, 0)) {
			@Override
			public Dimension getPreferredSize() {
				int gap   = 4;
				int slots = getComponentCount();
				int width = gap + (CARD_H + gap) * slots;
				return new Dimension(Math.max(width, gap * 2), zoneH);
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
				return new Dimension(width, zoneH);
			}
		};
		monsterInner.setOpaque(false);
		if (isP1) p1MonsterPanel = monsterInner;
		else      p2MonsterPanel = monsterInner;

		// Seat P2's cards against the centre-facing edge of the zone.
		//
		// The zone is taller than a card, and FlowLayout packs its single row against the top of
		// the container. P1's zone is the NORTH child of the bottom band, so its top edge faces the
		// centre and top-packed cards already sit against it. P2's zone is the SOUTH child of the
		// top band, so its *bottom* edge faces the centre and the spare height lands between P2's
		// cards and the centre line — seating them further out than P1's by exactly that amount.
		// Pushing P2's rows down by the spare height mirrors P1.
		//
		// That spare is also where the hand fan's peek comes from: zoneH above has already handed
		// the fan as much of it as the fan needs, so what remains here is whatever the fan left.
		//
		// The seat is applied below, once the scroll pane exists — it has to shrink by the
		// scrollbar's thickness whenever the bar is showing.
		//
		// The overridden getPreferredSize() above ignores insets, so this shifts the rows without
		// changing the zone's height.

		// Monster panel sits at the bottom of the EAST area for "lower-right" appearance
		JPanel monsterContainer = new JPanel(new BorderLayout());
		monsterContainer.setOpaque(false);
		monsterContainer.add(monsterInner, BorderLayout.SOUTH);

		JPanel outer = new JPanel(new BorderLayout()) {
			@Override
			public Dimension getPreferredSize() {
				Dimension fwd = forwardInner.getPreferredSize();
				Dimension mon = monsterInner.getPreferredSize();
				return new Dimension(fwd.width + mon.width, zoneH);
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
		scroll.setPreferredSize(new Dimension(0, zoneH));

		if (!isP1) {
			// Seat P2's cards against the centre-facing edge, discounting the scrollbar when it is
			// showing. The bar then hangs below the cards, over the board gradient — which begins in
			// the zone panel's own colour, so the strip reads as part of the board rather than as a
			// bite out of the cards. Without the discount the bar would eat its thickness off the
			// bottom of every card, cropping exactly the corner where power is printed.
			JScrollBar hbar = scroll.getHorizontalScrollBar();
			Runnable reseat = () -> {
				int seat = Math.max(0, zoneH - CARD_H - (hbar.isVisible() ? barH : 0));
				if (forwardInner.getInsets().top == seat) return;
				forwardInner.setBorder(BorderFactory.createEmptyBorder(seat, 0, 0, 0));
				monsterInner.setBorder(BorderFactory.createEmptyBorder(seat, 0, 0, 0));
			};
			reseat.run();
			hbar.addComponentListener(new ComponentAdapter() {
				@Override public void componentShown(ComponentEvent e)  { reseat.run(); }
				@Override public void componentHidden(ComponentEvent e) { reseat.run(); }
			});
		}
		return scroll;
	}

	/** Adds a Forward card to P1's forward zone and wires up the debug context menu. */
	/**
	 * Alhanalem 18-018R's replacement, asked by every path that puts a Character on a field: true
	 * when {@code card} is removed from the game instead of arriving — in which case this has
	 * already removed it, and the caller must place nothing.
	 *
	 * <p>Asked from inside the {@code place…} methods rather than at their twenty-odd call sites,
	 * because those methods are also where the "enters the field" abilities fire. A Character that
	 * never arrives must not trigger, and answering here is what makes that true for free.
	 *
	 * <p>What decides it is who owns the Summon or ability doing the playing, not whose field the
	 * card was bound for: the printed sentence says "by your opponent's Summons or abilities", so
	 * an effect of theirs that hands a Character to <em>your</em> side is caught by it too. A cast
	 * is not caught at all — paying a card's cost to put it on the field is neither a Summon nor an
	 * ability.
	 */
	boolean fieldEntryBecomesRfg(CardData card, boolean enteringP1) {
		if (card == null || lastCardWasCast) return false;
		Boolean causerIsP1 = resolvingEffectController();
		// The flag is held by the player who used Alhanalem, and bites what their opponent causes.
		if (causerIsP1 == null || !turn(!causerIsP1).oppFieldEntryBecomesRfg) return false;
		gameState.addToPermanentRfp(card);
		logEntry((enteringP1 ? "" : "[P2] ") + card.name()
				+ " would enter the field by your opponent's Summon or ability → Removed From Game instead");
		// Ownership decides which zone took it, so both labels are refreshed rather than guessed at.
		refreshP1WarpZoneUI();
		refreshP2WarpZoneUI();
		return true;
	}

	/**
	 * Which player's Summon or ability is currently resolving, or {@code null} when neither is.
	 * The two are tracked separately — a Summon on the stack and an ability off it — and every
	 * "by your opponent's Summons or abilities" rule wants either.
	 */
	private Boolean resolvingEffectController() {
		if (currentResolutionIsSummon && currentSummonSource != null) return currentSummonSourceIsP1;
		if (currentAbilitySource != null) return currentAbilitySourceIsP1;
		return null;
	}

	void placeCardInForwardZone(CardData card) {
		placeCardInForwardZone(card, false);
	}

	/** @param paidExtraCost whether the optional extra cost was paid when casting {@code card} (threaded to its ETB auto-ability). */
	void placeCardInForwardZone(CardData card, boolean paidExtraCost) {
		if (fieldEntryBecomesRfg(card, true)) return;
		// A card arriving on the field is a new object: it has taken no damage and dealt none.
		forgetDamageRecordFor(card);
		if (p1ForwardPanel == null) return;
		int idx = p1ForwardLabels.size();

		JLabel lbl = new JLabel("", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
		lbl.setOpaque(false);
		lbl.setForeground(Color.DARK_GRAY);
		lbl.setFont(FontLoader.loadPixelFont(11));
		lbl.setBorder(BorderFactory.createEmptyBorder());
		lbl.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				if (lbl.getIcon() == null) return;
				if (SwingUtilities.isLeftMouseButton(e)
						&& p1ForwardClickSelectsCombat()) {
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
		p1ForwardStates.add(forwardEntersFieldDull(card, true) ? CardState.DULL : CardState.ACTIVE);
		p1ForwardPlayedOnTurn.add(gameState.getTurnNumber());
		if (card.element() != null) p1Turn.elementForwardsEnteredThisTurn.add(card.element().toLowerCase());
		p1ForwardDamage.add(0);
		p1ForwardPowerBoost.add(0);
		p1ForwardPowerReduction.add(0);
		p1ForwardTempTraits.add(EnumSet.noneOf(CardData.Trait.class));
		p1ForwardRemovedTraits.add(EnumSet.noneOf(CardData.Trait.class));
		p1ForwardTempJobs.add(null);
		p1ForwardPrimedTop.add(null);
		p1ForwardFrozen.add(false);
		p1ForwardLabels.add(lbl);

		p1ForwardPanel.add(lbl);
		p1ForwardPanel.revalidate();
		p1ForwardPanel.repaint();

		refreshP1ForwardSlot(idx);
		if (!card.fieldPowerGrants().isEmpty()) refreshFieldGrantDependents(true);
		if (!card.fieldCostReductions().isEmpty() || p1HandHasSelfCostModifiers()) refreshHandCardStates();
		fieldEntryAnimator.fireEntersField(card, true, paidExtraCost);
		syncBzForwardPlayables(true);
		sendToBreakZoneByUniquenessRule(card, true);
		fireOppNoForwardsFieldAbilitiesForCard(card, true);
	}

	/** Adds a Monster card to P1's monster zone (right side of forward zone, newest leftmost). */
	void placeCardInMonsterZone(CardData card) {
		if (fieldEntryBecomesRfg(card, true)) return;
		// A card arriving on the field is a new object: it has taken no damage and dealt none.
		forgetDamageRecordFor(card);
		if (p1MonsterPanel == null) return;
		int idx = p1MonsterLabels.size();

		JLabel lbl = new JLabel("", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
		lbl.setOpaque(false);
		lbl.setForeground(Color.DARK_GRAY);
		lbl.setFont(FontLoader.loadPixelFont(11));
		lbl.setBorder(BorderFactory.createEmptyBorder());
		lbl.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				if (lbl.getIcon() == null) return;
				// Resolve the current index dynamically: breaking a monster (e.g. paying a
				// "Put X into the Break Zone" cost) compacts p1MonsterLabels, so a captured
				// index would go stale and address the wrong card.
				int currentIdx = p1MonsterLabels.indexOf(lbl);
				if (currentIdx < 0) return;
				if (SwingUtilities.isLeftMouseButton(e)
						&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
					handleP1MonsterLeftClick(currentIdx);
				} else {
					showMonsterContextMenu(currentIdx, lbl, e);
				}
			}
			@Override public void mouseEntered(MouseEvent e) {
				if (lbl.getIcon() != null) {
					int currentIdx = p1MonsterLabels.indexOf(lbl);
					if (currentIdx >= 0) showZoomAt(p1MonsterUrls.get(currentIdx));
				}
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
		fieldEntryAnimator.fireEntersField(card, true, false);
		syncBzForwardPlayables(true);
		sendToBreakZoneByUniquenessRule(card, true);
	}

	/** Reloads and re-renders a single P1 monster slot using its stored URL and state. */
	void refreshP1MonsterSlot(int idx) {
		String url   = p1MonsterUrls.get(idx);
		CardState state = p1MonsterStates.get(idx);
		JLabel slot  = p1MonsterLabels.get(idx);
		if (url == null) return;
		if (fieldEntryAnimator.holdSlotBlank(slot, p1MonsterCards.get(idx))) return;
		CardData card     = p1MonsterCards.get(idx);
		int power         = effectiveP1MonsterPower(idx);
		int basePower     = card.power();
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		Integer tempFwdPower = p1MonsterTempForwardPower.get(card);
		boolean canAttack = attackSubStep == 1 && isMonsterSelectableAsForward(idx);
		boolean canBlock  = isMonsterBlockSelectable(idx);
		boolean selected  = p1MonsterAttackIdx == idx || p1BlockerMonsterIdx == idx;
		Color   glow      = combatGlowFor(card, true);
		int damage        = p1MonsterDamage.get(idx);
		boolean bfaActive = bfa != null && (
				bfa.minControlledMonsters() > 0 ? p1MonsterCards.size() >= bfa.minControlledMonsters() :
				bfa.damageThreshold()       > 0 ? gameState.getP1DamageZone().size() >= bfa.damageThreshold() :
				gameState.getCurrentPlayer() == GameState.Player.P1);
		boolean actingForward = bfaActive || tempFwdPower != null;
		int fwdPow = p1MonsterForwardPower(idx);
		Map<String, Integer> countersMap = gameState.getCountersMap(card);
		int totalCounters = countersMap.values().stream().mapToInt(c -> c == null ? 0 : c.intValue()).sum();
		List<CardData.Trait> traitTabs = visibleMonsterTraitTabs(true, idx);
		final boolean primed = false;   // Priming tops a Forward slot; the Monster row has no tops
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.renderBackupCard(
						CardAnimation.toARGB(raw, CARD_W, CARD_H), state, canAttack || canBlock, selected, p1MonsterFrozen.get(idx), glow);
				TraitTab.renderTraitTabs(canvas, state, traitTabs, primed);
				if (damage > 0)
					CardAnimation.renderDamageOverlay(canvas, damage, state);
				if (actingForward)
					CardAnimation.renderPowerOverlayRight(canvas, fwdPow, new Color(80, 220, 80), state);
				else if (power > basePower)
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(80, 220, 80), state);
				if (!countersMap.isEmpty())
					CardAnimation.renderCounterOverlay(canvas, totalCounters, state, AppSettings.getCounterColor());
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null) { slot.setIcon(icon); slot.setText(null); }
					applyFieldSlotTooltip(slot, state, traitTabs, primed, countersMap);
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	/** Adds a Monster card to P2's monster zone (right side of forward zone). */
	void placeP2CardInMonsterZone(CardData card) {
		if (fieldEntryBecomesRfg(card, false)) return;
		// A card arriving on the field is a new object: it has taken no damage and dealt none.
		forgetDamageRecordFor(card);
		if (p2MonsterPanel == null) return;
		int idx = p2MonsterLabels.size();

		JLabel lbl = new JLabel("", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
		lbl.setOpaque(false);
		lbl.setFont(FontLoader.loadPixelFont(11));
		lbl.setBorder(BorderFactory.createEmptyBorder());
		lbl.addMouseListener(new MouseAdapter() {
			@Override public void mousePressed(MouseEvent e) {
				if (lbl.getIcon() != null && SwingUtilities.isRightMouseButton(e)) {
					int currentIdx = p2MonsterLabels.indexOf(lbl);
					if (currentIdx >= 0) showP2MonsterContextMenu(currentIdx, lbl, e);
				}
			}
			@Override public void mouseEntered(MouseEvent e) {
				if (lbl.getIcon() != null) {
					int currentIdx = p2MonsterLabels.indexOf(lbl);
					if (currentIdx >= 0) showZoomAt(p2MonsterUrls.get(currentIdx));
				}
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
		fieldEntryAnimator.fireEntersField(card, false, false);
		syncBzForwardPlayables(false);
		sendToBreakZoneByUniquenessRule(card, false);
	}

	/** Reloads and re-renders a single P2 monster slot using its stored URL and state. */
	void refreshP2MonsterSlot(int idx) {
		String url = p2MonsterUrls.get(idx);
		CardState state = p2MonsterStates.get(idx);
		JLabel slot = p2MonsterLabels.get(idx);
		if (url == null) return;
		if (fieldEntryAnimator.holdSlotBlank(slot, p2MonsterCards.get(idx))) return;
		CardData card     = p2MonsterCards.get(idx);
		Color    glow     = combatGlowFor(card, false);
		int power         = effectiveP2MonsterPower(idx);
		int basePower     = card.power();
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		Integer tempFwdPower = p2MonsterTempForwardPower.get(card);
		int damage        = p2MonsterDamage.get(idx);
		boolean bfaActive = bfa != null && (
				bfa.minControlledMonsters() > 0 ? p2MonsterCards.size() >= bfa.minControlledMonsters() :
				bfa.damageThreshold()       > 0 ? gameState.getP2DamageZone().size() >= bfa.damageThreshold() :
				gameState.getCurrentPlayer() == GameState.Player.P2);
		boolean actingForward = bfaActive || tempFwdPower != null;
		int fwdPow = p2MonsterForwardPower(idx);
		Map<String, Integer> countersMap = gameState.getCountersMap(card);
		int totalCounters = countersMap.values().stream().mapToInt(c -> c == null ? 0 : c.intValue()).sum();
		List<CardData.Trait> traitTabs = visibleMonsterTraitTabs(false, idx);
		final boolean primed = false;   // Priming tops a Forward slot; the Monster row has no tops
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.toARGB(raw, CARD_W, CARD_H);
				canvas = CardAnimation.renderBackupCard(canvas, state, false, false, p2MonsterFrozen.get(idx), glow);
				TraitTab.renderTraitTabs(canvas, state, traitTabs, primed);
				if (damage > 0)
					CardAnimation.renderDamageOverlay(canvas, damage, state);
				if (actingForward)
					CardAnimation.renderPowerOverlayRight(canvas, fwdPow, new Color(80, 220, 80), state);
				else if (power > basePower)
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(80, 220, 80), state);
				if (!countersMap.isEmpty())
					CardAnimation.renderCounterOverlay(canvas, totalCounters, state, AppSettings.getCounterColor());
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null) { slot.setIcon(icon); slot.setText(null); }
					applyFieldSlotTooltip(slot, state, traitTabs, primed, countersMap);
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	/** Shows a context menu for a P1 monster slot. */
	private void showMonsterContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();

		// Action abilities
		autoAbilityTriggers.addAbilityMenuItems(menu, p1MonsterCards.get(idx), p1MonsterFrozen.get(idx),
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

	// -------------------------------------------------------------------------
	// Forward slot rendering and selectability
	// -------------------------------------------------------------------------

	/** Reloads and re-renders a single P1 forward slot using its stored URL and state. */
	void refreshP1ForwardSlot(int idx) {
		refreshPlayerDamageShieldIcon(true);
		if (fieldEntryAnimator.holdSlotBlank(p1ForwardLabels.get(idx), p1ForwardCards.get(idx))) return;
		CardData topCard = p1ForwardPrimedTop.get(idx);
		final boolean primed = isPrimedForward(true, idx);
		// Primed: display and stats come from the top card
		String    url    = primed ? topCard.imageUrl() : p1ForwardUrls.get(idx);
		CardState state  = p1ForwardStates.get(idx);
		JLabel    slot   = p1ForwardLabels.get(idx);
		if (url == null) return;
		boolean hasHaste  = effectiveP1HasTrait(idx, CardData.Trait.HASTE);
		CardData fwdCard  = p1ForwardCards.get(idx);
		boolean canAttack = gameState.getCurrentPhase() == GameState.GamePhase.ATTACK
				&& attackSubStep == 1
				&& state == CardState.ACTIVE
				&& hasAttackRemaining(effectiveP1Forward(idx))
				&& !p1CannotAttack.contains(fwdCard)
				&& !p1CannotAttackPersistent.contains(fwdCard)
				&& !fwdCard.cannotAttackOrBlock()
				&& !isFieldAbilityCannotAttackOrBlock(fwdCard, true)
				&& (hasHaste || p1ForwardPlayedOnTurn.get(idx) != gameState.getTurnNumber());
		boolean canBlock  = isForwardBlockSelectable(idx);
		int damage    = p1ForwardDamage.get(idx);
		int power     = effectiveP1ForwardPower(idx);
		int basePower = (topCard != null ? topCard : p1ForwardCards.get(idx)).power();
		boolean selected = p1AttackSelection.contains(idx) || p1BlockerSelection == idx;
		Color   glow     = combatGlowFor(effectiveP1Forward(idx), true);
		Map<String, Integer> countersMap = gameState.getCountersMap(fwdCard);
		int totalCounters = countersMap.values().stream().mapToInt(c -> c == null ? 0 : c.intValue()).sum();
		List<CardData.Trait> traitTabs = visibleTraitTabs(true, idx);
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		final Object renderToken = markSlotRender(slot);
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.renderBackupCard(CardAnimation.toARGB(raw, CARD_W, CARD_H), state, canAttack || canBlock, selected, Boolean.TRUE.equals(p1ForwardFrozen.get(idx)), glow);
				TraitTab.renderTraitTabs(canvas, state, traitTabs, primed);
				if (damage > 0) {
					CardAnimation.renderDamageOverlay(canvas, damage, state);
				}
				if (power > basePower) {
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(80, 220, 80), state);
				} else if (power < basePower) {
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(230, 200, 60), state);
				}
				if (!countersMap.isEmpty())
					CardAnimation.renderCounterOverlay(canvas, totalCounters, state, AppSettings.getCounterColor());
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				if (slotRenderSuperseded(slot, renderToken)) return;
				try {
					ImageIcon icon = get();
					if (icon != null) { slot.setIcon(icon); slot.setText(null); }
					applyFieldSlotTooltip(slot, state, traitTabs, primed, countersMap);
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	/**
	 * Claims {@code slot} for the render about to start and returns that render's token.
	 *
	 * <p>Slot art is built on a background thread, and nothing orders two renders of the same slot
	 * against each other. That matters most around combat: breaking a blocker refreshes every
	 * Forward at a moment when no block step is open — so nothing draws as a legal blocker — and the
	 * next attack's block step refreshes them again immediately after. If the earlier, glow-less
	 * render finishes second it overwrites the newer one, and a Forward sits there looking
	 * ineligible while {@link #isForwardBlockSelectable} still says it can block. The glow is the
	 * only thing wrong in that state, which is what makes it read as "some blockers stopped working".
	 *
	 * <p>Both this and the check in {@code done()} run on the EDT, so the token needs no
	 * synchronisation: the last render to start is the only one allowed to paint.
	 */
	private Object markSlotRender(JLabel slot) {
		Object token = new Object();
		slot.putClientProperty(SLOT_RENDER_TOKEN, token);
		return token;
	}

	/** True when a later render has claimed {@code slot}, so this one's result must be dropped. */
	private boolean slotRenderSuperseded(JLabel slot, Object token) {
		return slot.getClientProperty(SLOT_RENDER_TOKEN) != token;
	}

	void refreshAllForwardSlots() {
		for (int i = 0; i < p1ForwardLabels.size(); i++) refreshP1ForwardSlot(i);
		for (int i = 0; i < p1MonsterLabels.size(); i++) refreshP1MonsterSlot(i);
	}

	private boolean isForwardSelectable(int idx) {
		if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return false;
		if (attackSubStep != 1) return false;
		if (idx < 0 || idx >= p1ForwardStates.size()) return false;
		CardState state = p1ForwardStates.get(idx);
		if (state != CardState.ACTIVE) return false;
		if (!hasAttackRemaining(effectiveP1Forward(idx))) return false;
		CardData fwd = p1ForwardCards.get(idx);
		if (p1CannotAttack.contains(fwd)) return false;
		if (p1CannotAttackPersistent.contains(fwd)) return false;
		if (fwd.cannotAttackOrBlock()) return false;
		if (isFieldAbilityCannotAttackOrBlock(fwd, true)) return false;
		if (isFieldAbilityCannotAttack(fwd, true)) return false;
		return effectiveP1HasTrait(idx, CardData.Trait.HASTE)
				|| p1ForwardPlayedOnTurn.get(idx) != gameState.getTurnNumber();
	}

	/**
	 * The P1 Forward that is compelled to attack this turn and still can, or {@code -1}.
	 *
	 * <p>Four sources feed it: {@link #p1MustAttack}, the one-turn instruction the choose
	 * chain writes ("it must attack this turn if possible"); {@link #permanentMustAttackOncePerTurn},
	 * the standing compulsion an effect grants (Roche 29-076H); the printed self-named form
	 * ({@link #selfMustAttackOncePerTurn} — Berserker, Umaro, Reddas); and the field-wide form
	 * ({@link #forwardsMustAttack} — Layle 16-083H, Jack Garland 24-079L). All but the first are
	 * "once per turn", so each is satisfied as soon as that Forward has attacked once.
	 *
	 * <p>"If possible" is {@link #isForwardSelectable}: a Forward that is dull, restricted, or out
	 * of attacks lifts the compulsion instead of stranding the player in the attack phase.
	 */
	int p1ForwardCompelledToAttackIdx() {
		boolean fieldWide = forwardsMustAttack(true);
		for (int i = 0; i < p1ForwardCards.size(); i++) {
			if (!isForwardSelectable(i)) continue;
			CardData fwd = p1ForwardCards.get(i);
			if (p1MustAttack.contains(fwd)) return i;
			boolean oncePerTurn = fieldWide
					|| permanentMustAttackOncePerTurn.contains(fwd)
					|| selfMustAttackOncePerTurn(fwd);
			if (oncePerTurn && attacksMadeThisTurn.getOrDefault(fwd, 0) == 0) return i;
		}
		return -1;
	}

	private boolean p1InBlockDeclaration() {
		return pendingP2Attacker != null || pendingP2PartyIndices != null;
	}

	/** Returns true if {@code idx} is a valid P1 blocker choice during block declaration. */
	boolean isForwardBlockSelectable(int idx) {
		return p1ForwardBlockRejection(idx) == null;
	}

	/**
	 * Why P1's Forward at {@code idx} cannot be chosen as a blocker, or {@code null} when it can.
	 *
	 * <p>Every refusal reads the same on the board — no green glow, and the click does nothing — but
	 * a dozen unrelated rules can produce it, several of which legitimately change from one attacker
	 * to the next. Naming the rule turns "that Forward stopped working" into something answerable,
	 * so {@link #toggleP1BlockerSelection} logs this when it turns a click away.
	 *
	 * <p>This is the single source of truth for blocker legality: {@link #isForwardBlockSelectable}
	 * is a null check over it, and the checks stay in their original order so the reason reported is
	 * the first rule that actually bit.
	 */
	String p1ForwardBlockRejection(int idx) {
		String ineligible = p1ForwardBlockIneligibility(idx);
		if (ineligible != null) return ineligible;
		// If any forward must block, restrict choices to those
		if (!p1MustBlock.isEmpty() && !p1MustBlock.contains(p1ForwardCards.get(idx)))
			return "another Forward you control must block";
		// Dio 26-075C: a Forward compelled against this specific attacker takes the block, and
		// only when it can — p1ForwardCompelledToBlockIdx returns -1 once "if possible" fails.
		int compelled = p1ForwardCompelledToBlockIdxForPendingAttack();
		if (compelled >= 0 && compelled != idx)
			return "another Forward is compelled to block this attacker";
		return null;
	}

	/**
	 * Everything that qualifies a P1 Forward as a blocker except the must-block restrictions,
	 * which are layered on by {@link #p1ForwardBlockRejection}. Split out so
	 * {@link #p1ForwardCompelledToBlockIdx} can ask "could this Forward block at all?" without
	 * calling back into the method that consults it.
	 */
	private boolean p1ForwardBlockEligible(int idx) {
		return p1ForwardBlockIneligibility(idx) == null;
	}

	/** Reason half of {@link #p1ForwardBlockEligible}; {@code null} means it qualifies. */
	private String p1ForwardBlockIneligibility(int idx) {
		if (!p1InBlockDeclaration()) return "no attack is waiting on a block";
		if (idx < 0 || idx >= p1ForwardStates.size()) return "no Forward in that slot";
		CardState s = p1ForwardStates.get(idx);
		if (s != CardState.ACTIVE) return "it is dull";
		CardData blocker = p1ForwardCards.get(idx);
		if (p1CannotBlock.contains(blocker)) return "an effect says it cannot block this turn";
		if (p1CannotBlockPersistent.contains(blocker)) return "an effect says it cannot block";
		if (blocker.cannotBlockAtAll() || blocker.cannotAttackOrBlock()) return "its own text says it cannot block";
		if (blockBarredByFieldCostLock(blocker)) return "a field ability bars Forwards of its cost from blocking";
		if (isFieldAbilityCannotAttackOrBlock(blocker, true)) return "a field ability says it cannot block";
		if (blocker.cannotBlockParty() && pendingP2PartyIndices != null) return "it cannot block a party";
		if (blocker.cannotBlockHigherPower() && attackerPowerExceedsBlocker(ForwardTarget.CardZone.FORWARD, idx))
			return "it cannot block a Forward of higher power";
		if (p1Turn.forwardCannotBlockInferiorPower && blockerPowerExceedsAttacker(ForwardTarget.CardZone.FORWARD, idx))
			return "an effect bars it from blocking a Forward of lower power";
		// Check attacker-side unblockability
		if (attackerUnblockable()) return "the attacker cannot be blocked";
		if (attackerBlockCostFiltersExclude(blocker.cost()))
			return "the attacker cannot be blocked by a Forward of its cost";
		if (attackerHigherPowerFilterExcludes(ForwardTarget.CardZone.FORWARD, idx))
			return "the attacker cannot be blocked by a Forward of higher power";
		if (attackerBlockPowerFiltersExclude(ForwardTarget.CardZone.FORWARD, idx))
			return "the attacker cannot be blocked by a Forward of its power";
		return null;
	}

	// -------------------------------------------------------------------------
	// Attack permission from field abilities; party formation
	// -------------------------------------------------------------------------

	/**
	 * Returns {@code true} when a board-wide cost-gated block lock currently bars {@code blocker}
	 * from blocking — Edea 2-100H's "Forwards of cost 5 or more cannot block."
	 *
	 * <p>The text names no controller, so both fields are searched and the lock catches its own
	 * controller's Forwards as readily as the opponent's. It is read per block-legality check rather
	 * than latched, because the source can leave the field mid-Attack Phase.
	 *
	 * <p>Every path by which a card is declared as a blocker consults this — the Forward row and the
	 * Monster and Backup rows a card can block from once something has turned it into a Forward,
	 * since a Forward is what the restriction speaks about however it got to be one.
	 */
	boolean blockBarredByFieldCostLock(CardData blocker) {
		if (blocker == null) return false;
		return costBlockLockExcludes(blocker, true) || costBlockLockExcludes(blocker, false);
	}

	private boolean costBlockLockExcludes(CardData blocker, boolean side) {
		List<CardData> fwds = side ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = side ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = side ? p1MonsterCards : p2MonsterCards;
		for (CardData c : fwds)                if (costLockBars(c, blocker)) return true;
		for (CardData c : bkps) if (c != null)  if (costLockBars(c, blocker)) return true;
		for (CardData c : mons)                if (costLockBars(c, blocker)) return true;
		return false;
	}

	private boolean costLockBars(CardData src, CardData blocker) {
		if (lostAbilitiesCards.contains(src)) return false;
		int[] lock = src.costForwardsCannotBlock();
		if (lock == null) return false;
		return lock[1] > 0 ? blocker.cost() >= lock[0] : blocker.cost() <= lock[0];
	}

	/**
	 * Returns {@code true} if any field ability on {@code card} currently prevents it from
	 * attacking or blocking (conditional forms — unconditional form is handled by
	 * {@link CardData#cannotAttackOrBlock()}).
	 */
	boolean isFieldAbilityCannotAttackOrBlock(CardData card, boolean isP1) {
		// Medusa's granted "If a Petrification Counter is placed on this Forward, this Forward cannot
		// attack or block." — driven off the counter itself (Medusa is the only source of them).
		if (gameState.getCounters(card, "Petrification") > 0) return true;
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher m2 = ActionResolverPatterns.IF_DONT_CONTROL_CARD_NAME_FWD_CANNOT_ATTACK_OR_BLOCK.matcher(fa.effectText());
			if (m2.find() && m2.group("subject").trim().equalsIgnoreCase(card.name())) {
				String required = m2.group("required").trim();
				List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
				if (fwds.stream().noneMatch(f -> f.name().equalsIgnoreCase(required))) return true;
			}
			Matcher m3 = ActionResolverPatterns.IF_COUNTER_LIMIT_CANNOT_ATTACK_OR_BLOCK.matcher(fa.effectText());
			if (m3.find() && m3.group("subject").trim().equalsIgnoreCase(card.name())) {
				int    limit       = Integer.parseInt(m3.group("count"));
				String counterName = m3.group("countername").trim();
				if (gameState.getCounters(card, counterName) <= limit) return true;
			}
		}
		return false;
	}

	/**
	 * Returns {@code true} if any field ability on {@code card} currently prevents it from
	 * attacking (attack-only restriction — does not affect blocking).
	 */
	boolean isFieldAbilityCannotAttack(CardData card, boolean isP1) {
		for (FieldAbility fa : card.fieldAbilities()) {
			// Unconditional: "[CardName] cannot attack."
			Matcher mStandalone = ActionResolverPatterns.STANDALONE_CANNOT_ATTACK.matcher(fa.effectText());
			if (mStandalone.find() && mStandalone.group("cardname").trim().equalsIgnoreCase(card.name()))
				return true;
			// Conditional: "If your opponent doesn't control any Forwards, [CardName] cannot attack."
			Matcher mOppNoFwds = ActionResolverPatterns.IF_OPP_NO_FORWARDS_CANNOT_ATTACK.matcher(fa.effectText());
			if (mOppNoFwds.find() && mOppNoFwds.group("subject").trim().equalsIgnoreCase(card.name())) {
				List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
				if (oppFwds.isEmpty()) return true;
			}
			// Permission rather than prohibition ("can only attack if …"), so it bars the attack
			// whenever neither arm holds — Elena 11-088R.
			Matcher mOnlyIf = AutoAbilityTriggers.FA_SELF_ATTACK_REQUIRES_CONTROL.matcher(fa.effectText());
			if (mOnlyIf.find() && mOnlyIf.group("card").trim().equalsIgnoreCase(card.name())
					&& !attackPermissionMet(mOnlyIf, isP1)) return true;
		}
		return false;
	}

	/**
	 * Whether either arm of an {@link AutoAbilityTriggers#FA_SELF_ATTACK_REQUIRES_CONTROL}
	 * permission currently holds: a plain count of the Forwards you control, or one Forward
	 * carrying the named Job whose name is not the excluded one.
	 *
	 * <p>The exclusion matters because the card naming the Job usually has it too — Elena is
	 * herself a Member of the Turks, so without it she would always satisfy her own second arm.
	 */
	private boolean attackPermissionMet(Matcher m, boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		if (fwds.size() >= Integer.parseInt(m.group("count"))) return true;
		String job    = m.group("job").trim();
		String except = m.group("except").trim();
		for (CardData f : fwds)
			if (!except.equalsIgnoreCase(f.name()) && meetsJobFilterEffective(f, job, fwds)) return true;
		return false;
	}

	/**
	 * Returns {@code true} if the forward at {@code idx} on the given player's side is a
	 * party-element wildcard — either intrinsically, via an active field ability from a card
	 * on the same player's field, or via a turn-scoped grant.
	 */
	private boolean effectiveCanFormPartyAnyElement(boolean isP1, int idx) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		if (idx < 0 || idx >= fwds.size()) return false;
		CardData fwd = fwds.get(idx);
		if (fwd.canFormPartyAnyElement()) return true;
		if (turn(isP1).partyAnyElementThisTurn) return true;
		// Check permanent field-ability grants from any card on the same player's field
		List<CardData> srcFwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[] srcBkps     = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> srcMons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : srcFwds) for (FieldPartyAnyElement g : src.fieldPartyAnyElements())
			if (g.appliesToCard(fwd, jobsStripped(fwd))) return true;
		for (CardData src : srcBkps) if (src != null) for (FieldPartyAnyElement g : src.fieldPartyAnyElements())
			if (g.appliesToCard(fwd, jobsStripped(fwd))) return true;
		for (CardData src : srcMons) for (FieldPartyAnyElement g : src.fieldPartyAnyElements())
			if (g.appliesToCard(fwd, jobsStripped(fwd))) return true;
		return false;
	}

	/**
	 * Returns the set of elements common to all non-wildcard members of {@code party},
	 * or {@code null} if every member is a wildcard (all-wildcard party, no element constraint).
	 * An empty set means the non-wildcard members share no element — an invalid party.
	 *
	 * @param isP1    which player's field abilities to check for wildcard grants
	 * @param indices forward-slot indices making up the party (from that player's forward list)
	 */
	private Set<String> partyRequiredElements(boolean isP1, List<Integer> indices) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		Set<String> required = null;
		for (int i : indices) {
			if (effectiveCanFormPartyAnyElement(isP1, i)) continue;
			CardData m = fwds.get(i);
			Set<String> elems = new java.util.HashSet<>(Arrays.asList(m.elements()));
			if (required == null) required = elems;
			else required.retainAll(elems);
		}
		return required;
	}

	/** Returns {@code true} if {@code indices} form a valid party for {@code isP1}'s forwards. */
	boolean canFormValidParty(boolean isP1, List<Integer> indices) {
		// A party is two or more Forwards, so a card that "cannot form parties" only bars the
		// grouping — it is still free to attack by itself, which is a one-member selection.
		if (indices.size() > 1) {
			List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
			for (int i : indices)
				if (i >= 0 && i < fwds.size() && cannotFormParties(fwds.get(i))) return false;
		}
		Set<String> req = partyRequiredElements(isP1, indices);
		return req == null || !req.isEmpty();
	}

	/**
	 * True when {@code card} carries "[card] cannot form parties." (Berserker 3-091C) — it may
	 * attack alone but may not be grouped with another Forward.
	 */
	boolean cannotFormParties(CardData card) {
		if (card == null || lostAbilitiesCards.contains(card)) return false;
		for (FieldAbility fa : effectiveFieldAbilities(card)) {
			Matcher m = AutoAbilityTriggers.FA_SELF_CANNOT_FORM_PARTIES.matcher(fa.effectText());
			if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) return true;
		}
		return false;
	}

	// -------------------------------------------------------------------------
	// Attacker and blocker selection
	// -------------------------------------------------------------------------

	private void toggleAttackSelection(int idx) {
		if (!isForwardSelectable(idx)) return;
		if (p1AttackSelection.contains(idx)) {
			p1AttackSelection.remove((Integer) idx);
			refreshAttackButton();
			refreshP1ForwardSlot(idx);
			return;
		}
		if (!p1AttackSelection.isEmpty()) {
			// Berserker 3-091C: barred both from joining a party and from being joined, so the
			// check reads the incoming Forward and everything already selected.
			if (cannotFormParties(p1ForwardCards.get(idx))) {
				logEntry("Cannot add to party — " + p1ForwardCards.get(idx).name() + " cannot form parties");
				return;
			}
			for (int sel : p1AttackSelection) {
				if (!cannotFormParties(p1ForwardCards.get(sel))) continue;
				logEntry("Cannot add to party — " + p1ForwardCards.get(sel).name() + " cannot form parties");
				return;
			}
			if (!effectiveCanFormPartyAnyElement(true, idx)) {
				// Compute the common element constraint across non-wildcard existing members
				Set<String> required = partyRequiredElements(true, p1AttackSelection);
				// null  → all existing members are wildcards → any element OK
				// empty → existing members share no common element (shouldn't occur in valid state)
				if (required != null && !required.isEmpty()) {
					CardData newFwd = p1ForwardCards.get(idx);
					if (Arrays.stream(newFwd.elements()).noneMatch(required::contains)) {
						logEntry("Cannot add to party — no shared element with the party");
						return;
					}
				}
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
		} else if (!p1AttackDeclarationInFlight()) {
			toggleAttackSelection(idx);
		}
	}

	/** Toggles the P1 blocker selection during block-declaration sub-step. */
	private void toggleP1BlockerSelection(int idx) {
		String why = p1ForwardBlockRejection(idx);
		if (why != null) {
			// Silent refusals are indistinguishable from a dead click, and the rule that bit is
			// rarely the one the player is thinking of. Say which.
			if (idx >= 0 && idx < p1ForwardCards.size())
				logEntry("[Block] " + p1ForwardCards.get(idx).name() + " cannot block — " + why + ".");
			return;
		}
		p1BlockerSelection = (p1BlockerSelection == idx) ? -1 : idx;
		p1BlockerMonsterIdx = -1;
		p1BlockerBackupIdx = -1;
		refreshAttackButton();
		refreshAllForwardSlots();
		for (int i = 0; i < p1BackupCards.length; i++) refreshP1BackupSlot(i);
	}

	// -------------------------------------------------------------------------
	// Blocker legality - unblockable, power filters, Monsters and Backups
	// -------------------------------------------------------------------------

	/**
	 * The P2 Characters currently attacking: every party member during a party attack, the lone
	 * attacker otherwise — including one attacking from the Monster or Backup row.
	 *
	 * <p>This used to return Forward-row indices only and gave back an empty list for a Monster or
	 * Backup acting as a Forward, which silently exempted such an attacker from every
	 * attacker-side blocking restriction. A Character attacking as a Forward is a Forward for as
	 * long as it is attacking, so its restrictions apply the same way; the exemption was a gap in
	 * the slot-indexed representation rather than a rule.
	 *
	 * <p>Every attacker-side check runs over this list, so they all read the whole party rather than
	 * a single {@code pendingP2AttackerIdx} — which a party attack never sets.
	 */
	private List<ForwardTarget> pendingP2AttackerTargets() {
		if (pendingP2PartyIndices != null) {
			// A member can be broken during the priority round before the block is declared.
			List<ForwardTarget> live = new ArrayList<>();
			for (int i : pendingP2PartyIndices)
				if (i >= 0 && i < p2ForwardCards.size())
					live.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
			return live;
		}
		if (pendingP2AttackerIdx < 0) return List.of();
		ForwardTarget.CardZone zone = pendingP2AttackerIsMonster ? ForwardTarget.CardZone.MONSTER
				: pendingP2AttackerIsBackup ? ForwardTarget.CardZone.BACKUP
				: ForwardTarget.CardZone.FORWARD;
		int limit = switch (zone) {
			case MONSTER -> p2MonsterCards.size();
			case BACKUP  -> p2BackupCards.length;
			default      -> p2ForwardCards.size();
		};
		if (pendingP2AttackerIdx >= limit) return List.of();
		ForwardTarget t = new ForwardTarget(false, pendingP2AttackerIdx, zone);
		return autoAbilityTriggers.fieldCardData(t) == null ? List.of() : List.of(t);
	}

	/** The attacking Character behind {@code t}, or {@code null} if its slot has since emptied. */
	private CardData pendingAttackerCard(ForwardTarget t) {
		return autoAbilityTriggers.fieldCardData(t);
	}

	/** Every card on P2's field that can carry an IfControlBoost. */
	private List<CardData> p2FieldCards() {
		return fieldCardsFor(false);
	}

	/** Every card on {@code isP1}'s field that can carry an {@link IfControlBoost}. */
	private List<CardData> fieldCardsFor(boolean isP1) {
		List<CardData> all = new ArrayList<>(isP1 ? p1ForwardCards : p2ForwardCards);
		for (CardData bkp : (isP1 ? p1BackupCards : p2BackupCards)) if (bkp != null) all.add(bkp);
		all.addAll(isP1 ? p1MonsterCards : p2MonsterCards);
		return all;
	}

	/**
	 * True when an {@link IfControlBoost} on the attacker's own side grants it "cannot be blocked"
	 * and every condition of that boost is currently met — the "If you control X" printings, and
	 * Zidane 8-115L's hand-size gate.
	 *
	 * <p>Re-read per block-legality check rather than recorded once, for the same reason
	 * {@link #hasSelfCannotBeBlockedFieldAbility} is: the condition opens and shuts as the board and
	 * the hand change, and a card drawn or discarded mid-turn moves Zidane across his threshold.
	 */
	boolean attackerConditionallyUnblockable(CardData attacker, boolean attackerIsP1) {
		if (attacker == null) return false;
		for (CardData src : fieldCardsFor(attackerIsP1))
			for (IfControlBoost icb : src.ifControlBoosts())
				if (icb.cannotBeBlocked() && icb.appliesToCard(attacker, jobsStripped(attacker))
						&& icbConditionsMet(icb, attackerIsP1))
					return true;
		return false;
	}

	/**
	 * True when {@code attacker} carries "cannot be blocked by a Monster that is also a Forward"
	 * (Jack Garland 29-123R). Read through {@link #effectiveFieldAbilities} so a granted copy
	 * restricts blockers exactly as a printed one does.
	 */
	boolean barsMonsterForwardBlockers(CardData attacker) {
		if (attacker == null || lostAbilitiesCards.contains(attacker)) return false;
		for (FieldAbility fa : effectiveFieldAbilities(attacker)) {
			Matcher m = AutoAbilityTriggers.FA_CANNOT_BE_BLOCKED_BY_MONSTER_FORWARD.matcher(fa.effectText());
			if (m.find() && m.group("card").trim().equalsIgnoreCase(attacker.name())) return true;
		}
		return false;
	}

	/**
	 * Whether P2's pending attack bars Monster blockers. A party is one attack made by several
	 * Forwards, so one member carrying the restriction gates the block — the same reading
	 * {@link #attackerUnblockable} takes.
	 */
	private boolean pendingP2AttackBarsMonsterBlockers() {
		for (ForwardTarget t : pendingP2AttackerTargets()) {
			CardData attacker = pendingAttackerCard(t);
			if (attacker != null && barsMonsterForwardBlockers(attacker)) return true;
		}
		return false;
	}

	/** True when a turn-scoped "cannot be blocked" grant covers any of the current attackers. */
	private boolean attackerUnblockable() {
		for (ForwardTarget t : pendingP2AttackerTargets())
			if (p2CannotBeBlocked.contains(pendingAttackerCard(t))) return true;
		return attackerConditionallyUnblockable();
	}

	/** Returns true if any IfControlBoost on P2's field grants cannot-be-blocked to an attacker
	 *  and all of that boost's conditions are currently met. */
	private boolean attackerConditionallyUnblockable() {
		for (ForwardTarget t : pendingP2AttackerTargets()) {
			CardData attacker = pendingAttackerCard(t);
			if (attacker == null) continue;
			if (hasSelfCannotBeBlockedFieldAbility(attacker, false)) return true;
			if (attackerConditionallyUnblockable(attacker, false)) return true;
		}
		return false;
	}

	/**
	 * Whether {@code card} currently hands itself "cannot be blocked" through one of its own field
	 * abilities — Ritz 11-063L's {@code Damage 3 -- Ritz gains "Ritz cannot be blocked."}.
	 *
	 * <p>Read per block-legality check rather than applied once, because the "Damage N --" gate it
	 * sits behind opens and shuts as its controller's damage zone fills. That is what separates it
	 * from the {@code p1/p2CannotBeBlocked} sets, which record a turn-scoped grant some
	 * effect made and are cleared at end of turn.
	 */
	boolean hasSelfCannotBeBlockedFieldAbility(CardData card, boolean isP1) {
		if (card == null || lostAbilitiesCards.contains(card)) return false;
		int dmg = (isP1 ? gameState.getP1DamageZone() : gameState.getP2DamageZone()).size();
		for (FieldAbility fa : effectiveFieldAbilities(card)) {
			if (fa.damageThreshold() > 0 && dmg < fa.damageThreshold()) continue;
			// Black Chocobo 3-054C shields the party rather than himself — "If Black Chocobo forms
			// a party, that party cannot be blocked." Answered here all the same, because every
			// caller already reads one attacker's unblockability as the whole attack's: a party is
			// one attack, and a restriction on any member gates the block for all of them.
			if (CardData.isSelfPartyCannotBeBlocked(fa.effectText(), card.name())
					&& isFormingParty(card, isP1)) return true;
			// The bare printing and the quoted-grant spelling both land here; the grant is what
			// Ritz prints, and it names its own carrier on both halves.
			if (CardData.isSelfCannotBeBlocked(fa.effectText(), card.name())) return true;
			CardData.SelfGainsQuotedGrant sgq =
					CardData.parseSelfGainsQuotedGrant(fa.effectText(), card.name());
			if (sgq == null) continue;
			for (String passive : sgq.passiveTexts())
				if (CardData.isSelfCannotBeBlocked(passive, card.name())) return true;
		}
		return false;
	}

	/**
	 * True when an attacker's "cannot be blocked by a Forward with greater power" restriction rules
	 * this blocker out.  Power is compared against the restricted attacker itself, so within a party
	 * only the members actually carrying the restriction gate the block.
	 */
	private boolean attackerHigherPowerFilterExcludes(ForwardTarget.CardZone blockerZone, int blockerIdx) {
		int blockerPower = fieldForwardPower(true, blockerZone, blockerIdx);
		for (ForwardTarget t : pendingP2AttackerTargets()) {
			CardData attacker = pendingAttackerCard(t);
			if (attacker != null && attacker.cannotBeBlockedByHigherPower()
					&& blockerPower > fieldForwardPower(false, t.zone(), t.idx())) return true;
		}
		return false;
	}

	/**
	 * True when an attacker's "cannot be blocked by a Forward of power N or more/less" restriction
	 * rules this blocker out (Ark Angel MR 8-045R).
	 *
	 * <p>An absolute threshold, where {@link #attackerHigherPowerFilterExcludes} is relative to the
	 * attacker's own power. The blocker's power is the effective one, so a pump applied to it in
	 * response can push it over the line — which is the interaction the card is sold on.
	 */
	private boolean attackerBlockPowerFiltersExclude(ForwardTarget.CardZone blockerZone, int blockerIdx) {
		int blockerPower = fieldForwardPower(true, blockerZone, blockerIdx);
		for (ForwardTarget t : pendingP2AttackerTargets())
			if (attackerPowerFilterExcludes(pendingAttackerCard(t), false, blockerPower)) return true;
		return false;
	}

	/**
	 * True when {@code attacker}'s absolute "cannot be blocked by a Forward of power N or
	 * more/less" restriction rules out a blocker of {@code blockerPower} — whether the restriction
	 * is printed (Ark Angel MR 8-045R) or granted for the turn (Iris 12-117R).
	 *
	 * <p>Seat-agnostic because both sides need it: {@code attackerBlockPowerFiltersExclude} asks it
	 * of a P2 attacker while P1 picks a blocker, and {@code ComputerPlayer.chooseBlocker} asks it of
	 * a P1 attacker while P2 does.
	 */
	boolean attackerPowerFilterExcludes(CardData attacker, boolean attackerIsP1, int blockerPower) {
		if (attacker == null) return false;
		int[] dyn = (attackerIsP1 ? p1CannotBeBlockedByPower : p2CannotBeBlockedByPower).get(attacker);
		if (dyn != null && blockerPowerExcluded(blockerPower, dyn)) return true;
		int[] intr = CardData.parseFieldCannotBeBlockedByPower(attacker.textEn(), attacker.name());
		return intr != null && blockerPowerExcluded(blockerPower, intr);
	}

	/** {@code filter} is {@code {powerVal, orMore}}, as {@link CardData#parseFieldCannotBeBlockedByPower} returns. */
	static boolean blockerPowerExcluded(int blockerPower, int[] filter) {
		return filter[1] == 1 ? blockerPower >= filter[0] : blockerPower <= filter[0];
	}

	/** True when the potential P1 blocker (given zone/idx) has strictly greater power than ANY attacker. */
	private boolean blockerPowerExceedsAttacker(ForwardTarget.CardZone blockerZone, int blockerIdx) {
		int blockerPower = fieldForwardPower(true, blockerZone, blockerIdx);
		for (ForwardTarget t : pendingP2AttackerTargets())
			if (blockerPower > fieldForwardPower(false, t.zone(), t.idx())) return true;
		return false;
	}

	/** True when ANY attacker (single or every party member) has strictly greater power than the blocker. */
	private boolean attackerPowerExceedsBlocker(ForwardTarget.CardZone blockerZone, int blockerIdx) {
		int blockerPower = fieldForwardPower(true, blockerZone, blockerIdx);
		for (ForwardTarget t : pendingP2AttackerTargets())
			if (fieldForwardPower(false, t.zone(), t.idx()) > blockerPower) return true;
		return false;
	}

	/**
	 * Returns true if the current P2 attacker's cost restrictions prevent a blocker of the
	 * given cost from blocking — checks dynamic (turn-scoped), intrinsic (field ability), and
	 * conditional (IfControlBoost) filters.
	 */
	private boolean attackerBlockCostFiltersExclude(int blockerCost) {
		for (ForwardTarget t : pendingP2AttackerTargets()) {
			CardData attacker = pendingAttackerCard(t);
			if (attacker == null) continue;
			if (allForwardsCannotBeBlockedByHigherCostThisTurn && blockerCost > attacker.cost()) return true;
			int[] dyn = p2CannotBeBlockedByCost.get(attacker);
			if (dyn != null && blockerCostExcluded(blockerCost, dyn)) return true;
			int[] intr = attacker.fieldCannotBeBlockedByCost();
			if (intr != null && blockerCostExcluded(blockerCost, intr)) return true;
			for (CardData src : p2FieldCards())
				for (IfControlBoost icb : src.ifControlBoosts())
					if (icb.cannotBeBlockedByCost() != null && icb.appliesToCard(attacker, jobsStripped(attacker))
							&& icbConditionsMet(icb, false)
							&& blockerCostExcluded(blockerCost, icb.cannotBeBlockedByCost()))
						return true;
		}
		return false;
	}

	/** True when a P1 monster acting as a Forward may be declared as a blocker. */
	boolean isMonsterBlockSelectable(int idx) {
		if (!p1InBlockDeclaration()) return false;
		if (idx < 0 || idx >= p1MonsterStates.size()) return false;
		if (Boolean.TRUE.equals(p1MonsterFrozen.get(idx))) return false;
		CardState s = p1MonsterStates.get(idx);
		if (s != CardState.ACTIVE) return false;
		if (!isP1MonsterTemporarilyForward(idx)) return false;
		if (!p1MustBlock.isEmpty()) return false;   // a Forward is forced to block
		if (p1ForwardCompelledToBlockIdxForPendingAttack() >= 0) return false;  // …against this attacker
		if (attackerUnblockable()) return false;
		// Jack Garland 29-123R: this method is the only path a Monster reaches a block by, so the
		// restriction is complete here for P1's side.
		if (pendingP2AttackBarsMonsterBlockers()) return false;
		CardData monsterBlocker = p1MonsterCards.get(idx);
		// Kitone 14-064R can point a per-turn block restriction at a Monster; instance keying is
		// what lets it reach this row at all.
		if (p1CannotBlock.contains(monsterBlocker)) return false;
		if (p1CannotBlockPersistent.contains(monsterBlocker)) return false;
		if (monsterBlocker.cannotBlockAtAll() || monsterBlocker.cannotAttackOrBlock()) return false;
		if (blockBarredByFieldCostLock(monsterBlocker)) return false;
		if (isFieldAbilityCannotAttackOrBlock(monsterBlocker, true)) return false;
		if (monsterBlocker.cannotBlockParty() && pendingP2PartyIndices != null) return false;
		if (monsterBlocker.cannotBlockHigherPower() && attackerPowerExceedsBlocker(ForwardTarget.CardZone.MONSTER, idx)) return false;
		if (p1Turn.forwardCannotBlockInferiorPower && blockerPowerExceedsAttacker(ForwardTarget.CardZone.MONSTER, idx)) return false;
		if (attackerBlockCostFiltersExclude(monsterBlocker.cost())) return false;
		if (attackerHigherPowerFilterExcludes(ForwardTarget.CardZone.MONSTER, idx)) return false;
		if (attackerBlockPowerFiltersExclude(ForwardTarget.CardZone.MONSTER, idx)) return false;
		return true;
	}

	/** True when a P1 backup acting as a Forward may be declared as a blocker. */
	private boolean isBackupBlockSelectable(int idx) {
		if (!p1InBlockDeclaration()) return false;
		if (idx < 0 || idx >= p1BackupCards.length || p1BackupCards[idx] == null) return false;
		if (p1BackupFrozen[idx]) return false;
		CardState s = p1BackupStates[idx];
		if (s != CardState.ACTIVE) return false;
		if (!isP1BackupTemporarilyForward(idx)) return false;
		if (!p1MustBlock.isEmpty()) return false;
		if (p1ForwardCompelledToBlockIdxForPendingAttack() >= 0) return false;
		if (attackerUnblockable()) return false;
		CardData backupBlocker = p1BackupCards[idx];
		// A Backup restricted while it was still a Backup keeps the restriction once something
		// turns it into a Forward for the turn — the restriction is on the card, not the row.
		if (p1CannotBlock.contains(backupBlocker)) return false;
		if (p1CannotBlockPersistent.contains(backupBlocker)) return false;
		if (backupBlocker.cannotBlockAtAll() || backupBlocker.cannotAttackOrBlock()) return false;
		if (blockBarredByFieldCostLock(backupBlocker)) return false;
		if (isFieldAbilityCannotAttackOrBlock(backupBlocker, true)) return false;
		if (backupBlocker.cannotBlockParty() && pendingP2PartyIndices != null) return false;
		if (backupBlocker.cannotBlockHigherPower() && attackerPowerExceedsBlocker(ForwardTarget.CardZone.BACKUP, idx)) return false;
		if (p1Turn.forwardCannotBlockInferiorPower && blockerPowerExceedsAttacker(ForwardTarget.CardZone.BACKUP, idx)) return false;
		if (attackerBlockCostFiltersExclude(backupBlocker.cost())) return false;
		if (attackerHigherPowerFilterExcludes(ForwardTarget.CardZone.BACKUP, idx)) return false;
		if (attackerBlockPowerFiltersExclude(ForwardTarget.CardZone.BACKUP, idx)) return false;
		return true;
	}

	private void toggleP1MonsterBlocker(int idx) {
		if (!isMonsterBlockSelectable(idx)) return;
		p1BlockerMonsterIdx = (p1BlockerMonsterIdx == idx) ? -1 : idx;
		p1BlockerSelection = -1;
		p1BlockerBackupIdx = -1;
		refreshAttackButton();
		refreshAllForwardSlots();
		for (int i = 0; i < p1BackupCards.length; i++) refreshP1BackupSlot(i);
	}

	private void toggleP1BackupBlocker(int idx) {
		if (!isBackupBlockSelectable(idx)) return;
		p1BlockerBackupIdx = (p1BlockerBackupIdx == idx) ? -1 : idx;
		p1BlockerSelection = -1;
		p1BlockerMonsterIdx = -1;
		refreshAttackButton();
		refreshAllForwardSlots();
		for (int i = 0; i < p1BackupCards.length; i++) refreshP1BackupSlot(i);
	}

	// -------------------------------------------------------------------------
	// Block resolution
	// -------------------------------------------------------------------------

	/** Called when P1 clicks the Attack/Block/Take-Damage button during block declaration. */
	private void handleP1BlockAction() {
		if (pendingP2PartyIndices != null) { handleP1PartyBlockAction(); return; }
		if (pendingP2Attacker == null) return;
		CardData attacker      = pendingP2Attacker;
		int      attackerIdx   = pendingP2AttackerIdx;
		Runnable onDone        = pendingP2BlockDone;
		boolean  isMonster     = pendingP2AttackerIsMonster;
		boolean  isBackup      = pendingP2AttackerIsBackup;

		// Determine the chosen blocker (a Forward, or a Monster/Backup acting as a Forward)
		ForwardTarget.CardZone blkZone = null;
		int blkIdx = -1;
		if (p1BlockerSelection >= 0)       { blkZone = ForwardTarget.CardZone.FORWARD; blkIdx = p1BlockerSelection; }
		else if (p1BlockerMonsterIdx >= 0) { blkZone = ForwardTarget.CardZone.MONSTER; blkIdx = p1BlockerMonsterIdx; }
		else if (p1BlockerBackupIdx >= 0)  { blkZone = ForwardTarget.CardZone.BACKUP;  blkIdx = p1BlockerBackupIdx; }

		// Must-block validation: done before clearing state so isForwardBlockSelectable still works.
		if (blkZone == null && blockIsCompelled(attacker, true)) {
			for (int i = 0; i < p1ForwardStates.size(); i++) {
				if (isForwardBlockSelectable(i)) {
					showEffectOptionDialog("You must block " + attacker.name()
							+ " — select an eligible Forward.", "Must Block", new Object[]{"OK"});
					return;
				}
			}
		}
		// Dio 26-075C: the compulsion sits on one Forward rather than on the attacker, so it also
		// has to be rejected when a different blocker was declared, not only when none was.
		if (p1ForwardCompelledToBlockIdx(attacker) >= 0
				&& !(blkZone == ForwardTarget.CardZone.FORWARD
						&& blkIdx == p1ForwardCompelledToBlockIdx(attacker))) {
			CardData compelled = p1ForwardCards.get(p1ForwardCompelledToBlockIdx(attacker));
			showEffectOptionDialog(compelled.name() + " must block " + attacker.name()
					+ " if possible.", "Must Block", new Object[]{"OK"});
			return;
		}

		// The answer goes out before the local board acts on it, so the attacking client is
		// resuming its combat while this one resolves the same battle.
		sendToOpponent(RemoteOpponent.blockAction(blkZone, blkIdx, null));

		// Clear pending state before any callbacks to avoid re-entrancy
		pendingP2Attacker           = null;
		pendingP2AttackerIdx        = -1;
		pendingP2BlockDone          = null;
		p1BlockerSelection          = -1;
		p1BlockerMonsterIdx         = -1;
		p1BlockerBackupIdx          = -1;
		pendingP2AttackerIsMonster  = false;
		pendingP2AttackerIsBackup   = false;
		pendingP2AttackerPower      = 0;
		refreshAttackButton();

		ForwardTarget.CardZone atkZone = isBackup ? ForwardTarget.CardZone.BACKUP
				: isMonster ? ForwardTarget.CardZone.MONSTER : ForwardTarget.CardZone.FORWARD;

		if (blkZone != null) {
			final ForwardTarget.CardZone fBlkZone = blkZone;
			final int fBlkIdx = blkIdx;
			CardData blocker;
			if (fBlkZone == ForwardTarget.CardZone.FORWARD) {
				CardData top = p1ForwardPrimedTop.get(fBlkIdx);
				blocker = (top != null) ? top : p1ForwardCards.get(fBlkIdx);
				p1BlockingIdx        = fBlkIdx;
				p1BlockedByAttacker  = attacker;
			} else {
				blocker = autoAbilityTriggers.fieldCardData(new ForwardTarget(true, fBlkIdx, fBlkZone));
			}
			autoAbilityTriggers.triggerAutoAbilitiesForBlock(blocker, true);
			autoAbilityTriggers.triggerAutoAbilitiesForIsBlocked(attacker, false);
			// Block declared: the turn player (P2) responds first, then P1, then damage.
			combatPriorityRound(false, blocker.name() + " blocks!", () -> {
				setAttackSubStep(3);
				if (atkZone == ForwardTarget.CardZone.FORWARD && fBlkZone == ForwardTarget.CardZone.FORWARD)
					resolveCombat(attacker, false, attackerIdx, blocker, true, fBlkIdx);
				else
					resolveActingCombat(false, atkZone, attackerIdx, true, fBlkZone, fBlkIdx);
				p1BlockingIdx       = -1;
				p1BlockedByAttacker = null;
				setAttackSubStep(-1);
				refreshAllForwardSlots();
				for (int i = 0; i < p1BackupCards.length; i++) refreshP1BackupSlot(i);
				onDone.run();
			});
		} else {
			combatPriorityRound(false, "No blocker declared — taking the damage.", () -> {
				setAttackSubStep(3);
				dealCombatDamageToOpponent(attacker, false, () -> {
					autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, false);
					setAttackSubStep(-1);
					onDone.run();
				});
			});
		}
	}

	private void handleP1PartyBlockAction() {
		List<Integer> attackerIndices = pendingP2PartyIndices;
		int           combinedPower   = pendingP2PartyCombined;
		Runnable      onDone          = pendingP2BlockDone;
		int           blockerIdx      = p1BlockerSelection;

		// Must-block validation: done before clearing state so isForwardBlockSelectable still works.
		if (blockerIdx < 0) {
			boolean partyMustBeBlocked = attackerIndices.stream()
					.anyMatch(i -> attackerMustBeBlocked(p2ForwardCards.get(i)))
					|| forwardsMustBlock(true);
			if (partyMustBeBlocked) {
				for (int i = 0; i < p1ForwardStates.size(); i++) {
					if (isForwardBlockSelectable(i)) {
						// No name when the compulsion is the field-wide kind — it names a side, not
						// an attacker, so there is no party member to point at.
						String mustBlockName = attackerIndices.stream()
								.filter(i2 -> attackerMustBeBlocked(p2ForwardCards.get(i2)))
								.map(i2 -> p2ForwardCards.get(i2).name())
								.findFirst().orElse("the attacking party");
						showEffectOptionDialog("You must block " + mustBlockName
								+ " — select an eligible Forward.", "Must Block", new Object[]{"OK"});
						return;
					}
				}
			}
		}
		// Dio 26-075C, party form: a compelled Forward must be the one declared, so this is
		// checked whether or not a blocker was named. See handleP1BlockAction for the single case.
		int partyCompelled = p1ForwardCompelledToBlockIdxForPendingAttack();
		if (partyCompelled >= 0 && blockerIdx != partyCompelled) {
			showEffectOptionDialog(p1ForwardCards.get(partyCompelled).name()
					+ " must block if possible.", "Must Block", new Object[]{"OK"});
			return;
		}

		pendingP2PartyIndices  = null;
		pendingP2PartyCombined = 0;
		pendingP2BlockDone     = null;
		p1BlockerSelection     = -1;
		refreshAttackButton();

		if (blockerIdx >= 0 && blockerIdx < p1ForwardCards.size()) {
			CardData top    = p1ForwardPrimedTop.get(blockerIdx);
			CardData blocker = (top != null) ? top : p1ForwardCards.get(blockerIdx);
			p1BlockingIdx = blockerIdx;
			autoAbilityTriggers.triggerAutoAbilitiesForBlock(blocker, true);
			for (int idx : attackerIndices)
				autoAbilityTriggers.triggerAutoAbilitiesForIsBlocked(p2ForwardCards.get(idx), false);
			// Block declared: the turn player (P2) responds first, then P1, then damage.
			combatPriorityRound(false, blocker.name() + " blocks the party!", () -> {
				setAttackSubStep(3);
				Map<Integer, Integer> spread =
						resolveP1BlockVsP2Party(blockerIdx, blocker, attackerIndices, combinedPower);
				// Unlike a single block, the party answer is not complete at declaration time: how
				// the blocker splits its damage is chosen during resolution, and the attacking
				// client needs that map to reach the same board. So the answer goes out here.
				sendToOpponent(RemoteOpponent.blockAction(
						ForwardTarget.CardZone.FORWARD, blockerIdx, spread));
				p1BlockingIdx       = -1;
				p1BlockedByAttacker = null;
				setAttackSubStep(-1);
				refreshAllForwardSlots();
				onDone.run();
			});
		} else {
			sendToOpponent(RemoteOpponent.blockAction(null, -1, null));
			combatPriorityRound(false, "No blocker declared — taking the party's damage.", () -> {
				setAttackSubStep(3);
				setPlayerDamageSource(partyExBurstSuppressor(attackerIndices, false));
				p1TakeDamage();
				for (int idx : attackerIndices)
					autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(p2ForwardCards.get(idx), false);
				setAttackSubStep(-1);
				onDone.run();
			});
		}
	}

	// -------------------------------------------------------------------------
	// Priority windows
	// -------------------------------------------------------------------------

	/** Sets attackSubStep and updates the phaseTracker sub-diamond. */
	private void setAttackSubStep(int step) {
		attackSubStep = step;
		if (phaseTracker != null && step >= 0
				&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK) {
			phaseTracker.setAttackStep(step);
		}
		if (nextPhaseButton != null && step == 1) nextPhaseButton.setEnabled(false);
	}

	/**
	 * Returns true if P1 has anything a priority window could be spent on: an action ability on the
	 * field, or a card in hand castable at Summon speed (a Summon, or a Back Attack Character).
	 * When this is false {@link #p1HoldPriority} passes automatically rather than stopping on a
	 * checkpoint the player could not act at.
	 */
	private boolean p1HasActivatableAbilities() {
		for (CardData c : fieldCards(true))
			if (hasFieldActionAbilities(c, true)) return true;
		for (CardData c : gameState.getP1Hand())
			if (c.castsAtSummonSpeed()) return true;
		return false;
	}

	/** Returns true if any P2 field card has at least one action ability. */
	private boolean p2HasActivatableAbilities() {
		for (CardData c : fieldCards(false))
			if (hasFieldActionAbilities(c, false)) return true;
		for (CardData c : gameState.getP2Hand())
			if (c.isSummon()) return true;
		return false;
	}

	/**
	 * Whether {@code c} has any action ability at all while on {@code isP1}'s field — printed, or
	 * borrowed from the removed-from-game zone. Clive 26-005H prints none of his own, so asking
	 * only {@link CardData#actionAbilities()} would skip every priority window he could act at.
	 */
	private boolean hasFieldActionAbilities(CardData c, boolean isP1) {
		if (c == null) return false;
		return !c.actionAbilities().isEmpty() || !rfgJobSpecialAbilities(c, isP1).isEmpty();
	}

	/**
	 * Auto-pass for the AI opponent: briefly flips the phase tracker to red (P2's priority),
	 * waits ~1.5 s, then restores blue and calls {@code onDone}.
	 *
	 * <p>The wait is deliberately not the only gate. Blocking choices pump the event queue, so this
	 * timer can fire while the player is still mid-decision — which is how a priority handoff once
	 * landed on top of an unresolved EX Burst target choice. {@link #runWhenBoardSettled} holds the
	 * handoff until the board is actually quiet.
	 */
	void p2AutoPass(Runnable onDone) {
		if (p2AutoPassTimer != null) { p2AutoPassTimer.stop(); p2AutoPassTimer = null; }
		phaseTracker.setHasPriority(false);
		p2AutoPassTimer = new Timer(1500, e -> {
			((Timer) e.getSource()).stop();
			p2AutoPassTimer = null;
			runWhenBoardSettled(() -> {
				// On P1's turn: let the opponent activate any reactive shields before passing.
				if (gameState.getCurrentPlayer() == GameState.Player.P1) {
					opponent.requestReactiveShields(() -> {
						phaseTracker.setHasPriority(true);
						onDone.run();
					});
				} else {
					phaseTracker.setHasPriority(true);
					onDone.run();
				}
			});
		});
		p2AutoPassTimer.setRepeats(false);
		p2AutoPassTimer.start();
	}

	/**
	 * Grants P1 priority during P2's main phase. Enables the Next Phase button so P1 can cast
	 * Summons or use Action abilities, then pass by clicking Next. Calling {@link #onNextPhase()}
	 * while this is active clears the state and runs {@code onPass}.
	 */
	void offerP1MainPhasePriority(Runnable onPass) {
		p1PriorityInP2MainOnDone = onPass;
		if (nextPhaseButton != null) nextPhaseButton.setEnabled(true);
		// P2 passes on a timer, so the hand popover may already be open — restate what is castable now.
		refreshHandCardStates();
		logEntry("[Priority] P2 passes — you may cast Summons or use abilities. Click Next Phase to pass.");
	}

	/**
	 * Combat checkpoint on P1's turn where P1 holds priority first — used after P1 declares an
	 * attacker. Instead of a pass-only popup, P1 keeps the board: they may cast a Summon or use an
	 * action ability and then click Next to pass, after which P2 responds (auto-pass) and
	 * {@code onPass} continues the combat step. When P1 has no action ability on the field and no
	 * Summon in hand there is nothing priority could be used for, so it passes automatically — the
	 * log says so, since combat otherwise appears to skip the checkpoint.
	 *
	 * @param announcement game-log line for what just happened, or {@code null} to log only the prompt
	 */
	private void p1HoldPriority(String announcement, Runnable onPass) {
		String lead = announcement != null ? announcement + " " : "";
		if (!p1HasActivatableAbilities()) {
			logEntry(lead + "No abilities or summons to use — passing priority automatically.");
			onPass.run();
			return;
		}
		logEntry(lead + "Use an ability or summon, or pass priority with 'Next'");
		p1CombatPriorityOnPass = onPass;
		// The window really is P1's now, so the tracker has to say so. refreshPhaseTracker paints
		// priority from the turn owner alone, which is right at a phase boundary and wrong here: on
		// P2's turn it leaves the indicator red while this method hands P1 the Next button, so the
		// board claimed P2 held priority and offered P1 the control to pass it.
		//
		// Attack Preparation on P2's turn is where that showed, because it is the one checkpoint
		// that reaches here directly -- offerP1AttackPrepPriority calls refreshPhaseTracker and then
		// this. Every other checkpoint on P2's turn arrives through opponentPriority, which already
		// flips the indicator back on its way out of P2's half of the round.
		//
		// Set after the early return above: a window that passes itself automatically never rests
		// with P1, and flashing the indicator for it would be a lie in the other direction.
		if (phaseTracker != null) phaseTracker.setHasPriority(true);
		if (nextPhaseButton != null) nextPhaseButton.setEnabled(true);
		refreshHandCardStates();   // the window just opened — recolour what is castable
		refreshAttackButton();   // Skip is not a legal action while holding priority mid-combat
	}

	/**
	 * Runs one full priority round at a combat checkpoint. The turn player holds priority first and
	 * their opponent second; {@code onBothPassed} runs only once both have passed, which is where
	 * combat moves on to its next sub-step. P1 holds priority through the Next button; P2 (the CPU)
	 * auto-passes.
	 *
	 * <p>Combat stays on the sub-step it is announcing for the whole round — a declaration is not
	 * "done" until both players have declined to respond to it.
	 *
	 * @param turnPlayerIsP1 whether the attacking (active) player is P1
	 * @param announcement   game-log line for the declaration this round follows
	 */
	private void combatPriorityRound(boolean turnPlayerIsP1, String announcement, Runnable onBothPassed) {
		if (turnPlayerIsP1) {
			p1HoldPriority(announcement, () -> { sendPriorityPass(); opponentPriority(onBothPassed); });
		} else {
			if (announcement != null) logEntry(announcement);
			opponentPriority(() -> p1HoldPriority(null,
					() -> { sendPriorityPass(); onBothPassed.run(); }));
		}
	}

	/**
	 * The opponent's half of a combat priority window: the AI takes it on a timer, a remote player
	 * takes it on their own client and this one waits to be told they are done.
	 *
	 * <p>The two clients mirror each other exactly here, which is what makes the wait safe.
	 * {@code turnPlayerIsP1} is written from the local seat, so it is true on one client and false
	 * on the other for the same round — exactly one of them takes each branch above, and a wait can
	 * never meet a wait.
	 *
	 * <p>The wait is modal but not inert: its nested event loop keeps delivering inbound actions,
	 * so a Summon the opponent casts <em>during</em> their window still arrives and is applied here
	 * before their pass releases it. That is the whole point of opening the window.
	 *
	 * <p>Reactive shields are not solicited on this path. They are an AI construct — for a human
	 * opponent, activating one is just using an action ability inside the window they now have.
	 */
	private void opponentPriority(Runnable onDone) {
		if (!(opponent instanceof RemoteOpponent remote)) {
			p2AutoPass(onDone);
			return;
		}
		phaseTracker.setHasPriority(false);
		runWhenBoardSettled(() -> {
			remote.awaitPriorityPass();
			// The wait can end because the pass arrived — or because the peer vanished and the
			// disconnect released it. Only the first means carry on with the battle.
			if (gameState.isP1GameOver()) return;
			// The wait can end because the pass arrived — or because the peer vanished and the
			// disconnect released it. Only the first means carry on with the battle.
			if (gameState.isP1GameOver()) return;
			phaseTracker.setHasPriority(true);
			onDone.run();
		});
	}

	/**
	 * Tells a remote opponent this client has passed a priority window they are holding open.
	 *
	 * <p>The rule this must not break is that a pass is only ever sent when the far client is
	 * already waiting for one — otherwise it sits in the delivered-answer buffer and releases some
	 * later window early. Both senders satisfy it: a combat round is mirrored, so the other client
	 * is in the opposite half of the same round, and a phase offer is followed immediately by the
	 * offerer waiting.
	 */
	private void sendPriorityPass() {
		if (opponent instanceof RemoteOpponent remote)
			remote.send(RemoteOpponent.choiceAction(ChoiceKind.PRIORITY_PASS, List.of()));
	}

	/**
	 * Passes priority at a phase transition and lets the opponent respond before the phase changes.
	 *
	 * <p>Unlike a combat round, the two clients are not both walking this checkpoint: only the
	 * player whose turn it is reaches the transition, and the other learns of it from the
	 * PHASE_ADVANCE that follows. So the offer is explicit — {@code PRIORITY_OFFER} goes out first
	 * and holds the phase open, and only when the answering pass arrives does {@code onDone} run
	 * and advance it. A response cast meanwhile is an ordinary PLAY_CARD and lands here inside the
	 * wait, in the phase it was actually made in.
	 *
	 * <p>Against the AI this is exactly the timer it always was.
	 */
	private void offerPhasePriority(Runnable onDone) {
		if (opponent instanceof RemoteOpponent remote)
			remote.send(GameAction.of(ActionType.PRIORITY_OFFER));
		opponentPriority(onDone);
	}

	/**
	 * The remote opponent is gone — cleanly, or because the socket died. Ends the game.
	 *
	 * <p><b>Order matters.</b> The game is marked over <em>before</em> the opponent is cancelled,
	 * because cancelling releases whatever was waiting on that peer and those releases run
	 * continuations: an outstanding block resumes as "no block", a priority wait returns and its
	 * callback would carry on into the next combat sub-step. Flagging first means they unwind into
	 * a finished game instead of playing on against nobody.
	 *
	 * <p>Releasing them at all is not optional. A choice wait is a modal dialog holding the EDT, so
	 * a peer that vanishes mid-question would otherwise leave this client frozen behind the
	 * disconnect notice — with no way to reach even the menu bar.
	 *
	 * <p>Both ends of a graceful goodbye arrive here: the DISCONNECT message first, then the socket
	 * closing behind it. The second is ignored, which is what the game-over guard is for.
	 */
	void onOpponentDisconnected(String reason) {
		if (!(opponent instanceof RemoteOpponent)) return;
		if (gameState.isP1GameOver()) return;
		triggerGameOver("Opponent disconnected — " + reason + ". The game cannot continue.");
		opponent.cancel();
	}

	/**
	 * The far side of {@link #offerPhasePriority}: the opponent has passed at a phase transition
	 * and is holding it open for this player.
	 *
	 * <p>Reuses the combat hold, which already serves both turns — P1 responds to P2's attacks the
	 * same way — and which auto-passes when there is nothing priority could be spent on. The pass
	 * is transmitted either way, so the opponent is never left waiting on a window this client
	 * silently skipped.
	 */
	void holdPriorityForPhaseOffer() {
		p1HoldPriority("[P2] passes priority.", this::sendPriorityPass);
	}

	/** True while P1 holds priority at a combat checkpoint and has not yet passed it with Next. */
	private boolean p1IsHoldingCombatPriority() {
		return p1CombatPriorityOnPass != null;
	}

	/**
	 * True from the moment P1 declares an attack until {@link #continueAttackPhase()} clears it.
	 * Combat deliberately stays on Declare Attackers for the whole declaration priority round, so
	 * {@code attackSubStep == 1} alone cannot tell a fresh Declare step from one whose attack is
	 * already resolving — and P1 no longer holds priority once they have passed it to P2. Skipping
	 * the phase or declaring a second attacker in that window would abandon the battle in progress,
	 * leaving the attacker dulled for nothing.
	 */
	private boolean p1AttackDeclarationInFlight() {
		return !p1DeclaredAttackers.isEmpty();
	}

	/**
	 * Attack Preparation on P2's turn: P2 has taken its actions, so P1 holds priority before P2 may
	 * declare an attacker. P1 passes with Next, which runs {@code onPassed}. The mirror of P1's own
	 * Attack Preparation, where P1 clicks Next and P2 auto-passes.
	 */
	void offerP1AttackPrepPriority(Runnable onPassed) {
		setAttackSubStep(0);
		refreshPhaseTracker();
		p1HoldPriority("Attack Preparation — [P2] passes.", onPassed);
	}

	/**
	 * The declared attackers still on the field. A response during the declaration's priority round
	 * can remove them, and with no attacker left there is nothing to block: combat skips the block
	 * step entirely.
	 */
	/** P1's attack ended before the block step because nothing is left attacking. */
	private void skipBlockStepNoAttackers() {
		logEntry("No attackers remain — Declare Blockers skipped.");
		setAttackSubStep(3);
		continueAttackPhase();
	}

	private List<CardData> survivingDeclaredAttackers(boolean attackerIsP1) {
		List<CardData> declared = attackerIsP1 ? p1DeclaredAttackers : p2DeclaredAttackers;
		List<CardData> onField  = attackerIsP1 ? p1ForwardCards      : p2ForwardCards;
		List<CardData> monsters = attackerIsP1 ? p1MonsterCards      : p2MonsterCards;
		CardData[]     backups  = attackerIsP1 ? p1BackupCards       : p2BackupCards;
		List<CardData> alive = new ArrayList<>();
		for (CardData c : declared) {
			if (identityIndexOf(onField, c) >= 0 || identityIndexOf(monsters, c) >= 0) { alive.add(c); continue; }
			for (CardData b : backups) if (b == c) { alive.add(c); break; }
		}
		return alive;
	}

	/** P1 clicked Next while holding combat priority: pass it on and continue the combat step. */
	private void passP1CombatPriority() {
		Runnable onPass = p1CombatPriorityOnPass;
		p1CombatPriorityOnPass = null;
		if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
		// The mirror of the grant in p1HoldPriority: priority has left P1 either way -- to P2 to
		// respond on P1's turn, or back to P2 to carry on with theirs. Whoever picks it up next
		// sets it themselves; this is what covers the gap until they do.
		if (phaseTracker != null) phaseTracker.setHasPriority(false);
		// On P1's turn P2 responds next; on P2's turn P1 is the one responding, so the round ends here.
		logEntry(gameState.getCurrentPlayer() == GameState.Player.P1
				? "[Priority] P1 passes — P2 may respond."
				: "[Priority] P1 passes.");
		refreshAttackButton();
		onPass.run();
	}

	// -------------------------------------------------------------------------
	// Combat timing and post-combat breaks
	// -------------------------------------------------------------------------

	/**
	 * True when a left-click on a P1 Forward slot means combat selection (declaring an attacker, or
	 * choosing a blocker on P2's turn) rather than opening the card's ability menu.  Attack
	 * Preparation and the post-declaration priority checkpoint are both "act with your cards"
	 * windows, so clicks there open the menu instead.
	 */
	private boolean p1ForwardClickSelectsCombat() {
		return gameState.getCurrentPhase() == GameState.GamePhase.ATTACK
				&& attackSubStep != 0 && p1CombatPriorityOnPass == null;
	}

	/**
	 * True when P1 currently holds a window in which {@code card} may be cast from hand.  This is
	 * the timing half of cast legality only — affordability, name conflicts, backup slots, cast
	 * limits and {@link #castRestrictionMet} are checked separately by each caller.
	 *
	 * <p>An ordinary Character may only be cast during P1's own Main Phase with the stack empty.
	 * A card that {@linkplain CardData#castsAtSummonSpeed() casts at Summon speed} — a Summon, or
	 * a Character with Back Attack — may also be cast at any priority window P1 holds: P2's Main
	 * Phase, either player's Attack Phase, and in response to an entry on the stack.
	 */
	boolean castTimingWindowOpen(CardData card) {
		boolean summonSpeed = card.castsAtSummonSpeed();
		GameState.GamePhase phase = gameState.getCurrentPhase();
		boolean isMainPhase = phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.MAIN_2;
		return ((isMainPhase || (p1MayActInAttackPhase() && summonSpeed)) && gameState.getStack().isEmpty()
					&& (phaseTracker.isMyTurn() || ((p1PriorityInP2MainOnDone != null
							|| p1IsHoldingCombatPriority()) && summonSpeed)))
				|| (p1IsRespondingToStack && summonSpeed);
	}

	/**
	 * True while P1 may act during an Attack Phase — cast a Summon or use an action ability that
	 * carries no attack-specific restriction: either their own Attack Preparation sub-step, or any
	 * combat checkpoint where they currently hold priority (including during P2's attacks).
	 */
	boolean p1MayActInAttackPhase() {
		if (p1IsHoldingCombatPriority()) return true;
		return gameState.getCurrentPhase() == GameState.GamePhase.ATTACK
				&& gameState.getCurrentPlayer() == GameState.Player.P1
				&& attackSubStep == 0;
	}

	/**
	 * Ends the battle-scoped state set up by "breaks after the attack or the block and doesn't deal
	 * any damage" (Vincent 2-078R): every card still marked is broken now, whether or not the battle
	 * itself broke it, and the damage suppression is lifted.  Safe to call after any battle — it is
	 * a no-op when nothing was marked.
	 */
	void resolvePostCombatBreaks() {
		dealsNoCombatDamageSet.clear();
		if (breakAfterCombatSet.isEmpty()) return;
		List<CardData> pending = new ArrayList<>(breakAfterCombatSet);
		breakAfterCombatSet.clear();
		for (CardData card : pending) {
			int p1Idx = p1ForwardCards.indexOf(card);
			int p2Idx = p2ForwardCards.indexOf(card);
			if (p1Idx < 0 && p2Idx < 0) continue;   // already broken or otherwise gone
			logEntry((p1Idx >= 0 ? "" : "[P2] ") + card.name() + " breaks after the battle");
			if (p1Idx >= 0) breakP1Forward(p1Idx); else breakP2Forward(p2Idx);
		}
	}

	/**
	 * After combat damage resolves, checks whether P1 has more eligible attackers.
	 * If yes, returns to sub-step 1 (Declare). If no, ends the attack phase.
	 */
	private void continueAttackPhase() {
		resolvePostCombatBreaks();
		// Every battle P1 declares ends here — both branches of all three single-attacker paths, and
		// the party path's onDone — which makes this the one place to digest the result. The
		// opponent's mirror of it is the finish runnable in initP1BlockDeclaration.
		sendCombatChecksum();
		p1AttackSelection.clear();
		p1DeclaredAttackers.clear();
		p1MonsterAttackIdx = -1;
		p1BackupAttackIdx = -1;
		// Combat is over: red comes off, and gray goes on for whatever has now spent its last
		// declaration. Both rows, since the opponent's screen is showing the same board.
		refreshCombatGlows();
		for (int i = 0; i < p1BackupCards.length; i++) refreshP1BackupSlot(i);
		if (attackDeclarationsExhausted(true)) {
			logEntry("Attack declaration limit reached — ending attack phase.");
			onNextPhase();
			return;
		}
		if (hasAttackableForward()) {
			setAttackSubStep(1);
			refreshAllForwardSlots();
			refreshAttackButton();
			logEntry("Select next attacker, or click Skip to end the Attack Phase.");
		} else {
			onNextPhase();
		}
	}

	// -------------------------------------------------------------------------
	// Monsters acting as Forwards
	// -------------------------------------------------------------------------

	/** Returns true when the P1 monster at {@code idx} currently has the Forward type. */
	boolean isP1MonsterTemporarilyForward(int idx) {
		if (idx < 0 || idx >= p1MonsterCards.size()) return false;
		CardData card = p1MonsterCards.get(idx);
		if (p1MonsterTempForwardPower.containsKey(card)) return true;
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		if (bfa == null) return false;
		if (bfa.minControlledMonsters() > 0) return p1MonsterCards.size() >= bfa.minControlledMonsters();
		if (bfa.damageThreshold()       > 0) return gameState.getP1DamageZone().size() >= bfa.damageThreshold();
		return gameState.getCurrentPlayer() == GameState.Player.P1;
	}

	/** Returns true when the P2 monster at {@code idx} currently has the Forward type. */
	boolean isP2MonsterTemporarilyForward(int idx) {
		if (idx < 0 || idx >= p2MonsterCards.size()) return false;
		CardData card = p2MonsterCards.get(idx);
		if (p2MonsterTempForwardPower.containsKey(card)) return true;
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		if (bfa == null) return false;
		if (bfa.minControlledMonsters() > 0) return p2MonsterCards.size() >= bfa.minControlledMonsters();
		if (bfa.damageThreshold()       > 0) return gameState.getP2DamageZone().size() >= bfa.damageThreshold();
		return gameState.getCurrentPlayer() == GameState.Player.P2;
	}

	/** Returns true when the P1 monster at {@code idx} can attack as a Forward this turn. */
	private boolean isMonsterSelectableAsForward(int idx) {
		if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return false;
		if (gameState.getCurrentPlayer() != GameState.Player.P1) return false;
		if (idx < 0 || idx >= p1MonsterStates.size()) return false;
		if (p1MonsterStates.get(idx) != CardState.ACTIVE) return false;
		CardData card = p1MonsterCards.get(idx);
		if (p1CannotAttack.contains(card) || p1CannotAttackPersistent.contains(card)) return false;
		if (card.cannotAttackOrBlock() || isFieldAbilityCannotAttackOrBlock(card, true)) return false;
		if (!p1MonsterTempForwardPower.containsKey(card)) {
			CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
			if (bfa == null) return false;
			if (bfa.damageThreshold() > 0 && gameState.getP1DamageZone().size() < bfa.damageThreshold()) return false;
		}
		return effectiveMonsterHasTrait(true, idx, CardData.Trait.HASTE)
				|| p1MonsterPlayedOnTurn.get(idx) != gameState.getTurnNumber();
	}

	/** Handles a left-click on a P1 monster slot during the attack phase. */
	private void handleP1MonsterLeftClick(int idx) {
		if (fieldTargetingActive) return;
		if (p1InBlockDeclaration()) { toggleP1MonsterBlocker(idx); return; }
		if (attackSubStep != 1 || p1IsHoldingCombatPriority() || p1AttackDeclarationInFlight()) return;
		if (!isMonsterSelectableAsForward(idx)) return;
		if (!p1AttackSelection.isEmpty()) {
			logEntry("Deselect the Forward first before selecting a Monster attacker.");
			return;
		}
		if (p1BackupAttackIdx >= 0) {
			logEntry("Deselect the Backup first before selecting a Monster attacker.");
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
		p1Turn.attackDeclarationsThisTurn++;
		CardData attacker = p1MonsterCards.get(monIdx);
		int attackerPower = p1MonsterForwardPower(monIdx);

		if (!effectiveMonsterHasTrait(true, monIdx, CardData.Trait.BRAVE)) {
			p1MonsterStates.set(monIdx, CardState.DULL);
			animateDullMonster(monIdx);
		}
		recordAttackDeclared(attacker);
		autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, true);

		p1DeclaredAttackers.clear();
		p1DeclaredAttackers.add(attacker);
		refreshCombatGlows();

		// Combat stays on Declare Attackers until both players have passed on the declaration.
		refreshAttackButton();
		sendToOpponent(RemoteOpponent.attackAction(
				ForwardTarget.CardZone.MONSTER, List.of(monIdx), attackerPower));

		combatPriorityRound(true, attacker.name() + " attacks! (Forward — " + attackerPower + ")", () -> {
			if (survivingDeclaredAttackers(true).isEmpty()) { skipBlockStepNoAttackers(); return; }
			setAttackSubStep(2);
			refreshAttackButton();
			opponent.requestBlocker(attackerPower,
					new ForwardTarget(true, monIdx, ForwardTarget.CardZone.MONSTER),
					blockIsCompelled(attacker, false), blk -> {
				if (blk != null) {
					CardData blocker = autoAbilityTriggers.fieldCardData(blk);
					logEntry("[P2] " + blocker.name() + " blocks!");
					autoAbilityTriggers.triggerAutoAbilitiesForBlock(blocker, false);
					if (blk.zone() == ForwardTarget.CardZone.FORWARD) { p2BlockingIdx = blk.idx(); p2BlockedByAttacker = attacker; }
					autoAbilityTriggers.triggerAutoAbilitiesForIsBlocked(attacker, true);
					combatPriorityRound(true, null, () -> {
						setAttackSubStep(3);
						resolveActingCombat(true, ForwardTarget.CardZone.MONSTER, monIdx, false, blk.zone(), blk.idx());
						p2BlockingIdx       = -1;
						p2BlockedByAttacker = null;
						continueAttackPhase();
					});
				} else {
					logEntry("[P2] declares no blocker.");
					combatPriorityRound(true, null, () -> {
						setAttackSubStep(3);
						dealCombatDamageToOpponent(attacker, true, () -> {
							autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, true);
							continueAttackPhase();
						});
					});
				}
			});
		});
	}

	// ── Generalized combat for cards acting as Forwards (any zone on either side) ──

	// -------------------------------------------------------------------------
	// Seat-agnostic field combat helpers
	// -------------------------------------------------------------------------

	int fieldForwardPower(boolean isP1, ForwardTarget.CardZone zone, int idx) {
		return switch (zone) {
			case FORWARD -> isP1 ? effectiveP1ForwardPower(idx) : effectiveP2ForwardPower(idx);
			case MONSTER -> isP1 ? p1MonsterForwardPower(idx)   : p2MonsterForwardPower(idx);
			case BACKUP  -> isP1 ? p1BackupForwardPower(idx)    : p2BackupForwardPower(idx);
			default      -> 0;
		};
	}

	private int fieldCombatDamage(boolean isP1, ForwardTarget.CardZone zone, int idx) {
		return switch (zone) {
			case FORWARD -> (isP1 ? p1ForwardDamage : p2ForwardDamage).get(idx);
			case MONSTER -> (isP1 ? p1MonsterDamage : p2MonsterDamage).get(idx);
			case BACKUP  -> {
				CardData c = (isP1 ? p1BackupCards : p2BackupCards)[idx];
				yield (isP1 ? p1BackupForwardDamage : p2BackupForwardDamage).getOrDefault(c, 0);
			}
			default -> 0;
		};
	}

	/**
	 * True when {@code damage} would break the Character in this slot: the power it has left, after
	 * the damage already on it, is no more than the incoming hit.
	 *
	 * <p>Reads the board as it stands. Damage reduction, "damage becomes 0" shields and anything the
	 * other player might do in response are not consulted, so this is for weighing a play — which is
	 * what both callers use it for — and not for deciding an outcome.
	 */
	boolean fieldForwardBreakableBy(boolean isP1, ForwardTarget.CardZone zone, int idx, int damage) {
		int power = fieldForwardPower(isP1, zone, idx);
		return power > 0 && power - fieldCombatDamage(isP1, zone, idx) <= damage;
	}

	private void addFieldCombatDamage(boolean isP1, ForwardTarget.CardZone zone, int idx, int amount) {
		switch (zone) {
			case FORWARD -> {
				List<Integer> dl = isP1 ? p1ForwardDamage : p2ForwardDamage;
				dl.set(idx, dl.get(idx) + amount);
				if (isP1) refreshP1ForwardSlot(idx); else refreshP2ForwardSlot(idx);
			}
			case MONSTER -> {
				List<Integer> dl = isP1 ? p1MonsterDamage : p2MonsterDamage;
				dl.set(idx, dl.get(idx) + amount);
				if (isP1) refreshP1MonsterSlot(idx); else refreshP2MonsterSlot(idx);
			}
			case BACKUP -> {
				CardData c = (isP1 ? p1BackupCards : p2BackupCards)[idx];
				(isP1 ? p1BackupForwardDamage : p2BackupForwardDamage).merge(c, amount, Integer::sum);
				if (isP1) refreshP1BackupSlot(idx); else refreshP2BackupSlot(idx);
			}
		}
	}

	void breakFieldCard(boolean isP1, ForwardTarget.CardZone zone, int idx) {
		switch (zone) {
			case FORWARD -> { if (isP1) breakP1Forward(idx);     else breakP2Forward(idx); }
			case MONSTER -> { if (isP1) autoAbilityTriggers.breakP1MonsterSlot(idx); else breakP2MonsterSlot(idx); }
			case BACKUP  -> { if (isP1) autoAbilityTriggers.breakP1BackupSlot(idx);  else breakP2BackupSlot(idx); }
		}
	}

	boolean fieldForwardTrait(boolean isP1, ForwardTarget.CardZone zone, int idx, CardData.Trait trait) {
		return switch (zone) {
			case FORWARD -> isP1 ? effectiveP1HasTrait(idx, trait) : effectiveP2HasTrait(idx, trait);
			case MONSTER -> effectiveMonsterHasTrait(isP1, idx, trait);
			case BACKUP  -> effectiveBackupHasTrait(isP1, idx, trait);
			default      -> false;
		};
	}

	/**
	 * Resolves combat where at least one participant is a Monster/Backup acting as a Forward.
	 * A card acting as a Forward is a Forward for every eligible purpose, so combat damage runs
	 * through the same outgoing/incoming modifier pipeline ({@link #modifyOutgoingCombatDamage},
	 * {@link #modifyIncomingDamage}) as a real Forward, and resolves First Strike, battle
	 * Breaktouch, and "deals damage to a Forward" break triggers identically.
	 * Forward-vs-Forward still uses {@link #resolveCombat}.
	 */
	private void resolveActingCombat(boolean atkP1, ForwardTarget.CardZone atkZone, int atkIdx,
			boolean blkP1, ForwardTarget.CardZone blkZone, int blkIdx) {
		CardData attacker = autoAbilityTriggers.fieldCardData(new ForwardTarget(atkP1, atkIdx, atkZone));
		CardData blocker  = autoAbilityTriggers.fieldCardData(new ForwardTarget(blkP1, blkIdx, blkZone));
		// The Backup/Monster half of the same staleness resolveCombat guards against: these indices
		// were recorded at block declaration, and a card that left the field in between leaves the
		// lookup with nothing to return. Skipped rather than resolved, so the Attack Phase carries
		// on instead of stalling on a NullPointerException.
		if (attacker == null || blocker == null) {
			logEntry("A combatant left the field before damage — combat skipped");
			return;
		}
		int atkPow = fieldForwardPower(atkP1, atkZone, atkIdx);
		int blkPow = fieldForwardPower(blkP1, blkZone, blkIdx);
		logEntry((atkP1 ? "" : "[P2] ") + attacker.name() + " (" + atkPow + ") vs "
				+ (blkP1 ? "" : "[P2] ") + blocker.name() + " (" + blkPow + ")");

		boolean atkFirst = fieldForwardTrait(atkP1, atkZone, atkIdx, CardData.Trait.FIRST_STRIKE)
				&& !fieldForwardTrait(blkP1, blkZone, blkIdx, CardData.Trait.FIRST_STRIKE);
		boolean blkFirst = fieldForwardTrait(blkP1, blkZone, blkIdx, CardData.Trait.FIRST_STRIKE)
				&& !fieldForwardTrait(atkP1, atkZone, atkIdx, CardData.Trait.FIRST_STRIKE);

		// Outgoing then incoming damage modifiers for each direction, exactly as resolveCombat does.
		int rawDmgToBlk = modifyOutgoingCombatDamage(atkP1, atkZone, atkIdx, atkPow, blocker);
		currentBattleAttacker = attacker; currentBattleAttackerIsP1 = atkP1;
		currentBattleAttackerIdx = atkIdx; currentBattleAttackerZone = atkZone;
		int dmgToBlk = modifyIncomingDamage(blkP1, blkZone, blkIdx, rawDmgToBlk, false, false);
		int rawDmgToAtk = modifyOutgoingCombatDamage(blkP1, blkZone, blkIdx, blkPow, attacker);
		currentBattleAttacker = blocker; currentBattleAttackerIsP1 = blkP1;
		currentBattleAttackerIdx = blkIdx; currentBattleAttackerZone = blkZone;
		int dmgToAtk = modifyIncomingDamage(atkP1, atkZone, atkIdx, rawDmgToAtk, false, false);
		currentBattleAttacker = null; currentBattleAttackerZone = ForwardTarget.CardZone.FORWARD;

		boolean atkBroken = dmgToAtk > 0 && fieldCombatDamage(atkP1, atkZone, atkIdx) + dmgToAtk >= atkPow;
		boolean blkBroken = dmgToBlk > 0 && fieldCombatDamage(blkP1, blkZone, blkIdx) + dmgToBlk >= blkPow;

		if (atkFirst && blkBroken)      { atkBroken = false; dmgToAtk = 0; }
		else if (blkFirst && atkBroken) { blkBroken = false; dmgToBlk = 0; }

		// Ahead of the breaks below, as in resolveCombat: a combatant killed by this blow was still
		// damaged by whoever struck it, and the "damaged by [X] …" printings read that on the way out.
		if (dmgToAtk > 0) recordDamagedBy(attacker, blocker);
		if (dmgToBlk > 0) recordDamagedBy(blocker,  attacker);

		if (atkBroken) breakFieldCard(atkP1, atkZone, atkIdx);
		else if (!blkFirst && dmgToAtk > 0) addFieldCombatDamage(atkP1, atkZone, atkIdx, dmgToAtk);

		if (blkBroken) breakFieldCard(blkP1, blkZone, blkIdx);
		else if (!atkFirst && dmgToBlk > 0) addFieldCombatDamage(blkP1, blkZone, blkIdx, dmgToBlk);

		// "When this Forward is dealt damage, break this Forward." — see resolveCombat.
		if (!atkBroken && breakOnDealtDamageGrant(atkP1, atkZone, atkIdx, attacker, dmgToAtk))
			atkBroken = true;
		if (!blkBroken && breakOnDealtDamageGrant(blkP1, blkZone, blkIdx, blocker, dmgToBlk))
			blkBroken = true;

		// Breaktouch (battle): temporary EOT grant — fires after main damage is resolved
		if (!blkBroken && dmgToBlk > 0 && breaktouchBattleSet.contains(attacker)) {
			logEntry((atkP1 ? "" : "[P2] ") + attacker.name() + " — Breaktouch! "
					+ (blkP1 ? "" : "[P2] ") + blocker.name() + " is broken.");
			breakFieldCard(blkP1, blkZone, blkIdx);
			blkBroken = true;
		}
		if (!atkBroken && dmgToAtk > 0 && breaktouchBattleSet.contains(blocker)) {
			logEntry((blkP1 ? "" : "[P2] ") + blocker.name() + " — Breaktouch! "
					+ (atkP1 ? "" : "[P2] ") + attacker.name() + " is broken.");
			breakFieldCard(atkP1, atkZone, atkIdx);
			atkBroken = true;
		}

		// Permanent "deals damage to forward" auto-abilities (e.g. Mandragora, Tonberry)
		if (dmgToBlk > 0 && !blkBroken) {
			if (fireBreaktouchForDamage(attacker, atkP1, blkP1, blkZone, blkIdx, dmgToBlk)) blkBroken = true;
		}
		if (dmgToAtk > 0 && !atkBroken) {
			if (fireBreaktouchForDamage(blocker, blkP1, atkP1, atkZone, atkIdx, dmgToAtk)) atkBroken = true;
		}

		if (!atkBroken && !blkBroken) {
			logEntry("Both forwards survive combat");
		}
	}

	/**
	 * Returns the power a P2 monster uses when attacking as a Forward. A temp-map entry (an
	 * effect that made it a Forward this turn, e.g. Gau) is applied later than the printed
	 * become-Forward ability, so it takes precedence while it lasts.
	 */
	int p2MonsterForwardPower(int idx) {
		CardData card = p2MonsterCards.get(idx);
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		Integer tempPower = p2MonsterTempForwardPower.get(card);
		int base = tempPower != null ? tempPower.intValue() : (bfa != null ? bfa.power() : 0);
		return base + computeConditionalBoostForTarget(card, false) + p2MonsterPowerBoost.getOrDefault(card, 0);
	}

	/** Returns true when the P2 monster at {@code idx} can attack as a Forward this turn. */
	boolean p2MonsterCanAttackAsForward(int idx) {
		if (p2MonsterStates.get(idx) != CardState.ACTIVE) return false;
		if (!isP2MonsterTemporarilyForward(idx)) return false;
		CardData card = p2MonsterCards.get(idx);
		if (p2CannotAttack.contains(card) || p2CannotAttackPersistent.contains(card)) return false;
		if (card.cannotAttackOrBlock() || isFieldAbilityCannotAttackOrBlock(card, false)) return false;
		return effectiveMonsterHasTrait(false, idx, CardData.Trait.HASTE)
				|| p2MonsterPlayedOnTurn.get(idx) != gameState.getTurnNumber();
	}

	// ── Backups acting as Forwards (e.g. 17-012R) ────────────────────────

	// -------------------------------------------------------------------------
	// Backups acting as Forwards
	// -------------------------------------------------------------------------

	private static int indexOfBackup(CardData[] backups, CardData card) {
		for (int i = 0; i < backups.length; i++) if (backups[i] == card) return i;
		return -1;
	}

	void makeP1BackupTemporaryForward(CardData source, int power) {
		int idx = indexOfBackup(p1BackupCards, source);
		if (idx < 0) return;
		p1BackupTempForwardPower.put(source, power);
		endOfTurnEffects.add(ctx -> {
			p1BackupTempForwardPower.remove(source);
			p1BackupForwardBoost.remove(source);
			p1BackupTempTraits.remove(source);
			p1BackupForwardDamage.remove(source);
			int still = indexOfBackup(p1BackupCards, source);
			if (still >= 0) refreshP1BackupSlot(still);
		});
		refreshP1BackupSlot(idx);
	}

	void makeP2BackupTemporaryForward(CardData source, int power) {
		int idx = indexOfBackup(p2BackupCards, source);
		if (idx < 0) return;
		p2BackupTempForwardPower.put(source, power);
		endOfTurnEffects.add(ctx -> {
			p2BackupTempForwardPower.remove(source);
			p2BackupForwardBoost.remove(source);
			p2BackupTempTraits.remove(source);
			p2BackupForwardDamage.remove(source);
			int still = indexOfBackup(p2BackupCards, source);
			if (still >= 0) refreshP2BackupSlot(still);
		});
		refreshP2BackupSlot(idx);
	}

	/** Power a P1 backup uses while acting as a Forward (become-Forward/temp base + boosts). */
	int p1BackupForwardPower(int idx) {
		CardData c = p1BackupCards[idx];
		if (c == null) return 0;
		CardData.BecomeForwardAbility bfa = c.becomeForwardAbility();
		int base = bfa != null ? bfa.power() : p1BackupTempForwardPower.getOrDefault(c, 0);
		return base + p1BackupForwardBoost.getOrDefault(c, 0);
	}

	int p2BackupForwardPower(int idx) {
		CardData c = p2BackupCards[idx];
		if (c == null) return 0;
		CardData.BecomeForwardAbility bfa = c.becomeForwardAbility();
		int base = bfa != null ? bfa.power() : p2BackupTempForwardPower.getOrDefault(c, 0);
		return base + p2BackupForwardBoost.getOrDefault(c, 0);
	}

	boolean isP1BackupTemporarilyForward(int idx) {
		if (idx < 0 || idx >= p1BackupCards.length) return false;
		CardData c = p1BackupCards[idx];
		if (c == null) return false;
		if (p1BackupTempForwardPower.containsKey(c)) return true;
		CardData.BecomeForwardAbility bfa = c.becomeForwardAbility();
		if (bfa == null) return false;
		if (bfa.damageThreshold() > 0) return gameState.getP1DamageZone().size() >= bfa.damageThreshold();
		return gameState.getCurrentPlayer() == GameState.Player.P1;
	}

	boolean isP2BackupTemporarilyForward(int idx) {
		if (idx < 0 || idx >= p2BackupCards.length) return false;
		CardData c = p2BackupCards[idx];
		if (c == null) return false;
		if (p2BackupTempForwardPower.containsKey(c)) return true;
		CardData.BecomeForwardAbility bfa = c.becomeForwardAbility();
		if (bfa == null) return false;
		if (bfa.damageThreshold() > 0) return gameState.getP2DamageZone().size() >= bfa.damageThreshold();
		return gameState.getCurrentPlayer() == GameState.Player.P2;
	}

	/** True when the backup at idx has {@code trait} innately or granted while acting as a Forward. */
	boolean effectiveBackupHasTrait(boolean isP1, int idx, CardData.Trait trait) {
		CardData[] backs = isP1 ? p1BackupCards : p2BackupCards;
		if (idx < 0 || idx >= backs.length || backs[idx] == null) return false;
		CardData c = backs[idx];
		if (c.hasTrait(trait)) return true;
		EnumSet<CardData.Trait> granted = (isP1 ? p1BackupTempTraits : p2BackupTempTraits).get(c);
		return granted != null && granted.contains(trait);
	}

	/** True when a P1 backup acting as a Forward may be declared as an attacker this turn. */
	private boolean isBackupSelectableAsForward(int idx) {
		if (gameState.getCurrentPhase() != GameState.GamePhase.ATTACK) return false;
		if (gameState.getCurrentPlayer() != GameState.Player.P1) return false;
		if (idx < 0 || idx >= p1BackupCards.length || p1BackupCards[idx] == null) return false;
		if (p1BackupStates[idx] != CardState.ACTIVE) return false;
		if (!isP1BackupTemporarilyForward(idx)) return false;
		CardData card = p1BackupCards[idx];
		if (p1CannotAttack.contains(card) || p1CannotAttackPersistent.contains(card)) return false;
		if (card.cannotAttackOrBlock() || isFieldAbilityCannotAttackOrBlock(card, true)) return false;
		return effectiveBackupHasTrait(true, idx, CardData.Trait.HASTE)
				|| p1BackupPlayedOnTurn[idx] != gameState.getTurnNumber();
	}

	boolean p2BackupCanAttackAsForward(int idx) {
		if (idx < 0 || idx >= p2BackupCards.length || p2BackupCards[idx] == null) return false;
		if (p2BackupStates[idx] != CardState.ACTIVE) return false;
		CardData card = p2BackupCards[idx];
		if (p2CannotAttack.contains(card) || p2CannotAttackPersistent.contains(card)) return false;
		if (card.cannotAttackOrBlock() || isFieldAbilityCannotAttackOrBlock(card, false)) return false;
		return isP2BackupTemporarilyForward(idx);
	}

	/** Handles a left-click on a P1 backup slot during the attack phase (attack as a Forward). */
	private void handleP1BackupLeftClick(int idx) {
		if (fieldTargetingActive) return;
		if (p1InBlockDeclaration()) { toggleP1BackupBlocker(idx); return; }
		if (attackSubStep != 1 || p1IsHoldingCombatPriority() || p1AttackDeclarationInFlight()) return;
		if (!isBackupSelectableAsForward(idx)) return;
		if (!p1AttackSelection.isEmpty()) {
			logEntry("Deselect the Forward first before selecting a Backup attacker.");
			return;
		}
		if (p1MonsterAttackIdx >= 0) {
			logEntry("Deselect the Monster first before selecting a Backup attacker.");
			return;
		}
		if (p1BackupAttackIdx == idx) {
			p1BackupAttackIdx = -1;
		} else {
			if (p1BackupAttackIdx >= 0) {
				int prev = p1BackupAttackIdx;
				p1BackupAttackIdx = -1;
				refreshP1BackupSlot(prev);
			}
			p1BackupAttackIdx = idx;
		}
		refreshAttackButton();
		refreshP1BackupSlot(idx);
	}

	private void executeP1BackupAttack(int bIdx) {
		p1Turn.attackDeclarationsThisTurn++;
		CardData attacker = p1BackupCards[bIdx];
		if (attacker == null) return;
		int attackerPower = p1BackupForwardPower(bIdx);

		if (!effectiveBackupHasTrait(true, bIdx, CardData.Trait.BRAVE)) {
			p1BackupStates[bIdx] = CardState.DULL;
			animateDullBackup(bIdx, true);
		}
		recordAttackDeclared(attacker);
		autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, true);

		p1DeclaredAttackers.clear();
		p1DeclaredAttackers.add(attacker);
		refreshCombatGlows();

		// Combat stays on Declare Attackers until both players have passed on the declaration.
		refreshAttackButton();
		sendToOpponent(RemoteOpponent.attackAction(
				ForwardTarget.CardZone.BACKUP, List.of(bIdx), attackerPower));

		combatPriorityRound(true, attacker.name() + " attacks! (Forward — " + attackerPower + ")", () -> {
			if (survivingDeclaredAttackers(true).isEmpty()) { skipBlockStepNoAttackers(); return; }
			setAttackSubStep(2);
			refreshAttackButton();
			opponent.requestBlocker(attackerPower,
					new ForwardTarget(true, bIdx, ForwardTarget.CardZone.BACKUP),
					blockIsCompelled(attacker, false), blk -> {
				if (blk != null) {
					CardData blocker = autoAbilityTriggers.fieldCardData(blk);
					logEntry("[P2] " + blocker.name() + " blocks!");
					autoAbilityTriggers.triggerAutoAbilitiesForBlock(blocker, false);
					if (blk.zone() == ForwardTarget.CardZone.FORWARD) { p2BlockingIdx = blk.idx(); p2BlockedByAttacker = attacker; }
					autoAbilityTriggers.triggerAutoAbilitiesForIsBlocked(attacker, true);
					combatPriorityRound(true, null, () -> {
						setAttackSubStep(3);
						resolveActingCombat(true, ForwardTarget.CardZone.BACKUP, bIdx, false, blk.zone(), blk.idx());
						p2BlockingIdx       = -1;
						p2BlockedByAttacker = null;
						continueAttackPhase();
					});
				} else {
					logEntry("[P2] declares no blocker.");
					combatPriorityRound(true, null, () -> {
						setAttackSubStep(3);
						dealCombatDamageToOpponent(attacker, true, () -> {
							autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, true);
							continueAttackPhase();
						});
					});
				}
			});
		});
	}

	/** @see DamageResolver#applyDamageToBackup */
	void applyDamageToBackup(boolean isP1, int idx, int amount) { damageResolver.applyDamageToBackup(isP1, idx, amount); }

	/**
	 * Clears all "Backup acting as Forward" state for both players (end of turn / reset).
	 *
	 * <p>The promotions in {@link #backupPermanentForwards} are held back: they outlast the turn and
	 * end only when the card leaves the field. Everything hung off a promotion — the boost, the
	 * granted traits and abilities, the damage it took as a Forward — is turn-scoped either way and
	 * still goes.
	 */
	void clearBackupForwardState() {
		p1BackupTempForwardPower.keySet().removeIf(c -> !backupPermanentForwards.contains(c));
		p2BackupTempForwardPower.keySet().removeIf(c -> !backupPermanentForwards.contains(c));
		p1BackupForwardBoost.clear();     p2BackupForwardBoost.clear();
		p1BackupTempTraits.clear();       p2BackupTempTraits.clear();
		p1BackupForwardDamage.clear();    p2BackupForwardDamage.clear();
		p1TempGrantedAbilities.clear();   p2TempGrantedAbilities.clear();
		p1BackupAttackIdx = -1; p2BackupAttackIdx = -1;
		for (int i = 0; i < p1BackupCards.length; i++) refreshP1BackupSlot(i);
		for (int i = 0; i < p2BackupCards.length; i++) refreshP2BackupSlot(i);
	}

	// -------------------------------------------------------------------------
	// Attack execution
	// -------------------------------------------------------------------------

	private void refreshAttackButton() {
		if (attackButton == null) return;
		boolean inAttack = gameState.getCurrentPhase() == GameState.GamePhase.ATTACK;
		boolean p1Turn   = gameState.getCurrentPlayer() == GameState.Player.P1;

		if (p1InBlockDeclaration()) {
			// Block declaration mode: P1 chooses a blocker by clicking a forward
			boolean hasBlocker = p1BlockerSelection >= 0 || p1BlockerMonsterIdx >= 0 || p1BlockerBackupIdx >= 0;
			attackButton.setText(hasBlocker ? "Block" : "Take Damage");
			attackButton.setEnabled(true);
		} else {
			int n = p1AttackSelection.size();
			boolean hasAnyAttacker = n > 0 || p1MonsterAttackIdx >= 0 || p1BackupAttackIdx >= 0;
			attackButton.setEnabled(inAttack && p1Turn && hasAnyAttacker && attackSubStep == 1
					&& !p1AttackDeclarationInFlight());
			attackButton.setText(n > 1 ? "Party Attack" : "Attack");
		}

		if (skipAttackButton != null)
			skipAttackButton.setEnabled(inAttack && p1Turn && attackSubStep == 1
					&& !p1InBlockDeclaration() && !p1IsHoldingCombatPriority()
					&& !p1AttackDeclarationInFlight());
	}

	void executeP1Attack(List<Integer> selection) {
		if (selection.isEmpty()) return;
		p1Turn.attackDeclarationsThisTurn++;

		// Dull the attackers (Brave ones stay active) and trigger their attack auto-abilities
		for (int idx : selection) {
			CardState stateBefore = p1ForwardStates.get(idx);
			if (!effectiveP1HasTrait(idx, CardData.Trait.BRAVE)) {
				p1ForwardStates.set(idx, CardState.DULL);
				animateDullForward(idx, null);
				if (stateBefore == CardState.ACTIVE)
					autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(p1ForwardCards.get(idx), true);
			}
			recordAttackDeclared(effectiveP1Forward(idx));
		}
		for (int idx : selection)
			autoAbilityTriggers.triggerAutoAbilitiesForAttack(
					p1ForwardPrimedTop.get(idx) != null ? p1ForwardPrimedTop.get(idx) : p1ForwardCards.get(idx), true);

		// The button emptied p1AttackSelection when it fired the declaration; record who is actually
		// attacking so attack-conditional abilities stay usable while P1 holds priority.
		p1DeclaredAttackers.clear();
		for (int idx : selection) p1DeclaredAttackers.add(effectiveP1Forward(idx));
		refreshCombatGlows();

		// Combat stays on Declare Attackers until both players have passed on the declaration.
		refreshAttackButton();
		sendToOpponent(RemoteOpponent.attackAction(ForwardTarget.CardZone.FORWARD, selection,
				selection.stream().mapToInt(this::effectiveP1ForwardPower).sum()));

		if (selection.size() == 1) {
			int idx = selection.get(0);
			CardData attacker = effectiveP1Forward(idx);
			// P1 attacks → P1 holds priority first; combat stays on Declare Attackers until both pass.
			combatPriorityRound(true, attacker.name() + " attacks!", () -> {
				if (survivingDeclaredAttackers(true).isEmpty()) { skipBlockStepNoAttackers(); return; }
				setAttackSubStep(2);
				refreshAttackButton();
				opponent.requestBlocker(effectiveP1ForwardPower(idx),
						new ForwardTarget(true, idx, ForwardTarget.CardZone.FORWARD),
						blockIsCompelled(attacker, false), blk -> {
					if (blk != null) {
						CardData blocker = autoAbilityTriggers.fieldCardData(blk);
						logEntry("[P2] " + blocker.name() + " blocks!");
						autoAbilityTriggers.triggerAutoAbilitiesForBlock(blocker, false);
						if (blk.zone() == ForwardTarget.CardZone.FORWARD) { p2BlockingIdx = blk.idx(); p2BlockedByAttacker = attacker; }
						autoAbilityTriggers.triggerAutoAbilitiesForIsBlocked(attacker, true);
						// Second round: both players may respond to the block before damage.
						combatPriorityRound(true, null, () -> {
							setAttackSubStep(3);
							if (blk.zone() == ForwardTarget.CardZone.FORWARD)
								resolveCombat(attacker, true, idx, blocker, false, blk.idx());
							else
								resolveActingCombat(true, ForwardTarget.CardZone.FORWARD, idx, false, blk.zone(), blk.idx());
							p2BlockingIdx       = -1;
							p2BlockedByAttacker = null;
							continueAttackPhase();
						});
					} else {
						logEntry("[P2] declares no blocker.");
						combatPriorityRound(true, null, () -> {
							setAttackSubStep(3);
							dealCombatDamageToOpponent(attacker, true, () -> {
								autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, true);
								continueAttackPhase();
							});
						});
					}
				});
			});
		} else {
			int combinedPower = 0;
			StringBuilder names = new StringBuilder();
			for (int idx : selection) {
				combinedPower += effectiveP1ForwardPower(idx);
				if (names.length() > 0) names.append(", ");
				names.append(p1ForwardCards.get(idx).name());
			}
			p1Turn.formedPartyThisTurn = true;
			List<CardData> p1PartyMembers = selection.stream()
					.map(p1ForwardCards::get).collect(Collectors.toList());
			autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(true, p1PartyMembers);
			final int fCombined = combinedPower;
			combatPriorityRound(true, "Party Attack! " + names + " (" + combinedPower + " combined)", () -> {
				if (survivingDeclaredAttackers(true).isEmpty()) { skipBlockStepNoAttackers(); return; }
				setAttackSubStep(2);
				refreshAttackButton();
				p2OfferBlockParty(selection, fCombined, this::continueAttackPhase);
			});
		}
	}

	// -------------------------------------------------------------------------
	// Party attack and block resolution, AI picks
	// -------------------------------------------------------------------------

	private void p2OfferBlockParty(List<Integer> attackerIndices, int combinedPower, Runnable onDone) {
		// Blocking a party means blocking every member, so one member carrying the compulsion
		// forces the block — the same reading handleP1PartyBlockAction takes on the human side.
		boolean forced = attackerIndices.stream()
				.anyMatch(i -> i < p1ForwardCards.size() && attackerMustBeBlocked(p1ForwardCards.get(i)))
				|| forwardsMustBlock(false);
		opponent.requestPartyBlocker(attackerIndices, combinedPower, forced, chosenIdx -> {
			if (chosenIdx != null) {
				final int blockerIdx   = chosenIdx;
				CardData  blocker      = p2ForwardCards.get(blockerIdx);
				final int blockerPower = effectiveP2ForwardPower(blockerIdx);
				logEntry("[P2] " + blocker.name() + " blocks the party!");
				// Both players may respond to the block before damage is worked out.
				combatPriorityRound(true, null, () -> {
					setAttackSubStep(3);
					// Party has First Strike only if every attacker has it and the blocker does not
					boolean partyFirst = attackerIndices.stream()
							.allMatch(i -> effectiveHasTrait(true, i, CardData.Trait.FIRST_STRIKE))
							&& !effectiveHasTrait(false, blockerIdx, CardData.Trait.FIRST_STRIKE);
					boolean blockerBroken = combinedPower >= blockerPower;
					// See resolveP1BlockVsP2Party — the combined power is one instance of damage.
					if (combinedPower > 0) autoAbilityTriggers.fireIsDealtDamageTriggers(blocker, false, combinedPower);
					// Every member of the party dealt part of that one instance, so each is a damager
					// of the blocker. Recorded before the break, as everywhere damage lands.
					if (combinedPower > 0)
						for (int i : attackerIndices)
							if (i < p1ForwardCards.size()) recordDamagedBy(blocker, p1ForwardCards.get(i));
					if (blockerBroken) breakP2Forward(blockerIdx);
					if (!partyFirst || !blockerBroken) {
						// How the blocker spreads its damage is the opponent's call.
						opponent.requestPartyBlockerDamage(attackerIndices, blockerPower, damageMap -> {
							applyPartyBlockerDamage(damageMap, blocker);
							if (onDone != null) onDone.run();
						});
					} else {
						logEntry("First Strike — party takes no return damage");
						if (onDone != null) onDone.run();
					}
				});
			} else {
				logEntry("[P2] declares no blocker.");
				combatPriorityRound(true, null, () -> {
					setAttackSubStep(3);
					setPlayerDamageSource(partyExBurstSuppressor(attackerIndices, true));
					p2TakeDamage(onDone);
				});
			}
		});
	}

	/**
	 * Builds the AI's optimal damage assignment for a party-attack block.
	 * Package-private: {@link ComputerPlayer} delegates to it, and it is also P1's fallback
	 * when the party-damage dialog is dismissed without an assignment.
	 */
	Map<Integer, Integer> p2AiBuildDamageMap(List<Integer> attackerIndices, int blockerPower) {
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

	/**
	 * Returns {@code true} if any party member other than {@code damagedIdx} has a
	 * "forming a party with [self]" field ability, nullifying that Forward's combat damage.
	 */
	private boolean partyProtectionApplies(Set<Integer> partySet, int damagedIdx, boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		for (int protectorIdx : partySet) {
			if (protectorIdx == damagedIdx || protectorIdx >= fwds.size()) continue;
			CardData protector = fwds.get(protectorIdx);
			for (FieldAbility fa : protector.fieldAbilities()) {
				Matcher m = AutoAbilityTriggers.FA_PARTY_DAMAGE_PROTECTION.matcher(fa.effectText());
				if (m.find() && m.group("source").trim().equalsIgnoreCase(protector.name()))
					return true;
			}
		}
		return false;
	}

	/**
	 * Applies a party-block damage map: logs, updates p1ForwardDamage, and breaks lethal targets.
	 * {@code blocker} is the P2 Forward whose power was spread across the party — the damager of
	 * record for every entry in the map.
	 */
	private void applyPartyBlockerDamage(Map<Integer, Integer> damageMap, CardData blocker) {
		if (damageMap.isEmpty()) return;
		Set<Integer> partySet = damageMap.keySet();
		for (Map.Entry<Integer, Integer> entry : damageMap.entrySet()) {
			int idx = entry.getKey(), dmg = entry.getValue();
			if (idx >= p1ForwardCards.size()) continue;
			if (partySet.size() >= 2 && partyProtectionApplies(partySet, idx, true)) {
				logEntry(p1ForwardCards.get(idx).name() + " — party damage nullified");
				continue;
			}
			p1ForwardDamage.set(idx, p1ForwardDamage.get(idx) + dmg);
			logEntry("[P2] Deals " + dmg + " damage to " + p1ForwardCards.get(idx).name());
			// One instance of damage per party member the blocker's power was spread across, each
			// firing "is dealt damage" triggers in its own right — see resolveCombat.
			autoAbilityTriggers.fireIsDealtDamageTriggers(p1ForwardCards.get(idx), true, dmg);
			if (dmg > 0) recordDamagedBy(p1ForwardCards.get(idx), blocker);
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
	/** @return the damage the blocker spread across the party, empty when First Strike stopped it. */
	private Map<Integer, Integer> resolveP1BlockVsP2Party(int blockerIdx, CardData blocker,
			List<Integer> attackerIndices, int combinedPower) {
		// Party has First Strike only if every attacker has it and the blocker does not
		boolean partyFirst = attackerIndices.stream()
				.allMatch(i -> effectiveHasTrait(false, i, CardData.Trait.FIRST_STRIKE))
				&& !effectiveHasTrait(true, blockerIdx, CardData.Trait.FIRST_STRIKE);

		int blockerPower = effectiveP1ForwardPower(blockerIdx);
		logEntry("[P2] Party deals " + combinedPower + " damage to " + blocker.name());
		boolean blockerBroken = combinedPower >= blockerPower;
		// The party's combined power is one instance of damage to the blocker; triggers fire on it
		// ahead of the break, as everywhere else damage lands.
		if (combinedPower > 0) autoAbilityTriggers.fireIsDealtDamageTriggers(blocker, true, combinedPower);
		// Each member dealt part of that instance, so each is a damager of the blocker.
		if (combinedPower > 0)
			for (int i : attackerIndices)
				if (i < p2ForwardCards.size()) recordDamagedBy(blocker, p2ForwardCards.get(i));
		if (blockerBroken) breakP1Forward(blockerIdx);

		if (!partyFirst || !blockerBroken) {
			List<CardData> attackerCards = new ArrayList<>();
			int[] effectivePowers = new int[attackerIndices.size()];
			for (int i = 0; i < attackerIndices.size(); i++) {
				int idx = attackerIndices.get(i);
				attackerCards.add(p2ForwardCards.get(idx));
				effectivePowers[i] = effectiveP2ForwardPower(idx);
			}
			Map<Integer, Integer> damageMap = cardPickerDialog.assignPartyDamage(
					attackerIndices, attackerCards, effectivePowers, blockerPower);
			if (damageMap.isEmpty()) damageMap = p2AiBuildDamageMap(attackerIndices, blockerPower);
			applyP2PartyAttackerDamage(damageMap, blocker);
			return damageMap;
		}
		logEntry("First Strike — party takes no return damage");
		return Map.of();
	}

	/**
	 * Applies a damage map onto P2 party attackers; breaks those that reach lethal. {@code blocker}
	 * is the P1 Forward whose power was spread across them — see {@link #applyPartyBlockerDamage}.
	 */
	private void applyP2PartyAttackerDamage(Map<Integer, Integer> damageMap, CardData blocker) {
		if (damageMap.isEmpty()) return;
		Set<Integer> partySet = damageMap.keySet();
		for (Map.Entry<Integer, Integer> entry : damageMap.entrySet()) {
			int idx = entry.getKey(), dmg = entry.getValue();
			if (idx >= p2ForwardCards.size()) continue;
			if (partySet.size() >= 2 && partyProtectionApplies(partySet, idx, false)) {
				logEntry(p2ForwardCards.get(idx).name() + " — party damage nullified");
				continue;
			}
			p2ForwardDamage.set(idx, p2ForwardDamage.get(idx) + dmg);
			logEntry("Deals " + dmg + " damage to " + p2ForwardCards.get(idx).name());
			// See applyPartyBlockerDamage — one instance of damage per party member.
			autoAbilityTriggers.fireIsDealtDamageTriggers(p2ForwardCards.get(idx), false, dmg);
			if (dmg > 0) recordDamagedBy(p2ForwardCards.get(idx), blocker);
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

	/**
	 * AI picks one of P2's Forwards to selectively take {@code amount} damage. Prefers a Forward
	 * whose effective power (minus current damage) exceeds {@code amount} so it survives;
	 * if none, falls back to the lowest-cost Forward (least valuable loss).
	 */
	ForwardTarget aiPickForwardToSurvive(int amount) {
		if (p2ForwardCards.isEmpty()) return null;
		int bestSurvivorIdx = -1;
		int bestSurvivorMargin = -1;
		int bestFallbackIdx = 0;
		int bestFallbackCost = Integer.MAX_VALUE;
		for (int i = 0; i < p2ForwardCards.size(); i++) {
			int effPower = effectiveP2ForwardPower(i);
			int remaining = effPower - p2ForwardDamage.get(i);
			if (remaining > amount) {
				int margin = remaining - amount;
				if (margin > bestSurvivorMargin) { bestSurvivorMargin = margin; bestSurvivorIdx = i; }
			}
			int cost = p2ForwardCards.get(i).cost();
			if (cost < bestFallbackCost) { bestFallbackCost = cost; bestFallbackIdx = i; }
		}
		int chosen = bestSurvivorIdx >= 0 ? bestSurvivorIdx : bestFallbackIdx;
		return new ForwardTarget(false, chosen, ForwardTarget.CardZone.FORWARD);
	}

	List<ForwardTarget> aiPickForwardsOrMonstersForBreak(int maxCount, boolean inclForwards, boolean inclMonsters) {
		List<ForwardTarget> eligible = new ArrayList<>();
		if (inclForwards)
			for (int i = 0; i < p2ForwardCards.size(); i++)
				eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
		if (inclMonsters)
			for (int i = 0; i < p2MonsterCards.size(); i++)
				eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER));
		eligible.sort(java.util.Comparator.comparingInt(t -> {
			CardData c = t.zone() == ForwardTarget.CardZone.FORWARD
					? p2ForwardCards.get(t.idx()) : p2MonsterCards.get(t.idx());
			return c.cost();
		}));
		return eligible.subList(0, Math.min(maxCount, eligible.size()));
	}

	ForwardTarget aiPickForwardForBreak() {
		if (p2ForwardCards.isEmpty()) return null;
		int worstIdx = 0;
		int worstCost = Integer.MAX_VALUE;
		for (int i = 0; i < p2ForwardCards.size(); i++) {
			int cost = p2ForwardCards.get(i).cost();
			if (cost < worstCost) { worstCost = cost; worstIdx = i; }
		}
		return new ForwardTarget(false, worstIdx, ForwardTarget.CardZone.FORWARD);
	}

	/** Returns the index of the least-valuable card in {@code hand} (lowest cost; backups before forwards). */
	static int pickWorstHandCard0(List<CardData> hand) {
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

	/**
	 * The most attack declarations {@code isP1} may make this turn — the turn-state limit an effect
	 * may have set (Folka 22-104R), narrowed by any continuous cap the opposing field imposes.
	 *
	 * <p>The Night Dancer 17-078R is the only such cap: "Your opponent may only declare as many
	 * attacks in the same turn as the number of Backups they control." Read here rather than
	 * written into {@code attackDeclarationLimit} because it moves with the board — a Backup
	 * entering or leaving changes it mid-phase, and a stored value would be stale.
	 *
	 * <p>The lowest cap wins when several apply, which is what {@code min} across the scan gives.
	 */
	int effectiveAttackDeclarationLimit(boolean isP1) {
		int limit = turn(isP1).attackDeclarationLimit;
		for (CardData src : fieldCards(!isP1)) {
			if (lostAbilitiesCards.contains(src)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(src))
				if (AutoAbilityTriggers.FA_OPP_ATTACKS_LIMITED_BY_OWN_BACKUPS.matcher(fa.effectText().trim()).matches())
					limit = Math.min(limit, backupCount(isP1));
		}
		return limit;
	}

	/**
	 * Extra generic CP {@code userIsP1} must pay to use any of their Characters' action abilities —
	 * The Emperor 20-092R's "The cost required for the Characters opponent controls to use action
	 * abilities is increased by 《2》."
	 *
	 * <p>Read off the opposing field, since the sentence taxes its controller's opponent. Several
	 * copies stack, which is what summing rather than maxing gives.
	 */
	int actionAbilityCostIncreaseFor(boolean userIsP1) {
		int extra = 0;
		for (CardData src : fieldCards(!userIsP1)) {
			if (lostAbilitiesCards.contains(src)) continue;
			for (FieldAbility fa : effectiveFieldAbilities(src)) {
				Matcher m = AutoAbilityTriggers.FA_OPP_ACTION_ABILITY_COST_INCREASE
						.matcher(fa.effectText().trim());
				if (m.matches()) extra += AutoAbilityTriggers.actionAbilityCostIncreaseAmount(m);
			}
		}
		return extra;
	}

	/**
	 * {@code ability} as its user actually has to pay for it — its own inline reduction applied
	 * first, then any tax the opposing field levies.
	 *
	 * <p>Reduction before increase, so a discounted ability under The Emperor pays the tax rather
	 * than having it cancelled out of the discount. The two are separate effects on the same cost
	 * and neither is worded to consume the other.
	 */
	ActionAbility effectiveAbilityCost(ActionAbility ability, boolean isP1) {
		ActionAbility eff = ability.inlineCostReductionJob() != null
				? ability.withReducedCp(computeInlineReduction(
						ability.inlineCostReductionJob(), ability.inlineCostReductionExcludeName(), isP1))
				: ability;
		return eff.withIncreasedCp(actionAbilityCostIncreaseFor(isP1));
	}

	/** Whether {@code isP1} has spent every attack declaration {@link #effectiveAttackDeclarationLimit} allows. */
	boolean attackDeclarationsExhausted(boolean isP1) {
		return turn(isP1).attackDeclarationsThisTurn >= effectiveAttackDeclarationLimit(isP1);
	}

	/** How many Backups {@code isP1} currently controls. */
	private int backupCount(boolean isP1) {
		int n = 0;
		for (CardData b : (isP1 ? p1BackupCards : p2BackupCards)) if (b != null) n++;
		return n;
	}

	/** How many Forwards {@code isP1} currently controls. */
	int forwardCount(boolean isP1) {
		return (isP1 ? p1ForwardCards : p2ForwardCards).size();
	}

	/** Package-private so the phase-continuation rule can be asserted directly. */
	boolean hasAttackableForward() {
		// The declaration cap is asked first: with none left there is no attacker to find, whatever
		// the rows hold. This is the gate for both the phase-entry skip and the between-attacks
		// continue, so The Night Dancer 17-078R closes the phase at zero Backups rather than
		// allowing one attack through before the post-combat check notices.
		if (attackDeclarationsExhausted(true)) return false;
		int turn = gameState.getTurnNumber();
		for (int i = 0; i < p1ForwardStates.size(); i++) {
			CardData fwd = p1ForwardCards.get(i);
			// hasAttackRemaining as well as ACTIVE, to stay in step with isForwardSelectable.
			// Dulling on attack is what normally takes a Forward out of this loop, and Brave does
			// not dull: without the count a Brave attacker stayed eligible here for the rest of the
			// phase while being unclickable there, so the phase never closed itself and the only
			// way out was Skip.
			if (p1ForwardStates.get(i) == CardState.ACTIVE
					&& hasAttackRemaining(effectiveP1Forward(i))
					&& !p1CannotAttack.contains(fwd)
					&& !p1CannotAttackPersistent.contains(fwd)
					&& !Boolean.TRUE.equals(p1ForwardFrozen.get(i))
					&& !fwd.cannotAttackOrBlock()
					&& !isFieldAbilityCannotAttackOrBlock(fwd, true)
					&& !isFieldAbilityCannotAttack(fwd, true)
					&& (effectiveP1HasTrait(i, CardData.Trait.HASTE)
					    || p1ForwardPlayedOnTurn.get(i) != turn))
				return true;
		}
		for (int i = 0; i < p1MonsterStates.size(); i++) {
			if (isMonsterSelectableAsForward(i)) return true;
		}
		for (int i = 0; i < p1BackupCards.length; i++) {
			if (isBackupSelectableAsForward(i)) return true;
		}
		return false;
	}

	// -------------------------------------------------------------------------
	// Field context menus
	// -------------------------------------------------------------------------

	/** Shows a context menu for a P1 forward slot. */
	private void showForwardContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();

		// Action abilities (use effective card — top card when primed)
		CardData effectiveFwd = p1ForwardPrimedTop.get(idx) != null
				? p1ForwardPrimedTop.get(idx) : p1ForwardCards.get(idx);
		autoAbilityTriggers.addAbilityMenuItems(menu, effectiveFwd, p1ForwardFrozen.get(idx),
				p1ForwardStates.get(idx), p1ForwardPlayedOnTurn.get(idx),
				() -> {
					CardState p1AACostBefore = p1ForwardStates.get(idx);
					p1ForwardStates.set(idx, CardState.DULL);
					animateDullForward(idx, null);
					if (p1AACostBefore == CardState.ACTIVE)
						autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(p1ForwardCards.get(idx), true);
				}, true);

		// Prime — visible only when not yet primed
		CardData fwd = p1ForwardCards.get(idx);
		if (fwd.hasPriming() && p1ForwardPrimedTop.get(idx) == null) {
			GameState.GamePhase phase = gameState.getCurrentPhase();
			boolean isMainPhase = phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.MAIN_2;
			JMenuItem primeItem = new JMenuItem("Prime (" + fwd.primingTarget() + ")");
			primeItem.setEnabled(isMainPhase && priming.canAffordPrimingCost(fwd)
					&& !priming.primingTargetOnField(fwd.primingTarget(), true));
			primeItem.addActionListener(ae -> priming.showPrimingPaymentDialog(fwd, idx));
			menu.add(primeItem);
		}

		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	private void showP2BackupContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();
		CardData card = p2BackupCards[idx];
		if (card != null) {
			autoAbilityTriggers.addAbilityMenuItems(menu, card, p2BackupFrozen[idx], p2BackupStates[idx], 0,
					() -> { p2BackupStates[idx] = CardState.DULL; animateDullP2Backup(idx, true); }, false);
		}
		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	private void showP2MonsterContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();
		autoAbilityTriggers.addAbilityMenuItems(menu, p2MonsterCards.get(idx), p2MonsterFrozen.get(idx),
				p2MonsterStates.get(idx), p2MonsterPlayedOnTurn.get(idx),
				() -> { p2MonsterStates.set(idx, CardState.DULL); refreshP2MonsterSlot(idx); }, false);
		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	private void showP2ForwardContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();
		CardData fwd         = p2ForwardCards.get(idx);
		CardData effectiveFwd = p2ForwardPrimedTop.get(idx) != null ? p2ForwardPrimedTop.get(idx) : fwd;
		autoAbilityTriggers.addAbilityMenuItems(menu, effectiveFwd, p2ForwardFrozen.get(idx),
				p2ForwardStates.get(idx), p2ForwardPlayedOnTurn.get(idx),
				() -> {
					CardState p2AACostBefore = p2ForwardStates.get(idx);
					p2ForwardStates.set(idx, CardState.DULL);
					refreshP2ForwardSlot(idx);
					if (p2AACostBefore == CardState.ACTIVE)
						autoAbilityTriggers.triggerAutoAbilitiesForBecomesDull(p2ForwardCards.get(idx), false);
				}, false);

		if (fwd.hasPriming() && p2ForwardPrimedTop.get(idx) == null) {
			JMenuItem primeItem = new JMenuItem("Prime (" + fwd.primingTarget() + ")");
			primeItem.setEnabled(!priming.primingTargetOnField(fwd.primingTarget(), false));
			primeItem.addActionListener(ae -> priming.applyP2PrimedCard(fwd, idx));
			menu.add(primeItem);
		}

		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	// -------------------------------------------------------------------------
	// Deck shuffling, zone panels and board rendering
	// -------------------------------------------------------------------------

	/**
	 * Collapses duplicate copies of the same printing down to one representative, keeping the
	 * order of first appearance.  Printings are identified by image URL — one image per card
	 * serial — so genuinely different versions of a card name are all preserved.
	 */
	static List<CardData> distinctVersions(List<CardData> cards) {
		LinkedHashMap<String, CardData> byPrinting = new LinkedHashMap<>();
		for (CardData c : cards) byPrinting.putIfAbsent(c.imageUrl(), c);
		return new ArrayList<>(byPrinting.values());
	}

	/** Shuffles P1's main deck in-place and refreshes the deck label. */
	void shuffleP1MainDeck() {
		List<CardData> list = new ArrayList<>(gameState.getP1MainDeck());
		Collections.shuffle(list);
		gameState.getP1MainDeck().clear();
		gameState.getP1MainDeck().addAll(list);
		refreshP1DeckLabel();
	}


	private JPanel buildBackupZonePanel(JLabel[] labelStorage) {
		JPanel slotsPanel = new JPanel(new GridLayout(1, 5, 2, 0));
		slotsPanel.setOpaque(false);
		for (int i = 0; i < 5; i++) {
			JLabel slot = new JLabel();
			slot.setFont(FontLoader.loadPixelFont(11));
			slot.setBorder(BorderFactory.createEmptyBorder());
			slot.setOpaque(false);
			slot.setPreferredSize(new Dimension(CARD_H, CARD_H));
			slot.setMinimumSize(new Dimension(CARD_H, CARD_H));
			if (labelStorage != null) labelStorage[i] = slot;
			slotsPanel.add(slot);
		}
		return slotsPanel;
	}

	private JPanel buildDamageZonePanel(String playerLabel) {
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
						g2.setFont(FontLoader.loadPixelFont(14));
						g2.setColor(Color.WHITE);
						FontMetrics fm = g2.getFontMetrics();
						int tx = (getWidth() - fm.stringWidth(letter)) / 2;
						int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
						g2.drawString(letter, tx, ty);
						if (getClientProperty("isExBurst") == Boolean.TRUE) {
							g2.setFont(FontLoader.loadPixelFont(9));
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
				// Per-slot listener: hovering a filled slot previews that damage card in the side
				// panel.  It also has to repeat the stack-level context menu, because a child with
				// its own MouseListener becomes the event target and the parent no longer sees it.
				final int slotIdx = i;
				slot.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) {
						previewDamageZoneCard(true, slotIdx);
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
					@Override public void mousePressed(MouseEvent e) {
						showP1DamageZoneContextMenu(slotsPanel, slot, e);
					}
				});
				slotsPanel.add(slot);
				p1DamageSlots[i] = slot;
			}

			// Covers the gaps between slots, which are not part of any slot's bounds.
			slotsPanel.addMouseListener(new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					showP1DamageZoneContextMenu(slotsPanel, slotsPanel, e);
				}
			});

			p1DamageSlotPanel = slotsPanel;
			p1ShieldIcon = new ShieldIcon();

		} else {
			// P2: mirrored damage slots — card on right, letter centered, EX in upper-left
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
						g2.setFont(FontLoader.loadPixelFont(14));
						g2.setColor(Color.WHITE);
						FontMetrics fm = g2.getFontMetrics();
						int tx = (getWidth() - fm.stringWidth(letter)) / 2;
						int ty = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
						g2.drawString(letter, tx, ty);
						if (getClientProperty("isExBurst") == Boolean.TRUE) {
							g2.setFont(FontLoader.loadPixelFont(9));
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
				final int slotIdx = i;
				slot.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) {
						previewDamageZoneCard(false, slotIdx);
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				slotsPanel.add(slot);
				p2DamageSlots[i] = slot;
			}
			p2ShieldIcon = new ShieldIcon();
		}

		ShieldIcon shieldIcon = isP1 ? p1ShieldIcon : p2ShieldIcon;
		JPanel[] damageSlots = isP1 ? p1DamageSlots : p2DamageSlots;
		JLayeredPane layered = new JLayeredPane() {
			@Override public void doLayout() {
				int w = getWidth(), h = getHeight();
				slotsPanel.setBounds(0, 0, w, h);
				slotsPanel.doLayout();   // ensure slot Y/H are current before reading them
				int dmg = isP1 ? gameState.getP1DamageZone().size()
				               : gameState.getP2DamageZone().size();
				if (dmg < 7 && damageSlots[dmg] != null && damageSlots[dmg].getHeight() > 0) {
					JPanel t = damageSlots[dmg];
					shieldIcon.setBounds(0, t.getY(), w, t.getHeight());
				} else {
					shieldIcon.setBounds(0, 0, 0, 0);
				}
			}
		};
		layered.add(slotsPanel, JLayeredPane.DEFAULT_LAYER);
		layered.add(shieldIcon, JLayeredPane.PALETTE_LAYER);

		JPanel panel = new JPanel(new BorderLayout(0, 4));
		panel.setPreferredSize(new Dimension(CARD_W, CARD_H * 2));
		panel.add(layered, BorderLayout.CENTER);
		return panel;
	}

	Image loadCardbackImage() {
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

	LookAtDeckDialogs lookDialogs() {
		if (lookDialogsInstance == null)
			lookDialogsInstance = new LookAtDeckDialogs(frame, gameState,
				new LookAtDeckDialogs.Callbacks(
					this::logEntry,
					this::showZoomAt, this::hideZoom,
					this::refreshP1DeckLabel, this::refreshP2DeckLabel,
					this::refreshP1HandLabel, this::refreshP2HandCountLabel,
					this::refreshP1BreakLabel, this::refreshP2BreakLabel,
					this::loadCardbackImage,
					isP1 -> animateCardDraw(isP1, 1),
					this::animateMillOneCard,
					this::decide));
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
		g.setFont(FontLoader.loadOverlayFont(12));
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

	/**
	 * Applies a field/board color to one player's zones and gradient board. Visual only — the
	 * caller (Preferences) is responsible for persisting the choice. {@code colorName} is a value
	 * from {@link ElementColor#boardColorChoices()} ({@code "Default"} or a title-case element).
	 */
	void applyBoardColor(boolean isP1, String colorName) {
		if (isP1) {
			applyElementColor(colorName, p1ZonesPanel);
			if (p1Board != null) p1Board.setGradientColor(null);
		} else {
			applyElementColor(colorName, p2ZonesPanel);
			if (p2Board != null) p2Board.setGradientColor(null);
		}
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
	void refreshCrystalDisplays() {
		if (p1CrystalDisplay != null) p1CrystalDisplay.setCount(gameState.getP1Crystals());
		if (p2CrystalDisplay != null) p2CrystalDisplay.setCount(gameState.getP2Crystals());
	}

	/**
	 * Prompts P1 to pick one special ability used this turn to replay for Gogo's "Mimic".
	 * Returns the chosen entry, or {@code null} if the player cancels.
	 */
	UsedSpecialAbility chooseMimicSpecialAbility(List<UsedSpecialAbility> options) {
		if (options.isEmpty()) return null;
		if (options.size() == 1) return options.get(0);
		String[] labels = new String[options.size()];
		for (int i = 0; i < options.size(); i++) {
			UsedSpecialAbility u = options.get(i);
			String name = u.ability().abilityName().isEmpty() ? "" : u.ability().abilityName() + " — ";
			labels[i] = u.source().name() + ": " + name + u.ability().effectText();
		}
		String choice = (String) JOptionPane.showInputDialog(frame,
				"Choose a special ability used this turn to Mimic:", "Mimic",
				JOptionPane.PLAIN_MESSAGE, null, labels, labels[0]);
		if (choice == null) return null;
		int idx = java.util.Arrays.asList(labels).indexOf(choice);
		return idx >= 0 ? options.get(idx) : null;
	}

	// -------------------------------------------------------------------------
	// P2 rendering helpers
	// -------------------------------------------------------------------------

	boolean p2HasAvailableBackupSlot() {
            for (CardData p2BackupCard : p2BackupCards) {
                if (p2BackupCard == null) {
                    return true;
                }
            }
		return false;
	}

	void placeP2CardInForwardZone(CardData card) {
		if (fieldEntryBecomesRfg(card, false)) return;
		// A card arriving on the field is a new object: it has taken no damage and dealt none.
		forgetDamageRecordFor(card);
		if (p2ForwardPanel == null) return;
		int idx = p2ForwardLabels.size();

		JLabel lbl = new JLabel("", SwingConstants.CENTER);
		lbl.setPreferredSize(new Dimension(CARD_H, CARD_H));
		lbl.setMinimumSize(new Dimension(CARD_H, CARD_H));
		lbl.setOpaque(false);
		lbl.setFont(FontLoader.loadPixelFont(11));
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
		p2ForwardStates.add(forwardEntersFieldDull(card, false) ? CardState.DULL : CardState.ACTIVE);
		p2ForwardPlayedOnTurn.add(gameState.getTurnNumber());
		if (card.element() != null) p2Turn.elementForwardsEnteredThisTurn.add(card.element().toLowerCase());
		p2ForwardDamage.add(0);
		p2ForwardPowerBoost.add(0);
		p2ForwardPowerReduction.add(0);
		p2ForwardTempTraits.add(EnumSet.noneOf(CardData.Trait.class));
		p2ForwardRemovedTraits.add(EnumSet.noneOf(CardData.Trait.class));
		p2ForwardTempJobs.add(null);
		p2ForwardPrimedTop.add(null);
		p2ForwardFrozen.add(false);
		p2ForwardLabels.add(lbl);

		p2ForwardPanel.add(lbl);
		p2ForwardPanel.revalidate();
		p2ForwardPanel.repaint();

		refreshP2ForwardSlot(idx);
		if (!card.fieldPowerGrants().isEmpty()) refreshFieldGrantDependents(false);
		if (!card.fieldCostReductions().isEmpty() || p1HandHasSelfCostModifiers()) refreshHandCardStates();
		fieldEntryAnimator.fireEntersField(card, false, false);
		syncBzForwardPlayables(false);
		sendToBreakZoneByUniquenessRule(card, false);
		fireOppNoForwardsFieldAbilitiesForCard(card, false);
	}

	void placeP2CardInFirstBackupSlot(CardData card) {
		if (fieldEntryBecomesRfg(card, false)) return;
		// A card arriving on the field is a new object: it has taken no damage and dealt none.
		forgetDamageRecordFor(card);
		for (int i = 0; i < p2BackupCards.length; i++) {
			if (p2BackupCards[i] != null) continue;
			p2BackupUrls[i]   = card.imageUrl();
			p2BackupCards[i]  = card;
			p2BackupStates[i] = CardState.DULL;
			refreshP2BackupSlot(i);
			fieldEntryAnimator.fireEntersField(card, false, false);
			syncBzForwardPlayables(false);
			sendToBreakZoneByUniquenessRule(card, false);
			return;
		}
	}

	void refreshP2BackupSlot(int idx) {
		String url    = p2BackupUrls[idx];
		JLabel slot   = p2BackupLabels[idx];
		CardState state = p2BackupStates[idx];
		if (slot == null) return;
		refreshPlayerDamageShieldIcon(false);
		if (url == null) { slot.setIcon(null); slot.setText(null); slot.setToolTipText(null); return; }
		if (fieldEntryAnimator.holdSlotBlank(slot, p2BackupCards[idx])) return;
		CardData card = p2BackupCards[idx];
		boolean actingForward = isP2BackupTemporarilyForward(idx);
		int fwdPower = actingForward ? p2BackupForwardPower(idx) : 0;
		int damage   = card != null ? p2BackupForwardDamage.getOrDefault(card, 0) : 0;
		Map<String, Integer> countersMap = card != null ? gameState.getCountersMap(card) : Map.of();
		int totalCounters = countersMap.values().stream().mapToInt(c -> c == null ? 0 : c.intValue()).sum();
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.renderBackupCard(
						CardAnimation.toARGB(raw, CARD_W, CARD_H), state, false, false, p2BackupFrozen[idx]);
				if (damage > 0) CardAnimation.renderDamageOverlay(canvas, damage, state);
				if (actingForward && fwdPower > 0)
					CardAnimation.renderPowerOverlayRight(canvas, fwdPower, new Color(80, 220, 80), state);
				if (!countersMap.isEmpty())
					CardAnimation.renderCounterOverlay(canvas, totalCounters, state, AppSettings.getCounterColor());
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null && p2BackupUrls[idx] != null) { slot.setIcon(icon); slot.setText(null); }
					slot.setToolTipText(buildCounterTooltip(countersMap));
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	void refreshP2ForwardSlot(int idx) {
		refreshPlayerDamageShieldIcon(false);
		if (fieldEntryAnimator.holdSlotBlank(p2ForwardLabels.get(idx), p2ForwardCards.get(idx))) return;
		CardData topCard = p2ForwardPrimedTop.get(idx);
		String url      = topCard != null ? topCard.imageUrl() : p2ForwardUrls.get(idx);
		CardState state = p2ForwardStates.get(idx);
		JLabel slot     = p2ForwardLabels.get(idx);
		if (url == null) return;
		int damage    = p2ForwardDamage.get(idx);
		int power     = effectiveP2ForwardPower(idx);
		CardData fwdCard = p2ForwardCards.get(idx);
		Color    glow    = combatGlowFor(effectiveP2Forward(idx), false);
		int basePower = (topCard != null ? topCard : fwdCard).power();
		Map<String, Integer> countersMap = gameState.getCountersMap(fwdCard);
		int totalCounters = countersMap.values().stream().mapToInt(c -> c == null ? 0 : c.intValue()).sum();
		List<CardData.Trait> traitTabs = visibleTraitTabs(false, idx);
		final boolean primed = isPrimedForward(false, idx);
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		final Object renderToken = markSlotRender(slot);
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.renderBackupCard(CardAnimation.toARGB(raw, CARD_W, CARD_H), state, false, false, p2ForwardFrozen.get(idx), glow);
				TraitTab.renderTraitTabs(canvas, state, traitTabs, primed);
				if (damage > 0) {
					CardAnimation.renderDamageOverlay(canvas, damage, state);
				}
				if (power > basePower) {
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(80, 220, 80), state);
				} else if (power < basePower) {
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(230, 200, 60), state);
				}
				if (!countersMap.isEmpty())
					CardAnimation.renderCounterOverlay(canvas, totalCounters, state, AppSettings.getCounterColor());
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				if (slotRenderSuperseded(slot, renderToken)) return;
				try {
					ImageIcon icon = get();
					if (icon != null) { slot.setIcon(icon); slot.setText(null); }
					applyFieldSlotTooltip(slot, state, traitTabs, primed, countersMap);
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	void refreshAllP2ForwardSlots() {
		for (int i = 0; i < p2ForwardLabels.size(); i++) refreshP2ForwardSlot(i);
	}

	// -------------------------------------------------------------------------
	// Computer player (P2 AI) — implemented in ComputerPlayer.java
	// -------------------------------------------------------------------------
	// (inner class removed; see ComputerPlayer.java)

}
