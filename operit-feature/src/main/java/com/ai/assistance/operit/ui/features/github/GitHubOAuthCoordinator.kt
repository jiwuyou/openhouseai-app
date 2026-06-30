package com.ai.assistance.operit.ui.features.github

import android.content.Context
import android.net.Uri
import com.ai.assistance.operit.data.api.GitHubApiService
import com.ai.assistance.operit.data.preferences.GitHubAuthPreferences
import com.ai.assistance.operit.data.preferences.GitHubUser

class GitHubOAuthCoordinator(context: Context) {
    private val appContext = context.applicationContext
    private val githubAuth = GitHubAuthPreferences.getInstance(appContext)
    private val githubApiService = GitHubApiService(appContext)

    suspend fun createExternalAuthorizationUrl(): String {
        val state = GitHubAuthPreferences.createOAuthState()
        githubAuth.setPendingOAuthState(state)
        return githubAuth.getAuthorizationUrl(state = state)
    }

    suspend fun completeExternalLogin(uri: Uri): Result<GitHubUser> {
        val expectedState = githubAuth.consumePendingOAuthState()
            ?: return Result.failure(IllegalStateException("Missing pending OAuth state"))
        return completeLoginFromRedirect(uri, expectedState)
    }

    suspend fun completeLoginFromRedirect(
        uri: Uri,
        expectedState: String
    ): Result<GitHubUser> {
        if (!GitHubAuthPreferences.isOAuthRedirectUri(uri)) {
            return Result.failure(IllegalArgumentException("Unsupported OAuth redirect URI"))
        }

        val returnedState = uri.getQueryParameter("state")
        if (returnedState.isNullOrBlank() || returnedState != expectedState) {
            return Result.failure(IllegalStateException("OAuth state mismatch"))
        }

        val error = uri.getQueryParameter("error")
        if (!error.isNullOrBlank()) {
            val errorDescription = uri.getQueryParameter("error_description").orEmpty()
            return Result.failure(
                IllegalStateException(errorDescription.ifBlank { error })
            )
        }

        val code = uri.getQueryParameter("code")
            ?: return Result.failure(IllegalStateException("Missing authorization code"))

        return completeLoginWithCode(code)
    }

    suspend fun completeLoginWithCode(code: String): Result<GitHubUser> {
        return runCatching {
            val tokenResponse = githubApiService.getAccessToken(code).getOrElse { error ->
                throw error
            }
            githubAuth.updateAccessToken(
                accessToken = tokenResponse.access_token,
                tokenType = tokenResponse.token_type,
                grantedScope = tokenResponse.scope
            )

            val user = githubApiService.getCurrentUser().getOrElse { error ->
                throw error
            }

            githubAuth.saveAuthInfo(
                accessToken = tokenResponse.access_token,
                tokenType = tokenResponse.token_type,
                userInfo = user,
                grantedScope = tokenResponse.scope
            )
            user
        }
    }
}
