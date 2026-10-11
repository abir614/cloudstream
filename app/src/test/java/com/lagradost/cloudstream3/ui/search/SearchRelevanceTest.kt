package com.lagradost.cloudstream3.ui.search

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SearchRelevanceTest {

    private lateinit var viewModel: SearchViewModel

    @Before
    fun setUp() {
        viewModel = SearchViewModel()
    }

    @Test
    fun `test exact match yields highest score of 1000`() {
        val score = viewModel.calculateRelevance(title = "Inception", query = "Inception")
        assertEquals(1000, score)

        val caseInsensitiveScore = viewModel.calculateRelevance(title = "Inception", query = "inception")
        assertEquals(1000, caseInsensitiveScore)
    }

    @Test
    fun `test normalized alphanumeric match yields 950`() {
        // Punctuations difference: "Spider-Man" vs "Spider Man"
        val score = viewModel.calculateRelevance(title = "Spider-Man", query = "Spider Man")
        assertEquals(950, score)
    }

    @Test
    fun `test prefix match ranks in 800-900 tier`() {
        val score = viewModel.calculateRelevance(title = "Breaking Bad: Season 1", query = "Breaking Bad")
        assertTrue("Prefix match score should be in 800-900 range, got $score", score in 800..900)
    }

    @Test
    fun `test substring match ranks in 600-700 tier`() {
        val score = viewModel.calculateRelevance(title = "The Lord of the Rings: The Fellowship", query = "Rings")
        assertTrue("Substring match score should be in 600-700 range, got $score", score in 600..700)
    }

    @Test
    fun `test multi-word unordered match scores 500`() {
        val score = viewModel.calculateRelevance(title = "Attack on Titan: The Final Season", query = "Titan Attack")
        assertEquals(500, score)
    }

    @Test
    fun `test relevance ordering ranks exact over prefix over substring`() {
        val query = "Matrix"
        val exactScore = viewModel.calculateRelevance("Matrix", query)
        val prefixScore = viewModel.calculateRelevance("Matrix Reloaded", query)
        val substringScore = viewModel.calculateRelevance("The Matrix Resurrections", query)

        assertTrue(exactScore > prefixScore)
        assertTrue(prefixScore > substringScore)
    }
}
