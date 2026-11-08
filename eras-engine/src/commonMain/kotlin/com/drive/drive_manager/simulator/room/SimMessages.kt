package com.drive.drive_manager.simulator.room

/**
 * The errors a room answers to an action it refuses, as the board shows them (neutral Spanish). The web client
 * matches [ROOM_GONE] exactly: its text must not change.
 */
object SimMessages {

    /** The room was closed (the board leaves it): matched by the web client. */
    const val ROOM_GONE = "La sala ya no existe"
    const val ROOM_FULL = "La sala está llena"
    /** The player acting is not one of the room's players. */
    const val NOT_IN_ROOM = "No estás en la sala"
    /** Another player named by the action is not in the room. */
    const val PLAYER_NOT_FOUND = "Jugador no encontrado"
    const val NO_OPPONENT = "No tienes rival en la sala"
    const val UNKNOWN_ACTION = "Acción desconocida: "
    const val NOT_YOUR_TURN = "No es tu turno"
    const val GAME_STARTED = "La partida ya empezó"
    const val GAME_NOT_IN_PROGRESS = "La partida no está en curso"

    const val INVALID_ZONE = "Zona no válida"
    const val DECK_EMPTY = "El mazo está vacío"
    const val LIFE_STACK_NOT_EMPTY = "Todavía te quedan vidas"
    const val DECK_NOT_FOUND = "No se encontró el mazo"

    const val CARD_NOT_FOUND = "No se encontró la carta"
    const val CARDS_NOT_FOUND = "No se encontraron las cartas"
    const val CARD_NOT_IN_DECK = "La carta no está en el mazo"
    const val TARGET_NOT_FOUND = "No se encontró la carta de destino"
    /** The card that holds an attached one (its material or resource). */
    const val HOLDER_NOT_FOUND = "No se encontró la carta a la que está unida"
    const val ATTACHMENT_NOT_FOUND = "No se encontró la carta unida"
    const val SUMMONED_NOT_FOUND = "No se encontró la carta invocada"
    const val SUMMONER_NOT_FOUND = "No se encontró el invocador"
    const val METADATA_NOT_FOUND = "No se encontraron los datos de la carta"

    /** The action does not say which card (no instanceId or cardId). */
    const val NO_CARD = "No se indicó la carta"
    const val NO_CARDS = "No se indicaron las cartas"
    const val NO_ORDER = "No se indicó el orden"
    const val NO_VALUE = "No se indicó el valor"
    const val NO_AMOUNT = "No se indicó la cantidad"
}
