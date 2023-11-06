/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.ververica.github;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Preconditions;
import com.ververica.ci.CiActions;
import com.ververica.ci.CiActionsLookup;
import okhttp3.Cache;
import okhttp3.OkHttpClient;
import okhttp3.OkUrlFactory;
import okhttp3.Request;
import okhttp3.Response;
import org.kohsuke.github.GHIssueComment;
import org.kohsuke.github.GHIssueState;
import org.kohsuke.github.GHPullRequest;
import org.kohsuke.github.GHRepository;
import org.kohsuke.github.GitHub;
import org.kohsuke.github.GitHubBuilder;
import org.kohsuke.github.extras.OkHttp3Connector;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public class GithubActionsImpl implements GitHubActions {
	private static final Logger LOG = LoggerFactory.getLogger(GithubActionsImpl.class);

	private final CiActionsLookup ciActionsLookup;
	private final Cache cache;
	private final OkHttpClient okHttpClient;
	private final GitHub gitHub;
	private final String authorizationToken;

	public GithubActionsImpl(CiActionsLookup ciActionsLookup, Path temporaryDirectory, String authorizationToken) throws IOException {
		this.ciActionsLookup = ciActionsLookup;
		cache = new Cache(temporaryDirectory.toFile(), 4 * 1024 * 1024);
		okHttpClient = setupOkHttpClient(cache);
		gitHub = setupGitHub(authorizationToken, okHttpClient);
		this.authorizationToken = authorizationToken;
	}

	private static OkHttpClient setupOkHttpClient(Cache cache) {
		LOG.info("Setting up OkHttp client with cache at {}.", cache.directory());

		final OkHttpClient.Builder okHttpClient = new OkHttpClient.Builder();
		okHttpClient.cache(cache);
		return okHttpClient.build();
	}

	private static GitHub setupGitHub(String token, OkHttpClient client) throws IOException {
		LOG.info("Setting up GitHub client.");

		return GitHubBuilder.fromEnvironment()
				.withOAuthToken(token)
				.withConnector(new OkHttp3Connector(new OkUrlFactory(client)))
				.build();
	}

	@Override
	public void submitComment(String repositoryName, int pullRequestID, String comment) throws IOException {
		final GHRepository repository = gitHub.getRepository(repositoryName);
		final GHPullRequest pullRequest = repository.getPullRequest(pullRequestID);
		pullRequest.comment(comment);
	}

	@Override
	public void deleteComment(String repositoryName, int pullRequestID, long commentId) throws IOException {
		final GHRepository repository = gitHub.getRepository(repositoryName);
		final GHPullRequest pullRequest = repository.getPullRequest(pullRequestID);
		final Optional<GHIssueComment> deletingComment = pullRequest.getComments().stream().filter(c -> c.getId() == commentId).findFirst();
		if (deletingComment.isPresent()) {
			deletingComment.get().delete();
		}
	}

	/**
	 * Retrieves the CI status for the given commit.
	 *
	 * <p>This internally retrieves the status via the Checks API, since the Commit Status API is not supported on
	 * {@code travis-ci.com}.
	 * Internally this uses a plain REST client, since the {@code github-api} does not support the
	 * Checks API. (see https://github.com/kohsuke/github-api/issues/520)
	 */
	public Iterable<GitHubCheckerStatus> getCommitState(String repositoryName, String commitHash, Pattern checkerNamePattern) {
		try (Response response = okHttpClient.newCall(new Request.Builder()
				.url("https://api.github.com/repos/" + repositoryName + "/commits/" + commitHash + "/check-runs")
				.addHeader("Accept", "application/vnd.github.antiope-preview+json")
				.addHeader("Authorization", "token " + authorizationToken)
				.build()).execute()) {
			final String rawJson = response.body().string();

			try {
				final ObjectMapper objectMapper = new ObjectMapper();
				final JsonNode jsonNode = objectMapper.readTree(rawJson);
				if (jsonNode == null || jsonNode.get("check_runs") == null) {
					LOG.warn("Could not retrieve checker for commit {}.", commitHash);
					return Collections.emptyList();
				}
				final Iterator<JsonNode> checkJson = jsonNode.get("check_runs").iterator();

				final Map<String, GitHubCheckerStatus> checksByUrl = new HashMap<>();
				while (checkJson.hasNext()) {
					final JsonNode next = checkJson.next();

					final String name = next.get("name").asText();
					if (!checkerNamePattern.matcher(name).matches()) {
						LOG.trace("Excluded checker with name {}.", name);
						continue;
					}
					LOG.trace("Processing checker run with name {}.", name);

					final JsonNode statusNode = next.get("status");
					final GHStatus status = GHStatus.valueOf(statusNode.asText().toUpperCase());

					final JsonNode conclusionNode = next.get("conclusion");
					final Optional<GHConclusion> conclusion = parseConclusion(conclusionNode);

					final String appSlug = next.get("app").get("slug").asText();
					final Optional<CiActions> ciActionsOptional = ciActionsLookup.getActionsForString(appSlug);
					if (!ciActionsOptional.isPresent()) {
						LOG.warn("Skipping checker since CI provider could not be determined. Slug={}.", appSlug);
						continue;
					}
					CiActions ciActions = ciActionsOptional.get();

					final JsonNode detailsUrlNode = next.get("details_url");
					final String detailsUrl = ciActions.normalizeUrl(detailsUrlNode.asText());

					final GitHubCheckerStatus checkerStatus;
					if (status != GHStatus.COMPLETED) {
						checkerStatus = new GitHubCheckerStatus(GitHubCheckerStatus.State.PENDING, detailsUrl, ciActions.getCiProvider());
					} else {
						if (!conclusion.isPresent()) {
							LOG.warn("Completed check did not have conclusion attached.");
							checkerStatus = null;
						} else {
							final GitHubCheckerStatus.State state;
							switch (conclusion.get()) {
								case SUCCESS:
									state = GitHubCheckerStatus.State.SUCCESS;
									break;
								case CANCELLED:
									state = GitHubCheckerStatus.State.CANCELED;
									break;
								default:
									state = GitHubCheckerStatus.State.FAILURE;
									break;
							}
							checkerStatus = new GitHubCheckerStatus(state, detailsUrl, ciActions.getCiProvider());
						}
					}
					if (checkerStatus != null) {
						checksByUrl.compute(detailsUrl, (s, gitHubCheckerStatus) ->
								gitHubCheckerStatus == null
										? checkerStatus
										: merge(checkerStatus, gitHubCheckerStatus));
					}
				}
				return new ArrayList<>(checksByUrl.values());
			} catch (Exception e) {
				LOG.debug("Raw Check JSON: {}.", rawJson);
				throw e;
			}
		} catch (Exception e) {
			// super janky but don't bother handling this in a better way
			// there are just too many failure points here
			LOG.warn("Could not retrieve commit state.", e);
			return Collections.emptyList();
		}
	}

	private static GitHubCheckerStatus merge(GitHubCheckerStatus a, GitHubCheckerStatus b) {
		Preconditions.checkArgument(a.getDetailsUrl().equals(b.getDetailsUrl()));
		Preconditions.checkArgument(a.getCiProvider().getName().equals(b.getCiProvider().getName()));

		final GitHubCheckerStatus.State mergedState;
		if (a.getState() == GitHubCheckerStatus.State.FAILURE || b.getState() == GitHubCheckerStatus.State.FAILURE) {
			mergedState = GitHubCheckerStatus.State.FAILURE;
		} else if (a.getState() == GitHubCheckerStatus.State.CANCELED || b.getState() == GitHubCheckerStatus.State.CANCELED) {
			mergedState = GitHubCheckerStatus.State.CANCELED;
		} else if (a.getState() == GitHubCheckerStatus.State.PENDING || b.getState() == GitHubCheckerStatus.State.PENDING) {
			mergedState = GitHubCheckerStatus.State.PENDING;
		} else if (a.getState() == GitHubCheckerStatus.State.SUCCESS && b.getState() == GitHubCheckerStatus.State.SUCCESS) {
			mergedState = GitHubCheckerStatus.State.SUCCESS;
		} else {
			LOG.warn("Unaccounted case for merging checker states. {} + {}", a.getState(), b.getState());
			mergedState = GitHubCheckerStatus.State.UNKNOWN;
		}
		return new GitHubCheckerStatus(mergedState, a.getDetailsUrl(), a.getCiProvider());
	}

	@Override
	public Iterable<String> getBranches(String repositoryName) throws IOException {
		return gitHub.getRepository(repositoryName).getBranches().keySet();
	}

	@Override
	public Iterable<GitHubComment> getComments(String repositoryName, int pullRequestID, String username) throws IOException {
		final GHRepository repository = gitHub.getRepository(repositoryName);
		final GHPullRequest pullRequest = repository.getPullRequest(pullRequestID);

		final List<GitHubComment> comments = new ArrayList<>();
		for (GHIssueComment listReviewComment : pullRequest.getComments()) {
			if (listReviewComment.getUser().getLogin().equals(username)) {
				comments.add(new GitHubComment(listReviewComment.getId(), listReviewComment.getUser().getName(), listReviewComment.getBody(), listReviewComment.getCreatedAt(), listReviewComment::update));
			}
		}
		return comments;
	}

	@Override
	public Stream<GitHubComment> getComments(String repositoryName, int pullRequestID, Pattern pattern) throws IOException {
		final GHRepository repository = gitHub.getRepository(repositoryName);
		final GHPullRequest pullRequest = repository.getPullRequest(pullRequestID);

		final List<GitHubComment> comments = new ArrayList<>();
		for (GHIssueComment listReviewComment : pullRequest.getComments()) {
			Matcher matcher = pattern.matcher(listReviewComment.getBody());
			if (matcher.find() || matcher.matches()) {
				comments.add(new GitHubComment(listReviewComment.getId(), listReviewComment.getUser().getName(), listReviewComment.getBody(), listReviewComment.getCreatedAt(), listReviewComment::update));
			}
		}
		return comments.stream();
	}

	@Override
	public Iterable<GithubPullRequest> getRecentlyUpdatedOpenPullRequests(String repositoryName, Date since) throws IOException {
		final GHRepository observedGitHubRepository = gitHub.getRepository(repositoryName);

		final List<GithubPullRequest> pullRequests = new ArrayList<>();
		for (GHPullRequest pullRequest : observedGitHubRepository.getPullRequests(GHIssueState.OPEN)) {
			LOG.trace("Evaluating PR {}.", pullRequest.getNumber());
			if (Objects.equals(pullRequest.getBase().getRef(), "asf-site")) {
				LOG.trace("Excluded PR {} due to merging against asf-site.", pullRequest.getNumber());
				continue;
			}
			final String title = pullRequest.getTitle().toUpperCase();
			if (title.contains("[RFC-")) {
				LOG.trace("Excluded PR {} due to being RFC.", pullRequest.getNumber());
				continue;
			}
			if (title.contains("[WIP]")) {
				LOG.trace("Excluded PR {} due to WIP.", pullRequest.getNumber());
				continue;
			}
			if (pullRequest.getUpdatedAt().after(since)) {
				pullRequests.add(new GithubPullRequest(pullRequest.getNumber(), pullRequest.getUpdatedAt(), pullRequest.getHead().getSha()));
			} else {
				LOG.trace("Excluded PR {} due to not being updated recently. LastUpdatedAt={} updateCutoff={}", pullRequest.getNumber(), pullRequest.getUpdatedAt(), since);
			}
		}

		return pullRequests;
	}

	@Override
	public boolean isPullRequestClosed(String repositoryName, int pullRequestID) throws IOException {
		final GHRepository observedGitHubRepository = gitHub.getRepository(repositoryName);

		return observedGitHubRepository.getPullRequest(pullRequestID).getState() == GHIssueState.CLOSED;
	}

	@Override
	public void close() {
		try {
			cache.close();
		} catch (Exception e) {
			LOG.debug("Error while shutting down cache.", e);
		}
	}

	private static Optional<GHConclusion> parseConclusion(JsonNode node) {
		if (node == null) {
			return Optional.empty();
		}

		String rawConclusion = node.asText().toUpperCase();
		try {
			return Optional.of(GHConclusion.valueOf(rawConclusion));
		} catch (IllegalArgumentException iae) {
			LOG.error("Encountered unknown conclusion {}.", rawConclusion);
			return Optional.of(GHConclusion.FAILURE);
		}

	}

	private enum GHConclusion {
		SUCCESS,
		FAILURE,
		NEUTRAL,
		STALE,
		CANCELLED,
		TIMED_OUT,
		ACTION_REQUIRED,
		NULL
	}

	private enum GHStatus {
		QUEUED,
		IN_PROGRESS,
		COMPLETED
	}
}
