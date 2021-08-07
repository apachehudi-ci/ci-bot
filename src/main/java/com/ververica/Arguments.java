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

import com.beust.jcommander.Parameter;

/**
 * Command-lind arguments for the {@link CiBot}.
 */
final class Arguments {
	@Parameter(
			names = {"--observedRepository", "-or"},
			required = true,
			description = "The repo to observe.")
	String observedRepository;

	@Parameter(
			names = {"--ciRepository", "-cr"},
			required = true,
			description = "The repo to run the CI.")
	String ciRepository;

	@Parameter(
			names = {"--user", "-u"},
			required = true,
			description = "The GitHub account name to use for posting results.")
	String username;

	@Parameter(
			names = {"--githubToken", "-gt"},
			required = true,
			description = "The GitHub authorization token with write permissions for the CI repository.")
	String githubToken;

	@Parameter(
			names = {"--travisToken", "-tt"},
			required = false,
			description = "The Travis authorization token with cancel permissions for the CI repository.")
	String travisToken;

	@Parameter(
			names = {"--azureToken", "-at"},
			required = false,
			description = "The Azure authorization token with cancel permissions for the CI repository.")
	String azureToken;

	@Parameter(
			names = {"--checkerNamePattern", "-cnp"},
			required = false,
			description = "A regex to select github checker runs to process.")
	String checkerNamePattern = ".*";

	@Parameter(
			names = {"--interval", "-i"},
			required = false,
			description = "The polling interval in seconds. If this parameter is <=0 then the bot will only do a single poll.")
	int pollingIntervalInSeconds = 300;

	@Parameter(
			names = {"--backlog", "-b"},
			required = false,
			description = "The number of hours the bot should go back in time when processing pull requests on startup." +
					"This should usually be inHours(currentTime - lastTimeBotShutdown)."
	)
	int backlogHours = 24;

	@Parameter(
			names = {"--newComment", "-nc"},
			required = false,
			description = "Set this flag to make CI report comment posted as new one every time. Otherwise, it updates the existing comment."
	)
	boolean postNewCommentForCIReport = false;

	@Parameter(
			names = {"--help", "-h"},
			help = true,
			hidden = true)
	boolean help = false;
}
