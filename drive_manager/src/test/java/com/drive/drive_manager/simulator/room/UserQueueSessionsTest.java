package com.drive.drive_manager.simulator.room;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.messaging.Message;
import org.springframework.messaging.simp.SimpMessageHeaderAccessor;
import org.springframework.messaging.simp.SimpMessageType;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.messaging.simp.broker.OrderedMessageChannelDecorator;
import org.springframework.messaging.simp.broker.SimpleBrokerMessageHandler;
import org.springframework.messaging.simp.user.DefaultUserDestinationResolver;
import org.springframework.messaging.simp.user.SimpSession;
import org.springframework.messaging.simp.user.SimpUser;
import org.springframework.messaging.simp.user.SimpUserRegistry;
import org.springframework.messaging.simp.user.UserDestinationMessageHandler;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.ImmutableMessageChannelInterceptor;
import org.springframework.messaging.support.MessageBuilder;

import java.security.Principal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A player with the site open in two tabs has two STOMP sessions. With {@code setPreserveReceiveOrder(true)} Spring
 * (7.0.5) sends a user message to each session through an ordered channel that needs a mutable message, but it hands
 * the same message to every session and the first send makes it immutable: every session after the first gets
 * "Expected mutable SimpMessageHeaderAccessor" and nothing. The channels are wired as
 * WebSocketMessageBrokerConfigurationSupport / AbstractMessageBrokerConfiguration do (no docker, no server).
 */
class UserQueueSessionsTest {

    private static final String USER = "alice";
    private static final String QUEUE = "/queue/room/r1";

    private final List<Message<?>> delivered = new ArrayList<>();
    private SimpMessagingTemplate template;
    private SimpUserRegistry registry;

    @BeforeEach
    void wire() {
        ExecutorSubscribableChannel inbound = new ExecutorSubscribableChannel();
        ExecutorSubscribableChannel outbound = new ExecutorSubscribableChannel();
        ExecutorSubscribableChannel broker = new ExecutorSubscribableChannel();
        broker.addInterceptor(new ImmutableMessageChannelInterceptor());
        // registry.setPreserveReceiveOrder(true)
        OrderedMessageChannelDecorator.configureInterceptor(inbound, true);
        outbound.subscribe(delivered::add);

        registry = new TwoTabs(USER, "s1", "s2");
        SimpleBrokerMessageHandler simpleBroker = new SimpleBrokerMessageHandler(inbound, outbound, broker, List.of("/queue"));
        // config.setPreservePublishOrder(true)
        simpleBroker.setPreservePublishOrder(true);
        simpleBroker.start();
        UserDestinationMessageHandler users = new UserDestinationMessageHandler(inbound, broker,
                new DefaultUserDestinationResolver(registry));
        users.start();

        for (String session : List.of("s1", "s2")) {
            simpleBroker.handleMessage(control(SimpMessageType.CONNECT, session, null));
            // What /user/queue/room/r1 becomes for that session (DefaultUserDestinationResolver)
            simpleBroker.handleMessage(control(SimpMessageType.SUBSCRIBE, session, QUEUE + "-user" + session));
        }
        template = new SimpMessagingTemplate(broker);
    }

    @Test
    void springAloneLosesTheSecondTab() {
        template.convertAndSendToUser(USER, QUEUE, "update 1");
        template.convertAndSendToUser(USER, QUEUE, "update 2");
        // 4 expected (2 updates x 2 tabs): one tab gets nothing
        assertEquals(2, messages().size(), "Spring 7.0.5 behavior changed: " + messagesBySession());
    }

    @Test
    void sendingPerSessionReachesEveryTabInOrder() {
        RoomMessages.toUser(template, registry, USER, QUEUE, "update 1");
        RoomMessages.toUser(template, registry, USER, QUEUE, "update 2");
        assertEquals(Map.of("s1", List.of("update 1", "update 2"), "s2", List.of("update 1", "update 2")),
                messagesBySession());
    }

    @Test
    void aUserWhoIsNotConnectedGetsNothing() {
        RoomMessages.toUser(template, registry, "bob", QUEUE, "update");
        assertEquals(List.of(), messages());
    }

    private List<Message<?>> messages() {
        Predicate<Message<?>> isMessage = m -> SimpMessageType.MESSAGE.equals(SimpMessageHeaderAccessor.getMessageType(m.getHeaders()));
        return delivered.stream().filter(isMessage).toList();
    }

    private Map<String, List<String>> messagesBySession() {
        return messages().stream().collect(Collectors.groupingBy(
                m -> SimpMessageHeaderAccessor.getSessionId(m.getHeaders()), LinkedHashMap::new,
                Collectors.mapping(m -> String.valueOf(m.getPayload()), Collectors.toList())));
    }

    private static Message<byte[]> control(SimpMessageType type, String session, String destination) {
        SimpMessageHeaderAccessor accessor = SimpMessageHeaderAccessor.create(type);
        accessor.setSessionId(session);
        accessor.setUser(() -> USER);
        if (destination != null) {
            accessor.setDestination(destination);
            accessor.setSubscriptionId("sub-" + session);
        }
        return MessageBuilder.createMessage(new byte[0], accessor.getMessageHeaders());
    }

    /** One user connected from two tabs. */
    private static final class TwoTabs implements SimpUserRegistry {
        private final SimpUser user;

        TwoTabs(String name, String... sessions) {
            this.user = new SimpUser() {
                private final Map<String, SimpSession> byId = new LinkedHashMap<>();
                {
                    for (String id : sessions) byId.put(id, session(id, this));
                }
                @Override public String getName() { return name; }
                @Override public Principal getPrincipal() { return () -> name; }
                @Override public boolean hasSessions() { return true; }
                @Override public SimpSession getSession(String id) { return byId.get(id); }
                @Override public Set<SimpSession> getSessions() { return Set.copyOf(byId.values()); }
            };
        }

        private static SimpSession session(String id, SimpUser owner) {
            return new SimpSession() {
                @Override public String getId() { return id; }
                @Override public SimpUser getUser() { return owner; }
                @Override public Set<org.springframework.messaging.simp.user.SimpSubscription> getSubscriptions() { return Set.of(); }
            };
        }

        @Override public SimpUser getUser(String userName) { return user.getName().equals(userName) ? user : null; }
        @Override public Set<SimpUser> getUsers() { return Set.of(user); }
        @Override public int getUserCount() { return 1; }
        @Override public Set<org.springframework.messaging.simp.user.SimpSubscription> findSubscriptions(
                org.springframework.messaging.simp.user.SimpSubscriptionMatcher matcher) { return Set.of(); }
    }
}
