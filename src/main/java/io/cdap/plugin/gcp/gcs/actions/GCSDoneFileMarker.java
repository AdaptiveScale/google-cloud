/*
 * Copyright © 2021 Cask Data, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */

package io.cdap.plugin.gcp.gcs.actions;

import com.google.auth.Credentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageException;
import com.google.common.base.Strings;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Macro;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.batch.BatchActionContext;
import io.cdap.cdap.etl.api.batch.PostAction;
import io.cdap.plugin.common.batch.action.Condition;
import io.cdap.plugin.common.batch.action.ConditionConfig;
import io.cdap.plugin.gcp.common.GCPConfig;
import io.cdap.plugin.gcp.common.GCPUtils;
import io.cdap.plugin.gcp.gcs.GCSPath;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

/**
 * A post action plugin that creates a marker file with a given name in case of a succeeded, failed or completed
 * pipeline.
 */
@Plugin(type = PostAction.PLUGIN_TYPE)
@Name(GCSDoneFileMarker.NAME)
@Description("Creates a marker file with a given name in case of a succeeded, failed or completed pipeline.")
public class GCSDoneFileMarker extends PostAction {
  public static final String NAME = "GCSDoneFileMarker";
  public Config config;

  /**
   * Creates a file marker
   *
   * @param project
   * @param path
   * @param serviceAccount
   * @param isServiceAccountFilePath
   * @param context
   * @throws IOException
   */
  private static void createFileMarker(String project, GCSPath path, String serviceAccount,
                                       Boolean isServiceAccountFilePath,
                                       BatchActionContext context) {

    String cmekKey = context.getArguments().get(GCPUtils.CMEK_KEY);
    Credentials credentials = null;
    if (serviceAccount != null) {
      try {
        credentials = GCPUtils.loadServiceAccountCredentials(serviceAccount, isServiceAccountFilePath);
      } catch (IOException e) {
        throw new RuntimeException(String.format("Failed to load credentials from path %s.", serviceAccount), e);
      }
    }

    Storage storage = GCPUtils.getStorage(project, credentials);

    if (storage.get(path.getBucket()) == null) {
      try {
        GCPUtils.createBucket(storage, path.getBucket(), null, cmekKey);
      } catch (StorageException e) {
        throw new RuntimeException(String.format("Failed to create bucket %s.", path.getBucket()), e);
      }
    }

    BlobId markerFileId = BlobId.of(path.getBucket(), path.getName());
    BlobInfo markerFileInfo = BlobInfo.newBuilder(markerFileId).build();
    try {
    storage.create(markerFileInfo, "".getBytes(StandardCharsets.UTF_8));
    } catch (StorageException e) {
      throw new RuntimeException(String.format("Failed to create the marker file at %s.", path.getUri()), e);
    }
  }

  @Override
  public void configurePipeline(PipelineConfigurer pipelineConfigurer) {
    config.validate(pipelineConfigurer.getStageConfigurer().getFailureCollector());
  }

  @Override
  public void run(BatchActionContext batchActionContext) throws IOException {
    config.validate(batchActionContext.getFailureCollector());
    if (!config.shouldRun(batchActionContext)) {
      return;
    }
    GCSPath markerFilePath = config.getPath();
    String serviceAccount = config.getServiceAccount();
    createFileMarker(config.getProject(), markerFilePath, serviceAccount, config.isServiceAccountFilePath(),
                     batchActionContext);
  }

  /**
   * Config for the plugin.
   */
  public static class Config extends GCPConfig {
    public static final String NAME_PATH = "path";
    public static final String NAME_RUN_CONDITION = "runCondition";

    @Name(NAME_RUN_CONDITION)
    @Description("When to run the action. Must be 'completion', 'success', or 'failure'. Defaults to 'completion'. " +
      "If set to 'completion', the action will be executed and a marker file will get created regardless of whether " +
      "the pipeline run succeeded or failed. If set to 'success', the action will get executed and the marker file " +
      "will get created only if the pipeline run succeeded. If set to 'failure', the action will get executed and " +
      "the marker file will get created only if the pipeline run failed")
    public String runCondition;

    @Name(NAME_PATH)
    @Description("Path where the marker file will appear.")
    @Macro
    private String path;

    Config() {
      super();
      this.runCondition = Condition.SUCCESS.name();
    }

    public GCSPath getPath() {
      return GCSPath.from(path);
    }

    void validate(FailureCollector collector) {
      if (!this.containsMacro(NAME_RUN_CONDITION)) {
        new ConditionConfig(runCondition).validate(collector);
      }

      if (!containsMacro(NAME_PATH)) {
        try {
          getPath();
        } catch (IllegalArgumentException e) {
          collector.addFailure(e.getMessage(), "Please provide a valid path.").withConfigProperty(NAME_PATH);
        }
      }

      Boolean isServiceAccountFilePath = isServiceAccountFilePath();
      if (isServiceAccountFilePath != null &&
        isServiceAccountFilePath && !containsMacro(NAME_SERVICE_ACCOUNT_FILE_PATH) &&
        Strings.isNullOrEmpty(getServiceAccountFilePath())) {
        collector
          .addFailure("Required property 'Service Account File Path' has no value.",
                      "Provide the path to the Service Account.")
          .withConfigProperty(NAME_SERVICE_ACCOUNT_FILE_PATH);
      }

      Boolean isServiceAccountJson = isServiceAccountJson();
      if (isServiceAccountJson != null && isServiceAccountJson && !containsMacro(NAME_SERVICE_ACCOUNT_JSON) &&
        Strings.isNullOrEmpty(getServiceAccountJson())) {
        collector
          .addFailure("Required property 'Service Account JSON' has no value.",
                      "Provide the Service Account JSON content.")
          .withConfigProperty(NAME_SERVICE_ACCOUNT_JSON);
      }

      collector.getOrThrowException();
    }

    public boolean shouldRun(BatchActionContext actionContext) {
      return new ConditionConfig(runCondition).shouldRun(actionContext);
    }
  }
}
