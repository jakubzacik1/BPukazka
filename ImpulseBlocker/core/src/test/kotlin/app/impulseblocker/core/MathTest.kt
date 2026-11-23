package app.impulseblocker.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MathTest {

    @Test fun generatesRequestedCountWithAllOperations() {
        val g = MathGenerator()
        for (d in Difficulty.entries) {
            val qs = g.generate(10, d)
            assertEquals(10, qs.size)
            assertEquals(Operation.entries.toSet(), qs.map { it.op }.toSet())
            for (q in qs) {
                if (q.op == Operation.DIVIDE) assertEquals(0, q.a % q.b)
                if (q.op == Operation.SUBTRACT) assertTrue(q.answer >= 0)
            }
        }
    }

    @Test fun sequencesDiffer() {
        val g = MathGenerator()
        val a = g.generate(10, Difficulty.MEDIUM)
        val b = g.generate(10, Difficulty.MEDIUM)
        assertNotEquals(a, b)
    }

    @Test fun answerChecking() {
        val q = MathQuestion(84, Operation.DIVIDE, 7)
        assertTrue(q.isCorrect(" 12 "))
        assertFalse(q.isCorrect("13"))
        assertFalse(q.isCorrect("twelve"))
        assertTrue(MathQuestion(5, Operation.SUBTRACT, 9).isCorrect("−4"))
    }
}
