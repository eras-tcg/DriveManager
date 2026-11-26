package com.drive.drive_manager.simulator.engine;

import com.drive.drive_manager.simulator.room.CardSlot;
import com.drive.drive_manager.simulator.room.GameRoom;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;

import static com.drive.drive_manager.simulator.engine.TestTable.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rules bugs reported from real games (2026-11-26): losing the last life with effects waiting, a second copy of an
 * "En Juego" once per turn between copies, and the order of cost modifiers on a summon by effect.
 */
@Tag("engine")
class ReportedRulesBugsTest extends CardBatchSupport {

    @BeforeEach
    void noConfirmations() {
        room.setConfirmMandatory(false);
    }

    /** Answers each prompt with its first valid card (or nothing when none is valid) until nothing is asked. */
    private void answerFirst() {
        for (int i = 0; i < 20 && room.getPrompt() != null; i++) {
            Prompt p = room.getPrompt();
            List<String> pick = p.getValidIds().isEmpty() ? List.of() : List.of(p.getValidIds().get(0));
            assertThat(Answers.answer(room, p.getPlayerId(), p.getId(), pick, List.of())).as(p.getInstruction()).isNull();
        }
    }

    private void oneLifeLeft(CardSlot life) {
        guest.getLifeStack().clear();
        life.setFaceDown(true);
        guest.getLifeStack().add(life);
    }

    // ------------------------------------------------------------------ 1. The last life

    @Test
    void theLastLifeNotReinforcedLosesAtOnceBeforeWhatItTriggers() {
        CardSlot life = card("gl", "Vida Fuerte", 1, 1, "R");
        life.setStrength(9);
        oneLifeLeft(life);
        // "Si alguna carta es removida de tus vidas: puedes invocar esta carta": it would be offered before the loss
        guest.getHand().add(card("groll", "Groll Vandálico", 1, 1, "R"));
        onField(host, card("a", "Atacante", 1, 1, "R")).setStrength(5);
        assertThat(CombatRules.attackByPlayer(room, "host", "a", null)).isNull();

        Prompt reinforce = room.getPrompt();
        assertThat(reinforce.getPurpose()).isEqualTo("REINFORCE");
        assertThat(Answers.answer(room, "guest", reinforce.getId(), List.of(), List.of())).isNull();

        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);
        assertThat(room.getWinnerId()).isEqualTo("host");
        assertThat(room.getPrompt()).isNull();
    }

    @Test
    void theLastLifeReinforcedKeepsTheGameGoing() {
        CardSlot life = card("gl", "Vida Fuerte", 1, 1, "R");
        life.setStrength(9);
        oneLifeLeft(life);
        onField(host, card("a", "Atacante", 1, 1, "R")).setStrength(5);
        assertThat(CombatRules.attackByPlayer(room, "host", "a", null)).isNull();
        Prompt reinforce = room.getPrompt();
        assertThat(Answers.answer(room, "guest", reinforce.getId(), List.of("gl"), List.of())).isNull();
        drain();
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.IN_PROGRESS);
        assertThat(guest.getLifeStack()).containsExactly(life);
    }

    @Test
    void aUtilityAsTheLastLifeIsNotPlayedTheGameIsLost() {
        CardSlot life = utility("gl", "Utilidad Vida", 4, "W");
        oneLifeLeft(life);
        onField(host, card("a", "Atacante", 1, 1, "R")).setStrength(5);
        assertThat(CombatRules.attackByPlayer(room, "host", "a", null)).isNull();

        assertThat(room.getPrompt()).isNull();
        assertThat(room.getStatus()).isEqualTo(GameRoom.RoomStatus.POST_GAME);
        assertThat(room.getWinnerId()).isEqualTo("host");
        assertThat(guest.getField()).doesNotContain(life);
    }

    @Test
    void aUtilityThatIsNotTheLastLifeMayStillBePlayed() {
        CardSlot life = utility("gu", "Utilidad Vida", 4, "W");
        life.setFaceDown(true);
        guest.getLifeStack().add(0, life);
        onField(host, card("a", "Atacante", 1, 1, "R")).setStrength(5);
        assertThat(CombatRules.attackByPlayer(room, "host", "a", null)).isNull();
        assertThat(room.getPrompt().getPurpose()).isEqualTo("LIFE_PLAY");
    }

    // ------------------------------------------------------------------ 2. "En Juego" once per turn between copies

    @Test
    void aSecondCopyWhoseEnJuegoWasAlreadyUsedGoesToTheDescarte() {
        // "(1) Elige 1 aliado Rojo nivel 3 o menor; reactívalo." (una vez por turno entre copias)
        CardSlot ally = onField(host, card("al", "Aliado Rojo", 1, 3, "R"));
        ally.setTapped(true);
        CardSlot first = utility("f1", "Fervor de Guerra", 1, "R");
        CardSlot second = utility("f2", "Fervor de Guerra", 1, "R");
        host.getHand().addAll(List.of(first, second));
        // Both played at the same moment (as both copies' "Al entrar al paso de bloqueo" do)
        Summons.play(room, host, first, 0, null, null, null);
        Summons.play(room, host, second, 0, null, null, null);
        Chain.advance(room);
        answerFirst();

        assertThat(room.getChain()).isEmpty();
        assertThat(room.getTriggerQueue()).isEmpty();
        assertThat(ally.isTapped()).isFalse();
        assertThat(host.getField()).containsExactly(ally);
        assertThat(host.getDiscardPile()).contains(first, second);
    }

    // ------------------------------------------------------------------ 4. Cost modifiers on a summon by effect

    @Test
    void abrazaAlmasSummonedByConvocadorDeSombrasWithNothingOnTheFieldCostsNothing() {
        // "En Destrucción: puedes tributar 1 carta de tu mano para invocar 1 criatura nivel 2 de tus tributos reduciendo su coste en 1."
        onField(host, withClasses(card("cs", "Convocador de Sombras", 3, 2, "P"), "espectro"));
        // Cost 2, "si no controlas cartas, esta carta cuesta 1 gema"; level 2 for an [espectro]'s effects
        CardSlot abraza = withClasses(card("aa", "Abraza Almas", 2, 1, "P"), "espectro");
        abraza.setFaceDown(false);
        host.getTributeZone().add(abraza);
        host.getHand().add(card("x", "Para Tributar", 1, 1, "R"));
        room.setSharedCounter(-1); // host: 1 gem
        assertThat(Removals.destroyByPlayer(room, "host", "host", "cs")).isNull();
        answerFirst();

        assertThat(host.getField()).containsExactly(abraza);
        assertThat(gems()).isEqualTo(1);
    }
}
