package shufflingway.net;

public enum ActionType {

    // ── Handshake ─────────────────────────────────────────────────────────────
    HELLO,          // payload: { "version": "1.0.0", "cardChecksum": "abc123..." }
    READY,          // payload: {} — both sides ready, game can begin

    // ── Lobby / match setup ───────────────────────────────────────────────────
    DECK_LIST,      // payload: { "deckName": "...", "serials": ["1-001H", ...] }
                    //   The sender's own deck, expanded one entry per copy and ordered by
                    //   serial — the same order getDeckCardsDetailed produces locally.
    GAME_SETUP,     // payload: { "seed": <long>, "hostGoesFirst": <bool> }
                    //   Host-authored. The seed drives both decks' shuffles on both clients.
    STATE_CHECKSUM, // payload: { "label": "...", "checksum": "..." }
                    //   Desync detection: a hash of state both clients must agree on.

    // ── Opening hand ──────────────────────────────────────────────────────────
    KEEP_HAND,      // payload: { "order": [cardIdx, ...] }
    MULLIGAN,       // payload: { "bottomOrder": [cardIdx, ...] }

    // ── Turn flow ─────────────────────────────────────────────────────────────
    ADVANCE_PHASE,  // payload: {}

    // ── Card actions ──────────────────────────────────────────────────────────
    PLAY_CARD,      // payload: { "handIdx": n, "card": "...", "discards": [idx, ...],
                    //            "backups": [slot, ...], "backupElements": { "slot": "Fire" } }
                    //   Indices address zones both clients hold in the same order.
    LB_PLAY,        // payload: { "lbIdx": n, "card": "...", "payment": [lbIdx, ...],
                    //            "discards": [idx, ...], "backups": [slot, ...],
                    //            "backupBreaks": { "slot": "Fire" } }
                    //   A card played out of the sender's LB deck. "lbIdx" and "payment" index
                    //   that deck, which both clients load in the same order at setup and never
                    //   shuffle, so they need no flip — the same reason hand and slot indices do
                    //   not. Separate from PLAY_CARD because nothing leaves a hand: the played
                    //   card and the cards paying for it are turned face up where they sit.
    DISCARD_HAND,   // payload: { "indices": [idx, ...] } — a discard with no CP, e.g. the
                    //   end-phase trim to five. Replicated because it renumbers the hand.
    ATTACK,         // payload: { "zone": "FORWARD"|"MONSTER"|"BACKUP", "indices": [n, ...],
                    //            "power": n }
                    //   The sender's own attackers, so the side flips on arrival; the slot indices
                    //   do not, both clients holding each zone in the same order. More than one
                    //   index is a party attack, which is always FORWARD. "power" is the sender's
                    //   effective total, carried only so the receiver can cross-check it.
    BLOCK,          // payload: { "blocked": bool, "zone": "...", "idx": n,
                    //            "damage": { "<attackerIdx>": amount } }
                    //   The answer to an ATTACK. "damage" is the blocker's spread across a blocked
                    //   party and is absent otherwise. When "blocked" is false the rest is absent.
    CHOICE,         // payload: { "kind": "<ChoiceKind>", "indices": [n, ...] }
                    //   One player's answer to a decision the other is parked on while an effect
                    //   resolves. See ChoiceKind for what each kind's integers index, and which
                    //   of them flip sides on arrival: hand and slot indices do not, both clients
                    //   holding each zone in the same order, while a field code names a side.
    PRIORITY_OFFER, // payload: {}
                    //   The sender has passed priority at a phase transition and is holding the
                    //   phase open while the receiver responds. The receiver answers with a
                    //   CHOICE of kind PRIORITY_PASS, and only then does ADVANCE_PHASE follow.
                    //   It has to precede the advance rather than ride on it: a response happens
                    //   in the phase being left, so a receiver told after the fact would be acting
                    //   in the wrong one. Combat needs no equivalent — both clients run a matching
                    //   priority round there and each already knows when to hold.
    RESOLVE_STACK,  // payload: {}

    // ── Utility ───────────────────────────────────────────────────────────────
    PING,           // payload: {} — keep-alive
    CHAT,           // payload: { "message": "..." }
    DISCONNECT      // payload: { "reason": "..." }
}
