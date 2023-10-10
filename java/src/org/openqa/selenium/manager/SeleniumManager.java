// Licensed to the Software Freedom Conservancy (SFC) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The SFC licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.
package org.openqa.selenium.manager;

import static org.openqa.selenium.Platform.MAC;
import static org.openqa.selenium.Platform.WINDOWS;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.openqa.selenium.Beta;
import org.openqa.selenium.Capabilities;
import org.openqa.selenium.MutableCapabilities;
import org.openqa.selenium.Platform;
import org.openqa.selenium.Proxy;
import org.openqa.selenium.WebDriverException;
import org.openqa.selenium.json.Json;
import org.openqa.selenium.json.JsonException;
import org.openqa.selenium.manager.SeleniumManagerOutput.Result;
import org.openqa.selenium.os.CommandLine;

/**
 * This implementation is still in beta, and may change.
 *
 * <p>The Selenium-Manager binaries are distributed in a JAR file
 * (org.openqa.selenium:selenium-manager) for the Java binding language. Since these binaries are
 * compressed within these JAR, we need to serialize the proper binary for the current platform
 * (Windows, macOS, or Linux) as an executable file. To implement this we use a singleton pattern,
 * since this way, we have a single instance in the JVM, and we reuse the resulting binary for all
 * the calls to the Selenium Manager singleton during all the Java process lifetime, deleting the
 * binary (stored as a local temporal file) on runtime shutdown.
 */
@Beta
public class SeleniumManager {

  private static final Logger LOG = Logger.getLogger(SeleniumManager.class.getName());


  // IMPORTANT: This version needs to be synchronized before each release.
  // Alternatively, we can try to use Bazel to automate this task
  private static final String SELENIUM_MANAGER_VERSION = "0.4.12";

  private static final String SELENIUM_MANAGER = "selenium-manager";
  private static final String DEFAULT_CACHE_PATH = "~/.cache/selenium";
  private static final String BINARY_PATH_FORMAT = "/manager/%s/%s";
  private static final String HOME = "~";
  private static final String CACHE_PATH_ENV = "SE_CACHE_PATH";

  private static final String EXE = ".exe";
  private static final String INFO = "INFO";
  private static final String WARN = "WARN";
  private static final String DEBUG = "DEBUG";

  private static volatile SeleniumManager manager;

  private final String managerPath = System.getenv("SE_MANAGER_PATH");
  private Path binary = managerPath == null ? null : Paths.get(managerPath);

  /** Wrapper for the Selenium Manager binary. */
  private SeleniumManager() {
  }

  public static SeleniumManager getInstance() {
    if (manager == null) {
      synchronized (SeleniumManager.class) {
        if (manager == null) {
          manager = new SeleniumManager();
        }
      }
    }
    return manager;
  }

  /**
   * Executes a process with the given arguments.
   *
   * @param arguments the file and arguments to execute.
   * @return the standard output of the execution.
   */
  private static Result runCommand(Path binary, List<String> arguments) {
    LOG.fine(String.format("Executing Process: %s", arguments));

    String output;
    int code;
    try {
      CommandLine command =
          new CommandLine(binary.toAbsolutePath().toString(), arguments.toArray(new String[0]));
      command.executeAsync();
      command.waitFor();
      if (command.isRunning()) {
        LOG.warning("Selenium Manager did not exit");
      }
      code = command.getExitCode();
      output = command.getStdOut();
    } catch (Exception e) {
      throw new WebDriverException("Failed to run command: " + arguments, e);
    }
    SeleniumManagerOutput jsonOutput = null;
    JsonException failedToParse = null;
    String dump = output;
    if (!output.isEmpty()) {
      try {
        jsonOutput = new Json().toType(output, SeleniumManagerOutput.class);
        jsonOutput
            .getLogs()
            .forEach(
                logged -> {
                  Level currentLevel =
                      logged.getLevel() == Level.INFO ? Level.FINE : logged.getLevel();
                  LOG.log(currentLevel, logged.getMessage());
                });
        dump = jsonOutput.getResult().getMessage();
      } catch (JsonException e) {
        failedToParse = e;
      }
    }
    if (code != 0) {
      throw new WebDriverException(
          "Command failed with code: " + code + ", executed: " + arguments + "\n" + dump,
          failedToParse);
    } else if (failedToParse != null || jsonOutput == null) {
      throw new WebDriverException(
          "Failed to parse json output, executed: " + arguments + "\n" + dump, failedToParse);
    }
    return jsonOutput.getResult();
  }

