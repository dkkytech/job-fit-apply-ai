package com.jd.pipeline.utils

import com.fasterxml.jackson.annotation.JsonSubTypes
import com.fasterxml.jackson.annotation.JsonTypeInfo
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.MapperFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.PropertyNamingStrategies
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import com.jd.pipeline.source.IntakeContext

object Json {
    /** Snake_case mapper with IntakeContext polymorphism — used by BridgeClient. */
    val mapper: ObjectMapper = ObjectMapper().apply {
        registerKotlinModule()
        setPropertyNamingStrategy(PropertyNamingStrategies.SNAKE_CASE)
        configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false)
        addMixIn(IntakeContext::class.java, IntakeContextMixin::class.java)
    }

    @JsonTypeInfo(use = JsonTypeInfo.Id.NAME, include = JsonTypeInfo.As.PROPERTY, property = "type")
    @JsonSubTypes(
        JsonSubTypes.Type(value = IntakeContext.Email::class, name = "email"),
        JsonSubTypes.Type(value = IntakeContext.Api::class, name = "api"),
        JsonSubTypes.Type(value = IntakeContext.Synthetic::class, name = "synthetic"),
    )
    internal abstract class IntakeContextMixin
}
