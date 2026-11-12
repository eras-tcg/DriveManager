package com.drive.drive_manager.simulator.room;

import com.drive.drive_manager.dto.Card;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CardNameCheckTest {

    private static Card card(String name, String edition, String sub, String color, int number) {
        Card c = new Card();
        c.setCardName(name);
        c.setEdition(edition);
        c.setSubEdition(sub);
        c.setColorIdentity(color);
        c.setCardNumber(number);
        return c;
    }

    @Test
    void listsTheNamesWrittenOtherwiseThanTheirCardFile() {
        CardNameCheck.Report report = CardNameCheck.check(List.of(
                card("VS 1", "E1", "2", "B", 5),          // the file is "vs-1": a name to fix
                card("Roto-G0", "E2", null, "B", 17),     // as the file
                card("Deriduragon", "E1", null, "G", 10), // without its accent
                card("Sin Programar", "E1", null, "W", 1) // no file: not this check's business (the unprogrammed warning is)
        ), List.of("vs-1", "roto-g0", "deriduragón", "falis"));

        assertThat(report.namesToFix()).containsExactly(
                new CardNameCheck.NameToFix("E1", "E1-G10", "Deriduragon", "deriduragón"),
                new CardNameCheck.NameToFix("E1", "E1.2-B05", "VS 1", "vs-1"));
        // A card file no metadata card names is never played
        assertThat(report.scriptsWithoutCard()).containsExactly("falis");
    }

    @Test
    void reportsNothingWhenEveryNameIsAsItsFile() {
        CardNameCheck.Report report = CardNameCheck.check(List.of(card("SAR-2", "E1", null, "B", 9)), List.of("sar-2"));
        assertThat(report.namesToFix()).isEmpty();
        assertThat(report.scriptsWithoutCard()).isEmpty();
    }
}
