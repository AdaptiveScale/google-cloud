/*
 * Copyright © 2020 AdaptiveScale, Inc.
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

import com.google.cloud.storage.Blob;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.StorageOptions;
import com.google.common.base.Joiner;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonSyntaxException;
import io.cdap.cdap.api.annotation.Description;
import io.cdap.cdap.api.annotation.Name;
import io.cdap.cdap.api.annotation.Plugin;
import io.cdap.cdap.etl.api.FailureCollector;
import io.cdap.cdap.etl.api.PipelineConfigurer;
import io.cdap.cdap.etl.api.StageConfigurer;
import io.cdap.cdap.etl.api.action.Action;
import io.cdap.cdap.etl.api.action.ActionContext;
import io.cdap.plugin.gcp.common.GCPUtils;
import io.cdap.plugin.gcp.gcs.GCSPath;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * This class <code>GCSArgumentSetter</code> get json file configuration from GCS.
 *
 * <p>The plugin provides the ability to map json properties as pipeline arguments name and columns
 * values as pipeline arguments Following is JSON configuration that can be provided. <code> {
 * "arguments" : [ { "name" : "input.path", "value" :"/data/sunny_feeds/master"}, { "name" :
 * "parse.schema", "value" : [ { "name" : "fname" }, { "name" : "age"}, { "name" : "salary"} ] }, {
 * "name" : "directives","value" : [ "parse-as-json body", "columns-replace s/body_//g", "keep
 * f1,f2" ]} ] }
 * </code>
 */
@Plugin(type = Action.PLUGIN_TYPE)
@Name(GCSArgumentSetter.NAME)
@Description("Argument setter for dynamically configuring pipeline from GCS")
public final class GCSArgumentSetter extends Action {

  public static final String NAME = "GCSArgumentSetter";
  private GCSArgumentSetterConfig config;

  @Override
  public void configurePipeline(PipelineConfigurer configurer) {
    super.configurePipeline(configurer);
    StageConfigurer stageConfigurer = configurer.getStageConfigurer();
    FailureCollector collector = stageConfigurer.getFailureCollector();
    config.validate(collector);
  }

  @Override
  public void run(ActionContext context) throws Exception {
    config.validateProperties(context.getFailureCollector());
    String fileContent = GCSArgumentSetter.getContent(config);

    try {
      Configuration configuration =
          new GsonBuilder().create().fromJson(fileContent, Configuration.class);
      for (Argument argument : configuration.getArguments()) {
        String name = argument.getName();
        String value = argument.getValue();
        if (value != null) {
          context.getArguments().set(name, value);
        } else {
          throw new RuntimeException(
              "Configuration '" + name + "' is null. Cannot set argument to null.");
        }
      }
    } catch (JsonSyntaxException e) {
      throw new RuntimeException(
          String.format(
              "Could not parse response from '%s': %s", config.getPath(), e.getMessage()));
    }
  }

  private static Storage getStorage(GCSArgumentSetterConfig config) throws IOException {
    return StorageOptions.newBuilder()
        .setProjectId(config.getProject())
        .setCredentials(GCPUtils.loadServiceAccountCredentials(config.getServiceAccountFilePath()))
        .build()
        .getService();
  }

  public static String getContent(GCSArgumentSetterConfig config) throws IOException {
    Storage storage = getStorage(config);
    GCSPath path = config.getPath();
    Blob blob = storage.get(path.getBucket(), path.getName());
    return new String(blob.getContent());
  }

  private final class Configuration {

    private List<Argument> arguments;

    public List<Argument> getArguments() {
      return arguments;
    }
  }

  private static final class Argument {

    private String name;
    private JsonElement value;

    public String getName() {
      return name;
    }

    public void setName(String name) {
      this.name = name;
    }

    public String getValue() {
      if (value == null) {
        throw new IllegalArgumentException("Null Argument value for name '" + name + "'");
      }

      if (value.isJsonArray()) {
        List<String> values = new ArrayList<>();
        for (JsonElement v : value.getAsJsonArray()) {
          values.add(v.getAsString());
        }
        return Joiner.on(",").join(values);
      }

      if (value.isJsonObject()) {
        List<String> values = new ArrayList<>();
        for (Map.Entry<String, JsonElement> entry : value.getAsJsonObject().entrySet()) {
          values.add(String.format("%s=%s", entry.getKey(), entry.getValue().getAsString()));
        }
        return Joiner.on(";").join(values);
      }

      if (value.isJsonPrimitive()) {
        return value.getAsString();
      }

      throw new IllegalArgumentException("Invalid argument value '" + value + "'");
    }
  }
}
