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

import java.io.IOException;
import java.util.Date;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;
import java.util.stream.Stream;

public interface GitHubActions extends AutoCloseable {
	void submitComment(String repository, int pullRequestID, String comment) throws IOException;

	void deleteComment(String repositoryName, int pullRequestID, long commentId) throws IOException;

	Iterable<GitHubCheckerStatus> getCommitState(String repositoryName, String commitHash, Pattern checkerNamePattern);

	Iterable<String> getBranches(String repositoryName) throws IOException;

	Iterable<GitHubComment> getComments(String repositoryName, int pullRequestID, String username) throws IOException;

	Stream<GitHubComment> getComments(String repositoryName, int pullRequestID, Pattern pattern) throws IOException;

	Iterable<GithubPullRequest> getRecentlyUpdatedOpenPullRequests(String repositoryName, Date since) throws IOException;

	boolean isPullRequestClosed(String repositoryName, int pullRequestID) throws IOException;

	void close();
}
