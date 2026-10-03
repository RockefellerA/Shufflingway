package shufflingway.graphics;

import java.awt.Image;

import javax.swing.ImageIcon;

import shufflingway.CardState;

/**
 * A field slot's rendered card, tagged with the state it was drawn in. The slot canvas is laid
 * out differently for an ACTIVE and a DULL card, and anything drawn against it afterwards — the
 * {@link ActionButton}s — has to follow the canvas actually on screen rather than the card's
 * state, which changes before the re-render lands.
 *
 * <p>Any other icon on a field slot (a rotation frame, an entry animation) is a plain
 * {@link ImageIcon}, which is how a slot knows not to draw its buttons over it.
 */
public class FieldCardIcon extends ImageIcon {

	private final CardState state;

	public FieldCardIcon(Image image, CardState state) {
		super(image);
		this.state = state;
	}

	/** The state this canvas was laid out for. */
	public CardState state() {
		return state;
	}
}
