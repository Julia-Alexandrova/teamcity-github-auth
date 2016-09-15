package org.jetbrains.teamcity.githubauth;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Strings;
import com.intellij.openapi.diagnostic.Logger;
import jetbrains.buildServer.PluginTypes;
import jetbrains.buildServer.controllers.interceptors.auth.HttpAuthenticationResult;
import jetbrains.buildServer.controllers.interceptors.auth.HttpAuthenticationScheme;
import jetbrains.buildServer.controllers.interceptors.auth.util.HttpAuthUtil;
import jetbrains.buildServer.log.Loggers;
import jetbrains.buildServer.serverSide.auth.ServerPrincipal;
import jetbrains.buildServer.serverSide.oauth.OAuthConnectionDescriptor;
import jetbrains.buildServer.serverSide.oauth.github.GitHubConstants;
import jetbrains.buildServer.users.DuplicateUserAccountException;
import jetbrains.buildServer.users.PluginPropertyKey;
import jetbrains.buildServer.users.SUser;
import jetbrains.buildServer.users.UserSet;
import jetbrains.buildServer.util.StringUtil;
import jetbrains.buildServer.web.util.WebUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import java.io.IOException;
import java.util.Collection;
import java.util.Collections;
import java.util.Iterator;
import java.util.Map;

import static java.util.Collections.emptySet;
import static java.util.Collections.singletonMap;

public class GitHubOAuth implements HttpAuthenticationScheme {
    static final PluginPropertyKey GITHUB_USER_ID_PROPERTY_KEY = new PluginPropertyKey(PluginTypes.AUTH_PLUGIN_TYPE, "github-oauth", "userId");
    static final String DEFAULT_SCOPE = "user,public_repo,repo,repo:status,write:repo_hook";
    private static final String STATE_SESSION_ATTR_NAME = "teamcity.gitHubAuth.state";

    @NotNull
    private final GitHubOAuthClient gitHubOAuthClient;
    @NotNull
    private final TeamCityCoreFacade teamCityCore;
    @NotNull
    private volatile Logger logger = Logger.getInstance(Loggers.AUTH_CATEGORY + ".gitHubOAuth");
    
    public GitHubOAuth(@NotNull GitHubOAuthClient gitHubOAuthClient,
                       @NotNull TeamCityCoreFacade teamCityCore) {
        this.gitHubOAuthClient = gitHubOAuthClient;
        this.teamCityCore = teamCityCore;
        teamCityCore.registerAuthModule(this);
    }

    @NotNull
    public String getUserRedirect(HttpServletRequest request) {
        OAuthConnectionDescriptor connection = getSuitableConnection();
        HttpSession session = request.getSession();
        String state = StringUtil.generateUniqueHash();
        session.setAttribute(STATE_SESSION_ATTR_NAME, state);
        return gitHubOAuthClient.getUserRedirect(connection.getParameters().get(GitHubConstants.CLIENT_ID_PARAM), DEFAULT_SCOPE, teamCityCore.getRootUrl(), state);
    }

