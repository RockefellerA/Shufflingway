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
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.stream.Collectors;

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
import javax.swing.ScrollPaneConstants;
import javax.swing.SwingConstants;
import javax.swing.SwingUtilities;
import javax.swing.SwingWorker;
import javax.swing.Timer;
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
import shufflingway.dialog.LbPaymentDialog;
import shufflingway.dialog.StandardPaymentDialog;
import shufflingway.dialog.WarpPaymentDialog;
import shufflingway.menu.FileMenu;
import shufflingway.menu.HelpMenu;
import shufflingway.menu.MultiplayerMenu;
import shufflingway.net.ActionType;
import shufflingway.net.GameConnection;

public class MainWindow {

	JFrame frame;
	private shufflingway.dialog.CardPickerDialog cardPickerDialog;

	final AutoAbilityTriggers autoAbilityTriggers = new AutoAbilityTriggers(this);

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
	JLabel p1DeckLabel;
	JLabel p2DeckLabel;
	private CrystalDisplay p1CrystalDisplay;
	private CrystalDisplay p2CrystalDisplay;
	private JButton p1LimitLabel;
	private JPanel handPanel;
	JLabel p1BreakLabel;
	JLabel p2BreakLabel;
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
	private Timer fadeTimer;      // drives fade-in / fade-out animation
	CardSlideAnimator cardSlideAnimator;
	private CardBreakAnimator breakAnimator;
	// In-flight discard animations: each pending slide hides one top-of-break-zone card from the
	// break label until its slide lands. The counter is incremented when a discard animation
	// starts and decremented when the corresponding swing Timer fires. refreshP*BreakLabel skips
	// the trailing N cards while N > 0.
	private int p1BreakAnimHide = 0;
	private int p2BreakAnimHide = 0;
	// Horizontal separator where the P1 and P2 fields meet (anchor for centered effect prompts)
	private JSeparator fieldDivider;
	// Opening hand confirmation popup
	private JWindow openingHandPopup;
	// Hand hover popover (deck zone mouseover)
	private JWindow handPopup;
	// Stack overlay (shown while any entry is on the resolution stack)
	private JWindow               summonStackWindow;
	private Timer     stackCountdownTimer;
	private int                   stackWindowGeneration = 0;
	private Timer handPopupHideTimer;
	private boolean handCardMenuOpen = false;

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
	/** Forwards that may not be chosen as a blocker for the remainder of this turn. */
	final Set<Integer> p1ForwardCannotBlock = new HashSet<>();
	final Set<Integer> p2ForwardCannotBlock = new HashSet<>();
	/** Forwards that must be chosen as a blocker this turn if they are eligible. */
	final Set<Integer> p1ForwardMustBlock   = new HashSet<>();
	final Set<Integer> p2ForwardMustBlock   = new HashSet<>();
	/** Forwards that may not attack for the remainder of this turn. */
	final Set<Integer> p1ForwardCannotAttack = new HashSet<>();
	final Set<Integer> p2ForwardCannotAttack = new HashSet<>();
	/** Forwards that must attack this turn if they are eligible. */
	final Set<Integer> p1ForwardMustAttack   = new HashSet<>();
	final Set<Integer> p2ForwardMustAttack   = new HashSet<>();
	/** Forwards restricted from attacking until the end of their owner's turn (survives one end-phase). */
	final Set<Integer> p1ForwardCannotAttackPersistent = new HashSet<>();
	final Set<Integer> p2ForwardCannotAttackPersistent = new HashSet<>();
	/** Forwards restricted from blocking until the end of their owner's turn (survives one end-phase). */
	final Set<Integer> p1ForwardCannotBlockPersistent  = new HashSet<>();
	final Set<Integer> p2ForwardCannotBlockPersistent  = new HashSet<>();
	/** Forwards that cannot be blocked this turn (attacker-side unblockability). */
	final Set<Integer>          p1ForwardCannotBeBlocked       = new HashSet<>();
	final Set<Integer>          p2ForwardCannotBeBlocked       = new HashSet<>();
	/** Forwards that cannot be blocked by Forwards whose cost matches the filter {costVal, 1=isMore/0=isLess}. */
	final Map<Integer, int[]>   p1ForwardCannotBeBlockedByCost = new HashMap<>();
	final Map<Integer, int[]>   p2ForwardCannotBeBlockedByCost = new HashMap<>();
	final boolean[]       p1BackupFrozen       = new boolean[5];
	final boolean[]       p2BackupFrozen       = new boolean[5];
	final List<Boolean>   p1MonsterFrozen      = new ArrayList<>();
	private JPanel p1ForwardPanel;

	/** Turn number on which each backup slot was last filled (0 = empty/unknown). */
	private final int[] p1BackupPlayedOnTurn = new int[5];

	// State for Backups temporarily acting as Forwards (e.g. 17-012R). Keyed by CardData.
	final Map<CardData, Integer> p1BackupTempForwardPower = new HashMap<>();
	private final Map<CardData, Integer> p2BackupTempForwardPower = new HashMap<>();
	final Map<CardData, List<ActionAbility>> p1TempGrantedAbilities = new HashMap<>();
	final Map<CardData, List<ActionAbility>> p2TempGrantedAbilities = new HashMap<>();
	final Map<CardData, Integer> p1BackupForwardBoost     = new HashMap<>();
	final Map<CardData, Integer> p2BackupForwardBoost     = new HashMap<>();
	final Map<CardData, EnumSet<CardData.Trait>> p1BackupTempTraits = new HashMap<>();
	final Map<CardData, EnumSet<CardData.Trait>> p2BackupTempTraits = new HashMap<>();
	final Map<CardData, Integer> p1BackupForwardDamage    = new HashMap<>();
	private final Map<CardData, Integer> p2BackupForwardDamage    = new HashMap<>();
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
	private final List<Integer>   p2MonsterDamage        = new ArrayList<>();
	final Map<CardData, Integer> p2MonsterTempForwardPower = new HashMap<>();
	final Map<CardData, Integer> p2MonsterPowerBoost = new HashMap<>();
	final Map<CardData, EnumSet<CardData.Trait>> p2MonsterTempTraits = new HashMap<>();
	JPanel p2MonsterPanel;

	private int      p2DamageCount = 0;
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
	ComputerPlayer        computerPlayer;

	private final Set<Integer> spentLbIndices   = new HashSet<>();
	final Set<Integer> p2SpentLbIndices = new HashSet<>();
	JButton            p2LimitButton;

	// Damage zone UI
	private JPanel   p1DamageSlotPanel;
	private JPanel[] p1DamageSlots = new JPanel[7];

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
	int                  p1BlockingIdx     = -1;

	// In-place field targeting: while active, the normal field-card click handlers
	// (attack selection, context menus) are suppressed so clicks pick effect targets.
	private boolean fieldTargetingActive = false;

	// Temporary attack triggers registered by action abilities (cleared at end of turn)
	final Map<CardData, List<Consumer<GameContext>>> p1TempAttackTriggers = new LinkedHashMap<>();
	final Map<CardData, List<Consumer<GameContext>>> p2TempAttackTriggers = new LinkedHashMap<>();
	final Map<CardData, List<Consumer<GameContext>>> p1TempBlockTriggers  = new LinkedHashMap<>();
	final Map<CardData, List<Consumer<GameContext>>> p2TempBlockTriggers  = new LinkedHashMap<>();

	// Attack phase sub-step (0=Prep, 1=Declare, 2=Block, 3=Damage; -1=not in attack phase)
	int attackSubStep = -1;

	// Non-modal P2-attack pending state: set while P1 is interactively declaring a blocker
	private CardData pendingP2Attacker        = null;
	private int      pendingP2AttackerIdx     = -1;
	private Runnable pendingP2BlockDone       = null;
	boolean  pendingP2AttackerIsMonster = false;
	boolean  pendingP2AttackerIsBackup  = false;
	int      pendingP2AttackerPower     = 0;
	private int           p1BlockerSelection      = -1;   // index of forward P1 clicked to block with
	private int           p1BlockerMonsterIdx     = -1;   // P1 monster acting as Forward chosen to block
	private int           p1BlockerBackupIdx      = -1;   // P1 backup acting as Forward chosen to block
	private List<Integer> pendingP2PartyIndices   = null; // set while P1 declares blocker vs P2 party
	private int           pendingP2PartyCombined  = 0;

	// Blocking-target tracking: set between "Blocker Declared" and resolveCombat so that
	// "Choose 1 Forward blocking [Name/Job]" effects can identify the blocking forward.
	CardData p1BlockedByAttacker  = null; // P2 attacker that p1BlockingIdx is blocking
	int      p2BlockingIdx        = -1;   // P2 forward blocking a P1 attacker
	CardData p2BlockedByAttacker  = null; // P1 attacker that p2BlockingIdx is blocking

	// Power of the Forward dulled as "Dull N active Forward" ability cost; set during payment.
	int      lastDullForwardCostPower = 0;

	boolean  effectProgress = true;

	// Separate JWindow for combat priority checkpoints (kept apart from summonStackWindow)
	private javax.swing.JWindow       combatPriorityWindow;
	private Timer         combatPriorityTimer;
	private Timer         p2AutoPassTimer;
	/** Non-null while P1 holds priority during P2's main phase; callback advances to the next phase. */
	private Runnable      p1PriorityInP2MainOnDone = null;

	// Damage-shield / damage-modifier state (keyed by CardData identity; cleared at end of turn)
	final Set<CardData>          nextIncomingDmgZeroSet   = new HashSet<>();
	final Map<CardData, Integer> nextIncomingDmgReduceMap      = new HashMap<>();
	final Map<CardData, Integer> nextAbilityDmgReduceMap       = new HashMap<>();
	final Map<CardData, Integer> incomingDmgIncreaseMap   = new HashMap<>();
	int globalForwardIncomingDmgIncrease = 0; // flat increase applied to ALL Forwards' incoming damage this turn
	final Set<CardData>          nullifyAbilityDmgSet     = new HashSet<>();
	final Set<CardData>          nullifyAbilityOnlyDmgSet = new HashSet<>();
	final Set<CardData>          nextOutgoingDmgZeroSet      = new HashSet<>();
	final Map<CardData, Integer> outgoingDmgMultiplierMap    = new IdentityHashMap<>();
	final Map<CardData, Integer> outgoingDmgFlatBoostMap     = new IdentityHashMap<>();
	final Set<CardData>          nextOutgoingDmgDoublerSet   = new HashSet<>();
	final Map<CardData, Integer> perCardIncomingDmgMultiplierMap = new IdentityHashMap<>();
	int p1ForwardIncomingDmgMult = 1; // multiplier applied when any P1 Forward receives damage
	int p2ForwardIncomingDmgMult = 1; // multiplier applied when any P2 Forward receives damage
	int p1AbilityOutgoingDmgMult = 1; // player-wide ability outgoing damage multiplier
	int p2AbilityOutgoingDmgMult = 1;
	final Set<CardData>          perCardNonLethalDmgSet      = new HashSet<>();
	boolean p1ReceivedDamageThisTurn = false;
	boolean p2ReceivedDamageThisTurn = false;
	int     p1CardsCastThisTurn          = 0;
	boolean p1SummonCastThisTurn         = false;
	final Set<String> p1CastJobsThisTurn  = new HashSet<>();
	final Set<String> p1CastNamesThisTurn = new HashSet<>();
	boolean p1TurnOpponentFwdBroken      = false;
	final Set<String> p1BrokenJobsThisTurn      = new HashSet<>();
	final Set<String> p1BrokenElementsThisTurn  = new HashSet<>();
	final Set<String> p1BrokenCategoriesThisTurn = new HashSet<>();
	int     p1CardsDrawnThisTurn         = 0;
	boolean p1DiscardedByEffectThisTurn  = false;
	boolean p1CausedOpponentDiscardThisTurn = false;
	// Power and name of the card most recently discarded as part of resolving an ability.
	int     lastDiscardedForwardPower    = 0;
	String  lastDiscardedCardName        = null;
	// Cost of the Forward most recently removed from the game by a "remove it from the game" effect.
	int     lastRemovedFromGameCardCost  = 0;
	boolean p1FormedPartyThisTurn        = false;
	boolean p1PartyAnyElementThisTurn   = false;
	boolean p2PartyAnyElementThisTurn   = false;
	int     p2CardsCastThisTurn          = 0;
	boolean p2SummonCastThisTurn         = false;
	final Set<String> p2CastJobsThisTurn  = new HashSet<>();
	final Set<String> p2CastNamesThisTurn = new HashSet<>();
	boolean p2TurnOpponentFwdBroken      = false;
	final Set<String> p2BrokenJobsThisTurn      = new HashSet<>();
	final Set<String> p2BrokenElementsThisTurn  = new HashSet<>();
	final Set<String> p2BrokenCategoriesThisTurn = new HashSet<>();
	int     p2CardsDrawnThisTurn         = 0;
	boolean p2DiscardedByEffectThisTurn  = false;
	boolean p2CausedOpponentDiscardThisTurn = false;
	boolean p2FormedPartyThisTurn        = false;
	int     p1ForwardsLeftFieldThisTurn  = 0;
	int     p2ForwardsLeftFieldThisTurn  = 0;
	final Set<String> p1ElementForwardsEnteredThisTurn = new HashSet<>();
	final Set<String> p2ElementForwardsEnteredThisTurn = new HashSet<>();
	final Set<String> p1CardsTookDamageThisTurn = new HashSet<>();
	final Set<String> p2CardsTookDamageThisTurn = new HashSet<>();
	boolean p1ForwardEnteredViaWarpThisTurn = false;
	boolean p2ForwardEnteredViaWarpThisTurn = false;
	boolean p1TurnOpponentCharReturnedToHand = false;
	boolean p2TurnOpponentCharReturnedToHand = false;
	boolean p1NonLethalProtection   = false;
	boolean p2NonLethalProtection   = false;
	boolean p1DmgReductionDisabled  = false;
	boolean p2DmgReductionDisabled  = false;
	int     p1GlobalDmgReduction    = 0;
	int     p2GlobalDmgReduction    = 0;
	/** Max attack declarations P2 may make this turn; {@code Integer.MAX_VALUE} = unlimited. */
	int opponentAttackDeclarationLimit  = Integer.MAX_VALUE;
	/** Number of attack declarations made by P2 in the current turn (cleared at end of each turn). */
	int p2AttackDeclarationsThisTurn    = 0;
	/** Max attack declarations P1 may make this turn; {@code Integer.MAX_VALUE} = unlimited. */
	int p1AttackDeclarationLimit        = Integer.MAX_VALUE;
	/** Number of attack declarations made by P1 in the current turn (cleared at end of each turn). */
	int p1AttackDeclarationsThisTurn    = 0;

	/** End-of-turn effects queued this turn; fired at the beginning of the END phase. */
	final List<Consumer<GameContext>> endOfTurnEffects = new ArrayList<>();

	/** Active "next cast costs N less" modifiers; consumed on first matching cast, or cleared at EOT. */
	final List<CostReductionModifier> activeCostReductions = new ArrayList<>();

	/**
	 * Cards in P1's Break Zone that have been made castable this turn by an effect like
	 * "Choose 1 [Element] Summon in your Break Zone."  Value is the cost reduction in CP
	 * applied to that one cast.  Identity-keyed so duplicate-named copies don't alias.
	 * Cleared at end of turn.
	 */
	final IdentityHashMap<CardData, Integer> bzPlayableP1 = new IdentityHashMap<>();
	/** P2 equivalent of {@link #bzPlayableP1}. */
	final IdentityHashMap<CardData, Integer> bzPlayableP2 = new IdentityHashMap<>();

	/** Effects deferred until the start of P1's next Main Phase 1. */
	final List<Consumer<GameContext>> pendingMainPhase1Effects = new ArrayList<>();

	/** Tracks once-per-turn ability uses this turn; keyed by card instance identity, value is set of effectText strings used. */
	final IdentityHashMap<CardData, Set<String>> usedOncePerTurnAbilities = new IdentityHashMap<>();

	/** Forwards that cannot be selected as targets by the opponent's Summons this turn. */
	final Set<CardData> cannotBeChosenBySummons        = new HashSet<>();
	/** Forwards that cannot be selected as targets by the opponent's abilities this turn. */
	final Set<CardData> cannotBeChosenByAbilities      = new HashSet<>();
	/** Forwards that cannot be selected as targets by either player's Summons this turn. */
	final Set<CardData> cannotBeChosenBySummonsAnyone  = new HashSet<>();
	/** Maps a card to an element: that card cannot be chosen by Summons/abilities of that element this turn. */
	final Map<CardData, String> cannotBeChosenByElement = new HashMap<>();
	/** Maps a card to an element: damage dealt to that card by Summons/abilities of that element becomes 0 this turn. */
	final Map<CardData, String> nullifyElementDamageMap = new HashMap<>();
	/** Maps a card to a permanent element override (Kam'lanaut ability); persists across turns. */
	final Map<CardData, String> elementOverrideMap      = new HashMap<>();
	/** Maps a card to a permanently-granted extra job (e.g. Bartz ability); persists across turns. */
	final Map<CardData, String> permanentExtraJobMap    = new HashMap<>();
	/** Characters that cannot be broken this turn. */
	final Set<CardData> cannotBeBrokenSet         = new HashSet<>();
	/** Characters that cannot be broken this turn by opposing non-damage abilities/summons. */
	final Set<CardData> cannotBeBrokenByNonDmgSet = new HashSet<>();
	/** Forwards that have Breaktouch (battle damage) until end of turn. */
	final Set<CardData> breaktouchBattleSet       = new HashSet<>();
	/** Cards that have escaped from the current Battle via an Escape ability — combat is skipped for their pairing. */
	final Set<CardData> escapedFromBattle         = new HashSet<>();
	/** The Summon card currently resolving (from the stack or as an EX Burst); null otherwise. */
	CardData currentSummonSource    = null;
	/** {@code true} if {@link #currentSummonSource} belongs to P1. */
	private boolean  currentSummonSourceIsP1 = false;
	/** The source card of the action ability currently resolving off the stack (null otherwise). */
	CardData currentAbilitySource       = null;
	/** Set to {@code true} while a Summon effect is resolving so {@link #selectCharacters} applies the correct protection set. */
	boolean currentResolutionIsSummon = false;
	/** Set to {@code true} by {@code returnNamedCardToYourHand} when the Summon itself is being returned to hand. */
	boolean pendingSummonReturnToHand = false;
	/** Stack entries whose effect has been cancelled by Y'shtola or similar; checked and consumed at resolution. */
	final Set<StackEntry> cancelledStackEntries = Collections.newSetFromMap(new IdentityHashMap<>());
	/** True while {@link #resolveTopOfStack} or EX Burst execution is running; suppresses {@link #showStackWindowIfNeeded}. */
	private boolean isResolvingStack = false;
	/** Set to {@code true} before placing a card whose ETF auto-ability should not fire (consumed on first trigger check). */
	boolean suppressAutoAbilityForNextCard = false;

	/**
	 * Forwards currently stolen by P1 from P2, mapped to their restoration condition:
	 * {@code "permanent"}, {@code "endOfTurn"}, or {@code "whileCardOnField:Name"}.
	 */
	private final IdentityHashMap<CardData, String> stolenForwards = new IdentityHashMap<>();
	/** Distinct element types used to pay the most recent card's CP cost; checked by castPaymentMinElements conditions. */
	int lastCastPaymentDistinctElements = 0;
	/** Specific element types used to pay the most recent card's CP cost; checked by castPaymentElement conditions. */
	final Set<String> lastCastPaymentElements = new HashSet<>();
	/** True if the most recently cast card was paid entirely by dulling Backups (no hand discards). */
	boolean lastCastWasPaidByBackupsOnly = false;
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
		frame.setBounds(0, 0, UiScale.scale(1920), UiScale.scale(1080));
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

		int CORNER_BAR_H = UiScale.scale(28);
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
		nextPhaseButton = new JButton("<html><center>NEXT<br>&#9658;</center></html>");
		nextPhaseButton.setFont(FontLoader.loadPixelNESFont(14));
		nextPhaseButton.setEnabled(false);
		nextPhaseButton.setFocusPainted(false);
		nextPhaseButton.addActionListener(e -> onNextPhase());

