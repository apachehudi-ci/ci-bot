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

import com.ververica.utils.ConsumerWithException;

import java.io.IOException;
import java.util.Date;

public class GitHubComment {
	private final long id;
	private final String commenter;
	private final String comment;
	private final Date createdAt;
	private final ConsumerWithException<String, IOException> updateFunction;

	public GitHubComment(long id, String commenter, String comment, Date createdAt, ConsumerWithException<String, IOException> updateFunction) {
		this.id = id;
		this.commenter = commenter;
		this.comment = comment;
		this.createdAt = createdAt;
		this.updateFunction = updateFunction;
	}

	public long getId() {
		return id;
	}

	public String getCommenter() {
		return commenter;
	}

	public String getCommentText() {
		return comment;
	}

	public Date getCreatedAt() {
		return createdAt;
	}

	public void update(String comment) throws IOException {
		updateFunction.accept(comment);
	}
}
