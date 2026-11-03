package com.drive.drive_manager.config;

import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.MongoTemplate;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SessionCheckTest {

    private final MongoTemplate mongo = mock(MongoTemplate.class);

    private SessionCheck at(long millis) {
        return new SessionCheck(mongo, Clock.fixed(Instant.ofEpochMilli(millis), ZoneOffset.UTC));
    }

    @Test
    void aTokenWithTheAccountsCurrentVersionStands() {
        when(mongo.findById("u1", Document.class, "users")).thenReturn(new Document("tokenVersion", 3));
        assertThat(at(0).isCurrent("u1", 3)).isTrue();
        assertThat(at(0).isCurrent("u1", 2)).isFalse();
    }

    @Test
    void aDeletedAccountsTokenDoesNotStand() {
        assertThat(at(0).isCurrent("gone", 0)).isFalse();
        assertThat(at(0).isCurrent(null, 0)).isFalse();
    }

    @Test
    void anAccountWithoutAVersionMatchesATokenWithoutOne() {
        when(mongo.findById("old", Document.class, "users")).thenReturn(new Document("username", "old"));
        assertThat(at(0).isCurrent("old", null)).isTrue();
        assertThat(at(0).isCurrent("old", 0)).isTrue();
    }

    @Test
    void theAccountIsReadAgainOnlyAfterAWhile() {
        when(mongo.findById("u1", Document.class, "users")).thenReturn(new Document("tokenVersion", 1));
        Clock[] now = {Clock.fixed(Instant.ofEpochMilli(0), ZoneOffset.UTC)};
        SessionCheck check = new SessionCheck(mongo, new Clock() {
            @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
            @Override public Clock withZone(java.time.ZoneId zone) { return this; }
            @Override public Instant instant() { return now[0].instant(); }
        });
        check.isCurrent("u1", 1);
        check.isCurrent("u1", 1);
        verify(mongo, times(1)).findById("u1", Document.class, "users");

        // The password changed: the old token stops working once the account is read again
        when(mongo.findById("u1", Document.class, "users")).thenReturn(new Document("tokenVersion", 2));
        now[0] = Clock.fixed(Instant.ofEpochMilli(SessionCheck.TTL_MS + 1), ZoneOffset.UTC);
        assertThat(check.isCurrent("u1", 1)).isFalse();
    }
}
