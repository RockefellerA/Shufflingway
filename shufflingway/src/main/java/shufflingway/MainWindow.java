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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutionException;

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
import static shufflingway.CpPaymentUtils.contributingElement;
import static shufflingway.CpPaymentUtils.matchesAnyElement;
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
	private JPanel p1MonsterPanel;

	private final List<Boolean>   p2MonsterFrozen       = new ArrayList<>();
	private final List<JLabel>    p2MonsterLabels        = new ArrayList<>();
	private final List<String>    p2MonsterUrls          = new ArrayList<>();
	private final List<CardData>  p2MonsterCards         = new ArrayList<>();
	private final List<CardState> p2MonsterStates        = new ArrayList<>();
	private final List<Integer>   p2MonsterPlayedOnTurn  = new ArrayList<>();
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

	private int             p1LbIndex   = 0;
	private final Set<Integer> spentLbIndices   = new HashSet<>();
	private final Set<Integer> p2SpentLbIndices = new HashSet<>();
	private JButton            p2LimitButton;

	// Damage zone UI
	private JPanel   p1DamageSlotPanel;
	private JPanel[] p1DamageSlots = new JPanel[7];
	private JPanel   p2DamageSlotPanel;

	// Next-phase button and its glow animation
	private JButton              nextPhaseButton;
	private javax.swing.Timer    glowTimer;
	private final float[]        glowAngle = { 0f };

	// Attack button and selection state for party attacks
	private JButton              attackButton;
	private final List<Integer>  p1AttackSelection = new ArrayList<>();
	private int                  p1BlockingIdx     = -1;

	// Damage-shield / damage-modifier state (keyed by CardData identity; cleared at end of turn)
	private final Set<CardData>          nextIncomingDmgZeroSet   = new java.util.HashSet<>();
	private final Map<CardData, Integer> nextIncomingDmgReduceMap = new java.util.HashMap<>();
	private final Map<CardData, Integer> incomingDmgIncreaseMap   = new java.util.HashMap<>();
	private final Set<CardData>          nullifyAbilityDmgSet     = new java.util.HashSet<>();
	private final Set<CardData>          nextOutgoingDmgZeroSet   = new java.util.HashSet<>();
	private boolean p1NonLethalProtection = false;
	private boolean p2NonLethalProtection = false;
	private int     p1GlobalDmgReduction  = 0;
	private int     p2GlobalDmgReduction  = 0;

	/** End-of-turn effects queued this turn; fired at the beginning of the END phase. */
	private final List<java.util.function.Consumer<GameContext>> endOfTurnEffects = new ArrayList<>();

	/** Tracks once-per-turn ability uses this turn; keyed by card instance identity, value is set of effectText strings used. */
	private final java.util.IdentityHashMap<CardData, java.util.Set<String>> usedOncePerTurnAbilities = new java.util.IdentityHashMap<>();

	/** Set when "Take 1 more turn; lose at the end of that turn" fires. */
	private boolean p1ExtraTurnThenLose = false;

	public static void main(String[] args) {
		Runtime.getRuntime().addShutdownHook(new Thread(ImageCache::shutdown));
		EventQueue.invokeLater(new Runnable() {
			public void run() {
				try {
					MainWindow window = new MainWindow();
					window.frame.setVisible(true);
					ImageIcon icon40 = new ImageIcon(getClass().getResource("/resources/shufflingway.png"));
					window.frame.setIconImage(icon40.getImage());
				} catch (Exception e) {
					e.printStackTrace();
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
		menuBar.add(new FileMenu(frame, this::startGame,
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
			if (!p1AttackSelection.isEmpty()) {
				List<Integer> sel = new ArrayList<>(p1AttackSelection);
				p1AttackSelection.clear();
				refreshAttackButton();
				executeP1Attack(sel);
			}
		});

		// Next-phase button, centred below the preview
		JPanel nextBtnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 8));
		nextBtnPanel.add(nextPhaseButton);
		nextBtnPanel.add(attackButton);

		JPanel sideNorth = new JPanel();
		sideNorth.setLayout(new BoxLayout(sideNorth, BoxLayout.Y_AXIS));
		sideNorth.add(cardPreviewPanel);
		sideNorth.add(nextBtnPanel);

		// Game log (scrollable, fills the rest of the side panel)
		gameLog = new JTextArea();
		gameLog.setEditable(false);
		gameLog.setLineWrap(true);
		gameLog.setWrapStyleWord(true);
		gameLog.setFont(new Font("Courier New", Font.PLAIN, 12));
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
		handPanel.setPreferredSize(new Dimension(sidePanelW, CARD_H));
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

	private void startGame(int deckId) {
		gameState.reset();
		p1LbIndex = 0;
		endOfTurnEffects.clear();
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

					List<scraper.DeckDatabase.DeckSummary> eligible = db.getDecksSummary()
							.stream()
							.filter(d -> d.mainCardCount() == 50 && d.id() != deckId)
							.collect(java.util.stream.Collectors.toList());
					if (!eligible.isEmpty()) {
						scraper.DeckDatabase.DeckSummary chosen =
								eligible.get(new java.util.Random().nextInt(eligible.size()));
						p2DeckName = chosen.name();
						p2Cards    = db.getDeckCardsDetailed(chosen.id());
					} else {
						// Only one deck available — P2 uses the same deck as P1
						p2DeckName = "(same as P1)";
						p2Cards = new java.util.ArrayList<>(p1Cards);
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

				List<CardData> main = new ArrayList<>();
				List<CardData> lb   = new ArrayList<>();
				for (DeckCardDetail card : p1Cards) {
					String tx = card.textEn();
					CardData cd = new CardData(card.imageUrl(), card.name(), card.element(),
							card.cost(), card.power(), card.type(), card.isLb(), card.lbCost(), card.exBurst(),
							card.multicard(), CardData.parseTraits(tx),
							CardData.parseWarpValue(tx), CardData.parseWarpCost(tx),
							CardData.parsePrimingTarget(tx), CardData.parsePrimingCost(tx),
							CardData.parseActionAbilities(tx), CardData.parseFieldAbilities(tx), card.job(), card.category1(), card.category2(), tx);
					if (card.isLb()) lb.add(cd);
					else             main.add(cd);
				}
				gameState.initializeDeck(main, lb);
				refreshP1DeckLabel();
				refreshP1LimitLabel();
				drawOpeningHand();

				if (p2Cards != null) {
					List<CardData> p2Main = new ArrayList<>();
					List<CardData> p2Lb   = new ArrayList<>();
					for (DeckCardDetail card : p2Cards) {
						String tx = card.textEn();
						CardData cd = new CardData(card.imageUrl(), card.name(), card.element(),
								card.cost(), card.power(), card.type(), card.isLb(), card.lbCost(), card.exBurst(),
								card.multicard(), CardData.parseTraits(tx),
								CardData.parseWarpValue(tx), CardData.parseWarpCost(tx),
								CardData.parsePrimingTarget(tx), CardData.parsePrimingCost(tx),
								CardData.parseActionAbilities(tx), CardData.parseFieldAbilities(tx), card.job(), card.category1(), card.category2(), tx);
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
		@SuppressWarnings("unchecked")
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
						cardLabels[idx].setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
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

	private void refreshP1HandLabel() {
		refreshHandPanel();
	}

	private void refreshHandPanel() {
		if (handPanel == null) return;
		handPanel.removeAll();
		int n = gameState.getP1Hand().size();
		String text = n == 0 ? "HAND" : "HAND -" + n + "-";
		JLabel lbl = new JLabel(text, SwingConstants.CENTER);
		lbl.setFont(FontLoader.loadPixelNESFont(11));
		lbl.setForeground(Color.LIGHT_GRAY);
		lbl.setBounds(0, 0, handPanel.getWidth() > 0 ? handPanel.getWidth() : sidePanelW, CARD_H);
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
                            logEntry("Main Phase 1");
                            processWarpCounters();
            }

			case MAIN_1 -> {
                            p1AttackSelection.clear();
                            gameState.advancePhase();   // MAIN_1 → ATTACK
                            refreshAttackButton();
                            logEntry("Attack Phase");
                            refreshAllForwardSlots();
                            if (!hasAttackableForward() && !hasBackAttackInHand()) {
                                logEntry("No attackers available — skipping to Main Phase 2");
                                onNextPhase();
                            }
            }

			case ATTACK -> {
                            p1AttackSelection.clear();
                            refreshAttackButton();
                            gameState.advancePhase();   // ATTACK → MAIN_2
                            refreshAllForwardSlots();
                            logEntry("Main Phase 2");
			}

			case MAIN_2 -> {
                            gameState.advancePhase();   // MAIN_2 → END
                            logEntry("End Phase");
                            fireEndOfTurnEffects(true);
                            for (int i = 0; i < p1ForwardDamage.size(); i++) p1ForwardDamage.set(i, 0);
                            for (int i = 0; i < p1ForwardPowerBoost.size(); i++) p1ForwardPowerBoost.set(i, 0);
                            for (int i = 0; i < p1ForwardPowerReduction.size(); i++) p1ForwardPowerReduction.set(i, 0);
                            p1ForwardTempTraits.forEach(java.util.EnumSet::clear);
                            p1ForwardRemovedTraits.forEach(java.util.EnumSet::clear);
                            for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
                            for (int i = 0; i < p2ForwardDamage.size(); i++) p2ForwardDamage.set(i, 0);
                            for (int i = 0; i < p2ForwardPowerBoost.size(); i++) p2ForwardPowerBoost.set(i, 0);
                            for (int i = 0; i < p2ForwardPowerReduction.size(); i++) p2ForwardPowerReduction.set(i, 0);
                            p2ForwardTempTraits.forEach(java.util.EnumSet::clear);
                            p2ForwardRemovedTraits.forEach(java.util.EnumSet::clear);
                            p1ForwardCannotBlock.clear();           p2ForwardCannotBlock.clear();
                            p1ForwardMustBlock.clear();             p2ForwardMustBlock.clear();
                            p1ForwardCannotAttack.clear();          p2ForwardCannotAttack.clear();
                            p1ForwardMustAttack.clear();            p2ForwardMustAttack.clear();
                            p1ForwardCannotAttackPersistent.clear(); p1ForwardCannotBlockPersistent.clear();
                            nextIncomingDmgZeroSet.clear();   nextIncomingDmgReduceMap.clear();
                            incomingDmgIncreaseMap.clear();   nullifyAbilityDmgSet.clear();
                            nextOutgoingDmgZeroSet.clear();
                            p1NonLethalProtection = false;    p2NonLethalProtection = false;
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
					nextPhaseButton.setEnabled(true);
					endOfTurnEffects.add(ctx -> triggerGameOver("Extra Turn ended — You Lose!"));
					onNextPhase(); // begin ACTIVE → DRAW automatically
				} else {
					// END → ACTIVE: increments turn number and switches to P2
					gameState.advancePhase();
					nextPhaseButton.setEnabled(false);
					computerPlayer.runTurn();
				}
			}
		}
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
		p1ForwardPrimedTop.clear();
		p1ForwardFrozen.clear();
		p1MonsterFrozen.clear();
		p1AttackSelection.clear();
		java.util.Arrays.fill(p1BackupPlayedOnTurn, 0);
		java.util.Arrays.fill(p1BackupFrozen, false);

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

		// Crystal badges
		refreshCrystalDisplays();

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
				lbl.setBorder(BorderFactory.createLineBorder(new Color(212, 175, 55), 2));
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

		// Log the decrement for every card before we tick (zone is a live view)
		for (GameState.WarpEntry entry : zone) {
			int before = entry.counters;
			int after  = before - 1;
			logEntry("Warp: \"" + entry.card.name() + "\" counter " + before + " → " + after
					+ (after == 0 ? " (resolving!)" : ""));
		}

		List<CardData> resolved = gameState.tickP1WarpCounters();
		for (CardData card : resolved) {
			logEntry("Warp: \"" + card.name() + "\" enters play (auto-ability)");
			if (card.isForward()) {
				placeCardInForwardZone(card);
			} else if (card.isBackup()) {
				if (hasAvailableBackupSlot()) placeCardInFirstBackupSlot(card);
				else {
					gameState.getP1BreakZone().add(card);
					logEntry("  No backup slot — \"" + card.name() + "\" → Break Zone");
				}
			} else if (card.isMonster()) {
				placeCardInMonsterZone(card);
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

	private void p1TakeDamage() {
		if (gameState.isP1GameOver()) return;
		CardData drawn = gameState.drawToDamageZone();
		if (drawn == null) {
			triggerGameOver("P1 milled out — You Lose!");
			return;
		}
		int idx = gameState.getP1DamageZone().size() - 1;
		boolean isEx = drawn.exBurst();

		refreshP1DeckLabel();
		logEntry("P1 takes 1 damage — " + drawn.name() + (isEx ? " [EX BURST!]" : ""));

		if (gameState.getP1DamageZone().size() >= 7) {
			triggerGameOver("7 Damage Taken - You Lose!");
		}

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
		});
		revealTimer.setRepeats(false);
		revealTimer.start();
	}

	private void p2TakeDamage() {
		CardData drawn = gameState.drawToP2DamageZone();
		p2DamageCount++;
		boolean isEx = drawn != null && drawn.exBurst();
		String cardInfo = drawn != null ? " — " + drawn.name() + (isEx ? " [EX BURST!]" : "") : "";
		logEntry("P2 takes 1 damage (" + p2DamageCount + "/7)" + cardInfo);

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
		});
		revealTimer.setRepeats(false);
		revealTimer.start();

		if (p2DamageCount >= 7) {
			triggerGameOver("Player 2 Defeated - You Win!");
		}
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

	private void breakP1Forward(int idx) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		CardData card    = p1ForwardCards.get(idx);
		CardData topCard = p1ForwardPrimedTop.get(idx);

		if (topCard != null) {
			// Primed: both cards move to break zone, then top card is immediately RFP'd
			gameState.getP1BreakZone().add(card);
			gameState.getP1BreakZone().add(topCard);
			logEntry(card.name() + " + " + topCard.name() + " → Break Zone (Primed)");
			gameState.getP1BreakZone().remove(topCard);
			gameState.addToP1PermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Game");
		} else {
			gameState.getP1BreakZone().add(card);
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
		p1ForwardPrimedTop.remove(idx);
		p1ForwardFrozen.remove(idx);
		p1ForwardLabels.remove(idx);
		shiftBlockSet(p1ForwardCannotBlock,              idx);
		shiftBlockSet(p1ForwardMustBlock,                idx);
		shiftBlockSet(p1ForwardCannotAttack,             idx);
		shiftBlockSet(p1ForwardMustAttack,               idx);
		shiftBlockSet(p1ForwardCannotAttackPersistent,   idx);
		shiftBlockSet(p1ForwardCannotBlockPersistent,    idx);

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
							toggleAttackSelection(fi);
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
		refreshP1BreakLabel();
	}

	/** Removes P2's forward at {@code idx} from the field and sends it to P2's Break Zone. */
	private void breakP2Forward(int idx) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		CardData card = p2ForwardCards.get(idx);
		gameState.getP2BreakZone().add(card);
		logEntry("[P2] " + card.name() + " → Break Zone");

		p2ForwardCards.remove(idx);
		p2ForwardUrls.remove(idx);
		p2ForwardStates.remove(idx);
		p2ForwardPlayedOnTurn.remove(idx);
		p2ForwardDamage.remove(idx);
		p2ForwardPowerBoost.remove(idx);
		p2ForwardPowerReduction.remove(idx);
		p2ForwardTempTraits.remove(idx);
		p2ForwardRemovedTraits.remove(idx);
		p2ForwardFrozen.remove(idx);
		p2ForwardLabels.remove(idx);
		shiftBlockSet(p2ForwardCannotBlock,              idx);
		shiftBlockSet(p2ForwardMustBlock,                idx);
		shiftBlockSet(p2ForwardCannotAttack,             idx);
		shiftBlockSet(p2ForwardMustAttack,               idx);
		shiftBlockSet(p2ForwardCannotAttackPersistent,   idx);
		shiftBlockSet(p2ForwardCannotBlockPersistent,    idx);

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
		refreshP2BreakLabel();
	}

	private void returnP1ForwardToDeck(int idx, boolean toBottom) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		CardData card    = p1ForwardCards.get(idx);
		CardData topCard = p1ForwardPrimedTop.get(idx);
		String   pos     = toBottom ? "bottom" : "top";
		if (topCard != null) {
			gameState.getP1BreakZone().add(topCard);
			gameState.addToP1PermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Game");
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
							toggleAttackSelection(fi);
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
	}

	private void returnP2ForwardToDeck(int idx, boolean toBottom) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		CardData card = p2ForwardCards.get(idx);
		String   pos  = toBottom ? "bottom" : "top";
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
		p2ForwardFrozen.remove(idx);
		p2ForwardLabels.remove(idx);
		shiftBlockSet(p2ForwardCannotBlock,            idx);
		shiftBlockSet(p2ForwardMustBlock,              idx);
		shiftBlockSet(p2ForwardCannotAttack,           idx);
		shiftBlockSet(p2ForwardMustAttack,             idx);
		shiftBlockSet(p2ForwardCannotAttackPersistent, idx);
		shiftBlockSet(p2ForwardCannotBlockPersistent,  idx);

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
	}

	private void returnP1ForwardUnderDeckTop(int idx, int position) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		CardData card    = p1ForwardCards.get(idx);
		CardData topCard = p1ForwardPrimedTop.get(idx);
		if (topCard != null) {
			gameState.getP1BreakZone().add(topCard);
			gameState.addToP1PermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Game");
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
							toggleAttackSelection(fi);
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
	}

	private void returnP2ForwardUnderDeckTop(int idx, int position) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		CardData card = p2ForwardCards.get(idx);
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
		p2ForwardFrozen.remove(idx);
		p2ForwardLabels.remove(idx);
		shiftBlockSet(p2ForwardCannotBlock,            idx);
		shiftBlockSet(p2ForwardMustBlock,              idx);
		shiftBlockSet(p2ForwardCannotAttack,           idx);
		shiftBlockSet(p2ForwardMustAttack,             idx);
		shiftBlockSet(p2ForwardCannotAttackPersistent, idx);
		shiftBlockSet(p2ForwardCannotBlockPersistent,  idx);

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
	}

	private void searchDeckForCard(boolean inclForwards, boolean inclBackups,
			boolean inclMonsters, boolean inclSummons,
			int costVal, String costCmp, String cardNameFilter, String jobFilter,
			String categoryFilter, String elementFilter, String excludeName,
			String destination) {
		boolean anyType = !inclForwards && !inclBackups && !inclMonsters && !inclSummons;
		List<CardData> matches = new ArrayList<>();
		for (CardData c : gameState.getP1MainDeck()) {
			if (!anyType) {
				if (c.isForward() && !inclForwards) continue;
				if (c.isBackup()  && !inclBackups)  continue;
				if (c.isMonster() && !inclMonsters) continue;
				if (c.isSummon()  && !inclSummons)  continue;
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
			if (excludeName != null && excludeName.equalsIgnoreCase(c.name())) continue;
			matches.add(c);
		}
		if (matches.isEmpty()) {
			shuffleP1MainDeck();
			logEntry("Search: no matching card found in deck");
			return;
		}
		CardData chosen = showDeckSearchSelectDialog(matches);
		if (chosen != null) gameState.removeFromP1MainDeck(chosen);
		shuffleP1MainDeck();
		if (chosen == null) {
			logEntry("Search: no card selected");
			return;
		}
		switch (destination) {
			case "hand" -> {
				gameState.getP1Hand().add(chosen);
				logEntry(chosen.name() + " → hand (search)");
				refreshP1HandLabel();
			}
			case "field" -> {
				logEntry(chosen.name() + " → field (search)");
				if (chosen.isBackup())       placeCardInFirstBackupSlot(chosen);
				else if (chosen.isMonster()) placeCardInMonsterZone(chosen);
				else                         placeCardInForwardZone(chosen);
			}
			case "underTop" -> {
				Deque<CardData> deck = gameState.getP1MainDeck();
				if (deck.isEmpty()) {
					deck.addFirst(chosen);
				} else {
					CardData top = deck.pollFirst();
					deck.addFirst(chosen);
					deck.addFirst(top);
				}
				logEntry(chosen.name() + " → under top card of deck (search)");
				refreshP1DeckLabel();
			}
		}
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
					lbl.setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
				}
				@Override public void mouseExited(MouseEvent e) {
					hideZoom();
					lbl.setBorder(BorderFactory.createLineBorder(
							selection[0].equals(candidate) ? Color.YELLOW : Color.GRAY, 2));
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

	private void returnP1ForwardToHand(int idx) {
		if (idx < 0 || idx >= p1ForwardCards.size()) return;
		CardData card    = p1ForwardCards.get(idx);
		CardData topCard = p1ForwardPrimedTop.get(idx);
		if (topCard != null) {
			gameState.getP1BreakZone().add(topCard);
			gameState.addToP1PermanentRfp(topCard);
			logEntry(topCard.name() + " → Removed From Game");
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
							toggleAttackSelection(fi);
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
		refreshP1HandLabel();
	}

	private void returnP2ForwardToHand(int idx) {
		if (idx < 0 || idx >= p2ForwardCards.size()) return;
		CardData card = p2ForwardCards.get(idx);
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
		p2ForwardFrozen.remove(idx);
		p2ForwardLabels.remove(idx);
		shiftBlockSet(p2ForwardCannotBlock,            idx);
		shiftBlockSet(p2ForwardMustBlock,              idx);
		shiftBlockSet(p2ForwardCannotAttack,           idx);
		shiftBlockSet(p2ForwardMustAttack,             idx);
		shiftBlockSet(p2ForwardCannotAttackPersistent, idx);
		shiftBlockSet(p2ForwardCannotBlockPersistent,  idx);

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
		refreshP2HandCountLabel();
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
	}

	private void returnP1MonsterToHand(int idx) {
		if (idx < 0 || idx >= p1MonsterCards.size()) return;
		CardData c = p1MonsterCards.get(idx);
		gameState.getP1Hand().add(c);
		logEntry(c.name() + " → returned to hand");
		p1MonsterCards.remove(idx);
		p1MonsterStates.remove(idx);
		p1MonsterFrozen.remove(idx);
		p1MonsterPlayedOnTurn.remove(idx);
		p1MonsterUrls.remove(idx);
		JLabel lbl = p1MonsterLabels.remove(idx);
		if (p1MonsterPanel != null) { p1MonsterPanel.remove(lbl); p1MonsterPanel.revalidate(); p1MonsterPanel.repaint(); }
		refreshP1HandLabel();
	}

	private void returnP2MonsterToHand(int idx) {
		if (idx < 0 || idx >= p2MonsterCards.size()) return;
		CardData c = p2MonsterCards.get(idx);
		gameState.getP2Hand().add(c);
		logEntry("[P2] " + c.name() + " → returned to hand");
		p2MonsterCards.remove(idx);
		p2MonsterStates.remove(idx);
		p2MonsterFrozen.remove(idx);
		p2MonsterPlayedOnTurn.remove(idx);
		p2MonsterUrls.remove(idx);
		JLabel lbl = p2MonsterLabels.remove(idx);
		if (p2MonsterPanel != null) { p2MonsterPanel.remove(lbl); p2MonsterPanel.revalidate(); p2MonsterPanel.repaint(); }
	}

	private int effectiveP1ForwardPower(int idx) {
		CardData top = p1ForwardPrimedTop.get(idx);
		int base = top != null ? top.power() : p1ForwardCards.get(idx).power();
		return base + p1ForwardPowerBoost.get(idx) - p1ForwardPowerReduction.get(idx);
	}

	private int effectiveP2ForwardPower(int idx) {
		return p2ForwardCards.get(idx).power() + p2ForwardPowerBoost.get(idx) - p2ForwardPowerReduction.get(idx);
	}

	private boolean effectiveP1HasTrait(int idx, CardData.Trait trait) {
		if (p1ForwardRemovedTraits.get(idx).contains(trait)) return false;
		return p1ForwardCards.get(idx).hasTrait(trait) || p1ForwardTempTraits.get(idx).contains(trait);
	}

	private boolean effectiveP2HasTrait(int idx, CardData.Trait trait) {
		if (p2ForwardRemovedTraits.get(idx).contains(trait)) return false;
		return p2ForwardCards.get(idx).hasTrait(trait) || p2ForwardTempTraits.get(idx).contains(trait);
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
		int dmgToBlocker     = modifyIncomingDamage(blockerIsP1,  blockerIdx,  rawDmgToBlocker,  false);
		int rawDmgToAttacker = modifyOutgoingCombatDamage(blockerIsP1, blockerIdx, effBlockerPow);
		int dmgToAttacker    = modifyIncomingDamage(attackerIsP1, attackerIdx, rawDmgToAttacker, false);

		boolean attackerBroken = dmgToAttacker >= effAttackerPow;
		boolean blockerBroken  = dmgToBlocker  >= effBlockerPow;

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
		if (!attackerBroken && !blockerBroken) {
			logEntry("Both forwards survive combat");
		}
	}

	/**
	 * P2 AI: returns the index of the best P2 blocker against {@code attacker},
	 * or -1 if P2 declines to block.
	 * Strategy: block with the highest-power active forward that can survive (power >= attacker) or trade evenly.
	 */
	private int p2ChooseBlocker(int effectiveAttackerPower) {
		// Honour must-block forwards first: if any eligible must-block forward exists, the AI
		// must use one of them (preferring one that can survive the attack).
		if (!p2ForwardMustBlock.isEmpty()) {
			int bestIdx = -1, bestPower = -1;
			for (int i = 0; i < p2ForwardStates.size(); i++) {
				if (!p2ForwardMustBlock.contains(i))   continue;
				if (p2ForwardCannotBlock.contains(i) || p2ForwardCannotBlockPersistent.contains(i)) continue;
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
			if (p2ForwardStates.get(i) != CardState.ACTIVE) continue;
			int effPow = effectiveP2ForwardPower(i);
			if (effPow >= effectiveAttackerPower && effPow > bestPower) {
				bestPower = effPow;
				bestIdx = i;
			}
		}
		return bestIdx;
	}

	/** Called after P1 attacks: gives P2 AI a chance to declare a blocker. */
	private void p2OfferBlock(CardData attacker, int attackerIdx) {
		int blockerIdx = p2ChooseBlocker(effectiveP1ForwardPower(attackerIdx));
		if (blockerIdx >= 0) {
			CardData blocker = p2ForwardCards.get(blockerIdx);
			logEntry("[P2] " + blocker.name() + " blocks!");
			triggerFieldAbilitiesForBlock(blocker, false);
			resolveCombat(attacker, true, attackerIdx, blocker, false, blockerIdx);
		} else {
			p2TakeDamage();
		}
	}

	/**
	 * Called when P2 attacks: shows a modal dialog for P1 to optionally declare a blocker.
	 * {@code onDone} is called synchronously after combat (or taking damage) resolves.
	 */
	private void p1ChooseBlockerDialog(CardData attacker, int attackerIdx, Runnable onDone) {
		List<Integer> eligible = new ArrayList<>();
		for (int i = 0; i < p1ForwardStates.size(); i++) {
			CardState s = p1ForwardStates.get(i);
			// ACTIVE forwards can always block; BRAVE_ATTACKED forwards can block because
			// Brave allows acting again even after attacking
			if ((s == CardState.ACTIVE || s == CardState.BRAVE_ATTACKED)
					&& !p1ForwardCannotBlock.contains(i)
					&& !p1ForwardCannotBlockPersistent.contains(i))
				eligible.add(i);
		}

		// If any eligible forward has a must-block restriction, restrict choices to those forwards
		// and prevent the player from declining to block.
		boolean mustBlockOnly = false;
		if (!p1ForwardMustBlock.isEmpty()) {
			List<Integer> mustEligible = new ArrayList<>();
			for (int i : eligible) if (p1ForwardMustBlock.contains(i)) mustEligible.add(i);
			if (!mustEligible.isEmpty()) {
				eligible = mustEligible;
				mustBlockOnly = true;
			}
		}

		if (eligible.isEmpty()) {
			p1TakeDamage();
			onDone.run();
			return;
		}

		JDialog dlg = new JDialog(frame, "Declare Blocker", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		JPanel panel = new JPanel(new BorderLayout(8, 8));
		panel.setBorder(BorderFactory.createEmptyBorder(12, 12, 12, 12));

		JLabel header = new JLabel("[P2] " + attacker.name() + " (" + effectiveP2ForwardPower(attackerIdx) + ") attacks!");
		header.setFont(FontLoader.loadPixelNESFont(12));
		header.setHorizontalAlignment(SwingConstants.CENTER);
		panel.add(header, BorderLayout.NORTH);

		JPanel forwardPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
		int[] chosen = { -1 };
		for (int bi : eligible) {
			// Use the top card's name/power for display when the forward is primed
			CardData top = p1ForwardPrimedTop.get(bi);
			CardData display = top != null ? top : p1ForwardCards.get(bi);
			JButton btn = new JButton("<html><center>" + display.name() + "<br>(" + effectiveP1ForwardPower(bi) + ")</center></html>");
			btn.setFont(FontLoader.loadPixelNESFont(10));
			btn.setPreferredSize(new Dimension(130, 60));
			final int blockerIdx = bi;
			btn.addActionListener(ae -> { chosen[0] = blockerIdx; dlg.dispose(); });
			forwardPanel.add(btn);
		}
		panel.add(forwardPanel, BorderLayout.CENTER);

		if (!mustBlockOnly) {
			JButton noBlockBtn = new JButton("No Block (Take Damage)");
			noBlockBtn.setFont(FontLoader.loadPixelNESFont(11));
			noBlockBtn.addActionListener(ae -> dlg.dispose());
			panel.add(noBlockBtn, BorderLayout.SOUTH);
		}

		dlg.setContentPane(panel);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);

		if (chosen[0] >= 0) {
			CardData top = p1ForwardPrimedTop.get(chosen[0]);
			CardData blocker = top != null ? top : p1ForwardCards.get(chosen[0]);
			p1BlockingIdx = chosen[0];
			triggerFieldAbilitiesForBlock(blocker, true);
			resolveCombat(attacker, false, attackerIdx, blocker, true, chosen[0]);
			p1BlockingIdx = -1;
		} else {
			p1TakeDamage();
		}
		onDone.run();
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
			lbl.setBorder(BorderFactory.createLineBorder(
					cd.exBurst() ? Color.YELLOW : Color.LIGHT_GRAY, cd.exBurst() ? 2 : 1));

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
			lbl.setBorder(BorderFactory.createLineBorder(
					cd.exBurst() ? Color.YELLOW : Color.LIGHT_GRAY, cd.exBurst() ? 2 : 1));

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
		Set<Integer> paymentSet = new java.util.HashSet<>();

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
					lbl.setBorder(BorderFactory.createLineBorder(new Color(255, 200, 0), 3));
				} else if (payment) {
					lbl.setBorder(BorderFactory.createLineBorder(Color.CYAN, 3));
				} else if (spent) {
					lbl.setBorder(BorderFactory.createLineBorder(new Color(60, 60, 60), 1));
				} else if (nameBlocked) {
					lbl.setBorder(BorderFactory.createLineBorder(Color.RED, 2));
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

		JDialog dlg = new JDialog(frame, "End Phase — Discard to 5", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		Set<Integer> selected = new java.util.HashSet<>();
		int mustDiscard = hand.size() - 5;

		JLabel statusLabel = new JLabel("Select " + mustDiscard + " card(s) to discard.", SwingConstants.CENTER);
		statusLabel.setFont(FontLoader.loadPixelNESFont(10));

		List<JLabel> cardLabels = new ArrayList<>();
		JPanel cardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 8));

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
				cardLabels.get(i).setBorder(BorderFactory.createLineBorder(
						selected.contains(i) ? Color.RED : Color.LIGHT_GRAY, selected.contains(i) ? 3 : 1));
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
					else if (selected.size() < mustDiscard)  selected.add(idx);
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
				gameState.breakFromHand(di);
			}
			logEntry("Discarded " + toDiscard.size() + " card(s) — hand reduced to 5");
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
		south.add(statusLabel,  BorderLayout.CENTER);
		south.add(confirmBtn,   BorderLayout.EAST);
		south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

		dlg.getContentPane().setLayout(new BorderLayout(0, 4));
		dlg.getContentPane().add(scrollPane, BorderLayout.CENTER);
		dlg.getContentPane().add(south,      BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	/**
	 * Shows a modal dialog letting P1 choose exactly {@code count} cards
	 * (or fewer if hand is smaller) to discard to the Break Zone.  No CP is generated.
	 * Called when P2 activates a "Your opponent discards N cards" ability.
	 */
	private void showForcedDiscardDialog(int count) {
		List<CardData> hand = gameState.getP1Hand();
		int mustDiscard = Math.min(count, hand.size());
		if (mustDiscard == 0) return;

		JDialog dlg = new JDialog(frame, "Discard " + mustDiscard + " Card(s)", true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DO_NOTHING_ON_CLOSE);

		Set<Integer> selected = new java.util.HashSet<>();

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
				if (d != null) logEntry("Discards " + d.name() + " (forced by opponent)");
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
		handPanel.setPreferredSize(new Dimension(w, CARD_H));
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

			// Load image async
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(url);
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

	private void onHandPopupCardClicked(int handIdx, CardData card, JLabel cardLabel, MouseEvent e) {
		if (gameState.isP1GameOver()) return;
		cancelHandPopupHide();
		handCardMenuOpen = true;

		JPopupMenu menu = new JPopupMenu();

		JMenuItem playItem = new JMenuItem("Play");
		GameState.GamePhase phase = gameState.getCurrentPhase();
		boolean isMainPhase = phase == GameState.GamePhase.MAIN_1 || phase == GameState.GamePhase.MAIN_2;
		boolean isCharacter = card.isForward() || card.isBackup() || card.isMonster();
		boolean nameConflict = isCharacter && !card.multicard() && hasCharacterNameOnField(card.name());
		boolean lightDarkConflict = isCharacter && card.isLightOrDark() && hasLightOrDarkOnField(true);
		playItem.setEnabled(isMainPhase && !nameConflict && !lightDarkConflict && canAffordCard(card, handIdx)
				&& (!card.isBackup() || hasAvailableBackupSlot()));
		playItem.addActionListener(ae -> {
			hideZoom();
			if (handPopup != null) { handPopup.dispose(); handPopup = null; }
			showPaymentDialog(card, handIdx);
		});
		menu.add(playItem);

		if (card.hasWarp()) {
			JMenuItem warpItem = new JMenuItem("Play (Warp " + card.warpValue() + ")");
			warpItem.setEnabled(isMainPhase && canAffordWarpCost(card, handIdx));
			warpItem.addActionListener(ae -> {
				hideZoom();
				if (handPopup != null) { handPopup.dispose(); handPopup = null; }
				showWarpPaymentDialog(card, handIdx);
			});
			menu.add(warpItem);
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
	 * Returns true if the player can theoretically afford to play {@code card}
	 * by combining existing CP with potential discards from hand.
	 * {@code excludeHandIdx} is the index of the card being played (not available
	 * for discard).
	 */
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
			return totalExisting + totalGenerate >= card.cost();
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
				for (int ei = 0; ei < elems.length; ei++) {
					if (p1BackupCards[i].containsElement(elems[ei])) {
						totalGenerate += 1;
						hasElemSource[ei] = true;
						break;
					}
				}
			}
		}
		for (boolean hs : hasElemSource) if (!hs) return false;
		return totalExisting + totalGenerate >= card.cost();
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
	private void showWarpPaymentDialog(CardData card, int handIdx) {
		List<String> rawCost = card.warpCost();
		// Generic CP (empty-string entries) don't go into the per-element cost map
		long genericNeeded = rawCost.stream().filter(String::isEmpty).count();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);
		int totalCost  = rawCost.size();  // includes generic entries

		JDialog dlg = new JDialog(frame, "Warp: " + card.name(), true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		List<CardData> hand = gameState.getP1Hand();

		Map<String, Integer> bankCpByElem = new LinkedHashMap<>(costByElem);
		for (String k : bankCpByElem.keySet()) bankCpByElem.put(k, 0);

		List<Integer> selectedBackups  = new ArrayList<>();
		List<Integer> selectedDiscards = new ArrayList<>();

		List<Integer> eligibleBackupSlots = new ArrayList<>();
		for (int i = 0; i < p1BackupCards.length; i++) {
			// When there is generic CP in the cost, any undulled backup is eligible
			if (p1BackupCards[i] != null && p1BackupStates[i] == CardState.ACTIVE
					&& (genericNeeded > 0 || matchesAnyElement(p1BackupCards[i], elems)))
				eligibleBackupSlots.add(i);
		}

		JLabel cpLabel = new JLabel();
		cpLabel.setFont(FontLoader.loadPixelNESFont(11));
		cpLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JButton confirmBtn = new JButton("Confirm Warp");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

		List<JLabel>   backupLbls   = new ArrayList<>();
		List<Integer>  backupSlots  = new ArrayList<>();
		List<JLabel>   discardLbls  = new ArrayList<>();
		List<Integer>  discardIdxs  = new ArrayList<>();

		boolean[] canAddDiscard = {false};
		Runnable updateAll = () -> {
			Map<String, Integer> cpByElem = new LinkedHashMap<>(bankCpByElem);
			int extraCp = 0;
			for (int slot : selectedBackups) {
				if (matchesAnyElement(p1BackupCards[slot], elems))
					cpByElem.merge(contributingElement(p1BackupCards[slot], elems, cpByElem, costByElem), 1, Integer::sum);
				else
					extraCp++;
			}
			for (int idx : selectedDiscards) {
				if (matchesAnyElement(hand.get(idx), elems))
					cpByElem.merge(contributingElement(hand.get(idx), elems, cpByElem, costByElem), 2, Integer::sum);
				else
					extraCp += 2;
			}
			int total = cpByElem.values().stream().mapToInt(Integer::intValue).sum() + extraCp;
			int unsatisfied = (int) java.util.stream.IntStream.range(0, elems.length)
					.filter(ei -> cpByElem.getOrDefault(elems[ei], 0) < costByElem.get(elems[ei]))
					.count();
			boolean canAddBackup = total < totalCost;
			canAddDiscard[0] = (total < totalCost) || (unsatisfied > 0 && total + 2 <= totalCost + 2 * unsatisfied);
			boolean satisfied    = cpByElem.entrySet().stream()
					.allMatch(e -> e.getValue() >= costByElem.getOrDefault(e.getKey(), 0));
			confirmBtn.setEnabled(total >= totalCost && satisfied);

			StringBuilder sb = new StringBuilder("Warp CP: " + total + " / " + totalCost + "  (");
			boolean first = true;
			for (String e : elems) {
				if (!first) sb.append(", ");
				sb.append(e).append(": ").append(cpByElem.getOrDefault(e, 0))
				  .append("/").append(costByElem.get(e));
				first = false;
			}
			if (genericNeeded > 0) {
				if (!first) sb.append(", ");
				sb.append("any: ").append(Math.min(extraCp, (int) genericNeeded))
				  .append("/").append((int) genericNeeded);
				first = false;
			}
			if (first) sb.append("free");  // pure 0-cost warp
			sb.append(")");
			cpLabel.setText(sb.toString());

			for (int i = 0; i < backupLbls.size(); i++) {
				JLabel lbl = backupLbls.get(i);
				boolean sel = selectedBackups.contains(backupSlots.get(i));
				lbl.setBorder(BorderFactory.createLineBorder(
						sel ? Color.YELLOW : (canAddBackup ? Color.GRAY : new Color(80, 80, 80)), sel ? 3 : 1));
				lbl.setBackground(sel || canAddBackup ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(sel || canAddBackup
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			for (int i = 0; i < discardLbls.size(); i++) {
				JLabel lbl = discardLbls.get(i);
				boolean sel = selectedDiscards.contains(discardIdxs.get(i));
				lbl.setBorder(BorderFactory.createLineBorder(
						sel ? Color.YELLOW : (canAddDiscard[0] ? Color.GRAY : new Color(80, 80, 80)), sel ? 3 : 1));
				lbl.setBackground(sel || canAddDiscard[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(sel || canAddDiscard[0]
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
		};
		updateAll.run();

		JPanel centerPanel = new JPanel();
		centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

		if (!eligibleBackupSlots.isEmpty()) {
			JLabel hdr = new JLabel("Backups — dull for 1 CP each:");
			hdr.setFont(FontLoader.loadPixelNESFont(9));
			hdr.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel backupCardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
			backupCardsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
			for (int slot : eligibleBackupSlots) {
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true);
				lbl.setBackground(Color.DARK_GRAY);
				lbl.setForeground(Color.WHITE);
				lbl.setFont(FontLoader.loadPixelNESFont(10));
				lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
				lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				final String url = p1BackupUrls[slot];
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						int tot = bankCpByElem.values().stream().mapToInt(Integer::intValue).sum()
								+ selectedBackups.size() + selectedDiscards.size() * 2;
						if (selectedBackups.remove(Integer.valueOf(slot))) { /* deselect ok */ }
						else if (tot < totalCost) selectedBackups.add(slot);
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) showZoomAt(url); }
					@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				});
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(url);
						return img == null ? null
								: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
					}
					@Override protected void done() {
						try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
						catch (InterruptedException | ExecutionException ignored) {}
					}
				}.execute();
				backupLbls.add(lbl);
				backupSlots.add(slot);
				backupCardsPanel.add(lbl);
			}
			centerPanel.add(hdr);
			centerPanel.add(backupCardsPanel);
		}

		JLabel discardHdr = new JLabel("Hand — discard for 2 CP each:");
		discardHdr.setFont(FontLoader.loadPixelNESFont(9));
		discardHdr.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel discardCardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
		discardCardsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);
		for (int i = 0; i < hand.size(); i++) {
			if (i == handIdx) continue;
			final int hi = i;
			CardData hc  = hand.get(i);
			boolean payable = !hc.isLightOrDark();
			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50, 50, 50));
			lbl.setForeground(Color.WHITE);
			lbl.setFont(FontLoader.loadPixelNESFont(10));
			lbl.setBorder(BorderFactory.createLineBorder(payable ? Color.GRAY : new Color(80, 80, 80), 1));
			lbl.setCursor(payable ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			final String imgUrl = hc.imageUrl();
			if (payable) {
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0])
							selectedDiscards.add(hi);
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) showZoomAt(imgUrl); }
					@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				});
				discardLbls.add(lbl);
				discardIdxs.add(hi);
			} else {
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) { if (lbl.getIcon() != null) showZoomAt(imgUrl); }
					@Override public void mouseExited(MouseEvent e)  { hideZoom(); }
				});
			}
			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(imgUrl);
					return img == null ? null
							: new ImageIcon(img.getScaledInstance(CARD_W, CARD_H, Image.SCALE_SMOOTH));
				}
				@Override protected void done() {
					try { ImageIcon ic = get(); if (ic != null) { lbl.setIcon(ic); lbl.setText(null); } }
					catch (InterruptedException | ExecutionException ignored) {}
				}
			}.execute();
			discardCardsPanel.add(lbl);
		}
		centerPanel.add(discardHdr);
		centerPanel.add(discardCardsPanel);

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
		cancelBtn.addActionListener(e -> dlg.dispose());
		confirmBtn.addActionListener(e -> {
			dlg.dispose();
			executeWarpPlay(card, handIdx, new ArrayList<>(selectedDiscards), new ArrayList<>(selectedBackups));
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonPanel.add(confirmBtn);
		buttonPanel.add(cancelBtn);

		StringBuilder costDesc = new StringBuilder();
		boolean f = true;
		for (Map.Entry<String, Integer> en : costByElem.entrySet()) {
			if (!f) costDesc.append(" + ");
			costDesc.append(en.getValue()).append(" ").append(en.getKey()).append(" CP");
			f = false;
		}
		if (genericNeeded > 0) {
			if (!f) costDesc.append(" + ");
			costDesc.append((int) genericNeeded).append(" any CP");
		}
		JLabel titleLabel = new JLabel(
				"Warp cost for: " + card.name() + "  (" + (costDesc.length() > 0 ? costDesc : "free") + ")",
				SwingConstants.CENTER);
		titleLabel.setFont(FontLoader.loadPixelNESFont(11));

		JPanel topPanel = new JPanel(new BorderLayout(0, 4));
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		topPanel.add(titleLabel, BorderLayout.NORTH);
		topPanel.add(cpLabel,   BorderLayout.CENTER);

		JPanel mainPanel = new JPanel(new BorderLayout(0, 4));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
		mainPanel.add(new JScrollPane(centerPanel), BorderLayout.CENTER);
		mainPanel.add(buttonPanel,                  BorderLayout.SOUTH);

		dlg.getContentPane().setLayout(new BorderLayout());
		dlg.getContentPane().add(topPanel,  BorderLayout.NORTH);
		dlg.getContentPane().add(mainPanel, BorderLayout.CENTER);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	/**
	 * Pays the Warp alternate cost (dulls backups, discards hand cards), removes the card
	 * from hand, and places it in the Removed-From-Play zone with Warp counters.
	 */
	private void executeWarpPlay(CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices) {
		List<String> rawCost = card.warpCost();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) costByElem.merge(e, 1, Integer::sum);
		String[] elems = costByElem.keySet().toArray(String[]::new);

		for (int bi : backupDullIndices) {
			p1BackupStates[bi] = CardState.DULL;
			animateDullBackup(bi, true);
			String cpElem = matchesAnyElement(p1BackupCards[bi], elems)
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
		JDialog dlg = new JDialog(frame, "Play " + card.name(), true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		List<CardData> hand   = gameState.getP1Hand();
		String         elem   = card.element();
		String[]       elems  = card.elements();
		int            cost   = card.cost();
		boolean        isLD   = card.isLightOrDark();

		// Always start from 0 — CP is generated and spent within a single payment action
		// and must never carry over from a previous play.
		Map<String, Integer> bankCpByElem = new LinkedHashMap<>();
		if (isLD) {
			bankCpByElem.put(elem, 0);
		} else {
			for (String e : elems) bankCpByElem.put(e, 0);
		}

		List<Integer> selectedBackups  = new ArrayList<>();
		List<Integer> selectedDiscards = new ArrayList<>();

		// L/D cards: any undulled backup is eligible. Others: must match an element.
		List<Integer> eligibleBackupSlots = new ArrayList<>();
		for (int i = 0; i < p1BackupCards.length; i++) {
			if (p1BackupCards[i] != null && p1BackupStates[i] == CardState.ACTIVE
					&& (isLD || matchesAnyElement(p1BackupCards[i], elems)))
				eligibleBackupSlots.add(i);
		}

		JLabel cpLabel = new JLabel();
		cpLabel.setFont(FontLoader.loadPixelNESFont(11));
		cpLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

		List<JLabel>   backupLbls  = new ArrayList<>();
		List<Integer>  backupSlots = new ArrayList<>();
		List<JLabel>   discardLbls  = new ArrayList<>();
		List<Integer>  discardIdxs  = new ArrayList<>();

		// Required CP per element for the card being played (each element needs ≥ 1).
		Map<String, Integer> costByElem = new LinkedHashMap<>();
		if (!isLD) for (String e : elems) costByElem.put(e, 1);

		boolean[] canAddDiscard = {false};
		Runnable updateAll = () -> {
			Map<String, Integer> cpByElem = new LinkedHashMap<>(bankCpByElem);
			int extraCp = 0;
			// Sort: cards matching fewer required elements first → optimal greedy assignment.
			List<Integer> sortedBackups = new ArrayList<>(selectedBackups);
			if (!isLD) sortedBackups.sort(Comparator.comparingInt(s ->
					(int) java.util.Arrays.stream(elems)
							.filter(e -> p1BackupCards[s].containsElement(e)).count()));
			for (int slot : sortedBackups) {
				if (isLD)
					cpByElem.merge(elem, 1, Integer::sum);
				else if (matchesAnyElement(p1BackupCards[slot], elems))
					cpByElem.merge(contributingElement(p1BackupCards[slot], elems, cpByElem, costByElem), 1, Integer::sum);
				else
					extraCp += 1;
			}
			List<Integer> sortedDiscards = new ArrayList<>(selectedDiscards);
			if (!isLD) sortedDiscards.sort(Comparator.comparingInt(i ->
					(int) java.util.Arrays.stream(elems)
							.filter(e -> hand.get(i).containsElement(e)).count()));
			for (int idx : sortedDiscards) {
				if (isLD)
					cpByElem.merge(elem, 2, Integer::sum);
				else if (matchesAnyElement(hand.get(idx), elems))
					cpByElem.merge(contributingElement(hand.get(idx), elems, cpByElem, costByElem), 2, Integer::sum);
				else
					extraCp += 2;
			}
			int total = cpByElem.values().stream().mapToInt(Integer::intValue).sum() + extraCp;
			int unsatisfiedElems = isLD ? 0 : (int) cpByElem.values().stream().filter(v -> v < 1).count();
			boolean canAddBackup  = total < cost;
			canAddDiscard[0] = isLD
					? total < cost
					: (total < cost) || (extraCp == 0 && unsatisfiedElems > 0 && total + 2 <= cost + 2 * unsatisfiedElems);
			boolean allElemsPresent = isLD || cpByElem.values().stream().allMatch(v -> v >= 1);
			confirmBtn.setEnabled(total >= cost && allElemsPresent);
			if (elems.length == 1) {
				cpLabel.setText("CP: " + total + " / " + cost + "  (" + elem + ")");
			} else {
				StringBuilder sb = new StringBuilder("CP: " + total + " / " + cost + "  (");
				boolean first = true;
				for (Map.Entry<String, Integer> e : cpByElem.entrySet()) {
					if (!first) sb.append(", ");
					sb.append(e.getKey()).append(": ").append(e.getValue());
					first = false;
				}
				sb.append(")");
				cpLabel.setText(sb.toString());
			}
			for (int i = 0; i < backupLbls.size(); i++) {
				JLabel  lbl      = backupLbls.get(i);
				boolean selected = selectedBackups.contains(backupSlots.get(i));
				lbl.setBorder(BorderFactory.createLineBorder(
						selected ? Color.YELLOW : (canAddBackup ? Color.GRAY : new Color(80, 80, 80)),
						selected ? 3 : 1));
				lbl.setBackground(selected || canAddBackup ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(selected || canAddBackup
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			for (int i = 0; i < discardLbls.size(); i++) {
				JLabel  lbl      = discardLbls.get(i);
				boolean selected = selectedDiscards.contains(discardIdxs.get(i));
				lbl.setBorder(BorderFactory.createLineBorder(
						selected ? Color.YELLOW : (canAddDiscard[0] ? Color.GRAY : new Color(80, 80, 80)),
						selected ? 3 : 1));
				lbl.setBackground(selected || canAddDiscard[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(selected || canAddDiscard[0]
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
		};
		updateAll.run();

		// ── Backup section ───────────────────────────────────────────────────
		JPanel centerPanel = new JPanel();
		centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

		if (!eligibleBackupSlots.isEmpty()) {
			JLabel backupHeader = new JLabel("Backups — dull for 1 CP each:");
			backupHeader.setFont(FontLoader.loadPixelNESFont(9));
			backupHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel backupCardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
			backupCardsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

			for (int slot : eligibleBackupSlots) {
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true);
				lbl.setBackground(Color.DARK_GRAY);
				lbl.setForeground(Color.WHITE);
				lbl.setFont(FontLoader.loadPixelNESFont(10));
				lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
				lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				final String url = p1BackupUrls[slot];
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						int total = bankCpByElem.values().stream().mapToInt(Integer::intValue).sum() + selectedBackups.size() + selectedDiscards.size() * 2;
						if (selectedBackups.remove(Integer.valueOf(slot))) {
							// deselect always allowed
						} else if (total < cost) {
							selectedBackups.add(slot);
						}
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() != null) showZoomAt(url);
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(url);
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
				backupLbls.add(lbl);
				backupSlots.add(slot);
				backupCardsPanel.add(lbl);
			}
			centerPanel.add(backupHeader);
			centerPanel.add(backupCardsPanel);
		}

		// ── Hand discard section ─────────────────────────────────────────────
		JLabel discardHeader = new JLabel("Hand — discard for 2 CP each:");
		discardHeader.setFont(FontLoader.loadPixelNESFont(9));
		discardHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel discardCardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
		discardCardsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		for (int i = 0; i < hand.size(); i++) {
			if (i == handIdx) continue;
			final int hi   = i;
			CardData  hc   = hand.get(i);
			boolean payable = !hc.isLightOrDark();

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50, 50, 50));
			lbl.setForeground(Color.WHITE);
			lbl.setFont(FontLoader.loadPixelNESFont(10));
			lbl.setBorder(BorderFactory.createLineBorder(payable ? Color.GRAY : new Color(80, 80, 80), 1));
			lbl.setCursor(payable
					? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());

			final String  imgUrl = hc.imageUrl();
			final boolean grey   = !payable;
			if (payable) {
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0]) {
							selectedDiscards.add(hi);
						}
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() != null) showZoomAt(imgUrl);
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				discardLbls.add(lbl);
				discardIdxs.add(hi);
			} else {
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() != null) showZoomAt(imgUrl);
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
			}

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(imgUrl);
					if (img == null) return null;
					BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = buf.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
					g2.dispose();
					if (grey) {
						return new ImageIcon(
								new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null)
										.filter(buf, null));
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
			discardCardsPanel.add(lbl);
		}
		centerPanel.add(discardHeader);
		centerPanel.add(discardCardsPanel);

		// ── Hint ─────────────────────────────────────────────────────────────
		JLabel hint = new JLabel(
				"<html><center>Backups: dull for 1 CP. Hand cards (" + elem
				+ ", non-Light/Dark): discard for 2 CP.</center></html>",
				SwingConstants.CENTER);
		hint.setFont(FontLoader.loadPixelNESFont(9));

		// ── Buttons ──────────────────────────────────────────────────────────
		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
		cancelBtn.addActionListener(e -> dlg.dispose());

		confirmBtn.addActionListener(e -> {
			dlg.dispose();
			executePlay(card, handIdx, new ArrayList<>(selectedDiscards), new ArrayList<>(selectedBackups));
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonPanel.add(confirmBtn);
		buttonPanel.add(cancelBtn);

		// ── Assemble ─────────────────────────────────────────────────────────
		JLabel titleLabel = new JLabel(
				"Pay for: " + card.name() + "  (Cost " + cost + " " + elem + " CP)",
				SwingConstants.CENTER);
		titleLabel.setFont(FontLoader.loadPixelNESFont(11));

		JPanel topPanel = new JPanel(new BorderLayout(0, 4));
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		topPanel.add(titleLabel, BorderLayout.NORTH);
		topPanel.add(cpLabel,    BorderLayout.CENTER);
		topPanel.add(hint,       BorderLayout.SOUTH);

		JPanel mainPanel = new JPanel(new BorderLayout(0, 4));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
		mainPanel.add(new JScrollPane(centerPanel), BorderLayout.CENTER);
		mainPanel.add(buttonPanel,                  BorderLayout.SOUTH);

		dlg.getContentPane().setLayout(new BorderLayout());
		dlg.getContentPane().add(topPanel,  BorderLayout.NORTH);
		dlg.getContentPane().add(mainPanel, BorderLayout.CENTER);

		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
	}

	/**
	 * Executes the play: dulls selected backups, discards payment cards (high-index
	 * first to preserve indices), adds the generated CP to the bank, spends the cost,
	 * removes the played card from hand, and places it in the appropriate zone.
	 */
	private void executePlay(CardData card, int cardHandIdx,
			List<Integer> discardIndices, List<Integer> backupDullIndices) {
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
			String cpElem = isLD ? p1BackupCards[bi].elements()[0]
					: contributingElement(p1BackupCards[bi], elems, execCpAccum, execCostByElem);
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
		gameState.removeFromHand(cardHandIdx);
		logEntry("Played \"" + card.name() + "\"");

		if (card.isBackup()) {
			placeCardInFirstBackupSlot(card);
		} else if (card.isForward()) {
			placeCardInForwardZone(card);
		} else if (card.isMonster()) {
			placeCardInMonsterZone(card);
		} else if (card.isSummon()) {
			showSummonOnStack(card);
		}

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
			java.util.function.Consumer<GameContext> effect = ActionResolver.parse(effectText, entry.source());
			if (effect != null) effect.accept(ctx);
			else logEntry("[ActionResolver] Summon effect not yet implemented: " + effectText);
			gameState.getP1BreakZone().add(entry.source());
			logEntry("\"" + entry.source().name() + "\" → Break Zone");
			refreshP1BreakLabel();
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
		JDialog dlg = new JDialog(frame, "Pay CP for LB: " + card.name(), true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		List<CardData> hand   = gameState.getP1Hand();
		String         elem   = card.element();
		String[]       elems  = card.elements();
		int            cost   = card.cost();
		boolean        isLD   = card.isLightOrDark();

		// Always start from 0 — CP is generated and spent within a single payment action.
		Map<String, Integer> bankCpByElem = new LinkedHashMap<>();
		if (isLD) {
			bankCpByElem.put(elem, 0);
		} else {
			for (String e : elems) bankCpByElem.put(e, 0);
		}

		List<Integer> selectedBackups  = new ArrayList<>();
		List<Integer> selectedDiscards = new ArrayList<>();

		List<Integer> eligibleBackupSlots = new ArrayList<>();
		for (int i = 0; i < p1BackupCards.length; i++) {
			if (p1BackupCards[i] != null && p1BackupStates[i] == CardState.ACTIVE
					&& (isLD || matchesAnyElement(p1BackupCards[i], elems)))
				eligibleBackupSlots.add(i);
		}

		JLabel cpLabel = new JLabel();
		cpLabel.setFont(FontLoader.loadPixelNESFont(11));
		cpLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

		List<JLabel>  backupLbls  = new ArrayList<>();
		List<Integer> backupSlots = new ArrayList<>();
		List<JLabel>  discardLbls  = new ArrayList<>();
		List<Integer> discardIdxs  = new ArrayList<>();

		boolean[] canAddDiscard = {false};
		Runnable updateAll = () -> {
			Map<String, Integer> cpByElem = new LinkedHashMap<>(bankCpByElem);
			int extraCp = 0;
			for (int slot : selectedBackups) {
				if (isLD)
					cpByElem.merge(elem, 1, Integer::sum);
				else if (matchesAnyElement(p1BackupCards[slot], elems))
					cpByElem.merge(contributingElement(p1BackupCards[slot], elems), 1, Integer::sum);
				else
					extraCp += 1;
			}
			for (int idx : selectedDiscards) {
				if (isLD)
					cpByElem.merge(elem, 2, Integer::sum);
				else if (matchesAnyElement(hand.get(idx), elems))
					cpByElem.merge(contributingElement(hand.get(idx), elems), 2, Integer::sum);
				else
					extraCp += 2;
			}
			int total = cpByElem.values().stream().mapToInt(Integer::intValue).sum() + extraCp;
			int unsatisfiedElems = isLD ? 0 : (int) cpByElem.values().stream().filter(v -> v < 1).count();
			boolean canAddBackup  = total < cost;
			canAddDiscard[0] = isLD
					? total < cost
					: (total < cost) || (extraCp == 0 && unsatisfiedElems > 0 && total + 2 <= cost + 2 * unsatisfiedElems);
			boolean allElemsPresent = isLD || cpByElem.values().stream().allMatch(v -> v >= 1);
			confirmBtn.setEnabled(total >= cost && allElemsPresent);
			if (elems.length == 1) {
				cpLabel.setText("CP: " + total + " / " + cost + "  (" + elem + ")");
			} else {
				StringBuilder sb = new StringBuilder("CP: " + total + " / " + cost + "  (");
				boolean first = true;
				for (Map.Entry<String, Integer> e : cpByElem.entrySet()) {
					if (!first) sb.append(", ");
					sb.append(e.getKey()).append(": ").append(e.getValue());
					first = false;
				}
				sb.append(")");
				cpLabel.setText(sb.toString());
			}
			for (int i = 0; i < backupLbls.size(); i++) {
				JLabel  lbl      = backupLbls.get(i);
				boolean selected = selectedBackups.contains(backupSlots.get(i));
				lbl.setBorder(BorderFactory.createLineBorder(
						selected ? Color.YELLOW : (canAddBackup ? Color.GRAY : new Color(80, 80, 80)),
						selected ? 3 : 1));
				lbl.setBackground(selected || canAddBackup ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(selected || canAddBackup
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			for (int i = 0; i < discardLbls.size(); i++) {
				JLabel  lbl      = discardLbls.get(i);
				boolean selected = selectedDiscards.contains(discardIdxs.get(i));
				lbl.setBorder(BorderFactory.createLineBorder(
						selected ? Color.YELLOW : (canAddDiscard[0] ? Color.GRAY : new Color(80, 80, 80)),
						selected ? 3 : 1));
				lbl.setBackground(selected || canAddDiscard[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(selected || canAddDiscard[0]
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
		};
		updateAll.run();

		JPanel centerPanel = new JPanel();
		centerPanel.setLayout(new BoxLayout(centerPanel, BoxLayout.Y_AXIS));

		if (!eligibleBackupSlots.isEmpty()) {
			JLabel backupHeader = new JLabel("Backups — dull for 1 CP each:");
			backupHeader.setFont(FontLoader.loadPixelNESFont(9));
			backupHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
			JPanel backupCardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
			backupCardsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

			for (int slot : eligibleBackupSlots) {
				JLabel lbl = new JLabel("...", SwingConstants.CENTER);
				lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
				lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
				lbl.setOpaque(true);
				lbl.setBackground(Color.DARK_GRAY);
				lbl.setForeground(Color.WHITE);
				lbl.setFont(FontLoader.loadPixelNESFont(10));
				lbl.setBorder(BorderFactory.createLineBorder(Color.GRAY, 1));
				lbl.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
				final String url = p1BackupUrls[slot];
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						int total = bankCpByElem.values().stream().mapToInt(Integer::intValue).sum() + selectedBackups.size() + selectedDiscards.size() * 2;
						if (selectedBackups.remove(Integer.valueOf(slot))) {
							// deselect always allowed
						} else if (total < cost) {
							selectedBackups.add(slot);
						}
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() != null) showZoomAt(url);
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				new SwingWorker<ImageIcon, Void>() {
					@Override protected ImageIcon doInBackground() throws Exception {
						Image img = ImageCache.load(url);
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
				backupLbls.add(lbl);
				backupSlots.add(slot);
				backupCardsPanel.add(lbl);
			}
			centerPanel.add(backupHeader);
			centerPanel.add(backupCardsPanel);
		}

		JLabel discardHeader = new JLabel("Hand — discard for 2 CP each:");
		discardHeader.setFont(FontLoader.loadPixelNESFont(9));
		discardHeader.setAlignmentX(Component.LEFT_ALIGNMENT);
		JPanel discardCardsPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 6));
		discardCardsPanel.setAlignmentX(Component.LEFT_ALIGNMENT);

		for (int i = 0; i < hand.size(); i++) {
			final int hi  = i;
			CardData  hc  = hand.get(i);
			boolean payable = !hc.isLightOrDark();

			JLabel lbl = new JLabel("...", SwingConstants.CENTER);
			lbl.setPreferredSize(new Dimension(CARD_W, CARD_H));
			lbl.setMinimumSize(new Dimension(CARD_W, CARD_H));
			lbl.setOpaque(true);
			lbl.setBackground(payable ? Color.DARK_GRAY : new Color(50, 50, 50));
			lbl.setForeground(Color.WHITE);
			lbl.setFont(FontLoader.loadPixelNESFont(10));
			lbl.setBorder(BorderFactory.createLineBorder(payable ? Color.GRAY : new Color(80, 80, 80), 1));
			lbl.setCursor(payable
					? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());

			final String  imgUrl = hc.imageUrl();
			final boolean grey   = !payable;
			if (payable) {
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mousePressed(MouseEvent e) {
						if (!selectedDiscards.remove(Integer.valueOf(hi)) && canAddDiscard[0]) {
							selectedDiscards.add(hi);
						}
						updateAll.run();
					}
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() != null) showZoomAt(imgUrl);
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
				discardLbls.add(lbl);
				discardIdxs.add(hi);
			} else {
				lbl.addMouseListener(new MouseAdapter() {
					@Override public void mouseEntered(MouseEvent e) {
						if (lbl.getIcon() != null) showZoomAt(imgUrl);
					}
					@Override public void mouseExited(MouseEvent e) { hideZoom(); }
				});
			}

			new SwingWorker<ImageIcon, Void>() {
				@Override protected ImageIcon doInBackground() throws Exception {
					Image img = ImageCache.load(imgUrl);
					if (img == null) return null;
					BufferedImage buf = new BufferedImage(CARD_W, CARD_H, BufferedImage.TYPE_INT_ARGB);
					Graphics2D g2 = buf.createGraphics();
					g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
					g2.drawImage(img, 0, 0, CARD_W, CARD_H, null);
					g2.dispose();
					if (grey) {
						return new ImageIcon(
								new ColorConvertOp(ColorSpace.getInstance(ColorSpace.CS_GRAY), null)
										.filter(buf, null));
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
			discardCardsPanel.add(lbl);
		}
		centerPanel.add(discardHeader);
		centerPanel.add(discardCardsPanel);

		JLabel hint = new JLabel(
				"<html><center>Backups: dull for 1 CP. Hand cards (" + elem
				+ ", non-Light/Dark): discard for 2 CP.</center></html>",
				SwingConstants.CENTER);
		hint.setFont(FontLoader.loadPixelNESFont(9));

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
		cancelBtn.addActionListener(e -> { hideZoom(); dlg.dispose(); });

		confirmBtn.addActionListener(e -> {
			spentLbIndices.add(lbCastIdx);
			spentLbIndices.addAll(pendingLbPayment);
			logEntry("Cast LB \"" + card.name() + "\"");
			dlg.dispose();
			executeLbPlay(card, new ArrayList<>(selectedDiscards), new ArrayList<>(selectedBackups));
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonPanel.add(confirmBtn);
		buttonPanel.add(cancelBtn);

		JLabel titleLabel = new JLabel(
				"Pay for LB: " + card.name() + "  (Cost " + cost + " " + elem + " CP)",
				SwingConstants.CENTER);
		titleLabel.setFont(FontLoader.loadPixelNESFont(11));

		JPanel topPanel = new JPanel(new BorderLayout(0, 4));
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		topPanel.add(titleLabel, BorderLayout.NORTH);
		topPanel.add(cpLabel,    BorderLayout.CENTER);
		topPanel.add(hint,       BorderLayout.SOUTH);

		JPanel mainPanel = new JPanel(new BorderLayout(0, 4));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
		mainPanel.add(new JScrollPane(centerPanel), BorderLayout.CENTER);
		mainPanel.add(buttonPanel,                  BorderLayout.SOUTH);

		dlg.getContentPane().setLayout(new BorderLayout());
		dlg.getContentPane().add(topPanel,  BorderLayout.NORTH);
		dlg.getContentPane().add(mainPanel, BorderLayout.CENTER);

		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
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
		for (int i = 0; i < p1BackupLabels.length; i++) {
			if (p1BackupLabels[i] == null || p1BackupLabels[i].getIcon() != null) continue;
			p1BackupUrls[i]          = card.imageUrl();
			p1BackupCards[i]         = card;
			p1BackupStates[i]        = CardState.DULL;
			p1BackupPlayedOnTurn[i]  = gameState.getTurnNumber();
			refreshP1BackupSlot(i);
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

	/**
	 * Shows a debug context menu for a P1 backup slot.
	 * Only visible when Debug Mode is enabled.
	 */
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
		sb.append("[");
		boolean first = true;
		if (ability.requiresDull())    { sb.append("Dull");      first = false; }
		if (ability.isSpecial())       { if (!first) sb.append(", "); sb.append("S"); first = false; }
		if (ability.hasXCost())        { if (!first) sb.append(", "); sb.append("X"); first = false; }
		if (ability.yourTurnOnly())        { if (!first) sb.append(", "); sb.append("your turn"); first = false; }
		if (ability.oncePerTurn())         { if (!first) sb.append(", "); sb.append("1/turn");      first = false; }
		if (ability.mainPhaseOnly())       { if (!first) sb.append(", "); sb.append("main phase"); first = false; }
		if (ability.whilePartyAttacking()) { if (!first) sb.append(", "); sb.append("while party atk"); first = false; }
		else if (ability.whileCardAttacking() != null) { if (!first) sb.append(", "); sb.append("while ").append(ability.whileCardAttacking()).append(" atk"); first = false; }
		if (ability.whileCardBlocking() != null) { if (!first) sb.append(", "); sb.append("while ").append(ability.whileCardBlocking()).append(" blk"); first = false; }
		if (ability.crystalCost() > 0) { if (!first) sb.append(", "); sb.append(ability.crystalCost()).append(" Crystal"); first = false; }
		for (String e : ability.cpCost()) {
			if (!first) sb.append(", ");
			sb.append(e.isEmpty() ? "any" : e);
			first = false;
		}
		for (BreakZoneCost bz : ability.breakZoneCosts()) {
			if (!first) sb.append(", ");
			sb.append("put ");
			if (bz.name().isEmpty()) sb.append(bz.count()).append(' ').append(bz.cardType());
			else sb.append(bz.name());
			sb.append("→BZ");
			first = false;
		}
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts()) {
			if (!first) sb.append(", ");
			sb.append("RFG ");
			if (rfg.cardName() != null) sb.append(rfg.cardName());
			else {
				sb.append(rfg.count() == -1 ? "all" : rfg.count());
				if (rfg.element()  != null) sb.append(' ').append(rfg.element());
				if (rfg.cardType() != null) sb.append(' ').append(rfg.cardType());
				else sb.append(" card");
			}
			sb.append(" (").append(rfg.zone().toLowerCase().replace('_', ' ')).append(')');
			first = false;
		}
		for (ReturnToHandCost rth : ability.returnToHandCosts()) {
			if (!first) sb.append(", ");
			sb.append("RTH ");
			if (rth.cardName() != null) sb.append(rth.cardName());
			else {
				sb.append(rth.count());
				if (rth.category() != null) sb.append(" Cat.").append(rth.category());
				if (rth.cardType() != null) sb.append(' ').append(rth.cardType());
			}
			first = false;
		}
		sb.append("] → ");
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
		logEntry("[P2] " + c.name() + " → Break Zone");
		gameState.getP2BreakZone().add(c);
		p2BackupCards[idx]  = null;
		p2BackupUrls[idx]   = null;
		p2BackupStates[idx] = CardState.ACTIVE;
		p2BackupFrozen[idx] = false;
		if (p2BackupLabels[idx] != null) {
			p2BackupLabels[idx].setIcon(null);
			p2BackupLabels[idx].setText(null);
		}
		refreshP2BreakLabel();
	}

	private void breakP2MonsterSlot(int idx) {
		if (idx >= p2MonsterCards.size()) return;
		CardData c = p2MonsterCards.get(idx);
		logEntry("[P2] " + c.name() + " → Break Zone");
		gameState.getP2BreakZone().add(c);
		p2MonsterCards.remove(idx);
		p2MonsterStates.remove(idx);
		p2MonsterFrozen.remove(idx);
		p2MonsterPlayedOnTurn.remove(idx);
		p2MonsterUrls.remove(idx);
		JLabel lbl = p2MonsterLabels.remove(idx);
		if (p2MonsterPanel != null) {
			p2MonsterPanel.remove(lbl);
			p2MonsterPanel.revalidate();
			p2MonsterPanel.repaint();
		}
		refreshP2BreakLabel();
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
		if (ability.requiresDull()) {
			if (state != CardState.ACTIVE) return false;
			if (playedTurn == gameState.getTurnNumber()) return false;
		}
		if (ability.isSpecial() && !hasSameNameInHand(source.name(), isP1)) return false;
		if (ability.crystalCost() > 0 && playerCrystals(isP1) < ability.crystalCost()) return false;
		for (BreakZoneCost bz : ability.breakZoneCosts())
			if (!bzCostSatisfied(bz, isP1)) return false;
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts())
			if (!rfgCostSatisfied(rfg, isP1)) return false;
		for (ReturnToHandCost rth : ability.returnToHandCosts())
			if (!rfthCostSatisfied(rth, isP1)) return false;
		return canAffordAbilityCost(ability, isP1);
	}

	// -------------------------------------------------------------------------
	// Field Ability triggers
	// -------------------------------------------------------------------------

	/** Matches "pay 《cost》[.] When/If you do so, sub-effect[. The maximum you can pay for 《X》 is N]". */
	private static final java.util.regex.Pattern FA_PAY_WHEN_DO_SO = java.util.regex.Pattern.compile(
		"(?i)^pay\\s+《([^》]+)》[.,]?\\s+(?:When|If)\\s+you\\s+do\\s+so[,.]?\\s+(.+?)(?:[.,]?\\s+The\\s+maximum\\s+you\\s+can\\s+pay\\s+for\\s+《X》\\s+is\\s+\\d+\\.?)?$",
		java.util.regex.Pattern.DOTALL
	);
	private static final java.util.regex.Pattern FA_MAX_X = java.util.regex.Pattern.compile(
		"(?i)The\\s+maximum\\s+you\\s+can\\s+pay\\s+for\\s+《X》\\s+is\\s+(\\d+)"
	);

	private void triggerFieldAbilitiesForEntersField(CardData card, boolean isP1) {
		for (FieldAbility fa : card.fieldAbilities()) {
			if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
			if (fa.trigger().contains("enter")) executeFieldAbility(fa, card, isP1);
		}
	}

	private void triggerFieldAbilitiesForAttack(CardData card, boolean isP1) {
		for (FieldAbility fa : card.fieldAbilities()) {
			if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
			if (fa.trigger().contains("attack")) executeFieldAbility(fa, card, isP1);
		}
	}

	private void triggerFieldAbilitiesForBlock(CardData card, boolean isP1) {
		for (FieldAbility fa : card.fieldAbilities()) {
			if (!fa.triggerCard().equalsIgnoreCase(card.name())) continue;
			if (fa.trigger().contains("block")) executeFieldAbility(fa, card, isP1);
		}
	}

	/** Fires "party attacks" field abilities on every card the controller has on the field. */
	private void triggerFieldAbilitiesForPartyAttack(boolean isP1) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		for (int i = 0; i < fwds.size(); i++) {
			CardData card = fwds.get(i);
			for (FieldAbility fa : card.fieldAbilities())
				if (fa.trigger().equals("party attacks"))
					executeFieldAbility(fa, card, isP1);
		}
		CardData[] bkps = isP1 ? p1BackupCards : p2BackupCards;
		for (CardData card : bkps) {
			if (card == null) continue;
			for (FieldAbility fa : card.fieldAbilities())
				if (fa.trigger().equals("party attacks"))
					executeFieldAbility(fa, card, isP1);
		}
	}

	/**
	 * Resolves a triggered field ability.  When the ability is optional ({@code youMay} or
	 * {@code opponentMay}), P1 is shown a Decline / OK dialog; the AI always accepts.
	 *
	 * <p>For {@code opponentMay} effects the execution context is flipped to the opponent's
	 * perspective so that "play from hand" and similar effects target the correct player.
	 */
	private void executeFieldAbility(FieldAbility fa, CardData source, boolean isP1) {
		// opponentMay effects run from the opponent's context
		boolean effectIsP1 = fa.opponentMay() ? !isP1 : isP1;

		// Detect "pay 《X/N》. When you do so, [effect]" — requires a payment dialog before resolving.
		java.util.regex.Matcher payM = FA_PAY_WHEN_DO_SO.matcher(fa.effectText());
		if (payM.find()) {
			executePayWhenDoSoFieldAbility(fa, source, isP1, effectIsP1, payM);
			return;
		}

		java.util.function.Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
		if (effect == null) {
			logEntry("[FieldAbility] Unrecognized effect: " + fa.effectText());
			return;
		}

		// P1 (human) decides when: they control the card and "you may", or
		// they are the opponent of P2's "your opponent may" ability.
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = JOptionPane.showOptionDialog(frame,
					source.name() + " — " + prompt,
					"Field Ability",
					JOptionPane.DEFAULT_OPTION,
					JOptionPane.PLAIN_MESSAGE,
					null,
					new Object[]{"OK", "Decline"},
					"OK");
			if (choice != 0) {
				logEntry("[FieldAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			logEntry("[FieldAbility] [AI] auto-accepts optional ability");
		}

		logEntry("[FieldAbility] " + source.name() + " — " + fa.effectText());
		effect.accept(buildGameContext(effectIsP1));
	}

	private void executePayWhenDoSoFieldAbility(FieldAbility fa, CardData source, boolean isP1,
			boolean effectIsP1, java.util.regex.Matcher payM) {
		String costToken = payM.group(1).trim();
		String subEffect = payM.group(2).trim().replaceAll("[.!,]+$", "");

		boolean isXCost = costToken.equalsIgnoreCase("X");
		int fixedCost;
		if (!isXCost) {
			try { fixedCost = Integer.parseInt(costToken); }
			catch (NumberFormatException e) {
				// Non-numeric, non-X cost token (e.g. 《C》 for crystal) — resolve normally.
				java.util.function.Consumer<GameContext> effect = ActionResolver.parse(fa.effectText(), source);
				if (effect != null) { logEntry("[FieldAbility] " + source.name() + " — " + fa.effectText()); effect.accept(buildGameContext(effectIsP1)); }
				else logEntry("[FieldAbility] Unrecognized effect: " + fa.effectText());
				return;
			}
		} else { fixedCost = 0; }

		java.util.regex.Matcher maxM = FA_MAX_X.matcher(fa.effectText());
		int maxCp = isXCost ? (maxM.find() ? Integer.parseInt(maxM.group(1)) : Integer.MAX_VALUE) : fixedCost;

		// P1 gets a confirm dialog; AI auto-accepts.
		boolean p1GetsDialog = (fa.youMay() && isP1) || (fa.opponentMay() && !isP1);
		if (p1GetsDialog) {
			String prompt = (fa.youMay() ? "You may: " : "Your opponent may: ") + fa.effectText();
			int choice = JOptionPane.showOptionDialog(frame,
					source.name() + " — " + prompt,
					"Field Ability",
					JOptionPane.DEFAULT_OPTION,
					JOptionPane.PLAIN_MESSAGE,
					null,
					new Object[]{"OK", "Decline"},
					"OK");
			if (choice != 0) {
				logEntry("[FieldAbility] " + source.name() + " — optional effect declined");
				return;
			}
		} else if (fa.youMay() || fa.opponentMay()) {
			logEntry("[FieldAbility] [AI] auto-accepts optional ability");
		}

		if (!isP1) {
			// AI pays the maximum it can (simplified — no backup state update for AI).
			int paid = maxCp == Integer.MAX_VALUE ? 1 : maxCp;
			applyPayWhenDoSoEffect(subEffect, source, paid, effectIsP1);
			return;
		}

		String finalSubEffect = subEffect;
		showFieldAbilityPaymentDialog(source.name(), fixedCost, maxCp, isP1,
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
		java.util.function.Consumer<GameContext> effect = ActionResolver.parse(subEffect, source, xValue);
		if (effect == null) {
			logEntry("[FieldAbility] Unrecognized 'when you do so' effect: " + subEffect);
			return;
		}
		logEntry("[FieldAbility] " + source.name() + " — when you do so: " + subEffect + " (X=" + xValue + ")");
		effect.accept(ctx);
	}

	/**
	 * Payment dialog for a field ability that requires CP payment.
	 * Shows backup cards (1 CP each) and hand cards to discard (2 CP each).
	 * Calls {@code onConfirm} with total CP paid after dulling backups / discarding cards.
	 */
	private void showFieldAbilityPaymentDialog(String cardName, int minCp, int maxCp,
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
				lbl.setBorder(BorderFactory.createLineBorder(
						sel ? Color.YELLOW : (canAddBackup[0] ? Color.GRAY : new Color(80, 80, 80)), sel ? 3 : 1));
				lbl.setBackground(sel || canAddBackup[0] ? Color.DARK_GRAY : new Color(50, 50, 50));
				lbl.setCursor(sel || canAddBackup[0]
						? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			for (int i = 0; i < discardLbls.size(); i++) {
				JLabel  lbl = discardLbls.get(i);
				boolean sel = selectedDiscards.contains(discardIdxs.get(i));
				lbl.setBorder(BorderFactory.createLineBorder(
						sel ? Color.YELLOW : (canAddDiscard[0] ? Color.GRAY : new Color(80, 80, 80)), sel ? 3 : 1));
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
			logEntry("[FieldAbility] " + cardName + " — payment cancelled");
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
			logEntry("[FieldAbility] " + cardName + " — paid " + paid + " CP");
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

	private List<ForwardTarget> eligibleBzFieldCards(BreakZoneCost bz, boolean isP1) {
		List<ForwardTarget> result = new ArrayList<>();
		List<CardData> fwds = playerForwardCards(isP1);
		List<CardData> mons = playerMonsterCards(isP1);
		CardData[]     bkps = playerBackupCards(isP1);
		if (!bz.name().isEmpty()) {
			for (int i = 0; i < fwds.size(); i++)
				if (bz.name().equalsIgnoreCase(fwds.get(i).name()))
					result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.FORWARD));
			for (int i = 0; i < mons.size(); i++)
				if (bz.name().equalsIgnoreCase(mons.get(i).name()))
					result.add(new ForwardTarget(isP1, i, ForwardTarget.CardZone.MONSTER));
			for (int i = 0; i < bkps.length; i++)
				if (bkps[i] != null && bz.name().equalsIgnoreCase(bkps[i].name()))
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
			if (rfg.cardName() != null && !c.name().equalsIgnoreCase(rfg.cardName())) continue;
			if (rfg.element()  != null && !c.containsElement(rfg.element()))          continue;
			if (rfg.cardType() != null && !matchesDiscardType(c, rfg.cardType()))     continue;
			result.add(i);
		}
		return result;
	}

	private List<Integer> eligibleRfgBzIndices(RemoveFromGameCost rfg, boolean isP1) {
		List<CardData> bz = isP1 ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
		List<Integer> result = new ArrayList<>();
		for (int i = 0; i < bz.size(); i++) {
			CardData c = bz.get(i);
			if (rfg.cardName() != null && !c.name().equalsIgnoreCase(rfg.cardName())) continue;
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
		if (rfg.cardName()    != null && !c.name().equalsIgnoreCase(rfg.cardName()))  return false;
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
		if (rth.cardName()    != null && !c.name().equalsIgnoreCase(rth.cardName()))    return false;
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
			for (int pick = 0; pick < rth.count(); pick++) {
				List<ForwardTarget> eligible = eligibleRfthFieldTargets(rth, isP1);
				if (eligible.isEmpty()) { logEntry("No eligible field card for return-to-hand cost."); break; }
				String[] options = eligible.stream()
						.map(t -> fieldCardData(t).name() + " (" + t.zone().name().toLowerCase() + ")")
						.toArray(String[]::new);
				String label = "Return to hand (cost)" + (rth.count() > 1 ? " (" + (pick + 1) + "/" + rth.count() + ")" : "");
				String choice = (String) JOptionPane.showInputDialog(frame,
						"Choose a card to return to hand:", label,
						JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
				if (choice == null) break;
				int listIdx = java.util.Arrays.asList(options).indexOf(choice);
				if (listIdx < 0) break;
				returnTargetToHand(ctx, eligible.get(listIdx));
			}
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

	private String fieldCardUrl(ForwardTarget t) {
		if (t.isP1()) return switch (t.zone()) {
			case FORWARD -> p1ForwardUrls.get(t.idx());
			case BACKUP  -> p1BackupUrls[t.idx()];
			case MONSTER -> p1MonsterUrls.get(t.idx());
		};
		return switch (t.zone()) {
			case FORWARD -> p2ForwardUrls.get(t.idx());
			case BACKUP  -> p2BackupUrls[t.idx()];
			case MONSTER -> p2MonsterUrls.get(t.idx());
		};
	}

	private void breakP1BackupSlot(int idx) {
		CardData c = p1BackupCards[idx];
		if (c == null) return;
		logEntry(c.name() + " → Break Zone");
		gameState.getP1BreakZone().add(c);
		p1BackupCards[idx]   = null;
		p1BackupUrls[idx]    = null;
		p1BackupStates[idx]  = CardState.ACTIVE;
		p1BackupFrozen[idx]  = false;
		if (p1BackupLabels[idx] != null) {
			p1BackupLabels[idx].setIcon(null);
			p1BackupLabels[idx].setText(null);
		}
		refreshP1BreakLabel();
	}

	private void breakP1MonsterSlot(int idx) {
		if (idx >= p1MonsterCards.size()) return;
		CardData c = p1MonsterCards.get(idx);
		logEntry(c.name() + " → Break Zone");
		gameState.getP1BreakZone().add(c);
		p1MonsterCards.remove(idx);
		p1MonsterStates.remove(idx);
		p1MonsterFrozen.remove(idx);
		p1MonsterPlayedOnTurn.remove(idx);
		p1MonsterUrls.remove(idx);
		JLabel lbl = p1MonsterLabels.remove(idx);
		if (p1MonsterPanel != null) {
			p1MonsterPanel.remove(lbl);
			p1MonsterPanel.revalidate();
			p1MonsterPanel.repaint();
		}
		refreshP1BreakLabel();
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
					|| ability.whileCardBlocking() != null || ability.whilePartyAttacking();
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
		long genericNeeded   = rawCost.stream().filter(String::isEmpty).count();
		LinkedHashMap<String, Integer> costByElem = new LinkedHashMap<>();
		for (String e : rawCost) if (!e.isEmpty()) costByElem.merge(e, 1, Integer::sum);
		String[] elems  = costByElem.keySet().toArray(String[]::new);
		int      totalCost = rawCost.size();

		List<BreakZoneCost> bzCosts = ability.breakZoneCosts();

		// If zero CP cost and no X cost, confirm immediately (BZ cost is always resolved automatically)
		if (totalCost == 0 && !ability.hasXCost()) {
			executeAbilityPayment(ability, source, applyDull, new ArrayList<>(), new ArrayList<>(), autoResolveBzTargets(source, bzCosts, isP1), isP1, 0);
			return;
		}

		JDialog dlg = new JDialog(frame, "Activate: " + source.name(), true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		// Capture player-specific arrays once so lambdas below can close over them
		List<CardData> hand       = playerHand(isP1);
		CardData[]     bkpCards   = playerBackupCards(isP1);
		CardState[]    bkpStates  = playerBackupStates(isP1);
		String[]       bkpUrls    = playerBackupUrls(isP1);

		Map<String, Integer> bankCpByElem = new LinkedHashMap<>(costByElem);
		for (String k : bankCpByElem.keySet()) bankCpByElem.put(k, 0);

		List<Integer> selectedBackups  = new ArrayList<>();
		List<Integer> selectedDiscards = new ArrayList<>();

		List<Integer> eligibleBackupSlots = new ArrayList<>();
		for (int i = 0; i < bkpCards.length; i++) {
			if (bkpCards[i] != null && bkpCards[i] != source && bkpStates[i] == CardState.ACTIVE
					&& (genericNeeded > 0 || matchesAnyElement(bkpCards[i], elems)))
				eligibleBackupSlots.add(i);
		}

		JLabel cpLabel     = new JLabel();
		cpLabel.setFont(FontLoader.loadPixelNESFont(11));
		cpLabel.setHorizontalAlignment(SwingConstants.CENTER);

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));

		List<JLabel>  backupLbls  = new ArrayList<>();
		List<Integer> backupSlots = new ArrayList<>();
		List<JLabel>  discardLbls = new ArrayList<>();
		List<Integer> discardIdxs = new ArrayList<>();

		boolean[] canAddDiscard = {false};
		boolean[] canAddBackup  = {false};
		int[]     xValueHolder  = {0};
		Runnable updateAll = () -> {
			Map<String, Integer> cpByElem = new LinkedHashMap<>(bankCpByElem);
			int extraCp = 0;
			for (int slot : selectedBackups) {
				if (matchesAnyElement(bkpCards[slot], elems))
					cpByElem.merge(contributingElement(bkpCards[slot], elems, cpByElem, costByElem), 1, Integer::sum);
				else extraCp++;
			}
			for (int idx : selectedDiscards) {
				if (matchesAnyElement(hand.get(idx), elems))
					cpByElem.merge(contributingElement(hand.get(idx), elems, cpByElem, costByElem), 2, Integer::sum);
				else extraCp += 2;
			}
			int total       = cpByElem.values().stream().mapToInt(Integer::intValue).sum() + extraCp;
			int unsatisfied = (int) java.util.stream.IntStream.range(0, elems.length)
					.filter(ei -> cpByElem.getOrDefault(elems[ei], 0) < costByElem.get(elems[ei])).count();
			boolean satisfied = cpByElem.entrySet().stream()
					.allMatch(en -> en.getValue() >= costByElem.getOrDefault(en.getKey(), 0));

			if (ability.hasXCost()) {
				xValueHolder[0]  = Math.max(0, total - totalCost);
				canAddBackup[0]  = true;  // no upper cap — extra goes to X
				canAddDiscard[0] = true;
				confirmBtn.setEnabled(satisfied);
			} else {
				int maxAllowed   = totalCost + elems.length + (totalCost % 2);
				canAddBackup[0]  = total < totalCost;
				canAddDiscard[0] = (total + 2 <= maxAllowed) && (total < totalCost || unsatisfied > 0);
				confirmBtn.setEnabled(total >= totalCost && satisfied);
			}

			StringBuilder sb = new StringBuilder("CP: " + total + " / " + totalCost + "  (");
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
			if (ability.hasXCost()) {
				if (!first) sb.append(", ");
				sb.append("X = ").append(xValueHolder[0]);
				first = false;
			}
			if (first) sb.append("free");
			sb.append(")");
			cpLabel.setText(sb.toString());

			for (int i = 0; i < backupLbls.size(); i++) {
				JLabel lbl = backupLbls.get(i); boolean sel = selectedBackups.contains(backupSlots.get(i));
				lbl.setBorder(BorderFactory.createLineBorder(sel ? Color.YELLOW : (canAddBackup[0] ? Color.GRAY : new Color(80,80,80)), sel?3:1));
				lbl.setBackground(sel || canAddBackup[0] ? Color.DARK_GRAY : new Color(50,50,50));
				lbl.setCursor(sel || canAddBackup[0] ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			for (int i = 0; i < discardLbls.size(); i++) {
				JLabel lbl = discardLbls.get(i); boolean sel = selectedDiscards.contains(discardIdxs.get(i));
				lbl.setBorder(BorderFactory.createLineBorder(sel ? Color.YELLOW : (canAddDiscard[0] ? Color.GRAY : new Color(80,80,80)), sel?3:1));
				lbl.setBackground(sel || canAddDiscard[0] ? Color.DARK_GRAY : new Color(50,50,50));
				lbl.setCursor(sel || canAddDiscard[0] ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
		};
		updateAll.run();

		JPanel center = new JPanel();
		center.setLayout(new BoxLayout(center, BoxLayout.Y_AXIS));

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

		JLabel discHdr = new JLabel("Hand — discard for 2 CP each:");
		discHdr.setFont(FontLoader.loadPixelNESFont(9)); discHdr.setAlignmentX(Component.LEFT_ALIGNMENT);
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
		center.add(discHdr); center.add(dp);

		JButton cancelBtn = new JButton("Cancel");
		cancelBtn.setFont(FontLoader.loadPixelNESFont(11));
		cancelBtn.addActionListener(ev -> dlg.dispose());
		confirmBtn.addActionListener(ev -> {
			dlg.dispose();
			executeAbilityPayment(ability, source, applyDull,
					new ArrayList<>(selectedDiscards), new ArrayList<>(selectedBackups),
					autoResolveBzTargets(source, bzCosts, isP1), isP1, xValueHolder[0]);
		});

		JPanel buttonPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
		buttonPanel.add(confirmBtn); buttonPanel.add(cancelBtn);

		// Build cost summary for title
		StringBuilder costDesc = new StringBuilder();
		boolean cf = true;
		if (ability.requiresDull())    { costDesc.append("Dull"); cf = false; }
		if (ability.isSpecial())       { if (!cf) costDesc.append(" + "); costDesc.append("S (discard ").append(source.name()).append(")"); cf = false; }
		if (ability.hasXCost())        { if (!cf) costDesc.append(" + "); costDesc.append("X CP"); cf = false; }
		if (ability.crystalCost() > 0) { if (!cf) costDesc.append(" + "); costDesc.append(ability.crystalCost()).append(" Crystal"); cf = false; }
		for (Map.Entry<String, Integer> en : costByElem.entrySet()) {
			if (!cf) costDesc.append(" + ");
			costDesc.append(en.getValue()).append(" ").append(en.getKey()).append(" CP"); cf = false;
		}
		if (genericNeeded > 0) { if (!cf) costDesc.append(" + "); costDesc.append((int) genericNeeded).append(" any CP"); }
		for (BreakZoneCost bz : bzCosts) {
			if (!cf) costDesc.append(" + ");
			costDesc.append("put ");
			if (bz.name().isEmpty()) costDesc.append(bz.count()).append(' ').append(bz.cardType());
			else costDesc.append(bz.name());
			costDesc.append(" into BZ");
			cf = false;
		}
		for (RemoveFromGameCost rfg : ability.removeFromGameCosts()) {
			if (!cf) costDesc.append(" + ");
			costDesc.append("RFG ");
			if (rfg.cardName() != null) costDesc.append(rfg.cardName());
			else {
				costDesc.append(rfg.count() == -1 ? "all" : rfg.count());
				if (rfg.element()  != null) costDesc.append(' ').append(rfg.element());
				if (rfg.cardType() != null) costDesc.append(' ').append(rfg.cardType());
				else costDesc.append(" card");
			}
			costDesc.append(" (").append(rfg.zone().toLowerCase().replace('_', ' ')).append(')');
			cf = false;
		}
		for (ReturnToHandCost rth : ability.returnToHandCosts()) {
			if (!cf) costDesc.append(" + ");
			costDesc.append("RTH ");
			if (rth.cardName() != null) costDesc.append(rth.cardName());
			else {
				costDesc.append(rth.count());
				if (rth.category() != null) costDesc.append(" Cat.").append(rth.category());
				if (rth.cardType() != null) costDesc.append(' ').append(rth.cardType());
			}
			cf = false;
		}

		JLabel titleLabel = new JLabel(
				"<html><center>" + source.name() + " — " + (costDesc.length() > 0 ? costDesc : "free") + "</center></html>",
				SwingConstants.CENTER);
		titleLabel.setFont(FontLoader.loadPixelNESFont(11));

		JPanel topPanel = new JPanel(new BorderLayout(0, 4));
		topPanel.setBorder(BorderFactory.createEmptyBorder(8, 8, 4, 8));
		topPanel.add(titleLabel, BorderLayout.NORTH);
		topPanel.add(cpLabel,   BorderLayout.CENTER);

		JPanel mainPanel = new JPanel(new BorderLayout(0, 4));
		mainPanel.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));
		mainPanel.add(new JScrollPane(center), BorderLayout.CENTER);
		mainPanel.add(buttonPanel,             BorderLayout.SOUTH);

		dlg.getContentPane().setLayout(new BorderLayout());
		dlg.getContentPane().add(topPanel,  BorderLayout.NORTH);
		dlg.getContentPane().add(mainPanel, BorderLayout.CENTER);
		dlg.pack(); dlg.setLocationRelativeTo(frame); dlg.setVisible(true);
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
		if (ability.crystalCost() > 0) playerSpendCrystals(isP1, ability.crystalCost());

		// Mark once-per-turn ability as used for this turn
		if (ability.oncePerTurn())
			usedOncePerTurnAbilities.computeIfAbsent(source, k -> new java.util.HashSet<>()).add(ability.effectText());

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
			Set<String> usedTypes = new java.util.HashSet<>();
			for (int pick = 0; pick < dc.count(); pick++) {
				List<CardData> hand = playerHand(isP1);
				List<Integer> eligible = new ArrayList<>();
				for (int i = 0; i < hand.size(); i++) {
					CardData c = hand.get(i);
					if (dc.cardName()  != null && !c.name().equalsIgnoreCase(dc.cardName())) continue;
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

		logEntry("\"" + source.name() + "\" activated ability");

		gameState.pushStack(new StackEntry(source, ability, isP1, xValue));
		showStackWindow();
		refreshP1HandLabel();
		refreshP1BreakLabel();
	}

	private static boolean matchesDiscardType(CardData c, String cardType) {
		return switch (cardType.toLowerCase()) {
			case "summon"    -> c.isSummon();
			case "forward"   -> c.isForward();
			case "backup"    -> c.isBackup();
			case "monster"   -> c.isMonster();
			case "character" -> !c.isSummon();
			default          -> true;
		};
	}

	private static String discardTypeKey(CardData c) {
		if (c.isSummon())  return "summon";
		if (c.isForward()) return "forward";
		if (c.isBackup())  return "backup";
		if (c.isMonster()) return "monster";
		return "other";
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
					for (int pick = 0; pick < rfg.count(); pick++) {
						List<ForwardTarget> eligible = eligibleRfgFieldTargets(rfg, isP1);
						if (eligible.isEmpty()) { logEntry("No eligible field card for remove-from-game cost."); break; }
						String[] options = eligible.stream()
								.map(t -> fieldCardData(t).name() + " (" + t.zone().name().toLowerCase() + ")")
								.toArray(String[]::new);
						String label = "Remove from game (field)" + (rfg.count() > 1 ? " (" + (pick + 1) + "/" + rfg.count() + ")" : "");
						String choice = (String) JOptionPane.showInputDialog(frame,
								"Choose a card to remove from game:", label,
								JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
						if (choice == null) break;
						int listIdx = java.util.Arrays.asList(options).indexOf(choice);
						if (listIdx < 0) break;
						ctx.removeTargetFromGame(eligible.get(listIdx));
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
		return new GameContext() {
			@Override public void logEntry(String msg) { MainWindow.this.logEntry(msg); }

			@Override public int p1ForwardCount()                    { return p1ForwardCards.size(); }
			@Override public CardData p1Forward(int idx) {
				CardData top = p1ForwardPrimedTop.get(idx);
				return top != null ? top : p1ForwardCards.get(idx);
			}
			@Override public int       p1ForwardCurrentDamage(int idx) { return p1ForwardDamage.get(idx); }
			@Override public CardState p1ForwardState(int idx)          { return p1ForwardStates.get(idx); }
			@Override public void damageP1Forward(int idx, int amount) {
				applyDamageToForward(true, idx, amount, true);
			}

			@Override public int p2ForwardCount()                    { return p2ForwardCards.size(); }
			@Override public CardData p2Forward(int idx)             { return p2ForwardCards.get(idx); }
			@Override public int       p2ForwardCurrentDamage(int idx) { return p2ForwardDamage.get(idx); }
			@Override public CardState p2ForwardState(int idx)          { return p2ForwardStates.get(idx); }
			@Override public void damageP2Forward(int idx, int amount) {
				applyDamageToForward(false, idx, amount, true);
			}

			@Override public void shieldNextIncomingDamage(ForwardTarget t) {
				CardData c = fieldCardData(t); if (c != null) nextIncomingDmgZeroSet.add(c);
			}
			@Override public void shieldNextIncomingDamageReduction(ForwardTarget t, int reduction) {
				CardData c = fieldCardData(t); if (c != null) nextIncomingDmgReduceMap.merge(c, reduction, Integer::sum);
			}
			@Override public void debuffIncomingDamageIncrease(ForwardTarget t, int amount) {
				CardData c = fieldCardData(t); if (c != null) incomingDmgIncreaseMap.merge(c, amount, Integer::sum);
			}
			@Override public void shieldAbilityDamage(ForwardTarget t) {
				CardData c = fieldCardData(t); if (c != null) nullifyAbilityDmgSet.add(c);
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

			@Override
			public java.util.List<ForwardTarget> selectCharacters(
					int maxCount, boolean upTo, boolean opponentOnly,
					boolean selfOnly, String condition, String element,
					int costVal, String costCmp,
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons) {
				java.util.List<ForwardTarget> eligible = new ArrayList<>();
				if (!opponentOnly) {
					if (inclForwards) for (int i = 0; i < p1ForwardCards.size(); i++) {
						CardData card = p1Forward(i);
						if (element != null && !card.containsElement(element)) continue;
						if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
						if (!meetsJobFilter(card, jobFilter)) continue;
						if (!meetsCardNameFilter(card, cardNameFilter)) continue;
						if (!meetsCategoryFilter(card, categoryFilter)) continue;
						if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
						if (meetsTargetCondition(p1ForwardStates.get(i), p1ForwardDamage.get(i),
								p1AttackSelection.contains(i), false, condition))
							eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.FORWARD));
					}
					if (inclBackups) for (int i = 0; i < p1BackupCards.length; i++) {
						if (p1BackupCards[i] == null) continue;
						if (element != null && !p1BackupCards[i].containsElement(element)) continue;
						if (!meetsCostConstraint(p1BackupCards[i].cost(), costVal, costCmp)) continue;
						if (!meetsJobFilter(p1BackupCards[i], jobFilter)) continue;
						if (!meetsCardNameFilter(p1BackupCards[i], cardNameFilter)) continue;
						if (!meetsCategoryFilter(p1BackupCards[i], categoryFilter)) continue;
						if (excludeName != null && excludeName.equalsIgnoreCase(p1BackupCards[i].name())) continue;
						if (meetsTargetCondition(p1BackupStates[i], 0, false, false, condition))
							eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.BACKUP));
					}
					if (inclMonsters) for (int i = 0; i < p1MonsterCards.size(); i++) {
						CardData card = p1MonsterCards.get(i);
						if (element != null && !card.containsElement(element)) continue;
						if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
						if (!meetsJobFilter(card, jobFilter)) continue;
						if (!meetsCardNameFilter(card, cardNameFilter)) continue;
						if (!meetsCategoryFilter(card, categoryFilter)) continue;
						if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
						if (meetsTargetCondition(p1MonsterStates.get(i), 0, false, false, condition))
							eligible.add(new ForwardTarget(true, i, ForwardTarget.CardZone.MONSTER));
					}
				}
				if (!selfOnly) {
					if (inclForwards) for (int i = 0; i < p2ForwardCards.size(); i++) {
						CardData card = p2ForwardCards.get(i);
						if (element != null && !card.containsElement(element)) continue;
						if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
						if (!meetsJobFilter(card, jobFilter)) continue;
						if (!meetsCardNameFilter(card, cardNameFilter)) continue;
						if (!meetsCategoryFilter(card, categoryFilter)) continue;
						if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
						if (meetsTargetCondition(p2ForwardStates.get(i), p2ForwardDamage.get(i),
								false, false, condition))
							eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.FORWARD));
					}
					if (inclBackups) for (int i = 0; i < p2BackupCards.length; i++) {
						if (p2BackupCards[i] == null) continue;
						if (element != null && !p2BackupCards[i].containsElement(element)) continue;
						if (!meetsCostConstraint(p2BackupCards[i].cost(), costVal, costCmp)) continue;
						if (!meetsJobFilter(p2BackupCards[i], jobFilter)) continue;
						if (!meetsCardNameFilter(p2BackupCards[i], cardNameFilter)) continue;
						if (!meetsCategoryFilter(p2BackupCards[i], categoryFilter)) continue;
						if (excludeName != null && excludeName.equalsIgnoreCase(p2BackupCards[i].name())) continue;
						if (meetsTargetCondition(p2BackupStates[i], 0, false, false, condition))
							eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.BACKUP));
					}
					if (inclMonsters) for (int i = 0; i < p2MonsterCards.size(); i++) {
						CardData card = p2MonsterCards.get(i);
						if (element != null && !card.containsElement(element)) continue;
						if (!meetsCostConstraint(card.cost(), costVal, costCmp)) continue;
						if (!meetsJobFilter(card, jobFilter)) continue;
						if (!meetsCardNameFilter(card, cardNameFilter)) continue;
						if (!meetsCategoryFilter(card, categoryFilter)) continue;
						if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
						if (meetsTargetCondition(p2MonsterStates.get(i), 0, false, false, condition))
							eligible.add(new ForwardTarget(false, i, ForwardTarget.CardZone.MONSTER));
					}
				}
				String costLabel = costVal >= 0
						? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
				String title = "Choose " + (upTo ? "up to " : "") + maxCount
						+ (condition != null ? " " + condition : "")
						+ (element != null ? " " + element : "")
						+ " Character" + (maxCount != 1 ? "s" : "") + costLabel
						+ (opponentOnly ? " (opponent)" : selfOnly ? " (yours)" : "");
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
				Object[] options = { "Top", "Bottom" };
				int result = JOptionPane.showOptionDialog(frame,
						"Place " + cardName + " at the top or bottom of the deck?",
						"Choose Deck Position",
						JOptionPane.YES_NO_OPTION, JOptionPane.QUESTION_MESSAGE,
						null, options, options[0]);
				return result != 1;
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
					String categoryFilter, String elementFilter, String excludeName,
					String destination) {
				MainWindow.this.searchDeckForCard(inclForwards, inclBackups, inclMonsters, inclSummons,
						costVal, costCmp, cardNameFilter, jobFilter, categoryFilter, elementFilter, excludeName, destination);
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
					boolean inclForwards, boolean inclBackups, boolean inclMonsters,
					String jobFilter, String cardNameFilter, String categoryFilter, String excludeName, boolean inclSummons) {
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
					if (!meetsJobFilter(card, jobFilter)) continue;
					if (!meetsCardNameFilter(card, cardNameFilter)) continue;
					if (!meetsCategoryFilter(card, categoryFilter)) continue;
					if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
					ForwardTarget.CardZone cz = card.isBackup()  ? ForwardTarget.CardZone.BACKUP
					                         : card.isMonster() ? ForwardTarget.CardZone.MONSTER
					                         :                    ForwardTarget.CardZone.FORWARD;
					eligible.add(new ForwardTarget(!opponentZone, i, cz));
				}
				String costLabel = costVal >= 0
						? " of cost " + costVal + (costCmp != null ? " or " + costCmp : "") : "";
				String title = "Choose " + (upTo ? "up to " : "") + maxCount
						+ (element != null ? " " + element : "")
						+ " Character" + (maxCount != 1 ? "s" : "") + costLabel
						+ " in " + (opponentZone ? "opponent's" : "your") + " Break Zone";
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
				java.util.List<CardData>  bz   = gameState.getP2BreakZone();
				for (int i = 0; i < count && !deck.isEmpty(); i++) {
					CardData card = deck.pop();
					bz.add(card);
					logEntry("[P2] Mill: \"" + card.name() + "\" → Break Zone");
				}
				refreshP2BreakLabel();
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

				JButton okBtn = new JButton("OK");
				okBtn.setFont(FontLoader.loadPixelNESFont(11));
				okBtn.addActionListener(ae -> { hideZoom(); dlg.dispose(); });

				JPanel south = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 6));
				south.add(okBtn);
				south.setBorder(BorderFactory.createEmptyBorder(0, 8, 8, 8));

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
								gameState.getP1BreakZone().add(card);
								logEntry(card.name() + " put into Break Zone from reveal");
								refreshP1BreakLabel();
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
					String excludeName, boolean entersDull) {
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
					if (excludeName != null && excludeName.equalsIgnoreCase(card.name())) continue;
					eligible.add(i);
				}
				if (eligible.isEmpty()) {
					logEntry("No eligible cards in hand to play.");
					return;
				}
				String[] options = eligible.stream()
						.map(i -> hand.get(i).name() + " (Cost: " + hand.get(i).cost() + ")")
						.toArray(String[]::new);
				String choice = (String) JOptionPane.showInputDialog(frame,
						"Choose a card to play onto the field:", "Play from Hand",
						JOptionPane.PLAIN_MESSAGE, null, options, options[0]);
				if (choice == null) return;
				int listIdx = java.util.Arrays.asList(options).indexOf(choice);
				if (listIdx < 0) return;
				int handIdx = eligible.get(listIdx);
				CardData card = hand.remove(handIdx);
				logEntry(card.name() + " played from hand onto field" + (entersDull ? " (dull)" : ""));
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

			@Override public void playTargetOntoField(ForwardTarget t) {
				java.util.List<CardData> bz = t.isP1() ? gameState.getP1BreakZone() : gameState.getP2BreakZone();
				if (t.idx() >= bz.size()) return;
				CardData card = bz.remove(t.idx());
				String src = t.isP1() ? "Break Zone" : "opponent's Break Zone";
				logEntry(card.name() + " played from " + src + " onto field");
				if (card.isBackup())       placeCardInFirstBackupSlot(card);
				else if (card.isMonster()) placeCardInMonsterZone(card);
				else                       placeCardInForwardZone(card);
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
						return p1MonsterCards.get(i).power();
				for (int i = 0; i < p2MonsterCards.size(); i++)
					if (p2MonsterCards.get(i).name().equalsIgnoreCase(cardName))
						return p2MonsterCards.get(i).power();
				logEntry("[ActionResolver] fieldForwardPowerByName: \"" + cardName + "\" not found on field");
				return -1;
			}

			@Override public int effectiveTargetPower(ForwardTarget t) {
				if (t.zone() == ForwardTarget.CardZone.BACKUP) return 0;
				if (t.zone() == ForwardTarget.CardZone.FORWARD)
					return t.isP1()
							? (t.idx() < p1ForwardCards.size() ? effectiveP1ForwardPower(t.idx()) : 0)
							: (t.idx() < p2ForwardCards.size() ? effectiveP2ForwardPower(t.idx()) : 0);
				return t.isP1()
						? (t.idx() < p1MonsterCards.size() ? p1MonsterCards.get(t.idx()).power() : 0)
						: (t.idx() < p2MonsterCards.size() ? p2MonsterCards.get(t.idx()).power() : 0);
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
					showForcedDiscardDialog(count);
				}
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
					showForcedDiscardDialog(count);
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
					String element, int costVal, String costCmp, int excludeCostVal) {
				if (!opponentOnly) {
					if (forwards) {
						for (int i = p1ForwardCards.size() - 1; i >= 0; i--) {
							CardData c = p1Forward(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							switch (action) {
								case BREAK          -> breakP1Forward(i);
								case DULL           -> dullP1Forward(i);
								case FREEZE         -> freezeP1Forward(i);
								case DULL_AND_FREEZE -> { dullP1Forward(i); freezeP1Forward(i); }
								case ACTIVATE       -> { p1ForwardStates.set(i, CardState.ACTIVE); refreshP1ForwardSlot(i); }
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
							switch (action) {
								case BREAK -> {
									logEntry(c.name() + " is broken");
									gameState.getP1BreakZone().add(c);
									p1BackupCards[i] = null;
									p1BackupStates[i] = CardState.ACTIVE;
									refreshP1BackupSlot(i);
									refreshP1BreakLabel();
								}
								case DULL           -> { p1BackupStates[i] = CardState.DULL;   logEntry(c.name() + " is dulled");          refreshP1BackupSlot(i); }
								case FREEZE         -> { p1BackupFrozen[i] = true;              logEntry(c.name() + " is frozen");          refreshP1BackupSlot(i); }
								case DULL_AND_FREEZE -> { p1BackupStates[i] = CardState.DULL; p1BackupFrozen[i] = true; logEntry(c.name() + " is dulled & frozen"); refreshP1BackupSlot(i); }
								case ACTIVATE       -> { p1BackupStates[i] = CardState.ACTIVE; logEntry(c.name() + " is activated");       refreshP1BackupSlot(i); }
							}
						}
					}
					if (monsters) {
						for (int i = p1MonsterCards.size() - 1; i >= 0; i--) {
							CardData c = p1MonsterCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							switch (action) {
								case BREAK -> {
									logEntry(c.name() + " is broken");
									gameState.getP1BreakZone().add(c);
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
							}
						}
					}
				}
				if (!selfOnly) {
					if (forwards) {
						for (int i = p2ForwardCards.size() - 1; i >= 0; i--) {
							CardData c = p2ForwardCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
							if (excludeCostVal >= 0 && c.cost() == excludeCostVal) continue;
							switch (action) {
								case BREAK          -> breakP2Forward(i);
								case DULL           -> dullP2Forward(i);
								case FREEZE         -> freezeP2Forward(i);
								case DULL_AND_FREEZE -> { dullP2Forward(i); freezeP2Forward(i); }
								case ACTIVATE       -> { p2ForwardStates.set(i, CardState.ACTIVE); refreshP2ForwardSlot(i); }
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
							switch (action) {
								case BREAK -> {
									logEntry("[P2] " + c.name() + " is broken");
									gameState.getP2BreakZone().add(c);
									p2BackupCards[i] = null;
									p2BackupStates[i] = CardState.ACTIVE;
									refreshP2BackupSlot(i);
									refreshP2BreakLabel();
								}
								case DULL           -> { p2BackupStates[i] = CardState.DULL;   logEntry("[P2] " + c.name() + " is dulled");          refreshP2BackupSlot(i); }
								case FREEZE         -> { p2BackupFrozen[i] = true;              logEntry("[P2] " + c.name() + " is frozen");          refreshP2BackupSlot(i); }
								case DULL_AND_FREEZE -> { p2BackupStates[i] = CardState.DULL; p2BackupFrozen[i] = true; logEntry("[P2] " + c.name() + " is dulled & frozen"); refreshP2BackupSlot(i); }
								case ACTIVATE       -> { p2BackupStates[i] = CardState.ACTIVE; logEntry("[P2] " + c.name() + " is activated");       refreshP2BackupSlot(i); }
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
									gameState.getP2BreakZone().add(c);
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
							}
						}
					}
				}
			}

			@Override
			public void applyMassFieldPowerBoost(int amount, boolean inclForwards, boolean inclMonsters,
					boolean opponentOnly, boolean selfOnly,
					String element, int costVal, String costCmp) {
				java.util.EnumSet<CardData.Trait> noTraits = java.util.EnumSet.noneOf(CardData.Trait.class);
				if (!opponentOnly) {
					if (inclForwards) {
						for (int i = 0; i < p1ForwardCards.size(); i++) {
							CardData c = p1Forward(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
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
							logEntry(c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
				if (!selfOnly) {
					if (inclForwards) {
						for (int i = 0; i < p2ForwardCards.size(); i++) {
							CardData c = p2ForwardCards.get(i);
							if (element != null && !c.containsElement(element)) continue;
							if (!meetsCostConstraint(c.cost(), costVal, costCmp)) continue;
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
							logEntry("[P2] " + c.name() + " gains +" + amount + " power until end of turn");
						}
					}
				}
			}

			@Override public void addEndOfTurnEffect(java.util.function.Consumer<GameContext> effect) {
				endOfTurnEffects.add(effect);
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

			@Override public int p1DamageCount() { return gameState.getP1DamageZone().size(); }

			@Override public int opponentHandSize() {
				return (isP1 ? gameState.getP2Hand() : gameState.getP1Hand()).size();
			}

			@Override public int countP1FieldCards(boolean inclForwards, boolean inclBackups,
					boolean inclMonsters, String jobFilter, String cardNameFilter) {
				int count = 0;
				if (inclForwards) for (CardData c : p1ForwardCards) {
					if (!meetsJobFilter(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					count++;
				}
				if (inclBackups) for (CardData c : p1BackupCards) {
					if (c == null) continue;
					if (!meetsJobFilter(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
					count++;
				}
				if (inclMonsters) for (CardData c : p1MonsterCards) {
					if (!meetsJobFilter(c, jobFilter)) continue;
					if (!meetsCardNameFilter(c, cardNameFilter)) continue;
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
		};
	}

	/** Fires all queued end-of-turn effects using a context for {@code isP1}, then clears the queue. */
	private void fireEndOfTurnEffects(boolean isP1) {
		if (endOfTurnEffects.isEmpty()) return;
		List<java.util.function.Consumer<GameContext>> pending = new ArrayList<>(endOfTurnEffects);
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
	 */
	private int modifyIncomingDamage(boolean isP1, int idx, int rawAmount, boolean fromAbility) {
		List<CardData> fwds = isP1 ? p1ForwardCards : p2ForwardCards;
		if (idx >= fwds.size()) return rawAmount;
		CardData card = fwds.get(idx);
		int amount = rawAmount;

		// Incoming damage increase (debuff)
		if (incomingDmgIncreaseMap.containsKey(card))
			amount += incomingDmgIncreaseMap.get(card);

		// Nullify damage from opponent's abilities/summons (does not block combat)
		if (fromAbility && nullifyAbilityDmgSet.contains(card)) return 0;

		// One-time: next incoming damage = 0
		if (nextIncomingDmgZeroSet.remove(card)) return 0;

		// One-time: next incoming damage reduced by N
		if (nextIncomingDmgReduceMap.containsKey(card))
			amount = Math.max(0, amount - nextIncomingDmgReduceMap.remove(card));

		// Global per-player damage reduction
		int globalRed = isP1 ? p1GlobalDmgReduction : p2GlobalDmgReduction;
		if (globalRed > 0) amount = Math.max(0, amount - globalRed);

		// Non-lethal protection: damage < forward's effective power → becomes 0
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
	private void applyDamageToForward(boolean isP1, int idx, int rawAmount, boolean fromAbility) {
		List<CardData>  fwds   = isP1 ? p1ForwardCards   : p2ForwardCards;
		List<Integer>   dmgList = isP1 ? p1ForwardDamage  : p2ForwardDamage;
		if (idx >= fwds.size()) return;
		int amount = modifyIncomingDamage(isP1, idx, rawAmount, fromAbility);
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
			if (isP1) breakP1Forward(idx); else breakP2Forward(idx);
		} else {
			if (isP1) refreshP1ForwardSlot(idx); else refreshP2ForwardSlot(idx);
		}
	}

	// -------------------------------------------------------------------------
	// Forward target selection helpers (used by GameContext.selectForwards)
	// -------------------------------------------------------------------------

	private static boolean meetsTargetCondition(CardState state, int damage,
			boolean isAttacking, boolean isBlocking, String condition) {
		if (condition == null) return true;
		return switch (condition.toLowerCase()) {
			case "active"    -> state == CardState.ACTIVE;
			case "dull"      -> state == CardState.DULL;
			case "damaged"   -> damage > 0;
			case "attacking" -> isAttacking;
			case "blocking"  -> isBlocking;
			default          -> true;
		};
	}

	private static boolean meetsCostConstraint(int cardCost, int costVal, String costCmp) {
		if (costVal < 0) return true;
		if (costCmp == null)            return cardCost == costVal;
		return switch (costCmp.toLowerCase()) {
			case "less" -> cardCost <= costVal;
			case "more" -> cardCost >= costVal;
			default     -> cardCost == costVal;
		};
	}

	/** Returns {@code true} if the card's job matches any job in the bar-separated {@code jobFilter}, or if the filter is {@code null}. */
	private static boolean meetsJobFilter(CardData card, String jobFilter) {
		if (jobFilter == null) return true;
		for (String j : jobFilter.split("\\|")) {
			if (j.trim().equalsIgnoreCase(card.job())) return true;
		}
		return false;
	}

	/** Returns {@code true} if the card's name matches {@code cardNameFilter} (case-insensitive), or if the filter is {@code null}. */
	private static boolean meetsCardNameFilter(CardData card, String cardNameFilter) {
		return cardNameFilter == null || cardNameFilter.equalsIgnoreCase(card.name());
	}

	/** Returns {@code true} if the card belongs to {@code categoryFilter} (case-insensitive contains), or if the filter is {@code null}. */
	private static boolean meetsCategoryFilter(CardData card, String categoryFilter) {
		if (categoryFilter == null) return true;
		String cf = categoryFilter.trim().toLowerCase();
		return card.category1().toLowerCase().contains(cf)
			|| card.category2().toLowerCase().contains(cf);
	}

	/** Returns {@code true} if the card contains at least one element from the bar-separated {@code elementFilter}, or if the filter is {@code null}. */
	private static boolean meetsElementFilter(CardData card, String elementFilter) {
		if (elementFilter == null) return true;
		for (String e : elementFilter.split("\\|")) {
			if (card.containsElement(e.trim())) return true;
		}
		return false;
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

		JDialog dlg = new JDialog(frame, title, true);
		dlg.setResizable(false);
		dlg.setDefaultCloseOperation(JDialog.DISPOSE_ON_CLOSE);

		java.util.List<ForwardTarget> chosen = new ArrayList<>();
		java.util.Set<Integer> sel = new java.util.LinkedHashSet<>();

		JPanel btnsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
		JButton[] btns = new JButton[eligible.size()];

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setEnabled(upTo); // enabled immediately for "up to", otherwise waits for maxCount

		for (int i = 0; i < eligible.size(); i++) {
			ForwardTarget target = eligible.get(i);
			int tIdx = target.idx();
			CardData card = switch (target.zone()) {
				case BACKUP  -> target.isP1() ? p1BackupCards[tIdx] : p2BackupCards[tIdx];
				case MONSTER -> target.isP1() ? p1MonsterCards.get(tIdx) : p2MonsterCards.get(tIdx);
				default      -> target.isP1()
						? (p1ForwardPrimedTop.get(tIdx) != null ? p1ForwardPrimedTop.get(tIdx) : p1ForwardCards.get(tIdx))
						: p2ForwardCards.get(tIdx);
			};
			String typeTag = switch (target.zone()) {
				case BACKUP  -> "BK";
				case MONSTER -> "MON";
				default      -> "FWD";
			};
			final int fi = i;
			JButton btn = new JButton("<html><center>"
					+ (target.isP1() ? "[You " : "[P2 ") + typeTag + "] "
					+ card.name() + "<br>(" + card.power() + ")</center></html>");
			btn.setFont(FontLoader.loadPixelNESFont(9));
			btn.setPreferredSize(new Dimension(130, 56));
			btn.addActionListener(ae -> {
				if (sel.contains(fi)) {
					sel.remove(fi);
					btn.setBackground(null);
				} else {
					if (sel.size() >= maxCount) return;
					sel.add(fi);
					btn.setBackground(Color.YELLOW);
					if (!upTo && sel.size() == maxCount) {
						for (int si : sel) chosen.add(eligible.get(si));
						dlg.dispose();
						return;
					}
				}
				confirmBtn.setEnabled(upTo || sel.size() == maxCount);
			});
			btns[i] = btn;
			btnsPanel.add(btn);
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
		dlg.getContentPane().add(hdr,      BorderLayout.NORTH);
		dlg.getContentPane().add(btnsPanel, BorderLayout.CENTER);
		dlg.getContentPane().add(south,     BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
		return java.util.List.copyOf(chosen);
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

		JPanel btnsPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));

		JButton confirmBtn = new JButton("Confirm");
		confirmBtn.setFont(FontLoader.loadPixelNESFont(11));
		confirmBtn.setEnabled(upTo);

		for (int i = 0; i < eligible.size(); i++) {
			ForwardTarget target = eligible.get(i);
			CardData card = zone.get(target.idx());
			final int fi = i;
			JButton btn = new JButton("<html><center>"
					+ (target.isP1() ? "[Your BZ] " : "[P2 BZ] ")
					+ card.name() + "<br>(" + card.power() + ")</center></html>");
			btn.setFont(FontLoader.loadPixelNESFont(9));
			btn.setPreferredSize(new Dimension(130, 56));
			btn.addActionListener(ae -> {
				if (sel.contains(fi)) {
					sel.remove(fi);
					btn.setBackground(null);
				} else {
					if (sel.size() >= maxCount) return;
					sel.add(fi);
					btn.setBackground(Color.YELLOW);
					if (!upTo && sel.size() == maxCount) {
						for (int si : sel) chosen.add(eligible.get(si));
						dlg.dispose();
						return;
					}
				}
				confirmBtn.setEnabled(upTo || sel.size() == maxCount);
			});
			btnsPanel.add(btn);
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
		dlg.getContentPane().add(hdr,       BorderLayout.NORTH);
		dlg.getContentPane().add(btnsPanel,  BorderLayout.CENTER);
		dlg.getContentPane().add(south,      BorderLayout.SOUTH);
		dlg.pack();
		dlg.setLocationRelativeTo(frame);
		dlg.setVisible(true);
		return java.util.List.copyOf(chosen);
	}

	// -------------------------------------------------------------------------

	private void showBackupContextMenu(int idx, JLabel slot, MouseEvent e) {
		JPopupMenu menu = new JPopupMenu();

		CardData card = p1BackupCards[idx];
		if (card != null) {
			addAbilityMenuItems(menu, card, p1BackupFrozen[idx], p1BackupStates[idx], p1BackupPlayedOnTurn[idx],
					() -> { p1BackupStates[idx] = CardState.DULL; animateDullBackup(idx, true); }, true);
		}

		if (AppSettings.isDebugMode()) {
			if (menu.getComponentCount() > 0) menu.addSeparator();
			JMenuItem dullItem = new JMenuItem("Debug: Dull");
			dullItem.addActionListener(ae -> {
				boolean dulling = p1BackupStates[idx] != CardState.DULL;
				p1BackupStates[idx] = dulling ? CardState.DULL : CardState.ACTIVE;
				animateDullBackup(idx, dulling);
			});
			menu.add(dullItem);

			JMenuItem freezeItem = new JMenuItem("Debug: Freeze");
			freezeItem.addActionListener(ae -> {
				p1BackupFrozen[idx] = !p1BackupFrozen[idx];
				refreshP1BackupSlot(idx);
			});
			menu.add(freezeItem);
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
					toggleAttackSelection(idx);
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
		p1ForwardStates.add(CardState.ACTIVE);
		p1ForwardPlayedOnTurn.add(gameState.getTurnNumber());
		p1ForwardDamage.add(0);
		p1ForwardPowerBoost.add(0);
		p1ForwardPowerReduction.add(0);
		p1ForwardTempTraits.add(java.util.EnumSet.noneOf(CardData.Trait.class));
		p1ForwardRemovedTraits.add(java.util.EnumSet.noneOf(CardData.Trait.class));
		p1ForwardPrimedTop.add(null);
		p1ForwardFrozen.add(false);
		p1ForwardLabels.add(lbl);

		p1ForwardPanel.add(lbl);
		p1ForwardPanel.revalidate();
		p1ForwardPanel.repaint();

		refreshP1ForwardSlot(idx);
		triggerFieldAbilitiesForEntersField(card, true);
	}

	/** Adds a Monster card to P1's monster zone (right side of forward zone, newest leftmost). */
	private void placeCardInMonsterZone(CardData card) {
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
				if (lbl.getIcon() != null) showMonsterContextMenu(idx, lbl, e);
			}
			@Override public void mouseEntered(MouseEvent e) {
				if (lbl.getIcon() != null) showZoomAt(p1MonsterUrls.get(idx));
			}
			@Override public void mouseExited(MouseEvent e) { hideZoom(); }
		});

		p1MonsterUrls.add(card.imageUrl());
		p1MonsterCards.add(card);
		p1MonsterStates.add(CardState.ACTIVE);
		p1MonsterPlayedOnTurn.add(gameState.getTurnNumber());
		p1MonsterFrozen.add(false);
		p1MonsterLabels.add(lbl);

		// Insert at front so newest monster appears leftmost
		p1MonsterPanel.add(lbl, 0);
		p1MonsterPanel.revalidate();
		p1MonsterPanel.repaint();

		refreshP1MonsterSlot(idx);
	}

	/** Reloads and re-renders a single P1 monster slot using its stored URL and state. */
	private void refreshP1MonsterSlot(int idx) {
		String url   = p1MonsterUrls.get(idx);
		CardState state = p1MonsterStates.get(idx);
		JLabel slot  = p1MonsterLabels.get(idx);
		if (url == null) return;
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage card = CardAnimation.toARGB(raw, CARD_W, CARD_H);
				return new ImageIcon(CardAnimation.renderBackupCard(card, state, false, false, p1MonsterFrozen.get(idx)));
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
		if (slot.getIcon() == null) slot.setIcon(new ImageIcon(CardAnimation.renderPlaceholder(state)));
		new SwingWorker<ImageIcon, Void>() {
			@Override protected ImageIcon doInBackground() throws Exception {
				Image raw = ImageCache.load(url);
				if (raw == null) return new ImageIcon(CardAnimation.renderPlaceholder(state));
				BufferedImage card = CardAnimation.toARGB(raw, CARD_W, CARD_H);
				return new ImageIcon(CardAnimation.renderBackupCard(card, state, false, false, p2MonsterFrozen.get(idx)));
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
		JPopupMenu menu = new JPopupMenu();

		// Action abilities
		addAbilityMenuItems(menu, p1MonsterCards.get(idx), p1MonsterFrozen.get(idx),
				p1MonsterStates.get(idx), p1MonsterPlayedOnTurn.get(idx),
				() -> { p1MonsterStates.set(idx, CardState.DULL); refreshP1MonsterSlot(idx); }, true);

		if (AppSettings.isDebugMode()) {
			JMenuItem dullItem = new JMenuItem("Debug: Dull");
			dullItem.addActionListener(ae -> {
				p1MonsterStates.set(idx,
						p1MonsterStates.get(idx) == CardState.DULL ? CardState.ACTIVE : CardState.DULL);
				refreshP1MonsterSlot(idx);
			});
			menu.add(dullItem);

			JMenuItem freezeItem = new JMenuItem("Debug: Freeze");
			freezeItem.addActionListener(ae -> {
				p1MonsterFrozen.set(idx, !p1MonsterFrozen.get(idx));
				refreshP1MonsterSlot(idx);
			});
			menu.add(freezeItem);
		}

		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
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
				&& state == CardState.ACTIVE
				&& !p1ForwardCannotAttack.contains(idx)
				&& !p1ForwardCannotAttackPersistent.contains(idx)
				&& (hasHaste || p1ForwardPlayedOnTurn.get(idx) != gameState.getTurnNumber());
		int damage    = p1ForwardDamage.get(idx);
		int power     = effectiveP1ForwardPower(idx);
		int basePower = (topCard != null ? topCard : p1ForwardCards.get(idx)).power();
		boolean selected = p1AttackSelection.contains(idx);
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
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(80, 220, 80));
				} else if (power < basePower) {
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(230, 200, 60));
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
		if (idx < 0 || idx >= p1ForwardStates.size()) return false;
		if (p1ForwardStates.get(idx) != CardState.ACTIVE) return false;
		if (p1ForwardCannotAttack.contains(idx)) return false;
		if (p1ForwardCannotAttackPersistent.contains(idx)) return false;
		return effectiveP1HasTrait(idx, CardData.Trait.HASTE)
				|| p1ForwardPlayedOnTurn.get(idx) != gameState.getTurnNumber();
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

	private void refreshAttackButton() {
		if (attackButton == null) return;
		boolean inAttack = gameState.getCurrentPhase() == GameState.GamePhase.ATTACK
				&& gameState.getCurrentPlayer() == GameState.Player.P1;
		int n = p1AttackSelection.size();
		attackButton.setEnabled(inAttack && n > 0);
		attackButton.setText(n > 1 ? "Party Attack" : "Attack");
	}

	private void executeP1Attack(List<Integer> selection) {
		if (selection.isEmpty()) return;
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
			triggerFieldAbilitiesForAttack(p1ForwardCards.get(idx), true);
		if (selection.size() == 1) {
			int idx = selection.get(0);
			CardData attacker = p1ForwardCards.get(idx);
			logEntry(attacker.name() + " attacks!");
			p2OfferBlock(attacker, idx);
		} else {
			int combinedPower = 0;
			StringBuilder names = new StringBuilder();
			for (int idx : selection) {
				combinedPower += effectiveP1ForwardPower(idx);
				if (names.length() > 0) names.append(", ");
				names.append(p1ForwardCards.get(idx).name());
			}
			logEntry("Party Attack! " + names + " (" + combinedPower + " combined)");
			triggerFieldAbilitiesForPartyAttack(true);
			p2OfferBlockParty(selection, combinedPower);
		}
	}

	private void p2OfferBlockParty(List<Integer> attackerIndices, int combinedPower) {
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
			p2AiDistributeDamage(attackerIndices, blockerPower);
		} else {
			p2TakeDamage();
		}
	}

	private void p2AiDistributeDamage(List<Integer> attackerIndices, int blockerPower) {
		if (attackerIndices.isEmpty() || blockerPower <= 0) return;
		List<int[]> targets = new ArrayList<>();
		for (int idx : attackerIndices) {
			if (idx < p1ForwardCards.size()) {
				int hp = effectiveP1ForwardPower(idx) - p1ForwardDamage.get(idx);
				targets.add(new int[]{ idx, hp });
			}
		}
		if (targets.isEmpty()) return;
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
		if (remaining > 0) {
			int lastIdx = targets.get(targets.size() - 1)[0];
			damageMap.merge(lastIdx, remaining, Integer::sum);
		}

		for (Map.Entry<Integer, Integer> entry : damageMap.entrySet()) {
			int idx = entry.getKey(), dmg = entry.getValue();
			p1ForwardDamage.set(idx, p1ForwardDamage.get(idx) + dmg);
			logEntry("[P2] Deals " + dmg + " damage to " + p1ForwardCards.get(idx).name());
		}

		List<Integer> toBreak = new ArrayList<>();
		for (int idx : damageMap.keySet()) {
			if (p1ForwardDamage.get(idx) >= effectiveP1ForwardPower(idx)) toBreak.add(idx);
		}
		toBreak.sort(Collections.reverseOrder());
		for (int idx : toBreak) breakP1Forward(idx);

		for (int i = 0; i < p1ForwardCards.size(); i++) refreshP1ForwardSlot(i);
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
					&& (effectiveP1HasTrait(i, CardData.Trait.HASTE)
					    || p1ForwardPlayedOnTurn.get(i) != turn))
				return true;
		}
		return false;
	}

	/** Shows a context menu for a P1 forward slot. */
	private void showForwardContextMenu(int idx, JLabel slot, MouseEvent e) {
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
			primeItem.setEnabled(isMainPhase && !alreadyPrimed && canAffordPrimingCost(fwd));
			primeItem.addActionListener(ae -> showPrimingPaymentDialog(fwd, idx));
			menu.add(primeItem);
		}

		if (AppSettings.isDebugMode()) {
			JMenuItem dullItem = new JMenuItem("Debug: Dull");
			dullItem.addActionListener(ae -> {
				boolean dulling = p1ForwardStates.get(idx) != CardState.DULL;
				p1ForwardStates.set(idx, dulling ? CardState.DULL : CardState.ACTIVE);
				if (dulling) animateDullForward(idx, null);
				else refreshP1ForwardSlot(idx);
			});
			menu.add(dullItem);

			JMenuItem freezeItem = new JMenuItem("Debug: Freeze");
			freezeItem.addActionListener(ae -> {
				p1ForwardFrozen.set(idx, !p1ForwardFrozen.get(idx));
				refreshP1ForwardSlot(idx);
			});
			menu.add(freezeItem);
		}

		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	private void showP2BackupContextMenu(int idx, JLabel slot, MouseEvent e) {
		JPopupMenu menu = new JPopupMenu();
		CardData card = p2BackupCards[idx];
		if (card != null) {
			addAbilityMenuItems(menu, card, p2BackupFrozen[idx], p2BackupStates[idx], 0,
					() -> { p2BackupStates[idx] = CardState.DULL; animateDullP2Backup(idx, true); }, false);
		}
		if (AppSettings.isDebugMode()) {
			if (menu.getComponentCount() > 0) menu.addSeparator();
			JMenuItem dullItem = new JMenuItem("Debug: Dull");
			dullItem.addActionListener(ae -> {
				boolean dulling = p2BackupStates[idx] != CardState.DULL;
				p2BackupStates[idx] = dulling ? CardState.DULL : CardState.ACTIVE;
				animateDullP2Backup(idx, dulling);
			});
			menu.add(dullItem);
			JMenuItem freezeItem = new JMenuItem("Debug: Freeze");
			freezeItem.addActionListener(ae -> {
				p2BackupFrozen[idx] = !p2BackupFrozen[idx];
				refreshP2BackupSlot(idx);
			});
			menu.add(freezeItem);
		}
		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	private void showP2MonsterContextMenu(int idx, JLabel slot, MouseEvent e) {
		JPopupMenu menu = new JPopupMenu();
		addAbilityMenuItems(menu, p2MonsterCards.get(idx), p2MonsterFrozen.get(idx),
				p2MonsterStates.get(idx), p2MonsterPlayedOnTurn.get(idx),
				() -> { p2MonsterStates.set(idx, CardState.DULL); refreshP2MonsterSlot(idx); }, false);
		if (AppSettings.isDebugMode()) {
			JMenuItem dullItem = new JMenuItem("Debug: Dull");
			dullItem.addActionListener(ae -> {
				p2MonsterStates.set(idx,
						p2MonsterStates.get(idx) == CardState.DULL ? CardState.ACTIVE : CardState.DULL);
				refreshP2MonsterSlot(idx);
			});
			menu.add(dullItem);
			JMenuItem freezeItem = new JMenuItem("Debug: Freeze");
			freezeItem.addActionListener(ae -> {
				p2MonsterFrozen.set(idx, !p2MonsterFrozen.get(idx));
				refreshP2MonsterSlot(idx);
			});
			menu.add(freezeItem);
		}
		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
	}

	private void showP2ForwardContextMenu(int idx, JLabel slot, MouseEvent e) {
		JPopupMenu menu = new JPopupMenu();
		addAbilityMenuItems(menu, p2ForwardCards.get(idx), p2ForwardFrozen.get(idx),
				p2ForwardStates.get(idx), p2ForwardPlayedOnTurn.get(idx),
				() -> { p2ForwardStates.set(idx, CardState.DULL); refreshP2ForwardSlot(idx); }, false);
		if (AppSettings.isDebugMode()) {
			JMenuItem dullItem = new JMenuItem("Debug: Dull");
			dullItem.addActionListener(ae -> {
				boolean dulling = p2ForwardStates.get(idx) != CardState.DULL;
				p2ForwardStates.set(idx, dulling ? CardState.DULL : CardState.ACTIVE);
				refreshP2ForwardSlot(idx);
			});
			menu.add(dullItem);
			JMenuItem freezeItem = new JMenuItem("Debug: Freeze");
			freezeItem.addActionListener(ae -> {
				p2ForwardFrozen.set(idx, !p2ForwardFrozen.get(idx));
				refreshP2ForwardSlot(idx);
			});
			menu.add(freezeItem);
		}
		if (menu.getComponentCount() > 0) menu.show(slot, e.getX(), e.getY());
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
				lbl.setBorder(BorderFactory.createLineBorder(sel ? Color.YELLOW : (canAddBackup ? Color.GRAY : new Color(80,80,80)), sel ? 3 : 1));
				lbl.setBackground(sel || canAddBackup ? Color.DARK_GRAY : new Color(50,50,50));
				lbl.setCursor(sel || canAddBackup ? Cursor.getPredefinedCursor(Cursor.HAND_CURSOR) : Cursor.getDefaultCursor());
			}
			for (int i = 0; i < discardLbls.size(); i++) {
				JLabel lbl = discardLbls.get(i); boolean sel = selectedDiscards.contains(discardIdxs.get(i));
				lbl.setBorder(BorderFactory.createLineBorder(sel ? Color.YELLOW : (canAddDiscard[0] ? Color.GRAY : new Color(80,80,80)), sel ? 3 : 1));
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
					lbl.setBorder(BorderFactory.createLineBorder(Color.YELLOW, 3));
				}
				@Override public void mouseExited(MouseEvent e) {
					hideZoom();
					lbl.setBorder(BorderFactory.createLineBorder(
							selection[0].equals(candidate) ? Color.YELLOW : Color.GRAY, 2));
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
						boolean gameActive = gameState.getCurrentPhase() != null;
						if (gameActive && !gameState.getP1MainDeck().isEmpty() && AppSettings.isDebugMode()) {
							JMenuItem dmgItem = new JMenuItem("Debug: Take 1 Damage");
							dmgItem.addActionListener(ae -> p1TakeDamage());
							menu.add(dmgItem);
						}
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
			p2DamageSlotPanel = slotsPanel;
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
				if (lbl.getIcon() != null) showZoomAt(p2ForwardUrls.get(idx));
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
		p2ForwardFrozen.add(false);
		p2ForwardLabels.add(lbl);

		p2ForwardPanel.add(lbl);
		p2ForwardPanel.revalidate();
		p2ForwardPanel.repaint();

		refreshP2ForwardSlot(idx);
		triggerFieldAbilitiesForEntersField(card, false);
	}

	private void placeP2CardInFirstBackupSlot(CardData card) {
		for (int i = 0; i < p2BackupCards.length; i++) {
			if (p2BackupCards[i] != null) continue;
			p2BackupUrls[i]   = card.imageUrl();
			p2BackupCards[i]  = card;
			p2BackupStates[i] = CardState.DULL;
			refreshP2BackupSlot(i);
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
		String url      = p2ForwardUrls.get(idx);
		CardState state = p2ForwardStates.get(idx);
		JLabel slot     = p2ForwardLabels.get(idx);
		if (url == null) return;
		int damage    = p2ForwardDamage.get(idx);
		int power     = effectiveP2ForwardPower(idx);
		int basePower = p2ForwardCards.get(idx).power();
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
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(80, 220, 80));
				} else if (power < basePower) {
					CardAnimation.renderPowerOverlayRight(canvas, power, new Color(230, 200, 60));
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
			logEntry("[P2] Main Phase 1");
			step(() -> doMainPhase(() -> {
				gameState.advancePhase(); // MAIN_1 → ATTACK
				boolean canAttack = false;
				for (int i = 0; i < p2ForwardStates.size(); i++) {
					if (p2ForwardCanAttack(i)) { canAttack = true; break; }
				}
				if (!canAttack) {
					logEntry("[P2] Attack Phase — No attackers, skipping");
					gameState.advancePhase(); // ATTACK → MAIN_2
					logEntry("[P2] Main Phase 2");
					step(() -> doMainPhase(this::doEndPhase));
				} else {
					logEntry("[P2] Attack Phase");
					refreshAllP2ForwardSlots();
					step(() -> doAttackPhase(() -> {
						gameState.advancePhase(); // ATTACK → MAIN_2
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
				if (card.isForward())      placeP2CardInForwardZone(card);
				else if (card.isBackup())  placeP2CardInFirstBackupSlot(card);
				else if (card.isMonster()) placeP2CardInMonsterZone(card);
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
				if (toPlay.isForward())      placeP2CardInForwardZone(toPlay);
				else if (toPlay.isBackup())  placeP2CardInFirstBackupSlot(toPlay);
				else if (toPlay.isMonster()) placeP2CardInMonsterZone(toPlay);
			}
			step(() -> doMainPhase(onDone));
		}

		// ── Attack Phase ─────────────────────────────────────────────────────

		private void doAttackPhase(Runnable onDone) {
			if (gameState.isP1GameOver()) return;
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
				triggerFieldAbilitiesForAttack(attacker, false);
				final int fi = i;
				p1ChooseBlockerDialog(attacker, fi, () -> {
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
			p1ForwardCannotBlock.clear();           p2ForwardCannotBlock.clear();
			p1ForwardMustBlock.clear();             p2ForwardMustBlock.clear();
			p1ForwardCannotAttack.clear();          p2ForwardCannotAttack.clear();
			p1ForwardMustAttack.clear();            p2ForwardMustAttack.clear();
			p2ForwardCannotAttackPersistent.clear(); p2ForwardCannotBlockPersistent.clear();
			nextIncomingDmgZeroSet.clear();   nextIncomingDmgReduceMap.clear();
			incomingDmgIncreaseMap.clear();   nullifyAbilityDmgSet.clear();
			nextOutgoingDmgZeroSet.clear();
			p1NonLethalProtection = false;    p2NonLethalProtection = false;
			p1GlobalDmgReduction  = 0;        p2GlobalDmgReduction  = 0;
			gameState.advancePhase(); // MAIN_2 → END
			logEntry("[P2] End Phase");
			gameState.advancePhase(); // END → ACTIVE (switches to P1, increments turn)
			step(this::startP1Turn);  // startP1Turn expects phase == ACTIVE
		}

		// ── P1 turn start (Active + Draw, then hand control back to player) ──

		private void startP1Turn() {
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
