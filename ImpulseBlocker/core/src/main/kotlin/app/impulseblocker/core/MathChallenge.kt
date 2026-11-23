package app.impulseblocker.core

import java.security.SecureRandom
import kotlin.random.Random
import kotlin.random.asKotlinRandom
import kotlinx.serialization.Serializable

@Serializable
enum class Difficulty(val label: String) {
    EASY("Easy"),
    MEDIUM("Medium"),
    HARD("Hard"),
    VERY_HARD("Very hard");
}

enum class Operation(val symbol: String) { ADD("+"), SUBTRACT("−"), MULTIPLY("×"), DIVIDE("÷") }

data class MathQuestion(val a: Int, val op: Operation, val b: Int) {
    val answer: Int = when (op) {
        Operation.ADD -> a + b
        Operation.SUBTRACT -> a - b
        Operation.MULTIPLY -> a * b
        Operation.DIVIDE -> a / b
    }
    val text: String get() = "$a ${op.symbol} $b"

    fun isCorrect(input: String): Boolean {
        val cleaned = input.trim().replace('−', '-').replace(" ", "")
        return cleaned.toIntOrNull() == answer
    }
}

/**
 * Generates question sets. All four operations appear in every set of 4+
 * questions; division always has an integer result; subtraction never goes
 * negative. A cryptographically seeded random source means sequences are not
 * reproducible between attempts.
 */
class MathGenerator(private val random: Random = SecureRandom().asKotlinRandom()) {

    fun generate(count: Int, difficulty: Difficulty): List<MathQuestion> {
        require(count > 0)
        val ops = buildList {
            // Guarantee a mix of all operations, then fill randomly.
            addAll(Operation.entries.shuffled(random))
            while (size < count) add(Operation.entries.random(random))
        }.take(count).shuffled(random)

        val result = ArrayList<MathQuestion>(count)
        val seen = HashSet<MathQuestion>()
        for (op in ops) {
            var q: MathQuestion
            var attempts = 0
            do {
                q = question(op, difficulty)
            } while (!seen.add(q) && ++attempts < 20)
            result += q
        }
        return result
    }

    fun question(op: Operation, d: Difficulty): MathQuestion = when (op) {
        Operation.ADD -> when (d) {
            Difficulty.EASY -> MathQuestion(r(2, 20), op, r(2, 20))
            Difficulty.MEDIUM -> MathQuestion(r(12, 99), op, r(12, 99))
            Difficulty.HARD -> MathQuestion(r(120, 999), op, r(45, 999))
            Difficulty.VERY_HARD -> MathQuestion(r(1200, 9999), op, r(450, 9999))
        }
        Operation.SUBTRACT -> {
            val (a, b) = when (d) {
                Difficulty.EASY -> r(5, 20) to r(1, 10)
                Difficulty.MEDIUM -> r(30, 150) to r(12, 99)
                Difficulty.HARD -> r(300, 999) to r(45, 600)
                Difficulty.VERY_HARD -> r(3000, 9999) to r(450, 6000)
            }
            MathQuestion(maxOf(a, b), op, minOf(a, b))
        }
        Operation.MULTIPLY -> when (d) {
            Difficulty.EASY -> MathQuestion(r(2, 9), op, r(2, 9))
            Difficulty.MEDIUM -> MathQuestion(r(11, 25), op, r(3, 9))
            Difficulty.HARD -> MathQuestion(r(13, 49), op, r(6, 19))
            Difficulty.VERY_HARD -> MathQuestion(r(23, 99), op, r(13, 49))
        }
        Operation.DIVIDE -> {
            // Build from the quotient so the answer is always an integer.
            val (divisor, quotient) = when (d) {
                Difficulty.EASY -> r(2, 9) to r(2, 9)
                Difficulty.MEDIUM -> r(3, 12) to r(6, 25)
                Difficulty.HARD -> r(6, 19) to r(12, 49)
                Difficulty.VERY_HARD -> r(13, 49) to r(23, 99)
            }
            MathQuestion(divisor * quotient, op, divisor)
        }
    }

    private fun r(from: Int, toInclusive: Int) = random.nextInt(from, toInclusive + 1)
}
