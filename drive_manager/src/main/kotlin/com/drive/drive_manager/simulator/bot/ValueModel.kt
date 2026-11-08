package com.drive.drive_manager.simulator.bot

import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sqrt
import com.drive.drive_manager.simulator.engine.JavaMath
import com.drive.drive_manager.simulator.engine.GameRandom
import com.drive.drive_manager.simulator.room.GameRoom

/**
 * A trainable model of the table's worth: the chance that a player wins from it, read from the table's numbers
 * ([BoardFeatures]). A small neural network: the numbers are standardized, go through one hidden layer
 * (ReLU) and a sigmoid; with no hidden units it is a logistic regression.
 *
 * It learns from games already played ([train]): each sample is a table seen by one player and whether
 * that player won; whole games are kept for validation. What it minimizes is the log loss (binary cross-entropy) plus an L2 penalty on its weights,
 * by Adam on mini-batches; it keeps the weights of the epoch with the lowest log loss on the validation samples
 * and stops when that has not improved for `patience` epochs. Saved and read as JSON by [ValueModelFiles].
 */
class ValueModel private constructor(private val w: Weights) {

    /** The model as it is saved: its weights, how its input is standardized, and how it was trained. */
    @JvmRecord
    data class Weights(val features: List<String>?, val mean: DoubleArray?, val std: DoubleArray?, val hidden: Int,
                       val w1: Array<DoubleArray>?, val b1: DoubleArray?, val w2: DoubleArray?, val b2: Double,
                       val training: Map<String, Any?>?)

    /** How it is trained. `validation`: share of the samples kept apart to judge each epoch. */
    @JvmRecord
    data class Training(val hidden: Int, val epochs: Int, val batch: Int, val learningRate: Double, val l2: Double,
                        val validation: Double, val patience: Int, val seed: Long) {
        companion object {
            @JvmField
            val DEFAULT = Training(16, 60, 256, 0.003, 1e-4, 0.15, 8, 1)
        }
    }

    /**
     * A table seen by a player, whether that player won it (1) or lost it (0), and the game it comes from (the tables
     * of one game look alike, so a game is kept whole on one side of the training / validation split).
     */
    @JvmRecord
    data class Sample(val x: DoubleArray?, val won: Double, val game: Long) {
        constructor(x: DoubleArray?, won: Double) : this(x, won, -1)
    }

    /** How well it predicts: log loss, Brier score and accuracy (predicting a win at 0.5 or more). */
    @JvmRecord
    data class Fit(val logLoss: Double, val brier: Double, val accuracy: Double, val samples: Int)

    fun weights(): Weights {
        return w
    }

    // ------------------------------------------------------------------ Using it

    /** The chance that `me` wins from the table. */
    fun winChance(room: GameRoom, me: String?): Double {
        return predict(BoardFeatures.of(room, me))
    }

    fun predict(x: DoubleArray): Double {
        return sigmoid(logit(x, null))
    }

    /** The output before the sigmoid; `hiddenOut` (when not null) receives the hidden layer, for training. */
    private fun logit(x: DoubleArray, hiddenOut: DoubleArray?): Double {
        val n = x.size
        val z = DoubleArray(n)
        for (i in 0 until n) z[i] = (x[i] - w.mean!![i]) / w.std!![i]
        if (w.hidden == 0) {
            var s = w.b2
            for (i in 0 until n) s += w.w2!![i] * z[i]
            return s
        }
        var s = w.b2
        for (h in 0 until w.hidden) {
            var a = w.b1!![h]
            for (i in 0 until n) a += w.w1!![h][i] * z[i]
            a = max(0.0, a)
            if (hiddenOut != null) hiddenOut[h] = a
            s += w.w2!![h] * a
        }
        return s
    }

    /** How well it predicts these samples. */
    fun fit(samples: List<Sample>): Fit {
        var loss = 0.0
        var brier = 0.0
        var right = 0.0
        for (s in samples) {
            val p = min(1 - 1e-7, max(1e-7, predict(s.x!!)))
            loss -= s.won * ln(p) + (1 - s.won) * ln(1 - p)
            brier += (p - s.won) * (p - s.won)
            if ((p >= 0.5) == (s.won >= 0.5)) right++
        }
        val n = max(1, samples.size)
        return Fit(loss / n, brier / n, right / n, samples.size)
    }

