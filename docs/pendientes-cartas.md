# Cartas: pendientes por revisar

## Caballero Leal (E1, "Turno Aliado / En Mano")

> Si algún aliado es invocado por efecto y no controlas copias de esta carta: puedes invocar esta carta reduciendo su coste en 1.

- **Qué pasa**: con dos copias en la mano, las dos se disparan con la misma invocación y se pueden invocar las dos. La
  condición "no controlas copias" (`ThisIs NO_COPIES`) solo se revisa al dispararse, cuando ninguna está en el campo.
- **Lo esperado (según el dueño)**: la segunda debería ver que la primera ya fue invocada y no invocarse.
- **Arreglo probado y descartado por ahora**: un paso `IfCondition NO_COPIES` (`covers: 1`) antes del `Summon` en
  `eras-engine/cards/E1/caballero-leal.json`, que vuelve a revisar la condición al resolver.
- **Por qué quedó pendiente**: al dueño le hace ruido la situación; falta decidir la regla general (¿las condiciones
  de un efecto "Si …:" se vuelven a revisar al resolver, para todas las cartas, o solo en esta?).
