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

package com.ververica;

import com.beust.jcommander.JCommander;
import com.beust.jcommander.ParameterException;
import com.ververica.ci.CiActions;
import com.ververica.ci.CiActionsContainer;
import com.ververica.ci.CiProvider;
import com.ververica.ci.azure.AzureActionsImpl;
import com.ververica.ci.travis.TravisActionsImpl;
import com.ververica.git.GitActionsImpl;
import com.ververica.git.GitException;
import com.ververica.github.GitHubCheckerStatus;
import com.ververica.github.GithubActionsImpl;
import com.ververica.utils.ConsumerWithException;
import com.ververica.utils.FunctionWithException;
import com.ververica.utils.RevisionInformation;
import org.apache.commons.io.FileUtils;
import org.eclipse.jgit.api.errors.TransportException;
import org.kohsuke.github.GHException;
import org.kohsuke.github.GHFileNotFoundException;
import org.kohsuke.github.HttpException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static com.ververica.utils.LogUtils.formatPullRequestID;

/**
 * A bot that mirrors pull requests opened against one repository (so called "observed repository") to branches in
 * another repository (so called "ci repository"), and report back the Checker status once the checks have completed.
 */
public class CiBot implements Runnable, AutoCloseable {

	private static final Logger LOG = LoggerFactory.getLogger(CiBot.class);

	private static final Path LOCAL_BASE_PATH = Paths.get(System.getProperty("java.io.tmpdir"), "ci_bot");

	private final Core core;
	private final GitActionsImpl gitActions;
	private final int pollingIntervalInSeconds;
	private final int backlogHours;
	private final boolean postNewCommentForCIReport;

	public static void main(String[] args) throws Exception {
		final Arguments arguments = new Arguments();
		final JCommander jCommander = JCommander.newBuilder()
				.addObject(arguments)
				.programName("java -jar ci-bot.jar")
				.args(args)
				.build();

		if (arguments.help) {
			final StringBuilder helpOutput = new StringBuilder();
			jCommander.usage(helpOutput);
			LOG.info(helpOutput.toString());
			return;
		}

		final RevisionInformation revisionInformation = RevisionInformation.getRevisionInformation();
		LOG.info("Starting CiBot. Revision: {} Date: {}", revisionInformation.getCommitHash(), revisionInformation.getCommitDate());

		final CiActions[] ciActions =
				Stream.concat(
						arguments.travisToken == null
								? Stream.empty()
								: Stream.of(new TravisActionsImpl(LOCAL_BASE_PATH.resolve("travis"), arguments.travisToken)),
						arguments.azureToken == null
								? Stream.empty()
								: Stream.of(AzureActionsImpl.create(LOCAL_BASE_PATH.resolve("azure"), arguments.azureToken)))
						.peek(ciAction -> LOG.info("Configured ci provider {}.", ciAction))
						.toArray(CiActions[]::new);


		if (ciActions.length == 0) {
			final ParameterException parameterException = new ParameterException("At least one ci provider must be configured.");
			parameterException.setJCommander(jCommander);
			throw parameterException;
		}

		final CiActionsContainer ciActionsContainer = new CiActionsContainer(ciActions);

		while (true) {
			final GitActionsImpl gitActions = new GitActionsImpl(LOCAL_BASE_PATH);
			try (final CiBot ciBot = new CiBot(
					new Core(
							arguments.observedRepository,
							arguments.ciRepository,
							arguments.username,
							arguments.githubToken,
							gitActions,
							new GithubActionsImpl(ciActionsContainer, LOCAL_BASE_PATH.resolve("github"), arguments.githubToken),
							ciActionsContainer,
							arguments.checkerNamePattern),
					gitActions,
					arguments.pollingIntervalInSeconds,
					arguments.backlogHours,
					arguments.postNewCommentForCIReport)) {
				ciBot.run();
			} catch (Exception e) {
				LOG.error("An exception crashed CiBot. Restarting in 5 minutes.", e);
				Thread.sleep(1000 * 60 * 5);
			}
		}
	}

	public CiBot(Core core, GitActionsImpl gitActions, int pollingIntervalInSeconds, int backlogHours, boolean postNewCommentForCIReport) {
		this.core = core;
		this.gitActions = gitActions;
		this.pollingIntervalInSeconds = pollingIntervalInSeconds;
		this.backlogHours = backlogHours;
		this.postNewCommentForCIReport = postNewCommentForCIReport;
	}

	@Override
	public void run() {
		try {
			Date lastUpdateDate = Date.from(Instant.now().minus(Duration.ofHours(backlogHours)));
			if (pollingIntervalInSeconds > 0) {
				while (true) {
					lastUpdateDate = runOnce(lastUpdateDate);
					LOG.info("Taking a nap...");
					Thread.sleep(pollingIntervalInSeconds * 1000L);
				}
			} else {
				 runOnce(lastUpdateDate);
			}
		} catch (Exception e) {
			LOG.error("An exception occurred.", e);
		}
	}

