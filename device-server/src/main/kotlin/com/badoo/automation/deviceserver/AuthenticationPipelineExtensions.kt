package com.badoo.automation.deviceserver

import io.ktor.application.call
import io.ktor.auth.*
import io.ktor.http.auth.HttpAuthHeader
import io.ktor.request.ApplicationRequest
import io.ktor.response.respond
import org.slf4j.Logger
import org.slf4j.LoggerFactory

class AnonymousPrincipal : Principal

fun HttpAuthHeader.Companion.bearerAuthChallenge(realm: String): HttpAuthHeader =
        HttpAuthHeader.Parameterized("Bearer", mapOf(HttpAuthHeader.Parameters.Realm to realm))

fun AuthenticationPipeline.anonymousAuthentication() {
    intercept(AuthenticationPipeline.RequestAuthentication) { context ->
        logger.warn("Bearer Authentication try with anonymous")
        if (context.principal == null) {
            context.principal(AnonymousPrincipal())
        }
    }
}
private val logger = LoggerFactory.getLogger("AuthenticationShit ")
fun AuthenticationPipeline.bearerAuthentication(realm: String, validate: suspend (String) -> UserIdPrincipal?) {
    intercept(AuthenticationPipeline.RequestAuthentication) { context ->
        logger.warn("Bearer Authentication bearerAuthentication")

        val credentials = call.request.bearerAuthenticationToken()
        val principal = credentials?.let { validate(it) }

        val cause = when {
            credentials == null -> AuthenticationFailedCause.NoCredentials
            principal == null -> AuthenticationFailedCause.InvalidCredentials
            else -> null
        }

        if (cause != null) {
            context.challenge("Bearer", cause) {
                call.respond(UnauthorizedResponse(HttpAuthHeader.bearerAuthChallenge(realm)))
                it.complete()
            }
        }
        if (principal != null) {
            context.principal(principal)
        }
    }
}

fun ApplicationRequest.bearerAuthenticationToken(): String? {
    val parsed = parseAuthorizationHeader()
    when (parsed) {
        is HttpAuthHeader.Single -> {
            if (parsed.authScheme != "Bearer") {
                return null
            }

            return parsed.blob
        }
        else -> return null
    }
}
