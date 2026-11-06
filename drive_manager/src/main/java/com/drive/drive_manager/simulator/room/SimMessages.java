package com.drive.drive_manager.simulator.room;

/**
 * The errors a room answers to an action it refuses, as the board shows them (neutral Spanish). The web client
 * matches {@link #ROOM_GONE} exactly: its text must not change.
 */
public final class SimMessages {

    private SimMessages() {}

    /** The room was closed (the board leaves it): matched by the web client. */
    public static final String ROOM_GONE = "La sala ya no existe";
    public static final String ROOM_FULL = "La sala está llena";
    /** The player acting is not one of the room's players. */
    public static final String NOT_IN_ROOM = "No estás en la sala";
    /** Another player named by the action is not in the room. */
    public static final String PLAYER_NOT_FOUND = "Jugador no encontrado";
    public static final String NO_OPPONENT = "No tienes rival en la sala";
    public static final String UNKNOWN_ACTION = "Acción desconocida: ";
    public static final String NOT_YOUR_TURN = "No es tu turno";
    public static final String GAME_STARTED = "La partida ya empezó";
    public static final String GAME_NOT_IN_PROGRESS = "La partida no está en curso";

    public static final String INVALID_ZONE = "Zona no válida";
    public static final String DECK_EMPTY = "El mazo está vacío";
    public static final String LIFE_STACK_NOT_EMPTY = "Todavía te quedan vidas";
    public static final String DECK_NOT_FOUND = "No se encontró el mazo";

    public static final String CARD_NOT_FOUND = "No se encontró la carta";
    public static final String CARDS_NOT_FOUND = "No se encontraron las cartas";
    public static final String CARD_NOT_IN_DECK = "La carta no está en el mazo";
    public static final String TARGET_NOT_FOUND = "No se encontró la carta de destino";
    /** The card that holds an attached one (its material or resource). */
    public static final String HOLDER_NOT_FOUND = "No se encontró la carta a la que está unida";
    public static final String ATTACHMENT_NOT_FOUND = "No se encontró la carta unida";
    public static final String SUMMONED_NOT_FOUND = "No se encontró la carta invocada";
    public static final String SUMMONER_NOT_FOUND = "No se encontró el invocador";
    public static final String METADATA_NOT_FOUND = "No se encontraron los datos de la carta";

    /** The action does not say which card (no instanceId or cardId). */
    public static final String NO_CARD = "No se indicó la carta";
    public static final String NO_CARDS = "No se indicaron las cartas";
    public static final String NO_ORDER = "No se indicó el orden";
    public static final String NO_VALUE = "No se indicó el valor";
    public static final String NO_AMOUNT = "No se indicó la cantidad";
}
