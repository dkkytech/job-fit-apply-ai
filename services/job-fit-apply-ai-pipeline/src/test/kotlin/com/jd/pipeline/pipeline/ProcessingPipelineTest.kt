package com.jd.pipeline.pipeline

import com.jd.pipeline.nodes.CheckDuplicateNode
import com.jd.pipeline.nodes.Node
import com.jd.pipeline.source.IngestionSource
import com.jd.pipeline.source.JdRecord
import com.jd.pipeline.state.JDState
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@DisplayName("ProcessingPipelineTest")
class ProcessingPipelineTest {

    @BeforeEach
    fun setup() {
        CheckDuplicateNode.resetFallback()
    }

    private fun minimalRecord(jdText: String = "x".repeat(200)) = JdRecord(
        jdText     = jdText,
        company    = "Acme Corp",
        roleTitle  = "Staff SDET",
        location   = "Seattle, WA",
        jobUrl     = null,
        source     = IngestionSource.EMAIL,
    )

    private fun injectNode(pipeline: ProcessingPipeline, fieldName: String, node: Node<JDState>) {
        val field = ProcessingPipeline::class.java.getDeclaredField(fieldName)
        field.isAccessible = true
        field.set(pipeline, node)
    }

    @Test
    @DisplayName("invoke returns SKIP with error when checkDuplicate throws")
    fun invokeCatchesCheckDuplicateException() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { _ ->
            throw RuntimeException("simulated checkDuplicate failure")
        })

        val result = pipeline.invoke(minimalRecord())

        assertEquals("SKIP", result.pipelineAction)
        assertEquals(0, result.fitScore)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("simulated checkDuplicate failure"))
    }

    @Test
    @DisplayName("invoke returns SKIP with error when scoreFit throws")
    fun invokeCatchesScoreFitException() {
        val pipeline = ProcessingPipeline()
        // checkDuplicate must pass first (return non-duplicate state)
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { _ ->
            throw RuntimeException("simulated scoreFit failure")
        })

        val result = pipeline.invoke(minimalRecord())

        assertEquals("SKIP", result.pipelineAction)
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("simulated scoreFit failure"))
    }

    @Test
    @DisplayName("invoke returns SKIP when job is duplicate and not a recruiter email")
    fun invokeSkipsDuplicateNonRecruiter() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state ->
            state.copy(isDuplicate = true)
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        assertTrue(result.isDuplicate)
    }

    @Test
    @DisplayName("invoke returns error result when tailor subgraph fails")
    fun invokeHandlesTailorSubgraphError() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = com.jd.pipeline.state.PipelineAction.TAILOR,
                fitScore = 90f,
            )
        })
        injectNode(pipeline, "tailorSubgraph", Node { state ->
            state.copy(error = "tailor subgraph failed")
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        // tailor error surfaces in the result
        assertNotNull(result.error)
        assertTrue(result.error!!.contains("tailor subgraph failed"))
    }

    @Test
    @DisplayName("invoke maps blank outputPath to null in result")
    fun invokeOutputPathNullWhenBlank() {
        val pipeline = ProcessingPipeline()
        injectNode(pipeline, "checkDuplicate", Node { state -> state.copy(isDuplicate = false) })
        injectNode(pipeline, "scoreFit", Node { state ->
            state.copy(
                pipelineAction = com.jd.pipeline.state.PipelineAction.SKIP,
                fitScore = 30f,
            )
        })
        injectNode(pipeline, "supabaseTrack", Node { state -> state })

        val result = pipeline.invoke(minimalRecord())

        assertEquals(null, result.outputPath)
    }
}