    @NotNull
    @Override
    public HttpAuthenticationResult processAuthenticationRequest(@NotNull HttpServletRequest request, @NotNull HttpServletResponse response, @NotNull Map<String, String> properties) throws IOException {
        String code = request.getParameter("code");
        String state = request.getParameter("state");

        HttpAuthenticationResult result = validateRequest(request, response, code, state);
        if (result != null) return result;

        OAuthConnectionDescriptor connection = getSuitableConnection();
        GitHubToken token = gitHubOAuthClient.exchangeCodeToToken(code, connection.getParameters().get(GitHubConstants.CLIENT_ID_PARAM),
                connection.getParameters().get(GitHubConstants.CLIENT_SECRET_PARAM),
                teamCityCore.getRootUrl());
        logger.debug("GitHub token received: " + token.describe(false));

        GitHubUser gitHubUser = gitHubOAuthClient.getUser(token.access_token);
        logger.debug("GitHub user obtained: " + gitHubUser.describe(false));

        UserSet<SUser> users = teamCityCore.findUserByPropertyValue(GITHUB_USER_ID_PROPERTY_KEY, gitHubUser.getId());
        Iterator<SUser> iterator = users.getUsers().iterator();
        if (iterator.hasNext()) {
            final SUser found = iterator.next();
            teamCityCore.rememberToken(connection, found, gitHubUser.getLogin(), token);
            logger.debug("Corresponding TeamCity user found for the GitHub user '" + gitHubUser.describe(false) + "': " + found.describe(true));
            return HttpAuthenticationResult.authenticated(new ServerPrincipal(null, found.getUsername()), true);
        }

        try {
            SUser created = teamCityCore.createUser(gitHubUser.getLogin(), singletonMap(GITHUB_USER_ID_PROPERTY_KEY, gitHubUser.getId()));
            logger.debug("New TeamCity user created for the GitHub user '" + gitHubUser.describe(false) + "': " + created.describe(true));
            teamCityCore.rememberToken(connection, created, gitHubUser.getLogin(), token);
            return HttpAuthenticationResult.authenticated(new ServerPrincipal(null, gitHubUser.getLogin()), true);
        } catch (DuplicateUserAccountException e) {
            logger.warn("GitHub login error: user with username '" + gitHubUser.getLogin() + "' already exist.");
            return HttpAuthUtil.sendUnauthorized(request, response, "User with username '" + gitHubUser.getLogin() + "' already exist", emptySet());
        }
    }

    @Nullable
    private HttpAuthenticationResult validateRequest(HttpServletRequest request, HttpServletResponse response, String code, String state) throws IOException {
        if (Strings.isNullOrEmpty(code)) {
            logger.debug("No 'code' parameter found in the request, skip GitHub authentication");
            return HttpAuthenticationResult.notApplicable();
        }

        if (state == null) {
            logger.warn("Attempt to login using GitHub with empty 'state' parameter. Request: " + WebUtil.getRequestDump(request));
            return HttpAuthUtil.sendUnauthorized(request, response, "GitHub login error: 'state' parameter is empty", emptySet());
        }

        if (!state.equals(request.getSession().getAttribute(STATE_SESSION_ATTR_NAME))) {
            logger.warn("Attempt to login using GitHub with invalid 'state' parameter: " + state + ". Request: " + WebUtil.getRequestDump(request));
            return HttpAuthUtil.sendUnauthorized(request, response, "GitHub login error: 'state' parameter is invalid", emptySet());
        }
        return null;
    }

    @NotNull
    @Override
    public String getName() {
        return "github-oauth";
    }

    @NotNull
    @Override
    public String getDisplayName() {
        return "GitHub OAuth";
    }

    @NotNull
    @Override
    public String getDescription() {
        return "Allows authentication using GitHub account";
    }

    @Override
    public boolean isMultipleInstancesAllowed() {
        return false;
    }

    @NotNull
    @Override
    public Map<String, String> getDefaultProperties() {
        return Collections.emptyMap();
    }

    @Nullable
    @Override
    public String getEditPropertiesJspFilePath() {
        return GitHubAuthSettingsController.PATH;
    }

    @NotNull
    @Override
    public String describeProperties(@NotNull Map<String, String> properties) {
        return "";
    }

    @Nullable
    @Override
    public Collection<String> validate(@NotNull Map<String, String> properties) {
        if (tryFindSuitableConnection() == null) {
            return Collections.singleton("GitHub Authentication is inactive as GitHub.com Connection in the Root Project is not specified");
        }
        return null;
    }

    @Nullable
    public OAuthConnectionDescriptor tryFindSuitableConnection() {
        return teamCityCore.getRootProjectGitHubConnection();
    }

    @NotNull
    public OAuthConnectionDescriptor getSuitableConnection() {
        if (!isAuthModuleConfigured()) {
            throw new GitHubLoginException("Attempt to login via GitHub OAuth while corresponding auth module is not configured");
        }
        OAuthConnectionDescriptor found = tryFindSuitableConnection();
        if (found == null) {
            throw new GitHubLoginException("Attempt to login via GitHub OAuth while GitHub.com Connection in the Root Project is not configured");
        }
        return found;
    }

    public boolean isAuthModuleConfigured() {
        return teamCityCore.isAuthModuleConfigured(GitHubOAuth.class);
    }

    @VisibleForTesting
    void setLogger(Logger logger) {
        this.logger = logger;
    }
}