		// Pulsing glow border — runs continuously, only paints when enabled
		glowTimer = new Timer(40, e -> {
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
			} else if (p1BackupAttackIdx >= 0) {
				int bIdx = p1BackupAttackIdx;
				p1BackupAttackIdx = -1;
				refreshAttackButton();
				executeP1BackupAttack(bIdx);
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
		}

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

	// -------------------------------------------------------------------------
	// Game startup
	// -------------------------------------------------------------------------

	private void startGame(int deckId, int p2DeckId) {
		// --- Tear down any in-progress game before resetting state ---
		// Stop timers first so they cannot fire callbacks after state is cleared.
		stackWindowGeneration++;
		if (stackCountdownTimer  != null) { stackCountdownTimer.stop();    stackCountdownTimer  = null; }
		if (combatPriorityTimer  != null) { combatPriorityTimer.stop();    combatPriorityTimer  = null; }
		if (p2AutoPassTimer      != null) { p2AutoPassTimer.stop();         p2AutoPassTimer      = null; }
		// Dispose any floating windows.
		if (summonStackWindow    != null) { summonStackWindow.dispose();    summonStackWindow    = null; }
		if (combatPriorityWindow != null) { combatPriorityWindow.dispose(); combatPriorityWindow = null; }
		if (openingHandPopup     != null) { openingHandPopup.dispose();     openingHandPopup     = null; }
		// Reset stack-resolution flags so new abilities can reach the stack window.
		isResolvingStack         = false;
		currentResolutionIsSummon = false;
		pendingSummonReturnToHand = false;
		currentSummonSource      = null;
		currentAbilitySource     = null;
		suppressAutoAbilityForNextCard = false;
		// Per-game callback/priority state.
		p1PriorityInP2MainOnDone = null;
		// Per-game / per-turn collections that are not covered by gameState.reset() or clearUIZones().
		cancelledStackEntries.clear();
		usedOncePerTurnAbilities.clear();
		elementOverrideMap.clear();
		permanentExtraJobMap.clear();
		stolenForwards.clear();
		cannotBeChosenBySummons.clear();
		cannotBeChosenByAbilities.clear();
		cannotBeChosenBySummonsAnyone.clear();
		cannotBeChosenByElement.clear();
		p1TempAttackTriggers.clear();
		p2TempAttackTriggers.clear();
		p1TempBlockTriggers.clear();
		p2TempBlockTriggers.clear();
		p1ForwardCannotBlock.clear();
		p2ForwardCannotBlock.clear();
		// Per-turn tracking flags.
		p1ReceivedDamageThisTurn = false;
		p2ReceivedDamageThisTurn = false;
		p1PartyAnyElementThisTurn = false;
		p2PartyAnyElementThisTurn = false;
		lastCardWasCast   = false;
		lastCardWarpedIn  = false;

		gameState.reset();
		endOfTurnEffects.clear();
		pendingMainPhase1Effects.clear();
		activeCostReductions.clear();
		bzPlayableP1.clear();
		bzPlayableP2.clear();
		if (computerPlayer != null) computerPlayer.cancel();
		computerPlayer = new ComputerPlayer(this);
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
			showOpeningHandPopup(newCards, AppSettings.isDebugUnlimitedMulligan());
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

	void refreshP2HandCountLabel() {
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
	 *   <li>MAIN_1  → ATTACK : passes priority to P2 (auto-pass), then enters Attack</li>
	 *   <li>ATTACK  → MAIN_2 : nothing automatic</li>
	 *   <li>MAIN_2  → END    : passes priority to P2 (auto-pass), then runs end-of-turn</li>
	 *   <li>END     → ACTIVE : increment turn, immediately activate cards</li>
	 * </ul>
	 */
	private void onNextPhase() {
		if (gameState.isP1GameOver()) return;
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
				gameState.advancePhase();   // ACTIVE → DRAW
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
                            gameState.advancePhase();   // DRAW → MAIN_1
                            refreshPhaseTracker();
                            logEntry("Main Phase 1");
                            processWarpCounters(true);
                            if (!pendingMainPhase1Effects.isEmpty()) {
                                List<Consumer<GameContext>> pending = new ArrayList<>(pendingMainPhase1Effects);
                                pendingMainPhase1Effects.clear();
                                GameContext ctx = buildGameContext(true);
                                pending.forEach(e -> e.accept(ctx));
                            }
                            fireFieldMainPhase1Abilities(true);
            }

			case MAIN_1 -> {
                            p1AttackSelection.clear();
                            p1MonsterAttackIdx = -1;
                            logEntry("[Priority] P1 passes — P2 may respond.");
                            if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
                            p2AutoPass(() -> {
                                gameState.advancePhase();   // MAIN_1 → ATTACK
                                logEntry("Attack Phase");
                                autoAbilityTriggers.triggerAutoAbilitiesForBeginningOfAttackPhase(true);
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
                            if (combatPriorityTimer  != null) { combatPriorityTimer.stop();    combatPriorityTimer  = null; }
                            if (combatPriorityWindow != null) { combatPriorityWindow.dispose(); combatPriorityWindow = null; }
                            if (p2AutoPassTimer      != null) { p2AutoPassTimer.stop();         p2AutoPassTimer      = null; }

                            if (attackSubStep == 0) {
                                logEntry("[Priority] P1 passes — P2 may respond.");
                                if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
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
                            fireFieldMainPhase2Abilities(true);
			}

			case MAIN_2 -> {
                            logEntry("[Priority] P1 passes — P2 may respond.");
                            if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
                            p2AutoPass(() -> {
                                gameState.advancePhase();   // MAIN_2 → END
                                refreshPhaseTracker();
                                logEntry("End Phase");
                                fireFieldEndOfTurnAbilities(true);
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
                                incomingDmgIncreaseMap.clear();   globalForwardIncomingDmgIncrease = 0;   nullifyAbilityDmgSet.clear();
                                nullifyAbilityOnlyDmgSet.clear(); perCardNonLethalDmgSet.clear();
                                nextOutgoingDmgZeroSet.clear();    outgoingDmgMultiplierMap.clear();
                                nextOutgoingDmgDoublerSet.clear(); outgoingDmgFlatBoostMap.clear();
                                perCardIncomingDmgMultiplierMap.clear();
                                p1ForwardIncomingDmgMult = 1;      p2ForwardIncomingDmgMult = 1;
                                p1AbilityOutgoingDmgMult = 1;      p2AbilityOutgoingDmgMult = 1;
                                cannotBeChosenBySummons.clear();  cannotBeChosenByAbilities.clear();  cannotBeChosenBySummonsAnyone.clear();  cannotBeChosenByElement.clear();  nullifyElementDamageMap.clear();
                                cannotBeBrokenSet.clear();        cannotBeBrokenByNonDmgSet.clear();  breaktouchBattleSet.clear();
                                p1NonLethalProtection = false;    p2NonLethalProtection = false;
                                p1DmgReductionDisabled = false;   p2DmgReductionDisabled = false;
                                p1GlobalDmgReduction  = 0;        p2GlobalDmgReduction  = 0;
                                opponentAttackDeclarationLimit = Integer.MAX_VALUE; p2AttackDeclarationsThisTurn = 0;
                                p1AttackDeclarationLimit = Integer.MAX_VALUE;       p1AttackDeclarationsThisTurn = 0;
                                for (int i = 0; i < p2ForwardCards.size(); i++) refreshP2ForwardSlot(i);
                                showEndPhaseDiscardDialog();
                                onNextPhase();             // END → ACTIVE (auto-advance)
                            });
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
				usedOncePerTurnAbilities.clear();
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
		Arrays.fill(p1BackupPlayedOnTurn, 0);
		Arrays.fill(p1BackupFrozen, false);
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
		p1MonsterPowerBoost.clear();
		p1MonsterTempTraits.clear();
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
			p1LimitLabel.setText("LIMIT");
			p1LimitLabel.setForeground(new Color(80, 65, 20));
		} else {
			p1LimitLabel.setText("LIMIT -" + playable + "-");
			p1LimitLabel.setForeground(Color.BLACK);
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
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));
			wrapper.add(lbl,       BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		JButton closeBtn = new JButton("Close");
		closeBtn.setFont(FontLoader.loadPixelNESFont(11));
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
		String url = !perm.isEmpty()
				? perm.get(perm.size() - 1).imageUrl()
				: zone.get(zone.size() - 1).card.imageUrl();
		label.setUrl(url);
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image img = ImageCache.load(url);
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
	 * Decrements Warp counters on every card in the active player's warp zone at the start
	 * of their Main Phase 1.  Cards whose counter hits 0 are pushed onto the Stack as
	 * auto-abilities and resolved to the field.
	 */
	void processWarpCounters(boolean isP1) {
		List<GameState.WarpEntry> zone = isP1
				? gameState.getP1WarpZone() : gameState.getP2WarpZone();
		if (zone.isEmpty()) return;

		String tag = isP1 ? "Warp: " : "[P2] Warp: ";
		for (GameState.WarpEntry entry : zone) {
			int before = entry.counters;
			int after  = before - 1;
			logEntry(tag + "\"" + entry.card.name() + "\" counter " + before + " → " + after
					+ (after == 0 ? " (resolving!)" : ""));
			autoAbilityTriggers.triggerAutoAbilitiesForWarpCounterRemoved(entry.card, isP1);
		}

		List<CardData> resolved = isP1
				? gameState.tickP1WarpCounters() : gameState.tickP2WarpCounters();
		for (CardData card : resolved) {
			logEntry(tag + "\"" + card.name() + "\" enters play (auto-ability)");
			lastCardWarpedIn = true;
			try {
				if (card.isForward()) {
					if (isP1) {
						placeCardInForwardZone(card);
						p1ForwardEnteredViaWarpThisTurn = true;
					} else {
						placeP2CardInForwardZone(card);
						p2ForwardEnteredViaWarpThisTurn = true;
					}
				} else if (card.isBackup()) {
					if (isP1) {
						if (hasAvailableBackupSlot()) placeCardInFirstBackupSlot(card);
						else {
							addToP1BreakZone(card);
							logEntry("  No backup slot — \"" + card.name() + "\" → Break Zone");
						}
					} else {
						if (hasAvailableP2BackupSlot()) placeP2CardInFirstBackupSlot(card);
						else {
							addToP2BreakZone(card);
							logEntry("  No backup slot — \"" + card.name() + "\" → Break Zone");
						}
					}
				} else if (card.isMonster()) {
					if (isP1) placeCardInMonsterZone(card);
					else      placeP2CardInMonsterZone(card);
				}
			} finally {
				lastCardWarpedIn = false;
			}
		}
		if (!resolved.isEmpty()) {
			if (isP1) refreshP1BreakLabel(); else refreshP2BreakLabel();
		}
		if (isP1) refreshP1WarpZoneUI(); else refreshP2WarpZoneUI();
	}

	private void showRemovedFromPlayDialog(GrayscaleLabel removeLabel, String player) {
		showRemovedFromPlayDialog(removeLabel, player, "P1".equals(player));
	}

	private void showRemovedFromPlayDialog(GrayscaleLabel removeLabel, String player, boolean isP1) {
		List<GameState.WarpEntry> warpZone = isP1
				? gameState.getP1WarpZone() : gameState.getP2WarpZone();
		List<CardData>            permZone = isP1
				? gameState.getP1PermanentRfp() : gameState.getP2PermanentRfp();
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

	private void showBreakZoneDialog() { showBreakZoneDialog(gameState.getP1BreakZone(), "P1 Break Zone", true); }
	private void showP2BreakZoneDialog() { showBreakZoneDialog(gameState.getP2BreakZone(), "P2 Break Zone", false); }

	private void showBreakZoneDialog(List<CardData> zone, String title, boolean isP1) {
		if (zone.isEmpty()) return;

		JDialog dlg = new JDialog(frame, title + " (" + zone.size() + " cards)", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		for (CardData cd : zone) {
			final boolean hasBzAbility = isP1 && cd.actionAbilities().stream()
					.anyMatch(a -> a.breakZoneOnly() != null && autoAbilityTriggers.canActivateBzAbility(a, cd, true));
			final boolean hasBzPlay    = isP1 && bzPlayableP1.containsKey(cd) && (!cd.isSummon() || !summonCastingProhibited());
			final int     bzPlayCost   = hasBzPlay
					? Math.max(0, cd.cost() - bzPlayableP1.get(cd)) : -1;
			final boolean interactive  = hasBzAbility || hasBzPlay;

			JPanel cardWrapper = new JPanel(new BorderLayout(0, 4));
			cardWrapper.setBackground(cardsPanel.getBackground());

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
			if (interactive) lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) {
					if (lbl.getIcon() != null) showZoomAt(cd.imageUrl());
				}
				@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				@Override public void mousePressed(MouseEvent e) {
					if (!isP1 || !SwingUtilities.isLeftMouseButton(e)) return;
					boolean anyBzAbility = cd.actionAbilities().stream()
							.anyMatch(a -> a.breakZoneOnly() != null);
					if (!anyBzAbility && !hasBzPlay) return;
					hideZoom();
					JPopupMenu menu = new JPopupMenu();
					if (hasBzPlay) {
						JMenuItem playItem = new JMenuItem("Play  (" + bzPlayCost + " CP)");
						playItem.addActionListener(ae -> {
							dlg.dispose();
							showBzPlayPaymentDialog(cd, bzPlayCost);
						});
						menu.add(playItem);
					}
					for (ActionAbility ability : cd.actionAbilities()) {
						if (ability.breakZoneOnly() == null) continue;
						boolean abilityEnabled = autoAbilityTriggers.canActivateBzAbility(ability, cd, true);
						JMenuItem item = new JMenuItem(abilityEnabled ? buildAbilityMenuLabelHtml(ability) : buildAbilityMenuLabel(ability));
						item.setEnabled(abilityEnabled);
						item.addActionListener(ae -> autoAbilityTriggers.showBzAbilityPaymentDialog(ability, cd, true));
						menu.add(item);
					}
					if (menu.getComponentCount() > 0) menu.show(lbl, e.getX(), e.getY());
				}
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					if (img == null) return null;
					BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = buf.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,  RenderingHints.VALUE_ANTIALIAS_ON);
					g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
					if (hasBzAbility) CardAnimation.drawGlow(g2, new Color(30, 144, 255), 0, 0, CARD_W, CARD_H);
					if (hasBzPlay) {
						CardAnimation.drawGlow(g2, new Color(0, 220, 80), 0, 0, CARD_W, CARD_H);
						int badgeW = 26, badgeH = 22;
						int bx = 4, by = 4;
						g2.setColor(new Color(0, 0, 0, 200));
						g2.fillRoundRect(bx, by, badgeW, badgeH, 6, 6);
						g2.setColor(new Color(0, 220, 80));
						g2.drawRoundRect(bx, by, badgeW, badgeH, 6, 6);
						g2.setColor(Color.WHITE);
						g2.setFont(FontLoader.loadPixelNESFont(11));
						String costStr = String.valueOf(bzPlayCost);
						FontMetrics fm = g2.getFontMetrics();
						int tx = bx + (badgeW - fm.stringWidth(costStr)) / 2;
						int ty = by + (badgeH - fm.getHeight()) / 2 + fm.getAscent();
						g2.drawString(costStr, tx, ty);
					}
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

	/**
	 * Modal "pick 1 card" dialog used by effects like "Choose 1 [Element] Summon in your
	 * Break Zone."  Shows {@code candidates} as clickable thumbnails; returns the picked card,
	 * or the first candidate if the player closes the dialog without picking.  Always returns
	 * non-null when {@code candidates} is non-empty.
	 */
	CardData chooseSummonFromBzDialog(List<CardData> candidates, String element) {
		if (candidates.isEmpty()) return null;
		if (candidates.size() == 1) return candidates.get(0);

		JDialog dlg = new JDialog(frame, "Choose 1 " + element + " Summon from Break Zone", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		CardData[] picked = { null };
		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setEnabled(false);

		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));
		JLabel[] cardLabels = new JLabel[candidates.size()];

		for (int i = 0; i < candidates.size(); i++) {
			final int idx = i;
			final CardData cd = candidates.get(i);
			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(Color.DARK_GRAY);
			lbl.setBorder(BorderFactory.createLineBorder(Color.LIGHT_GRAY, 1));
			lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
			cardLabels[i] = lbl;

			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mouseEntered(MouseEvent e) { showZoomAt(cd.imageUrl()); }
				@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				@Override public void mousePressed(MouseEvent e) {
					if (!SwingUtilities.isLeftMouseButton(e)) return;
					picked[0] = cd;
					for (int j = 0; j < cardLabels.length; j++) {
						cardLabels[j].setBorder(BorderFactory.createLineBorder(
								j == idx ? new Color(0, 200, 80) : Color.LIGHT_GRAY,
								j == idx ? 3 : 1));
					}
					confirmBtn.setEnabled(true);
				}
			});

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();

			JLabel nameLabel = new JLabel(cd.name(), SwingConstants.CENTER);
			nameLabel.setFont(FontLoader.loadPixelNESFont(9));
			nameLabel.setPreferredSize(new Dimension(CARD_W, 18));

			JPanel wrapper = new JPanel(new BorderLayout(0, 4));
			wrapper.setBackground(cardsPanel.getBackground());
			wrapper.add(lbl,       BorderLayout.CENTER);
			wrapper.add(nameLabel, BorderLayout.SOUTH);
			cardsPanel.add(wrapper);
		}

		JScrollPane scrollPane = new JScrollPane(cardsPanel,
				JScrollPane.VERTICAL_SCROLLBAR_NEVER,
				JScrollPane.HORIZONTAL_SCROLLBAR_AS_NEEDED);
		scrollPane.setPreferredSize(new Dimension(
				Math.min(candidates.size() * (CARD_W + 16) + 16, 900),
				CARD_H + 60));

		confirmBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

		JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		south.add(confirmBtn);
		south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
		dlg.getContentPane().add(south,      BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);

		return picked[0] != null ? picked[0] : candidates.get(0);
	}

	void triggerGameOver(String reason) {
		gameState.setP1GameOver(true);
		logEntry(reason);
		if (nextPhaseButton != null) nextPhaseButton.setEnabled(false);
	}

	void p1TakeDamage() { p1TakeDamage(null); }

	void p1TakeDamage(Runnable onDone) {
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
		autoAbilityTriggers.triggerAutoAbilitiesForDamageZone(true);
		autoAbilityTriggers.triggerAutoAbilitiesForEitherPlayerReceivesDamage();
		autoAbilityTriggers.triggerAutoAbilitiesForYouReceiveDamage(true);
		animateCardToDamage(true, idx);

		int animDelay = CardSlideAnimator.TOTAL_FRAMES * CardSlideAnimator.FRAME_MS;
		Timer revealTimer = new Timer(animDelay, e -> {
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
			if (isEx) autoAbilityTriggers.triggerExBurst(drawn, true);
			if (onDone != null) onDone.run();
		});
		revealTimer.setRepeats(false);
		revealTimer.start();
	}

	void p2TakeDamage() { p2TakeDamage(null); }

	void p2TakeDamage(Runnable onDone) {
		p2ReceivedDamageThisTurn = true;
		CardData drawn = gameState.drawToP2DamageZone();
		p2DamageCount++;
		boolean isEx = drawn != null && drawn.exBurst();
		String cardInfo = drawn != null ? " — " + drawn.name() + (isEx ? " [EX BURST!]" : "") : "";
		logEntry("P2 takes 1 damage (" + p2DamageCount + "/7)" + cardInfo);
		autoAbilityTriggers.triggerAutoAbilitiesForDamageZone(false);
		autoAbilityTriggers.triggerAutoAbilitiesForEitherPlayerReceivesDamage();
		autoAbilityTriggers.triggerAutoAbilitiesForYouReceiveDamage(false);

		int slotIdx = p2DamageCount - 1;
		if (drawn != null) animateCardToDamage(false, slotIdx);

		refreshP2DeckLabel();

		int animDelay = CardSlideAnimator.TOTAL_FRAMES * CardSlideAnimator.FRAME_MS;
		Timer revealTimer = new Timer(animDelay, e -> {
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
			if (isEx && drawn != null) autoAbilityTriggers.triggerExBurst(drawn, false);
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

	void breakP1Forward(int idx) {
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
								&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK && attackSubStep != 0) {
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
		p2TurnOpponentFwdBroken = true;
		for (String j : card.jobs()) p1BrokenJobsThisTurn.add(j.toLowerCase());
		if (card.element() != null && !card.element().isBlank()) p1BrokenElementsThisTurn.add(card.element().toLowerCase());
		if (card.category1() != null && !card.category1().isBlank()) p1BrokenCategoriesThisTurn.add(card.category1().toLowerCase());
		if (card.category2() != null && !card.category2().isBlank()) p1BrokenCategoriesThisTurn.add(card.category2().toLowerCase());
		if (gameState.getCurrentPlayer() == GameState.Player.P1) p1ForwardsLeftFieldThisTurn++;
		else p2ForwardsLeftFieldThisTurn++;
		// If the broken card was itself stolen from P2, drop its tracking entry
		stolenForwards.remove(card);
		// Restore any forwards that were conditioned on this card remaining on the field
		checkAndRestoreStolenOnLeave(card.name());

		refreshP1BreakLabel();
		if (topCard != null) refreshP1WarpZoneUI();
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, true);
		autoAbilityTriggers.triggerAutoAbilitiesForBreakZone(card, true, partySnapshot);
	}

	/** Removes P2's forward at {@code idx} from the field and sends it to P2's Break Zone. */
	void breakP2Forward(int idx) {
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
		p1TurnOpponentFwdBroken = true;
		for (String j : card.jobs()) p2BrokenJobsThisTurn.add(j.toLowerCase());
		if (card.element() != null && !card.element().isBlank()) p2BrokenElementsThisTurn.add(card.element().toLowerCase());
		if (card.category1() != null && !card.category1().isBlank()) p2BrokenCategoriesThisTurn.add(card.category1().toLowerCase());
		if (card.category2() != null && !card.category2().isBlank()) p2BrokenCategoriesThisTurn.add(card.category2().toLowerCase());
		if (gameState.getCurrentPlayer() == GameState.Player.P1) p1ForwardsLeftFieldThisTurn++;
		else p2ForwardsLeftFieldThisTurn++;
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
			lbl.setFont(FontLoader.loadPixelNESFont(11));
			lbl.setBorder(BorderFactory.createEmptyBorder());
			lbl.addMouseListener(new MouseAdapter() {
				@Override public void mousePressed(MouseEvent e) {
					if (lbl.getIcon() == null) return;
					if (SwingUtilities.isLeftMouseButton(e)
							&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK && attackSubStep != 0) {
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
	void stealForwardFromP2ToP1(int p2Idx, String condition, boolean activate) {
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
						&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK && attackSubStep != 0) {
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
		if (!card.fieldCostReductions().isEmpty() || p1HandHasSelfCostModifiers()) refreshHandPopupIfVisible();
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
								&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK && attackSubStep != 0) {
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
		p2ForwardTempTraits.add(EnumSet.noneOf(CardData.Trait.class));
		p2ForwardRemovedTraits.add(EnumSet.noneOf(CardData.Trait.class));
		p2ForwardTempJobs.add(null);
		p2ForwardFrozen.add(false);
		rebuildP2ForwardPanel();
		if (!card.fieldPowerGrants().isEmpty()) refreshFieldGrantDependents(false);
		if (!card.fieldCostReductions().isEmpty() || p1HandHasSelfCostModifiers()) refreshHandPopupIfVisible();

		logEntry(card.name() + " — control returned to P2");
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

	void returnP1ForwardToDeck(int idx, boolean toBottom) {
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
								&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK && attackSubStep != 0) {
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
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, true);
	}

	void returnP2ForwardToDeck(int idx, boolean toBottom) {
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
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, false);
	}

	void returnP1ForwardUnderDeckTop(int idx, int position) {
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
								&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK && attackSubStep != 0) {
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
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, true);
	}

	void returnP2ForwardUnderDeckTop(int idx, int position) {
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
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, false);
	}

	void searchDeckForCard(boolean isP1,
			boolean inclForwards, boolean inclBackups,
			boolean inclMonsters, boolean inclSummons,
			int costVal, String costCmp, String cardNameFilter, String jobFilter,
			String categoryFilter, String elementFilter, String excludeName, String excludeElem,
			String destination, int count, boolean entersDull) {
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
			boolean passesNameJob = (jobFilter == null && cardNameFilter == null)
				|| (jobFilter != null && cardNameFilter != null
					? meetsJobFilterEffective(c, jobFilter) || meetsCardNameFilter(c, cardNameFilter)
					: meetsJobFilterEffective(c, jobFilter) && meetsCardNameFilter(c, cardNameFilter));
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
		List<CardData> chosen = new ArrayList<>();
		for (int i = 0; i < count && !matches.isEmpty(); i++) {
			CardData pick;
			if (!isP1) {
				List<CardData> copy = new ArrayList<>(matches);
				Collections.shuffle(copy);
				pick = copy.get(0);
				logEntry("[AI] chose " + pick.name());
			} else {
				pick = cardPickerDialog.pickFromDeckSearch(matches);
			}
			if (pick == null) break;
			matches.remove(pick);
			if (isP1) gameState.removeFromP1MainDeck(pick);
			else      deck.remove(pick);
			chosen.add(pick);
		}
		shuffleDeck(isP1);
		if (chosen.isEmpty()) {
			logEntry("Search: no card selected");
			return;
		}
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
					if (isP1) { addToP1BreakZone(card); refreshP1BreakLabel(); }
					else      { addToP2BreakZone(card); refreshP2BreakLabel(); }
					logEntry((isP1 ? "" : "[P2] ") + card.name() + " → Break Zone (search)");
				}
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

		CardData[] picked = { matches.get(0) };  // fallback if dialog is dismissed without a click

		final int CARDS_PER_ROW = 10;
		JPanel cardsPanel = new JPanel();
		cardsPanel.setLayout(new BoxLayout(cardsPanel, BoxLayout.Y_AXIS));
		JPanel currentRow = null;
		for (int idx = 0; idx < matches.size(); idx++) {
			CardData candidate = matches.get(idx);
			if (idx % CARDS_PER_ROW == 0) {
				currentRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 12));
				currentRow.setAlignmentX(Component.LEFT_ALIGNMENT);
				cardsPanel.add(currentRow);
			}
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
					picked[0] = candidate;
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
			currentRow.add(wrapper);
		}

		JLabel hint = new JLabel("Click a card to select it", SwingConstants.CENTER);
		hint.setFont(FontLoader.loadPixelNESFont(9));

		// Wrap in a scroll pane sized to show at most 2 rows; scroll vertically when more.
		// Row height = FlowLayout vgap (12) above + card (CARD_H) + BorderLayout vgap (4) + name (18) + vgap below (12)
		int rowHeight = 12 + CARD_H + 4 + 18 + 12;
		int rowsToShow = Math.min(2, (matches.size() + CARDS_PER_ROW - 1) / CARDS_PER_ROW);
		// Row width = left margin (12) + N cards × CARD_W + (N-1) × hgap (12) + right margin (12)
		int colsInWidest = Math.min(matches.size(), CARDS_PER_ROW);
		int rowWidth = 12 + colsInWidest * CARD_W + (colsInWidest - 1) * 12 + 12;

		JScrollPane scroll = new JScrollPane(cardsPanel,
				ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
				ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
		scroll.setBorder(null);
		scroll.getVerticalScrollBar().setUnitIncrement(rowHeight);
		// Reserve scrollbar width when content exceeds the visible rows so cards don't get clipped.
		int scrollbarPad = matches.size() > rowsToShow * CARDS_PER_ROW
				? scroll.getVerticalScrollBar().getPreferredSize().width : 0;
		scroll.setPreferredSize(new Dimension(rowWidth + scrollbarPad, rowsToShow * rowHeight));

		dlg.getContentPane().setLayout(new BorderLayout(0, 6));
		dlg.getContentPane().add(scroll, BorderLayout.CENTER);
		dlg.getContentPane().add(hint, BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);

		return picked[0];
	}

	void searchDeckJobAndTypeDontShareElements(boolean isP1, String jobFilter, String typeName) {
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

	int showCardImageChooser(List<CardData> cards, String title, boolean allowCancel) {
		return cardPickerDialog.pickCardImage(cards, title, allowCancel);
	}

	int showCardImageChooser(List<CardData> cards, String title, boolean allowCancel, boolean showCost) {
		return cardPickerDialog.pickCardImage(cards, title, allowCancel, showCost);
	}

	List<Integer> showCardMultiImageChooser(List<CardData> cards, String title, int count,
			boolean eachDifferentType, boolean showCost) {
		return cardPickerDialog.pickMultiCardImage(cards, title, count, eachDifferentType, showCost);
	}

	void returnP1ForwardToHand(int idx) {
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
								&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK && attackSubStep != 0) {
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
		if (gameState.getCurrentPlayer() == GameState.Player.P1) p1ForwardsLeftFieldThisTurn++;
		else p2ForwardsLeftFieldThisTurn++;
		p2TurnOpponentCharReturnedToHand = true;
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, true);
	}

	void returnP2ForwardToHand(int idx) {
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
		if (gameState.getCurrentPlayer() == GameState.Player.P1) p1ForwardsLeftFieldThisTurn++;
		else p2ForwardsLeftFieldThisTurn++;
		p1TurnOpponentCharReturnedToHand = true;
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(card, false);
	}

	void returnP1BackupToHand(int idx) {
		if (idx < 0 || idx >= p1BackupCards.length || p1BackupCards[idx] == null) return;
		CardData c = p1BackupCards[idx];
		gameState.getP1Hand().add(c);
		logEntry(c.name() + " → returned to hand");
		p1BackupTempForwardPower.remove(c); p1BackupForwardBoost.remove(c);
		p1BackupTempTraits.remove(c);       p1BackupForwardDamage.remove(c);
		if (p1BackupAttackIdx == idx) p1BackupAttackIdx = -1;
		p1BackupCards[idx]  = null;
		p1BackupUrls[idx]   = null;
		p1BackupStates[idx] = CardState.ACTIVE;
		p1BackupFrozen[idx] = false;
		if (p1BackupLabels[idx] != null) { p1BackupLabels[idx].setIcon(null); p1BackupLabels[idx].setText(null); }
		refreshP1HandLabel();
		p2TurnOpponentCharReturnedToHand = true;
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, true);
	}

	void returnP2BackupToHand(int idx) {
		if (idx < 0 || idx >= p2BackupCards.length || p2BackupCards[idx] == null) return;
		CardData c = p2BackupCards[idx];
		gameState.getP2Hand().add(c);
		logEntry("[P2] " + c.name() + " → returned to hand");
		p2BackupTempForwardPower.remove(c); p2BackupForwardBoost.remove(c);
		p2BackupTempTraits.remove(c);       p2BackupForwardDamage.remove(c);
		if (p2BackupAttackIdx == idx) p2BackupAttackIdx = -1;
		p2BackupCards[idx]  = null;
		p2BackupUrls[idx]   = null;
		p2BackupStates[idx] = CardState.ACTIVE;
		p2BackupFrozen[idx] = false;
		if (p2BackupLabels[idx] != null) { p2BackupLabels[idx].setIcon(null); p2BackupLabels[idx].setText(null); }
		p1TurnOpponentCharReturnedToHand = true;
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
	}

	void returnP1MonsterToHand(int idx) {
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
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, true);
	}

	void returnP2MonsterToHand(int idx) {
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
		autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
	}

	int effectiveP1ForwardPower(int idx) {
		CardData top  = p1ForwardPrimedTop.get(idx);
		CardData card = p1ForwardCards.get(idx);
		int base = top != null ? top.power() : card.power();
		return base + p1ForwardPowerBoost.get(idx) - p1ForwardPowerReduction.get(idx)
				+ computeConditionalBoostForTarget(card, true);
	}

	int effectiveP2ForwardPower(int idx) {
		CardData card = p2ForwardCards.get(idx);
		return card.power() + p2ForwardPowerBoost.get(idx) - p2ForwardPowerReduction.get(idx)
				+ computeConditionalBoostForTarget(card, false);
	}

	boolean effectiveP1HasTrait(int idx, CardData.Trait trait) {
		if (p1ForwardRemovedTraits.get(idx).contains(trait)) return false;
		CardData card = p1ForwardCards.get(idx);
		return card.hasTrait(trait)
				|| p1ForwardTempTraits.get(idx).contains(trait)
				|| computeConditionalTraitsForTarget(card, true).contains(trait);
	}

	boolean effectiveP2HasTrait(int idx, CardData.Trait trait) {
		if (p2ForwardRemovedTraits.get(idx).contains(trait)) return false;
		CardData card = p2ForwardCards.get(idx);
		return card.hasTrait(trait)
				|| p2ForwardTempTraits.get(idx).contains(trait)
				|| computeConditionalTraitsForTarget(card, false).contains(trait);
	}

	private boolean effectiveHasTrait(boolean isP1, int idx, CardData.Trait trait) {
		return isP1 ? effectiveP1HasTrait(idx, trait) : effectiveP2HasTrait(idx, trait);
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
		int rawDmgToBlocker  = modifyOutgoingCombatDamage(attackerIsP1, attackerIdx, effAttackerPow, blocker);
		int dmgToBlocker     = modifyIncomingDamage(blockerIsP1,  blockerIdx,  rawDmgToBlocker,  false, false);
		int rawDmgToAttacker = modifyOutgoingCombatDamage(blockerIsP1, blockerIdx, effBlockerPow, attacker);
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
	/** True when a P2 monster acting as a Forward may be declared as a blocker. */
	private boolean p2MonsterCanBlockAsForward(int idx) {
		if (idx < 0 || idx >= p2MonsterStates.size()) return false;
		if (p2MonsterStates.get(idx) != CardState.ACTIVE) return false;
		if (Boolean.TRUE.equals(p2MonsterFrozen.get(idx))) return false;
		return isP2MonsterTemporarilyForward(idx);
	}

	/** True when a P2 backup acting as a Forward may be declared as a blocker. */
	private boolean p2BackupCanBlockAsForward(int idx) {
		if (idx < 0 || idx >= p2BackupCards.length || p2BackupCards[idx] == null) return false;
		if (p2BackupStates[idx] != CardState.ACTIVE) return false;
		if (p2BackupFrozen[idx]) return false;
		return isP2BackupTemporarilyForward(idx);
	}

	/** Checks all cost-filter sources (dynamic, intrinsic, conditional ICB) for a P1 Forward attacker. */
	private boolean p1AttackerCostFiltersExclude(int attackerIdx, int blockerCost) {
		int[] dyn = p1ForwardCannotBeBlockedByCost.get(attackerIdx);
		if (dyn != null && blockerCostExcluded(blockerCost, dyn)) return true;
		int[] intr = p1ForwardCards.get(attackerIdx).fieldCannotBeBlockedByCost();
		if (intr != null && blockerCostExcluded(blockerCost, intr)) return true;
		CardData attCard = p1ForwardCards.get(attackerIdx);
		for (CardData src : p1ForwardCards)
			for (IfControlBoost icb : src.ifControlBoosts())
				if (icb.cannotBeBlockedByCost() != null && icb.appliesToCard(attCard)
						&& icbConditionsMet(icb, true)
						&& blockerCostExcluded(blockerCost, icb.cannotBeBlockedByCost()))
					return true;
		for (CardData bkp : p1BackupCards)
			if (bkp != null)
				for (IfControlBoost icb : bkp.ifControlBoosts())
					if (icb.cannotBeBlockedByCost() != null && icb.appliesToCard(attCard)
							&& icbConditionsMet(icb, true)
							&& blockerCostExcluded(blockerCost, icb.cannotBeBlockedByCost()))
						return true;
		for (CardData mon : p1MonsterCards)
			for (IfControlBoost icb : mon.ifControlBoosts())
				if (icb.cannotBeBlockedByCost() != null && icb.appliesToCard(attCard)
						&& icbConditionsMet(icb, true)
						&& blockerCostExcluded(blockerCost, icb.cannotBeBlockedByCost()))
					return true;
		return false;
	}

	private ForwardTarget p2ChooseBlocker(int effectiveAttackerPower, ForwardTarget attacker) {
		// Attacker-side unblockability is only tracked for Forwards.
		// Attacker-side restrictions are only tracked for P1 Forwards.
		int     p1AttackerIdx         = -1;
		boolean p1AttackerHigherPower = false;
		int     p1AttackerPower       = 0;
		if (attacker != null && attacker.isP1() && attacker.zone() == ForwardTarget.CardZone.FORWARD) {
			p1AttackerIdx = attacker.idx();
			if (p1ForwardCannotBeBlocked.contains(p1AttackerIdx)) return null;
			p1AttackerHigherPower = p1ForwardCards.get(p1AttackerIdx).cannotBeBlockedByHigherPower();
			if (p1AttackerHigherPower)
				p1AttackerPower = fieldForwardPower(true, ForwardTarget.CardZone.FORWARD, p1AttackerIdx);
		}

		// Candidate P2 blockers: Forwards plus Monsters/Backups acting as Forwards.
		List<ForwardTarget> cands = new ArrayList<>();
		for (int i = 0; i < p2ForwardStates.size(); i++) {
			if (p2ForwardCannotBlock.contains(i) || p2ForwardCannotBlockPersistent.contains(i)) continue;
			if (p2ForwardStates.get(i) != CardState.ACTIVE) continue;
			if (p1AttackerIdx >= 0 && p1AttackerCostFiltersExclude(p1AttackerIdx, p2ForwardCards.get(i).cost())) continue;
			if (p1AttackerHigherPower && fieldForwardPower(false, ForwardTarget.CardZone.FORWARD, i) > p1AttackerPower) continue;
			cands.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
		}
		for (int i = 0; i < p2MonsterCards.size(); i++) {
			if (!p2MonsterCanBlockAsForward(i)) continue;
			if (p1AttackerIdx >= 0 && p1AttackerCostFiltersExclude(p1AttackerIdx, p2MonsterCards.get(i).cost())) continue;
			if (p1AttackerHigherPower && fieldForwardPower(false, ForwardTarget.CardZone.MONSTER, i) > p1AttackerPower) continue;
			cands.add(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER));
		}
		for (int i = 0; i < p2BackupCards.length; i++) {
			if (!p2BackupCanBlockAsForward(i)) continue;
			if (p1AttackerIdx >= 0 && p1AttackerCostFiltersExclude(p1AttackerIdx, p2BackupCards[i].cost())) continue;
			if (p1AttackerHigherPower && fieldForwardPower(false, ForwardTarget.CardZone.BACKUP, i) > p1AttackerPower) continue;
			cands.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP));
		}

		// Honour must-block Forwards first: pick the weakest that can survive.
		if (!p2ForwardMustBlock.isEmpty()) {
			ForwardTarget best = null; int bestPow = -1;
			for (ForwardTarget t : cands) {
				if (t.zone() != ForwardTarget.CardZone.FORWARD || !p2ForwardMustBlock.contains(t.idx())) continue;
				int p = fieldForwardPower(false, t.zone(), t.idx());
				if (p >= effectiveAttackerPower && (best == null || p < bestPow)) { best = t; bestPow = p; }
			}
			if (best != null) return best;
			// else fall through — constraint lifted if none can survive
		}

		// Otherwise pick the strongest blocker that survives the attack.
		ForwardTarget best = null; int bestPow = -1;
		for (ForwardTarget t : cands) {
			int p = fieldForwardPower(false, t.zone(), t.idx());
			if (p >= effectiveAttackerPower && p > bestPow) { best = t; bestPow = p; }
		}
		return best;
	}

	private static boolean blockerCostExcluded(int blockerCost, int[] costFilter) {
		return costFilter[1] == 1 ? blockerCost >= costFilter[0] : blockerCost <= costFilter[0];
	}

	/** Called after P1 attacks: gives P2 AI a chance to declare a blocker. */
	private void p2OfferBlock(CardData attacker, int attackerIdx) {
		ForwardTarget blk = p2ChooseBlocker(effectiveP1ForwardPower(attackerIdx),
				new ForwardTarget(true, attackerIdx, ForwardTarget.CardZone.FORWARD));
		if (blk != null) {
			CardData blocker = autoAbilityTriggers.fieldCardData(blk);
			logEntry("[P2] " + blocker.name() + " blocks!");
			autoAbilityTriggers.triggerAutoAbilitiesForBlock(blocker, false);
			if (blk.zone() == ForwardTarget.CardZone.FORWARD) { p2BlockingIdx = blk.idx(); p2BlockedByAttacker = attacker; }
			autoAbilityTriggers.triggerAutoAbilitiesForIsBlocked(attacker, true);
			if (blk.zone() == ForwardTarget.CardZone.FORWARD)
				resolveCombat(attacker, true, attackerIdx, blocker, false, blk.idx());
			else
				resolveActingCombat(true, ForwardTarget.CardZone.FORWARD, attackerIdx, false, blk.zone(), blk.idx());
			p2BlockingIdx       = -1;
			p2BlockedByAttacker = null;
		} else {
			p2TakeDamage();
			autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, true);
		}
	}

	/**
	 * Called when P2 attacks: sets up interactive block declaration so P1 can click
	 * a forward on the field (or click "Take Damage") instead of using a modal dialog.
	 * {@code onDone} is called asynchronously after combat or damage resolves.
	 */
	void initP1BlockDeclaration(CardData attacker, int attackerIdx, Runnable onDone) {
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
				autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, false);
				onDone.run();
			});
			return;
		}

		int displayPow = (pendingP2AttackerIsMonster || pendingP2AttackerIsBackup)
				? pendingP2AttackerPower : effectiveP2ForwardPower(attackerIdx);
		logEntry("[P2] " + attacker.name() + " (" + displayPow + ") attacks!"
				+ " Select a blocker or click 'Take Damage'.");

		// Store pending state so the attack button and forward clicks know what to do
		pendingP2Attacker    = attacker;
		pendingP2AttackerIdx = attackerIdx;
		pendingP2BlockDone   = onDone;
		p1BlockerSelection   = -1;
		p1BlockerMonsterIdx  = -1;
		p1BlockerBackupIdx   = -1;

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

	void initP1BlockDeclarationVsParty(List<Integer> attackerIndices, int combinedPower, Runnable onDone) {
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
				autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(p2ForwardCards.get(idx), false);
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
		p1BlockerMonsterIdx    = -1;
		p1BlockerBackupIdx     = -1;

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
		JPanel cardsPanel = new JPanel(new GridLayout(0, 4, 8, 8));
		cardsPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

		Runnable refreshLabels = () -> {
			for (int i = 0; i < cardLabels.size(); i++) {
				JLabel lbl = cardLabels.get(i);
				CardData lcd = lbDeck.get(i);
				boolean spent       = spentLbIndices.contains(i);
				boolean casting     = (castingIdx[0] == i);
				boolean payment     = paymentSet.contains(i);
				boolean inPaymentMode = castingIdx[0] >= 0;
				boolean nameBlocked = !inPaymentMode && !spent
						&& (   !castRestrictionMet(lcd)
							|| (   (lcd.isForward() || lcd.isBackup() || lcd.isMonster())
								&& ((!lcd.multicard() && hasCharacterNameOnField(lcd.name()) && !isMultiNameExceptionActive(lcd.name()))
									|| isLightDarkConflict(lcd))));

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
				Set<Integer> pendingPayment = new HashSet<>(paymentSet);
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
							&& (   !castRestrictionMet(cd)
								|| (   (cd.isForward() || cd.isBackup() || cd.isMonster())
									&& ((!cd.multicard() && hasCharacterNameOnField(cd.name()) && !isMultiNameExceptionActive(cd.name()))
										|| isLightDarkConflict(cd))));
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

			// Load full image, greyed if spent; overlay effective cost if reduced/increased
			final boolean spent = spentLbIndices.contains(i);
			final int lbEffectiveCost = effectiveCastCost(cd);
			final int lbCostDelta     = cd.cost() - lbEffectiveCost;
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(cd.imageUrl());
					if (img == null) return null;
					BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = buf.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
					if (!spent && lbCostDelta != 0) {
						g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
						String text = String.valueOf(lbEffectiveCost);
						g2.setFont(FontLoader.loadPixelNESFont(15));
						FontMetrics fm = g2.getFontMetrics();
						int x = 8, y = fm.getAscent() + 7;
						g2.setColor(Color.BLACK);
						g2.drawString(text, x + 1, y + 1);
						g2.drawString(text, x + 2, y + 1);
						g2.drawString(text, x + 1, y + 2);
						g2.drawString(text, x + 2, y + 2);
						g2.setColor(lbCostDelta > 0 ? new Color(0x44EE44) : new Color(0xFF8844));
						g2.drawString(text, x, y);
					}
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
		dlg.getContentPane().add(cardsPanel, BorderLayout.CENTER);
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
				playerBreakFromHand(true,di);
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
	void showForcedDiscardDialog(int count, boolean forcedByOpponent) {
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
				CardData d = playerBreakFromHand(true,di);
				if (d != null) logEntry("Discards " + d.name() + (forcedByOpponent ? " (forced by opponent)" : ""));
			}
			if (!toDiscard.isEmpty()) p1DiscardedByEffectThisTurn = true;
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

		JDialog dlg = new JDialog(frame, "Discard 1 " + cardType, true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		boolean[] result = {false};
		int[] selectedIdx = {-1};

		JLabel statusLabel = new JLabel("Select 1 " + cardType + " to discard.", SwingConstants.CENTER);
		statusLabel.setFont(FontLoader.loadPixelNESFont(10));

		List<JLabel> cardLabels = new ArrayList<>();
		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		JButton confirmBtn = new JButton("Discard");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setEnabled(false);

		Runnable refresh = () -> {
			confirmBtn.setEnabled(selectedIdx[0] >= 0);
			for (int j = 0; j < cardLabels.size(); j++) {
				boolean sel = eligible.get(j).equals(selectedIdx[0]);
				cardLabels.get(j).setBorder(BorderFactory.createLineBorder(
						sel ? Color.RED : Color.LIGHT_GRAY, sel ? 3 : 1));
			}
		};

		for (int j = 0; j < eligible.size(); j++) {
			final int handIdx = eligible.get(j);
			CardData cd = hand.get(handIdx);

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
					selectedIdx[0] = selectedIdx[0] == handIdx ? -1 : handIdx;
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
			int idx = selectedIdx[0];
			if (idx >= 0) {
				CardData d = playerBreakFromHand(true,idx);
				if (d != null) {
					logEntry("Discards " + d.name());
					p1DiscardedByEffectThisTurn = true;
					lastDiscardedCardName = d.name();
					if (d.isForward()) lastDiscardedForwardPower = d.power();
				}
				refreshP1HandLabel();
				refreshP1BreakLabel();
				result[0] = true;
			}
		});

		JScrollPane scroll = new JScrollPane(cardsPanel);
		scroll.setPreferredSize(new Dimension(Math.min(eligible.size() * (CARD_W + 16) + 20, 600), CARD_H + 60));

		JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 4));
		btnPanel.add(confirmBtn);

		JPanel main = new JPanel(new BorderLayout(0, 6));
		main.setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));
		main.add(statusLabel, BorderLayout.NORTH);
		main.add(scroll,      BorderLayout.CENTER);
		main.add(btnPanel,    BorderLayout.SOUTH);

		dlg.setContentPane(main);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
		return result[0];
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

	void showPlaceToBottomOfDeckDialog(int count) {
		List<CardData> hand = gameState.getP1Hand();
		int mustPlace = Math.min(count, hand.size());
		if (mustPlace == 0) return;

		JDialog dlg = new JDialog(frame, "Place " + mustPlace + " Card(s) at Bottom of Deck", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		Set<Integer> selected = new HashSet<>();

		JLabel statusLabel = new JLabel("Select " + mustPlace + " card(s) to place at the bottom of your deck.", SwingConstants.CENTER);
		statusLabel.setFont(FontLoader.loadPixelNESFont(10));

		List<JLabel> cardLabels = new ArrayList<>();
		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

		JButton confirmBtn = new JButton("Place");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setEnabled(false);

		Runnable refresh = () -> {
			int remaining = mustPlace - selected.size();
			statusLabel.setText(remaining > 0
					? "Select " + remaining + " more card(s)."
					: "Ready — click Place to confirm.");
			confirmBtn.setEnabled(selected.size() == mustPlace);
			for (int i = 0; i < cardLabels.size(); i++) {
				cardLabels.get(i).setBorder(BorderFactory.createLineBorder(
						selected.contains(i) ? Color.BLUE : Color.LIGHT_GRAY,
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
					else if (selected.size() < mustPlace) selected.add(idx);
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
			List<Integer> toPlace = new ArrayList<>(selected);
			toPlace.sort(Collections.reverseOrder());
			for (int pi : toPlace) {
				CardData d = gameState.getP1Hand().remove(pi);
				gameState.getP1MainDeck().addLast(d);
				logEntry("Places " + d.name() + " at bottom of deck");
			}
			refreshP1HandLabel();
			refreshP1DeckLabel();
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
	void showHandRfpSelectionDialog(List<CardData> targetHand, int count, boolean rfpIsP1) {
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
			boolean handIsAttackPrep = handPhase == GameState.GamePhase.ATTACK && attackSubStep == 0;
			boolean handCanPlayAction = (handIsMainPhase || (handIsAttackPrep && card.isSummon())) && gameState.getStack().isEmpty()
					&& (phaseTracker.isMyTurn() || (p1PriorityInP2MainOnDone != null && card.isSummon()));
			boolean handIsCharacter = card.isForward() || card.isBackup() || card.isMonster();
			boolean handNameConflict = handIsCharacter && !card.multicard() && hasCharacterNameOnField(card.name()) && !isMultiNameExceptionActive(card.name());
			boolean handLightDarkConflict = handIsCharacter && isLightDarkConflict(card);
			final boolean canPlay = handCanPlayAction && !handNameConflict && !handLightDarkConflict
					&& canAffordCard(card, idx) && (!card.isBackup() || hasAvailableBackupSlot()) && castRestrictionMet(card)
					&& (!card.isSummon() || !summonCastingProhibited());

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
		handPopupHideTimer = new Timer(120, e -> {
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
		boolean isAttackPrep = phase == GameState.GamePhase.ATTACK && attackSubStep == 0;
		boolean canPlaySpecialAction = (isMainPhase || (isAttackPrep && card.isSummon())) && gameState.getStack().isEmpty()
				&& (phaseTracker.isMyTurn() || (p1PriorityInP2MainOnDone != null && card.isSummon()));
		boolean isCharacter = card.isForward() || card.isBackup() || card.isMonster();
		boolean nameConflict = isCharacter && !card.multicard() && hasCharacterNameOnField(card.name()) && !isMultiNameExceptionActive(card.name());
		boolean lightDarkConflict = isCharacter && isLightDarkConflict(card);
		playItem.setEnabled(canPlaySpecialAction && !nameConflict && !lightDarkConflict && canAffordCard(card, handIdx)
				&& (!card.isBackup() || hasAvailableBackupSlot()) && castRestrictionMet(card)
				&& (!card.isSummon() || !summonCastingProhibited()));
		playItem.addActionListener(ae -> {
			hideZoom();
			if (handPopup != null) { handPopup.dispose(); handPopup = null; }
			showPaymentDialog(card, handIdx);
		});
		menu.add(playItem);

		if (card.hasWarp()) {
			JMenuItem warpItem = new JMenuItem("Play (Warp " + card.warpValue() + ")");
			warpItem.setEnabled(canPlaySpecialAction && canAffordWarpCost(card, handIdx) && castRestrictionMet(card)
					&& (!card.isSummon() || !summonCastingProhibited()));
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
					.collect(Collectors.groupingBy(elem -> elem.isEmpty() ? "generic" : elem, LinkedHashMap::new, Collectors.counting()))
					.entrySet().stream().map(en -> (en.getKey().equals("generic") ? en.getValue() + " CP" : en.getValue() + " " + en.getKey() + " CP")).collect(Collectors.joining(" + "));
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
			boolean abilityEnabled = autoAbilityTriggers.canActivateHandAbility(ability, card, true);
			String abilityHtml = buildAbilityMenuLabelHtml(ability);
			JMenuItem item = new JMenuItem(abilityEnabled
					? "<html>Use: " + abilityHtml.substring("<html>".length())
					: "Use: " + buildAbilityMenuLabel(ability));
			item.setEnabled(abilityEnabled);
			item.addActionListener(ae -> {
				hideZoom();
				if (handPopup != null) { handPopup.dispose(); handPopup = null; }
				autoAbilityTriggers.showActionAbilityPaymentDialog(ability, card, () -> {}, true);
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
	void addToP1BreakZone(CardData card) {
		List<CardData> zone = gameState.getP1BreakZone();
		zone.add(card);
		if (card.isLb()) zone.remove(card);
	}

	/**
	 * Adds {@code card} to P2's Break Zone. LB cards enter then are immediately removed,
	 * so "when put into the Break Zone" triggers fire but the card does not stay there.
	 */
	void addToP2BreakZone(CardData card) {
		List<CardData> zone = gameState.getP2BreakZone();
		zone.add(card);
		if (card.isLb()) zone.remove(card);
	}

	void refreshP1BreakLabel() {
		List<CardData> zone = gameState.getP1BreakZone();
		int topIdx = zone.size() - 1 - p1BreakAnimHide;
		if (topIdx < 0) {
			p1BreakLabel.setIcon(null);
			p1BreakLabel.setFont(FontLoader.loadPixelNESFont(18));
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
					if (icon != null) { p1BreakLabel.setIcon(icon); p1BreakLabel.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	void refreshP2BreakLabel() {
		List<CardData> zone = gameState.getP2BreakZone();
		int topIdx = zone.size() - 1 - p2BreakAnimHide;
		if (topIdx < 0) {
			p2BreakLabel.setIcon(null);
			p2BreakLabel.setFont(FontLoader.loadPixelNESFont(18));
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

	void startBreakAnim(JLabel label) {
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
		int selfRed = 0;
		int selfInc = 0;
		int selfFloor = 0;
		for (SelfCostModifier mod : card.selfCostModifiers()) {
			int units = computeSelfCostUnits(mod, true);
			int delta = mod.amountPerUnit() * units;
			if (mod.isIncrease()) selfInc += delta;
			else {
				selfRed += delta;
				selfFloor = Math.max(selfFloor, mod.minCost());
			}
		}
		int cost = card.cost() + selfInc - selfRed;
		cost = Math.max(selfFloor, cost);
		cost = applyFieldReductions(cost, card, true);
		for (CostReductionModifier m : activeCostReductions) {
			if (m.matches(card)) return m.apply(cost);
		}
		return cost;
	}

	List<CardData> drawP1Cards(int count) {
		List<CardData> drawn = gameState.drawToHand(count);
		p1CardsDrawnThisTurn += drawn.size();
		return drawn;
	}

	List<CardData> drawP2Cards(int count) {
		List<CardData> drawn = gameState.drawP2ToHand(count);
		p2CardsDrawnThisTurn += drawn.size();
		return drawn;
	}

	/** Returns {@code true} if any card in P1's hand has self-cost modifiers that vary with game state. */
	private boolean p1HandHasSelfCostModifiers() {
		for (CardData c : gameState.getP1Hand())
			if (!c.selfCostModifiers().isEmpty()) return true;
		return false;
	}

	private int computeSelfCostUnits(SelfCostModifier mod, boolean isP1) {
		List<CardData>   fwds  = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]       bkps  = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData>   bz    = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
		List<CardData>   dmg   = isP1 ? gameState.getP1DamageZone() : gameState.getP2DamageZone();
		boolean summonCastFlag = isP1 ? p1SummonCastThisTurn : p2SummonCastThisTurn;

		return switch (mod.scalingType()) {
			case IF_CAST_SUMMON_THIS_TURN ->
				summonCastFlag ? 1 : 0;
			case IF_CAST_JOB_OR_NAME_THIS_TURN -> {
				Set<String> castJobs  = isP1 ? p1CastJobsThisTurn  : p2CastJobsThisTurn;
				Set<String> castNames = isP1 ? p1CastNamesThisTurn : p2CastNamesThisTurn;
				yield (castJobs.contains(mod.param1().toLowerCase())
						|| castNames.contains(mod.param2().toLowerCase())) ? 1 : 0;
			}
			case IF_CONTROL_NAME -> {
				String name = mod.param1();
				yield fwds.stream().anyMatch(f -> f.name().equalsIgnoreCase(name))
					|| Arrays.stream(bkps).filter(b -> b != null)
					         .anyMatch(b -> b.name().equalsIgnoreCase(name))
					? 1 : 0;
			}
			case EACH_FORWARD ->
				fwds.size();
			case EACH_BACKUP ->
				(int) Arrays.stream(bkps).filter(b -> b != null).count();
			case EACH_FORWARD_WITH_CATEGORY -> {
				String cat = mod.param1();
				yield (int) fwds.stream()
						.filter(f -> cat.equalsIgnoreCase(f.category1()) || cat.equalsIgnoreCase(f.category2()))
						.count();
			}
			case EACH_FORWARD_WITH_JOB -> {
				String job = mod.param1();
				yield (int) (fwds.stream().filter(f -> f.hasJob(job)).count()
						+ Arrays.stream(bkps).filter(b -> b != null && b.hasJob(job)).count());
			}
			case EACH_FORWARD_WITH_JOB_OR_NAME -> {
				String job  = mod.param1();
				String name = mod.param2();
				yield (int) (fwds.stream().filter(f -> f.hasJob(job) || name.equalsIgnoreCase(f.name())).count()
						+ Arrays.stream(bkps).filter(b -> b != null
								&& (b.hasJob(job) || name.equalsIgnoreCase(b.name()))).count());
			}
			case EACH_DAMAGE_RECEIVED ->
				dmg.size();
			case EACH_NAME_IN_BZ -> {
				String name = mod.param1();
				yield (int) bz.stream()
						.filter(c -> c.name().equalsIgnoreCase(name))
						.count();
			}
			case PER_N_BZ_CARDS -> {
				int n = Integer.parseInt(mod.param1());
				yield bz.size() / n;
			}
			case EACH_OPPONENT_HAND_CARD ->
				(isP1 ? gameState.getP2Hand() : gameState.getP1Hand()).size();
		case IF_RECEIVED_N_DAMAGE_OR_MORE -> {
				int threshold = Integer.parseInt(mod.param1());
				yield dmg.size() >= threshold ? 1 : 0;
			}
		case IF_OPPONENT_FORWARD_BROKEN_THIS_TURN ->
				(isP1 ? p1TurnOpponentFwdBroken : p2TurnOpponentFwdBroken) ? 1 : 0;
		case IF_CONTROL_N_OR_MORE_CATEGORY_TYPE -> {
				int n = Integer.parseInt(mod.param1());
				String[] parts = mod.param2().split("\\|", 2);
				String cat  = parts[0];
				String type = parts.length > 1 ? parts[1] : "Forward";
				long fwdCount = 0, bkpCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.stream()
							.filter(f -> cat.equalsIgnoreCase(f.category1()) || cat.equalsIgnoreCase(f.category2()))
							.count();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps)
							.filter(b -> b != null && (cat.equalsIgnoreCase(b.category1()) || cat.equalsIgnoreCase(b.category2())))
							.count();
				yield (fwdCount + bkpCount) >= n ? 1 : 0;
			}
		case EACH_CATEGORY_TYPE_CONTROLLED -> {
				String cat  = mod.param1();
				String type = mod.param2() == null || mod.param2().isBlank() ? "Forward" : mod.param2();
				long fwdCount = fwds.stream()
						.filter(f -> cat.equalsIgnoreCase(f.category1()) || cat.equalsIgnoreCase(f.category2()))
						.count();
				long bkpCount = 0;
				if ("Character".equalsIgnoreCase(type)) {
					bkpCount = Arrays.stream(bkps)
							.filter(b -> b != null)
							.filter(b -> cat.equalsIgnoreCase(b.category1()) || cat.equalsIgnoreCase(b.category2()))
							.count();
				}
				yield (int) (fwdCount + bkpCount);
			}
		case EACH_NAME_OR_NAME_CONTROLLED -> {
				String name1 = mod.param1();
				String name2 = mod.param2();
				yield (int) fwds.stream()
						.filter(f -> f.name().equalsIgnoreCase(name1) || f.name().equalsIgnoreCase(name2))
						.count();
			}
		case PER_N_FILTERED_BZ_CARDS -> {
				int n = Integer.parseInt(mod.param1());
				String spec = mod.param2() == null ? "" : mod.param2();
				String[] parts = spec.split("\\|", 2);
				String elemFilter = parts.length > 0 ? parts[0].trim() : "";
				String typeFilter = parts.length > 1 ? parts[1].trim() : "";
				long filtered = bz.stream().filter(c -> {
					if (!elemFilter.isEmpty() && !elemFilter.equalsIgnoreCase(c.element())) return false;
					if (!typeFilter.isEmpty() && !typeFilter.equalsIgnoreCase(c.type()))   return false;
					return true;
				}).count();
				yield (int) (filtered / n);
			}
		case EACH_CARD_CAST_THIS_TURN ->
				isP1 ? p1CardsCastThisTurn : p2CardsCastThisTurn;
		case IF_OWN_JOB_BROKEN_THIS_TURN ->
				(isP1 ? p1BrokenJobsThisTurn : p2BrokenJobsThisTurn)
						.contains(mod.param1().toLowerCase()) ? 1 : 0;
		case IF_CONTROL_NONE_OF_TYPE -> {
				String type = mod.param1() == null ? "Forward" : mod.param1();
				long fwdCount = 0, bkpCount = 0, monCount = 0;
				List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.size();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps).filter(b -> b != null).count();
				if ("Monster".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					monCount = mons.size();
				yield (fwdCount + bkpCount + monCount) == 0 ? 1 : 0;
			}
		case IF_OPPONENT_DISCARDED_THIS_TURN ->
				(isP1 ? p2DiscardedByEffectThisTurn : p1DiscardedByEffectThisTurn) ? 1 : 0;
		case IF_DRAWN_N_OR_MORE_THIS_TURN -> {
				int n = Integer.parseInt(mod.param1());
				yield (isP1 ? p1CardsDrawnThisTurn : p2CardsDrawnThisTurn) >= n ? 1 : 0;
			}
		case IF_OPPONENT_DISCARDED_BY_ME_THIS_TURN ->
				(isP1 ? p1CausedOpponentDiscardThisTurn : p2CausedOpponentDiscardThisTurn) ? 1 : 0;
		case IF_OWN_FORWARD_FORMED_PARTY_THIS_TURN ->
				(isP1 ? p1FormedPartyThisTurn : p2FormedPartyThisTurn) ? 1 : 0;
		case IF_OPPONENT_HAND_N_OR_LESS -> {
				int n = Integer.parseInt(mod.param1());
				yield (isP1 ? gameState.getP2Hand() : gameState.getP1Hand()).size() <= n ? 1 : 0;
			}
		case EACH_TYPE_WITH_MIN_COST -> {
				int minCostVal = Integer.parseInt(mod.param1());
				String type = mod.param2() == null ? "Character" : mod.param2();
				long fwdCount = 0, bkpCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.stream().filter(f -> f.cost() >= minCostVal).count();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps)
							.filter(b -> b != null && b.cost() >= minCostVal).count();
				yield (int) (fwdCount + bkpCount);
			}
		case IF_OPPONENT_CONTROLS_MORE_TYPE -> {
				String type = mod.param1() == null ? "Forward" : mod.param1();
				List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
				CardData[]     oppBkps = isP1 ? p2BackupCards  : p1BackupCards;
				List<CardData> oppMons = isP1 ? p2MonsterCards  : p1MonsterCards;
				long selfCount = 0, oppCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type)) {
					selfCount += fwds.size();
					oppCount  += oppFwds.size();
				}
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type)) {
					selfCount += Arrays.stream(bkps).filter(b -> b != null).count();
					oppCount  += Arrays.stream(oppBkps).filter(b -> b != null).count();
				}
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> selfMons = isP1 ? p1MonsterCards : p2MonsterCards;
					selfCount += selfMons.size();
					oppCount  += oppMons.size();
				}
				yield oppCount > selfCount ? 1 : 0;
			}
		case EACH_DISTINCT_OPPONENT_TYPE_ELEMENT -> {
				String type = mod.param1() == null ? "Character" : mod.param1();
				List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
				CardData[]     oppBkps = isP1 ? p2BackupCards  : p1BackupCards;
				Set<String> elems = new HashSet<>();
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					for (CardData f : oppFwds)
						for (String e : f.element().split("/")) elems.add(e.trim().toLowerCase());
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					for (CardData b : oppBkps)
						if (b != null) for (String e : b.element().split("/")) elems.add(e.trim().toLowerCase());
				yield elems.size();
			}
		case EACH_CRYSTAL_YOU_HAVE ->
				isP1 ? gameState.getP1Crystals() : gameState.getP2Crystals();
		case IF_CONTROL_CATEGORY_TYPE_NOT_ELEMENT -> {
				String[] catType = mod.param1().split("\\|", 2);
				String cat      = catType[0];
				String type     = catType.length > 1 ? catType[1] : "Forward";
				String excluded = mod.param2();
				Predicate<CardData> matches = c ->
						(cat.equalsIgnoreCase(c.category1()) || cat.equalsIgnoreCase(c.category2()))
						&& !c.containsElement(excluded);
				boolean found = false;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					found = fwds.stream().anyMatch(matches);
				if (!found && ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type)))
					found = Arrays.stream(bkps).filter(b -> b != null).anyMatch(matches);
				yield found ? 1 : 0;
			}
		case EACH_DISTINCT_BACKUP_ELEMENT -> {
				Set<String> distinctElems = new HashSet<>();
				for (CardData b : bkps) {
					if (b != null && b.element() != null && !b.element().contains("/"))
						distinctElems.add(b.element().toLowerCase());
				}
				yield distinctElems.size();
			}
		case EACH_ELEMENT_TYPE_CONTROLLED -> {
				String elem = mod.param1();
				String type = mod.param2() == null ? "Forward" : mod.param2();
				long fwdCount = 0, bkpCount = 0, monCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.stream().filter(f -> elem.equalsIgnoreCase(f.element())).count();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps).filter(b -> b != null && elem.equalsIgnoreCase(b.element())).count();
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
					monCount = mons.stream().filter(mn -> elem.equalsIgnoreCase(mn.element())).count();
				}
				yield (int) (fwdCount + bkpCount + monCount);
			}
		case IF_N_OR_MORE_FORWARDS_LEFT_FIELD_THIS_TURN -> {
				int n = Integer.parseInt(mod.param1());
				yield (isP1 ? p1ForwardsLeftFieldThisTurn : p2ForwardsLeftFieldThisTurn) >= n ? 1 : 0;
			}
		case IF_CONTROL_N_OR_MORE_JOB -> {
				int n    = Integer.parseInt(mod.param1());
				String job = mod.param2();
				long count = fwds.stream().filter(f -> f.hasJob(job)).count()
						+ Arrays.stream(bkps).filter(b -> b != null && b.hasJob(job)).count();
				yield count >= n ? 1 : 0;
			}
		case IF_ELEMENT_FORWARD_ENTERED_FIELD_THIS_TURN -> {
				String elem = mod.param1();
				yield (isP1 ? p1ElementForwardsEnteredThisTurn : p2ElementForwardsEnteredThisTurn)
						.stream().anyMatch(e -> elem.equalsIgnoreCase(e)) ? 1 : 0;
			}
		case IF_OPPONENT_CONTROLS_N_OR_MORE_TYPE -> {
				int n = Integer.parseInt(mod.param1());
				String type = mod.param2() == null ? "Forward" : mod.param2();
				List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
				CardData[]     oppBkps = isP1 ? p2BackupCards  : p1BackupCards;
				List<CardData> oppMons = isP1 ? p2MonsterCards : p1MonsterCards;
				long count = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					count += oppFwds.size();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					count += Arrays.stream(oppBkps).filter(b -> b != null).count();
				if ("Monster".equalsIgnoreCase(type))
					count += oppMons.size();
				yield count >= n ? 1 : 0;
			}
		case IF_FORWARD_ENTERED_VIA_WARP_THIS_TURN ->
				(isP1 ? p1ForwardEnteredViaWarpThisTurn : p2ForwardEnteredViaWarpThisTurn) ? 1 : 0;
		case IF_N_OR_MORE_JOB_IN_BZ -> {
				int n    = Integer.parseInt(mod.param1());
				String job = mod.param2();
				long count = bz.stream().filter(c -> c.hasJob(job)).count();
				yield count >= n ? 1 : 0;
			}
		case IF_RECEIVED_EXACTLY_N_DAMAGE -> {
				int n = Integer.parseInt(mod.param1());
				yield dmg.size() == n ? 1 : 0;
			}
		case HIGHEST_COST_ELEMENT_FORWARD -> {
				String elem = mod.param1();
				yield fwds.stream()
						.filter(f -> elem.equalsIgnoreCase(f.element()))
						.mapToInt(CardData::cost)
						.max()
						.orElse(0);
			}
		case IF_CONTROL_N_OR_MORE_TYPE -> {
				int n = Integer.parseInt(mod.param1());
				String type = mod.param2() == null ? "Forward" : mod.param2();
				long fwdCount = 0, bkpCount = 0, monCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.size();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps).filter(b -> b != null).count();
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
					monCount = mons.size();
				}
				yield (fwdCount + bkpCount + monCount) >= n ? 1 : 0;
			}
		case IF_CONTROL_N_OR_MORE_ELEMENT_TYPE -> {
				int n = Integer.parseInt(mod.param1());
				String[] parts = mod.param2().split("\\|", 2);
				String elem = parts[0];
				String type = parts.length > 1 ? parts[1] : "Forward";
				long fwdCount = 0, bkpCount = 0, monCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.stream().filter(f -> elem.equalsIgnoreCase(f.element())).count();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps)
							.filter(b -> b != null && elem.equalsIgnoreCase(b.element())).count();
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
					monCount = mons.stream().filter(mn -> elem.equalsIgnoreCase(mn.element())).count();
				}
				yield (fwdCount + bkpCount + monCount) >= n ? 1 : 0;
			}
		case IF_BOTH_NAMES_IN_BZ -> {
				String name1 = mod.param1();
				String name2 = mod.param2();
				boolean has1 = bz.stream().anyMatch(c -> c.name().equalsIgnoreCase(name1));
				boolean has2 = bz.stream().anyMatch(c -> c.name().equalsIgnoreCase(name2));
				yield (has1 && has2) ? 1 : 0;
			}
		case PER_N_ELEMENT_TYPE_CONTROLLED -> {
				int n = Integer.parseInt(mod.param1());
				String[] parts = mod.param2().split("\\|", 2);
				String elem = parts[0];
				String type = parts.length > 1 ? parts[1] : "Forward";
				long fwdCount = 0, bkpCount = 0, monCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					fwdCount = fwds.stream().filter(f -> elem.equalsIgnoreCase(f.element())).count();
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
					bkpCount = Arrays.stream(bkps)
							.filter(b -> b != null && elem.equalsIgnoreCase(b.element())).count();
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
					monCount = mons.stream().filter(mn -> elem.equalsIgnoreCase(mn.element())).count();
				}
				yield (int) ((fwdCount + bkpCount + monCount) / n);
			}
		case IF_IS_YOUR_TURN ->
				(isP1 == (gameState.getCurrentPlayer() == GameState.Player.P1)) ? 1 : 0;
		case IF_CONTROL_JOB_OR_NAME -> {
				String job  = mod.param1();
				String name = mod.param2();
				boolean found = fwds.stream().anyMatch(f -> f.hasJob(job) || name.equalsIgnoreCase(f.name()))
						|| Arrays.stream(bkps).filter(b -> b != null)
								.anyMatch(b -> b.hasJob(job) || name.equalsIgnoreCase(b.name()));
				yield found ? 1 : 0;
			}
		case EACH_MONSTER -> {
				List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
				yield mons.size();
			}
		case IF_OPPONENT_CHARACTER_RETURNED_TO_HAND_THIS_TURN ->
				(isP1 ? p1TurnOpponentCharReturnedToHand : p2TurnOpponentCharReturnedToHand) ? 1 : 0;
		case IF_CONTROL_N_OR_MORE_JOB_OR_NAME -> {
				int n = Integer.parseInt(mod.param1());
				String[] parts = mod.param2().split("\\|", 2);
				String job  = parts[0];
				String name = parts.length > 1 ? parts[1] : "";
				long count = fwds.stream()
								.filter(f -> f.hasJob(job) || name.equalsIgnoreCase(f.name())).count()
						+ Arrays.stream(bkps).filter(b -> b != null
								&& (b.hasJob(job) || name.equalsIgnoreCase(b.name()))).count();
				yield count >= n ? 1 : 0;
			}
		case EACH_CARD_DRAWN_THIS_TURN ->
				isP1 ? p1CardsDrawnThisTurn : p2CardsDrawnThisTurn;
		case IF_N_OR_MORE_CATEGORY_IN_BZ_AND_RFP -> {
				int n    = Integer.parseInt(mod.param1());
				String cat = mod.param2();
				Predicate<CardData> hasCat = c ->
						cat.equalsIgnoreCase(c.category1()) || cat.equalsIgnoreCase(c.category2());
				long bzCount  = bz.stream().filter(hasCat).count();
				List<CardData> rfp = isP1 ? gameState.getP1PermanentRfp() : gameState.getP2PermanentRfp();
				long rfpCount = rfp.stream().filter(hasCat).count();
				yield (bzCount + rfpCount) >= n ? 1 : 0;
			}
		case IF_OWN_ELEMENT_OR_CATEGORY_BROKEN_THIS_TURN -> {
				String elem = mod.param1();
				String cat  = mod.param2();
				Set<String> elems = isP1 ? p1BrokenElementsThisTurn : p2BrokenElementsThisTurn;
				Set<String> cats  = isP1 ? p1BrokenCategoriesThisTurn : p2BrokenCategoriesThisTurn;
				yield (elem != null && elems.contains(elem.toLowerCase()))
					|| (cat  != null && cats.contains(cat.toLowerCase())) ? 1 : 0;
			}
		case IF_OPPONENT_CONTROLS_N_MORE_THAN_ME -> {
				int n = Integer.parseInt(mod.param1());
				String type = mod.param2() == null ? "Forward" : mod.param2();
				List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
				CardData[]     oppBkps = isP1 ? p2BackupCards  : p1BackupCards;
				List<CardData> oppMons = isP1 ? p2MonsterCards : p1MonsterCards;
				long selfCount = 0, oppCount = 0;
				if ("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type)) {
					selfCount += fwds.size();
					oppCount  += oppFwds.size();
				}
				if ("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type)) {
					selfCount += Arrays.stream(bkps).filter(b -> b != null).count();
					oppCount  += Arrays.stream(oppBkps).filter(b -> b != null).count();
				}
				if ("Monster".equalsIgnoreCase(type)) {
					List<CardData> selfMons = isP1 ? p1MonsterCards : p2MonsterCards;
					selfCount += selfMons.size();
					oppCount  += oppMons.size();
				}
				yield (oppCount - selfCount) >= n ? 1 : 0;
			}
		case EACH_JOB_OR_ELEMENT_TYPE_CONTROLLED -> {
				String job = mod.param1();
				String[] parts = mod.param2().split("\\|", 2);
				String elem = parts[0];
				String type = parts.length > 1 ? parts[1] : "Character";
				List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
				Predicate<CardData> fwdPred = f ->
						f.hasJob(job)
						|| (("Forward".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
							&& elem.equalsIgnoreCase(f.element()));
				Predicate<CardData> bkpPred = b ->
						b.hasJob(job)
						|| (("Backup".equalsIgnoreCase(type) || "Character".equalsIgnoreCase(type))
							&& elem.equalsIgnoreCase(b.element()));
				yield (int) (fwds.stream().filter(fwdPred).count()
						+ Arrays.stream(bkps).filter(b -> b != null).filter(bkpPred).count()
						+ mons.stream().filter(mn -> mn.hasJob(job)
								|| ("Monster".equalsIgnoreCase(type) && elem.equalsIgnoreCase(mn.element()))).count());
			}
		};
	}

	private int applyFieldReductions(int cost, CardData card, boolean isP1) {
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
			if (fcr.ownerOnly() && srcIsP1 != isP1) continue;
			if (!fcr.matchesCard(card)) continue;
			int units = fcr.scalingJobFilter() != null
					? countForwardsWithJob(fcr.scalingJobFilter(), isP1) : 1;
			cost = fcr.apply(cost, units);
		}
		return cost;
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
	private boolean summonCastingProhibited() {
		for (CardData c : p1ForwardCards) if (c != null && ActionResolver.hasPlayerCannotCastSummonsFieldAbility(c)) return true;
		for (CardData c : p1BackupCards)  if (c != null && ActionResolver.hasPlayerCannotCastSummonsFieldAbility(c)) return true;
		for (CardData c : p2ForwardCards) if (c != null && ActionResolver.hasPlayerCannotCastSummonsFieldAbility(c)) return true;
		for (CardData c : p2BackupCards)  if (c != null && ActionResolver.hasPlayerCannotCastSummonsFieldAbility(c)) return true;
		return false;
	}

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
		if (cr.requiresAForward()   &&  p1ForwardCards.isEmpty()) return false;

		if (cr.maxOpponentHandSize() >= 0
				&& gameState.getP2Hand().size() > cr.maxOpponentHandSize()) return false;

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

		if (cr.mustControlCondition() != null && !controlConditionMet(cr.mustControlCondition(), true))
			return false;

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
			Map<Integer, String> elementOverrides) {
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
			playerBreakFromHand(true,di);
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
		autoAbilityTriggers.triggerAutoAbilitiesForWarpPlaced(card, true);
		refreshP1HandLabel();
		refreshP1BreakLabel();
		refreshP1WarpZoneUI();
	}

	/**
	 * P2 equivalent of {@link #executeWarpPlay}: pays the Warp alternate cost (dulls P2
	 * backups, breaks P2 hand cards), removes the card from P2's hand, and places it in
	 * P2's Removed-From-Play zone with Warp counters.  Caller is responsible for choosing
	 * which backups/hand cards satisfy the cost.
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
	 * Returns true if a "You can play 2 or more Card Name X" exception is active on P1's field
	 * for the given card name, allowing the name-uniqueness rule to be bypassed.
	 */
	private boolean isMultiNameExceptionActive(String cardName) {
		for (CardData c : p1ForwardCards) if (cardName.equalsIgnoreCase(c.grantsMultiNamePlay())) return true;
		for (CardData c : p1MonsterCards) if (cardName.equalsIgnoreCase(c.grantsMultiNamePlay())) return true;
		for (CardData c : p1BackupCards)  if (c != null && cardName.equalsIgnoreCase(c.grantsMultiNamePlay())) return true;
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

	private boolean hasAvailableBackupSlot() {
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

	/**
	 * Opens a modal payment dialog where the player selects backups to dull (1 CP each)
	 * and/or hand cards to discard (2 CP each) to cover the cost of {@code card}.
	 *
	 * Constraints enforced:
	 *   - Backups may not cause total CP to exceed the cost (no overpay via backups).
	 *   - Discards may overpay by 1 per element (total <= cost + elems.length - 1 after adding).
	 */
	/**
	 * Opens the standard payment dialog for a card being cast from P1's Break Zone (via
	 * "Choose 1 Summon in your Break Zone, you can cast it this turn" effects).  Reuses
	 * {@link StandardPaymentDialog} with {@code handIdx = -1} so no hand card is excluded
	 * from the discard list, and routes the confirm callback to {@link #executePlayFromBzP1}.
	 */
	private void showBzPlayPaymentDialog(CardData card, int reducedCost) {
		new StandardPaymentDialog(frame, card, -1, reducedCost,
				gameState.getP1Hand(), p1BackupCards, p1BackupStates, p1BackupUrls,
				this::showZoomAt, this::hideZoom,
				new ArrayList<>(p1ForwardCards),
				(discards, backups, overrides) -> executePlayFromBzP1(card, discards, backups, overrides),
				isAnyElementCast(card))
			.show();
	}

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
				(int) Arrays.stream(elems)
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
				(int) Arrays.stream(elems)
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
			playerBreakFromHand(true,di);
			if (di < cardHandIdx) cardHandIdx--;
		}
		// Clear all CP generated during payment — includes off-element CP from L/D card discards
		// (e.g. discarding Fire Ifrits to pay for a Light card generates Fire CP that must be cleared)
		Set<String> cpToClear = new java.util.LinkedHashSet<>(Arrays.asList(elems));
		cpToClear.addAll(execCpAccum.keySet());
		for (String e : cpToClear) {
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
		refreshP1HandLabel();
		activeCostReductions.removeIf(m -> m.consumeOnUse() && m.matches(card));
		p1CardsCastThisTurn++;
		for (String j : card.jobs()) p1CastJobsThisTurn.add(j.toLowerCase());
		p1CastNamesThisTurn.add(card.name().toLowerCase());
		if (card.isSummon()) {
			p1SummonCastThisTurn = true;
			refreshHandPopupIfVisible();
		}
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

		refreshP1BreakLabel();
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
		String[] elems = card.elements();
		boolean  isLD  = card.isLightOrDark();
		Map<String, Integer> execCostByElem = new LinkedHashMap<>();
		if (!isLD) for (String e : elems) execCostByElem.put(e, 1);
		Map<String, Integer> execCpAccum = new LinkedHashMap<>();

		List<Integer> sortedBackups = new ArrayList<>(backupDullIndices);
		if (!isLD) sortedBackups.sort(Comparator.comparingInt(s ->
				(int) Arrays.stream(elems)
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
		}
		discardIndices.sort(Collections.reverseOrder());
		for (int di : discardIndices) {
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
		lastCastWasPaidByBackupsOnly = discardIndices.isEmpty() && !backupDullIndices.isEmpty();

		// Remove source from Break Zone (by identity — multiple same-named copies may exist)
		List<CardData> bz = gameState.getP1BreakZone();
		for (int i = 0; i < bz.size(); i++) {
			if (bz.get(i) == card) { bz.remove(i); break; }
		}
		bzPlayableP1.remove(card);
		refreshP1BreakLabel();

		activeCostReductions.removeIf(m -> m.consumeOnUse() && m.matches(card));
		p1CardsCastThisTurn++;
		for (String j : card.jobs()) p1CastJobsThisTurn.add(j.toLowerCase());
		p1CastNamesThisTurn.add(card.name().toLowerCase());
		if (card.isSummon()) {
			p1SummonCastThisTurn = true;
			refreshHandPopupIfVisible();
		}
		logEntry("Played \"" + card.name() + "\" from Break Zone");

		lastCardWasCast = true;
		if (card.isBackup())       placeCardInFirstBackupSlot(card);
		else if (card.isForward()) placeCardInForwardZone(card);
		else if (card.isMonster()) placeCardInMonsterZone(card);
		else if (card.isSummon())  showSummonOnStack(card);
		lastCardWasCast = false;
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
	void executePlayFromBzP2(CardData card, int reducedCost,
			List<Integer> discardIndices, Map<Integer, String> discardElementAssignments,
			List<Integer> dullBackupIndices, Map<Integer, String> backupElementAssignments) {
		String[] elems = card.elements();

		payP2CostViaBackupsAndDiscards(
				dullBackupIndices, backupElementAssignments,
				discardIndices,    discardElementAssignments);

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

		// Remove source from P2 Break Zone by identity (handles duplicate-named copies).
		List<CardData> bz = gameState.getP2BreakZone();
		for (int i = 0; i < bz.size(); i++) {
			if (bz.get(i) == card) { bz.remove(i); break; }
		}
		bzPlayableP2.remove(card);
		refreshP2BreakLabel();

		p2CardsCastThisTurn++;
		for (String j : card.jobs()) p2CastJobsThisTurn.add(j.toLowerCase());
		p2CastNamesThisTurn.add(card.name().toLowerCase());
		if (card.isSummon()) p2SummonCastThisTurn = true;
		logEntry("[P2] Played \"" + card.name() + "\" from Break Zone");

		lastCardWasCast = true;
		if (card.isBackup())       placeP2CardInFirstBackupSlot(card);
		else if (card.isForward()) placeP2CardInForwardZone(card);
		else if (card.isMonster()) placeP2CardInMonsterZone(card);
		else if (card.isSummon())  showSummonOnStack(card);
		lastCardWasCast = false;
	}

	/** True when P2 is the built-in computer player (no active multiplayer connection). */
	boolean isP2Cpu() {
		return multiplayerMenu == null || multiplayerMenu.getActiveConnection() == null;
	}

	/** Pushes a Summon onto the stack and opens the stack overlay. */
	void showSummonOnStack(CardData card) {
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

		if (cancelledStackEntries.remove(entry)) {
			String pfx = entry.isP1() ? "" : "[P2] ";
			logEntry(pfx + "\"" + entry.source().name() + "\" — effect cancelled");
			if (entry.isSummon()) {
				addToP1BreakZone(entry.source());
				logEntry("\"" + entry.source().name() + "\" → Break Zone");
				refreshP1BreakLabel();
			}
			if (!gameState.getStack().isEmpty()) showStackWindow();
			return;
		}

		isResolvingStack = true;
		try {
			GameContext ctx = buildGameContext(entry.isP1());
			if (entry.isSummon()) {
				String effectText = entry.effectText();
				logEntry("[Summon] Resolving \"" + entry.source().name() + "\": " + effectText);
				Consumer<GameContext> effect = ActionResolver.parse(effectText, entry.source());
				if (effect != null) {
					currentResolutionIsSummon   = true;
					currentSummonSource     = entry.source();
					currentSummonSourceIsP1 = entry.isP1();
					pendingSummonReturnToHand   = false;
					try { effect.accept(ctx); } finally {
						currentResolutionIsSummon = false;
						currentSummonSource   = null;
					}
				} else logEntry("[ActionResolver] Summon effect not yet implemented: " + effectText);
				autoAbilityTriggers.triggerAutoAbilitiesForCastSummon(entry.isP1());
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
			} else if (entry.isExBurstEntry()) {
				String exText = entry.effectText();
				logEntry("[EX Burst on Stack] Resolving \"" + entry.source().name() + "\": " + exText);
				Consumer<GameContext> effect = ActionResolver.parse(exText, entry.source());
				if (effect != null) {
					currentAbilitySource = entry.source();
					try { effect.accept(ctx); } finally { currentAbilitySource = null; }
				} else {
					logEntry("[EX Burst on Stack] Effect not yet implemented: " + exText);
				}
				refreshP1HandLabel();
				refreshP1BreakLabel();
			} else if (entry.isAutoAbility()) {
				AutoAbility ab = entry.autoAbility();
				logEntry("[AutoAbility] Resolving \"" + entry.source().name() + "\": " + ab.effectText());
				Consumer<GameContext> effect = ActionResolver.parse(ab.effectText(), entry.source());
				if (effect != null) {
					currentAbilitySource = entry.source();
					try { effect.accept(ctx); } finally { currentAbilitySource = null; }
				} else {
					logEntry("[AutoAbility] Unrecognized effect: " + ab.effectText());
				}
				refreshP1HandLabel();
				refreshP1BreakLabel();
			} else {
				currentAbilitySource = entry.source();
				try {
					ActionResolver.resolve(entry.ability(), entry.source(), gameState, ctx, entry.xValue());
				} finally {
					currentAbilitySource = null;
				}
				refreshP1HandLabel();
				refreshP1BreakLabel();
			}
		} finally {
			isResolvingStack = false;
		}

		if (!gameState.getStack().isEmpty()) showStackWindow();
		else { lastDiscardedForwardPower = 0; lastDiscardedCardName = null; }
	}

	/** Calls {@link #showStackWindow()} only when we are not already inside a stack resolution chain. */
	void showStackWindowIfNeeded() {
		if (!isResolvingStack && !gameState.getStack().isEmpty()) showStackWindow();
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
		Map<String, Integer> lbCpAccum = new LinkedHashMap<>();
		for (int bi : backupDullIndices) {
			p1BackupStates[bi] = CardState.DULL;
			animateDullBackup(bi, true);
			String cpElem = isLD ? p1BackupCards[bi].elements()[0] : contributingElement(p1BackupCards[bi], elems);
			gameState.addP1Cp(cpElem, 1);
			lbCpAccum.merge(cpElem, 1, Integer::sum);
		}
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
		if (p1BackupLabels == null) return;
		for (int i = 0; i < p1BackupLabels.length; i++) {
			if (p1BackupLabels[i] == null || p1BackupLabels[i].getIcon() != null) continue;
			p1BackupUrls[i]          = card.imageUrl();
			p1BackupCards[i]         = card;
			p1BackupStates[i]        = CardState.DULL;
			p1BackupPlayedOnTurn[i]  = gameState.getTurnNumber();
			refreshP1BackupSlot(i);
			autoAbilityTriggers.triggerAutoAbilitiesForEntersField(card, true);
			sendToBreakZoneByUniquenessRule(card, true);
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
					Timer timer = new Timer(16, null);
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


	void animateDullForward(int idx, Runnable onComplete) {
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
					Timer timer = new Timer(16, null);
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

	void animateDullP2Forward(int idx, Runnable onComplete) {
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
					Timer timer = new Timer(16, null);
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

	void animateActivateForward(int idx) {
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
					Timer timer = new Timer(16, null);
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

	void animateActivateMonster(int idx) {
		String url  = p1MonsterUrls.get(idx);
		JLabel slot = p1MonsterLabels.get(idx);
		if (url == null || slot == null) { refreshP1MonsterSlot(idx); return; }

		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null : CardAnimation.toARGB(raw, CARD_W, CARD_H);
			}
			@Override protected void done() {
				try {
					BufferedImage card = get();
					if (card == null) { refreshP1MonsterSlot(idx); return; }

					int   totalFrames = 12;
					int[] frame       = { 0 };
					Timer timer = new Timer(16, null);
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
							refreshP1MonsterSlot(idx);
						}
					});
					timer.start();
				} catch (InterruptedException | ExecutionException ignored) {
					refreshP1MonsterSlot(idx);
				}
			}
		}.execute();
	}

	private void animateDullMonster(int idx) {
		String url  = p1MonsterUrls.get(idx);
		JLabel slot = p1MonsterLabels.get(idx);
		if (url == null || slot == null) { refreshP1MonsterSlot(idx); return; }

		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null : CardAnimation.toARGB(raw, CARD_W, CARD_H);
			}
			@Override protected void done() {
				try {
					BufferedImage card = get();
					if (card == null) { refreshP1MonsterSlot(idx); return; }

					int   totalFrames = 12;
					int[] frame       = { 0 };
					Timer timer = new Timer(16, null);
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
							refreshP1MonsterSlot(idx);
						}
					});
					timer.start();
				} catch (InterruptedException | ExecutionException ignored) {
					refreshP1MonsterSlot(idx);
				}
			}
		}.execute();
	}

	void animateActivateP2Forward(int idx) {
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
					Timer timer = new Timer(16, null);
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
	void refreshP1BackupSlot(int idx) {
		String url  = p1BackupUrls[idx];
		CardState state = p1BackupStates[idx];
		JLabel slot  = p1BackupLabels[idx];
		if (slot == null) return;
		if (url == null) { slot.setIcon(null); slot.setText(null); return; }
		CardData card = p1BackupCards[idx];
		boolean actingForward = isP1BackupTemporarilyForward(idx);
		boolean canAttack = attackSubStep == 1 && isBackupSelectableAsForward(idx);
		boolean canBlock  = isBackupBlockSelectable(idx);
		boolean selected  = p1BackupAttackIdx == idx || p1BlockerBackupIdx == idx;
		int fwdPower = actingForward ? p1BackupForwardPower(idx) : 0;
		int damage   = card != null ? p1BackupForwardDamage.getOrDefault(card, 0) : 0;
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
				return new ImageIcon(canvas);
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
		if (ability.opponentTurnOnly())             { if (!firstRestrict) restrict.append(", "); restrict.append("opp turn");      firstRestrict = false; }
		if (ability.oncePerTurn())                  { if (!firstRestrict) restrict.append(", "); restrict.append("1/turn");        firstRestrict = false; }
		if (ability.mainPhaseOnly())                { if (!firstRestrict) restrict.append(", "); restrict.append("main phase");    firstRestrict = false; }
		if (ability.whilePartyAttacking())          { if (!firstRestrict) restrict.append(", "); restrict.append("while party atk"); firstRestrict = false; }
		else if (ability.whileCardAttacking() != null) { if (!firstRestrict) restrict.append(", "); restrict.append("while ").append(ability.whileCardAttacking()).append(" atk"); firstRestrict = false; }
		if (ability.whileCardBlocking() != null)          { if (!firstRestrict) restrict.append(", "); restrict.append("while ").append(ability.whileCardBlocking()).append(" blk"); firstRestrict = false; }
		if (ability.requiresOppDiscardedThisTurn())       { if (!firstRestrict) restrict.append(", "); restrict.append("opp discarded");    firstRestrict = false; }
		if (ability.requiresCastSummonThisTurn())         { if (!firstRestrict) restrict.append(", "); restrict.append("cast summon");       firstRestrict = false; }
		if (ability.requiresOpponentEmptyHand())          { if (!firstRestrict) restrict.append(", "); restrict.append("opp empty hand");    firstRestrict = false; }
		if (ability.requiresElementForwardEnteredThisTurn() != null) { if (!firstRestrict) restrict.append(", "); restrict.append(ability.requiresElementForwardEnteredThisTurn()).append(" fwd entered"); firstRestrict = false; }
		if (ability.requiresNamedCardTookDamageThisTurn() != null)  { if (!firstRestrict) restrict.append(", "); restrict.append(ability.requiresNamedCardTookDamageThisTurn()).append(" took dmg");  firstRestrict = false; }
		if (ability.requiresSelfReceivedDamageThisTurn())           { if (!firstRestrict) restrict.append(", "); restrict.append("self rcvd dmg"); firstRestrict = false; }
		if (ability.requiresSelfReceivedDamageThisTurn())           { if (!firstRestrict) restrict.append(", "); restrict.append("self rcvd dmg"); firstRestrict = false; }
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

	/**
	 * Returns {@code true} if the player can afford the CP portion of an action
	 * ability's cost (element and generic CP only; Dull/S requirements are checked
	 * separately in the context-menu enable logic).
	 */
	boolean canAffordAbilityCost(ActionAbility ability, boolean isP1) {
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
	 * If {@code card} is currently the primed top of a forward slot, returns the name of
	 * the primer (base) card beneath it; otherwise returns {@code null}.
	 */
	String getPrimerCardName(CardData card, boolean isP1) {
		List<CardData> primedTops = isP1 ? p1ForwardPrimedTop : p2ForwardPrimedTop;
		List<CardData> bases      = isP1 ? p1ForwardCards      : p2ForwardCards;
		for (int i = 0; i < primedTops.size(); i++)
			if (card.equals(primedTops.get(i))) return bases.get(i).name();
		return null;
	}

	// ---- Per-player data selectors used by the ability payment chain -----------

	List<CardData> playerHand(boolean isP1)       { return isP1 ? gameState.getP1Hand()       : gameState.getP2Hand(); }
	CardData[]     playerBackupCards(boolean isP1) { return isP1 ? p1BackupCards               : p2BackupCards; }
	CardState[]    playerBackupStates(boolean isP1){ return isP1 ? p1BackupStates              : p2BackupStates; }
	private boolean[]      playerBackupFrozen(boolean isP1){ return isP1 ? p1BackupFrozen              : p2BackupFrozen; }
	String[]       playerBackupUrls(boolean isP1)  { return isP1 ? p1BackupUrls                : p2BackupUrls; }
	List<CardData> playerForwardCards(boolean isP1){ return isP1 ? p1ForwardCards              : p2ForwardCards; }
	List<CardData> playerMonsterCards(boolean isP1){ return isP1 ? p1MonsterCards              : p2MonsterCards; }
	int  playerCrystals(boolean isP1)              { return isP1 ? gameState.getP1Crystals()   : gameState.getP2Crystals(); }
	int  playerCpForElem(boolean isP1, String e)   { return isP1 ? gameState.getP1CpForElement(e) : gameState.getP2CpForElement(e); }
	private Map<String, Integer> playerCpByElem(boolean isP1) { return isP1 ? gameState.getP1CpByElement() : gameState.getP2CpByElement(); }
	void playerAddCp(boolean isP1, String e, int n)    { if (isP1) gameState.addP1Cp(e, n);   else gameState.addP2Cp(e, n); }
	void playerSpendCp(boolean isP1, String e, int n)  { if (isP1) gameState.spendP1Cp(e, n); else gameState.spendP2Cp(e, n); }
	void playerClearCp(boolean isP1, String e)         { if (isP1) gameState.clearP1Cp(e);    else gameState.clearP2Cp(e); }
	void playerSpendCrystals(boolean isP1, int n)      { if (isP1) gameState.spendP1Crystals(n); else gameState.spendP2Crystals(n); }
	CardData playerBreakFromHand(boolean isP1, int i)  {
		CardData d = isP1 ? gameState.breakFromHand(i) : gameState.breakP2FromHand(i);
		if (d != null) animateCardDiscard(isP1, d);
		return d;
	}
	void playerDullBackupSlot(boolean isP1, int idx) {
		if (isP1) animateDullBackup(idx, true); else animateDullP2Backup(idx, true);
	}

	void animateDullP2Backup(int idx, boolean dulling) {
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
					Timer timer = new Timer(16, null);
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

	void animateDullP2Monster(int idx) {
		String url  = p2MonsterUrls.get(idx);
		JLabel slot = p2MonsterLabels.get(idx);
		if (url == null || slot == null) { refreshP2MonsterSlot(idx); return; }

		new SwingWorker<BufferedImage, Void>() {
			@Override protected BufferedImage doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				return raw == null ? null : CardAnimation.toARGB(raw, CARD_W, CARD_H);
			}
			@Override protected void done() {
				try {
					BufferedImage card = get();
					if (card == null) { refreshP2MonsterSlot(idx); return; }

					int   totalFrames = 12;
					int[] frame       = { 0 };
					Timer timer = new Timer(16, null);
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
							refreshP2MonsterSlot(idx);
						}
					});
					timer.start();
				} catch (InterruptedException | ExecutionException ignored) {
					refreshP2MonsterSlot(idx);
				}
			}
		}.execute();
	}

	void breakP2BackupSlot(int idx) {
		CardData c = p2BackupCards[idx];
		if (c == null) return;
		startBreakAnim(p2BackupLabels[idx]);
		logEntry("[P2] " + c.name() + " → Break Zone");
		addToP2BreakZone(c);
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
		addToP2BreakZone(c);
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

	/**
	 * Returns {@code true} if {@code ability} can currently be activated by the
	 * card at the given slot.
	 *
	 * @param state       current card state (ACTIVE / DULL / BRAVE_ATTACKED)
	 * @param playedTurn  turn the card entered the field (0 = unknown)
	 * @param sourceName  card name, needed for special-ability hand check
	 */
	boolean canActivateAbility(ActionAbility ability, boolean isFrozen, CardState state,
			int playedTurn, CardData source, boolean isP1) {
		if (ability.breakZoneOnly() != null) return false; // only activatable from the Break Zone
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
			if (playedTurn > 0 && gameState.getTurnNumber() - playedTurn < 2) return false;
		}
		if (ability.isSpecial() && !hasSameNameInHand(source.name(), getPrimerCardName(source, isP1), isP1)) return false;
		if (ability.damageThreshold() > 0) {
			int dmg = isP1 ? gameState.getP1DamageZone().size() : gameState.getP2DamageZone().size();
			if (dmg < ability.damageThreshold()) return false;
		}
		if (ability.requiresOppDiscardedThisTurn()) {
			boolean caused = isP1 ? p1CausedOpponentDiscardThisTurn : p2CausedOpponentDiscardThisTurn;
			if (!caused) return false;
		}
		if (ability.requiresCastSummonThisTurn()) {
			if (!(isP1 ? p1SummonCastThisTurn : p2SummonCastThisTurn)) return false;
		}
		if (ability.requiresOpponentEmptyHand()) {
			List<CardData> oppHand = isP1 ? gameState.getP2Hand() : gameState.getP1Hand();
			if (!oppHand.isEmpty()) return false;
		}
		if (ability.requiresNamedCardTookDamageThisTurn() != null) {
			Set<String> damaged = isP1 ? p1CardsTookDamageThisTurn : p2CardsTookDamageThisTurn;
			if (!damaged.contains(ability.requiresNamedCardTookDamageThisTurn())) return false;
		}
		if (ability.requiresSelfReceivedDamageThisTurn()) {
			if (!(isP1 ? p1ReceivedDamageThisTurn : p2ReceivedDamageThisTurn)) return false;
		}
		if (ability.requiresElementForwardEnteredThisTurn() != null) {
			Set<String> entered = isP1 ? p1ElementForwardsEnteredThisTurn : p2ElementForwardsEnteredThisTurn;
			if (!entered.contains(ability.requiresElementForwardEnteredThisTurn())) return false;
		}
		if (ability.controlCondition() != null && !controlConditionMet(ability.controlCondition(), isP1)) return false;
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
		return canAffordAbilityCost(ability, isP1);
	}

	/**
	 * Returns {@code true} when the "if you control [X]" restriction on an action ability is met
	 * by the controlling player's current field state.
	 */
	boolean controlConditionMet(ControlCondition cond, boolean isP1) {
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
				if (cond.anyOf()) {
					if (found) return true;
				} else if (!found) return false;
			}
			return !cond.anyOf();
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
			if (cond.element()        != null && !card.containsElement(cond.element()))        continue;
			if (cond.excludeElement() != null &&  card.containsElement(cond.excludeElement())) continue;
			if (cond.job()            != null && !meetsJobFilterEffective(card, cond.job()))   continue;
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
	 * Returns the effective element list of {@code c}, substituting the override element when present.
	 * Used to compare against the currently-resolving Summon/ability's elements.
	 */
	List<String> effectiveElements(CardData c) {
		String override = elementOverrideMap.get(c);
		return (override != null) ? List.of(override) : Arrays.asList(c.elements());
	}

	private String effectiveExtraJob(CardData card) {
		return permanentExtraJobMap.get(card);
	}

	boolean meetsJobFilterEffective(CardData card, String jobFilter) {
		return meetsJobFilter(card, jobFilter, effectiveExtraJob(card));
	}

	boolean meetsJobFilterEffective(CardData card, String jobFilter,
			List<CardData> controlledForwards) {
		if (meetsJobFilter(card, jobFilter, controlledForwards)) return true;
		String extra = effectiveExtraJob(card);
		if (extra == null || jobFilter == null) return false;
		for (String j : jobFilter.split("\\|"))
			if (extra.equalsIgnoreCase(j.trim())) return true;
		return false;
	}

	/**
	 * Returns {@code true} if any {@link IfControlBoost} on the given player's field
	 * targets {@code targetName} and grants it immunity to Summons ({@code forSummon=true})
	 * or abilities ({@code forSummon=false}) while its conditions are currently met.
	 */
	boolean icbGrantsImmunity(String targetName, boolean isP1, boolean forSummon) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : fwds)          if (icbSourceGrantsImmunity(src, targetName, isP1, forSummon)) return true;
		for (CardData bkp : bkps) if (bkp != null && icbSourceGrantsImmunity(bkp, targetName, isP1, forSummon)) return true;
		for (CardData src : mons)          if (icbSourceGrantsImmunity(src, targetName, isP1, forSummon)) return true;
		return false;
	}

	private boolean icbSourceGrantsImmunity(CardData src, String targetName, boolean isP1, boolean forSummon) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (IfControlBoost icb : src.ifControlBoosts()) {
			if (forSummon ? !icb.cannotBeChosenBySummons() : !icb.cannotBeChosenByAbilities()) continue;
			if (!icbTargetsName(icb, targetName, fwds, bkps, mons)) continue;
			if (icbConditionsMet(icb, isP1)) return true;
		}
		return false;
	}

	private static boolean icbTargetsName(IfControlBoost icb, String targetName,
			List<CardData> fwds, CardData[] bkps, List<CardData> mons) {
		if (icb.targetFilter() == null) {
			return icb.targetCardName().equalsIgnoreCase(targetName);
		}
		for (CardData c : fwds) if (targetName.equalsIgnoreCase(c.name()) && icb.appliesToCard(c)) return true;
		for (CardData c : mons) if (targetName.equalsIgnoreCase(c.name()) && icb.appliesToCard(c)) return true;
		for (CardData c : bkps) if (c != null && targetName.equalsIgnoreCase(c.name()) && icb.appliesToCard(c)) return true;
		return false;
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

		// Opposing-side debuffs ("The Forwards opponent controls lose N power") apply
		// to this target when the source sits across the field.
		List<CardData> oppFwds = isP1 ? p2ForwardCards : p1ForwardCards;
		CardData[]     oppBkps = isP1 ? p2BackupCards  : p1BackupCards;
		List<CardData> oppMons = isP1 ? p2MonsterCards : p1MonsterCards;
		for (CardData src : oppFwds) boost += opposingFieldDebuffContribution(src, target);
		for (CardData bkp : oppBkps) if (bkp != null) boost += opposingFieldDebuffContribution(bkp, target);
		for (CardData src : oppMons) boost += opposingFieldDebuffContribution(src, target);
		return boost;
	}

	/** Sum of {@link FieldPowerGrant#powerBonus} from {@code src} for grants that target the opposing side. */
	private int opposingFieldDebuffContribution(CardData src, CardData target) {
		int sum = 0;
		for (FieldPowerGrant fpg : src.fieldPowerGrants())
			if (fpg.affectsOpponent() && fpg.appliesToCard(target)) sum += fpg.powerBonus();
		return sum;
	}

	private int fieldBoostContribution(CardData src, CardData target, boolean isP1) {
		int boost = 0;
		for (IfControlBoost icb : src.ifControlBoosts())
			if (icb.appliesToCard(target) && icbConditionsMet(icb, isP1))
				boost += icb.powerBonus();
		for (FieldPowerGrant fpg : src.fieldPowerGrants())
			if (!fpg.affectsOpponent() && fpg.appliesToCard(target))
				boost += fpg.powerBonus();
		if (src == target) {
			for (ScalingSelfPowerBoost ssb : src.scalingSelfPowerBoosts()) {
				int count = switch (ssb.source()) {
					case OPPONENT_FORWARDS -> isP1 ? p2ForwardCards.size() : p1ForwardCards.size();
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
				};
				boost += ssb.perUnit() * count;
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
		if (ssb.elementFilter() != null && !c.containsElement(ssb.elementFilter())) return false;
		if (ssb.excludeElement() != null && c.containsElement(ssb.excludeElement())) return false;
		return matchesScalingFilter(c, ssb.jobFilter(), ssb.categoryFilter(), ssb.cardNameFilter());
	}

	/**
	 * OR-disjunction filter check used by {@link #scalingCharacterCounts}.
	 * Returns {@code true} if all three filters are {@code null} (no restriction) OR if the
	 * card matches at least one of the non-null filters.
	 */
	private boolean matchesScalingFilter(CardData c, String jobFilter, String categoryFilter, String cardNameFilter) {
		if (jobFilter == null && categoryFilter == null && cardNameFilter == null) return true;
		if (jobFilter      != null && CardFilters.meetsJobFilter(c, jobFilter))           return true;
		if (categoryFilter != null && CardFilters.meetsCategoryFilter(c, categoryFilter)) return true;
		if (cardNameFilter != null && CardFilters.meetsCardNameFilter(c, cardNameFilter)) return true;
		return false;
	}

	/**
	 * Collects all traits conditionally granted to {@code target} on the given player's side
	 * by any active {@link IfControlBoost} or {@link FieldPowerGrant} on the field.
	 */
	private EnumSet<CardData.Trait> computeConditionalTraitsForTarget(CardData target, boolean isP1) {
		EnumSet<CardData.Trait> out = EnumSet.noneOf(CardData.Trait.class);
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : fwds) collectFieldTraits(src, target, isP1, out);
		for (CardData bkp : bkps) if (bkp != null) collectFieldTraits(bkp, target, isP1, out);
		for (CardData src : mons) collectFieldTraits(src, target, isP1, out);
		return out;
	}

	private void collectFieldTraits(CardData src, CardData target, boolean isP1,
			EnumSet<CardData.Trait> out) {
		for (IfControlBoost icb : src.ifControlBoosts())
			if (icb.appliesToCard(target) && icbConditionsMet(icb, isP1))
				out.addAll(icb.grantedTraits());
		for (FieldPowerGrant fpg : src.fieldPowerGrants())
			if (!fpg.affectsOpponent() && fpg.appliesToCard(target))
				out.addAll(fpg.grantedTraits());
	}

	int effectiveP1MonsterPower(int idx) {
		CardData card = p1MonsterCards.get(idx);
		return card.power() + computeConditionalBoostForTarget(card, true) + p1MonsterPowerBoost.getOrDefault(card, 0);
	}

	int effectiveP2MonsterPower(int idx) {
		CardData card = p2MonsterCards.get(idx);
		return card.power() + computeConditionalBoostForTarget(card, false) + p2MonsterPowerBoost.getOrDefault(card, 0);
	}

	/** Power a P1 monster uses while acting as a Forward: become-Forward base + conditional/EOT boosts. */
	private int p1MonsterForwardPower(int idx) {
		CardData card = p1MonsterCards.get(idx);
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		int base = bfa != null ? bfa.power() : p1MonsterTempForwardPower.getOrDefault(card, 0);
		return base + computeConditionalBoostForTarget(card, true) + p1MonsterPowerBoost.getOrDefault(card, 0);
	}


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
		return new GameContextImpl(this, isP1, exBurst);
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

	/** Called at the start of Main Phase 1 to fire "At the beginning of your Main Phase 1" field abilities. */
	void fireFieldMainPhase1Abilities(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		GameContext ctx = buildGameContext(isP1);
		for (CardData card : fwds) fireFieldMainPhase1AbilitiesForCard(card, ctx);
		for (CardData card : bkps) if (card != null) fireFieldMainPhase1AbilitiesForCard(card, ctx);
		for (CardData card : mons) fireFieldMainPhase1AbilitiesForCard(card, ctx);
	}

	private void fireFieldMainPhase1AbilitiesForCard(CardData card, GameContext ctx) {
		for (FieldAbility fa : card.fieldAbilities()) {
			Consumer<GameContext> effect =
					ActionResolver.tryParseBeginningOfMainPhase1FieldAbility(fa.effectText(), card);
			if (effect != null) {
				logEntry("[Field] " + card.name() + " — Main Phase 1 start: " + fa.effectText());
				effect.accept(ctx);
			}
		}
	}

	/** Called at the start of Main Phase 2 to fire "At the beginning of your Main Phase 2" field abilities. */
	void fireFieldMainPhase2Abilities(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[]     bkps = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> mons = isP1 ? p1MonsterCards : p2MonsterCards;
		GameContext ctx = buildGameContext(isP1);
		for (CardData card : fwds) fireFieldMainPhase2AbilitiesForCard(card, ctx);
		for (CardData card : bkps) if (card != null) fireFieldMainPhase2AbilitiesForCard(card, ctx);
		for (CardData card : mons) fireFieldMainPhase2AbilitiesForCard(card, ctx);
	}

	private void fireFieldMainPhase2AbilitiesForCard(CardData card, GameContext ctx) {
		for (FieldAbility fa : card.fieldAbilities()) {
			Consumer<GameContext> effect =
					ActionResolver.tryParseBeginningOfMainPhase2FieldAbility(fa.effectText(), card);
			if (effect != null) {
				logEntry("[Field] " + card.name() + " — Main Phase 2 start: " + fa.effectText());
				effect.accept(ctx);
			}
		}
	}

	/** Fires all queued end-of-turn effects using a context for {@code isP1}, then clears the queue. */
	void fireEndOfTurnEffects(boolean isP1) {
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
		int amount = rawAmount * (isP1 ? p1ForwardIncomingDmgMult : p2ForwardIncomingDmgMult)
		                       * perCardIncomingDmgMultiplierMap.getOrDefault(card, 1);

		// Incoming damage increase (debuff) — applied regardless of reduction-disabled flag
		if (incomingDmgIncreaseMap.containsKey(card))
			amount += incomingDmgIncreaseMap.get(card);
		if (globalForwardIncomingDmgIncrease > 0)
			amount += globalForwardIncomingDmgIncrease;

		// Source-based nullification (these block damage by type of source, not by reducing amount)
		if (fromAbility) {
			// Nullify all ability/summon damage
			if (nullifyAbilityDmgSet.contains(card)) return 0;
			// Nullify ability-only damage (not Summons)
			if (!currentResolutionIsSummon && nullifyAbilityOnlyDmgSet.contains(card)) return 0;
			// Element-scoped nullification (Hein ability): covers both targeted and AoE damage
			String nullifyElem = nullifyElementDamageMap.get(card);
			if (nullifyElem != null) {
				CardData resCard = currentResolutionIsSummon ? currentSummonSource : currentAbilitySource;
				if (resCard != null && effectiveElements(resCard).contains(nullifyElem)) return 0;
			}
			// Passive field ability: nullify Summon-only damage
			if (currentResolutionIsSummon) {
				for (FieldAbility fa : card.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_NULLIFY_SUMMON_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) return 0;
				}
			}

			// Passive field ability: nullify ability-source damage entirely (not Summons) — e.g. Philia
			if (!currentResolutionIsSummon) {
				for (FieldAbility fa : card.fieldAbilities()) {
					Matcher m = AutoAbilityTriggers.FA_NULLIFY_ABILITY_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) {
						logEntry(card.name() + " — ability damage nullified by field ability (→ 0)");
						return 0;
					}
				}
			}

			// Passive field ability: reduce ability-source damage by N (gated on damage threshold)
			if (!currentResolutionIsSummon) {
				int dmgInZone = isP1 ? gameState.getP1DamageZone().size() : gameState.getP2DamageZone().size();
				for (FieldAbility fa : card.fieldAbilities()) {
					if (fa.damageThreshold() > 0 && dmgInZone < fa.damageThreshold()) continue;
					Matcher m = AutoAbilityTriggers.FA_REDUCE_ABILITY_DAMAGE.matcher(fa.effectText());
					if (m.find() && m.group("card").trim().equalsIgnoreCase(card.name())) {
						int reduction = Integer.parseInt(m.group("reduction"));
						int before = amount;
						amount = Math.max(0, amount - reduction);
						logEntry(card.name() + " — ability damage reduced by " + reduction + " (" + before + " → " + amount + ")");
					}
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

		// Passive field ability: self-targeted incoming damage modifier
		// ("by a Forward / by Summon or ability / other than battle damage / less than power / any source")
		for (FieldAbility fa : card.fieldAbilities()) {
			Matcher fam = AutoAbilityTriggers.FA_DAMAGE_MODIFIER.matcher(fa.effectText());
			if (!fam.find() || !fam.group("card").trim().equalsIgnoreCase(card.name())) continue;
			String src = fam.group("sourceclause");
			boolean applies;
			if (src == null || src.isBlank()) {
				applies = true;
			} else {
				String srcN = src.trim().toLowerCase();
				if (srcN.startsWith("less than") && srcN.endsWith("power")) {
					int power = isP1 ? effectiveP1ForwardPower(idx) : effectiveP2ForwardPower(idx);
					applies = amount < power;
				} else if (srcN.startsWith("by a forward")) {
					applies = !fromAbility;
				} else if (srcN.contains("summon") && !srcN.contains("abilit")) {
					applies = fromAbility && currentResolutionIsSummon;
				} else if (!srcN.contains("summon") && !srcN.startsWith("other")) {
					applies = fromAbility && !currentResolutionIsSummon;
				} else {
					applies = fromAbility;
				}
			}
			if (!applies) continue;
			String reduceStr = fam.group("reduceby");
			String setstoStr = fam.group("setsto");
			if (reduceStr != null) {
				int before = amount;
				amount = Math.max(0, amount - Integer.parseInt(reduceStr));
				logEntry(card.name() + " — damage reduced by " + reduceStr + " (" + before + " → " + amount + ")");
			} else if (setstoStr != null) {
				int fixed = Integer.parseInt(setstoStr);
				logEntry(card.name() + " — damage set to " + fixed + " instead");
				amount = fixed;
			}
		}

		// Passive field ability on other friendly cards: field-wide incoming damage modifier
		amount = applyFieldWideDamageModifiers(amount, card, isP1, idx, fromAbility);

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
	 * Scans all friendly Forwards and Backups for {@link AutoAbilityTriggers#FA_FIELD_DAMAGE_MODIFIER}
	 * abilities and applies any that target the damaged Forward.
	 * Returns the (possibly modified) damage amount.
	 */
	private int applyFieldWideDamageModifiers(int amount, CardData damaged, boolean isP1, int idx, boolean fromAbility) {
		int effectivePower = isP1 ? effectiveP1ForwardPower(idx) : effectiveP2ForwardPower(idx);
		boolean attackerIsBackup = !fromAbility && (isP1 ? pendingP2AttackerIsBackup : p1BackupAttackIdx >= 0);

		List<CardData> sources = new ArrayList<>(isP1 ? p1ForwardCards : p2ForwardCards);
		for (CardData bkp : isP1 ? p1BackupCards : p2BackupCards)
			if (bkp != null) sources.add(bkp);

		for (CardData protector : sources) {
			for (FieldAbility fa : protector.fieldAbilities()) {
				Matcher m = AutoAbilityTriggers.FA_FIELD_DAMAGE_MODIFIER.matcher(fa.effectText());
				if (!m.find()) continue;

				// Target filter
				String category = m.group("category");
				String job      = m.group("job");
				String costStr  = m.group("cost");
				String costcmp  = m.group("costcmp");
				String except   = m.group("except1") != null ? m.group("except1").trim()
				                                             : (m.group("except2") != null ? m.group("except2").trim() : null);

				if (category != null && !CardFilters.meetsCategoryFilter(damaged, category)) continue;
				if (job      != null && !CardFilters.meetsJobFilter(damaged, job))            continue;
				if (costStr  != null) {
					int costVal = Integer.parseInt(costStr);
					boolean orMore = "more".equalsIgnoreCase(costcmp);
					if (orMore ? damaged.cost() < costVal : damaged.cost() > costVal) continue;
				}
				if (except != null && except.equalsIgnoreCase(damaged.name())) continue;

				// Source clause
				String src = m.group("sourceclause");
				if (src != null && !src.isBlank()) {
					String srcN = src.trim().toLowerCase();
					if (srcN.contains("less than its power") && amount >= effectivePower) continue;
					if (srcN.contains("by a backup") && !attackerIsBackup) continue;
				}

				// Apply effect
				String reduceStr = m.group("reduceby");
				String setstoStr = m.group("setsto");
				if (reduceStr != null) {
					int before = amount;
					amount = Math.max(0, amount - Integer.parseInt(reduceStr));
					logEntry(damaged.name() + " — damage reduced by " + reduceStr
							+ " (" + before + " → " + amount + ") [" + protector.name() + "]");
				} else if (setstoStr != null) {
					int fixed = Integer.parseInt(setstoStr);
					logEntry(damaged.name() + " — damage set to " + fixed + " instead [" + protector.name() + "]");
					amount = fixed;
				}
			}
		}
		return amount;
	}

	/**
	 * Applies outgoing-damage modifiers for a forward that is about to deal combat damage.
	 * Checks and consumes the one-time "next outgoing damage = 0" shield.
	 */
	private int modifyOutgoingCombatDamage(boolean isP1, int idx, int rawAmount, CardData target) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		if (idx >= fwds.size()) return rawAmount;
		CardData card = fwds.get(idx);
		if (nextOutgoingDmgZeroSet.remove(card)) return 0;
		int mult = outgoingDmgMultiplierMap.getOrDefault(card, 1);
		if (nextOutgoingDmgDoublerSet.remove(card)) mult *= 2;
		if (target != null) mult *= fieldAbilityCombatOutgoingMult(card, target);
		int flat = (target != null) ? outgoingDmgFlatBoostMap.getOrDefault(card, 0) : 0;
		return rawAmount * mult + flat;
	}

	private int fieldAbilityCombatOutgoingMult(CardData attacker, CardData target) {
		int mult = 1;
		for (FieldAbility fa : attacker.fieldAbilities()) {
			Matcher m = AutoAbilityTriggers.FA_DOUBLE_DAMAGE_VS_COST_THRESHOLD.matcher(fa.effectText());
			if (m.find() && m.group("name").trim().equalsIgnoreCase(attacker.name())
					&& target.cost() >= Integer.parseInt(m.group("cost")))
				mult *= 2;
		}
		return mult;
	}

	/**
	 * Applies incoming-damage modifiers, writes the result to the damage accumulator,
	 * and breaks the forward if accumulated damage reaches its effective power.
	 */
	void applyDamageToMonster(boolean isP1, int idx, int amount) {
		List<CardData> mons    = isP1 ? p1MonsterCards  : p2MonsterCards;
		List<Integer>  dmgList = isP1 ? p1MonsterDamage : p2MonsterDamage;
		if (idx >= mons.size() || amount <= 0) return;
		int accum  = dmgList.get(idx) + amount;
		dmgList.set(idx, accum);
		boolean asFwd = isP1 ? isP1MonsterTemporarilyForward(idx) : isP2MonsterTemporarilyForward(idx);
		int effPow = asFwd ? (isP1 ? p1MonsterForwardPower(idx) : p2MonsterForwardPower(idx))
		                   : (isP1 ? effectiveP1MonsterPower(idx) : effectiveP2MonsterPower(idx));
		logEntry((isP1 ? "" : "[P2] ") + mons.get(idx).name() + " takes " + amount + " damage"
				+ (effPow > 0 ? " (" + (effPow - accum) + " remaining)" : ""));
		if (effPow > 0 && accum >= effPow) {
			if (isP1) autoAbilityTriggers.breakP1MonsterSlot(idx); else breakP2MonsterSlot(idx);
		} else {
			if (isP1) refreshP1MonsterSlot(idx); else refreshP2MonsterSlot(idx);
		}
	}

	void applyDamageToForward(boolean isP1, int idx, int rawAmount, boolean fromAbility, boolean unreduced) {
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
		(isP1 ? p1CardsTookDamageThisTurn : p2CardsTookDamageThisTurn).add(fwds.get(idx).name());
		int effPow = isP1 ? effectiveP1ForwardPower(idx) : effectiveP2ForwardPower(idx);
		logEntry((isP1 ? "" : "[P2] ") + fwds.get(idx).name() + " takes " + amount + " damage"
				+ (effPow > 0 ? " (" + (effPow - accum) + " remaining)" : ""));
		if (effPow > 0 && accum >= effPow) {
			CardData fwd = fwds.get(idx);
			if (cannotBeBrokenSet.contains(fwd)) {
				logEntry((isP1 ? "" : "[P2] ") + fwd.name() + " survives lethal damage (cannot be broken — damage clears at end of turn)");
				if (isP1) refreshP1ForwardSlot(idx); else refreshP2ForwardSlot(idx);
				if (currentSummonSource != null)
					fireBreaktouchForDamage(currentSummonSource, currentSummonSourceIsP1, isP1, idx);
			} else {
				if (isP1) breakP1Forward(idx); else breakP2Forward(idx);
			}
		} else {
			if (isP1) refreshP1ForwardSlot(idx); else refreshP2ForwardSlot(idx);
			// Fire "deals damage to forward" triggers from tracked ability source (e.g. Ramuh + Lightning Summon)
			if (currentSummonSource != null)
				fireBreaktouchForDamage(currentSummonSource, currentSummonSourceIsP1, isP1, idx);
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
	 * is sent directly to the Break Zone.  This does NOT count as "breaking", so
	 * "cannot be broken" protection is bypassed and break-zone auto-abilities do not
	 * fire.  "Leaves field" auto-abilities still fire.  Multicards are exempt.
	 *
	 * <p>Call this AFTER {@code incoming} has been added to the field and its
	 * enter-the-field abilities have been queued, so ETF effects resolve first.
	 * Returns {@code true} if any conflict was found.
	 */
	private boolean sendToBreakZoneByUniquenessRule(CardData incoming, boolean isP1) {
		if (incoming.multicard()) return false;
		if (isP1 && isMultiNameExceptionActive(incoming.name())) return false;
		boolean conflict = false;
		if (isP1) {
			// P1 forwards
			for (int i = p1ForwardCards.size() - 1; i >= 0; i--) {
				CardData c = p1ForwardCards.get(i);
				if (c == incoming) continue;
				if (!cardNamesOverlap(incoming, c)) continue;
				conflict = true;
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
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, true);
			}
			// P1 backups
			for (int i = 0; i < p1BackupCards.length; i++) {
				CardData c = p1BackupCards[i];
				if (c == null || c == incoming || !cardNamesOverlap(incoming, c)) continue;
				conflict = true;
				logEntry("[Uniqueness] " + c.name() + " — sent to Break Zone");
				addToP1BreakZone(c);
				p1BackupCards[i] = null; p1BackupStates[i] = CardState.ACTIVE;
				refreshP1BackupSlot(i); refreshP1BreakLabel();
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, true);
			}
			// P1 monsters
			for (int i = p1MonsterCards.size() - 1; i >= 0; i--) {
				CardData c = p1MonsterCards.get(i);
				if (c == incoming || !cardNamesOverlap(incoming, c)) continue;
				conflict = true;
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
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, true);
			}
		} else {
			// P2 forwards
			for (int i = p2ForwardCards.size() - 1; i >= 0; i--) {
				CardData c = p2ForwardCards.get(i);
				if (c == incoming) continue;
				if (!cardNamesOverlap(incoming, c)) continue;
				conflict = true;
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
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
			}
			// P2 backups
			for (int i = 0; i < p2BackupCards.length; i++) {
				CardData c = p2BackupCards[i];
				if (c == null || c == incoming || !cardNamesOverlap(incoming, c)) continue;
				conflict = true;
				logEntry("[Uniqueness] [P2] " + c.name() + " — sent to Break Zone");
				addToP2BreakZone(c);
				p2BackupCards[i] = null; p2BackupStates[i] = CardState.ACTIVE;
				refreshP2BackupSlot(i); refreshP2BreakLabel();
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
			}
			// P2 monsters
			for (int i = p2MonsterCards.size() - 1; i >= 0; i--) {
				CardData c = p2MonsterCards.get(i);
				if (c == incoming || !cardNamesOverlap(incoming, c)) continue;
				conflict = true;
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
				autoAbilityTriggers.triggerAutoAbilitiesForLeavesField(c, false);
			}
		}
		return conflict;
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
	List<ForwardTarget> selectFieldTargetsInPlace(
			List<ForwardTarget> eligible, int maxCount, boolean upTo, String title) {
		final Color GLOW_ELIGIBLE = new Color(90, 200, 255);
		final Color GLOW_PICKED   = Color.YELLOW;

		java.util.LinkedHashSet<Integer> sel = new java.util.LinkedHashSet<>();
		List<JLabel> labels = new ArrayList<>(eligible.size());
		Map<JLabel, javax.swing.border.Border> origBorders = new HashMap<>();
		List<java.awt.event.MouseListener> listeners = new ArrayList<>(eligible.size());
		List<ForwardTarget> result = new ArrayList<>();
		boolean[] dulls = new boolean[eligible.size()];
		final Timer[] pulseTimerRef = { null };

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
			if (nextPhaseButton != null) nextPhaseButton.setEnabled(nextWasEnabled);
			for (Integer si : sel) result.add(eligible.get(si));
			bar.dispose();
			if (loop != null) loop.exit();
		};

		fieldTargetingActive = true;
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
	List<ForwardTarget> showBreakZoneSelectDialog(
			List<ForwardTarget> eligible, List<CardData> zone,
			int maxCount, boolean upTo, String title) {
		if (eligible.isEmpty()) { logEntry("Choose: no eligible targets in break zone"); return List.of(); }
		if (!upTo && eligible.size() <= maxCount) return List.copyOf(eligible);
		return cardPickerDialog.pickFromBreakZone(eligible, zone, maxCount, upTo, title);
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
	void placeCardInForwardZone(CardData card) {
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
						&& gameState.getCurrentPhase() == GameState.GamePhase.ATTACK && attackSubStep != 0) {
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
		if (card.element() != null) p1ElementForwardsEnteredThisTurn.add(card.element().toLowerCase());
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
		if (!card.fieldCostReductions().isEmpty() || p1HandHasSelfCostModifiers()) refreshHandPopupIfVisible();
		autoAbilityTriggers.triggerAutoAbilitiesForEntersField(card, true);
		sendToBreakZoneByUniquenessRule(card, true);
	}

	/** Adds a Monster card to P1's monster zone (right side of forward zone, newest leftmost). */
	void placeCardInMonsterZone(CardData card) {
		if (p1MonsterPanel == null) return;
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
		autoAbilityTriggers.triggerAutoAbilitiesForEntersField(card, true);
		sendToBreakZoneByUniquenessRule(card, true);
	}

	/** Reloads and re-renders a single P1 monster slot using its stored URL and state. */
	void refreshP1MonsterSlot(int idx) {
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
		boolean canBlock  = isMonsterBlockSelectable(idx);
		boolean selected  = p1MonsterAttackIdx == idx || p1BlockerMonsterIdx == idx;
		int damage        = p1MonsterDamage.get(idx);
		boolean bfaActive = bfa != null && (
				bfa.minControlledMonsters() > 0 ? p1MonsterCards.size() >= bfa.minControlledMonsters() :
				bfa.damageThreshold()       > 0 ? gameState.getP1DamageZone().size() >= bfa.damageThreshold() :
				gameState.getCurrentPlayer() == GameState.Player.P1);
		boolean actingForward = bfaActive || tempFwdPower != null;
		int fwdPow = p1MonsterForwardPower(idx);
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.renderBackupCard(
						CardAnimation.toARGB(raw, CARD_W, CARD_H), state, canAttack || canBlock, selected, p1MonsterFrozen.get(idx));
				if (damage > 0)
					CardAnimation.renderDamageOverlay(canvas, damage, state);
				if (actingForward)
					CardAnimation.renderPowerOverlayRight(canvas, fwdPow, new Color(80, 220, 80), state);
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
	void placeP2CardInMonsterZone(CardData card) {
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
		autoAbilityTriggers.triggerAutoAbilitiesForEntersField(card, false);
		sendToBreakZoneByUniquenessRule(card, false);
	}

	/** Reloads and re-renders a single P2 monster slot using its stored URL and state. */
	void refreshP2MonsterSlot(int idx) {
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
		boolean bfaActive = bfa != null && (
				bfa.minControlledMonsters() > 0 ? p2MonsterCards.size() >= bfa.minControlledMonsters() :
				bfa.damageThreshold()       > 0 ? gameState.getP2DamageZone().size() >= bfa.damageThreshold() :
				gameState.getCurrentPlayer() == GameState.Player.P2);
		boolean actingForward = bfaActive || tempFwdPower != null;
		int fwdPow = p2MonsterForwardPower(idx);
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.toARGB(raw, CARD_W, CARD_H);
				canvas = CardAnimation.renderBackupCard(canvas, state, false, false, p2MonsterFrozen.get(idx));
				if (damage > 0)
					CardAnimation.renderDamageOverlay(canvas, damage, state);
				if (actingForward)
					CardAnimation.renderPowerOverlayRight(canvas, fwdPow, new Color(80, 220, 80), state);
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

	/** Reloads and re-renders a single P1 forward slot using its stored URL and state. */
	void refreshP1ForwardSlot(int idx) {
		CardData topCard = p1ForwardPrimedTop.get(idx);
		boolean  primed  = topCard != null;
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
				&& !p1ForwardCannotAttack.contains(idx)
				&& !p1ForwardCannotAttackPersistent.contains(idx)
				&& !fwdCard.cannotAttackOrBlock()
				&& !isFieldAbilityCannotAttackOrBlock(fwdCard, true)
				&& (hasHaste || p1ForwardPlayedOnTurn.get(idx) != gameState.getTurnNumber());
		boolean canBlock  = isForwardBlockSelectable(idx);
		int damage    = p1ForwardDamage.get(idx);
		int power     = effectiveP1ForwardPower(idx);
		int basePower = (topCard != null ? topCard : p1ForwardCards.get(idx)).power();
		boolean selected = p1AttackSelection.contains(idx) || p1BlockerSelection == idx;
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage canvas = CardAnimation.renderBackupCard(CardAnimation.toARGB(raw, CARD_W, CARD_H), state, canAttack || canBlock, selected, Boolean.TRUE.equals(p1ForwardFrozen.get(idx)));
				if (damage > 0) {
					CardAnimation.renderDamageOverlay(canvas, damage, state);
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

	void refreshAllForwardSlots() {
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
		CardData fwd = p1ForwardCards.get(idx);
		if (fwd.cannotAttackOrBlock()) return false;
		if (isFieldAbilityCannotAttackOrBlock(fwd, true)) return false;
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
		CardData blocker = p1ForwardCards.get(idx);
		if (blocker.cannotBlockAtAll() || blocker.cannotAttackOrBlock()) return false;
		if (isFieldAbilityCannotAttackOrBlock(blocker, true)) return false;
		if (blocker.cannotBlockParty() && pendingP2PartyIndices != null) return false;
		if (blocker.cannotBlockHigherPower() && attackerPowerExceedsBlocker(ForwardTarget.CardZone.FORWARD, idx)) return false;
		// Check attacker-side unblockability
		if (p2ForwardCannotBeBlocked.contains(pendingP2AttackerIdx)) return false;
		if (attackerConditionallyUnblockable()) return false;
		if (attackerBlockCostFiltersExclude(p1ForwardCards.get(idx).cost())) return false;
		if (attackerCannotBeBlockedByHigherPower() && blockerPowerExceedsAttacker(ForwardTarget.CardZone.FORWARD, idx)) return false;
		// If any forward must block, restrict choices to those
		if (!p1ForwardMustBlock.isEmpty() && !p1ForwardMustBlock.contains(idx)) return false;
		return true;
	}

	/**
	 * Returns {@code true} if any field ability on {@code card} currently prevents it from
	 * attacking or blocking (conditional forms — unconditional form is handled by
	 * {@link CardData#cannotAttackOrBlock()}).
	 */
	boolean isFieldAbilityCannotAttackOrBlock(CardData card, boolean isP1) {
		for (FieldAbility fa : card.fieldAbilities()) {
			java.util.regex.Matcher m2 = ActionResolver.IF_DONT_CONTROL_CARD_NAME_FWD_CANNOT_ATTACK_OR_BLOCK.matcher(fa.effectText());
			if (m2.find() && m2.group("subject").trim().equalsIgnoreCase(card.name())) {
				String required = m2.group("required").trim();
				List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
				if (fwds.stream().noneMatch(f -> f.name().equalsIgnoreCase(required))) return true;
			}
			java.util.regex.Matcher m3 = ActionResolver.IF_COUNTER_LIMIT_CANNOT_ATTACK_OR_BLOCK.matcher(fa.effectText());
			if (m3.find() && m3.group("subject").trim().equalsIgnoreCase(card.name())) {
				int    limit       = Integer.parseInt(m3.group("count"));
				String counterName = m3.group("countername").trim();
				if (gameState.getCounters(card, counterName) <= limit) return true;
			}
		}
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
		if (isP1 ? p1PartyAnyElementThisTurn : p2PartyAnyElementThisTurn) return true;
		// Check permanent field-ability grants from any card on the same player's field
		List<CardData> srcFwds = isP1 ? p1ForwardCards : p2ForwardCards;
		CardData[] srcBkps     = isP1 ? p1BackupCards  : p2BackupCards;
		List<CardData> srcMons = isP1 ? p1MonsterCards : p2MonsterCards;
		for (CardData src : srcFwds) for (FieldPartyAnyElement g : src.fieldPartyAnyElements()) if (g.appliesToCard(fwd)) return true;
		for (CardData src : srcBkps) if (src != null) for (FieldPartyAnyElement g : src.fieldPartyAnyElements()) if (g.appliesToCard(fwd)) return true;
		for (CardData src : srcMons) for (FieldPartyAnyElement g : src.fieldPartyAnyElements()) if (g.appliesToCard(fwd)) return true;
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
		Set<String> req = partyRequiredElements(isP1, indices);
		return req == null || !req.isEmpty();
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
		} else {
			toggleAttackSelection(idx);
		}
	}

	/** Toggles the P1 blocker selection during block-declaration sub-step. */
	private void toggleP1BlockerSelection(int idx) {
		if (!isForwardBlockSelectable(idx)) return;
		p1BlockerSelection = (p1BlockerSelection == idx) ? -1 : idx;
		p1BlockerMonsterIdx = -1;
		p1BlockerBackupIdx = -1;
		refreshAttackButton();
		refreshAllForwardSlots();
		for (int i = 0; i < p1BackupCards.length; i++) refreshP1BackupSlot(i);
	}

	/** Only Forward attackers track cannot-be-blocked; acting-as-Forwards don't. */
	private boolean attackerUnblockable() {
		if (pendingP2AttackerIsMonster || pendingP2AttackerIsBackup) return false;
		return p2ForwardCannotBeBlocked.contains(pendingP2AttackerIdx)
				|| attackerConditionallyUnblockable();
	}

	/** Returns true if any IfControlBoost on P2's field grants cannot-be-blocked to the attacker
	 *  and all of that boost's conditions are currently met. */
	private boolean attackerConditionallyUnblockable() {
		if (pendingP2AttackerIsMonster || pendingP2AttackerIsBackup) return false;
		CardData attacker = p2ForwardCards.get(pendingP2AttackerIdx);
		for (CardData src : p2ForwardCards)
			for (IfControlBoost icb : src.ifControlBoosts())
				if (icb.cannotBeBlocked() && icb.appliesToCard(attacker) && icbConditionsMet(icb, false))
					return true;
		for (CardData bkp : p2BackupCards)
			if (bkp != null)
				for (IfControlBoost icb : bkp.ifControlBoosts())
					if (icb.cannotBeBlocked() && icb.appliesToCard(attacker) && icbConditionsMet(icb, false))
						return true;
		for (CardData mon : p2MonsterCards)
			for (IfControlBoost icb : mon.ifControlBoosts())
				if (icb.cannotBeBlocked() && icb.appliesToCard(attacker) && icbConditionsMet(icb, false))
					return true;
		return false;
	}

	/** True when the current P2 attacker cannot be blocked by a Forward with greater power. */
	private boolean attackerCannotBeBlockedByHigherPower() {
		if (pendingP2AttackerIsMonster || pendingP2AttackerIsBackup) return false;
		return p2ForwardCards.get(pendingP2AttackerIdx).cannotBeBlockedByHigherPower();
	}

	/** True when the potential P1 blocker (given zone/idx) has strictly greater power than the attacker. */
	private boolean blockerPowerExceedsAttacker(ForwardTarget.CardZone blockerZone, int blockerIdx) {
		int attackerPower = fieldForwardPower(false, ForwardTarget.CardZone.FORWARD, pendingP2AttackerIdx);
		int blockerPower  = fieldForwardPower(true, blockerZone, blockerIdx);
		return blockerPower > attackerPower;
	}

	/** True when ANY attacker (single or every party member) has strictly greater power than the blocker. */
	private boolean attackerPowerExceedsBlocker(ForwardTarget.CardZone blockerZone, int blockerIdx) {
		int blockerPower = fieldForwardPower(true, blockerZone, blockerIdx);
		if (pendingP2PartyIndices != null) {
			for (int pi : pendingP2PartyIndices)
				if (effectiveP2ForwardPower(pi) > blockerPower) return true;
			return false;
		}
		if (pendingP2AttackerIsMonster || pendingP2AttackerIsBackup) return false;
		return fieldForwardPower(false, ForwardTarget.CardZone.FORWARD, pendingP2AttackerIdx) > blockerPower;
	}

	/**
	 * Returns true if the current P2 attacker's cost restrictions prevent a blocker of the
	 * given cost from blocking — checks dynamic (turn-scoped), intrinsic (field ability), and
	 * conditional (IfControlBoost) filters.
	 */
	private boolean attackerBlockCostFiltersExclude(int blockerCost) {
		if (pendingP2AttackerIsMonster || pendingP2AttackerIsBackup) return false;
		int[] dyn = p2ForwardCannotBeBlockedByCost.get(pendingP2AttackerIdx);
		if (dyn != null && blockerCostExcluded(blockerCost, dyn)) return true;
		int[] intr = p2ForwardCards.get(pendingP2AttackerIdx).fieldCannotBeBlockedByCost();
		if (intr != null && blockerCostExcluded(blockerCost, intr)) return true;
		CardData attacker = p2ForwardCards.get(pendingP2AttackerIdx);
		for (CardData src : p2ForwardCards)
			for (IfControlBoost icb : src.ifControlBoosts())
				if (icb.cannotBeBlockedByCost() != null && icb.appliesToCard(attacker)
						&& icbConditionsMet(icb, false)
						&& blockerCostExcluded(blockerCost, icb.cannotBeBlockedByCost()))
					return true;
		for (CardData bkp : p2BackupCards)
			if (bkp != null)
				for (IfControlBoost icb : bkp.ifControlBoosts())
					if (icb.cannotBeBlockedByCost() != null && icb.appliesToCard(attacker)
							&& icbConditionsMet(icb, false)
							&& blockerCostExcluded(blockerCost, icb.cannotBeBlockedByCost()))
						return true;
		for (CardData mon : p2MonsterCards)
			for (IfControlBoost icb : mon.ifControlBoosts())
				if (icb.cannotBeBlockedByCost() != null && icb.appliesToCard(attacker)
						&& icbConditionsMet(icb, false)
						&& blockerCostExcluded(blockerCost, icb.cannotBeBlockedByCost()))
					return true;
		return false;
	}

	/** True when a P1 monster acting as a Forward may be declared as a blocker. */
	private boolean isMonsterBlockSelectable(int idx) {
		if (!p1InBlockDeclaration()) return false;
		if (idx < 0 || idx >= p1MonsterStates.size()) return false;
		if (Boolean.TRUE.equals(p1MonsterFrozen.get(idx))) return false;
		CardState s = p1MonsterStates.get(idx);
		if (s != CardState.ACTIVE && s != CardState.BRAVE_ATTACKED) return false;
		if (!isP1MonsterTemporarilyForward(idx)) return false;
		if (!p1ForwardMustBlock.isEmpty()) return false;   // a Forward is forced to block
		if (attackerUnblockable()) return false;
		CardData monsterBlocker = p1MonsterCards.get(idx);
		if (monsterBlocker.cannotBlockAtAll() || monsterBlocker.cannotAttackOrBlock()) return false;
		if (isFieldAbilityCannotAttackOrBlock(monsterBlocker, true)) return false;
		if (monsterBlocker.cannotBlockParty() && pendingP2PartyIndices != null) return false;
		if (monsterBlocker.cannotBlockHigherPower() && attackerPowerExceedsBlocker(ForwardTarget.CardZone.MONSTER, idx)) return false;
		if (attackerBlockCostFiltersExclude(monsterBlocker.cost())) return false;
		if (attackerCannotBeBlockedByHigherPower() && blockerPowerExceedsAttacker(ForwardTarget.CardZone.MONSTER, idx)) return false;
		return true;
	}

	/** True when a P1 backup acting as a Forward may be declared as a blocker. */
	private boolean isBackupBlockSelectable(int idx) {
		if (!p1InBlockDeclaration()) return false;
		if (idx < 0 || idx >= p1BackupCards.length || p1BackupCards[idx] == null) return false;
		if (p1BackupFrozen[idx]) return false;
		CardState s = p1BackupStates[idx];
		if (s != CardState.ACTIVE && s != CardState.BRAVE_ATTACKED) return false;
		if (!isP1BackupTemporarilyForward(idx)) return false;
		if (!p1ForwardMustBlock.isEmpty()) return false;
		if (attackerUnblockable()) return false;
		CardData backupBlocker = p1BackupCards[idx];
		if (backupBlocker.cannotBlockAtAll() || backupBlocker.cannotAttackOrBlock()) return false;
		if (isFieldAbilityCannotAttackOrBlock(backupBlocker, true)) return false;
		if (backupBlocker.cannotBlockParty() && pendingP2PartyIndices != null) return false;
		if (backupBlocker.cannotBlockHigherPower() && attackerPowerExceedsBlocker(ForwardTarget.CardZone.BACKUP, idx)) return false;
		if (attackerBlockCostFiltersExclude(backupBlocker.cost())) return false;
		if (attackerCannotBeBlockedByHigherPower() && blockerPowerExceedsAttacker(ForwardTarget.CardZone.BACKUP, idx)) return false;
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
			setAttackSubStep(3);
			combatPriority("Blocker Declared", false, () -> {
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
			p1TakeDamage(() -> {
				autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, false);
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
			autoAbilityTriggers.triggerAutoAbilitiesForBlock(blocker, true);
			for (int idx : attackerIndices)
				autoAbilityTriggers.triggerAutoAbilitiesForIsBlocked(p2ForwardCards.get(idx), false);
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
				autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(p2ForwardCards.get(idx), false);
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

		// No time limit when P2 is a CPU
		if (isP2Cpu()) {
			countdownLabel.setText("Your priority");
		} else {
			combatPriorityTimer = new Timer(1000, null);
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
	}

	/**
	 * Auto-pass for the AI opponent: briefly flips the phase tracker to red (P2's priority),
	 * waits ~1.5 s, then restores blue and calls {@code onDone}.
	 */
	void p2AutoPass(Runnable onDone) {
		if (p2AutoPassTimer != null) { p2AutoPassTimer.stop(); p2AutoPassTimer = null; }
		phaseTracker.setHasPriority(false);
		p2AutoPassTimer = new Timer(1500, e -> {
			((Timer) e.getSource()).stop();
			p2AutoPassTimer = null;
			phaseTracker.setHasPriority(true);
			onDone.run();
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
		logEntry("[Priority] P2 passes — you may cast Summons or use abilities. Click Next Phase to pass.");
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
		p1BackupAttackIdx = -1;
		refreshAllForwardSlots();
		for (int i = 0; i < p1BackupCards.length; i++) refreshP1BackupSlot(i);
		if (p1AttackDeclarationsThisTurn >= p1AttackDeclarationLimit) {
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

	/** Returns true when the P1 monster at {@code idx} can attack as a Forward this turn. */
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
		return effectiveMonsterHasTrait(true, idx, CardData.Trait.HASTE)
				|| p1MonsterPlayedOnTurn.get(idx) != gameState.getTurnNumber();
	}

	/** Handles a left-click on a P1 monster slot during the attack phase. */
	private void handleP1MonsterLeftClick(int idx) {
		if (fieldTargetingActive) return;
		if (p1InBlockDeclaration()) { toggleP1MonsterBlocker(idx); return; }
		if (attackSubStep != 1) return;
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
		p1AttackDeclarationsThisTurn++;
		CardData attacker = p1MonsterCards.get(monIdx);
		int attackerPower = p1MonsterForwardPower(monIdx);

		if (effectiveMonsterHasTrait(true, monIdx, CardData.Trait.BRAVE)) {
			p1MonsterStates.set(monIdx, CardState.BRAVE_ATTACKED);
			refreshP1MonsterSlot(monIdx);
		} else {
			p1MonsterStates.set(monIdx, CardState.DULL);
			animateDullMonster(monIdx);
		}
		autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, true);

		setAttackSubStep(2);
		refreshAttackButton();

		logEntry(attacker.name() + " attacks! (Forward — " + attackerPower + ")");
		combatPriority("Attacker Declared", true, () -> {
			ForwardTarget blk = p2ChooseBlocker(attackerPower,
					new ForwardTarget(true, monIdx, ForwardTarget.CardZone.MONSTER));
			if (blk != null) {
				CardData blocker = autoAbilityTriggers.fieldCardData(blk);
				logEntry("[P2] " + blocker.name() + " blocks!");
				autoAbilityTriggers.triggerAutoAbilitiesForBlock(blocker, false);
				if (blk.zone() == ForwardTarget.CardZone.FORWARD) { p2BlockingIdx = blk.idx(); p2BlockedByAttacker = attacker; }
				autoAbilityTriggers.triggerAutoAbilitiesForIsBlocked(attacker, true);
				setAttackSubStep(3);
				combatPriority("Blocker Declared", true, () -> {
					resolveActingCombat(true, ForwardTarget.CardZone.MONSTER, monIdx, false, blk.zone(), blk.idx());
					p2BlockingIdx       = -1;
					p2BlockedByAttacker = null;
					continueAttackPhase();
				});
			} else {
				setAttackSubStep(3);
				p2TakeDamage(() -> {
					autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, true);
					continueAttackPhase();
				});
			}
		});
	}

	// ── Generalized combat for cards acting as Forwards (any zone on either side) ──

	private int fieldForwardPower(boolean isP1, ForwardTarget.CardZone zone, int idx) {
		return switch (zone) {
			case FORWARD -> isP1 ? effectiveP1ForwardPower(idx) : effectiveP2ForwardPower(idx);
			case MONSTER -> isP1 ? p1MonsterForwardPower(idx)   : p2MonsterForwardPower(idx);
			case BACKUP  -> isP1 ? p1BackupForwardPower(idx)    : p2BackupForwardPower(idx);
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
		};
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

	private void breakFieldCard(boolean isP1, ForwardTarget.CardZone zone, int idx) {
		switch (zone) {
			case FORWARD -> { if (isP1) breakP1Forward(idx);     else breakP2Forward(idx); }
			case MONSTER -> { if (isP1) autoAbilityTriggers.breakP1MonsterSlot(idx); else breakP2MonsterSlot(idx); }
			case BACKUP  -> { if (isP1) autoAbilityTriggers.breakP1BackupSlot(idx);  else breakP2BackupSlot(idx); }
		}
	}

	private boolean fieldForwardTrait(boolean isP1, ForwardTarget.CardZone zone, int idx, CardData.Trait trait) {
		return switch (zone) {
			case FORWARD -> isP1 ? effectiveP1HasTrait(idx, trait) : effectiveP2HasTrait(idx, trait);
			case MONSTER -> effectiveMonsterHasTrait(isP1, idx, trait);
			case BACKUP  -> effectiveBackupHasTrait(isP1, idx, trait);
		};
	}

	/**
	 * Resolves combat where at least one participant is a Monster/Backup acting as a Forward.
	 * Simplified model (power vs accumulated damage, First Strike aware; no outgoing/incoming
	 * damage modifiers). Forward-vs-Forward still uses {@link #resolveCombat}.
	 */
	private void resolveActingCombat(boolean atkP1, ForwardTarget.CardZone atkZone, int atkIdx,
			boolean blkP1, ForwardTarget.CardZone blkZone, int blkIdx) {
		CardData attacker = autoAbilityTriggers.fieldCardData(new ForwardTarget(atkP1, atkIdx, atkZone));
		CardData blocker  = autoAbilityTriggers.fieldCardData(new ForwardTarget(blkP1, blkIdx, blkZone));
		int atkPow = fieldForwardPower(atkP1, atkZone, atkIdx);
		int blkPow = fieldForwardPower(blkP1, blkZone, blkIdx);
		logEntry((atkP1 ? "" : "[P2] ") + attacker.name() + " (" + atkPow + ") vs "
				+ (blkP1 ? "" : "[P2] ") + blocker.name() + " (" + blkPow + ")");

		boolean atkFirst = fieldForwardTrait(atkP1, atkZone, atkIdx, CardData.Trait.FIRST_STRIKE)
				&& !fieldForwardTrait(blkP1, blkZone, blkIdx, CardData.Trait.FIRST_STRIKE);
		boolean blkFirst = fieldForwardTrait(blkP1, blkZone, blkIdx, CardData.Trait.FIRST_STRIKE)
				&& !fieldForwardTrait(atkP1, atkZone, atkIdx, CardData.Trait.FIRST_STRIKE);

		int dmgToAtk = blkPow;
		int dmgToBlk = atkPow;
		boolean atkBroken = dmgToAtk > 0 && fieldCombatDamage(atkP1, atkZone, atkIdx) + dmgToAtk >= atkPow;
		boolean blkBroken = dmgToBlk > 0 && fieldCombatDamage(blkP1, blkZone, blkIdx) + dmgToBlk >= blkPow;

		if (atkFirst && blkBroken)      { atkBroken = false; dmgToAtk = 0; }
		else if (blkFirst && atkBroken) { blkBroken = false; dmgToBlk = 0; }

		if (atkBroken) breakFieldCard(atkP1, atkZone, atkIdx);
		else if (!blkFirst && dmgToAtk > 0) addFieldCombatDamage(atkP1, atkZone, atkIdx, dmgToAtk);

		if (blkBroken) breakFieldCard(blkP1, blkZone, blkIdx);
		else if (!atkFirst && dmgToBlk > 0) addFieldCombatDamage(blkP1, blkZone, blkIdx, dmgToBlk);
	}

	/** Returns the power a P2 monster uses when attacking as a Forward. */
	int p2MonsterForwardPower(int idx) {
		CardData card = p2MonsterCards.get(idx);
		CardData.BecomeForwardAbility bfa = card.becomeForwardAbility();
		int base = bfa != null ? bfa.power() : p2MonsterTempForwardPower.getOrDefault(card, 0);
		return base + computeConditionalBoostForTarget(card, false) + p2MonsterPowerBoost.getOrDefault(card, 0);
	}

	/** Returns true when the P2 monster at {@code idx} can attack as a Forward this turn. */
	boolean p2MonsterCanAttackAsForward(int idx) {
		if (p2MonsterStates.get(idx) != CardState.ACTIVE) return false;
		if (!isP2MonsterTemporarilyForward(idx)) return false;
		return effectiveMonsterHasTrait(false, idx, CardData.Trait.HASTE)
				|| p2MonsterPlayedOnTurn.get(idx) != gameState.getTurnNumber();
	}

	// ── Backups acting as Forwards (e.g. 17-012R) ────────────────────────

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
	private int p1BackupForwardPower(int idx) {
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
		return effectiveBackupHasTrait(true, idx, CardData.Trait.HASTE)
				|| p1BackupPlayedOnTurn[idx] != gameState.getTurnNumber();
	}

	boolean p2BackupCanAttackAsForward(int idx) {
		if (idx < 0 || idx >= p2BackupCards.length || p2BackupCards[idx] == null) return false;
		if (p2BackupStates[idx] != CardState.ACTIVE) return false;
		return isP2BackupTemporarilyForward(idx);
	}

	/** Handles a left-click on a P1 backup slot during the attack phase (attack as a Forward). */
	private void handleP1BackupLeftClick(int idx) {
		if (fieldTargetingActive) return;
		if (p1InBlockDeclaration()) { toggleP1BackupBlocker(idx); return; }
		if (attackSubStep != 1) return;
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
		p1AttackDeclarationsThisTurn++;
		CardData attacker = p1BackupCards[bIdx];
		if (attacker == null) return;
		int attackerPower = p1BackupForwardPower(bIdx);

		if (effectiveBackupHasTrait(true, bIdx, CardData.Trait.BRAVE)) {
			p1BackupStates[bIdx] = CardState.BRAVE_ATTACKED;
			refreshP1BackupSlot(bIdx);
		} else {
			p1BackupStates[bIdx] = CardState.DULL;
			animateDullBackup(bIdx, true);
		}
		autoAbilityTriggers.triggerAutoAbilitiesForAttack(attacker, true);

		setAttackSubStep(2);
		refreshAttackButton();

		logEntry(attacker.name() + " attacks! (Forward — " + attackerPower + ")");
		combatPriority("Attacker Declared", true, () -> {
			ForwardTarget blk = p2ChooseBlocker(attackerPower,
					new ForwardTarget(true, bIdx, ForwardTarget.CardZone.BACKUP));
			if (blk != null) {
				CardData blocker = autoAbilityTriggers.fieldCardData(blk);
				logEntry("[P2] " + blocker.name() + " blocks!");
				autoAbilityTriggers.triggerAutoAbilitiesForBlock(blocker, false);
				if (blk.zone() == ForwardTarget.CardZone.FORWARD) { p2BlockingIdx = blk.idx(); p2BlockedByAttacker = attacker; }
				autoAbilityTriggers.triggerAutoAbilitiesForIsBlocked(attacker, true);
				setAttackSubStep(3);
				combatPriority("Blocker Declared", true, () -> {
					resolveActingCombat(true, ForwardTarget.CardZone.BACKUP, bIdx, false, blk.zone(), blk.idx());
					p2BlockingIdx       = -1;
					p2BlockedByAttacker = null;
					continueAttackPhase();
				});
			} else {
				setAttackSubStep(3);
				p2TakeDamage(() -> {
					autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, true);
					continueAttackPhase();
				});
			}
		});
	}

	/** Applies ability/combat damage to a backup that is currently acting as a Forward. */
	void applyDamageToBackup(boolean isP1, int idx, int amount) {
		CardData[] backs = isP1 ? p1BackupCards : p2BackupCards;
		if (idx < 0 || idx >= backs.length || backs[idx] == null || amount <= 0) return;
		boolean asFwd = isP1 ? isP1BackupTemporarilyForward(idx) : isP2BackupTemporarilyForward(idx);
		if (!asFwd) return;
		CardData c = backs[idx];
		Map<CardData, Integer> dmgMap = isP1 ? p1BackupForwardDamage : p2BackupForwardDamage;
		int accum = dmgMap.getOrDefault(c, 0) + amount;
		dmgMap.put(c, accum);
		int effPow = isP1 ? p1BackupForwardPower(idx) : p2BackupForwardPower(idx);
		logEntry((isP1 ? "" : "[P2] ") + c.name() + " takes " + amount + " damage"
				+ (effPow > 0 ? " (" + (effPow - accum) + " remaining)" : ""));
		if (effPow > 0 && accum >= effPow) {
			if (isP1) autoAbilityTriggers.breakP1BackupSlot(idx); else breakP2BackupSlot(idx);
		} else {
			if (isP1) refreshP1BackupSlot(idx); else refreshP2BackupSlot(idx);
		}
	}

	/** Clears all "Backup acting as Forward" state for both players (end of turn / reset). */
	void clearBackupForwardState() {
		p1BackupTempForwardPower.clear(); p2BackupTempForwardPower.clear();
		p1BackupForwardBoost.clear();     p2BackupForwardBoost.clear();
		p1BackupTempTraits.clear();       p2BackupTempTraits.clear();
		p1BackupForwardDamage.clear();    p2BackupForwardDamage.clear();
		p1TempGrantedAbilities.clear();   p2TempGrantedAbilities.clear();
		p1BackupAttackIdx = -1; p2BackupAttackIdx = -1;
		for (int i = 0; i < p1BackupCards.length; i++) refreshP1BackupSlot(i);
		for (int i = 0; i < p2BackupCards.length; i++) refreshP2BackupSlot(i);
	}

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
			attackButton.setEnabled(inAttack && p1Turn && hasAnyAttacker && attackSubStep == 1);
			attackButton.setText(n > 1 ? "Party Attack" : "Attack");
		}

		if (skipAttackButton != null)
			skipAttackButton.setEnabled(inAttack && p1Turn && attackSubStep == 1
					&& !p1InBlockDeclaration());
	}

	private void executeP1Attack(List<Integer> selection) {
		if (selection.isEmpty()) return;
		p1AttackDeclarationsThisTurn++;

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
			autoAbilityTriggers.triggerAutoAbilitiesForAttack(p1ForwardCards.get(idx), true);

		setAttackSubStep(2); // moving to block-declaration sub-step
		refreshAttackButton();

		if (selection.size() == 1) {
			int idx = selection.get(0);
			CardData attacker = p1ForwardCards.get(idx);
			logEntry(attacker.name() + " attacks!");
			// Priority window after attacker declared (P1 attacks → P1 priority first)
			combatPriority("Attacker Declared", true, () -> {
				ForwardTarget blk = p2ChooseBlocker(effectiveP1ForwardPower(idx),
						new ForwardTarget(true, idx, ForwardTarget.CardZone.FORWARD));
				if (blk != null) {
					CardData blocker = autoAbilityTriggers.fieldCardData(blk);
					logEntry("[P2] " + blocker.name() + " blocks!");
					autoAbilityTriggers.triggerAutoAbilitiesForBlock(blocker, false);
					if (blk.zone() == ForwardTarget.CardZone.FORWARD) { p2BlockingIdx = blk.idx(); p2BlockedByAttacker = attacker; }
					autoAbilityTriggers.triggerAutoAbilitiesForIsBlocked(attacker, true);
					setAttackSubStep(3);
					// Priority window after blocker declared (P1 still attacker → P1 first)
					combatPriority("Blocker Declared", true, () -> {
						if (blk.zone() == ForwardTarget.CardZone.FORWARD)
							resolveCombat(attacker, true, idx, blocker, false, blk.idx());
						else
							resolveActingCombat(true, ForwardTarget.CardZone.FORWARD, idx, false, blk.zone(), blk.idx());
						p2BlockingIdx       = -1;
						p2BlockedByAttacker = null;
						continueAttackPhase();
					});
				} else {
					setAttackSubStep(3);
					p2TakeDamage(() -> {
						autoAbilityTriggers.triggerAutoAbilitiesForDealsDamageToOpponent(attacker, true);
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
			p1FormedPartyThisTurn = true;
			List<CardData> p1PartyMembers = selection.stream()
					.map(p1ForwardCards::get).collect(Collectors.toList());
			autoAbilityTriggers.triggerAutoAbilitiesForPartyAttack(true, p1PartyMembers);
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
			// Party has First Strike only if every attacker has it and the blocker does not
			boolean partyFirst = attackerIndices.stream()
					.allMatch(i -> effectiveHasTrait(true, i, CardData.Trait.FIRST_STRIKE))
					&& !effectiveHasTrait(false, bestBlockerIdx, CardData.Trait.FIRST_STRIKE);
			boolean blockerBroken = combinedPower >= blockerPower;
			if (blockerBroken) breakP2Forward(bestBlockerIdx);
			if (!partyFirst || !blockerBroken) {
				applyPartyBlockerDamage(p2AiBuildDamageMap(attackerIndices, blockerPower));
			} else {
				logEntry("First Strike — party takes no return damage");
			}
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

	/** Applies a party-block damage map: logs, updates p1ForwardDamage, and breaks lethal targets. */
	private void applyPartyBlockerDamage(Map<Integer, Integer> damageMap) {
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
		// Party has First Strike only if every attacker has it and the blocker does not
		boolean partyFirst = attackerIndices.stream()
				.allMatch(i -> effectiveHasTrait(false, i, CardData.Trait.FIRST_STRIKE))
				&& !effectiveHasTrait(true, blockerIdx, CardData.Trait.FIRST_STRIKE);

		int blockerPower = effectiveP1ForwardPower(blockerIdx);
		logEntry("[P2] Party deals " + combinedPower + " damage to " + blocker.name());
		boolean blockerBroken = combinedPower >= blockerPower;
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
			applyP2PartyAttackerDamage(damageMap);
		} else {
			logEntry("First Strike — party takes no return damage");
		}
	}

	/** Applies a damage map onto P2 party attackers; breaks those that reach lethal. */
	private void applyP2PartyAttackerDamage(Map<Integer, Integer> damageMap) {
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

	private boolean hasAttackableForward() {
		int turn = gameState.getTurnNumber();
		for (int i = 0; i < p1ForwardStates.size(); i++) {
			CardData fwd = p1ForwardCards.get(i);
			if (p1ForwardStates.get(i) == CardState.ACTIVE
					&& !p1ForwardCannotAttack.contains(i)
					&& !p1ForwardCannotAttackPersistent.contains(i)
					&& !Boolean.TRUE.equals(p1ForwardFrozen.get(i))
					&& !fwd.cannotAttackOrBlock()
					&& !isFieldAbilityCannotAttackOrBlock(fwd, true)
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

	/** Shows a context menu for a P1 forward slot. */
	private void showForwardContextMenu(int idx, JLabel slot, MouseEvent e) {
		if (fieldTargetingActive) return;
		JPopupMenu menu = new JPopupMenu();

		// Action abilities (use effective card — top card when primed)
		CardData effectiveFwd = p1ForwardPrimedTop.get(idx) != null
				? p1ForwardPrimedTop.get(idx) : p1ForwardCards.get(idx);
		autoAbilityTriggers.addAbilityMenuItems(menu, effectiveFwd, p1ForwardFrozen.get(idx),
				p1ForwardStates.get(idx), p1ForwardPlayedOnTurn.get(idx),
				() -> { p1ForwardStates.set(idx, CardState.DULL); animateDullForward(idx, null); }, true);

		// Prime — visible only when not yet primed
		CardData fwd = p1ForwardCards.get(idx);
		if (fwd.hasPriming() && p1ForwardPrimedTop.get(idx) == null) {
			GameState.GamePhase phase = gameState.getCurrentPhase();
			boolean isMainPhase = phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.MAIN_2;
			JMenuItem primeItem = new JMenuItem("Prime (" + fwd.primingTarget() + ")");
			primeItem.setEnabled(isMainPhase && canAffordPrimingCost(fwd)
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
				() -> { p2ForwardStates.set(idx, CardState.DULL); refreshP2ForwardSlot(idx); }, false);

		if (fwd.hasPriming() && p2ForwardPrimedTop.get(idx) == null) {
			JMenuItem primeItem = new JMenuItem("Prime (" + fwd.primingTarget() + ")");
			primeItem.setEnabled(!primingTargetOnField(fwd.primingTarget()));
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
		autoAbilityTriggers.triggerAutoAbilitiesForPrimedInto(primingCard, chosen, false);
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
			playerBreakFromHand(true,di);
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
		autoAbilityTriggers.triggerAutoAbilitiesForPrimedInto(primingCard, chosen, true);
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

		// Holds the picked version; defaults to first match so closing without a click auto-picks.
		CardData[] picked = { matches.get(0) };

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
					lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
				}
				@Override public void mousePressed(MouseEvent e) {
					picked[0] = candidate;
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
		gameState.removeFromP1MainDeck(picked[0]);
		shuffleP1MainDeck();
		applyPrimedCard(picked[0], primingCard, slotIdx);
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
					this::animateMillOneCard));
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
	void refreshCrystalDisplays() {
		if (p1CrystalDisplay != null) p1CrystalDisplay.setCount(gameState.getP1Crystals());
		if (p2CrystalDisplay != null) p2CrystalDisplay.setCount(gameState.getP2Crystals());
	}

	// -------------------------------------------------------------------------
	// P2 rendering helpers
	// -------------------------------------------------------------------------

	boolean p2HasAvailableBackupSlot() {
		for (int i = 0; i < p2BackupCards.length; i++) {
			if (p2BackupCards[i] == null) return true;
		}
		return false;
	}

	void placeP2CardInForwardZone(CardData card) {
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
		if (card.element() != null) p2ElementForwardsEnteredThisTurn.add(card.element().toLowerCase());
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
		if (!card.fieldCostReductions().isEmpty() || p1HandHasSelfCostModifiers()) refreshHandPopupIfVisible();
		autoAbilityTriggers.triggerAutoAbilitiesForEntersField(card, false);
		sendToBreakZoneByUniquenessRule(card, false);
	}

	void placeP2CardInFirstBackupSlot(CardData card) {
		for (int i = 0; i < p2BackupCards.length; i++) {
			if (p2BackupCards[i] != null) continue;
			p2BackupUrls[i]   = card.imageUrl();
			p2BackupCards[i]  = card;
			p2BackupStates[i] = CardState.DULL;
			refreshP2BackupSlot(i);
			autoAbilityTriggers.triggerAutoAbilitiesForEntersField(card, false);
			sendToBreakZoneByUniquenessRule(card, false);
			return;
		}
	}

	void refreshP2BackupSlot(int idx) {
		String url    = p2BackupUrls[idx];
		JLabel slot   = p2BackupLabels[idx];
		CardState state = p2BackupStates[idx];
		if (slot == null) return;
		if (url == null) { slot.setIcon(null); slot.setText(null); return; }
		CardData card = p2BackupCards[idx];
		boolean actingForward = isP2BackupTemporarilyForward(idx);
		int fwdPower = actingForward ? p2BackupForwardPower(idx) : 0;
		int damage   = card != null ? p2BackupForwardDamage.getOrDefault(card, 0) : 0;
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
				return new ImageIcon(canvas);
			}
			@Override protected void done() {
				try {
					ImageIcon icon = get();
					if (icon != null && p2BackupUrls[idx] != null) { slot.setIcon(icon); slot.setText(null); }
				} catch (InterruptedException | ExecutionException ignored) {}
			}
		}.execute();
	}

	void refreshP2ForwardSlot(int idx) {
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
					CardAnimation.renderDamageOverlay(canvas, damage, state);
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

	void refreshAllP2ForwardSlots() {
		for (int i = 0; i < p2ForwardLabels.size(); i++) refreshP2ForwardSlot(i);
	}

	// -------------------------------------------------------------------------
	// Computer player (P2 AI) — implemented in ComputerPlayer.java
	// -------------------------------------------------------------------------
	// (inner class removed; see ComputerPlayer.java)

}