    companion object {

        // ------------------------------------------------------------------ Training

        /** Receives a line per epoch of a training (a `Consumer<String>` as before). */
        fun interface TrainingLog {
            fun accept(line: String)
        }

        /**
         * A model trained on the samples (see the class). `log` receives one line per epoch.
         */
        @JvmStatic
        fun train(samples: List<Sample>, t: Training, log: TrainingLog?): ValueModel {
            if (samples.size < 10) throw IllegalArgumentException("Too few samples to train: " + samples.size)
            val random = GameRandom(t.seed)
            // Whole games to validation (a sample without a game is a game of its own)
            val byGame: MutableMap<Long, MutableList<Sample>> = LinkedHashMap()
            var alone = Long.MIN_VALUE
            for (s in samples) byGame.getOrPut(if (s.game >= 0) s.game else alone++) { ArrayList() }.add(s)
            val games: MutableList<MutableList<Sample>> = ArrayList(byGame.values)
            GameRandom.shuffle(games, random)
            val train: MutableList<Sample> = ArrayList()
            val valid: MutableList<Sample> = ArrayList()
            for (g in games) (if (valid.size < samples.size * t.validation) valid else train).addAll(g)
            val n = BoardFeatures.NAMES.size

            val mean = DoubleArray(n)
            val std = DoubleArray(n)
            for (s in train) for (i in 0 until n) mean[i] += s.x!![i] / train.size
            for (s in train) for (i in 0 until n) std[i] += (s.x!![i] - mean[i]).pow(2.0) / train.size
            for (i in 0 until n) std[i] = max(1e-6, sqrt(std[i]))

            val hidden = t.hidden
            val width = if (hidden == 0) n else hidden
            val w1 = Array(hidden) { DoubleArray(n) }
            val b1 = DoubleArray(hidden)
            val w2 = DoubleArray(width)
            for (h in 0 until hidden) for (i in 0 until n) w1[h][i] = random.nextGaussian() * sqrt(2.0 / n)
            for (h in 0 until width) w2[h] = random.nextGaussian() * sqrt(1.0 / width) * 0.1
            val b2 = doubleArrayOf(0.0)

            // Adam moments, one per weight, in the same shapes
            val m1 = Array(hidden) { DoubleArray(n) }
            val v1 = Array(hidden) { DoubleArray(n) }
            val mb1 = DoubleArray(hidden)
            val vb1 = DoubleArray(hidden)
            val m2 = DoubleArray(width)
            val v2 = DoubleArray(width)
            val mb2 = doubleArrayOf(0.0)
            val vb2 = doubleArrayOf(0.0)
            val beta1 = 0.9
            val beta2 = 0.999
            val eps = 1e-8
            var step = 0L

            var best: Weights? = null
            var bestLoss = Double.POSITIVE_INFINITY
            var sinceBest = 0
            val epochs: MutableList<Map<String, Any?>> = ArrayList()
            for (epoch in 1..t.epochs) {
                val order: MutableList<Sample> = ArrayList(train)
                GameRandom.shuffle(order, random)
                var start = 0
                while (start < order.size) {
                    val batch = order.subList(start, min(order.size, start + t.batch))
                    val g1 = Array(hidden) { DoubleArray(n) }
                    val gb1 = DoubleArray(hidden)
                    val g2 = DoubleArray(width)
                    var gb2 = 0.0
                    val current = ValueModel(Weights(BoardFeatures.NAMES, mean, std, hidden, w1, b1, w2, b2[0], null))
                    val act = DoubleArray(hidden)
                    for (s in batch) {
                        val p = sigmoid(current.logit(s.x!!, act))
                        val d = (p - s.won) / batch.size  // d(log loss)/d(logit)
                        val z = DoubleArray(n)
                        for (i in 0 until n) z[i] = (s.x[i] - mean[i]) / std[i]
                        gb2 += d
                        if (hidden == 0) {
                            for (i in 0 until n) g2[i] += d * z[i]
                            continue
                        }
                        for (h in 0 until hidden) {
                            g2[h] += d * act[h]
                            if (act[h] <= 0) continue
                            val dh = d * w2[h]
                            gb1[h] += dh
                            for (i in 0 until n) g1[h][i] += dh * z[i]
                        }
                    }
                    step++
                    val c1 = 1 - beta1.pow(step.toDouble())
                    val c2 = 1 - beta2.pow(step.toDouble())
                    for (h in 0 until hidden) {
                        for (i in 0 until n) {
                            val g = g1[h][i] + t.l2 * w1[h][i]
                            m1[h][i] = beta1 * m1[h][i] + (1 - beta1) * g
                            v1[h][i] = beta2 * v1[h][i] + (1 - beta2) * g * g
                            w1[h][i] -= t.learningRate * (m1[h][i] / c1) / (sqrt(v1[h][i] / c2) + eps)
                        }
                        mb1[h] = beta1 * mb1[h] + (1 - beta1) * gb1[h]
                        vb1[h] = beta2 * vb1[h] + (1 - beta2) * gb1[h] * gb1[h]
                        b1[h] -= t.learningRate * (mb1[h] / c1) / (sqrt(vb1[h] / c2) + eps)
                    }
                    for (h in 0 until width) {
                        val g = g2[h] + t.l2 * w2[h]
                        m2[h] = beta1 * m2[h] + (1 - beta1) * g
                        v2[h] = beta2 * v2[h] + (1 - beta2) * g * g
                        w2[h] -= t.learningRate * (m2[h] / c1) / (sqrt(v2[h] / c2) + eps)
                    }
                    mb2[0] = beta1 * mb2[0] + (1 - beta1) * gb2
                    vb2[0] = beta2 * vb2[0] + (1 - beta2) * gb2 * gb2
                    b2[0] -= t.learningRate * (mb2[0] / c1) / (sqrt(vb2[0] / c2) + eps)
                    start += t.batch
                }
                val now = ValueModel(Weights(BoardFeatures.NAMES, mean, std, hidden, w1, b1, w2, b2[0], null))
                val trainFit = now.fit(train)
                val validFit = now.fit(valid)
                val e: MutableMap<String, Any?> = LinkedHashMap()
                e["epoch"] = epoch
                e["trainLogLoss"] = round(trainFit.logLoss)
                e["validLogLoss"] = round(validFit.logLoss)
                e["validBrier"] = round(validFit.brier)
                e["validAccuracy"] = round(validFit.accuracy)
                epochs.add(e)
                log?.accept(e.toString())
                if (validFit.logLoss < bestLoss - 1e-5) {
                    bestLoss = validFit.logLoss
                    sinceBest = 0
                    best = Weights(BoardFeatures.NAMES, mean.copyOf(), std.copyOf(), hidden, deepClone(w1), b1.copyOf(), w2.copyOf(), b2[0], null)
                } else if (++sinceBest >= t.patience) {
                    break
                }
            }
            val chosen = ValueModel(best!!)
            val training: MutableMap<String, Any?> = LinkedHashMap()
            training["settings"] = t
            training["trainSamples"] = train.size
            training["validSamples"] = valid.size
            training["train"] = chosen.fit(train)
            training["valid"] = chosen.fit(valid)
            training["epochs"] = epochs
            return ValueModel(Weights(best.features, best.mean, best.std, best.hidden, best.w1, best.b1, best.w2, best.b2, training))
        }

        // ------------------------------------------------------------------ From its weights (saved and read: ValueModelFiles)

        @JvmStatic
        fun of(weights: Weights?): ValueModel {
            if (BoardFeatures.NAMES != weights!!.features) {
                throw IllegalArgumentException("The model was trained with other table features: " + weights.features)
            }
            return ValueModel(weights)
        }

        // ------------------------------------------------------------------ Helpers

        private fun sigmoid(v: Double): Double {
            return 1 / (1 + exp(-v))
        }

        private fun deepClone(a: Array<DoubleArray>): Array<DoubleArray> {
            return Array(a.size) { i -> a[i].copyOf() }
        }

        private fun round(v: Double): Double {
            return JavaMath.round(v * 10000) / 10000.0
        }
    }
}