	private Date runOnce(Date lastUpdateDate) throws Exception {
		final Date currentUpdateDate = Date.from(Instant.now().minus(Duration.ofMinutes(10)));
		try {
			tick(lastUpdateDate);
		} catch (SocketTimeoutException ste) {
			LOG.error("Timeout occurred.", ste);
		} catch (HttpException he) {
			// this may happen in case of a timeout for some reasons
			LOG.error("Generic HTTP exception occurred.", he);
		} catch (TransportException te) {
			// this may happen in case of a git timeout
			LOG.error("Generic transport exception occurred.", te);
		} catch (GHException ge) {
			LOG.error("Generic github exception occurred.", ge);
		} catch (GHFileNotFoundException gfnfe) {
			LOG.error("GitHub server error.", gfnfe);
		} catch (IOException ioe) {
			LOG.error("Some IO error.", ioe);
		} catch (GitException ge) {
			LOG.error("A Git error has occurred.", ge);
		}
		return currentUpdateDate;
	}

	@Override
	public void close() {
		core.close();
		try {
			FileUtils.deleteDirectory(LOCAL_BASE_PATH.toFile());
		} catch (Exception e) {
			LOG.debug("Error while cleaning up directory.", e);
		}
		LOG.info("Shutting down.");
	}

	private void tick(Date lastUpdateTime) throws Exception {
		core.getPullRequests(lastUpdateTime)
				.map(FunctionWithException.wrap(
						core::processPullRequest,
						(r, e) -> LOG.error("Error while processing pull request {}.", formatPullRequestID(r.getID()), e)))
				.filter(Optional::isPresent)
				.map(Optional::get)
				.forEach(ConsumerWithException.wrap(
						this::processCiReport,
						(r, e) -> LOG.error("Error while processing pull request {}.", formatPullRequestID(r.getPullRequestID()), e)));
		gitActions.cleanup();
	}

	private void processCiReport(CiReport ciReport) throws Exception {
		final int pullRequestID = ciReport.getPullRequestID();

		if (core.isPullRequestClosed(pullRequestID)) {
			LOG.info("PullRequest {} was closed; canceling builds and deleting branches.", formatPullRequestID(pullRequestID));
			ciReport.getPendingBuilds().forEach(core::cancelBuild);
			ciReport.getFinishedBuilds().forEach(core::deleteCiBranch);
			return;
		}

		// retry mirroring for builds with an unknown state, in case something went wrong during the push/CI trigger
		ciReport.getUnknownBuilds().forEach(build -> core.mirrorPullRequest(build.pullRequestID));

		Map<Trigger.Type, List<Build>> builds = ciReport.getRequiredBuilds().collect(Collectors.groupingBy(build -> build.trigger.getType()));
		List<Build> pushBuilds = builds.getOrDefault(Trigger.Type.PUSH, Collections.emptyList());
		List<Build> manualBuilds = builds.getOrDefault(Trigger.Type.MANUAL, Collections.emptyList());

		if (!pushBuilds.isEmpty() || !manualBuilds.isEmpty()) {
			LOG.info("Canceling pending builds for PullRequest {} since a new build was triggered.", formatPullRequestID(pullRequestID));
			// HACK: cancel all pending builds, on the assumption that they are all identical anyway
			// this may not be necessarily true in the future
			ciReport.getPendingBuilds().forEach(core::cancelBuild);
		}

		if (!pushBuilds.isEmpty()) {
			// we've got a new commit, skip all triggered manual builds
			manualBuilds.forEach(manualBuild -> ciReport.add(new Build(pullRequestID, "", Optional.of(new GitHubCheckerStatus(GitHubCheckerStatus.State.CANCELED, "TBD", CiProvider.Unknown)), manualBuild.trigger)));

			// there should only ever by a single push build
			Build latestPushBuild = pushBuilds.get(pushBuilds.size() - 1);
			core.runBuild(ciReport, latestPushBuild)
					.ifPresent(ciReport::add);
		} else if (!manualBuilds.isEmpty()) {
			// we are just re-running some previous build
			// ideally make sure we don't trigger equivalent builds multiple times

			// HACK: only process the last manual trigger, on the assumption that they are all identical anyway
			// this may not be necessarily true in the future
			for (int x = 0; x < manualBuilds.size() - 1; x++) {
				Build manualBuild = manualBuilds.get(x);
				ciReport.add(new Build(pullRequestID, "0000", Optional.of(new GitHubCheckerStatus(GitHubCheckerStatus.State.CANCELED, "TBD", CiProvider.Unknown)), manualBuild.trigger));
			}

			Build latestManualBuild = manualBuilds.get(manualBuilds.size() - 1);
			core.runBuild(ciReport, latestManualBuild)
					.ifPresent(ciReport::add);
		}

		final List<Build> finishedBuilds = ciReport.getFinishedBuilds().collect(Collectors.toList());
		final int numFinishedBuilds = finishedBuilds.size();
		if (numFinishedBuilds > 0) {
			final String lastHash = finishedBuilds.get(numFinishedBuilds - 1).commitHash;

			final List<Build> oldBuilds = finishedBuilds.stream().filter(build -> !build.commitHash.equals(lastHash)).collect(Collectors.toList());
			if (!oldBuilds.isEmpty()) {
				LOG.info("Deleting {} unnecessary branches for PullRequest {}, since newer commits are present. Retaining branches for commit {}.",
						oldBuilds.size(),
						formatPullRequestID(pullRequestID),
						lastHash);

				oldBuilds.stream()
						.peek(core::deleteCiBranch)
						.map(Build::asDeleted)
						.forEach(ciReport::add);
			}
		}

		if (ciReport.getBuilds().count() > 0) {
			core.updateCiReport(ciReport, postNewCommentForCIReport);
		}
	}
}
