package com.drive.drive_manager.simulator;

/**
 * What an effect gave or took from a card for a while ("pierde 3 de fuerza hasta el final del turno"), with the
 * card it came from, so the card's details can show it. {@code until}: "turn", "nextTurn" or "attack".
 */
public record FloatingEffect(String text, String source, String until, int strength, String keyword) {}
