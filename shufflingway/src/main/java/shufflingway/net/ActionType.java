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
                    //            "backups": [slot, ...], "backupElements": { "slot": "Fire" },
                    //            "alt": { "crystals": n, "dull": [slot, ...],
                    //                     "removeBackups": [slot, ...],
                    //                     "putToBz": [{ "idx": n, "zone": "FORWARD" }, ...],
                    //                     "bzRemovals": [idx, ...] },
                    //            "extra": { "type": "BZ_REMOVE", "crystals": n, "x": n,
                    //                       "bzRemovals": [idx, ...],
                    //                       "handDiscards": [idx, ...] } }
                    //   Indices address zones both clients hold in the same order.
                    //   "alt" is present only for a cast under one of the card's alternate costs,
                    //   and carries what that cost handed over — none of which the indices above
                    //   account for. Its presence is itself the signal: Golbez 17-140S hands
                    //   nothing over and still sends an empty object, because the receiver has to
                    //   know the cost was taken to arm the drawback paying for it. What is not a
                    //   choice (the reduced CP, the drawback itself) is read off the card at both
                    //   ends rather than sent.
                    //   "extra" is present only for a cast that paid the card's optional
                    //   surcharge, and says what that surcharge took. Its CP half is not here:
                    //   a fixed or 《X》 amount is charged through the ordinary payment dialog, so
                    //   the Backups and discards covering it are in "discards"/"backups" like any
                    //   other payment, and only the size of it ("x") travels, for the effects that
                    //   read it back. Its indices address the hand and Break Zone as they stood
                    //   when the surcharge was chosen, before the play spent anything.
                    //   "alt" and "extra" are separate offers on separate menu items, so at most
                    //   one of the two is ever present.
    LB_PLAY,        // payload: { "lbIdx": n, "card": "...", "payment": [lbIdx, ...],
                    //            "discards": [idx, ...], "backups": [slot, ...],
                    //            "backupBreaks": { "slot": "Fire" } }
                    //   A card played out of the sender's LB deck. "lbIdx" and "payment" index
                    //   that deck, which both clients load in the same order at setup and never
                    //   shuffle, so they need no flip — the same reason hand and slot indices do
                    //   not. Separate from PLAY_CARD because nothing leaves a hand: the played
                    //   card and the cards paying for it are turned face up where they sit.
    WARP_PLAY,      // payload: { "handIdx": n, "card": "...", "discards": [idx, ...],
                    //            "backups": [slot, ...], "backupElements": { "slot": "Fire" },
                    //            "backupBreaks": { "slot": "Fire" } }
                    //   A card played from hand to the sender's Removed-From-Play zone with its
                    //   Warp counters. Its own action rather than a PLAY_CARD because nothing
                    //   reaches the field: the card leaves the hand for the Warp zone, and only
                    //   arrives turns later when its last counter comes off. That arrival needs
                    //   no action of its own — both clients tick the counters at the start of
                    //   Main Phase 1, which ADVANCE_PHASE already replicates, so the card enters
                    //   on both boards from the same count without being told to.
                    //   No "alt" or "extra": nothing in the corpus prints a Warp cost alongside
                    //   an alternate cost or a surcharge, and they are separate menu items.
    ACTIVATE_ABILITY, // payload: { "zone": "FORWARD"|"BACKUP"|"MONSTER", "idx": n, "card": "...",
                    //             "ability": n, "discards": [idx, ...], "backups": [slot, ...],
                    //             "bzTargets": [{ "idx": n, "zone": "FORWARD" }, ...],
                    //             "x": n, "sCost": n, "backupBreaks": { "slot": "Fire" } }
                    //   An action ability the sender activated off one of their own field cards.
                    //   "zone"/"idx" locate that card on their side, which is the receiver's P2;
                    //   "ability" indexes the card's printed action abilities, which both clients
                    //   parse from the same text. What the ability costs is not sent — both ends
                    //   read it off the card and apply the same board-derived discounts — only
                    //   what was handed over to pay it.
                    //   Abilities that are not printed on the card (granted ones, and the
                    //   Petrification removal) carry no index and are not replicated yet.
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
