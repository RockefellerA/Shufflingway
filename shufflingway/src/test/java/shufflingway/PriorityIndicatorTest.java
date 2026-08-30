package shufflingway;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Set;

import javax.swing.JButton;

import org.junit.jupiter.api.Test;

/**
 * The priority indicator agrees with the Next button.
 *
 * <p>{@code refreshPhaseTracker} paints priority from the turn owner alone, which is right at a
 * phase boundary and wrong at a priority window. Attack Preparation on P2's turn is where the two
 * disagreed: {@code offerP1AttackPrepPriority} calls {@code refreshPhaseTracker} — turning the
 * indicator red for P2 — and then hands P1 the Next button to pass with. The board said P2 held
 * priority while offering P1 the control that only its holder has. Every other checkpoint on P2's
 * turn goes through {@code opponentPriority}, which flips the indicator itself, which is why this
 * one step was the only one that showed it.
 */
public class PriorityIndicatorTest {

	private static Object get(Object target, Class<?> owner, String name) throws Exception {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		return f.get(target);
	}

	private static void set(Object target, Class<?> owner, String name, Object v) throws Exception {
		Field f = owner.getDeclaredField(name);
		f.setAccessible(true);
		f.set(target, v);
	}

	/** A Summon in hand is the cheapest thing that makes a priority window worth opening. */
	private static CardData summon() {
		return new CardData(null, "Shiva", "Ice", 2, 0, "Summon", false, 0, false, false,
				Set.of(), 0, List.of(), null, List.of(),
				List.of(), List.of(), List.of(), List.of(), List.of(), List.of(), List.of(),
				List.of(), List.of(), List.of(),
				false, false, null, false, false, false, false, false, 1,
				null, null, null, "");
	}

	@Test
	void attackPreparationOnP2sTurnHandsTheIndicatorToP1WithTheNextButton() throws Exception {
		MainWindow mw = new MainWindow();

		// Put the board on P2's turn, in the Attack phase, with something P1 could actually do —
		// otherwise the window passes itself and priority never rests with P1 at all.
		set(mw.gameState, GameState.class, "currentPlayer", GameState.Player.P2);
		set(mw.gameState, GameState.class, "currentPhase", GameState.GamePhase.ATTACK);
		mw.gameState.getP1Hand().add(summon());

		mw.offerP1AttackPrepPriority(() -> { });

		JButton next = (JButton) get(mw, MainWindow.class, "nextPhaseButton");
		PhaseTracker tracker = (PhaseTracker) get(mw, MainWindow.class, "phaseTracker");
		boolean indicatorSaysP1 = (Boolean) get(tracker, PhaseTracker.class, "hasPriority");

		assertTrue(next.isEnabled(), "P1 is being offered the pass control, so the window is open");
		assertTrue(indicatorSaysP1,
				"and the indicator has to agree — it was still showing P2's priority while the "
				+ "Next button invited P1 to pass it");
	}

	@Test
	void passingItBackTakesTheIndicatorWithIt() throws Exception {
		MainWindow mw = new MainWindow();
		set(mw.gameState, GameState.class, "currentPlayer", GameState.Player.P2);
		set(mw.gameState, GameState.class, "currentPhase", GameState.GamePhase.ATTACK);
		mw.gameState.getP1Hand().add(summon());

		boolean[] passed = { false };
		mw.offerP1AttackPrepPriority(() -> passed[0] = true);
		mw.onNextPhase();   // P1 clicks Next to pass

		PhaseTracker tracker = (PhaseTracker) get(mw, MainWindow.class, "phaseTracker");
		assertTrue(passed[0], "the pass should have run the continuation");
		assertFalse((Boolean) get(tracker, PhaseTracker.class, "hasPriority"),
				"priority has left P1, so the indicator goes back to the turn player");
	}
}