  /**
   * Determines the correct Selenium Manager binary to use.
   *
   * @return the path to the Selenium Manager binary.
   */
  private synchronized Path getBinary() {
    if (binary == null) {
      try {
        Platform current = Platform.getCurrent();
        String folder = "linux";
        String extension = "";
        if (current.is(WINDOWS)) {
          extension = EXE;
          folder = "windows";
        } else if (current.is(MAC)) {
          folder = "macos";
        }

        binary = getBinaryInCache(SELENIUM_MANAGER + extension);
        if (!binary.toFile().exists()) {
          String binaryPathInJar = String.format("%s/%s%s", folder, SELENIUM_MANAGER, extension);
          try (InputStream inputStream = this.getClass().getResourceAsStream(binaryPathInJar)) {
            binary.getParent().toFile().mkdirs();
            Files.copy(inputStream, binary);
          }
          binary.toFile().setExecutable(true);
        }

      } catch (Exception e) {
        throw new WebDriverException("Unable to obtain Selenium Manager Binary", e);
      }
    } else if (!Files.exists(binary)) {
      throw new WebDriverException(
          String.format("Unable to obtain Selenium Manager Binary at: %s", binary));
    }
    binary.toFile().setExecutable(true);

    LOG.fine(String.format("Selenium Manager binary found at: %s", binary));

    return binary;
  }

  /**
   * Returns the browser binary path when present in the vendor options
   *
   * @param options browser options used to start the session
   * @return the browser binary path when present, only Chrome/Firefox/Edge
   */
  private String getBrowserBinary(Capabilities options) {
    List<String> vendorOptionsCapabilities =
        Arrays.asList("moz:firefoxOptions", "goog:chromeOptions", "ms:edgeOptions");
    for (String vendorOptionsCapability : vendorOptionsCapabilities) {
      if (options.asMap().containsKey(vendorOptionsCapability)) {
        try {
          @SuppressWarnings("unchecked")
          Map<String, Object> vendorOptions =
              (Map<String, Object>) options.getCapability(vendorOptionsCapability);
          return (String) vendorOptions.get("binary");
        } catch (Exception e) {
          LOG.warning(
              String.format(
                  "Exception while retrieving the browser binary path. %s: %s",
                  options, e.getMessage()));
        }
      }
    }
    return null;
  }

  /**
   * Determines the location of the correct driver.
   *
   * @param options Browser Options instance.
   * @return the location of the driver.
   */
  public Result getDriverPath(Capabilities options, boolean offline) {
    Path binaryFile = getBinary();
    if (binaryFile == null) {
      return null;
    }

    List<String> arguments = new ArrayList<>();
    arguments.add("--browser");
    arguments.add(options.getBrowserName());
    arguments.add("--output");
    arguments.add("json");

    if (!options.getBrowserVersion().isEmpty()) {
      arguments.add("--browser-version");
      arguments.add(options.getBrowserVersion());
      // We know the browser binary path, we don't need the browserVersion.
      // Useful when "beta" is specified as browserVersion, but the browser driver cannot match it.
      if (options instanceof MutableCapabilities) {
        ((MutableCapabilities) options).setCapability("browserVersion", (String) null);
      }
    }

    String browserBinary = getBrowserBinary(options);
    if (browserBinary != null && !browserBinary.isEmpty()) {
      arguments.add("--browser-path");
      arguments.add(browserBinary);
    }

    if (getLogLevel().intValue() <= Level.FINE.intValue()) {
      arguments.add("--debug");
    }

    if (offline) {
      arguments.add("--offline");
    }

    Proxy proxy = Proxy.extractFrom(options);
    if (proxy != null) {
      if (proxy.getSslProxy() != null) {
        arguments.add("--proxy");
        arguments.add(proxy.getSslProxy());
      } else if (proxy.getHttpProxy() != null) {
        arguments.add("--proxy");
        arguments.add(proxy.getHttpProxy());
      }
    }

    Result result = runCommand(binaryFile, arguments);
    LOG.fine(
        String.format(
            "Using driver at location: %s, browser at location %s",
            result.getDriverPath(), result.getBrowserPath()));
    return result;
  }

  private Level getLogLevel() {
    Level level = LOG.getLevel();
    if (level == null && LOG.getParent() != null) {
      level = LOG.getParent().getLevel();
    }
    if (level == null) {
      return Level.INFO;
    }
    return level;
  }

  private Path getBinaryInCache(String binaryName) {
    String cachePath = DEFAULT_CACHE_PATH.replace(HOME, System.getProperty("user.home"));

    // Look for cache path as env
    String cachePathEnv = System.getenv(CACHE_PATH_ENV);
    if (cachePathEnv != null) {
      cachePath = cachePathEnv;
    }

    return Paths.get(cachePath + String.format(BINARY_PATH_FORMAT, SELENIUM_MANAGER_VERSION, binaryName));
  }
}
